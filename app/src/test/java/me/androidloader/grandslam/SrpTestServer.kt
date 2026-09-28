package me.androidloader.grandslam

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A minimal SRP-6a *server*, used only by the tests.
 *
 * Written from RFC 5054 rather than by reusing the client, so the round-trip test
 * proves the two derivations agree instead of proving the client is consistent with
 * itself. This is what catches a wrong hash input: both sides would still be
 * self-consistent if the client were wrong on its own.
 */
class SrpTestServer(
    /** The account name. The server needs it only to recompute `x` if asked. */
    private val username: ByteArray,
    password: ByteArray,
    private val salt: ByteArray,
    random: SecureRandom = SecureRandom(),
) {
    /**
     * The private key `x = H(s | H(I | ":" | P))`.
     *
     * The PBKDF2 output is the *password* argument to this hash, not `x` itself.
     * Conflating the two is the classic way to build a server that verifies
     * nothing.
     */
    private val x: BigInteger = BigInteger(
        1,
        hash(salt + hash(username + ":".toByteArray() + password)),
    )

    /** The stored verifier `v = g^x mod N`. */
    private val verifier: BigInteger = SrpGroup.g.modPow(x, SrpGroup.N)

    /** The modulus in wire form, which is the width `k` pads `g` to. */
    private val modulus: ByteArray = SrpGroup.N.toMinimal()

    /** `k = H(N | PAD(g))`, the shared multiplier both sides need. */
    private val k: BigInteger = BigInteger(1, hash(modulus + padTo(modulus.size, SrpGroup.g)))

    private val b: BigInteger = BigInteger(1, ByteArray(32).also(random::nextBytes))

    /**
     * The server's ephemeral public value, `B = k·v + g^b mod N`.
     *
     * The `k·v` term is what lets the client remove it algebraically; leaving it out
     * produces a `B` the client cannot complete the handshake with.
     */
    val publicB: BigInteger = k.multiply(verifier).add(SrpGroup.g.modPow(b, SrpGroup.N))
        .mod(SrpGroup.N)

    /** [publicB] in the wire form the client expects. */
    val publicBBytes: ByteArray = publicB.toMinimal()

    /** The PBKDF2 output, i.e. what the client is asked to prove knowledge of. */

    /**
     * Handles the client's public value and proof.
     *
     * The premaster secret uses the server-side form `(A · v^u)^b` rather than the
     * client's `(B - k·g^x)^(a + u·x)`. The two are algebraically equal, but only
     * if each is built exactly as its own derivation defines it, so this mirrors
     * the reference server's expression instead of rearranging it.
     *
     * @return the proof to send back, and the session key, so the test can assert
     *   that both sides derived the same value.
     */
    fun process(clientPublicA: BigInteger, clientProof: ByteArray): Pair<ByteArray, ByteArray> {
        val aBytes = clientPublicA.toMinimal()
        val bBytes = publicB.toMinimal()

        val u = BigInteger(1, hash(aBytes + bBytes))
        val key = clientPublicA.multiply(verifier.modPow(u, SrpGroup.N))
            .mod(SrpGroup.N)
            .modPow(b, SrpGroup.N)
            .toMinimal()

        // M2 = H(A | M1 | K)
        val serverProof = hash(aBytes + clientProof + key)
        return serverProof to key
    }

    private fun hash(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}

private fun BigInteger.toMinimal(): ByteArray {
    val raw = toByteArray()
    return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
}

/** Right-aligns [value] in a field [width] bytes wide, as SRP's `PAD` does. */
private fun padTo(width: Int, value: BigInteger): ByteArray {
    val raw = value.toMinimal()
    if (raw.size == width) return raw
    val out = ByteArray(width)
    System.arraycopy(raw, 0, out, width - raw.size, raw.size)
    return out
}
