package me.androidloader.pairing

import me.androidloader.plist.PlistCodec
import me.androidloader.plist.PlistValue
import me.androidloader.plist.asData
import me.androidloader.plist.plistData
import me.androidloader.usbmux.UsbmuxProtocol
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * The certificate material a pairing attempt needs.
 *
 * Kept separate from [HostIdentity] so that a pairing record can be assembled from
 * an existing set (when re-pairing with a stored identity) or from a freshly
 * generated one, without the two paths having to agree on a single type.
 */
data class HostCertificateSet(
    val rootCertificate: X509Certificate,
    val rootPrivateKey: PrivateKey,
    val hostCertificate: X509Certificate,
    val hostPrivateKey: PrivateKey,
    val hostPublicKey: PublicKey,
) {
    /** Encodes this set into the record shape lockdownd stores. */
    fun toRecord(device: UsbmuxProtocol.Device? = null): PairingRecord = PairingRecord(
        deviceCertificate = ByteArray(0),
        devicePublicKey = ByteArray(0),
        devicePrivateKey = null,
        hostCertificate = hostCertificate.encoded,
        hostPrivateKey = hostPrivateKey.encoded,
        hostPublicKey = hostPublicKey.encoded,
        rootCertificate = rootCertificate.encoded,
        rootPrivateKey = rootPrivateKey.encoded,
        wifiMacAddress = null,
        systemBUID = null,
        udid = device?.udid,
    )

    companion object {
        /** Generates a usable host identity. */
        fun generate(
            random: SecureRandom = SecureRandom(),
            now: Date = Date(),
        ): HostCertificateSet {
            val generated = HostIdentity.generate(random, now)
            return HostCertificateSet(
                rootCertificate = generated.rootCertificate,
                rootPrivateKey = generated.rootPrivateKey,
                hostCertificate = generated.hostCertificate,
                hostPrivateKey = generated.hostPrivateKey,
                hostPublicKey = generated.hostPublicKey,
            )
        }

        /**
         * Rebuilds a set from its stored form.
         *
         * The private keys are stored as PKCS#8 and the certificates as DER, which
         * is what [encode] writes, so a round trip is lossless.
         */
        fun parse(encoded: ByteArray): HostCertificateSet? {
            val dict = runCatching { PlistCodec.decodeDict(encoded) }.getOrNull() ?: return null
            val hostCert = dict["HostCertificate"]?.asData ?: return null
            val rootCert = dict["RootCertificate"]?.asData ?: return null
            val hostKey = dict["HostPrivateKey"]?.asData ?: return null
            val rootKey = dict["RootPrivateKey"]?.asData ?: return null
            return runCatching {
                HostCertificateSet(
                    rootCertificate = KeyMaterial.decodeCertificate(rootCert),
                    rootPrivateKey = KeyMaterial.decodePrivateKey(rootKey),
                    hostCertificate = KeyMaterial.decodeCertificate(hostCert),
                    hostPrivateKey = KeyMaterial.decodePrivateKey(hostKey),
                    hostPublicKey = KeyMaterial.decodeCertificate(hostCert).publicKey,
                )
            }.getOrNull()
        }

        /** Serialises a set for storage alongside the pairing records. */
        fun encode(set: HostCertificateSet): ByteArray = PlistCodec.encodeXml(
            PlistValue.DictValue(
                mapOf(
                    "HostCertificate" to plistData(set.hostCertificate.encoded),
                    "HostPrivateKey" to plistData(set.hostPrivateKey.encoded),
                    "HostPublicKey" to plistData(set.hostPublicKey.encoded),
                    "RootCertificate" to plistData(set.rootCertificate.encoded),
                    "RootPrivateKey" to plistData(set.rootPrivateKey.encoded),
                ),
            ),
        )
    }
}

/** Shared helpers for reading DER key material out of a pairing record. */
internal object KeyMaterial {
    fun decodeCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate

    fun decodePrivateKey(der: ByteArray): PrivateKey {
        // lockdownd stores PKCS#8. The algorithm is inferred rather than assumed,
        // because a record written by another tool may use RSA instead of EC.
        val spec = PKCS8EncodedKeySpec(der)
        for (algorithm in listOf("EC", "RSA", "DSA")) {
            runCatching {
                return java.security.KeyFactory.getInstance(algorithm).generatePrivate(spec)
            }
        }
        throw IllegalArgumentException("unrecognised private key format in the pairing record")
    }
}
