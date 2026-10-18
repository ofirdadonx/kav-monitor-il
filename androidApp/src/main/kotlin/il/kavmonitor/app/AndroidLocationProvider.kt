package il.kavmonitor.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import il.kavmonitor.shared.location.GeoFix
import il.kavmonitor.shared.location.LocationProvider
import il.kavmonitor.shared.location.LocationSubscription
import kotlin.time.Instant

/**
 * Foreground location via the platform [LocationManager] (no Play Services dependency). Uses the fused provider on
 * Android 12+ when present, otherwise GPS + network. Updates are delivered on the main looper and removed in
 * [LocationSubscription.stop]. No background location permission is requested or used.
 *
 * The permission dialog needs an Activity: [MainActivity] sets [permissionRequester] while it is started.
 */
class AndroidLocationProvider(private val context: Context) : LocationProvider {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    var permissionRequester: ((userInitiated: Boolean, onResult: (Boolean) -> Unit) -> Unit)? = null

    override fun hasPermission(): Boolean = PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun isLocationEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(lm)

    override fun requestPermission(userInitiated: Boolean, onResult: (Boolean) -> Unit): Boolean {
        val r = permissionRequester ?: return false
        r(userInitiated, onResult)
        return true
    }

    override fun start(intervalMillis: Long, minDistanceMeters: Double, onFix: (GeoFix) -> Unit): LocationSubscription {
        if (!hasPermission()) return LocationSubscription { }
        val all = lm.allProviders
        val providers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && LocationManager.FUSED_PROVIDER in all &&
            lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)
        ) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in all }
        }
        var best: Location? = null
        fun offer(loc: Location) {
            if (!isBetter(loc, best)) return
            best = loc
            onFix(GeoFix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                Instant.fromEpochMilliseconds(loc.time)))
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = offer(location)
        }
        try {
            // A recent cached fix shows something immediately; fresh fixes follow.
            providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time < LAST_KNOWN_MAX_AGE_MS }
                .maxByOrNull { it.time }?.let { offer(it) }
            providers.forEach {
                lm.requestLocationUpdates(it, intervalMillis, minDistanceMeters.toFloat(), listener, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            lm.removeUpdates(listener)
            return LocationSubscription { }
        }
        return LocationSubscription { lm.removeUpdates(listener) }
    }

    /** Newer fixes win unless they are much less accurate and only slightly newer (GPS vs. coarse network fixes). */
    private fun isBetter(loc: Location, current: Location?): Boolean {
        if (current == null) return true
        val newer = loc.time - current.time
        if (newer > SIGNIFICANTLY_NEWER_MS) return true
        if (newer < -SIGNIFICANTLY_NEWER_MS) return false
        val acc = if (loc.hasAccuracy()) loc.accuracy else Float.MAX_VALUE
        val curAcc = if (current.hasAccuracy()) current.accuracy else Float.MAX_VALUE
        return if (newer >= 0) acc <= curAcc * 2 else acc < curAcc
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        private const val LAST_KNOWN_MAX_AGE_MS = 2 * 60_000L
        private const val SIGNIFICANTLY_NEWER_MS = 60_000L
    }
}
