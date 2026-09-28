package me.androidloader.usbmux

import me.androidloader.plist.PlistCodec
import me.androidloader.plist.PlistValue
import me.androidloader.plist.asData
import me.androidloader.plist.asDict
import me.androidloader.plist.asList
import me.androidloader.plist.asString
import me.androidloader.plist.plistArray
import me.androidloader.plist.plistData
import me.androidloader.plist.plistInt
import me.androidloader.plist.plistString
import java.io.ByteArrayOutputStream

/**
 * Wire format for the usbmuxd control socket.
 *
 * Every frame is a 16-byte little-endian header followed by an XML plist body:
 *
 * ```
 * offset  size  field
 * 0       4     total length, header included
 * 4       4     version
 * 8       4     message type
 * 12      4     tag, echoed by the daemon
 * 16      n     XML plist payload
 * ```
 *
 * This mirrors `idevice`'s `usbmuxd::raw_packet`, which is the reference
 * implementation iloader itself uses.
 */
object UsbmuxProtocol {

    const val HEADER_SIZE = 16

    /** Binary plist request/response version. */
    const val VERSION_BINARY = 0

    /** XML plist request/response version. This is what libimobiledevice sends. */
    const val VERSION_XML = 1

    /** usbmuxd answers with a result frame carrying a numeric status. */
    const val MESSAGE_RESULT = 1

    /** usbmuxd answers with a plist frame. */
    const val MESSAGE_PLIST = 8

    /** `Number` values in a result frame. */
    object Result {
        const val SUCCESS = 0
        const val BAD_COMMAND = 1
        const val BAD_DEVICE = 2
        const val CONNECTION_REFUSED = 3
        const val BAD_VERSION = 6
    }

    /**
     * Wraps [body] in a usbmuxd frame.
     *
     * @param tag echoed by the daemon so replies can be correlated.
     */
    fun frame(
        body: Map<String, PlistValue>,
        version: Int = VERSION_XML,
        message: Int = MESSAGE_PLIST,
        tag: Int = 0,
    ): ByteArray {
        val payload = PlistCodec.encodeXml(PlistValue.DictValue(body))
        val out = ByteArray(HEADER_SIZE + payload.size)
        writeIntLE(out, 0, out.size)
        writeIntLE(out, 4, version)
        writeIntLE(out, 8, message)
        writeIntLE(out, 12, tag)
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /** A decoded frame. */
    data class Frame(
        val length: Int,
        val version: Int,
        val message: Int,
        val tag: Int,
        val body: Map<String, PlistValue>,
    ) {
        /** The `Number` field of a result frame, if this is one. */
        val resultCode: Int? get() = (body["Number"] as? PlistValue.IntValue)?.value

        /** Turns a non-zero result code into an exception naming the actual failure. */
        fun requireSuccess() {
            val code = resultCode ?: return
            if (code == Result.SUCCESS) return
            throw UsbmuxProtocolException(
                when (code) {
                    Result.BAD_COMMAND -> "usbmuxd rejected the request as a bad command"
                    Result.BAD_DEVICE -> "usbmuxd does not know that device"
                    Result.CONNECTION_REFUSED -> "the iPhone refused the connection; unlock it and trust this computer"
                    Result.BAD_VERSION -> "usbmuxd rejected the protocol version; try a newer usbmuxd"
                    else -> "usbmuxd returned error code $code"
                },
            )
        }
    }

    /**
     * Incremental frame reader.
     *
     * A usbmuxd stream has no message boundaries of its own, so frames are pulled
     * off a buffer as it fills. This keeps the socket handling free of assumptions
     * about how a read happens to split.
     */
    class FrameReader {
        private val buffer = ByteArrayOutputStream()
        private val pending = ArrayDeque<Frame>()

        fun offer(chunk: ByteArray, length: Int = chunk.size) {
            buffer.write(chunk, 0, length)
            drain()
        }

        /** Returns the next complete frame, or null if more bytes are needed. */
        fun poll(): Frame? = pending.removeFirstOrNull()

        /** Bytes buffered but not yet part of a complete frame. */
        fun buffered(): Int = buffer.size() - consumed

        private var consumed = 0

        private fun drain() {
            while (true) {
                val buf = buffer.toByteArray()
                if (buf.size - consumed < HEADER_SIZE) return
                val total = readIntLE(buf, consumed)
                if (total < HEADER_SIZE) throw UsbmuxProtocolException(
                    "usbmuxd frame length $total is smaller than the header",
                )
                if (buf.size - consumed < total) return
                val version = readIntLE(buf, consumed + 4)
                val message = readIntLE(buf, consumed + 8)
                val tag = readIntLE(buf, consumed + 12)
                val body = try {
                    PlistCodec.decodeDict(
                        buf.copyOfRange(consumed + HEADER_SIZE, consumed + total),
                    )
                } catch (e: Exception) {
                    throw UsbmuxProtocolException("undecodable usbmuxd payload: ${e.message}", e)
                }
                pending += Frame(total, version, message, tag, body)
                consumed += total
            }
        }

        fun reset() {
            buffer.reset()
            consumed = 0
            pending.clear()
        }
    }

    // ------------------------------------------------------- request bodies

    fun listDevicesRequest(): ByteArray = frame(
        mapOf(
            "MessageType" to plistString("ListDevices"),
            "ClientVersionString" to plistString("androidloader"),
            // libimobiledevice reports 3 here; the daemon uses it to decide whether
            // to emit the newer property names we depend on.
            "kLibUSBMuxVersion" to plistInt(3),
        ),
    )

    fun readPairRecordRequest(udid: String): ByteArray = frame(
        mapOf(
            "MessageType" to plistString("ReadPairRecord"),
            "PairRecordID" to plistString(udid),
            "ClientVersionString" to plistString("androidloader"),
        ),
    )

    fun savePairRecordRequest(udid: String, record: ByteArray): ByteArray = frame(
        mapOf(
            "MessageType" to plistString("SavePairRecord"),
            "PairRecordID" to plistString(udid),
            "PairRecordData" to plistData(record),
            "ClientVersionString" to plistString("androidloader"),
        ),
    )

    /**
     * Opens a channel to [port] on the device.
     *
     * `PortNumber` is byte-swapped before it is sent; see [portToBigEndian].
     */
    fun connectRequest(deviceId: Int, port: Int): ByteArray = frame(
        mapOf(
            "MessageType" to plistString("Connect"),
            "DeviceID" to plistInt(deviceId),
            "PortNumber" to plistInt(portToBigEndian(port)),
        ),
    )

    fun listenRequest(): ByteArray = frame(
        mapOf("MessageType" to plistString("Listen"), "ClientVersionString" to plistString("androidloader")),
    )

    fun readBuidRequest(): ByteArray = frame(
        mapOf("MessageType" to plistString("ReadBUID"), "ClientVersionString" to plistString("androidloader")),
    )

    /**
     * Byte-swaps a port, mirroring the reference client's `port.to_be()`.
     *
     * This looks like a no-op bug at a glance, but the field is read back as a
     * little-endian 16-bit value, so a big-endian conversion of the number is
     * exactly the swap. lockdownd on 62078 must go out as 0x7EF2.
     */
    fun portToBigEndian(port: Int): Int =
        ((port and 0xFF) shl 8) or ((port ushr 8) and 0xFF)

    // ------------------------------------------------------ response bodies

    /** How a device is attached. */
    sealed interface ConnectionType {
        data object Usb : ConnectionType
        data class Network(val address: String) : ConnectionType
        data class Unknown(val description: String) : ConnectionType
    }

    /** A device as reported by `ListDevices`. */
    data class Device(
        val deviceId: Int,
        val udid: String,
        val connection: ConnectionType,
    )

    /**
     * Parses a `DeviceList` response.
     *
     * Entries without a serial number are skipped rather than failing the whole
     * listing: a recovery-mode device or a half-initialised entry should not stop
     * the user from seeing a usable phone.
     */
    fun parseDeviceList(body: Map<String, PlistValue>): List<Device> {
        val list = body["DeviceList"]?.asList ?: return emptyList()
        return list.mapNotNull { entry ->
            val dict = entry.asDict ?: return@mapNotNull null
            val id = (dict["DeviceID"] as? PlistValue.IntValue)?.value ?: return@mapNotNull null
            val props = dict["Properties"]?.asDict ?: return@mapNotNull null
            val udid = props["SerialNumber"]?.asString ?: return@mapNotNull null
            val kind = props["ConnectionType"]?.asString ?: "Unknown"
            val connection: ConnectionType = when (kind) {
                "USB" -> ConnectionType.Usb
                "Network" -> {
                    val address = parseNetworkAddress(props["NetworkAddress"]?.asData)
                    if (address != null) {
                        ConnectionType.Network(address)
                    } else {
                        ConnectionType.Unknown("Network (unresolved address)")
                    }
                }
                else -> ConnectionType.Unknown(kind)
            }
            Device(id, udid, connection)
        }
    }

    /**
     * Decodes a usbmuxd `NetworkAddress`, which is a `sockaddr`-like structure
     * rather than a bare IP. The leading byte is the address family: 2 is IPv4 and
     * 30 is IPv6, with the address itself starting after the family and port.
     */
    private fun parseNetworkAddress(data: ByteArray?): String? {
        val b = data ?: return null
        if (b.isEmpty()) return null
        return when (b[0].toInt() and 0xFF) {
            0x02 -> if (b.size >= 8) {
                "${b[4].toInt() and 0xFF}.${b[5].toInt() and 0xFF}." +
                    "${b[6].toInt() and 0xFF}.${b[7].toInt() and 0xFF}"
            } else {
                null
            }
            0x1E, 0x1C -> {
                if (b.size < 24) return null
                val groups = (8 until 24 step 2)
                    .joinToString(":") { String.format("%02x%02x", b[it].toInt() and 0xFF, b[it + 1].toInt() and 0xFF) }
                "[$groups]"
            }
            else -> null
        }
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun readIntLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
}

/** Raised when usbmuxd speaks unexpectedly, which usually means a version mismatch. */
class UsbmuxProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
