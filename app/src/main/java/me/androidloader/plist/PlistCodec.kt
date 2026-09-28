package me.androidloader.plist

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/** Raised when a property list cannot be parsed or is structurally invalid. */
class PlistException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Property-list codec.
 *
 * Only XML encoding is implemented, because that is what this client sends: both
 * usbmuxd and lockdownd accept XML plists for requests, and libimobiledevice
 * writes XML for the same exchanges. Responses arrive in either format, so
 * decoding auto-detects `bplist00` versus an XML document.
 */
object PlistCodec {

    private val BPLIST_MAGIC = "bplist00".toByteArray(StandardCharsets.US_ASCII)

    /** Seconds between the Unix epoch and the 2001-01-01 epoch used by plist dates. */
    const val APPLE_EPOCH_OFFSET = 978307200.0

    fun decode(bytes: ByteArray): PlistValue {
        if (bytes.isEmpty()) throw PlistException("empty plist payload")
        return if (startsWith(bytes, BPLIST_MAGIC)) decodeBinary(bytes) else decodeXml(bytes)
    }

    /** Decodes and requires the root to be a dictionary. */
    fun decodeDict(bytes: ByteArray): Map<String, PlistValue> =
        decode(bytes).asDict ?: throw PlistException("plist root is not a dictionary")

    fun encodeXml(value: PlistValue): ByteArray {
        val out = StringBuilder(256)
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
        out.append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
        out.append("<plist version=\"1.0\">\n")
        writeXml(value, out)
        out.append("</plist>\n")
        return out.toString().toByteArray(StandardCharsets.UTF_8)
    }

    // ------------------------------------------------------------------ XML

    private fun writeXml(value: PlistValue, out: StringBuilder) {
        when (value) {
            is PlistValue.DictValue -> {
                out.append("<dict>")
                for ((k, v) in value.value) {
                    out.append("<key>").append(escapeXml(k)).append("</key>")
                    writeXml(v, out)
                }
                out.append("</dict>")
            }
            is PlistValue.ArrayValue -> {
                out.append("<array>")
                for (v in value.value) writeXml(v, out)
                out.append("</array>")
            }
            is PlistValue.BoolValue -> out.append(if (value.value) "<true/>" else "<false/>")
            is PlistValue.IntValue -> out.append("<integer>").append(value.value).append("</integer>")
            is PlistValue.LongValue ->
                out.append("<integer>").append(value.value).append("</integer>")
            is PlistValue.UIntValue ->
                out.append("<integer>").append(value.value).append("</integer>")
            is PlistValue.BigIntValue ->
                out.append("<integer>").append(value.value).append("</integer>")
            is PlistValue.DoubleValue ->
                // Locale.ROOT matters: a comma decimal separator would corrupt the plist.
                out.append("<real>").append(String.format(Locale.ROOT, "%s", value.value))
                    .append("</real>")
            is PlistValue.StringValue ->
                out.append("<string>").append(escapeXml(value.value)).append("</string>")
            is PlistValue.DataValue ->
                out.append("<data>").append(base64(value.value)).append("</data>")
            is PlistValue.DateValue ->
                out.append("<date>").append(formatAppleDate(value.epochSeconds))
                    .append("</date>")
            PlistValue.UnknownValue -> out.append("<string></string>")
        }
    }

    fun decodeXml(bytes: ByteArray): PlistValue {
        val text = String(bytes, StandardCharsets.UTF_8)
        val reader = XmlPlistReader(text)
        return reader.parseDocument()
    }

    private fun formatAppleDate(epochSeconds: Double): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(((epochSeconds + APPLE_EPOCH_OFFSET) * 1000.0).toLong()))
    }

    internal fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                '\n' -> sb.append("&#10;")
                '\r' -> sb.append("&#13;")
                '\t' -> sb.append("&#9;")
                else -> if (c.code < 0x20) sb.append("&#").append(c.code).append(';') else sb.append(c)
            }
        }
        return sb.toString()
    }

    internal fun base64(data: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(data)

    // --------------------------------------------------------------- binary

    /**
     * Decodes a `bplist00` document.
     *
     * The 32-byte trailer is laid out as five unused bytes, `sortVersion`,
     * `offsetIntSize`, `objectRefSize`, then three 8-byte big-endian values:
     * `numObjects`, `topObject` and `offsetTableOffset`.
     */
    fun decodeBinary(bytes: ByteArray): PlistValue {
        val TRAILER_SIZE = 32
        if (bytes.size < TRAILER_SIZE + 8) {
            throw PlistException("binary plist too small (${bytes.size} bytes)")
        }
        val t = bytes.size - TRAILER_SIZE
        val offsetIntSize = bytes[t + 6].toInt() and 0xFF
        val objectRefSize = bytes[t + 7].toInt() and 0xFF
        if (offsetIntSize !in 1..8 || objectRefSize !in 1..8) {
            throw PlistException("invalid binary plist trailer sizes")
        }
        // The three trailer counters are always 8 bytes wide, regardless of
        // offsetIntSize, which only describes the entries of the offset table.
        val numObjects = readUInt(bytes, t + 8, 8)
        val topObject = readUInt(bytes, t + 16, 8).toInt()
        val offsetTableOffset = readUInt(bytes, t + 24, 8).toInt()
        if (topObject !in 0 until numObjects) throw PlistException("top object index out of range")
        if (offsetTableOffset < 0 || offsetTableOffset > t) {
            throw PlistException("offset table starts outside the object area")
        }

        // Entries in the offset table are relative to the start of the object
        // region, which begins after the 8-byte `bplist00` magic. Getting this
        // wrong shifts every object by 8 bytes, which surfaces as an
        // out-of-range offset rather than as wrong data.
        val objectRegion = BPLIST_MAGIC.size

        fun offsetOf(index: Int): Int = objectRegion +
            readUInt(bytes, offsetTableOffset + index * offsetIntSize, offsetIntSize).toInt()

        return parseObject(bytes, offsetOf(topObject), objectRefSize, ::offsetOf, HashSet())
    }

    /**
     * @param offsetOf resolves an object index to its byte offset in the buffer.
     * @param visiting guards against the reference cycles a malformed file could
     *   otherwise turn into unbounded recursion.
     */
    private fun parseObject(
        buf: ByteArray,
        offset: Int,
        refSize: Int,
        offsetOf: (Int) -> Int,
        visiting: MutableSet<Int>,
    ): PlistValue {
        if (offset < 0 || offset >= buf.size) throw PlistException("object offset out of range")
        if (!visiting.add(offset)) throw PlistException("cyclic object reference in binary plist")
        try {
            val marker = buf[offset].toInt() and 0xFF
            val high = marker and 0xF0
            val low = marker and 0x0F

            // 0x0n: singletons and 1/2/4/8/16-byte integers.
            if (high == 0x00) {
                if (low == 0x00) return PlistValue.UnknownValue
                val size = 1 shl low
                if (size > 8) {
                    val big = java.math.BigInteger(1, buf.copyOfRange(offset + 1, offset + 1 + size))
                    return PlistValue.BigIntValue(big)
                }
                val raw = readUInt(buf, offset + 1, size)
                return if (low == 3 && raw > Int.MAX_VALUE) {
                    PlistValue.LongValue(raw)
                } else {
                    PlistValue.IntValue(raw.toInt())
                }
            }
            if (high == 0x10 || high == 0x20) {
                val size = 1 shl low
                val raw = readUInt(buf, offset + 1, size)
                val signed = signExtend(raw, size)
                return if (signed in Int.MIN_VALUE.toLong()..Int.MAX_VALUE) {
                    PlistValue.IntValue(signed.toInt())
                } else {
                    PlistValue.LongValue(signed)
                }
            }
            if (high == 0x30) {
                return PlistValue.DoubleValue(
                    java.nio.ByteBuffer.wrap(buf, offset + 1, 8)
                        .order(java.nio.ByteOrder.BIG_ENDIAN).double,
                )
            }
            if (high == 0x40) {
                // 0x4F means the length follows in an int8; otherwise the low
                // nibble *is* the length. Note this is not a power of two, unlike
                // the integer markers above.
                val len = if (low == 0x0F) {
                    readUInt(buf, offset + 1, 1).toInt()
                } else {
                    low
                }
                if (len < 0 || offset + 1 + len > buf.size) {
                    throw PlistException("data object claims $len bytes, past the end")
                }
                return PlistValue.DataValue(buf.copyOfRange(offset + 1, offset + 1 + len))
            }
            if (high == 0x50) {
                // 0x5F marks a NUL-terminated ASCII string whose length is not
                // stored; any other low nibble is the length itself.
                val end = if (low == 0x0F) {
                    var i = offset + 1
                    while (i < buf.size && buf[i] != 0.toByte()) i++
                    if (i >= buf.size) throw PlistException("unterminated string object")
                    i
                } else {
                    if (offset + 1 + low > buf.size) {
                        throw PlistException("string object claims $low bytes, past the end")
                    }
                    offset + 1 + low
                }
                return PlistValue.StringValue(
                    String(buf, offset + 1, end - offset - 1, StandardCharsets.UTF_8),
                )
            }
            if (high == 0x60) {
                val secs = java.nio.ByteBuffer.wrap(buf, offset + 1, 8)
                    .order(java.nio.ByteOrder.BIG_ENDIAN).double
                return PlistValue.DateValue(secs - APPLE_EPOCH_OFFSET)
            }
            if (high == 0x80) {
                // ASCII string with an explicit length; emitted by some generators.
                return PlistValue.StringValue(
                    String(buf, offset + 1, low, StandardCharsets.US_ASCII),
                )
            }
            if (high == 0xA0) {
                val count = low
                val items = ArrayList<PlistValue>(count)
                for (i in 0 until count) {
                    val ref = readUInt(buf, offset + 1 + i * refSize, refSize).toInt()
                    items += parseObject(buf, offsetOf(ref), refSize, offsetOf, visiting)
                }
                return PlistValue.ArrayValue(items)
            }
            if (high == 0xC0) {
                // Set: same encoding as an array, with a count in the low nibble.
                return parseObject(buf, offset and 0xF0 or 0xA0, refSize, offsetOf, visiting)
            }
            if (high == 0xD0) {
                val count = low
                val keyRefsStart = offset + 1
                val valRefsStart = keyRefsStart + count * refSize
                val map = LinkedHashMap<String, PlistValue>(count)
                for (i in 0 until count) {
                    val keyRef = readUInt(buf, keyRefsStart + i * refSize, refSize).toInt()
                    val valRef = readUInt(buf, valRefsStart + i * refSize, refSize).toInt()
                    val key = parseObject(buf, offsetOf(keyRef), refSize, offsetOf, visiting)
                    val name = (key as? PlistValue.StringValue)?.value
                        ?: throw PlistException("non-string dictionary key in binary plist")
                    map[name] = parseObject(buf, offsetOf(valRef), refSize, offsetOf, visiting)
                }
                return PlistValue.DictValue(map)
            }
            if (high == 0xE0) {
                val n = low + 1
                return PlistValue.UIntValue(readUInt(buf, offset + 1, n))
            }
            throw PlistException(
                "unsupported binary plist marker 0x${Integer.toHexString(marker)}",
            )
        } finally {
            visiting.remove(offset)
        }
    }

    private fun signExtend(raw: Long, size: Int): Long {
        val bits = size * 8
        if (bits >= 64) return raw
        return if (raw and (1L shl (bits - 1)) != 0L) raw - (1L shl bits) else raw
    }

    private fun readUInt(buf: ByteArray, offset: Int, size: Int): Long {
        if (offset < 0 || offset + size > buf.size) throw PlistException("read past end of plist")
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (buf[offset + i].toLong() and 0xFF)
        return v
    }

    private fun startsWith(haystack: ByteArray, needle: ByteArray): Boolean {
        if (haystack.size < needle.size) return false
        for (i in needle.indices) if (haystack[i] != needle[i]) return false
        return true
    }

    fun deflate(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(data) }
        return out.toByteArray()
    }

    fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 2)
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }
}

/**
 * Small XML plist reader. Deliberately not a general XML parser: it only accepts
 * the element set Apple emits, which keeps it short enough to audit.
 */
private class XmlPlistReader(private val text: String) {
    private var pos = 0

    fun parseDocument(): PlistValue {
        val start = text.indexOf("<plist")
        if (start < 0) throw PlistException("missing <plist> root element")
        pos = text.indexOf('>', start) + 1
        if (pos <= 0) throw PlistException("malformed <plist> root element")
        return parseValue()
    }

    private fun parseValue(): PlistValue = when (val tag = nextTag()) {
        "dict" -> parseDict()
        "array" -> parseArray()
        "string" -> PlistValue.StringValue(readText("string"))
        "integer" -> parseInteger(readText("integer").trim())
        "real" -> readText("real").trim().toDoubleOrNull()?.let(PlistValue::DoubleValue)
            ?: throw PlistException("malformed <real> value")
        "data" -> PlistValue.DataValue(
            runCatching {
                java.util.Base64.getMimeDecoder().decode(readText("data").trim())
            }.getOrElse { throw PlistException("malformed <data> value: ${it.message}") },
        )
        "date" -> PlistValue.DateValue(parseDate(readText("date").trim()))
        "true" -> PlistValue.BoolValue(true)
        "false" -> PlistValue.BoolValue(false)
        else -> throw PlistException("unsupported plist element <$tag>")
    }

    private fun parseDict(): PlistValue {
        val map = LinkedHashMap<String, PlistValue>()
        while (true) {
            skipWhitespace()
            if (consume("</dict>")) return PlistValue.DictValue(map)
            val tag = nextTag()
            if (tag != "key") throw PlistException("expected <key> in <dict>, found <$tag>")
            val key = readText("key")
            map[key] = parseValue()
        }
    }

    private fun parseArray(): PlistValue {
        val items = mutableListOf<PlistValue>()
        while (true) {
            skipWhitespace()
            if (consume("</array>")) return PlistValue.ArrayValue(items)
            items += parseValue()
        }
    }

    private fun parseInteger(raw: String): PlistValue {
        if (raw.startsWith("0x") || raw.startsWith("0X")) {
            val v = raw.substring(2).toULongOrNull(16)
                ?: throw PlistException("malformed hex integer '$raw'")
            return PlistValue.UIntValue(v.toLong())
        }
        val bi = try {
            java.math.BigInteger(raw)
        } catch (e: NumberFormatException) {
            throw PlistException("malformed integer '$raw'")
        }
        return when {
            bi.bitLength() < 31 -> PlistValue.IntValue(bi.toInt())
            bi.bitLength() < 63 -> PlistValue.LongValue(bi.toLong())
            else -> PlistValue.BigIntValue(bi)
        }
    }

    private fun parseDate(raw: String): Double {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val d = fmt.parse(raw) ?: throw PlistException("malformed <date> value '$raw'")
        return d.time / 1000.0 - PlistCodec.APPLE_EPOCH_OFFSET
    }

    /**
     * Returns the tag name of the next element, positioning the cursor just past
     * the opening tag. Self-closing tags (`<true/>`) are consumed entirely.
     */
    private fun nextTag(): String {
        skipWhitespace()
        if (pos >= text.length || text[pos] != '<') {
            throw PlistException("expected element at offset $pos but found ${preview()}")
        }
        val start = pos + 1
        var i = start
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-')) i++
        if (i == start) throw PlistException("expected element name at offset $start")
        val name = text.substring(start, i)
        if (text.startsWith("/>", i)) {
            pos = i + 2
        } else if (text.startsWith(">", i)) {
            pos = i + 1
        } else {
            throw PlistException("malformed <$name> opening tag")
        }
        return name
    }

    /** Reads character data up to the matching close tag and consumes it. */
    private fun readText(tag: String): String {
        val close = "</$tag>"
        val end = text.indexOf(close, pos)
        if (end < 0) throw PlistException("unterminated <$tag>")
        val raw = text.substring(pos, end)
        pos = end + close.length
        return unescape(raw)
    }

    private fun consume(literal: String): Boolean {
        if (text.startsWith(literal, pos)) {
            pos += literal.length
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun preview(): String =
        text.substring(pos.coerceAtMost(text.length), (pos + 12).coerceAtMost(text.length))

    private fun unescape(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                sb.append(c)
                i++
                continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0) {
                sb.append(c)
                i++
                continue
            }
            when (val ent = s.substring(i + 1, semi)) {
                "amp" -> sb.append('&')
                "lt" -> sb.append('<')
                "gt" -> sb.append('>')
                "quot" -> sb.append('"')
                "apos" -> sb.append('\'')
                else -> {
                    val cp = when {
                        ent.startsWith("#x") || ent.startsWith("#X") ->
                            ent.substring(2).toIntOrNull(16)
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()
                        else -> null
                    }
                    if (cp != null) sb.appendCodePoint(cp) else sb.append("&$ent;")
                }
            }
            i = semi + 1
        }
        return sb.toString()
    }
}
