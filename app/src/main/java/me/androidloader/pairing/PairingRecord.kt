package me.androidloader.pairing

import me.androidloader.plist.PlistCodec
import me.androidloader.plist.PlistValue
import me.androidloader.plist.asData
import me.androidloader.plist.asString

/**
 * A lockdownd pairing record, equivalent to libimobiledevice's
 * `PairingRecord` / idevice's `PairingFile`.
 *
 * The record is a plist holding the device's root certificate, its identity
 * certificate and private key, and the host's certificate and key. Both sides need
 * their key material to open a mutually-authenticated TLS session, which is what
 * every privileged lockdownd request afterwards depends on.
 */
data class PairingRecord(
    val deviceCertificate: ByteArray,
    val devicePublicKey: ByteArray,
    val devicePrivateKey: ByteArray?,
    val hostCertificate: ByteArray,
    val hostPrivateKey: ByteArray,
    val hostPublicKey: ByteArray,
    val rootCertificate: ByteArray,
    val rootPrivateKey: ByteArray?,
    val wifiMacAddress: String?,
    val systemBUID: String?,
    val udid: String?,
) {
    fun toPlist(): ByteArray = PlistCodec.encodeXml(
        PlistValue.DictValue(
            buildMap {
                put("DeviceCertificate", PlistValue.DataValue(deviceCertificate))
                put("DevicePublicKey", PlistValue.DataValue(devicePublicKey))
                devicePrivateKey?.let { put("DevicePrivateKey", PlistValue.DataValue(it)) }
                put("HostCertificate", PlistValue.DataValue(hostCertificate))
                put("HostPrivateKey", PlistValue.DataValue(hostPrivateKey))
                put("HostPublicKey", PlistValue.DataValue(hostPublicKey))
                put("RootCertificate", PlistValue.DataValue(rootCertificate))
                rootPrivateKey?.let { put("RootPrivateKey", PlistValue.DataValue(it)) }
                wifiMacAddress?.let { put("WiFiMACAddress", PlistValue.StringValue(it)) }
                systemBUID?.let { put("SystemBUID", PlistValue.StringValue(it)) }
                udid?.let { put("UDID", PlistValue.StringValue(it)) }
            },
        ),
    )

    companion object {
        /** Parses a pairing record, or returns null when a required field is absent. */
        fun parse(bytes: ByteArray): PairingRecord? {
            val dict = runCatching { PlistCodec.decodeDict(bytes) }.getOrNull() ?: return null
            fun data(key: String): ByteArray? = dict[key]?.asData
            fun requiredData(key: String): ByteArray? =
                data(key)?.takeIf { it.isNotEmpty() }

            return PairingRecord(
                deviceCertificate = requiredData("DeviceCertificate") ?: return null,
                devicePublicKey = requiredData("DevicePublicKey") ?: return null,
                devicePrivateKey = data("DevicePrivateKey"),
                hostCertificate = requiredData("HostCertificate") ?: return null,
                hostPrivateKey = requiredData("HostPrivateKey") ?: return null,
                hostPublicKey = requiredData("HostPublicKey") ?: return null,
                rootCertificate = requiredData("RootCertificate") ?: return null,
                rootPrivateKey = data("RootPrivateKey"),
                wifiMacAddress = dict["WiFiMACAddress"]?.asString,
                systemBUID = dict["SystemBUID"]?.asString,
                udid = dict["UDID"]?.asString,
            )
        }
    }
}
