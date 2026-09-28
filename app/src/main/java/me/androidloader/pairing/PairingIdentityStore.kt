package me.androidloader.pairing

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * The stable identifiers this install presents to the device.
 *
 * `hostId` in particular must survive re-pairing: the device keeps a trust list
 * keyed by it, and generating a fresh one each time leaves entries behind that the
 * user cannot see or remove.
 */
class PairingIdentityStore(val hostId: String, val systemBuid: String)

/** Loads or creates this install's pairing identifiers. */
object PairingIdentity {

    /** Reads the identifiers, creating and persisting them on first use. */
    fun load(context: Context): PairingIdentityStore {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            val stored = runCatching { file.readText() }.getOrNull()
            if (stored != null) {
                val hostId = stored.substringBefore('\n').trim()
                val buid = stored.substringAfter('\n', "").trim()
                if (hostId.isNotEmpty() && buid.isNotEmpty()) {
                    return PairingIdentityStore(hostId, buid)
                }
            }
        }
        val fresh = PairingIdentityStore(
            hostId = UUID.randomUUID().toString(),
            systemBuid = newSystemBuid(),
        )
        runCatching {
            file.writeText("${fresh.hostId}\n${fresh.systemBuid}\n")
        }
        return fresh
    }

    /** A new host identifier, in the canonical UUID form. */
    fun newHostId(): String = UUID.randomUUID().toString()

    /**
     * A system BUID: a 40-bit identifier in the hyphenated uppercase form Apple's
     * tooling uses. It distinguishes installs when several hosts pair with a device.
     */
    fun newSystemBuid(random: java.security.SecureRandom = java.security.SecureRandom()): String {
        val bytes = ByteArray(6).also(random::nextBytes)
        return buildString {
            for (i in bytes.indices) {
                if (i == 3) append('-')
                append(String.format("%02X", bytes[i]))
            }
        }
    }

    private const val FILE_NAME = "pairing-identity.txt"
}
