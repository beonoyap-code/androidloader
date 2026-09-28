package me.androidloader.usbmux

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Adapter that turns the socket usbmuxd hands back into a plain duplex stream.
 *
 * lockdownd and every service above it treat this as an ordinary TCP connection,
 * so nothing in the stack above needs to know the bytes are actually crossing USB.
 */
class UsbmuxSocketChannel(private val socket: Socket) : AutoCloseable {

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val closed = AtomicBoolean(false)

    /**
     * The socket beneath this channel, so lockdownd can layer TLS over it.
     *
     * Exposed for the handshake in `LockdownTls`; the streams above stay owned by
     * this channel, and the TLS layer reads and writes the same descriptor.
     */
    val rawSocket: Socket get() = socket

    fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length)

    fun readFully(buffer: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, offset + read, length - read)
            if (n < 0) throw EOFException("the iPhone closed the connection")
            read += n
        }
    }

    /**
     * [readFully] with the failure mode spelled out for callers outside this file.
     *
     * AFC in particular needs to tell "the device hung up" apart from "the device
     * sent something malformed", because the two point at completely different
     * problems for the user.
     */
    fun readFullyOrThrow(buffer: ByteArray, offset: Int, length: Int) {
        try {
            readFully(buffer, offset, length)
        } catch (e: EOFException) {
            throw IOException("the iPhone closed the connection mid-message", e)
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("the iPhone stopped responding mid-message", e)
        }
    }

    /**
     * Writes [length] bytes, returning how many were accepted.
     *
     * Android's socket streams can accept a partial write, so looping on the result
     * is required rather than assuming a single call moves everything.
     */
    fun writeOrThrow(buffer: ByteArray, offset: Int, length: Int): Int =
        try {
            output.write(buffer, offset, length)
            output.flush()
            length
        } catch (e: java.net.SocketTimeoutException) {
            throw IOException("the iPhone stopped accepting data", e)
        }

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        output.write(buffer, offset, length)
        output.flush()
    }

    fun flush() = output.flush()

    val isClosed: Boolean get() = closed.get() || socket.isClosed

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
        }
    }
}

/**
 * Reads a single length-prefixed message off this channel.
 *
 * The read runs on [Dispatchers.IO] because a blocking socket read must not occupy
 * a caller's dispatcher thread.
 */
suspend fun UsbmuxSocketChannel.readOneMessage(): ByteArray = withContext(Dispatchers.IO) {
    val prefix = ByteArray(ServiceFraming.LENGTH_PREFIX)
    readFully(prefix, 0, prefix.size)
    val length = ServiceFraming.lengthOf(prefix)
        ?: throw IOException("service frame declared no length")
    if (length < 0 || length > MAX_MESSAGE) {
        throw IOException("service frame length $length is out of range")
    }
    val body = ByteArray(length)
    if (length > 0) readFully(body, 0, length)
    body
}

/** Upper bound on one service message, guarding against a corrupt length field. */
const val MAX_MESSAGE = 64 * 1024 * 1024
