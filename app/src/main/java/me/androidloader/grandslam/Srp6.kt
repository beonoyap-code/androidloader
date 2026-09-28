package me.androidloader.grandslam

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory

/** Raised when GrandSlam refuses a step of the handshake. */
class GrandSlamException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The 2048-bit SRP group from RFC 5054, which is what Apple's GrandSlam server
 * uses.
 *
 * The modulus is byte-identical to the reference `srp` crate's `groups/2048.bin`
 * and uses `g = 2`. Written as an exact-length hex constant because a single wrong
 * digit produces a handshake that fails in a way that looks like a wrong password.
 */
object SrpGroup {
    val N: BigInteger = BigInteger(
        "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050" +
            "A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50" +
            "E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B8" +
            "55F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773B" +
            "CA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748" +
            "544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE" +
            "6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB" +
            "694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73",
        16,
    )

    val g: BigInteger = BigInteger.valueOf(2)

    /** Byte width of the modulus; every `to_bytes_be` in the protocol uses it. */
    const val PADDING_LENGTH = 256

    init {
        require(N.bitLength() == 2048) { "the SRP modulus must be 2048 bits" }
    }
}

/**
 * SRP-6a client, matching the exact derivations Apple's GrandSlam server expects.
 *
 * These follow the reference `srp` crate (which `isideload`, and therefore
 * iloader, uses) rather than RFC 5054's appendix, because the deployed server
 * expects the crate's behaviour. The differences from the RFC text are all in the
 * hash inputs, and getting any of them wrong yields a failure that looks exactly
 * like a wrong password:
 *
 *  - identity hash is `H(username | ":" | password)`, not `H(I | ":" | ":" | P)`
 *  - `M1 = H(A | B | K)` and `M2 = H(A | M1 | K)`, with the session key `K`
 *    substituted for the RFC's `k`, and **no** `H(N) XOR H(g)` and `H(I)` terms
 *  - integers are hashed as their minimal big-endian encoding, not zero-padded to
 *    the modulus width
 *
 * The username is the account's email address, and the password passed in is
 * already the PBKDF2 output (see [GrandSlamLogin.deriveVerifierInput]).
 */
class Srp6Client(
    private val username: ByteArray,
    private val password: ByteArray,
    random: SecureRandom = SecureRandom(),
) {

    /** The ephemeral private value `a`, as 32 random bytes. */
    private val a: BigInteger = BigInteger(1, ByteArray(32).also(random::nextBytes))

    /** The ephemeral public value `A = g^a mod N`. */
    val publicA: BigInteger = SrpGroup.g.modPow(a, SrpGroup.N)

    private var sessionKey: ByteArray? = null
    private var expectedServerProof: ByteArray? = null

    /**
     * Computes the session key and the client proof from the server's challenge.
     *
     * @param salt the server's salt `s`
     * @param serverPublicBytes the server's ephemeral `B`, as received
     * @param iterations PBKDF2 iteration count `i` from the server
     * @return the client proof `M1`
     */
    fun processChallenge(
        salt: ByteArray,
        serverPublicBytes: ByteArray,
        iterations: Int,
    ): ByteArray {
        val serverPublic = BigInteger(1, serverPublicBytes)

        // A B of zero mod N would make the shared secret independent of the
        // password. This is the standard SRP defence against a malicious server.
        if (serverPublic.mod(SrpGroup.N) == BigInteger.ZERO) {
            throw GrandSlamException("the server sent an illegal public value; refusing to continue")
        }

        val aBytes = publicA.toMinimalBytes()
        val bBytes = serverPublic.toMinimalBytes()

        val u = BigInteger(1, hash(aBytes + bBytes))
        if (u == BigInteger.ZERO) {
            throw GrandSlamException("the server sent a degenerate scrambling value; refusing to continue")
        }

        // k = H(N | PAD(g)), where PAD right-aligns g in a field the width of the
        // modulus. Hashing a bare `g` byte instead gives a different k, which
        // breaks the shared secret.
        val modulus = SrpGroup.N.toMinimalBytes()
        val k = BigInteger(1, hash(modulus + SrpGroup.g.paddedTo(modulus.size)))
        // x = H(s | H(I | ":" | P)), matching the reference implementation's
        // identity hash rather than RFC 5054's two-separator form.
        val identity = hash(username + ":".toByteArray() + password)
        val x = BigInteger(1, hash(salt + identity))

        // S = (B - k*g^x) ^ (a + u*x) mod N
        val gx = SrpGroup.g.modPow(x, SrpGroup.N)
        val base = serverPublic.subtract(k.multiply(gx)).mod(SrpGroup.N)
        if (base == BigInteger.ZERO) {
            throw GrandSlamException("the server's public value does not yield a usable session key")
        }
        val key = base.modPow(a.add(u.multiply(x)), SrpGroup.N)

        val keyBytes = key.toMinimalBytes()
        sessionKey = keyBytes

        val proof = hash(aBytes + bBytes + keyBytes)
        expectedServerProof = hash(aBytes + proof + keyBytes)
        return proof
    }

    /**
     * Verifies the server's `M2`.
     *
     * This is what makes the exchange mutually authenticating: without it a fake
     * server could collect the proof and impersonate Apple later.
     */
    fun verifyServer(serverProof: ByteArray) {
        val expected = expectedServerProof
            ?: throw GrandSlamException("the server proof was requested before a session existed")
        if (!MessageDigest.isEqual(expected, serverProof)) {
            throw GrandSlamException(
                "the server's proof did not verify. This usually means the connection was " +
                    "intercepted, or Apple changed the handshake.",
            )
        }
    }

    /** The shared session key, used as the HMAC key for every later derivation. */
    fun sessionKey(): ByteArray = sessionKey?.clone()
        ?: throw GrandSlamException("no session key; the challenge has not been processed yet")

    private fun hash(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** Minimal big-endian encoding, with no sign byte and no zero padding. */
private fun BigInteger.toMinimalBytes(): ByteArray {
    val raw = toByteArray()
    return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
}

/** Right-aligns this value in a field [width] bytes wide, as SRP's `PAD` does. */
private fun BigInteger.paddedTo(width: Int): ByteArray {
    val raw = toMinimalBytes()
    if (raw.size == width) return raw
    val out = ByteArray(width)
    System.arraycopy(raw, 0, out, width - raw.size, raw.size)
    return out
}

/**
 * Turns an Apple ID password into the input SRP expects.
 *
 * Apple hashes the password with SHA-256 and then runs PBKDF2 over that hash using
 * the server's salt and iteration count. Under the `s2k_fo` protocol the hash is
 * additionally hex-encoded first, which is a quirk of that variant rather than of
 * SRP.
 */
object GrandSlamLogin {

    /** Password derivations Apple negotiates. */
    object Protocol {
        const val S2K = "s2k"
        const val S2K_FO = "s2k_fo"
    }

    /**
     * @param protocol the `sp` value the server selected
     * @return the 32-byte verifier input for SRP
     */
    fun deriveVerifierInput(
        password: String,
        salt: ByteArray,
        iterations: Int,
        protocol: String,
    ): ByteArray {
        if (iterations <= 0) {
            throw GrandSlamException("the server reported $iterations PBKDF2 iterations")
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray(Charsets.UTF_8))

        val passwordHash: ByteArray = if (protocol == Protocol.S2K_FO) {
            // Hex-encode the SHA-256 digest, ASCII-lowercase, then PBKDF2 the
            // resulting text bytes.
            hex(digest).toByteArray(Charsets.US_ASCII)
        } else {
            digest
        }

        val spec = PBEKeySpec(
            CharArray(passwordHash.size) { i -> passwordHash[i].toInt().toChar() },
            salt,
            iterations,
            256,
        )
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } catch (e: java.security.NoSuchAlgorithmException) {
            // PBKDF2WithHmacSHA256 is present on Android 26+ and in the unit-test
            // JVM, but fall back to a direct implementation rather than failing.
            pbkdf2HmacSha256(passwordHash, salt, iterations, 32)
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /** Minimal PBKDF2-HMAC-SHA256, used only if the platform provider is missing. */
    private fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val out = ByteArray(keyLength)
        var block = 1
        var offset = 0
        while (offset < keyLength) {
            val input = salt + byteArrayOf(
                (block ushr 24).toByte(),
                (block ushr 16).toByte(),
                (block ushr 8).toByte(),
                block.toByte(),
            )
            var u = mac.doFinal(input)
            val acc = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in acc.indices) acc[j] = (acc[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(acc.size, keyLength - offset)
            System.arraycopy(acc, 0, out, offset, n)
            offset += n
            block++
        }
        return out
    }
}

/**
 * Derives the keys GrandSlam uses for its encrypted payloads.
 *
 * Apple's scheme is a handful of HMAC-SHA256 derivations over the SRP session key
 * followed by AES-CBC for the SPD blob and AES-GCM for app tokens. The label
 * strings are part of the protocol and are hashed verbatim.
 */
class GrandSlamKeys(sessionKey: ByteArray) {

    private val key = sessionKey.clone()

    /** GCM authentication tag length in bits. */
    private val TAG_BITS = 128

    private fun derive(label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.UTF_8))
    }

    /** AES-256 key for the SPD payload. */
    private fun spdKey(): ByteArray = derive("extra data key:")

    /** AES-256 IV for the SPD payload; the first 16 bytes of the derivation. */
    private fun spdIv(): ByteArray = derive("extra data iv:").copyOf(16)

    /** Decrypts the SPD blob returned by a completed login. */
    fun decryptSpd(ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(spdKey(), "AES"),
            IvParameterSpec(spdIv()),
        )
        return cipher.doFinal(ciphertext)
    }

    /**
     * Decrypts an app-token envelope.
     *
     * The layout is three length bytes (salt, nonce, tag) followed by those three
     * fields and the ciphertext. Apple supplies the nonce rather than deriving it,
     * so it is read from the envelope.
     */
    fun decryptAppToken(envelope: ByteArray): ByteArray {
        if (envelope.size < 3) {
            throw GrandSlamException("the app token is too short to be valid (${envelope.size} bytes)")
        }
        val saltLength = envelope[0].toInt() and 0xFF
        val nonceLength = envelope[1].toInt() and 0xFF
        val tagLength = envelope[2].toInt() and 0xFF
        var offset = 3
        if (offset + saltLength + nonceLength + tagLength > envelope.size) {
            throw GrandSlamException("the app token header is inconsistent with its length")
        }
        val salt = envelope.copyOfRange(offset, offset + saltLength); offset += saltLength
        val nonce = envelope.copyOfRange(offset, offset + nonceLength); offset += nonceLength
        val tag = envelope.copyOfRange(offset, offset + tagLength); offset += tagLength
        val ciphertext = envelope.copyOfRange(offset, envelope.size)

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val aad = mac.doFinal(salt)

        // The tag is carried in its own envelope field, so it is appended before
        // doFinal: the cipher verifies the trailing GCM tag from the ciphertext.
        val withTag = ciphertext + tag

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(spdKey(), "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(aad)
        return cipher.update(withTag) + cipher.doFinal()
    }

    /**
     * The checksum Apple requires on an app-token request.
     *
     * Bound to the account, the requested service and the literal `apptokens`, so a
     * captured request cannot be replayed against a different service.
     */
    fun appTokenChecksum(dsid: String, app: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        mac.update("apptokens".toByteArray(Charsets.UTF_8))
        mac.update(dsid.toByteArray(Charsets.UTF_8))
        mac.update(app.toByteArray(Charsets.UTF_8))
        return mac.doFinal()
    }
}
