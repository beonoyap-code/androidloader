package me.androidloader.usbmux

import me.androidloader.plist.PlistCodec
import me.androidloader.plist.PlistValue
import me.androidloader.plist.asString
import me.androidloader.plist.plistInt
import me.androidloader.usbmux.ServiceFraming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the usbmuxd wire format.
 *
 * The framing assertions check literal bytes rather than a round trip, because a
 * round trip cannot catch a header field that is consistently wrong in both
 * directions.
 */
class UsbmuxProtocolTest {

    @Test
    fun `frames use a 16-byte little-endian header`() {
        val frame = UsbmuxProtocol.listDevicesRequest()
        val total = leInt(frame, 0)
        assertEquals(frame.size, total)
        assertEquals(UsbmuxProtocol.VERSION_XML, leInt(frame, 4))
        assertEquals(UsbmuxProtocol.MESSAGE_PLIST, leInt(frame, 8))
        assertEquals(0, leInt(frame, 12))
    }

    @Test
    fun `list devices request carries the fields usbmuxd reads`() {
        val body = PlistCodec.decodeDict(
            UsbmuxProtocol.listDevicesRequest().copyOfRange(16, UsbmuxProtocol.listDevicesRequest().size),
        )
        assertEquals("ListDevices", body["MessageType"]?.asString)
        assertEquals(3, body["kLibUSBMuxVersion"]?.let { (it as? PlistValue.IntValue)?.value })
    }

    @Test
    fun `connect byte-swaps the port as the reference client does`() {
        // lockdownd is 62078 == 0xF27E. The field is read back as little-endian, so
        // the reference client's `port.to_be()` puts 0x7EF2 on the wire.
        assertEquals(0x7EF2, UsbmuxProtocol.portToBigEndian(62078))
        assertEquals(62078, UsbmuxProtocol.portToBigEndian(0x7EF2))
        // AFC is 50333; the swap is its own inverse.
        assertEquals(50333, UsbmuxProtocol.portToBigEndian(UsbmuxProtocol.portToBigEndian(50333)))

        val request = UsbmuxProtocol.connectRequest(deviceId = 4, port = 62078)
        val body = PlistCodec.decodeDict(request.copyOfRange(16, request.size))
        assertEquals(4, (body["DeviceID"] as? PlistValue.IntValue)?.value)
        assertEquals(0x7EF2, (body["PortNumber"] as? PlistValue.IntValue)?.value)
    }

    @Test
    fun `parses a USB device list`() {
        val body = PlistCodec.decodeDict(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
              <key>DeviceList</key>
              <array>
                <dict>
                  <key>DeviceID</key><integer>4</integer>
                  <key>Properties</key>
                  <dict>
                    <key>ConnectionType</key><string>USB</string>
                    <key>SerialNumber</key><string>00008030-001A2B3C4D5E6F7H</string>
                  </dict>
                </dict>
              </array>
            </dict></plist>
            """.trimIndent().toByteArray(),
        )
        val devices = UsbmuxProtocol.parseDeviceList(body)
        assertEquals(1, devices.size)
        assertEquals(4, devices[0].deviceId)
        assertEquals("00008030-001A2B3C4D5E6F7H", devices[0].udid)
        assertEquals(UsbmuxProtocol.ConnectionType.Usb, devices[0].connection)
    }

    @Test
    fun `parses a network device address`() {
        // A sockaddr-style blob: family 0x02 (AF_INET), a two-byte port, then the
        // four address bytes 192.168.1.5.
        val address = byteArrayOf(
            0x02, 0x1F.toByte(), 0x90.toByte(), // family, then a two-byte port
            0x00, // address
            192.toByte(), 168.toByte(), 1, 5,
        )
        val body = PlistCodec.decodeDict(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
              <key>DeviceList</key>
              <array>
                <dict>
                  <key>DeviceID</key><integer>7</integer>
                  <key>Properties</key>
                  <dict>
                    <key>ConnectionType</key><string>Network</string>
                    <key>SerialNumber</key><string>network-udid</string>
                    <key>NetworkAddress</key>
                    <data>${java.util.Base64.getEncoder().encodeToString(address)}</data>
                  </dict>
                </dict>
              </array>
            </dict></plist>
            """.trimIndent().toByteArray(),
        )
        val devices = UsbmuxProtocol.parseDeviceList(body)
        assertEquals(1, devices.size)
        val connection = devices[0].connection
        assertTrue("expected a network connection, got $connection", connection is UsbmuxProtocol.ConnectionType.Network)
        assertEquals("192.168.1.5", (connection as UsbmuxProtocol.ConnectionType.Network).address)
    }

    @Test
    fun `skips entries without a serial number instead of failing`() {
        val body = PlistCodec.decodeDict(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
              <key>DeviceList</key>
              <array>
                <dict><key>DeviceID</key><integer>1</integer></dict>
                <dict>
                  <key>DeviceID</key><integer>2</integer>
                  <key>Properties</key>
                  <dict>
                    <key>ConnectionType</key><string>USB</string>
                    <key>SerialNumber</key><string>good</string>
                  </dict>
                </dict>
              </array>
            </dict></plist>
            """.trimIndent().toByteArray(),
        )
        val devices = UsbmuxProtocol.parseDeviceList(body)
        assertEquals(1, devices.size)
        assertEquals("good", devices[0].udid)
    }

    @Test
    fun `returns an empty list when there is no device list`() {
        assertEquals(emptyList<UsbmuxProtocol.Device>(), UsbmuxProtocol.parseDeviceList(emptyMap()))
    }

    @Test
    fun `frame reader assembles frames split across reads`() {
        val reader = UsbmuxProtocol.FrameReader()
        val reply = replyFrame(mapOf("Number" to plistInt(0)))

        // Feed one byte at a time; the frame must only appear once complete.
        for (i in reply.indices) {
            reader.offer(reply.copyOfRange(i, i + 1))
            if (i < reply.size - 1) {
                assertNull("frame emitted early at byte $i", reader.poll())
            }
        }
        val frame = reader.poll()
        assertNotNull(frame)
        assertEquals(0, frame!!.resultCode)
    }

    @Test
    fun `frame reader yields two frames from one chunk`() {
        val reader = UsbmuxProtocol.FrameReader()
        val a = replyFrame(mapOf("Number" to plistInt(0)))
        val b = replyFrame(mapOf("Number" to plistInt(3)))
        reader.offer(a + b)

        assertEquals(0, reader.poll()?.resultCode)
        assertEquals(3, reader.poll()?.resultCode)
        assertNull(reader.poll())
    }

    @Test
    fun `result code three explains the refused connection`() {
        val reader = UsbmuxProtocol.FrameReader()
        reader.offer(replyFrame(mapOf("Number" to plistInt(3))))
        val frame = reader.poll()!!
        val message = try {
            frame.requireSuccess()
            "no exception"
        } catch (e: UsbmuxProtocolException) {
            e.message ?: ""
        }
        assertTrue("message should mention unlocking the device: $message", message.contains("unlock"))
    }

    @Test
    fun `result code two names the unknown device`() {
        val reader = UsbmuxProtocol.FrameReader()
        reader.offer(replyFrame(mapOf("Number" to plistInt(2))))
        val frame = reader.poll()!!
        val message = try {
            frame.requireSuccess()
            "no exception"
        } catch (e: UsbmuxProtocolException) {
            e.message ?: ""
        }
        assertTrue(message.contains("know that device"))
    }

    @Test
    fun `a zero result code does not throw`() {
        val frame = UsbmuxProtocol.parseDeviceList(emptyMap()).let {
            UsbmuxProtocol.FrameReader().also { r -> r.offer(replyFrame(mapOf("Number" to plistInt(0)))) }
                .poll()!!
        }
        frame.requireSuccess()
    }

    @Test
    fun `service framing uses a big-endian length prefix`() {
        val framed = ServiceFraming.frame("hello".toByteArray())
        assertEquals(5, ServiceFraming.lengthOf(framed.copyOfRange(0, 4)))
        assertEquals("hello", String(framed.copyOfRange(4, framed.size)))

        // A length above 255 must occupy all four bytes.
        val big = ServiceFraming.frame(ByteArray(300))
        assertEquals(300, ServiceFraming.lengthOf(big.copyOfRange(0, 4)))
    }

    @Test
    fun `service framing reports no length for a partial prefix`() {
        assertNull(ServiceFraming.lengthOf(byteArrayOf(0, 0)))
    }

    private fun replyFrame(body: Map<String, PlistValue>): ByteArray {
        val payload = PlistCodec.encodeXml(PlistValue.DictValue(body))
        val out = ByteArray(16 + payload.size)
        leBytes(out, 0, out.size)
        leBytes(out, 4, UsbmuxProtocol.VERSION_XML)
        leBytes(out, 8, UsbmuxProtocol.MESSAGE_RESULT)
        leBytes(out, 12, 0)
        payload.copyInto(out, 16)
        return out
    }

    private fun leInt(buf: ByteArray, offset: Int) =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun leBytes(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
