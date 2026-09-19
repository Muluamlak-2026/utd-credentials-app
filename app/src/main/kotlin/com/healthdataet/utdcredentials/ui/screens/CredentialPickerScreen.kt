package com.healthdataet.utdcredentials.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.healthdataet.utdcredentials.accessibility.UtdClickAccessibilityService
import com.healthdataet.utdcredentials.data.ApiClient
import com.healthdataet.utdcredentials.data.PendingSequentialLogins
import com.healthdataet.utdcredentials.data.PendingUpToDateLogin
import com.healthdataet.utdcredentials.data.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor

/** One row from /api/v1/credentials/list -- see admin/api_routes.py's
 * list_credentials_for_login for the exact shape this mirrors. Round 48n:
 * lastTestAt/lastTestStatus/lastTestReason are the exact same Sign-In Test
 * result the web Hub's own column shows for this credential (attached
 * server-side, no extra round trip).
 *
 * Round 66: planType/expiryDate are read defensively from several possible
 * server field-name spellings (see [firstNonBlank] in parseCredentials) so
 * this keeps working whether the server calls them plan_type/expiry_date or
 * something else -- if the server doesn't send either field at all yet,
 * these simply stay null and the new Plan/Near-Expiry filters show nothing
 * for that row (Failed-Previous-Login and the success color both work today
 * regardless, since they only depend on the login-test fields already sent). */
data class CredentialEntry(
    val source: String,
    val id: Long,
    val ucCode: String?,
    val username: String?,
    val password: String?,
    val status: String?,
    val assignedTo: String?,
    val phone: String?,
    val lastTestAt: String? = null,
    val lastTestStatus: String? = null,
    val lastTestReason: String? = null,
    val planType: String? = null,
    val expiryDate: String? = null
)

private fun firstNonBlank(o: JSONObject, vararg keys: String): String? {
    for (k in keys) {
        val v = o.optString(k)
        if (v.isNotBlank() && !v.equals("null", ignoreCase = true)) return v
    }
    return null
}

private fun parseCredentials(json: JSONObject?): List<CredentialEntry> {
    val arr = json?.optJSONArray("credentials") ?: return emptyList()
    val out = mutableListOf<CredentialEntry>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        out.add(
            CredentialEntry(
                source = o.optString("source"),
                id = o.optLong("id"),
                ucCode = o.optString("uc_code").ifBlank { null },
                username = o.optString("username").ifBlank { null },
                password = o.optString("password").ifBlank { null },
                status = o.optString("status").ifBlank { null },
                assignedTo = o.optString("assigned_to").ifBlank { null },
                phone = o.optString("phone").ifBlank { null },
                lastTestAt = o.optString("login_test_at").ifBlank { null },
                lastTestStatus = o.optString("login_test_status").ifBlank { null },
                lastTestReason = o.optString("login_test_reason").ifBlank { null },
                planType = firstNonBlank(o, "plan_type", "subscription_plan", "plan"),
                expiryDate = firstNonBlank(o, "expiry_date", "end_date", "expiration_date", "subscription_end_date")
            )
        )
    }
    return out
}

/** Round 66: three-way coloring instead of a plain problem/no-problem flag
 * -- a genuinely successful last test now gets its own SUCCESS tone (green)
 * instead of blending into the same neutral gray as "untested"/"skipped". */
private enum class TestTone { SUCCESS, PROBLEM, NEUTRAL }

private fun testSummary(cred: CredentialEntry): Pair<String, TestTone> {
    if (cred.username.isNullOrBlank() || cred.password.isNullOrBlank()) {
        return "Last sign-in test: incomplete credentials" to TestTone.PROBLEM
    }
    return when (cred.lastTestStatus) {
        "success" -> "Last sign-in test: SUCCESS · ${cred.lastTestAt ?: ""}" to TestTone.SUCCESS
        "failed" -> "Last sign-in test: FAILED · ${cred.lastTestAt ?: ""}" to TestTone.PROBLEM
        "timeout" -> "Last sign-in test: TIMED OUT · ${cred.lastTestAt ?: ""}" to TestTone.PROBLEM
        "skipped" -> "Last sign-in test: skipped (no credentials at the time)" to TestTone.NEUTRAL
        else -> "Last sign-in test: untested / not tried yet" to TestTone.NEUTRAL
    }
}

/** Normalizes whatever the server sends for a plan ("1year", "1-year",
 * "1 Year", "annual", etc. are all guesses -- only the presence of a "1" or
 * "2" digit is load-bearing here) down to "1year"/"2year"/null. */
private fun normalizedPlan(raw: String?): String? {
    val v = raw?.lowercase()?.trim()
    if (v.isNullOrBlank()) return null
    return when {
        v.contains("2") -> "2year"
        v.contains("1") -> "1year"
        else -> null
    }
}

private fun planLabel(raw: String?): String? = when (normalizedPlan(raw)) {
    "1year" -> "1-Year"
    "2year" -> "2-Year"
    else -> null
}

private val EXPIRY_FORMATS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy-MM-dd HH:mm:ss",
    "yyyy-MM-dd"
)

/** Parses [raw] against a few common date shapes and returns whole days
 * from now until that date (negative if already past). Returns null if
 * [raw] is blank or matches none of the tried formats -- callers must treat
 * null as "unknown", never as "not expiring". */
private fun daysUntilExpiry(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    for (fmt in EXPIRY_FORMATS) {
        try {
            val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.US)
            sdf.isLenient = false
            val parsed = sdf.parse(raw.take(fmt.length.coerceAtMost(raw.length))) ?: continue
            val diffMs = parsed.time - System.currentTimeMillis()
            return floor(diffMs / 86_400_000.0).toInt()
        } catch (e: Exception) {
            // try the next format
        }
    }
    return null
}

private enum class CredFilter(val label: String) {
    ALL("All credentials"),
    PLAN_1YR("1-Year subscribers"),
    PLAN_2YR("2-Year subscribers"),
    NEAR_EXPIRY("Near expiry (≤30 days)"),
    FAILED_PREVIOUS("Failed previous login"),
    UNTESTED("Never tested")
}

private enum class CredSort(val label: String) {
    DEFAULT("(row order)"),
    EXPIRY_SOON("Expiry: soonest first"),
    PLAN_TYPE("Plan type"),
    TEST_ISSUES_FIRST("Status: issues first"),
    TEST_SUCCESS_FIRST("Status: success first")
}

private fun applyFilterAndSort(
    list: List<CredentialEntry>,
    filter: CredFilter,
    sort: CredSort
): List<CredentialEntry> {
    var out = when (filter) {
        CredFilter.ALL -> list
        CredFilter.PLAN_1YR -> list.filter { normalizedPlan(it.planType) == "1year" }
        CredFilter.PLAN_2YR -> list.filter { normalizedPlan(it.planType) == "2year" }
        CredFilter.NEAR_EXPIRY -> list.filter {
            val d = daysUntilExpiry(it.expiryDate)
            d != null && d in 0..30
        }
        CredFilter.FAILED_PREVIOUS -> list.filter {
            it.lastTestStatus == "failed" || it.lastTestStatus == "timeout"
        }
        CredFilter.UNTESTED -> list.filter { it.lastTestStatus.isNullOrBlank() }
    }
    out = when (sort) {
        CredSort.DEFAULT -> out
        CredSort.EXPIRY_SOON -> out.sortedWith(
            compareBy(nullsLast()) { daysUntilExpiry(it.expiryDate) }
        )
        CredSort.PLAN_TYPE -> out.sortedWith(compareBy(nullsLast()) { normalizedPlan(it.planType) })
        CredSort.TEST_ISSUES_FIRST -> out.sortedByDescending {
            it.lastTestStatus == "failed" || it.lastTestStatus == "timeout"
        }
        CredSort.TEST_SUCCESS_FIRST -> out.sortedByDescending { it.lastTestStatus == "success" }
    }
    return out
}

private val PAGE_SIZE_OPTIONS = listOf(100, 200)

/**
 * Round 48i: "a separate panel in which importing or selecting among a
 * sourced credential will be done" -- lets the admin search/browse the
 * exact same active/draft/pool/legacy credential list the web Credentials
 * Hub shows, then either:
 *  - hand ONE straight to UpToDateLoginScreen's WebView (the original
 *    "Log In With This Credential" button, fill-only, admin taps the real
 *    login button themselves), or
 *  - tick a CHECKBOX on several rows and run them through
 *    SequentialLoginScreen (the "Run Sequential Login" bar that appears
 *    once at least one row is checked), which logs into each one in turn,
 *    unattended, clears the session between attempts, and reports
 *    success/failure per credential -- the round 48i(+) follow-up request.
 *
 * Round 48n: real paging (page-size dropdown of 100/200, a page picker)
 * replaces round 48l's "keep appending 200 more" -- each page now REPLACES
 * what's shown, but selections made on any page are remembered
 * (selectedEntries, keyed by id) so admins can tick some on page 1, jump to
 * page 2, tick more, and still run all of them together.
 *
 * Round 66: a genuinely successful last-test now shows in green instead of
 * the same neutral gray as untested, and a Filter/Sort control lets the
 * admin narrow the current page down to 1-Year subscribers, 2-Year
 * subscribers, credentials expiring within 30 days, or ones that failed
 * their last automated login -- plus sort the visible list by expiry, plan,
 * or test status. Filtering/sorting only ever reorders/hides rows already
 * loaded on the current page; it never changes what's fetched from the
 * server or what "Select page"/"Run Sequential Login" operate on (both now
 * act on the filtered/sorted view, so ticking "Select page" after filtering
 * to "Failed previous login" selects exactly those rows, not the full page).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialPickerScreen(
    session: SessionManager,
    onBack: () -> Unit,
    onCredentialChosen: () -> Unit,
    onRunSequential: () -> Unit,
    onOpenHistory: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Round 48p: surfaces the new Accessibility Service fallback (see
    // UtdClickAccessibilityService's doc comment) so the admin has an
    // actual in-app way to discover and turn it on -- without this there
    // was no UI anywhere pointing at it, so it could only ever be enabled
    // by someone who already knew Android's system Settings path by heart.
    // Re-checked via the lifecycle observer below every time this screen
    // comes back to the foreground (e.g. returning from the system
    // Settings screen after flipping it on), not just once on first entry.
    var accessibilityEnabled by remember {
        mutableStateOf(UtdClickAccessibilityService.isEnabledInSystemSettings(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = UtdClickAccessibilityService.isEnabledInSystemSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var query by remember { mutableStateOf("") }
    var credentials by remember { mutableStateOf(listOf<CredentialEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var revealedIds by remember { mutableStateOf(setOf<Long>()) }
    // Round 48n: selection is remembered by id ACROSS pages (a plain Map so
    // "Run Sequential Login" can still find the full CredentialEntry for
    // something selected on a page that's no longer the one showing).
    var selectedEntries by remember { mutableStateOf(mapOf<Long, CredentialEntry>()) }
    var pageSize by remember { mutableStateOf(200) }
    var currentPage by remember { mutableStateOf(1) }
    var totalMatching by remember { mutableStateOf(0) }
    var lastQuery by remember { mutableStateOf("") }
    var pageMenuOpen by remember { mutableStateOf(false) }
    var sizeMenuOpen by remember { mutableStateOf(false) }
    // Round 66: client-side filter/sort over the currently loaded page.
    var filterMode by remember { mutableStateOf(CredFilter.ALL) }
    var sortMode by remember { mutableStateOf(CredSort.DEFAULT) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    fun runSearch(q: String, page: Int, size: Int) {
        val apiToken = session.apiToken
        if (apiToken == null) {
            errorText = "Not logged in."
            loading = false
            return
        }
        loading = true
        errorText = null
        lastQuery = q
        scope.launch {
            val offset = (page - 1) * size
            val result = withContext(Dispatchers.IO) {
                ApiClient(session.baseUrl).listCredentials(apiToken, q, offset, size)
            }
            loading = false
            if (result.ok && result.json != null) {
                credentials = parseCredentials(result.json)
                totalMatching = result.json.optInt("total", credentials.size)
                currentPage = page
                pageSize = size
            } else {
                errorText = result.error ?: "Could not load credentials."
            }
        }
    }

    LaunchedEffect(Unit) { runSearch("", page = 1, size = pageSize) }

    val totalPages = maxOf(1, ceil(totalMatching.toDouble() / pageSize).toInt())
    val displayedCredentials = remember(credentials, filterMode, sortMode) {
        applyFilterAndSort(credentials, filterMode, sortMode)
    }
    val loginableCount = selectedEntries.count {
        !it.value.username.isNullOrBlank() && !it.value.password.isNullOrBlank()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UpToDate Quick Login") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Icons.Filled.History is only in the material-icons-extended
                    // artifact, which this project doesn't depend on -- a plain
                    // text glyph avoids adding that dependency for one icon.
                    TextButton(onClick = onOpenHistory) { Text("History") }
                }
            )
        },
        bottomBar = {
            if (selectedEntries.isNotEmpty()) {
                // Round 48n(e): the footer was getting clipped by the phone's
                // gesture nav bar -- navigationBarsPadding() keeps it clear,
                // and the button/text below are sized down to fit
                // comfortably above it either way.
                Surface(tonalElevation = 3.dp, modifier = Modifier.navigationBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${selectedEntries.size} selected",
                                style = MaterialTheme.typography.labelMedium
                            )
                            if (loginableCount != selectedEntries.size) {
                                Text(
                                    "${selectedEntries.size - loginableCount} missing username/password",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        TextButton(
                            onClick = { selectedEntries = emptyMap() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text("Clear") }
                        Button(
                            onClick = {
                                val chosen = selectedEntries.values.filter {
                                    !it.username.isNullOrBlank() && !it.password.isNullOrBlank()
                                }
                                if (chosen.isNotEmpty()) {
                                    PendingSequentialLogins.queue = chosen
                                    onRunSequential()
                                } else {
                                    errorText = "None of the selected credentials have both a username and password."
                                }
                            },
                            enabled = loginableCount > 0,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Run ($loginableCount)", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Round 48n(a): the explanatory paragraph is gone -- this screen
            // is admin-only and self-explanatory, so it was just eating
            // vertical space for nothing.

            // Round 48n(b): search field shrunk to a genuine single visual
            // line -- the previous long label wrapped to 2-3 lines since it
            // doubles as the placeholder text when the field is empty.
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("UC-code / username / email / phone", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { runSearch(query, page = 1, size = pageSize) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) { Text("Search") }
            }

            Spacer(Modifier.height(6.dp))

            // Round 48p: the Accessibility Service status/enable card -- the
            // final, coordinate-free fallback for the Sign In/Continue click
            // problem only ever helps once the admin has turned it on once
            // in system Settings (Android requires this manual step for
            // every accessibility service; no app can enable it silently).
            // Shown compactly, and only surfaced with an actionable "Enable"
            // button when it's actually off, so once it's on this card just
            // confirms that quietly instead of nagging.
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                colors = if (accessibilityEnabled) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                } else {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (accessibilityEnabled) "Auto-click helper: ON" else "Auto-click helper: OFF",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (accessibilityEnabled) {
                                "Sign In/Continue clicks use the most reliable method available."
                            } else {
                                "Recommended: fixes Sign In/Continue not registering a tap."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (!accessibilityEnabled) {
                        TextButton(
                            onClick = {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                } catch (e: Exception) {
                                    errorText = "Couldn't open system Accessibility settings on this device."
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text("Enable") }
                    }
                }
            }

            // Round 48n(c): page-size + page picker, replacing the old
            // "Load next 200" append button -- this is how you actually
            // reach the second (or Nth) 200-block, and selections made on
            // an earlier page are kept even after moving to another one.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    selectedEntries = selectedEntries + displayedCredentials
                        .filter { !it.username.isNullOrBlank() && !it.password.isNullOrBlank() }
                        .associateBy { it.id }
                }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("Select page") }
                TextButton(onClick = { selectedEntries = emptyMap() }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) { Text("Clear") }
                Spacer(Modifier.weight(1f))

                Box {
                    TextButton(onClick = { sizeMenuOpen = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("$pageSize/page", style = MaterialTheme.typography.labelSmall)
                    }
                    DropdownMenu(expanded = sizeMenuOpen, onDismissRequest = { sizeMenuOpen = false }) {
                        PAGE_SIZE_OPTIONS.forEach { size ->
                            DropdownMenuItem(
                                text = { Text("$size per page") },
                                onClick = {
                                    sizeMenuOpen = false
                                    runSearch(lastQuery, page = 1, size = size)
                                }
                            )
                        }
                    }
                }

                Box {
                    TextButton(onClick = { pageMenuOpen = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Page $currentPage/$totalPages", style = MaterialTheme.typography.labelSmall)
                    }
                    DropdownMenu(expanded = pageMenuOpen, onDismissRequest = { pageMenuOpen = false }) {
                        for (p in 1..totalPages) {
                            val from = (p - 1) * pageSize + 1
                            val to = minOf(p * pageSize, totalMatching)
                            DropdownMenuItem(
                                text = { Text("Page $p ($from-$to)") },
                                onClick = {
                                    pageMenuOpen = false
                                    if (p != currentPage) runSearch(lastQuery, page = p, size = pageSize)
                                }
                            )
                        }
                    }
                }
            }

            // Round 66: Filter/Sort row -- narrows and reorders the
            // CURRENTLY LOADED page only (never re-queries the server).
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Box {
                    TextButton(onClick = { filterMenuOpen = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Filter: ${filterMode.label}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = filterMenuOpen, onDismissRequest = { filterMenuOpen = false }) {
                        CredFilter.values().forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.label) },
                                onClick = {
                                    filterMenuOpen = false
                                    filterMode = f
                                }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
                Box {
                    TextButton(onClick = { sortMenuOpen = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Sort: ${sortMode.label}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        CredSort.values().forEach { s ->
                            DropdownMenuItem(
                                text = { Text(s.label) },
                                onClick = {
                                    sortMenuOpen = false
                                    sortMode = s
                                }
                            )
                        }
                    }
                }
            }

            Text(
                if (filterMode == CredFilter.ALL) {
                    "$totalMatching total matching · ${selectedEntries.size} selected overall"
                } else {
                    "$totalMatching total matching · ${displayedCredentials.size} shown (filtered) · ${selectedEntries.size} selected overall"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (errorText != null) {
                Text(errorText ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(4.dp))

            if (loading) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (displayedCredentials.isEmpty()) {
                Text(
                    if (credentials.isEmpty()) "No credentials found." else "No credentials on this page match \"${filterMode.label}\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(displayedCredentials, key = { it.id }) { cred ->
                        val revealed = revealedIds.contains(cred.id)
                        val checked = selectedEntries.containsKey(cred.id)
                        val (testLabel, testTone) = testSummary(cred)
                        val expiryDays = daysUntilExpiry(cred.expiryDate)
                        val plan = planLabel(cred.planType)
                        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { isChecked ->
                                                selectedEntries = if (isChecked) {
                                                    selectedEntries + (cred.id to cred)
                                                } else {
                                                    selectedEntries - cred.id
                                                }
                                            }
                                        )
                                        Text(
                                            cred.ucCode ?: "(no UC-code)",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    Text(
                                        "${cred.source}${cred.status?.let { " · $it" } ?: ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text("Username: ${cred.username ?: "-"}", style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Password: " + if (revealed) (cred.password ?: "-") else "••••••••",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    TextButton(
                                        onClick = { revealedIds = if (revealed) revealedIds - cred.id else revealedIds + cred.id },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(if (revealed) "Hide" else "Show", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                if (cred.assignedTo != null || cred.phone != null) {
                                    Text(
                                        "Assigned: ${cred.assignedTo ?: "-"}${cred.phone?.let { " · $it" } ?: ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                // Round 66: plan + expiry line -- only shown
                                // when the server actually sent one of those
                                // fields for this credential; near-expiry
                                // (<=30 days, including already-past) is
                                // called out in amber/red the same way the
                                // web Hub does.
                                if (plan != null || expiryDays != null) {
                                    Text(
                                        buildString {
                                            if (plan != null) append("Plan: $plan")
                                            if (plan != null && expiryDays != null) append(" · ")
                                            if (expiryDays != null) {
                                                append(cred.expiryDate ?: "")
                                                append(
                                                    when {
                                                        expiryDays < 0 -> " (expired)"
                                                        expiryDays == 0 -> " (expires today)"
                                                        else -> " ($expiryDays days left)"
                                                    }
                                                )
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (expiryDays != null && expiryDays <= 30) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                                // Round 48n(d): each card now shows its own
                                // last automated sign-in test result, synced
                                // from the exact same data the web Hub's
                                // Sign-In Test column reads. Round 66: a real
                                // SUCCESS now renders in green rather than
                                // the same gray as an untested row.
                                Text(
                                    testLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (testTone) {
                                        TestTone.PROBLEM -> MaterialTheme.colorScheme.error
                                        TestTone.SUCCESS -> Color(0xFF2E7D32)
                                        TestTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = if (testTone == TestTone.SUCCESS) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                Button(
                                    onClick = {
                                        val u = cred.username
                                        val p = cred.password
                                        if (!u.isNullOrBlank() && !p.isNullOrBlank()) {
                                            PendingUpToDateLogin.username = u
                                            PendingUpToDateLogin.password = p
                                            PendingUpToDateLogin.source = cred.source
                                            PendingUpToDateLogin.id = cred.id
                                            onCredentialChosen()
                                        } else {
                                            errorText = "This credential has no username/password on file."
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Log In With This Credential", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
