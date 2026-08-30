package com.healthdataet.utdcredentials.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Round 48p: the FINAL escalation for the Sign In/Continue click problem
 * that five rounds of coordinate-tap and key-press fixes still didn't
 * fully solve. Everything tried so far (nativeTap, then hold+move timing,
 * then a focus+ENTER key event) works by producing SOME kind of Android
 * input event and hoping it lands correctly on uptodate.com's button --
 * this instead sidesteps input entirely: an enabled Accessibility Service
 * can walk the actual ACCESSIBILITY NODE TREE (which Chromium/WebView
 * exposes for every visible web element, same tree TalkBack reads) and
 * call `performAction(ACTION_CLICK)` DIRECTLY on the button's own node.
 * That's not a coordinate or a synthetic event at all -- it's the OS
 * itself telling that exact element "you were clicked", which Chromium
 * turns into a genuinely trusted click no matter what this page's handler
 * requires. This is the same mechanism TalkBack/Switch Access use to let
 * a user "tap" something without ever touching the screen, so it is a
 * long-standing, well-supported Android capability -- not a hack.
 *
 * Scope and privacy: `utd_accessibility_service_config.xml`'s
 * `android:packageNames="com.healthdataet.utdcredentials"` means Android
 * itself refuses to deliver this service ANY content from any other app --
 * it is completely inert everywhere outside this one app, including on the
 * phone's home screen, other apps, and system UI. It never runs unless the
 * admin manually turns it on once in Settings > Accessibility (Android
 * requires that manual step for every accessibility service, with no way
 * for an app to enable it silently) -- exactly the "bigger ask" already
 * flagged in round 48k as the real fallback if tap/key-press weren't
 * enough. If it's never enabled, the app behaves exactly as before this
 * round: tap, then key-press, then offset-tap, with manual intervention
 * always available regardless.
 */
class UtdClickAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event handling needed -- this service is only ever driven on
        // demand by clickButtonByText() below, never reacting to events on
        // its own (it does nothing in the background, ever).
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile private var instance: UtdClickAccessibilityService? = null

        /** Whether the service is both enabled by the admin (in system
         * Settings) AND actually connected right now -- both need to be
         * true for [clickButtonByText] to have any chance of working. */
        fun isActive(): Boolean = instance != null

        /**
         * Checks Android's own list of currently-enabled accessibility
         * services for this app's service -- lets the UI show an accurate
         * "ON"/"OFF" status even before the service has connected (e.g.
         * right after the admin flips it on but before this process has
         * picked that up), without needing a live [instance] yet.
         */
        fun isEnabledInSystemSettings(context: Context): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val serviceId = "${context.packageName}/${UtdClickAccessibilityService::class.java.name}"
                enabled.split(':').any { it.equals(serviceId, ignoreCase = true) }
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Searches the current foreground window's accessibility tree for a
         * node whose text/content-description contains one of [labels]
         * (case-insensitive substring, same matching style as
         * findButtonByText in LoginAutomation.kt's JS), then performs a
         * real ACTION_CLICK on it -- walking up to the nearest clickable
         * ancestor first, since the matched text node itself is very often
         * a label INSIDE the actual clickable button/container rather than
         * the clickable element itself (an extremely common pattern in
         * real-world web/app UIs). Returns true only if a click was
         * actually dispatched to some node.
         */
        fun clickButtonByText(labels: List<String>): Boolean {
            val svc = instance ?: return false
            val root = svc.rootInActiveWindow ?: return false
            try {
                for (label in labels) {
                    val matches = root.findAccessibilityNodeInfosByText(label) ?: continue
                    for (node in matches) {
                        var candidate: AccessibilityNodeInfo? = node
                        var depth = 0
                        while (candidate != null && depth < 8) {
                            if (candidate.isClickable && candidate.isEnabled) {
                                val ok = candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                if (ok) return true
                            }
                            candidate = candidate.parent
                            depth++
                        }
                    }
                }
            } catch (e: Exception) {
                // Never let a lookup/click failure here crash the login
                // flow -- the tap/key-press fallback chain still runs.
            }
            return false
        }
    }
}
