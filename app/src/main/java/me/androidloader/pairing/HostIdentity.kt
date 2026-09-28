package me.androidloader.pairing

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

/**
 * The host half of a lockdownd pairing.
 *
 * lockdownd will only talk to a host that can present a certificate chaining to
 * the root the device was told about, so pairing means minting a two-level chain:
 * a self-signed root, and a host certificate signed by it.
 *
 * These deliberately match what libimobiledevice produces, because lockdownd
 * validates the shapes:
 *
 *  - **RSA 2048**, not EC. lockdownd's own key is RSA, and mixing curves here is
 *    the kind of difference that surfaces as a generic pairing failure.
 *  - **PEM**, not DER. The record fields are PEM text, so DER is silently ignored.
 *  - Root is `CA:TRUE` with no key usage; the host leaf is `CA:FALSE` with
 *    `digitalSignature, keyEncipherment`.
 *  - Serial number 0 and a ten-year validity, which is what the reference writes.
 */
object HostIdentity {

    private const val KEY_BITS = 2048
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    private const val VALIDITY_DAYS = 10L * 365

    /**
     * RSA cannot express a notBefore in the past, and the device's clock can drift
     * from the phone's, so the window is skewed backwards by a day.
     */
    private const val NOT_BEFORE_SLACK_MILLIS = 24L * 60 * 60 * 1000

    /** Generates a root and host identity, PEM-encoded ready for a pair record. */
    fun generate(
        random: SecureRandom = SecureRandom(),
        now: Date = Date(),
    ): GeneratedIdentity {
        val rootKeys = newKeyPair(random)
        val rootCert = certificate(
            subjectKeys = rootKeys,
            issuerKeys = rootKeys,
            isCertificateAuthority = true,
            random = random,
            now = now,
        )

        val hostKeys = newKeyPair(random)
        val hostCert = certificate(
            subjectKeys = hostKeys,
            issuerKeys = rootKeys,
            isCertificateAuthority = false,
            random = random,
            now = now,
        )

        return GeneratedIdentity(
            rootCertificate = rootCert,
            rootPrivateKey = rootKeys.private,
            hostCertificate = hostCert,
            hostPrivateKey = hostKeys.private,
            hostPublicKey = hostKeys.public,
        )
    }

    /** The generated material. */
    data class GeneratedIdentity(
        val rootCertificate: X509Certificate,
        val rootPrivateKey: PrivateKey,
        val hostCertificate: X509Certificate,
        val hostPrivateKey: PrivateKey,
        val hostPublicKey: java.security.PublicKey,
    )

    private fun newKeyPair(random: SecureRandom): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(KEY_BITS, random)
        return gen.generateKeyPair()
    }

    private fun certificate(
        subjectKeys: KeyPair,
        issuerKeys: KeyPair,
        isCertificateAuthority: Boolean,
        random: SecureRandom,
        now: Date,
    ): X509Certificate {
        // The reference writes serial 0; some device firmware is unhappy with
        // anything else in this position.
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=androidloader"),
            BigInteger.ZERO,
            Date(now.time - NOT_BEFORE_SLACK_MILLIS),
            Date(now.time + VALIDITY_DAYS * 24 * 60 * 60 * 1000),
            X500Name("CN=androidloader"),
            subjectKeys.public,
        )
        builder.addExtension(
            Extension.basicConstraints,
            true,
            BasicConstraints(isCertificateAuthority),
        )
        if (!isCertificateAuthority) {
            // The leaf signs and encrypts; it is explicitly not a CA.
            builder.addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
            builder.addExtension(
                Extension.extendedKeyUsage,
                false,
                KeyPurposeId.getInstance(org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_clientAuth),
            )
        }
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(issuerKeys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
