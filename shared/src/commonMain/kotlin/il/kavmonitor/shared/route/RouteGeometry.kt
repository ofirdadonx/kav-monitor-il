package il.kavmonitor.shared.route

import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.util.Geo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Where a GPS fix lies along the route's stop sequence. */
data class RoutePosition(
    /** Distance from the first stop along the route (meters, in the units of [RouteStop.distanceMeters]). */
    val alongMeters: Double,
    /** 0.0..1.0 of the whole route. */
    val fraction: Double,
    /** Fix lies between stop[segmentIndex] and stop[segmentIndex + 1]. */
    val segmentIndex: Int,
    /** Straight-line distance from the fix to the stop-to-stop polyline. */
    val offRouteMeters: Double,
    val nearestStopIndex: Int,
    val nearestStopMeters: Double,
    /** First stop ahead of the fix. */
    val nextStopIndex: Int,
)

object RouteGeometry {
    /**
     * Ensures every stop has [RouteStop.distanceMeters]. GTFS `shape_dist_traveled` (road distance) is kept when present
     * and monotonic; otherwise a straight-line cumulative estimate from the stop coordinates is used for all stops.
     */
    fun withDistances(stops: List<RouteStop>): List<RouteStop> {
        if (stops.size < 2) return stops
        val d = stops.map { it.distanceMeters }
        val complete = d.all { it != null } && d.zipWithNext().all { (a, b) -> b!! >= a!! } && d.last()!! > 0
        if (complete) return stops
        if (stops.any { it.lat == null || it.lon == null }) return stops.map { it.copy(distanceMeters = null) }
        var acc = 0.0
        return stops.mapIndexed { i, s ->
            if (i > 0) acc += Geo.distanceMeters(stops[i - 1].lat!!, stops[i - 1].lon!!, s.lat!!, s.lon!!)
            s.copy(distanceMeters = acc)
        }
    }

    /**
     * Projects a GPS fix onto the straight segments between consecutive stops in [fromIndex]..[toIndex] and interpolates
     * the GTFS distance. Restricting the range (bus is known to be past stop `fromIndex` and before stop `toIndex`) avoids
     * snapping to the wrong leg of routes that loop back on themselves. Null when distances/coordinates are missing.
     */
    fun project(stops: List<RouteStop>, lat: Double, lon: Double, fromIndex: Int = 0, toIndex: Int = stops.lastIndex): RoutePosition? {
        if (stops.size < 2) return null
        val total = stops.last().distanceMeters?.takeIf { it > 0 } ?: return null
        val from = fromIndex.coerceIn(0, stops.lastIndex)
        val to = toIndex.coerceIn(from, stops.lastIndex)
        val cosLat = cos(lat * PI / 180)
        fun xy(sLat: Double, sLon: Double) = Pair((sLon - lon) * cosLat * M_PER_DEG, (sLat - lat) * M_PER_DEG)

        var best: RoutePosition? = null
        if (from == to) {
            val s = stops[to]
            val dist = s.distanceMeters ?: return null
            val off = if (s.lat != null && s.lon != null) Geo.distanceMeters(lat, lon, s.lat, s.lon) else return null
            return RoutePosition(dist, (dist / total).coerceIn(0.0, 1.0), (to - 1).coerceAtLeast(0), off, to, off, to)
        }
        for (i in from until to) {
            val a = stops[i]; val b = stops[i + 1]
            if (a.lat == null || a.lon == null || b.lat == null || b.lon == null) continue
            val da = a.distanceMeters ?: continue
            val db = b.distanceMeters ?: continue
            val (ax, ay) = xy(a.lat, a.lon)
            val (bx, by) = xy(b.lat, b.lon)
            val vx = bx - ax; val vy = by - ay
            val len2 = vx * vx + vy * vy
            // point is at the origin of the local frame
            val t = if (len2 == 0.0) 0.0 else (((-ax) * vx + (-ay) * vy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * vx; val py = ay + t * vy
            val off = sqrt(px * px + py * py)
            if (best == null || off < best.offRouteMeters) {
                val along = da + t * (db - da)
                best = RoutePosition(along, (along / total).coerceIn(0.0, 1.0), i, off, -1, Double.MAX_VALUE, i + 1)
            }
        }
        val pos = best ?: return null
        var nearest = -1; var nearestM = Double.MAX_VALUE
        for (i in from..to) {
            val s = stops[i]
            if (s.lat == null || s.lon == null) continue
            val m = Geo.distanceMeters(lat, lon, s.lat, s.lon)
            if (m < nearestM) { nearest = i; nearestM = m }
        }
        return pos.copy(nearestStopIndex = nearest, nearestStopMeters = nearestM)
    }

    private const val M_PER_DEG = 6_371_000.0 * PI / 180
}
