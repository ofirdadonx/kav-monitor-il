package il.kavmonitor.shared.watch

import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.util.Geo
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Which working stops stand in for a watched stop that curlbus can't answer (HTTP 5xx).
 * [upstream]: nearest working stop before the target (a bus listed there hasn't reached the target yet).
 * [downstream]: nearest working stop after it (a bus heading there passes the target first).
 * At most these two are polled (≤ 2 curlbus calls per poll).
 */
data class FallbackPlan(
    val target: RouteStop,
    val upstream: RouteStop?,
    val downstream: RouteStop?,
    /** The direction's stops (with distances), to tell whether a bus has passed the target. */
    val stops: List<RouteStop> = emptyList(),
) {
    val polled: List<RouteStop> get() = listOfNotNull(upstream, downstream)
}

object StopFallback {
    /** Assumed bus speed when the stop list has no GTFS times (≈ 18 km/h, urban average incl. stops). */
    const val DEFAULT_BUS_SPEED_M_PER_S = 5.0
    /** Same radius as the arrival rule: a fix this close to the target counts as "at the target". */
    const val NEAR_TARGET_METERS = 150.0
    /** An estimated target ETA this far in the past (bus not listed upstream any more) means it has passed. */
    val PASSED_ETA_SLACK: Duration = 2.minutes

    fun plan(trigger: TriggerType, stops: List<RouteStop>, target: RouteStop, isBroken: (String) -> Boolean): FallbackPlan? {
        val i = stops.indexOfFirst { it.code == target.code && it.sequence == target.sequence }
            .takeIf { it >= 0 } ?: stops.indexOfFirst { it.code == target.code }.takeIf { it >= 0 } ?: return null
        val down = stops.drop(i + 1).firstOrNull { it.code != target.code && !isBroken(it.code) }
        // "departs origin": the next working stop's drop-off / GPS logic replaces the origin's; no upstream exists
        val up = if (trigger == TriggerType.DEPARTS_ORIGIN) null
        else stops.take(i).lastOrNull { it.code != target.code && !isBroken(it.code) }
        if (up == null && down == null) return null
        val withDist = il.kavmonitor.shared.route.RouteGeometry.withDistances(stops)
        return FallbackPlan(withDist[i], up?.let { u -> withDist.first { it.sequence == u.sequence } },
            down?.let { d -> withDist.first { it.sequence == d.sequence } }, withDist)
    }

    /** Scheduled travel time [from] → [to] (to after from): GTFS stop times, else route distance, else straight line × 1.3. */
    fun travel(from: RouteStop, to: RouteStop): Duration {
        val a = from.offsetSeconds
        val b = to.offsetSeconds
        if (a != null && b != null && b >= a) return (b - a).seconds
        val da = from.distanceMeters
        val db = to.distanceMeters
        val meters = if (da != null && db != null && db >= da) db - da
        else if (from.lat != null && from.lon != null && to.lat != null && to.lon != null) Geo.distanceMeters(from.lat, from.lon, to.lat, to.lon) * 1.3
        else 0.0
        return (meters / DEFAULT_BUS_SPEED_M_PER_S).roundToInt().seconds
    }

    /**
     * Synthesises the target stop's visit list from the neighbours' lists, so the unchanged [il.kavmonitor.shared.trigger.TriggerDetector]
     * can run on it. Per bus: ETA at target = ETA upstream + travel(up → target) if still listed upstream, else ETA downstream −
     * travel(target → down). The freshest GPS fix of either listing is kept (the 150 m rule uses the target's coordinates).
     * A bus that is no longer listed upstream and has passed the target (fix beyond it towards downstream, or estimated ETA
     * more than [PASSED_ETA_SLACK] ago) is left out, which the detector reads as "arrived / passed".
     * [up]/[down] are null when that stop wasn't polled or the poll failed.
     */
    fun derive(plan: FallbackPlan, up: List<LiveVisit>?, down: List<LiveVisit>?, now: Instant, gpsFreshMaxAge: Duration): List<LiveVisit> {
        val target = plan.target
        val upMap = up.orEmpty().let(WatchEngine::uniqueByKey).associateBy { it.key }
        val downMap = down.orEmpty().let(WatchEngine::uniqueByKey).associateBy { it.key }
        val keys = (upMap.keys + downMap.keys).distinct()
        return keys.mapNotNull { key ->
            val u = upMap[key]
            val d = downMap[key]
            val base = u ?: d!!
            val gps = listOfNotNull(u, d).filter { it.hasGps }.maxByOrNull { it.recordedAt ?: Instant.DISTANT_PAST }
            val eta = when {
                u?.eta != null && plan.upstream != null -> u.eta + travel(plan.upstream, target)
                d?.eta != null && plan.downstream != null -> d.eta - travel(target, plan.downstream)
                else -> null
            }
            if (u == null) {
                val fresh = gps?.recordedAt?.let { now - it <= gpsFreshMaxAge } == true
                if (fresh && passedTarget(gps!!, plan)) return@mapNotNull null
                if (eta != null && eta < now - PASSED_ETA_SLACK) return@mapNotNull null
            }
            base.copy(
                stopCode = target.code, eta = eta,
                recordedAt = gps?.recordedAt ?: base.recordedAt, lat = gps?.lat, lon = gps?.lon,
                vehicleRef = base.vehicleRef ?: listOfNotNull(u, d).firstNotNullOfOrNull { it.vehicleRef },
            )
        }
    }

    /**
     * The fix, projected onto the stop-to-stop polyline between the upstream and downstream stand-ins, lies more than
     * [NEAR_TARGET_METERS] beyond the target along the route (straight-line distances mislead on curvy legs: from 25141 the
     * route first heads south-west, while 31492 is south-east).
     */
    private fun passedTarget(v: LiveVisit, plan: FallbackPlan): Boolean {
        val stops = plan.stops
        val targetAlong = plan.target.distanceMeters ?: return false
        if (stops.size < 2) return false
        val from = plan.upstream?.let { u -> stops.indexOfFirst { it.sequence == u.sequence } }?.takeIf { it >= 0 }
            ?: stops.indexOfFirst { it.sequence == plan.target.sequence }
        val to = plan.downstream?.let { d -> stops.indexOfFirst { it.sequence == d.sequence } }?.takeIf { it >= 0 } ?: stops.lastIndex
        val pos = il.kavmonitor.shared.route.RouteGeometry.project(stops, v.lat!!, v.lon!!, from.coerceAtLeast(0), to) ?: return false
        if (pos.offRouteMeters > MAX_OFF_ROUTE_METERS) return false
        return pos.alongMeters > targetAlong + NEAR_TARGET_METERS
    }

    /** Fixes farther than this from the stop polyline aren't used for "passed" (could be on another leg). */
    const val MAX_OFF_ROUTE_METERS = 400.0
}
