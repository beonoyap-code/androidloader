package me.androidloader.install

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.androidloader.plist.PlistCodec
import me.androidloader.plist.PlistValue
import me.androidloader.plist.asDict
import me.androidloader.plist.asInt
import me.androidloader.plist.asList
import me.androidloader.plist.asString
import me.androidloader.plist.plistString
import me.androidloader.plist.string
import me.androidloader.usbmux.ServiceFraming
import me.androidloader.usbmux.UsbmuxClient
import me.androidloader.usbmux.UsbmuxProtocol
import me.androidloader.usbmux.UsbmuxSocketChannel
import me.androidloader.usbmux.readOneMessage
import java.io.Closeable

/** Raised when the installation service refuses or fails. */
class InstallException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What the installer reported about the app. */
data class InstallOutcome(
    val bundleIdentifier: String?,
    val displayName: String?,
    val errorCode: Int?,
    val errorDescription: String?,
) {
    val isSuccess: Boolean get() = errorCode == null
}

/**
 * `com.apple.mobile.installation_proxy`, the service that actually installs apps.
 *
 * Unlike most services, installation_proxy multiplexes over a single connection:
 * every request is a length-prefixed plist, and the reply is another plist. A
 * failed install is reported in-band with `Error`, `ErrorCode` and
 * `ErrorDescription` rather than as a transport failure, so the reply has to be
 * inspected rather than just parsed.
 *
 * The bundle itself is not sent here. It is staged over AFC and referenced by path,
 * which is what keeps the install off the slower lockdownd channel.
 */
class InstallationProxyClient private constructor(
    private val channel: UsbmuxSocketChannel,
) : Closeable {

    /** Apple's port for this service. */
    companion object {
        /** Apple's port for this service. */
        const val PORT = 6789

        /** Identifies this host to the service; shown in device logs. */
        const val LABEL = "androidloader"
    }

    suspend fun connect(
        usbmux: UsbmuxClient,
        device: UsbmuxProtocol.Device,
    ): InstallationProxyClient = withContext(Dispatchers.IO) {
        val socket = usbmux.connectTo(device, PORT)
        InstallationProxyClient(UsbmuxSocketChannel(socket))
    }

    /**
     * Installs the bundle staged at [stagedPath].
     *
     * @param options extra keys merged into the options dictionary; used to pass
     *   the container mapping and the `PackageType`.
     */
    suspend fun install(
        stagedPath: String,
        bundleIdentifier: String,
        options: Map<String, PlistValue> = emptyMap(),
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val merged = buildMap {
            putAll(options)
            put("PackagePath", plistString(stagedPath))
            put("CFBundleIdentifier", plistString(bundleIdentifier))
            // ClientOptions is the documented shape; the sibling keys are accepted
            // for older devices that still read the flat form.
            put("ClientOptions", PlistValue.DictValue(options))
        }
        val reply = exchange(
            mapOf(
                "Command" to plistString("Install"),
                "PackagePath" to plistString(stagedPath),
                "ClientOptions" to PlistValue.DictValue(merged),
                "Label" to plistString(LABEL),
            ),
        )
        interpret(reply)
    }

    /** Lists the bundle identifiers currently installed. */
    suspend fun listApplications(): List<InstallOutcome> = withContext(Dispatchers.IO) {
        val reply = exchange(
            mapOf(
                "Command" to plistString("Lookup"),
                "ClientOptions" to PlistValue.DictValue(
                    mapOf(
                        "BundleIDs" to PlistValue.ArrayValue(emptyList()),
                        "ReturnAttributes" to PlistValue.ArrayValue(
                            listOf(plistString("CFBundleIdentifier"), plistString("CFBundleDisplayName")),
                        ),
                    ),
                ),
                "Label" to plistString(LABEL),
            ),
        )
        reply["LookupResult"]?.asList.orEmpty().map { entry ->
            val dict = entry.asDict ?: return@map null
            InstallOutcome(
                bundleIdentifier = dict.string("CFBundleIdentifier"),
                displayName = dict.string("CFBundleDisplayName"),
                errorCode = null,
                errorDescription = null,
            )
        }.filterNotNull()
    }

    /** Removes an installed app. */
    suspend fun uninstall(bundleIdentifier: String): InstallOutcome = withContext(Dispatchers.IO) {
        val reply = exchange(
            mapOf(
                "Command" to plistString("Uninstall"),
                "ApplicationIdentifier" to plistString(bundleIdentifier),
                "Label" to plistString(LABEL),
            ),
        )
        interpret(reply)
    }

    private suspend fun exchange(body: Map<String, PlistValue>): Map<String, PlistValue> {
        val framed = ServiceFraming.plistFrame(body)
        channel.write(framed, 0, framed.size)
        return PlistCodec.decodeDict(channel.readOneMessage())
    }

    /**
     * Reads the outcome out of a reply.
     *
     * A failed install is a successful exchange that reports a problem, so the
     * distinction between transport failure and install failure lives here.
     */
    private fun interpret(reply: Map<String, PlistValue>): InstallOutcome {
        val errorCode = reply["ErrorCode"]?.asInt
        val description = reply.string("ErrorDescription") ?: reply.string("Error")
        if (errorCode != null) {
            throw InstallException(explain(errorCode, description))
        }
        // Some replies nest the payload one level down under Status.
        val status = reply["Status"]?.asDict
        val bundle = status?.string("CFBundleIdentifier") ?: reply.string("CFBundleIdentifier")
        val name = status?.string("CFBundleDisplayName") ?: reply.string("CFBundleDisplayName")
        return InstallOutcome(bundle, name, errorCode = null, errorDescription = null)
    }

    /**
     * Turns install error codes into something actionable.
     *
     * The free-account limits produce -402620395 and friends, and those are the
     * ones a user will hit most, so they get specific advice.
     */
    private fun explain(code: Int, description: String?): String {
        val base = when (code) {
            -402620395 ->
                "Too many apps signed with this Apple ID. A free account allows three every " +
                    "seven days. Remove one and try again."
            -402620396 -> "A signing certificate for this bundle identifier already exists on the device."
            -402620397 -> "The app could not be verified. The bundle is damaged or not signed for this device."
            -402620398 -> "The app's entitlements are not permitted. A free account cannot use this app's capabilities."
            -402620401 -> "The device is not eligible for this app. Check the minimum iOS version."
            -402620414 -> "The device is locked. Unlock the iPhone and try again."
            -402620413 -> "The provisioning profile is missing or expired."
            else -> null
        }
        return buildString {
            append(base ?: "The iPhone refused to install the app (error $code).")
            if (!description.isNullOrBlank()) append(" ").append(description)
        }
    }

    override fun close() {
        channel.close()
    }

}
