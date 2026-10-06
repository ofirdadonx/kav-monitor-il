package il.kavmonitor.shared.location

import kotlin.time.Instant

/** One position fix of the user. */
data class GeoFix(
    val lat: Double,
    val lon: Double,
    /** Horizontal accuracy radius in meters, if the platform reports it. */
    val accuracyMeters: Double? = null,
    /** When the fix was taken (platform time). */
    val time: Instant,
)

/** A running stream of location updates; [stop] must be called when the screen goes away. */
fun interface LocationSubscription {
    fun stop()
}

/**
 * Platform location access (Android: LocationManager, see androidApp AndroidLocationProvider). Kept as an interface so an
 * iOS (CLLocationManager) implementation can be added later. Foreground only: implementations must not keep receiving
 * updates after [LocationSubscription.stop], and nothing here runs in the background.
 */
interface LocationProvider {
    /** Whether the app currently may read the location (fine or coarse). */
    fun hasPermission(): Boolean

    /** Whether the device's location services are on (false → no fixes will arrive even with permission). */
    fun isLocationEnabled(): Boolean = true

    /**
     * Asks the user for permission. [userInitiated] = the user tapped "enable location" (if the system won't show the
     * dialog any more, the platform may open the app's settings instead). [onResult] gets true if granted.
     * Returns false if it could not ask right now (e.g. no visible screen).
     */
    fun requestPermission(userInitiated: Boolean, onResult: (Boolean) -> Unit): Boolean

    /** Starts foreground updates (fresh fix; then on movement or every ~[intervalMillis]). [onFix] is called on the main thread. */
    fun start(intervalMillis: Long, minDistanceMeters: Double, onFix: (GeoFix) -> Unit): LocationSubscription
}

/** For platforms/tests without location: never has permission, never asks. */
object NoLocationProvider : LocationProvider {
    override fun hasPermission() = false
    override fun isLocationEnabled() = false
    override fun requestPermission(userInitiated: Boolean, onResult: (Boolean) -> Unit) = false
    override fun start(intervalMillis: Long, minDistanceMeters: Double, onFix: (GeoFix) -> Unit) = LocationSubscription { }
}
