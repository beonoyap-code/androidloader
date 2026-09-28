package me.androidloader.pairing

import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey

/**
 * PEM encoding for the key material in a pairing record.
 *
 * lockdownd stores these fields as PEM text, not DER. Sending DER is not rejected
 * loudly, it simply fails to parse as a certificate, so the mismatch shows up much
 * later as an unexplained pairing error.
 */
object Pem {

    private const val LINE_LENGTH = 64

    /** Encodes a certificate. */
    fun encode(certificate: X509Certificate): ByteArray =
        encode(certificate.encoded, "CERTIFICATE")

    /** Encodes a private key as PKCS#8. */
    fun encodePrivate(key: PrivateKey): ByteArray =
        encode(key.encoded, "PRIVATE KEY")

    /** Encodes a public key as SubjectPublicKeyInfo. */
    fun encodePublic(key: PublicKey): ByteArray =
        encode(key.encoded, "PUBLIC KEY")

    private fun encode(der: ByteArray, label: String): ByteArray {
        val base64 = java.util.Base64.getEncoder().encodeToString(der)
        val out = StringBuilder(base64.length + label.length * 2 + 32)
        out.append("-----BEGIN ").append(label).append("-----\n")
        for (i in base64.indices step LINE_LENGTH) {
            out.append(base64, i, minOf(i + LINE_LENGTH, base64.length)).append('\n')
        }
        out.append("-----END ").append(label).append("-----\n")
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    /** Strips PEM armour, returning the DER payload, or null if there is none. */
    fun toDer(pem: ByteArray): ByteArray? {
        val text = String(pem, Charsets.US_ASCII)
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .filterNot { it.isWhitespace() }
        if (text.isEmpty()) return null
        return runCatching { java.util.Base64.getDecoder().decode(text) }.getOrNull()
    }

    /** True when the value looks like PEM rather than raw DER. */
    fun looksLikePem(value: ByteArray): Boolean =
        String(value, Charsets.US_ASCII).contains("-----BEGIN")
}
