package com.healthdataet.utdcredentials.util

import android.content.Context
import android.content.Intent
import android.os.Process
import com.healthdataet.utdcredentials.CrashReportActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global safety net, installed once from UtdCredentialsApp.onCreate (which
 * runs before ANY Activity, including MainActivity) -- catches every
 * uncaught exception anywhere in the app, on any thread, instead of
 * letting Android silently kill the process and show its bare "UTD
 * Credentials keeps stopping" system dialog with zero detail.
 *
 * Added after a real device crashed instantly on every launch with no way
 * to see why short of pairing ADB over Wireless Debugging by hand -- this
 * makes that a one-time cost, not a recurring one: from now on, any crash
 * (a) is written to a small text file in this app's own private storage,
 * and (b) immediately relaunches straight into CrashReportActivity showing
 * that same text, selectable and copyable right there on the phone.
 */
object CrashHandler {
    private const val CRASH_DIR = "crash_logs"
    private const val MAX_KEPT_LOGS = 10
    const val LAST_CRASH_EXTRA = "last_crash_text"

    fun install(context: Context) {
        val appContext = context.applicationContext
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val text = renderCrash(thread, throwable)
                writeLog(appContext, text)
                launchRecovery(appContext, text)
            } catch (inner: Throwable) {
                // The handler itself must never throw -- worst case, fall
                // through to the process kill below with no recovery
                // screen, which is still no worse than the old behavior.
            } finally {
                Process.killProcess(Process.myPid())
                Runtime.getRuntime().exit(10)
            }
        }
    }

    private fun renderCrash(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return "UTD Credentials crash report\n" +
            "Time: $timestamp\n" +
            "Thread: ${thread.name}\n\n" +
            sw.toString()
    }

    private fun writeLog(context: Context, text: String) {
        try {
            val dir = File(context.filesDir, CRASH_DIR)
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            File(dir, "crash_$stamp.txt").writeText(text)
            // Never let this grow unbounded on a device that (for whatever
            // reason) keeps hitting the same bug repeatedly.
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_KEPT_LOGS)
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            // Best-effort -- a failure to persist the log must never stop
            // the recovery screen below from still showing.
        }
    }

    private fun launchRecovery(context: Context, text: String) {
        val intent = Intent(context, CrashReportActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(LAST_CRASH_EXTRA, text)
        }
        context.startActivity(intent)
    }

    /** All saved crash logs, most recent first -- read from
     * CrashLogScreen (App Settings -> Diagnostics) so a past crash can
     * still be reviewed even after its recovery screen was dismissed. */
    fun listLogs(context: Context): List<File> {
        val dir = File(context.filesDir, CRASH_DIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun clearLogs(context: Context) {
        try {
            File(context.filesDir, CRASH_DIR).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            // Best-effort.
        }
    }
}
