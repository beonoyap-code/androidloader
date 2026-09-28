package me.androidloader.lockdown

import me.androidloader.pairing.KeyMaterial
import me.androidloader.pairing.PairingRecord
import me.androidloader.usbmux.UsbmuxProtocol
import me.androidloader.usbmux.UsbmuxSocketChannel
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * TLS for lockdownd.
 *
 * The channel is the same USB socket, wrapped in TLS after pairing. Two things
 * make this unusual and worth spelling out:
 *
 *  - The device is authenticated by the root certificate stored in the pairing
 *    record, not by any public CA. There is deliberately no system trust store here.
 *  - The `ssl_protocol_version` lockdown key may ask for a specific version.
 *    lockdownd on some iOS builds refuses the handshake outright if the client
 *    offers only TLS 1.3, so the requested version is honoured explicitly.
 */
object LockdownTls {

    /**
     * Wraps [channel] in TLS using the identities from [record].
     *
     * [protocolVersion] carries lockdownd's `ssl_protocol_version` value when
     * known; see [LockdownClient.preferredTlsVersion].
     */
    fun wrap(
        channel: UsbmuxSocketChannel,
        record: PairingRecord,
        device: UsbmuxProtocol.Device,
        protocolVersion: String? = null,
    ): TlsChannel {
        val context = buildContext(record)
        val factory = context.socketFactory

        val raw = underlyingSocket(channel)

        val socket = try {
            factory.createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
        } catch (e: Exception) {
            throw LockdownException("could not start TLS to the iPhone: ${e.message}", e)
        }

        // Apple negotiates with a restricted suite set, and some iOS builds refuse a
        // handshake that offers only TLS 1.3. Enabling explicitly what lockdownd
        // advertises avoids depending on platform defaults.
        socket.enabledProtocols = when (protocolVersion) {
            null -> arrayOf("TLSv1.2", "TLSv1.3").filter { candidate ->
                runCatching { socket.supportedProtocols.contains(candidate) }.getOrDefault(false)
            }.toTypedArray()
            else -> arrayOf(protocolVersion)
        }.ifEmpty { socket.supportedProtocols }

        socket.soTimeout = 30_000
        return TlsChannel(socket)
    }

    private fun buildContext(record: PairingRecord): SSLContext {
        val hostCert = decodeCertificate(record.hostCertificate)
        val rootCert = decodeCertificate(record.rootCertificate)

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        keyStore.setKeyEntry(
            "host",
            hostPrivateKey(record),
            CharArray(0),
            arrayOf(hostCert, rootCert),
        )

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, CharArray(0))
        }

        return SSLContext.getInstance("TLS").apply {
            // Trust exactly the pairing root and nothing else; the system trust
            // store is deliberately not consulted.
            init(kmf.keyManagers, arrayOf(PairingTrustManager(rootCert)), SecureRandom())
        }
    }

    private fun decodeCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate

    private fun hostPrivateKey(record: PairingRecord): java.security.PrivateKey =
        try {
            KeyMaterial.decodePrivateKey(record.hostPrivateKey)
        } catch (e: Exception) {
            throw LockdownException(
                "the pairing record's host private key is unreadable; pair again to replace it",
                e,
            )
        }

    /**
     * Android's own trust manager is bypassed entirely: the only acceptable peer is
     * the device that issued this pairing record.
     */
    private class PairingTrustManager(private val expectedRoot: X509Certificate) :
        X509TrustManager {

        /**
         * This client never acts as a TLS server, so a client certificate is
         * neither offered nor expected.
         */
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            Unit

        /**
         * Accepts only a chain terminating at this pairing's root.
         *
         * The public CA trust store is deliberately not consulted: the device is
         * authenticated by the identity stored in the pairing record, so trusting
         * any CA-signed certificate would let an impostor present a valid
         * certificate for a different device.
         */
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw LockdownException("the iPhone presented no certificate")
            // The chain must terminate at the pairing root recorded for this device.
            // Trusting any CA-signed certificate would let a network attacker
            // present a valid certificate for a different device.
            if (chain.last() != expectedRoot) {
                throw LockdownException(
                    "the TLS peer is not the paired iPhone; the connection may be intercepted",
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(expectedRoot)
    }

    /**
     * Recovers the socket underneath a channel so TLS can be layered on it.
     *
     * The channel owns its socket, so this is a small internal accessor rather than
     * a public one; the socket's read and write streams are already in use, which is
     * fine because the TLS layer reads and writes the same descriptor.
     */
    private fun underlyingSocket(channel: UsbmuxSocketChannel): Socket = channel.rawSocket

}
