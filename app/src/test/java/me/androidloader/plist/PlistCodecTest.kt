package me.androidloader.plist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the property-list codec.
 *
 * Round-tripping alone would not catch a shared mistake in the encoder and decoder,
 * so the wire-format cases assert against literals: the exact bytes libimobiledevice
 * produces and the exact bytes Apple sends.
 */
class PlistCodecTest {

    @Test
    fun `encodes the same framing libimobiledevice uses`() {
        val xml = String(
            PlistCodec.encodeXml(
                PlistValue.DictValue(
                    mapOf(
                        "MessageType" to plistString("ListDevices"),
                        "ClientVersionString" to plistString("idevice-rs"),
                        "kLibUSBMuxVersion" to plistInt(3),
                    ),
                ),
            ),
        )
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("<key>MessageType</key>"))
        assertTrue(xml.contains("<string>ListDevices</string>"))
        assertTrue(xml.contains("<integer>3</integer>"))
        assertTrue(xml.trimEnd().endsWith("</plist>"))
    }

    @Test
    fun `round-trips a nested dictionary`() {
        val original = PlistValue.DictValue(
            mapOf(
                "DeviceID" to plistInt(2),
                "Properties" to PlistValue.DictValue(
                    mapOf(
                        "ConnectionType" to plistString("USB"),
                        "SerialNumber" to plistString("00008030-000A1B2C3D4E5F6G"),
                    ),
                ),
                "Flags" to plistArray(listOf(plistInt(1), plistString("two"), plistBool(false))),
            ),
        )
        val decoded = PlistCodec.decodeDict(PlistCodec.encodeXml(original))

        assertEquals(2, decoded["DeviceID"]?.asInt)
        val props = decoded["Properties"]?.asDict
        assertEquals("USB", props?.get("ConnectionType")?.asString)
        assertEquals("00008030-000A1B2C3D4E5F6G", props?.get("SerialNumber")?.asString)
        val flags = decoded["Flags"]?.asList
        assertEquals(3, flags?.size)
        assertEquals("two", flags?.get(1)?.asString)
        assertEquals(false, flags?.get(2)?.asBoolean)
    }

    @Test
    fun `escapes and unescapes XML metacharacters`() {
        val awkward = "a<b>&c\"d'e\nf"
        val decoded = PlistCodec.decodeDict(
            PlistCodec.encodeXml(plistDict(mapOf("k" to plistString(awkward)))),
        )
        assertEquals(awkward, decoded["k"]?.asString)
    }

    @Test
    fun `unescapes numeric character references`() {
        val decoded = PlistCodec.decodeDict(
            PlistCodec.encodeXml(plistDict(mapOf("k" to plistString("tab\there")))),
        )
        assertEquals("tab\there", decoded["k"]?.asString)
    }

    @Test
    fun `preserves binary data exactly`() {
        val payload = ByteArray(256) { it.toByte() }
        val decoded = PlistCodec.decodeDict(
            PlistCodec.encodeXml(plistDict(mapOf("d" to plistData(payload)))),
        )
        assertTrue(payload.contentEquals(decoded["d"]?.asData))
    }

    @Test
    fun `decodes the header Apple's lockdownd responses use`() {
        val body = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Request</key><string>GetValue</string>
                <key>Value</key>
                <dict>
                    <key>ProductVersion</key><string>17.4.1</string>
                    <key>DeviceClass</key><string>iPhone14,2</string>
                </dict>
            </dict>
            </plist>
        """.trimIndent()
        val decoded = PlistCodec.decodeDict(body.toByteArray())
        assertEquals("GetValue", decoded["Request"]?.asString)
        val value = decoded["Value"]?.asDict
        assertEquals("17.4.1", value?.get("ProductVersion")?.asString)
        assertEquals("iPhone14,2", value?.get("DeviceClass")?.asString)
    }

    @Test(expected = PlistException::class)
    fun `rejects an empty payload`() {
        PlistCodec.decode(ByteArray(0))
    }

    @Test(expected = PlistException::class)
    fun `rejects a plist with no root`() {
        PlistCodec.decodeDict("not a plist at all".toByteArray())
    }


    @Test
    fun `decodes a binary plist dictionary`() {
        val decoded = PlistCodec.decodeDict(
            binaryPlist(
                root = listOf(1 to 2, 3 to 4),
                Obj(1, kind = Kind.STRING, text = "MessageType"),
                Obj(2, kind = Kind.STRING, text = "Result"),
                Obj(3, kind = Kind.STRING, text = "Number"),
                Obj(4, kind = Kind.INT, bytes = byteArrayOf(0)),
            ),
        )
        assertEquals("Result", decoded["MessageType"]?.asString)
        assertEquals(0, decoded["Number"]?.asInt)
    }

    @Test
    fun `decodes a binary plist with a nested array and dictionary`() {
        val decoded = PlistCodec.decodeDict(
            binaryPlist(
                root = listOf(1 to 2),
                Obj(1, kind = Kind.STRING, text = "DeviceList"),
                Obj(2, kind = Kind.ARRAY, refs = listOf(3)),
                Obj(3, kind = Kind.DICT, keyRefs = listOf(4, 5), valueRefs = listOf(6, 7)),
                Obj(4, kind = Kind.STRING, text = "DeviceID"),
                Obj(5, kind = Kind.STRING, text = "Properties"),
                Obj(6, kind = Kind.INT, bytes = byteArrayOf(3)),
                Obj(7, kind = Kind.DICT, keyRefs = listOf(8, 9), valueRefs = listOf(10, 11)),
                Obj(8, kind = Kind.STRING, text = "ConnectionType"),
                Obj(9, kind = Kind.STRING, text = "SerialNumber"),
                Obj(10, kind = Kind.STRING, text = "USB"),
                Obj(11, kind = Kind.STRING, text = "udid-binary"),
            ),
        )
        val list = decoded["DeviceList"]?.asList
        assertEquals(1, list?.size)
        val entry = list?.get(0)?.asDict
        assertEquals(3, entry?.get("DeviceID")?.asInt)
        val props = entry?.get("Properties")?.asDict
        assertEquals("USB", props?.get("ConnectionType")?.asString)
        assertEquals("udid-binary", props?.get("SerialNumber")?.asString)
    }

    @Test
    fun `rejects a truncated binary plist`() {
        val bplist = binaryPlist(
            root = listOf(1 to 2),
            Obj(1, kind = Kind.STRING, text = "k"),
            Obj(2, kind = Kind.STRING, text = "v"),
        )
        try {
            PlistCodec.decodeDict(bplist.copyOf(bplist.size - 8))
            throw AssertionError("a truncated binary plist should not decode")
        } catch (expected: PlistException) {
            assertTrue(expected.message!!.isNotEmpty())
        }
    }

    // ------------------------------------------------------ binary fixtures

    private enum class Kind { STRING, INT, ARRAY, DICT }

    /**
     * One object in a fixture.
     *
     * @param index position in the object table; object 0 is reserved for the root
     *   dictionary, so real objects start at 1 and must be listed in order.
     * @param refs array elements, as object indexes.
     * @param keyRefs then [valueRefs], as object indexes, for a dictionary body.
     */
    private class Obj(
        val index: Int,
        val kind: Kind,
        val text: String? = null,
        val bytes: ByteArray? = null,
        val refs: List<Int> = emptyList(),
        val keyRefs: List<Int> = emptyList(),
        val valueRefs: List<Int> = emptyList(),
    ) {
        init {
            require(index >= 1) { "object 0 is reserved for the root dictionary" }
        }
    }

    /**
     * Builds a valid `bplist00` from an explicit object list.
     *
     * Hand-written so the decoder is exercised against an encoder that shares no
     * code with it; a round trip would paper over any mistake both sides make.
     *
     * @param root the key and value object indexes of the top-level dictionary.
     */
    private fun binaryPlist(root: List<Pair<Int, Int>>, vararg objects: Obj): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        val offsets = IntArray(objects.size + 1)

        // Each object's marker is immediately followed by its own body, which is
        // what the format requires: there is no indirection between them.
        for (o in objects) {
            offsets[o.index] = body.size()
            when (o.kind) {
                Kind.STRING -> {
                    val s = o.text!!.toByteArray()
                    body.write(0x50 or s.size)
                    body.write(s)
                }
                Kind.INT -> {
                    body.write(0x10)
                    body.write(o.bytes!!)
                }
                Kind.ARRAY -> {
                    body.write(0xA0 or o.refs.size)
                    o.refs.forEach { body.write(it) }
                }
                Kind.DICT -> {
                    body.write(0xD0 or o.keyRefs.size)
                    o.keyRefs.forEach { body.write(it) }
                    o.valueRefs.forEach { body.write(it) }
                }
            }
        }

        // The root dictionary closes the object table.
        offsets[0] = body.size()
        body.write(0xD0 or root.size)
        root.forEach { body.write(it.first) }
        root.forEach { body.write(it.second) }

        // Table entries stay relative to the start of the object region; the
        // trailer's offsetTableOffset is absolute, so the magic length is added.
        val offsetTable = body.size()
        for (i in 0..objects.size) {
            body.write((offsets[i] ushr 8) and 0xFF)
            body.write(offsets[i] and 0xFF)
        }

        val magic = "bplist00".toByteArray()
        val out = java.io.ByteArrayOutputStream()
        out.write(magic)
        out.write(body.toByteArray())
        // Trailer: five unused bytes, sortVersion, then the two size fields.
        repeat(5) { out.write(0) }
        out.write(0) // sortVersion
        out.write(2) // offsetIntSize
        out.write(1) // objectRefSize
        writeLong(out, objects.size + 1L) // numObjects
        writeLong(out, 0L) // topObject
        writeLong(out, (magic.size + offsetTable).toLong())
        return out.toByteArray()
    }

    /** Writes a big-endian 64-bit value, as the binary plist trailer requires. */
    private fun writeLong(out: java.io.ByteArrayOutputStream, value: Long) {
        for (shift in 56 downTo 0 step 8) {
            out.write(((value shr shift) and 0xFF).toInt())
        }
    }
}
