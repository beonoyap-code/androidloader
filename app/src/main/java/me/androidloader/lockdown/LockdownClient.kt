package me.androidloader.lockdown

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.androidloader.pairing.PairingIdentity
import me.androidloader.pairing.PairingRecord
import me.androidloader.pairing.Pem
import me.androidloader.plist.PlistCodec
import me.androidloader.plist.PlistValue
import me.androidloader.plist.asData
import me.androidloader.plist.asString
import me.androidloader.plist.plistBool
import me.androidloader.plist.plistDict
import me.androidloader.plist.plistString
import me.androidloader.plist.string
import me.androidloader.pairing.HostIdentity
import me.androidloader.usbmux.ServiceFraming
import me.androidloader.usbmux.UsbmuxClient
import me.androidloader.usbmux.UsbmuxProtocol
import me.androidloader.usbmux.UsbmuxSocketChannel
import me.androidloader.usbmux.readOneMessage
import java.io.Closeable

/** Raised when lockdownd reports a failure. */
class LockdownException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** lockdownd's value domain, which decides whether pairing is even possible. */
data class QueryType(
    val type: String,
    val requiresPairing: Boolean,
) {
    companion object {
        const val TYPE_NORMAL = "com.apple.mobile.lockdown"
        const val TYPE_RECOVERY = "com.apple.mobile.lockdown_recovery"
    }
}

/**
 * A live session with lockdownd.
 *
 * The flow is entirely plaintext until the very end, which is the part that is
 * easy to get wrong:
 *
 *  1. connect over usbmux
 *  2. read the device's public key with `GetValue`
 *  3. mint a root and host certificate locally
 *  4. send `Pair` **in plaintext**, carrying those certificates
 *  5. the device shows its trust prompt and returns the full record
 *  6. `StartSession`; TLS is enabled afterwards, for later requests
 *
 * There is no `StartPairing`, and no TLS before step 4. An earlier version of this
 * code began a TLS handshake immediately, with a trust manager anchored on the
 * pairing root, which at that point we do not have. lockdownd received a
 * ClientHello where it expected a property list, the handshake failed, and because
 * the trust prompt is raised by the `Pair` request, no prompt ever appeared.
 */
class LockdownClient private constructor(
    private val device: UsbmuxProtocol.Device,
    private val channel: UsbmuxSocketChannel,
) : Closeable {

    /** Set once a privileged session is open; required for install and file access. */
    var sessionId: String? = null
        private set

    /** The pairing record in force, once one is known. */
    var record: PairingRecord? = null
        private set

    val boundDevice: UsbmuxProtocol.Device get() = device

    /** Opens a lockdownd connection to [device] over the plain socket. */
    suspend fun connect(
        usbmux: UsbmuxClient,
        device: UsbmuxProtocol.Device,
    ): LockdownClient = withContext(Dispatchers.IO) {
        val socket = usbmux.connectTo(device, UsbmuxClient.PORT_LOCKDOWN)
        LockdownClient(device, UsbmuxSocketChannel(socket))
    }

    /**
     * Binds a client to an already-open channel and device.
     *
     * The usbmux path uses [connect]. This exists so the protocol can be driven
     * over a socket obtained elsewhere, which is what the tests use in place of
     * real hardware.
     */
    companion object {
        fun onChannel(
            device: UsbmuxProtocol.Device,
            channel: UsbmuxSocketChannel,
        ): LockdownClient = LockdownClient(device, channel)

        /** Identifies this host to lockdownd. */
        const val LABEL = "androidloader"

        /** Turns a lockdownd error code into something the user can act on. */
        fun lockdownErrorMessage(code: String, detail: String?): String = when (code) {
            "PasswordProtected" ->
                "The iPhone is locked with a passcode. Unlock it, then try again."
            "InvalidHostID" ->
                "This phone is no longer trusted. Pair again to fix it."
            "InvalidService" -> "The iPhone refused the requested service."
            "SetSessionDisabled" ->
                "Developer Mode is off. Turn it on in Settings, then reconnect."
            "DeviceLockComplete" -> "The iPhone is locked. Unlock it and try again."
            "UserDeniedPairing" ->
                "Pairing was declined on the iPhone. Tap Pair and then Trust when prompted."
            else -> buildString {
                append("lockdownd refused the request: ").append(code)
                if (!detail.isNullOrBlank()) append(" (").append(detail).append(')')
            }
        }
    }

    /** Sends one plist request over the plain channel and returns the reply. */
    private suspend fun exchange(body: Map<String, PlistValue>): Map<String, PlistValue> =
        withContext(Dispatchers.IO) {
            val framed = ServiceFraming.plistFrame(
                buildMap {
                    putAll(body)
                    put("Label", plistString(LABEL))
                    put("ProtocolVersion", plistString("2"))
                    sessionId?.let { put("SessionID", plistString(it)) }
                },
            )
            channel.write(framed, 0, framed.size)
            val parsed = PlistCodec.decodeDict(channel.readOneMessage())
            val error = parsed.string("Error")
            if (error != null) {
                throw LockdownException(lockdownErrorMessage(error, parsed.string("ErrorDescription")))
            }
            parsed
        }

    /** Reports the value domain lockdownd is in. */
    suspend fun queryType(): QueryType {
        val reply = exchange(mapOf("Request" to plistString("QueryType")))
        val type = reply["Type"]?.asString
            ?: throw LockdownException("lockdownd did not report a value domain")
        val prohibited = reply["PairingProhibited"]
        return QueryType(
            type = type,
            requiresPairing = prohibited !is PlistValue.BoolValue || prohibited.value,
        )
    }

    /**
     * Pairs with the device, in plaintext.
     *
     * The trust prompt appears on the iPhone while this is in flight, so it must
     * not be called with the device locked, and a refusal comes back as an error
     * rather than a timeout.
     *
     * @param identifiers the host id and system BUID to register. Reusing the same
     *   values across pairings keeps the device's trust list from growing.
     */
    suspend fun pair(
        hostId: String = PairingIdentity.newHostId(),
        systemBuid: String = PairingIdentity.newSystemBuid(),
    ): PairingRecord = withContext(Dispatchers.IO) {
        // The device's public key is needed to finish the record after pairing, and
        // reading it now is what the reference does before generating anything.
        val devicePublicKey = try {
            request("DevicePublicKey")
                ?: throw LockdownException("the iPhone did not return its public key")
        } catch (e: LockdownException) {
            throw LockdownException(
                "The iPhone refused to share its public key. Make sure it is unlocked " +
                    "and that Developer Mode is on.",
                e,
            )
        }

        // The Wi-Fi address is read before pairing on purpose: the reference notes
        // that asking for it afterwards fails on iOS 7-era devices.
        val wifi = runCatching { request("WiFiAddress") }.getOrNull()

        val identity = HostIdentity.generate()

        val seed = PairingRecord(
            deviceCertificate = ByteArray(0),
            devicePublicKey = devicePublicKey,
            hostCertificate = Pem.encode(identity.hostCertificate),
            hostPrivateKey = Pem.encodePrivate(identity.hostPrivateKey),
            hostPublicKey = Pem.encodePublic(identity.hostPublicKey),
            rootCertificate = Pem.encode(identity.rootCertificate),
            rootPrivateKey = Pem.encodePrivate(identity.rootPrivateKey),
            hostId = hostId,
            systemBuid = systemBuid,
            wifiMacAddress = wifi?.let { String(it, Charsets.UTF_8) },
        )

        val reply = exchange(
            mapOf(
                "Request" to plistString("Pair"),
                "PairRecord" to PlistValue.DictValue(seed.toPairingRequest()),
                "PairingOptions" to plistDict(
                    mapOf("ExtendedPairingErrors" to plistBool(true)),
                ),
            ),
        )

        val returned = (reply["PairRecordData"] as? PlistValue.DataValue)?.value
        val adopted = returned?.let { PairingRecord.parse(it) }
            ?: throw LockdownException(
                "The iPhone accepted the pairing but returned no record. Erase it from " +
                    "Settings, General, Transfer or Reset, Reset, and try again.",
            )
        record = adopted
        adopted
    }

    /** Reads one device property. */
    suspend fun getValue(key: String): PlistValue? =
        exchange(mapOf("Request" to plistString("GetValue"), "Key" to plistString(key)))[key]

    suspend fun request(key: String): ByteArray? = getValue(key)?.asData

    suspend fun getString(key: String): String? = getValue(key)?.asString

    /** Every readable device property. */
    suspend fun deviceInfo(): Map<String, String> = withContext(Dispatchers.IO) {
        exchange(mapOf("Request" to plistString("GetValue")))
            .mapNotNull { (k, v) -> v.asString?.let { k to it } }
            .toMap()
    }

    /**
     * Opens a privileged session.
     *
     * Fails when the device is locked or the host is no longer trusted, which are
     * the two conditions that actually produce this.
     */
    suspend fun startSession() = withContext(Dispatchers.IO) {
        val hostId = record?.hostId
            ?: throw LockdownException("no pairing record; pair before starting a session")
        val reply = exchange(
            mapOf(
                "Request" to plistString("StartSession"),
                "HostID" to plistString(hostId),
            ),
        )
        sessionId = reply.string("SessionID") ?: throw LockdownException(
            "the iPhone refused to open a session. It is most likely locked, or this " +
                "phone is no longer trusted. Pair again if that persists.",
        )
    }

    /**
     * Pairs if needed, then opens a session.
     *
     * The common path for the caller: a device with no record is paired, and one
     * that already has a record is used as is.
     */
    suspend fun openSession(
        existing: PairingRecord?,
        hostId: String,
        systemBuid: String,
    ): PairingRecord = withContext(Dispatchers.IO) {
        val established = if (existing != null) {
            record = existing
            existing
        } else {
            pair(hostId, systemBuid)
        }
        startSession()
        established
    }

    override fun close() {
        channel.close()
    }
}
