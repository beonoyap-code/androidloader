package me.androidloader.lockdown

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.androidloader.pairing.HostCertificateSet
import me.androidloader.pairing.PairingRecord
import me.androidloader.plist.PlistCodec
import me.androidloader.plist.PlistValue
import me.androidloader.plist.asString
import me.androidloader.plist.plistBool
import me.androidloader.plist.plistString
import me.androidloader.plist.string
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
 * A live session with lockdownd on one device.
 *
 * lockdownd gates every privileged operation, and the sequence is strictly ordered:
 *
 *  1. connect over usbmux
 *  2. `StartPairing`, which switches the socket to TLS using the host certificate
 *  3. `Pair`, registering this host with the device
 *  4. `StartSession`, opening the privileged session that install and file
 *     operations later require
 *
 * The pairing request is special: it must be written to the *plain* channel
 * before TLS is switched on. Once paired, every subsequent request goes through the
 * TLS channel. Modelling this as a transport swap on one object keeps the ordering
 * explicit instead of spreading it across separate types.
 */
class LockdownClient private constructor(
    private val device: UsbmuxProtocol.Device,
    private var transport: Transport,
) : Closeable {

    /** Abstraction over the plain and TLS phases so requests have one code path. */
    private sealed interface Transport : Closeable {
        fun write(buffer: ByteArray, offset: Int, length: Int)
        suspend fun readMessage(): ByteArray
    }

    private class Plain(val channel: UsbmuxSocketChannel) : Transport {
        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            channel.write(buffer, offset, length)

        override suspend fun readMessage(): ByteArray = channel.readOneMessage()
        override fun close() = channel.close()
    }

    private class Secure(private val channel: TlsChannel) : Transport {
        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            channel.write(buffer, offset, length)

        override suspend fun readMessage(): ByteArray = channel.readOne()
        override fun close() = channel.close()
    }

    /** Set once a privileged session is open; required for install and file access. */
    var sessionId: String? = null
        private set

    /** The pairing record in force for this connection. */
    var record: PairingRecord? = null
        private set

    /** The device this session is bound to. */
    val boundDevice: UsbmuxProtocol.Device get() = device

    /**
     * Opens a lockdownd connection to [device].
     *
     * No TLS is established yet: the caller decides whether to pair, start a
     * session, or issue an unpaired request such as `QueryType`.
     */
    suspend fun connect(
        usbmux: UsbmuxClient,
        device: UsbmuxProtocol.Device,
    ): LockdownClient = withContext(Dispatchers.IO) {
        val socket = usbmux.connectTo(device, UsbmuxClient.PORT_LOCKDOWN)
        LockdownClient(device, Plain(UsbmuxSocketChannel(socket)))
    }

    companion object {
        /**
         * Binds a client to an already-open channel and device.
         *
         * The usbmux path uses [connect], which owns the socket it opens. This
         * factory exists so the protocol can be driven over a socket obtained
         * elsewhere, which is what the tests use in place of real hardware.
         */
        fun onChannel(
            device: UsbmuxProtocol.Device,
            channel: UsbmuxSocketChannel,
        ): LockdownClient = LockdownClient(device, Plain(channel))

        /** Identifies this host to lockdownd; appears in the device's trust list. */
        const val LABEL = "androidloader"

        /** Turns a lockdownd error code into something the user can act on. */
        fun lockdownErrorMessage(code: String, detail: String?): String = when (code) {
            "PasswordProtected" ->
                "The iPhone is locked with a passcode. Unlock it, then try again."
            "InvalidHostID" ->
                "This Android device is no longer trusted. Erase the pairing record and pair again."
            "InvalidService" -> "The iPhone refused the requested service."
            "SetSessionDisabled" ->
                "Developer Mode or the lockdown service is disabled. Turn on Developer Mode " +
                    "in iOS Settings, then reconnect."
            "DeviceLockComplete" -> "The iPhone is locked. Unlock it and try again."
            else -> buildString {
                append("lockdownd error ")
                append(code)
                if (!detail.isNullOrBlank()) append(": ").append(detail)
            }
        }
    }

    /**
     * Sends one plist request and returns the response body.
     *
     * `Label` and `ProtocolVersion` are always present because current lockdownd
     * rejects requests without them.
     */
    suspend fun request(body: Map<String, PlistValue>): Map<String, PlistValue> =
        withContext(Dispatchers.IO) {
            val full = buildMap {
                putAll(body)
                put("Label", plistString(LABEL))
                put("ProtocolVersion", plistString("2"))
                sessionId?.let { put("SessionID", plistString(it)) }
            }
            val framed = ServiceFraming.plistFrame(full)
            transport.write(framed, 0, framed.size)
            val parsed = PlistCodec.decodeDict(transport.readMessage())
            val error = parsed.string("Error")
            if (error != null) {
                throw LockdownException(
                    lockdownErrorMessage(error, parsed.string("ErrorDescription")),
                )
            }
            parsed
        }

    /** Reports which value domain lockdownd is in and whether pairing is required. */
    suspend fun queryType(): QueryType {
        val reply = request(mapOf("Request" to plistString("QueryType")))
        val type = reply["Type"]?.asString
            ?: throw LockdownException("lockdownd did not report a value domain")
        val prohibited = reply["PairingProhibited"]
        return QueryType(
            type = type,
            // Absent means pairing is allowed, which is the common case.
            requiresPairing = prohibited !is PlistValue.BoolValue || prohibited.value,
        )
    }

    /**
     * Performs the TLS handshake, registers this host, and switches the transport.
     *
     * The user must accept the trust prompt on the iPhone while this runs. A
     * refusal arrives as an [LockdownException] rather than a hang.
     *
     * @return the pairing record lockdownd issued, which supersedes any cached one.
     */
    suspend fun pair(cached: PairingRecord?): PairingRecord = withContext(Dispatchers.IO) {
        val plain = transport as? Plain
            ?: throw LockdownException("this connection has already been paired")
        // A cached record supplies the host identity; otherwise a fresh one is
        // minted. Either way the device returns the authoritative record below.
        val seed = cached ?: HostCertificateSet.generate().toRecord(device)
        val tls = LockdownTls.wrap(plain.channel, seed, device)
        tls.handshake()

        val reply = try {
            exchangeTls(
                tls,
                mapOf(
                    "Request" to plistString("Pair"),
                    "ExtendedPairingErrors" to plistBool(true),
                ),
            )
        } catch (e: Exception) {
            tls.close()
            throw e
        }

        // lockdownd returns the authoritative record; a cached copy can be stale or
        // malformed, so prefer the device's.
        val returned = (reply["PairRecordData"] as? PlistValue.DataValue)?.value
        val adopted = returned?.let { PairingRecord.parse(it) } ?: seed
        this@LockdownClient.record = adopted
        transport = Secure(tls)
        adopted
    }

    /**
     * Adopts a pairing record that was stored earlier, switching the transport to
     * TLS without re-pairing. Used on the common path where the device is already
     * paired and only a session needs opening.
     */
    suspend fun adoptExisting(record: PairingRecord) = withContext(Dispatchers.IO) {
        val plain = transport as? Plain
            ?: throw LockdownException("this connection has already been paired")
        val tls = LockdownTls.wrap(plain.channel, record, device)
        tls.handshake()
        this@LockdownClient.record = record
        transport = Secure(tls)
    }

    /**
     * Opens a TLS session and, when a record is available, a privileged session.
     *
     * This is the common path: a device that has already been paired needs only
     * [adoptExisting] followed by [startSession]. A device with no stored record
     * needs [pair] first, which is why the two are kept separate rather than folded
     * into one call that could silently re-pair.
     */
    suspend fun openSession(stored: PairingRecord?): PairingRecord = withContext(Dispatchers.IO) {
        val established = if (stored != null) {
            adoptExisting(stored)
            stored
        } else {
            pair(null)
        }
        startSession()
        established
    }

    /** Sends [body] over the freshly established TLS channel. */
    private suspend fun exchangeTls(
        tls: TlsChannel,
        body: Map<String, PlistValue>,
    ): Map<String, PlistValue> {
        val framed = ServiceFraming.plistFrame(
            buildMap {
                putAll(body)
                put("Label", plistString(LABEL))
                put("ProtocolVersion", plistString("2"))
            },
        )
        tls.write(framed, 0, framed.size)
        val parsed = PlistCodec.decodeDict(tls.readOne())
        val error = parsed.string("Error")
        if (error != null) {
            throw LockdownException(lockdownErrorMessage(error, parsed.string("ErrorDescription")))
        }
        return parsed
    }

    /**
     * Opens a privileged session. Fails when the device is locked or the pairing
     * record is no longer trusted, which are the two conditions that actually
     * produce this error in practice.
     */
    suspend fun startSession() = withContext(Dispatchers.IO) {
        val reply = request(mapOf("Request" to plistString("StartSession")))
        sessionId = reply.string("SessionID") ?: throw LockdownException(
            "the device refused to open a session. It is most likely locked, or this " +
                "computer is no longer trusted. Unlock the iPhone and pair again if needed.",
        )
    }

    /** Reads one device property. */
    suspend fun getValue(key: String): PlistValue? =
        request(mapOf("Request" to plistString("GetValue"), "Key" to plistString(key)))[key]

    suspend fun getString(key: String): String? = getValue(key)?.asString

    /** Every readable device property. */
    suspend fun deviceInfo(): Map<String, String> = withContext(Dispatchers.IO) {
        request(mapOf("Request" to plistString("GetValue")))
            .mapNotNull { (k, v) -> v.asString?.let { k to it } }
            .toMap()
    }

    /**
     * The TLS version to request, read from the device when it advertises one.
     *
     * lockdownd on some iOS builds refuses a handshake that offers only TLS 1.3,
     * so this is honoured rather than left to platform defaults.
     */
    suspend fun preferredTlsVersion(): String? = getString("ssl_protocol_version")

    override fun close() {
        transport.close()
    }
}
