// Crash log (superprompt §6.6; Fase 6).
//
// BunnyLock had no crash log of its own — only "copy diagnostics" in
// Tools. This is SignalX 1.0.9's CrashLog (SignalXApp.kt), ported as-is
// in behavior: an uncaught error writes version + phone + date + stack
// trace to a file, and the last 5 files are kept.
//
// The files live in the app's external files dir
// (Android/data/dev.ririxi.bunnylock/files/crash-logs/), NOT in the
// database and NOT in internal files: "Delete all field data" clears
// the SQLite tables only, so the crash log survives it — the error has
// to survive to be sent to whoever maintains the app. (Uninstall still
// removes the dir; that is Android's rule, not ours.)
//
// The log can never break the app: every write is guarded, and a
// failure to write only loses the record.
//
// The handler keeps SignalX's one exception: an error on a Mapsforge
// tile thread does NOT kill the app — it is logged, the online-tile
// crash marker is set (so the map reopens in grid mode next time),
// and only that thread dies. On the main thread the error is never
// swallowed: the app closes as usual after the log is written.

package dev.ririxi.bunnylock.signalx

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLog {
    const val DIR_NAME = "crash-logs"
    private const val MAX_FILES = 5

    /** The log folder: external app files when possible, internal files when not. */
    fun dir(context: Context): File? {
        val ext = try { context.getExternalFilesDir(null) } catch (_: Exception) { null }
        val base = ext ?: try { context.filesDir } catch (_: Exception) { return null }
        return File(base, DIR_NAME).also {
            try { it.mkdirs() } catch (_: Exception) { }
        }
    }

    /** The newest crash log, or null when there is none. */
    fun latest(context: Context): File? =
        try {
            dir(context)?.listFiles()
                ?.filter { it.name.startsWith("crash-") && it.name.endsWith(".txt") }
                ?.maxByOrNull { it.name }
        } catch (_: Exception) {
            null
        }

    /** Deletes the newest crash log. Returns true when a file was deleted. */
    fun deleteLatest(context: Context): Boolean =
        try {
            latest(context)?.delete() == true
        } catch (_: Exception) {
            false
        }

    fun write(
        context: Context,
        throwable: Throwable,
    ) {
        try {
            val d = dir(context) ?: return
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val version = try {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                "${pi.versionName} (vc ${pi.versionCode})"
            } catch (_: Exception) {
                "?"
            }
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            File(d, "crash-$stamp.txt").writeText(
                "bunnylock $version\n" +
                    "Phone: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    "Date: $stamp\n\n" + sw.toString(),
            )
            // Keep the last 5.
            d.listFiles()
                ?.filter { it.name.startsWith("crash-") && it.name.endsWith(".txt") }
                ?.sortedBy { it.name }
                ?.dropLast(MAX_FILES)
                ?.forEach { try { it.delete() } catch (_: Exception) { } }
        } catch (_: Exception) {
            // Never break anything for the log.
        }
    }

    /** Is the error from the Mapsforge tile download/cache path? Checked through the whole cause chain. */
    fun isTilePathCrash(t: Throwable?): Boolean {
        var cur = t
        while (cur != null) {
            for (el in cur.stackTrace) {
                val cn = el.className
                if (cn.startsWith("org.mapsforge.map.layer.download.") ||
                    cn.startsWith("org.mapsforge.map.layer.cache.")
                ) return true
            }
            cur = cur.cause
        }
        return false
    }

    /**
     * Installs the handler. Call once, first thing in Application.onCreate.
     * The previous handler still runs (and the process still dies) for
     * main-thread errors; only a tile-thread error is swallowed, after
     * logging it and marking the online-tile crash in the "bunnylock"
     * prefs — the same marker MapManager reads.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val mainThread = Thread.currentThread()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { write(appContext, throwable) } catch (_: Exception) { }
            if (thread !== mainThread && isTilePathCrash(throwable)) {
                try {
                    appContext.getSharedPreferences("bunnylock", Context.MODE_PRIVATE).edit()
                        .putBoolean(OnlineMap.PREF_ONLINE_TILE_CRASH, true).apply()
                } catch (_: Exception) { }
                return@setDefaultUncaughtExceptionHandler
            }
            try {
                previous?.uncaughtException(thread, throwable)
            } catch (_: Exception) {
            }
            if (previous == null) {
                try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Exception) { }
            }
        }
    }
}
