package me.androidloader.usbmux

import me.androidloader.plist.PlistCodec
import me.androidloader.plist.PlistValue

/**
 * Message framing used by lockdownd and the services above it.
 *
 * Once a service is connected, its plists are prefixed by a four-byte big-endian
 * length. Reading one message is therefore a read of exactly that many bytes, which
 * is what makes a partial socket read harmless and removes any need to scan for a
 * delimiter.
 */
object ServiceFraming {

    /** Size of the length prefix in bytes. */
    const val LENGTH_PREFIX = 4

    /** Encodes a payload with its big-endian length prefix. */
    fun frame(payload: ByteArray): ByteArray {
        val out = ByteArray(LENGTH_PREFIX + payload.size)
        val n = payload.size
        out[0] = ((n ushr 24) and 0xFF).toByte()
        out[1] = ((n ushr 16) and 0xFF).toByte()
        out[2] = ((n ushr 8) and 0xFF).toByte()
        out[3] = (n and 0xFF).toByte()
        payload.copyInto(out, LENGTH_PREFIX)
        return out
    }

    /** Encodes an already-built plist dictionary with its length prefix. */
    fun plistFrame(body: Map<String, PlistValue>): ByteArray =
        frame(PlistCodec.encodeXml(PlistValue.DictValue(body)))

    /**
     * The length announced by a prefix, or null when [prefix] is too short to hold
     * one. Null rather than a partial value so a caller cannot mistake a
     * half-read prefix for a real length.
     */
    fun lengthOf(prefix: ByteArray): Int? {
        if (prefix.size < LENGTH_PREFIX) return null
        return ((prefix[0].toInt() and 0xFF) shl 24) or
            ((prefix[1].toInt() and 0xFF) shl 16) or
            ((prefix[2].toInt() and 0xFF) shl 8) or
            (prefix[3].toInt() and 0xFF)
    }
}
