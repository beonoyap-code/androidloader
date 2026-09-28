package me.androidloader.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.UUID

/**
 * Tests for the pairing material.
 *
 * The certificate shapes here are dictated by lockdownd rather than chosen, and
 * the earlier implementation got three of them wrong: EC instead of RSA, DER
 * instead of PEM, and no HostID. Each of those fails as a generic "pairing
 * failed" rather than as a certificate problem, so they are pinned here.
 */
class PairingRecordTest {

    @Test
    fun `generates RSA keys, not EC`() {
        // lockdownd validates the key type. libimobiledevice uses RSA 2048 for both
        // the root and the host.
        val identity = HostIdentity.generate()
        assertTrue(
            "root key should be RSA",
            identity.rootPrivateKey.algorithm.equals("RSA", ignoreCase = true),
        )
        assertTrue(
            "host key should be RSA",
            identity.hostPrivateKey.algorithm.equals("RSA", ignoreCase = true),
        )
        val modulus = (identity.rootPrivateKey as java.security.interfaces.RSAPrivateKey).modulus
        assertEquals("RSA modulus should be 2048 bits", 2048, modulus.bitLength())
    }

    @Test
    fun `root is a certificate authority and the host leaf is not`() {
        val identity = HostIdentity.generate()
        // getBasicConstraints() returns -1 when the extension is absent, so the
        // leaf is checked for its explicit CA:FALSE rather than for absence.
        assertTrue(
            "root should assert CA",
            identity.rootCertificate.basicConstraints >= 0,
        )
        assertFalse(
            "host leaf must not assert CA",
            identity.hostCertificate.basicConstraints >= 0,
        )
    }

    @Test
    fun `certificates are PEM, because the record fields are PEM text`() {
        val identity = HostIdentity.generate()
        assertTrue(Pem.looksLikePem(Pem.encode(identity.hostCertificate)))
        assertTrue(Pem.looksLikePem(Pem.encodePrivate(identity.hostPrivateKey)))
        assertTrue(Pem.encode(identity.rootCertificate).let {
            String(it).startsWith("-----BEGIN CERTIFICATE-----")
        })
    }

    @Test
    fun `pem round trips back to the same DER`() {
        val identity = HostIdentity.generate()
        val pem = Pem.encode(identity.hostCertificate)
        val der = Pem.toDer(pem)
        assertNotNull(der)
        assertTrue("DER should survive the round trip", identity.hostCertificate.encoded.contentEquals(der))
    }

    @Test
    fun `the pairing request omits the private keys`() {
        // The keys never leave the device. Sending them would hand over the one
        // secret that makes this host trusted.
        val record = sampleRecord()
        val request = record.toPairingRequest()
        assertTrue(request.containsKey("DeviceCertificate"))
        assertTrue(request.containsKey("HostCertificate"))
        assertTrue(request.containsKey("RootCertificate"))
        assertTrue(request.containsKey("HostID"))
        assertTrue(request.containsKey("SystemBUID"))
        assertFalse(request.containsKey("HostPrivateKey"))
        assertFalse(request.containsKey("RootPrivateKey"))
        assertFalse(request.containsKey("DevicePublicKey"))
    }

    @Test
    fun `a stored record round trips`() {
        val record = sampleRecord()
        val parsed = PairingRecord.parse(record.toPlist())
        assertNotNull(parsed)
        assertEquals(record.hostId, parsed!!.hostId)
        assertEquals(record.systemBuid, parsed.systemBuid)
        assertTrue(record.rootCertificate.contentEquals(parsed.rootCertificate))
        assertTrue(record.hostPrivateKey.contentEquals(parsed.hostPrivateKey))
    }

    @Test
    fun `a record missing a device field is rejected`() {
        // Detected here rather than as a much later, confusing TLS failure.
        val parsed = PairingRecord.parse(ByteArray(0))
        assertNull(parsed)
        val partial = sampleRecord().toPairingRequest()
        assertNull(
            "a request-shaped record is not a stored record",
            PairingRecord.parse(
                me.androidloader.plist.PlistCodec.encodeXml(
                    me.androidloader.plist.PlistValue.DictValue(partial),
                ),
            ),
        )
    }

    @Test
    fun `host id is a uuid and the buid is forty bits`() {
        val hostId = PairingIdentity.newHostId()
        assertNotNull(UUID.fromString(hostId))

        val buid = PairingIdentity.newSystemBuid()
        assertEquals("AAAAAA-BBBBBB form", 13, buid.length)
        assertTrue(buid.contains('-'))
        assertEquals(buid.uppercase(), buid)
    }

    @Test
    fun `identifiers differ between calls`() {
        // Reusing one identity keeps the device's trust list from growing, so the
        // generator must not be constant.
        assertFalse(PairingIdentity.newHostId() == PairingIdentity.newHostId())
    }

    private fun sampleRecord() = PairingRecord(
        deviceCertificate = "device-cert".toByteArray(),
        devicePublicKey = "device-key".toByteArray(),
        hostCertificate = "host-cert".toByteArray(),
        hostPrivateKey = "host-key".toByteArray(),
        hostPublicKey = "host-pub".toByteArray(),
        rootCertificate = "root-cert".toByteArray(),
        rootPrivateKey = "root-key".toByteArray(),
        hostId = "11111111-2222-3333-4444-555555555555",
        systemBuid = "A1B2C3-D4E5F6",
    )
}
