package me.androidloader.pairing

import android.content.Context
import me.androidloader.plist.PlistCodec
import me.androidloader.plist.PlistValue
import me.androidloader.plist.asString
import me.androidloader.plist.plistString
import me.androidloader.plist.string
import java.io.File

/**
 * Stores pairing records on the device.
 *
 * A record contains the host's private key, so it is written to the app's private
 * directory and never to shared storage. The Apple ID password is not stored here:
 * that lives in [me.androidloader.grandslam.AccountStore], keyed separately, because
 * a pairing record can be re-read from usbmuxd while a password cannot be recovered.
 */
object PairingStore {

    /**
     * A per-install identity.
     *
     * lockdownd remembers the host certificate that paired, so reusing one identity
     * across pairs is what avoids filling the device's trust list. The identifier
     * is the standard 40-bit, hyphenated form.
     */
    val systemBuid: String by lazy {
        val entropy = java.security.SecureRandom()
        val bytes = ByteArray(6).also(entropy::nextBytes)
        buildString {
            for ((i, b) in bytes.withIndex()) {
                if (i == 3) append('-')
                append("%02X".format(b))
            }
        }
    }

    /** The identity generated for this install, creating it on first use. */
    fun identity(context: Context, now: java.util.Date = java.util.Date()): HostCertificateSet {
        val file = identityFile(context)
        if (file.exists()) {
            runCatching { HostCertificateSet.parse(file.readBytes()) }
                .getOrNull()
                ?.let { return it }
        }
        val generated = HostCertificateSet.generate(now = now)
        runCatching { file.writeBytes(HostCertificateSet.encode(generated)) }
        return generated
    }

    /** Loads the record for [udid], or null when this device has not been paired. */
    fun load(context: Context, udid: String): PairingRecord? {
        val file = recordFile(context, udid)
        if (!file.exists()) return null
        return runCatching { PairingRecord.parse(file.readBytes()) }.getOrNull()
    }

    /** Writes the record for [udid], replacing any previous one. */
    fun save(context: Context, udid: String, record: PairingRecord) {
        val file = recordFile(context, udid)
        file.parentFile?.mkdirs()
        file.writeBytes(record.toPlist())
    }

    /** Every stored record, keyed by UDID. */
    fun all(context: Context): Map<String, PairingRecord> {
        val dir = dir(context)
        if (!dir.exists()) return emptyMap()
        return dir.listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".plist") }
            .mapNotNull { file ->
                val udid = file.name.removeSuffix(".plist")
                load(context, udid)?.let { udid to it }
            }
            .toMap()
    }

    /** Forgets the record for [udid], so the next attempt re-pairs. */
    fun forget(context: Context, udid: String) {
        recordFile(context, udid).delete()
    }

    private fun dir(context: Context) = File(context.filesDir, "pairing")

    private fun recordFile(context: Context, udid: String) = File(dir(context), "$udid.plist")

    private fun identityFile(context: Context) = File(context.filesDir, "identity.plist")
}
