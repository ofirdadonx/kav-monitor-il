package il.kavmonitor.shared.location

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.russhwolf.settings.Settings

enum class LocationAccess {
    /** Not known yet / never asked. */
    UNKNOWN,
    /** The system permission dialog is showing. */
    ASKING,
    GRANTED,
    /** Denied (or revoked): the UI shows a small "enable location" row and never asks on its own again. */
    DENIED,
}

/**
 * Foreground-only location for the "nearest stop" sections. Updates run ONLY while a section is attached ([attach] /
 * [detach] from the composable) AND the app is visible ([setAppVisible] from Activity onStart/onStop) AND permission is
 * granted. The permission dialog is shown automatically at most once ever (the first time a section is shown, flag kept
 * in [settings]); after a denial only an explicit tap on "enable location" ([enableLocation]) asks again.
 * Must be used from the main thread.
 */
class LocationController(
    private val provider: LocationProvider,
    /** Keeps the "asked once" flag across launches (null: in memory only). */
    private val settings: Settings?,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val minDistanceMeters: Double = DEFAULT_MIN_DISTANCE_METERS,
) {
    var access by mutableStateOf(if (provider.hasPermission()) LocationAccess.GRANTED else LocationAccess.UNKNOWN); private set
    var fix by mutableStateOf<GeoFix?>(null); private set
    var servicesOff by mutableStateOf(false); private set

    private var askedInMemory = false
    private var asked: Boolean
        get() = settings?.getBoolean(KEY_ASKED, false) ?: askedInMemory
        set(v) { if (settings != null) settings.putBoolean(KEY_ASKED, v) else askedInMemory = v }

    private var tokens = 0
    private val attached = mutableSetOf<Int>()
    private var appVisible = false
    private var subscription: LocationSubscription? = null
    private var generation = 0

    /** True while platform location updates are running (for tests / diagnostics). */
    val isUpdating: Boolean get() = subscription != null
    /** How many times updates were started (for tests). */
    var starts = 0; private set

    fun attach(): Int {
        val t = ++tokens
        attached += t
        reconcile()
        return t
    }

    fun detach(token: Int) {
        attached -= token
        reconcile()
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        reconcile()
    }

    /** The user tapped "enable location". */
    fun enableLocation() {
        if (provider.hasPermission()) { reconcile(); return }
        ask(userInitiated = true)
    }

    private fun ask(userInitiated: Boolean) {
        if (access == LocationAccess.ASKING) return
        val previous = access
        access = LocationAccess.ASKING
        val launched = provider.requestPermission(userInitiated) { granted ->
            access = if (granted || provider.hasPermission()) LocationAccess.GRANTED else LocationAccess.DENIED
            reconcile()
        }
        if (!launched) {
            access = previous
            if (!userInitiated) asked = false
        }
    }

    private fun reconcile() {
        val shown = attached.isNotEmpty() && appVisible
        if (provider.hasPermission()) {
            access = LocationAccess.GRANTED
        } else if (access == LocationAccess.GRANTED) {
            access = LocationAccess.DENIED // revoked in settings
        }
        if (shown && access == LocationAccess.UNKNOWN) {
            if (!asked) {
                asked = true
                ask(userInitiated = false)
            } else {
                access = LocationAccess.DENIED // asked before and still no permission: don't nag
            }
        }
        if (shown && access == LocationAccess.GRANTED) {
            servicesOff = !provider.isLocationEnabled()
            if (subscription == null) {
                val gen = ++generation
                starts++
                subscription = provider.start(intervalMillis, minDistanceMeters) { f -> if (gen == generation) fix = f }
            }
        } else {
            stopUpdates()
        }
    }

    private fun stopUpdates() {
        val s = subscription ?: return
        subscription = null
        generation++
        s.stop()
    }

    companion object {
        const val KEY_ASKED = "location_permission_asked"
        const val DEFAULT_INTERVAL_MILLIS = 20_000L
        const val DEFAULT_MIN_DISTANCE_METERS = 15.0
    }
}
