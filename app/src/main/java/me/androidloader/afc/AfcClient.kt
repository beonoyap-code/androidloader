package me.androidloader.afc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.androidloader.plist.PlistCodec
import me.androidloader.plist.PlistValue
import me.androidloader.plist.asString
import me.androidloader.usbmux.UsbmuxClient
import me.androidloader.usbmux.UsbmuxProtocol
import me.androidloader.usbmux.UsbmuxSocketChannel
import me.androidloader.usbmux.readOneMessage
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Raised when an AFC operation fails. */
class AfcException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Apple File Conduit.
 *
 * AFC is how an app bundle reaches the device: the installer cannot be handed a
 * multi-gigabyte archive over lockdownd, so the bundle is streamed into AFC's
 * media directory first and `installation_proxy` is then pointed at the staged
 * path. AFC is a header-plus-packet protocol on a raw stream, not a plist
 * protocol, which is why it is implemented separately.
 *
 * Packet layout, all integers little-endian:
 *
 * ```
 * 0  8  magic  "CFA6LPAA"
 * 8  8  entire_length   header + payload
 * 16 8  this_length     header + this packet's payload
 * 24 8  packet_num
 * 32 8  operation
 * 40 .. payload
 * ```
 */
class AfcClient private constructor(
    private val channel: UsbmuxSocketChannel,
) : Closeable {

    /** The AFC magic string. */
    private val magic = MAGIC

    // ------------------------------------------------------------ operations

    /**
     * AFC operation codes.
     *
     * Only the subset needed to stage an app bundle is implemented. The gaps are
     * deliberate: the full table has operations this app has no use for, and each
     * unused one is a chance to get the numbering wrong.
     */
    private object Op {
        const val STATUS = 0x00000001
        const val DATA = 0x00000002
        const val READ_DIR = 0x00000008
        const val REMOVE_PATH = 0x00000008 + 1
        const val MAKE_DIR = 0x00000009 + 1
        const val FILE_OPEN = 0x0000000D
        const val FILE_OPEN_RES = 0x0000000E
        const val FILE_WRITE = 0x00000010
        const val FILE_CLOSE = 0x00000014
    }

    /** A handle to an open file on the device. */
    private data class FileHandle(val id: Long)

    /**
     * Connects to AFC on [device] and performs the initial handshake.
     */
    suspend fun connect(
        usbmux: UsbmuxClient,
        device: UsbmuxProtocol.Device,
    ): AfcClient = withContext(Dispatchers.IO) {
        val socket = usbmux.connectTo(device, UsbmuxClient.PORT_AFC)
        val client = AfcClient(UsbmuxSocketChannel(socket))
        // The handshake response tells us the protocol version in use; a device
        // that answers nothing is not ready for file access.
        val status = client.readStatus()
        if (status != STATUS_OK) {
            client.close()
            throw AfcException("AFC handshake failed with status $status")
        }
        client
    }

    /** Creates a directory, ignoring the case where it already exists. */
    suspend fun makeDirectory(path: String) = withContext(Dispatchers.IO) {
        sendPacket(Op.MAKE_DIR, cString(path))
        val status = readStatus()
        if (status != STATUS_OK && status != STATUS_OBJECT_EXISTS) {
            throw AfcException("could not create $path on the iPhone (status $status)")
        }
    }

    /**
     * Removes a file or directory if present.
     *
     * Failures are swallowed deliberately: this is used to clear a previous staging
     * directory, and a missing path is the desired end state.
     */
    suspend fun remove(path: String) = withContext(Dispatchers.IO) {
        runCatching {
            sendPacket(Op.REMOVE_PATH, cString(path))
            readStatus()
        }
        Unit
    }

    /**
     * Writes [data] to [path], creating or truncating it.
     *
     * AFC caps a single packet at roughly 1 MiB, so the payload is split. Each
     * chunk carries the same packet number, which is how the device reassembles
     * the logical write; getting that wrong silently truncates files.
     */
    suspend fun writeFile(path: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val handle = openFile(path)
        try {
            var packetNumber = 0L
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + MAX_PAYLOAD, data.size)
                val chunk = data.copyOfRange(offset, end)
                val header = buildPacket(Op.FILE_WRITE, handle.id, chunk.size, 0)
                // The header carries the operation and the payload length; the
                // payload itself is written straight after it, so the two are
                // concatenated before going on the wire.
                val frame = header + chunk
                writeAll(frame)
                val status = readStatus()
                if (status != STATUS_OK) {
                    throw AfcException("writing $path failed at offset $offset (status $status)")
                }
                packetNumber++
                offset = end
            }
        } finally {
            runCatching { closeFile(handle) }
        }
    }

    /** Names of the entries directly under [path]. */
    suspend fun listDirectory(path: String): List<String> = withContext(Dispatchers.IO) {
        sendPacket(Op.READ_DIR, cString(path))
        val body = readDataPacket()
        // The reply is a run of NUL-terminated names, terminated by an empty name.
        val names = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < body.size) {
            if (body[i] == 0.toByte()) {
                if (i > start) {
                    names += String(body, start, i - start, StandardCharsets.UTF_8)
                }
                start = i + 1
                if (names.isEmpty() && i == 0) break
            }
            i++
        }
        names
    }

    // ------------------------------------------------------------- internals

    private fun openFile(path: String): FileHandle {
        val payload = ByteBuffer.allocate(8 + cString(path).size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(0) // mode 0 = read/write, create if absent
            .put(cString(path))
            .array()
        sendPacket(Op.FILE_OPEN, payload)
        val id = readFileHandle()
        return FileHandle(id)
    }

    private fun closeFile(handle: FileHandle) {
        sendPacket(Op.FILE_CLOSE, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(handle.id).array())
        readStatus()
    }

    /** Builds a 40-byte header plus [extra], ready to concatenate with a payload. */
    private fun buildPacket(operation: Int, id: Long, payloadLength: Int, packetNumber: Int): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        magic.copyInto(header, 0)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        // entire_length and this_length both cover the header; entire_length would
        // additionally cover trailing DATA packets, but each chunk is sent whole.
        buf.putLong(8, (HEADER_SIZE + payloadLength).toLong())
        buf.putLong(16, (HEADER_SIZE + payloadLength).toLong())
        buf.putLong(24, id)
        buf.putInt(32, operation)
        return header
    }

    private fun buildPacket(operation: Int, payload: ByteArray): ByteArray {
        val header = buildPacket(operation, 0, payload.size, 0)
        return header + payload
    }

    private fun sendPacket(operation: Int, payload: ByteArray) {
        writeAll(buildPacket(operation, payload))
    }

    /**
     * Writes every byte of [bytes].
     *
     * [UsbmuxSocketChannel.writeOrThrow] loops internally until the buffer is
     * drained, so this is a single call in practice; the loop guards against a
     * transport that reports a partial write.
     */
    private fun writeAll(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = channel.writeOrThrow(bytes, offset, bytes.size - offset)
            if (n <= 0) throw EOFException("the iPhone stopped accepting AFC data")
            offset += n
        }
    }

    /** Reads a status packet, returning its code. */
    private fun readStatus(): Long = readLong(Op.STATUS)

    private fun readFileHandle(): Long = readLong(Op.FILE_OPEN_RES)

    private fun readLong(expectedOperation: Int): Long {
        val header = ByteArray(HEADER_SIZE)
        channel.readFullyOrThrow(header, 0, HEADER_SIZE)
        val thisLength = header.longAt(16)
        if (thisLength < HEADER_SIZE) {
            throw AfcException("AFC packet declared a length of $thisLength, below the header size")
        }
        val entireLength = header.longAt(8)
        if (entireLength < thisLength) {
            throw AfcException("AFC packet is internally inconsistent ($entireLength < $thisLength)")
        }
        val remaining = (thisLength - HEADER_SIZE).toInt()
        val payload = ByteArray(remaining)
        if (remaining > 0) channel.readFullyOrThrow(payload, 0, remaining)

        // this_length may be smaller than entire_length when a response is split
        // across packets; drain the rest so the stream stays aligned.
        var toDrain = entireLength - thisLength
        while (toDrain > 0) {
            val skip = minOf(toDrain, 64 * 1024L)
            val scratch = ByteArray(skip.toInt())
            channel.readFullyOrThrow(scratch, 0, scratch.size)
            toDrain -= skip
        }

        val operation = header.intAt(32)
        if (operation != expectedOperation) {
            throw AfcException("expected an AFC status packet but got operation 0x${Integer.toHexString(operation)}")
        }
        return header.longAt(24)
    }

    private fun readDataPacket(): ByteArray {
        readLong(Op.DATA)
        // The data itself follows as a separate DATA packet; its header carries the
        // length in the id field, per the protocol's quirk.
        val header = ByteArray(HEADER_SIZE)
        channel.readFullyOrThrow(header, 0, HEADER_SIZE)
        val length = header.longAt(8) - HEADER_SIZE
        if (length < 0 || length > 64L * 1024 * 1024) {
            throw AfcException("AFC data packet length $length is out of range")
        }
        val body = ByteArray(length.toInt())
        if (body.isNotEmpty()) channel.readFullyOrThrow(body, 0, body.size)
        return body
    }

    private fun cString(s: String): ByteArray = s.toByteArray(StandardCharsets.UTF_8) + 0

    private fun ByteArray.longAt(offset: Int): Long =
        ByteBuffer.wrap(this, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long

    private fun ByteArray.intAt(offset: Int): Int =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    override fun close() {
        channel.close()
    }

    companion object {
        /** AFC packet header size. */
        const val HEADER_SIZE = 40

        /** Largest payload in one DATA packet. */
        const val MAX_PAYLOAD = 1024 * 1024

        /** "CFA6LPAA" in ASCII. */
        val MAGIC = "CFA6LPAA".toByteArray(StandardCharsets.US_ASCII)

        const val STATUS_OK = 0L
        const val STATUS_OBJECT_EXISTS = 6L
        const val STATUS_OBJECT_NOT_FOUND = 8L
        const val STATUS_DIR_NOT_EMPTY = 26L
    }
}
