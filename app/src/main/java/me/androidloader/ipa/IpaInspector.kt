package me.androidloader.ipa

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Metadata read out of an IPA's `Info.plist`. */
data class IpaMetadata(
    val bundleIdentifier: String,
    val displayName: String,
    val version: String,
    val minimumOsVersion: String,
    val executable: String,
    /** True when the bundle carries embedded provisioning and a signature. */
    val isSigned: Boolean,
    /** The payload directory inside the archive. */
    val payloadRoot: String,
)

/** Raised when a file is not a usable IPA. */
class IpaException(message: String) : Exception(message)

/**
 * Reads the parts of an IPA that the install pipeline needs.
 *
 * The critical output is the bundle identifier, because it is the key the Apple
 * side uses to match the app to a provisioned profile, and the payload root,
 * because the bundle is staged to the device by path.
 *
 * Only the archive is read here. Nothing is extracted, so inspecting a 2 GB IPA
 * costs a few reads rather than a full unpack.
 */
class IpaInspector private constructor(private val zip: ZipFile) : AutoCloseable {

    /** Reads the metadata for the app bundle inside this archive. */
    fun inspect(): IpaMetadata {
        val root = payloadRoot()
        val info = zip.getEntry("$root/Info.plist")
            ?: throw IpaException("the archive has no Info.plist at $root/Info.plist")

        val dict = zip.readPlist(info)

        val bundleId = (dict["CFBundleIdentifier"] as? me.androidloader.plist.PlistValue.StringValue)?.value
            ?: throw IpaException("Info.plist has no CFBundleIdentifier")
        val name = (dict["CFBundleName"] as? me.androidloader.plist.PlistValue.StringValue)?.value
            ?: (dict["CFBundleDisplayName"] as? me.androidloader.plist.PlistValue.StringValue)?.value
            ?: (dict["CFBundleExecutable"] as? me.androidloader.plist.PlistValue.StringValue)?.value
            ?: bundleId.substringAfterLast('.')
        val version = (dict["CFBundleShortVersionString"] as? me.androidloader.plist.PlistValue.StringValue)?.value
            ?: (dict["CFBundleVersion"] as? me.androidloader.plist.PlistValue.StringValue)?.value
            ?: "0"
        val minOs = (dict["MinimumOSVersion"] as? me.androidloader.plist.PlistValue.StringValue)?.value ?: "0"
        val executable = (dict["CFBundleExecutable"] as? me.androidloader.plist.PlistValue.StringValue)?.value
            ?: throw IpaException("Info.plist has no CFBundleExecutable")

        val signed = zip.getEntry("$root/embedded.mobileprovision") != null &&
            zip.getEntry("$root/CodeResources") != null

        return IpaMetadata(
            bundleIdentifier = bundleId,
            displayName = name,
            version = version,
            minimumOsVersion = minOs,
            executable = executable,
            isSigned = signed,
            payloadRoot = root,
        )
    }

    /**
     * Finds the single `.app` directory under `Payload/`.
     *
     * iTunes also puts things like `Symbols/` and store metadata in the archive, so
     * the payload is located by name rather than assumed to be the first entry.
     */
    fun payloadRoot(): String {
        val direct = zip.entries().asSequence()
            .map { it.name }
            .firstOrNull { it.startsWith("Payload/") && it.endsWith(".app/Info.plist") }
        if (direct != null) return direct.removeSuffix("Info.plist")

        val anyApp = zip.entries().asSequence()
            .map { it.name }
            .firstOrNull { it.contains(".app/") && it.endsWith("Info.plist") }
            ?: throw IpaException("the archive contains no .app bundle")
        // Trim to the directory containing the Info.plist.
        return anyApp.substringBeforeLast('/', anyApp).let { "$it/" }
    }

    /** The signature files Apple checks when verifying a bundle. */
    fun codeSignaturePaths(): List<String> = zip.entries().asSequence()
        .map { it.name }
        .filter { it.endsWith("_CodeSignature/CodeResources") }
        .toList()

    override fun close() {
        zip.close()
    }

    companion object {
        /** Opens [file] for inspection. */
        fun open(file: File): IpaInspector = try {
            IpaInspector(ZipFile(file))
        } catch (e: java.util.zip.ZipException) {
            throw IpaException("${file.name} is not a readable archive: ${e.message}")
        }

        /** Convenience for a one-shot read. */
        fun inspect(file: File): IpaMetadata = open(file).use { it.inspect() }
    }
}

private fun ZipFile.readPlist(entry: ZipEntry): Map<String, me.androidloader.plist.PlistValue> =
    getInputStream(entry).use { stream ->
        me.androidloader.plist.PlistCodec.decodeDict(stream.readBytes())
    }
