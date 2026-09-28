package me.androidloader.usbmux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.androidloader.plist.PlistValue
import me.androidloader.plist.asData
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Where the usbmuxd daemon is reachable. */
data class UsbmuxEndpoint(
    val host: String = "127.0.0.1",
    val port: Int = UsbmuxClient.DEFAULT_PORT,
) {
    companion object {
        /**
         * The endpoint `termux-usbmuxd -s 127.0.0.1:27015` listens on.
         *
         * The daemon's default is a Unix socket inside Termux's private data
         * directory, which this app cannot open: Android sandboxes each app UID
         * and that path is not world-accessible. Loopback TCP is shared between
         * apps, so TCP is the only transport available to an APK.
         */
        val DEFAULT = UsbmuxEndpoint()
    }
}

/** Raised when the usbmuxd daemon cannot be reached. */
class UsbmuxUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A connected usbmuxd service.
 *
 * The lifecycle mirrors how libimobiledevice uses the daemon: open a control
 * connection, list devices, then `connect` to a port and treat the resulting
 * socket as a plain duplex stream to the iPhone. Control traffic and payload
 * traffic never share a socket, so [close] and [connectTo] are the two operations
 * that matter.
 */
class UsbmuxClient(
    private val endpoint: UsbmuxEndpoint = UsbmuxEndpoint.DEFAULT,
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 15_000,
) : AutoCloseable {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val reader = UsbmuxProtocol.FrameReader()

    /** Locks the control connection so concurrent requests cannot interleave frames. */
    private val controlLock = Any()

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * Opens the control connection.
     *
     * Suspending, and dispatched to [Dispatchers.IO], because this performs
     * blocking socket I/O. The view model drives the flow from the main
     * dispatcher, so a non-suspending version would throw
     * `NetworkOnMainThreadException` the moment it was called.
     *
     * A refused connection is reported with the Termux command needed to start the
     * daemon, because "connection refused" here almost always means usbmuxd simply
     * is not running yet.
     */
    suspend fun connect() = withContext(Dispatchers.IO) { openControlConnection() }

    /** The blocking half of [connect]; callers must already be on an IO thread. */
    private fun openControlConnection() {
        withControl {
            if (isConnected) return
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.soTimeout = readTimeoutMs
                s.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMs)
                socket = s
                input = s.getInputStream()
                output = s.getOutputStream()
                reader.reset()
            } catch (e: java.net.ConnectException) {
                throw UsbmuxUnavailableException(
                    "Nothing is listening on ${endpoint.host}:${endpoint.port}. " +
                        "Start usbmuxd in Termux first: " +
                        "termux-usb -r -E -e \"usbmuxd --socket ${endpoint.host}:${endpoint.port} " +
                        "--pidfile NONE -f\" /dev/bus/usb/001/002",
                    e,
                )
            } catch (e: SocketTimeoutException) {
                throw UsbmuxUnavailableException(
                    "Timed out connecting to usbmuxd at ${endpoint.host}:${endpoint.port}. " +
                        "Check that usbmuxd is running in Termux.",
                    e,
                )
            } catch (e: java.io.IOException) {
                throw UsbmuxUnavailableException(
                    "Could not reach usbmuxd at ${endpoint.host}:${endpoint.port}: ${e.message}",
                    e,
                )
            }
        }
    }

    /** Lists every device usbmuxd currently knows about. */
    suspend fun listDevices(): List<UsbmuxProtocol.Device> = withContext(Dispatchers.IO) {
        connect()
        val body = request(UsbmuxProtocol.listDevicesRequest())
        UsbmuxProtocol.parseDeviceList(body)
    }

    /** The single device, or null when none or several are attached. */
    suspend fun singleDevice(): UsbmuxProtocol.Device? =
        listDevices().singleOrNull()

    /**
     * Opens a raw byte stream to [port] on [device].
     *
     * The returned socket is already handed to usbmuxd, so the first byte written
     * here is the first byte the iPhone sees on that port.
     */
    suspend fun connectTo(
        device: UsbmuxProtocol.Device,
        port: Int,
        readTimeoutMs: Int = this.readTimeoutMs,
    ): Socket = withContext(Dispatchers.IO) {
        connect()
        val body = request(UsbmuxProtocol.connectRequest(device.deviceId, port))
        (body["Number"] as? PlistValue.IntValue)?.value.let { code ->
            if (code != UsbmuxProtocol.Result.SUCCESS) {
                throw UsbmuxProtocolException(connectFailureMessage(code, port))
            }
        }
        // The control socket is now owned by the iPhone service; hand back a copy
        // so the caller can use it while closing the client releases the original.
        val s = socket ?: throw UsbmuxUnavailableException("usbmuxd connection vanished")
        s.soTimeout = readTimeoutMs
        s
    }

    /** Reads this device's pairing record, or null if it has never been paired. */
    suspend fun readPairRecord(udid: String): ByteArray? = withContext(Dispatchers.IO) {
        val body = request(UsbmuxProtocol.readPairRecordRequest(udid))
        if ((body["PairRecordData"] as? PlistValue.DataValue) == null) null
        else body["PairRecordData"]?.asData
    }

    /** Stores a pairing record in the daemon's own storage. */
    suspend fun savePairRecord(udid: String, record: ByteArray) = withContext(Dispatchers.IO) {
        val body = request(UsbmuxProtocol.savePairRecordRequest(udid, record))
        val code = (body["Number"] as? PlistValue.IntValue)?.value
        if (code != UsbmuxProtocol.Result.SUCCESS) {
            throw UsbmuxProtocolException("usbmuxd refused to store the pairing record (code $code)")
        }
    }

    /** A stable per-host identifier, mirroring usbmuxd's BUID. */
    suspend fun readBuid(): String? = withContext(Dispatchers.IO) {
        request(UsbmuxProtocol.readBuidRequest())["BUID"]?.let {
            (it as? PlistValue.StringValue)?.value
        }
    }

    /**
     * Sends one request and returns the next reply body.
     *
     * Both sides are synchronized because usbmuxd has no request IDs on the wire
     * in practice: a tag is echoed, but the daemon does not reorder replies for us,
     * so overlapping requests would cross.
     */
    private fun request(bytes: ByteArray): Map<String, PlistValue> = withControl {
        val out = output ?: throw UsbmuxUnavailableException("usbmuxd is not connected")
        val err = input ?: throw UsbmuxUnavailableException("usbmuxd is not connected")
        out.write(bytes)
        out.flush()
        val frame = reader.poll() ?: readFrame(err)
        frame.requireSuccess()
        frame.body
    }

    private fun readFrame(err: InputStream): UsbmuxProtocol.Frame {
        val header = ByteArray(UsbmuxProtocol.HEADER_SIZE)
        readFully(err, header)
        val total = leInt(header, 0)
        if (total < UsbmuxProtocol.HEADER_SIZE) {
            throw UsbmuxProtocolException("usbmuxd announced an impossible frame length of $total")
        }
        val body = ByteArray(total - UsbmuxProtocol.HEADER_SIZE)
        readFully(err, body)
        return try {
            val parsed = me.androidloader.plist.PlistCodec.decodeDict(body)
            UsbmuxProtocol.Frame(
                length = total,
                version = leInt(header, 4),
                message = leInt(header, 8),
                tag = leInt(header, 12),
                body = parsed,
            )
        } catch (e: Exception) {
            throw UsbmuxProtocolException("unreadable usbmuxd response: ${e.message}", e)
        }
    }

    private fun readFully(stream: InputStream, into: ByteArray) {
        var read = 0
        while (read < into.size) {
            val n = try {
                stream.read(into, read, into.size - read)
            } catch (e: SocketTimeoutException) {
                throw UsbmuxUnavailableException("usbmuxd stopped responding; is Termux still running?", e)
            }
            if (n < 0) throw EOFException("usbmuxd closed the connection")
            read += n
        }
    }

    private fun connectFailureMessage(code: Int?, port: Int): String = when (code) {
        UsbmuxProtocol.Result.BAD_COMMAND -> "usbmuxd rejected the connect request"
        UsbmuxProtocol.Result.BAD_DEVICE -> "usbmuxd lost track of the device before it could connect"
        UsbmuxProtocol.Result.CONNECTION_REFUSED ->
            "The iPhone refused port $port. Unlock the phone, accept 'Trust This Computer', " +
                "and make sure Developer Mode is on."
        UsbmuxProtocol.Result.BAD_VERSION ->
            "usbmuxd does not understand this protocol version; update the Termux usbmuxd build"
        null -> "usbmuxd returned no status for the connect request"
        else -> "usbmuxd could not open port $port (code $code)"
    }

    private inline fun <T> withControl(block: () -> T): T = synchronized(controlLock) { block() }

    private fun leInt(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    override fun close() {
        withControl {
            runCatching { socket?.close() }
            socket = null
            input = null
            output = null
            reader.reset()
        }
    }

    companion object {
        /** usbmuxd's conventional TCP port. */
        const val DEFAULT_PORT = 27015

        /** lockdownd on the iPhone. */
        const val PORT_LOCKDOWN = 62078

        /** Apple's file transfer service, used to stage large app bundles. */
        const val PORT_AFC = 50333
    }
}
