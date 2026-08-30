package com.healthdataet.utdcredentials.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Result of any ApiClient call: [ok] mirrors the server's own `{"ok": ...}`
 * envelope (see admin/api_routes.py's api_ok/api_error), [json] is the full
 * parsed body when available, [error] is a human-readable message on
 * failure (network problem or the server's own `error` field). */
class ApiResult(val ok: Boolean, val json: JSONObject?, val error: String?)

/**
 * Thin client for the panel's existing /api/v1 JSON API
 * (admin/api_routes.py) -- login, push device-token registration, and
 * notification polling only. Everything else an admin needs to DO lives in
 * the full web panel, shown in FullSiteScreen -- this client exists purely
 * to support native login + push, exactly like the previous build did.
 */
class ApiClient(private val baseUrl: String) {
    // Round 48p: bumped 15s -> 30s (connect/read/write) and
    // retryOnConnectionFailure made explicit -- reported "login test lag
    // delays" on a weak connection are consistent with these short old
    // timeouts cutting a slow-but-otherwise-fine request off early and
    // surfacing it as a network error, rather than any code path actually
    // hanging. This only raises the ceiling for how long a slow request is
    // allowed to keep trying; a request that would have succeeded in 5s
    // before still succeeds in 5s now.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun parse(bodyText: String, httpOk: Boolean): ApiResult {
        val parsed = if (bodyText.isNotBlank()) {
            try { JSONObject(bodyText) } catch (e: Exception) { JSONObject() }
        } else JSONObject()
        return if (httpOk && parsed.optBoolean("ok", false)) {
            ApiResult(true, parsed, null)
        } else {
            ApiResult(false, parsed, parsed.optString("error").ifBlank { "Request failed" })
        }
    }

    private fun post(path: String, body: JSONObject, token: String? = null): ApiResult {
        return try {
            val builder = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .post(body.toString().toRequestBody(jsonMedia))
            if (token != null) builder.addHeader("Authorization", "Bearer $token")
            client.newCall(builder.build()).execute().use { resp ->
                parse(resp.body?.string().orEmpty(), resp.isSuccessful)
            }
        } catch (e: IOException) {
            ApiResult(false, null, e.message ?: "Network error -- check the admin panel URL and your connection.")
        } catch (e: Exception) {
            ApiResult(false, null, e.message ?: "Unexpected error")
        }
    }

    private fun getWithAuth(path: String, token: String): ApiResult {
        return try {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                parse(resp.body?.string().orEmpty(), resp.isSuccessful)
            }
        } catch (e: IOException) {
            ApiResult(false, null, e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult(false, null, e.message ?: "Unexpected error")
        }
    }

    fun login(username: String, password: String): ApiResult {
        val body = JSONObject().put("username", username).put("password", password)
        return post("/api/v1/login", body)
    }

    fun logout(token: String, deviceToken: String?): ApiResult {
        val body = JSONObject()
        if (!deviceToken.isNullOrBlank()) body.put("device_token", deviceToken)
        return post("/api/v1/logout", body, token)
    }

    fun registerDeviceToken(token: String, deviceToken: String): ApiResult {
        val body = JSONObject().put("token", deviceToken).put("platform", "android")
        return post("/api/v1/device-token", body, token)
    }

    fun pollNotifications(token: String, sinceId: Long): ApiResult {
        return getWithAuth("/api/v1/notifications/poll?since_id=$sinceId&limit=50", token)
    }

    /** Round 48i: backs CredentialPickerScreen -- the same unified
     * active/draft/pool/legacy credential list the web Credentials Hub
     * already shows, searchable so a long pool never has to be scrolled
     * through blind. [query] blank means "everything" (capped server-side
     * at 200 rows). */
    fun listCredentials(token: String, query: String, offset: Int = 0, limit: Int = 200): ApiResult {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        // Round 48n: real paging -- offset/limit together select an exact
        // page (100 or 200 per page, picked in the UI) rather than always
        // widening a single accumulated list. See
        // list_credentials_for_login's own doc comment for the server side.
        return getWithAuth("/api/v1/credentials/list?q=$encodedQuery&offset=$offset&limit=$limit", token)
    }

    /** Round 48k: reports the result of one automated UpToDate sign-in
     * attempt (single-credential screen, or one step of the sequential
     * batch runner) back to the panel, so the Credentials Hub's "Sign-In
     * Test" column can show when a credential was last tried and what
     * happened -- [source]/[id] must be the exact pair the credential was
     * handed with from listCredentials, never guessed. [status] is one of
     * "success", "failed", "timeout", "skipped". Best-effort: a failure
     * here (network hiccup, etc.) is never surfaced to the login flow --
     * see the call sites, which fire-and-forget this. */
    fun reportLoginAttempt(token: String, source: String, id: Long, status: String, reason: String?): ApiResult {
        val body = JSONObject()
            .put("source", source)
            .put("id", id)
            .put("status", status)
        if (!reason.isNullOrBlank()) body.put("reason", reason)
        return post("/api/v1/credentials/report-login-attempt", body, token)
    }
}
