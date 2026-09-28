package me.androidloader.lockdown

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.androidloader.usbmux.MAX_MESSAGE
import me.androidloader.usbmux.ServiceFraming
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

/**
 * A duplex byte channel backed by TLS.
 *
 * Deliberately shaped like `UsbmuxSocketChannel` so the request paths in
 * [LockdownClient] can be pointed at either one depending on whether the
 * connection has completed its handshake.
 */
class TlsChannel(private val socket: SSLSocket) : Closeable {

    private val input get() = socket.inputStream
    private val output get() = socket.outputStream
    private var closed = false

    /**
     * Completes the handshake.
     *
     * `startHandshake` rather than letting the first read trigger it, so a refusal
     * is reported as a handshake failure with a useful message instead of a
     * generic "stream closed".
     */
    fun handshake() {
        try {
            socket.startHandshake()
        } catch (e: javax.net.ssl.SSLException) {
            throw LockdownException(
                "TLS handshake with the iPhone failed: ${e.message}. " +
                    "This usually means the pairing record is stale; erase it and pair again.",
                e,
            )
        }
    }

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        output.write(buffer, offset, length)
        output.flush()
    }

    fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val n = try {
                input.read(buffer, offset + read, length - read)
            } catch (e: SocketTimeoutException) {
                throw LockdownException("the iPhone stopped responding", e)
            }
            if (n < 0) throw EOFException("the iPhone closed the connection")
            read += n
        }
    }

    /**
     * Reads one length-prefixed plist message.
     *
     * @return the plist body, without its length prefix
     */
    suspend fun readOne(): ByteArray = withContext(Dispatchers.IO) {
        val prefix = ByteArray(ServiceFraming.LENGTH_PREFIX)
        readFully(prefix, 0, prefix.size)
        val length = ServiceFraming.lengthOf(prefix)
            ?: throw IOException("lockdownd frame declared no length")
        if (length < 0 || length > MAX_MESSAGE) {
            throw IOException("lockdownd frame length $length is out of range")
        }
        val body = ByteArray(length)
        if (length > 0) readFully(body, 0, length)
        body
    }

    override fun close() {
        if (closed) return
        closed = true
        // send a close_notify before dropping the TCP connection, otherwise the
        // device logs an unclean shutdown.
        runCatching { socket.close() }
    }
}
