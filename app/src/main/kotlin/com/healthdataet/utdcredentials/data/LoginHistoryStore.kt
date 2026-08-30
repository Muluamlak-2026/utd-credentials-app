package com.healthdataet.utdcredentials.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Round 48l: "let login actions be saved in the apk same page with it's
 * history button so that how much ordered, how much success and failure
 * with their date of action". This is deliberately a separate, purely
 * on-device record from the website Hub's "Sign-In Test" column (which is
 * per-credential, server-side, and only ever shows the LATEST result) --
 * this one is a running LOG of every attempt the app itself has ever made,
 * so a History screen can show real ordered/success/failure totals and
 * list out each attempt with its own date, entirely offline.
 *
 * Stored as a single JSON array in SharedPreferences (no DB/migration
 * needed for a simple append-mostly log). Capped at [MAX_ENTRIES] -- oldest
 * entries are dropped first -- so this can never grow unbounded on a phone
 * that's been used for a very long time.
 */
data class LoginHistoryEntry(
    val timestampMs: Long,
    val source: String,
    val credId: Long,
    val ucCode: String?,
    val username: String?,
    val status: String, // "success" | "failed" | "timeout" | "skipped"
    val reason: String?
)

object LoginHistoryStore {
    private const val PREFS_NAME = "login_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 1000

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(
        context: Context,
        source: String,
        credId: Long,
        ucCode: String?,
        username: String?,
        status: String,
        reason: String?
    ) {
        val p = prefs(context)
        val existing = loadRaw(p)
        val entry = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("source", source)
            .put("id", credId)
            .put("uc", ucCode ?: "")
            .put("user", username ?: "")
            .put("status", status)
            .put("reason", reason ?: "")
        existing.put(entry)
        // Drop oldest entries beyond the cap (index 0 is oldest since we
        // always append at the end).
        val result = if (existing.length() > MAX_ENTRIES) {
            val dropCount = existing.length() - MAX_ENTRIES
            val trimmed = JSONArray()
            for (i in dropCount until existing.length()) trimmed.put(existing.get(i))
            trimmed
        } else existing
        p.edit().putString(KEY_ENTRIES, result.toString()).apply()
    }

    private fun loadRaw(p: SharedPreferences): JSONArray {
        val raw = p.getString(KEY_ENTRIES, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
    }

    fun all(context: Context): List<LoginHistoryEntry> {
        val arr = loadRaw(prefs(context))
        val out = mutableListOf<LoginHistoryEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                LoginHistoryEntry(
                    timestampMs = o.optLong("t"),
                    source = o.optString("source"),
                    credId = o.optLong("id"),
                    ucCode = o.optString("uc").ifBlank { null },
                    username = o.optString("user").ifBlank { null },
                    status = o.optString("status"),
                    reason = o.optString("reason").ifBlank { null }
                )
            )
        }
        // Newest first for display.
        return out.sortedByDescending { it.timestampMs }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply()
    }
}
