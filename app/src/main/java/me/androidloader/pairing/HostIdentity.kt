package me.androidloader.pairing

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
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
 * lockdownd will only talk to a host that can present a certificate chaining to the
 * pairing root it holds for the device, so pairing means minting a two-level chain:
 * a self-signed root and a host certificate signed by it. The key algorithm is
 * EC on P-256, which is what libimobiledevice uses and what Apple's TLS stack
 * accepts.
 */
object HostIdentity {

    private const val VALIDITY_DAYS = 10L * 365

    /**
     * Generates a fresh root certificate and the key material the host will use.
     *
     * @return the root key pair and certificate, plus the host key pair.
     */
    fun generate(
        random: SecureRandom = SecureRandom(),
        now: Date = Date(),
    ): GeneratedIdentity {
        val rootKeys = newKeyPair(random)
        val rootCert = selfSignedCertificate(
            keys = rootKeys,
            commonName = "Androidloader Root",
            serial = BigInteger(64, random),
            now = now,
            isCertificateAuthority = true,
        )

        val hostKeys = newKeyPair(random)
        val hostCert = signedCertificate(
            subjectKeys = hostKeys,
            issuerCert = rootCert,
            issuerKey = rootKeys.private,
            commonName = "Androidloader Host",
            serial = BigInteger(64, random),
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

    /** The material produced by [generate], plus the encoded forms a record needs. */
    data class GeneratedIdentity(
        val rootCertificate: X509Certificate,
        val rootPrivateKey: PrivateKey,
        val hostCertificate: X509Certificate,
        val hostPrivateKey: PrivateKey,
        val hostPublicKey: java.security.PublicKey,
    )

    private fun newKeyPair(random: SecureRandom): KeyPair {
        // EC P-256, which is what libimobiledevice uses and what Apple's TLS stack
        // accepts for a pairing host certificate.
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(EC_P256_PARAMS, random)
        return gen.generateKeyPair()
    }

    private fun selfSignedCertificate(
        keys: KeyPair,
        commonName: String,
        serial: BigInteger,
        now: Date,
        isCertificateAuthority: Boolean,
    ): X509Certificate {
        val subject = X500Name("CN=$commonName")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            serial,
            Date(now.time - NOT_BEFORE_SLACK_MILLIS),
            Date(now.time + VALIDITY_DAYS * 24 * 60 * 60 * 1000),
            subject,
            keys.public,
        )
        addExtensions(builder, isCertificateAuthority)
        return sign(builder, keys.private)
    }

    private fun signedCertificate(
        subjectKeys: KeyPair,
        issuerCert: X509Certificate,
        issuerKey: PrivateKey,
        commonName: String,
        serial: BigInteger,
        now: Date,
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            issuerCert,
            serial,
            Date(now.time - NOT_BEFORE_SLACK_MILLIS),
            Date(now.time + VALIDITY_DAYS * 24 * 60 * 60 * 1000),
            X500Name("CN=$commonName"),
            subjectKeys.public,
        )
        addExtensions(builder, isCertificateAuthority = false)
        return sign(builder, issuerKey)
    }

    /**
     * `isCertificateAuthority` drives the basic-constraints and key-usage
     * extensions. The root needs them to sign; the host leaf must explicitly not
     * be a CA, or some TLS stacks refuse the chain.
     */
    private fun addExtensions(
        builder: X509v3CertificateBuilder,
        isCertificateAuthority: Boolean,
    ) {
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(isCertificateAuthority))
        // Extension.basicConstraints is marked critical, as RFC 5280 requires. Some
        // TLS stacks also expect keyUsage to be critical when it is present.
        builder.addExtension(
            Extension.keyUsage,
            true,
            if (isCertificateAuthority) {
                // Cert sign + CRL sign only; a CA that could also sign app traffic
                // would be a much more useful key to steal.
                KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
            } else {
                // digitalSignature only: the host authenticates itself, it never
                // negotiates a key exchange, so keyEncipherment is misleading.
                KeyUsage(KeyUsage.digitalSignature)
            },
        )
    }

    private fun sign(builder: X509v3CertificateBuilder, key: PrivateKey): X509Certificate {
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(key)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /**
     * Apple's TLS implementation is strict about notBefore being in the past, but a
     * small backwards skew protects against clock drift between the phone and the
     * Android device, which is common.
     */
    private const val NOT_BEFORE_SLACK_MILLIS = 24L * 60 * 60 * 1000

    /**
     * The curve Apple's lockdownd expects for a pairing host certificate. Anything
     * else produces a TLS failure that looks like a stale pairing record.
     */
    private const val CURVE = "secp256r1"

    private val EC_P256_PARAMS: java.security.spec.ECGenParameterSpec =
        java.security.spec.ECGenParameterSpec(CURVE)
}
