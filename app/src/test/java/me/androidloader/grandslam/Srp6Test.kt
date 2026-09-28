package me.androidloader.grandslam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Tests for the SRP-6a implementation.
 *
 * Two things are verified: that the group is the exact one the server uses, and
 * that a full exchange reaches the same shared secret on both sides. The second is
 * the real test, because an error anywhere in the derivation chain still produces a
 * self-consistent client.
 */
class Srp6Test {

    @Test
    fun `group modulus is 2048 bits with g equal to 2`() {
        assertEquals(2048, SrpGroup.N.bitLength())
        assertEquals(256, SrpGroup.N.toByteArray().let { b ->
            if (b.size > 1 && b[0] == 0.toByte()) b.size - 1 else b.size
        })
        assertEquals(BigInteger.valueOf(2), SrpGroup.g)
    }

    /**
     * The modulus must be exactly the RFC 5054 value. A single wrong digit still
     * yields a 2048-bit number that passes the bit-length check, so the hex is
     * compared literally.
     */
    @Test
    fun `group modulus matches RFC 5054 exactly`() {
        val expected = buildString {
            append("AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050")
            append("A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50")
            append("E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8")
            append("55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B")
            append("CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748")
            append("544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE")
            append("6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB")
            append("694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73")
        }
        assertEquals(expected, SrpGroup.N.toString(16).uppercase())
    }

    @Test
    fun `a full exchange reaches the same secret on both sides`() {
        val username = "someone@example.com".toByteArray()
        val password = "correct horse battery staple".toByteArray()
        val salt = "a-random-salt".toByteArray()

        // The server side, computed independently from RFC 5054.
        val verifierInput = GrandSlamLogin.deriveVerifierInput(
            password = "correct horse battery staple",
            salt = salt,
            iterations = 20_000,
            protocol = GrandSlamLogin.Protocol.S2K,
        )
        val server = SrpTestServer(username, verifierInput, salt)

        val client = Srp6Client(username, verifierInput, random = SecureRandom())
        // The wire value is the minimal big-endian encoding of B.
        val clientProof = client.processChallenge(salt, server.publicBBytes, 20_000)

        val (serverProof, sessionKey) = server.process(client.publicA, clientProof)
        // Assert the shared secret first: if M2 disagreed, the cause would be
        // clearer than a bare "proof did not verify".
        assertTrue(
            "client and server must agree on the session key",
            client.sessionKey().contentEquals(sessionKey),
        )
        client.verifyServer(serverProof)

        assertTrue("client and server must agree on the session key", client.sessionKey().contentEquals(sessionKey))
    }

    @Test
    fun `a wrong password yields a different secret`() {
        val username = "someone@example.com"
        val salt = "salt-value".toByteArray()
        val iterations = 10_000

        val right = GrandSlamLogin.deriveVerifierInput("right", salt, iterations, GrandSlamLogin.Protocol.S2K)
        val wrong = GrandSlamLogin.deriveVerifierInput("wrong", salt, iterations, GrandSlamLogin.Protocol.S2K)

        val server = SrpTestServer(username.toByteArray(), right, salt)
        val client = Srp6Client(username.toByteArray(), wrong, random = SecureRandom())
        client.processChallenge(salt, server.publicBBytes, iterations)
        val (_, serverKey) = server.process(client.publicA, ByteArray(32))

        assertTrue("a wrong password must not reproduce the session key", !client.sessionKey().contentEquals(serverKey))
    }

    @Test
    fun `a forged server proof is rejected`() {
        val username = "someone@example.com".toByteArray()
        val password = "hunter2".toByteArray()
        val salt = "salty".toByteArray()
        val iterations = 10_000
        val verifier = GrandSlamLogin.deriveVerifierInput("hunter2", salt, iterations, GrandSlamLogin.Protocol.S2K)

        val server = SrpTestServer(username, verifier, salt)
        val client = Srp6Client(username, verifier, random = SecureRandom())
        client.processChallenge(salt, server.publicBBytes, iterations)

        try {
            client.verifyServer(ByteArray(32))
            fail("a zeroed server proof should be rejected")
        } catch (expected: GrandSlamException) {
            assertTrue(expected.message!!.contains("intercepted"))
        }
    }

    @Test
    fun `a degenerate server public value is refused`() {
        val client = Srp6Client(
            "someone@example.com".toByteArray(),
            ByteArray(32),
            random = SecureRandom(),
        )
        try {
            // B = N is zero mod N, which would make the secret password-independent.
            client.processChallenge(ByteArray(4), SrpGroup.N.toByteArray().let { BigInteger(1, it).toByteArray() }, 1000)
            fail("B = N should be rejected")
        } catch (expected: GrandSlamException) {
            assertTrue(expected.message!!.contains("illegal"))
        }
    }

    @Test
    fun `verifier input is 32 bytes for both s2k variants`() {
        val salt = ByteArray(8) { it.toByte() }
        val plain = GrandSlamLogin.deriveVerifierInput("pw", salt, 10_000, GrandSlamLogin.Protocol.S2K)
        val hex = GrandSlamLogin.deriveVerifierInput("pw", salt, 10_000, GrandSlamLogin.Protocol.S2K_FO)
        assertEquals(32, plain.size)
        assertEquals(32, hex.size)
        // The two variants differ, which is why the protocol name is honoured.
        assertTrue(!plain.contentEquals(hex))
    }

    @Test
    fun `verifier input matches a direct pbkdf2 computation`() {
        val salt = "the-salt".toByteArray()
        val iterations = 4096
        val digest = MessageDigest.getInstance("SHA-256").digest("password".toByteArray())

        val expected = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(
                javax.crypto.spec.PBEKeySpec(
                    CharArray(digest.size) { i -> digest[i].toInt().toChar() },
                    salt,
                    iterations,
                    256,
                ),
            )
            .encoded

        val actual = GrandSlamLogin.deriveVerifierInput(
            "password", salt, iterations, GrandSlamLogin.Protocol.S2K,
        )
        assertTrue("PBKDF2 output must match the platform provider", actual.contentEquals(expected))
    }

    @Test
    fun `a non-positive iteration count is rejected`() {
        try {
            GrandSlamLogin.deriveVerifierInput("pw", ByteArray(4), 0, GrandSlamLogin.Protocol.S2K)
            fail("zero iterations should be rejected")
        } catch (expected: GrandSlamException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `app token checksum is bound to account and app`() {
        val keys = GrandSlamKeys(ByteArray(32) { it.toByte() })
        val a = keys.appTokenChecksum("adsid-1", "com.apple.gs.ideservices")
        val b = keys.appTokenChecksum("adsid-2", "com.apple.gs.ideservices")
        val c = keys.appTokenChecksum("adsid-1", "com.apple.gs.developer")
        assertEquals(32, a.size)
        assertTrue("the checksum must depend on the account", !a.contentEquals(b))
        assertTrue("the checksum must depend on the app", !a.contentEquals(c))
    }
}
