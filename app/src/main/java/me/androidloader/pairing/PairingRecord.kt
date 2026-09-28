package me.androidloader.pairing

import me.androidloader.plist.PlistCodec
import me.androidloader.plist.PlistValue
import me.androidloader.plist.asData
import me.androidloader.plist.asString
import java.util.UUID

/**
 * A lockdownd pairing record.
 *
 * Field values are PEM text rather than DER, which is what the device writes and
 * expects. The record is what makes a host trusted: the device keeps the root
 * certificate from pairing time and checks every later TLS session against it, so
 * losing this file means pairing again.
 */
data class PairingRecord(
    /** The device's certificate, issued during pairing. */
    val deviceCertificate: ByteArray,
    /** The device's public key, PEM. Used to sign the host's requests. */
    val devicePublicKey: ByteArray,
    val hostCertificate: ByteArray,
    val hostPrivateKey: ByteArray,
    val hostPublicKey: ByteArray,
    val rootCertificate: ByteArray,
    val rootPrivateKey: ByteArray,
    /** Identifies this host to lockdownd; required by `StartSession`. */
    val hostId: String,
    val systemBuid: String,
    val wifiMacAddress: String? = null,
) {
    /**
     * The record as sent to lockdownd during pairing: public material only.
     *
     * The private keys are stripped because they never leave the device, and
     * sending them would be a needless disclosure of the one secret that makes a
     * host trusted.
     */
    fun toPairingRequest(): Map<String, PlistValue> = buildMap {
        put("DeviceCertificate", PlistValue.DataValue(deviceCertificate))
        put("HostCertificate", PlistValue.DataValue(hostCertificate))
        put("HostID", PlistValue.StringValue(hostId))
        put("RootCertificate", PlistValue.DataValue(rootCertificate))
        put("SystemBUID", PlistValue.StringValue(systemBuid))
    }

    /** The full record, as stored locally. */
    fun toPlist(): ByteArray = PlistCodec.encodeXml(
        PlistValue.DictValue(
            buildMap {
                put("DeviceCertificate", PlistValue.DataValue(deviceCertificate))
                put("DevicePublicKey", PlistValue.DataValue(devicePublicKey))
                put("HostCertificate", PlistValue.DataValue(hostCertificate))
                put("HostPrivateKey", PlistValue.DataValue(hostPrivateKey))
                put("HostPublicKey", PlistValue.DataValue(hostPublicKey))
                put("RootCertificate", PlistValue.DataValue(rootCertificate))
                put("RootPrivateKey", PlistValue.DataValue(rootPrivateKey))
                put("HostID", PlistValue.StringValue(hostId))
                put("SystemBUID", PlistValue.StringValue(systemBuid))
                wifiMacAddress?.let { put("WiFiMACAddress", PlistValue.StringValue(it)) }
            },
        ),
    )

    companion object {
        /**
         * Parses the record lockdownd returned.
         *
         * Returns null when a field the device must have supplied is missing, which
         * is how a partial or foreign record is detected without a later, much
         * more confusing TLS failure.
         */
        fun parse(bytes: ByteArray): PairingRecord? {
            val dict = runCatching { PlistCodec.decodeDict(bytes) }.getOrNull() ?: return null
            fun data(key: String): ByteArray? = dict[key]?.asData?.takeIf { it.isNotEmpty() }
            fun required(key: String): ByteArray? = data(key)

            return PairingRecord(
                deviceCertificate = required("DeviceCertificate") ?: return null,
                devicePublicKey = required("DevicePublicKey") ?: return null,
                hostCertificate = required("HostCertificate") ?: return null,
                hostPrivateKey = required("HostPrivateKey") ?: return null,
                hostPublicKey = data("HostPublicKey") ?: return null,
                rootCertificate = required("RootCertificate") ?: return null,
                rootPrivateKey = data("RootPrivateKey") ?: return null,
                hostId = dict["HostID"]?.asString ?: return null,
                systemBuid = dict["SystemBUID"]?.asString ?: return null,
                wifiMacAddress = dict["WiFiMACAddress"]?.asString,
            )
        }
    }
}
