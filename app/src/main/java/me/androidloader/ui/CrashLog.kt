package me.androidloader.ui

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Records the last uncaught exception so the app can show it on next launch.
 *
 * A sideloading tool runs on someone else's phone with no debugger attached, and
 * "it crashed" is not a bug report. Writing the trace to a file the UI surfaces
 * means a failure is diagnosable without adb, which is the only way to get useful
 * information out of a remote test at all.
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_BYTES = 64 * 1024

    /**
     * Installs the handler.
     *
     * The previous handler is always invoked afterwards, so the platform still
     * does its own thing: a swallowed crash would leave the process in an unknown
     * state, which is worse than a visible exit.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val text = buildString {
            append("thread: ").append(thread.name).append('\n')
            append("time: ").append(java.util.Date()).append('\n')
            append("android: ")
                .append(android.os.Build.VERSION.SDK_INT)
                .append(" / ")
                .append(android.os.Build.MANUFACTURER)
                .append('\n')
            append("device: ").append(android.os.Build.DEVICE).append('\n')
            append("app: ")
                .append(BuildConfigCompat.versionName())
                .append('\n')
            append('\n')
            val writer = StringWriter()
            PrintWriter(writer).use { error.printStackTrace(it) }
            append(writer)
        }
        file(context).writeText(text.take(MAX_BYTES))
    }

    /**
     * The recorded trace, or null when the last run ended cleanly.
     *
     * Read once and then cleared, so a crash is reported once rather than
     * sticking to the screen forever.
     */
    fun consume(context: Context): String? {
        val file = file(context)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return text?.takeIf { it.isNotBlank() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}

/**
 * Reads the version name from the manifest.
 *
 * Kept separate from BuildConfig so the app still reports something useful if
 * the generated class is unavailable in a unit test.
 */
private object BuildConfigCompat {
    fun versionName(): String = runCatching {
        Class.forName("me.androidloader.BuildConfig")
            .getField("VERSION_NAME")
            .get(null) as? String
    }.getOrNull() ?: "unknown"
}
