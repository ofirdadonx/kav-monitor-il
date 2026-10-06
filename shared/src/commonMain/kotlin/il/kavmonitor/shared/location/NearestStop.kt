package il.kavmonitor.shared.location

import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.util.Geo
import kotlin.math.roundToLong

/** The stop of a route closest to the user, by straight-line (haversine) distance. */
data class NearestStop(val stop: RouteStop, val index: Int, val distanceMeters: Double)

object NearestStops {
    /** Closest stop with coordinates, or null if none has coordinates. Ties keep the earlier stop in route order. */
    fun find(stops: List<RouteStop>, lat: Double, lon: Double): NearestStop? {
        var best: NearestStop? = null
        stops.forEachIndexed { i, s ->
            val sLat = s.lat ?: return@forEachIndexed
            val sLon = s.lon ?: return@forEachIndexed
            val d = Geo.distanceMeters(lat, lon, sLat, sLon)
            if (best == null || d < best!!.distanceMeters) best = NearestStop(s, i, d)
        }
        return best
    }

    /** Straight-line distance from the fix to one stop (null without coordinates). */
    fun distanceTo(stop: RouteStop, lat: Double, lon: Double): Double? {
        val sLat = stop.lat ?: return null
        val sLon = stop.lon ?: return null
        return Geo.distanceMeters(lat, lon, sLat, sLon)
    }
}

/** A distance ready for display: [value] in [unit] (whole meters below 1000 m, km with one decimal from 1000 m). */
data class DistanceLabel(val value: String, val unit: Unit) {
    enum class Unit { METERS, KILOMETERS }
}

fun formatDistance(meters: Double): DistanceLabel {
    val m = meters.coerceAtLeast(0.0).roundToLong()
    if (m < 1000) return DistanceLabel(m.toString(), DistanceLabel.Unit.METERS)
    val tenths = (meters / 100.0).roundToLong()
    return DistanceLabel("${tenths / 10}.${tenths % 10}", DistanceLabel.Unit.KILOMETERS)
}
