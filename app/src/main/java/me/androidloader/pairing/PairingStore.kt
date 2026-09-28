package me.androidloader.pairing

import android.content.Context
import java.io.File

/**
 * Stores pairing records on the device.
 *
 * A record holds the host private key, so it lives in the app's private directory
 * and never in shared storage. The Apple ID password is stored separately, in the
 * account store, because a record can be re-fetched from usbmuxd while a password
 * cannot be recovered.
 */
object PairingStore {

    /** Loads the record for [udid], or null when this phone has not paired it. */
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

    /**
     * Forgets the record for [udid] so the next attempt re-pairs.
     *
     * Also the remedy when the device stops trusting this phone, which otherwise
     * shows up as a session error with no obvious cause.
     */
    fun forget(context: Context, udid: String) {
        recordFile(context, udid).delete()
    }

    private fun dir(context: Context) = File(context.filesDir, "pairing")

    private fun recordFile(context: Context, udid: String) = File(dir(context), "$udid.plist")
}
