package il.kavmonitor.shared.route

import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.model.StopSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** What the live route view shows: one direction (line_ref) and its ordered stops. */
data class RouteLiveRequest(
    val lineRef: Int,
    val lineName: String,
    val operatorRef: Int,
    val stops: List<RouteStop>,
    /** Optional stop of interest (watched / chosen stop): always sampled so each bus gets an ETA to it. */
    val targetStopCode: String? = null,
)

enum class RouteBusStatus {
    /** Live trip with a GPS fix, already away from the origin's list. */
    ACTIVE,
    /** Live trip (GPS) still listed at the origin: about to leave. */
    AT_ORIGIN,
    /** Planned trip, no GPS yet (listed at the origin or scheduled in the future). */
    NOT_STARTED,
    /** Scheduled start has passed and the trip is listed down the route, but SIRI has no location for it. */
    NO_GPS,
}

data class RouteBus(
    val key: String,
    val vehicleRef: String?,
    val tripId: String?,
    val status: RouteBusStatus,
    /** curlbus `departed`: planned departure from the origin. */
    val scheduledStart: Instant?,
    /** When the SIRI record (GPS fix) was recorded. Only meaningful when [hasGps]. */
    val recordedAt: Instant?,
    val lat: Double?,
    val lon: Double?,
    /** Null when unknown or when the fix is too far from the route to give an honest percentage. */
    val position: RoutePosition?,
    val offRoute: Boolean,
    val nextStop: RouteStop?,
    /** Stop the bus is at/next to (within [RouteLiveFetcher.NEAR_STOP_METERS]). */
    val nearStop: RouteStop?,
    val etaAtTarget: Instant?,
    val etaAtDestination: Instant?,
    val remainingMeters: Double?,
    /**
     * When this view saw the bus leave the origin: it was listed at the origin on the previous refresh (≤ 2.5 min earlier)
     * and is now live and gone from the origin's list. Null unless actually observed. SIRI drops a trip from the origin
     * list when it goes live, ≈1 min before it pulls out (seen live), so this is approximate (± ~1 min).
     */
    val departedObservedAt: Instant? = null,
) {
    val hasGps: Boolean get() = lat != null && lon != null
    val isActive: Boolean get() = status == RouteBusStatus.ACTIVE || status == RouteBusStatus.AT_ORIGIN
    fun gpsAge(now: Instant): Duration? = if (hasGps) recordedAt?.let { now - it } else null
}

data class RouteLiveSnapshot(
    val fetchedAt: Instant,
    val buses: List<RouteBus>,
    val sampledStops: List<RouteStop>,
    val failedStops: List<RouteStop>,
    val targetStopCode: String?,
) {
    val active: List<RouteBus> get() = buses.filter { it.isActive }
    val notStarted: List<RouteBus> get() = buses.filter { it.status == RouteBusStatus.NOT_STARTED }
    val noGps: List<RouteBus> get() = buses.filter { it.status == RouteBusStatus.NO_GPS }
}

/**
 * Builds the live picture of one direction from curlbus (SIRI StopMonitoring) lists of a few sampled stops
 * ([RouteSampling]): every live bus is listed at the stops ahead of it, with its latest GPS fix (≈15–50 s old).
 * Progress = GPS projected onto the stop sequence between the last sampled stop it already passed and the first sampled
 * stop that still lists it, interpolating GTFS `shape_dist_traveled`.
 *
 * A sampled stop that fails (curlbus answers HTTP 500 for some stops) is replaced by a neighbour, remembered for the
 * next refreshes. At most [maxParallel] requests run at once.
 */
class RouteLiveFetcher(
    private val live: LiveDataSource,
    val request: RouteLiveRequest,
    private val clock: Clock = Clock.System,
    private val maxParallel: Int = 3,
) {
    private val stops = request.stops
    private val targetIdx: Int? = request.targetStopCode?.let { c -> stops.indexOfFirst { it.code == c }.takeIf { it >= 0 } }
    val sampleIndices: List<Int> = (RouteSampling.sampleIndices(stops) + listOfNotNull(targetIdx)).distinct().sorted()
    private val substitute = mutableMapOf<Int, Int>()
    /** key → time of the last refresh in which the bus was listed at the origin (origin sample succeeded). */
    private val lastSeenAtOrigin = mutableMapOf<String, Instant>()
    private val departedAt = mutableMapOf<String, Instant>()

    private class SampleResult(val base: Int, val used: Int?, val snapshot: StopSnapshot?)

    suspend fun refresh(): RouteLiveSnapshot {
        val subs = substitute.toMap()
        val results = coroutineScope {
            val sem = Semaphore(maxParallel)
            sampleIndices.map { base -> async { sem.withPermit { fetchSample(base, subs[base]) } } }.awaitAll()
        }
        results.forEach { r -> if (r.used != null && r.used != r.base) substitute[r.base] = r.used else if (r.used == r.base) substitute.remove(r.base) }
        if (results.all { it.snapshot == null }) throw IllegalStateException("No live data from any sampled stop")
        return build(clock.now(), results)
    }

    private suspend fun fetchSample(base: Int, sub: Int?): SampleResult {
        val candidates = buildList {
            if (sub != null) add(sub)
            add(base)
            if (base != targetIdx) { // the target needs its own ETA; a neighbour would be wrong
                if (base - 1 > 0 && base - 1 !in sampleIndices) add(base - 1)
                if (base + 1 <= stops.lastIndex && base + 1 !in sampleIndices) add(base + 1)
            }
        }.distinct().take(3)
        for (idx in candidates) {
            try {
                return SampleResult(base, idx, live.stopVisits(stops[idx].code))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // try the next candidate
            }
        }
        return SampleResult(base, null, null)
    }

    private fun build(now: Instant, results: List<SampleResult>): RouteLiveSnapshot {
        val ok = results.filter { it.snapshot != null && it.used != null }
        val succeeded = ok.map { it.used!! }.distinct().sorted()
        val byKey = LinkedHashMap<String, MutableList<Pair<Int, LiveVisit>>>()
        for (r in ok) {
            for (v in r.snapshot!!.forLine(request.lineRef, request.lineName, request.operatorRef)) {
                byKey.getOrPut(v.key) { mutableListOf() } += r.used!! to v
            }
        }
        if (0 in succeeded) {
            for ((key, list) in byKey) {
                val atOrigin = list.any { it.first == 0 }
                val seen = lastSeenAtOrigin[key]
                if (atOrigin) {
                    lastSeenAtOrigin[key] = now
                } else if (seen != null && list.any { it.second.hasGps } && now - seen <= MAX_DEPARTURE_OBSERVE_GAP && key !in departedAt) {
                    departedAt[key] = now
                }
            }
            lastSeenAtOrigin.keys.retainAll { k -> byKey[k]?.any { it.first == 0 } == true }
        }
        val buses = byKey.map { (key, list) -> busFrom(now, key, list, succeeded).copy(departedObservedAt = departedAt[key]) }
            .sortedWith(
                compareBy<RouteBus>({ statusOrder(it.status) }, { -(it.position?.fraction ?: -1.0) }, { it.scheduledStart })
            )
        return RouteLiveSnapshot(
            fetchedAt = now,
            buses = buses,
            sampledStops = succeeded.map { stops[it] },
            failedStops = results.filter { it.snapshot == null }.map { stops[it.base] },
            targetStopCode = request.targetStopCode,
        )
    }

    private fun statusOrder(s: RouteBusStatus) = when (s) {
        RouteBusStatus.ACTIVE -> 0
        RouteBusStatus.AT_ORIGIN -> 1
        RouteBusStatus.NO_GPS -> 2
        RouteBusStatus.NOT_STARTED -> 3
    }

    private fun busFrom(now: Instant, key: String, list: List<Pair<Int, LiveVisit>>, succeeded: List<Int>): RouteBus {
        val listed = list.map { it.first }.distinct().sorted()
        val firstListed = listed.first()
        val gpsVisit = list.filter { it.second.hasGps }.maxByOrNull { it.second.recordedAt ?: Instant.DISTANT_PAST }?.second
        val rep = gpsVisit ?: list.maxBy { it.second.recordedAt ?: Instant.DISTANT_PAST }.second
        val scheduled = list.firstNotNullOfOrNull { it.second.originDepartureTime }
        val listedAtOrigin = 0 in listed
        val status = when {
            gpsVisit != null && listedAtOrigin -> RouteBusStatus.AT_ORIGIN
            gpsVisit != null -> RouteBusStatus.ACTIVE
            listedAtOrigin || (scheduled != null && scheduled > now - NOT_STARTED_GRACE) -> RouteBusStatus.NOT_STARTED
            else -> RouteBusStatus.NO_GPS
        }
        var position: RoutePosition? = null
        var offRoute = false
        if (gpsVisit != null) {
            // SIRI drops a visit ~1 min before the bus reaches the stop: allow one extra segment behind.
            val prevPassed = succeeded.filter { it < firstListed }.maxOrNull() ?: 0
            val from = (prevPassed - 1).coerceAtLeast(0)
            val p = if (status == RouteBusStatus.AT_ORIGIN) RouteGeometry.project(stops, gpsVisit.lat!!, gpsVisit.lon!!, 0, 0)
                    else RouteGeometry.project(stops, gpsVisit.lat!!, gpsVisit.lon!!, from, firstListed)
            if (p != null) {
                offRoute = p.offRouteMeters > OFF_ROUTE_METERS && status == RouteBusStatus.ACTIVE
                position = p.takeUnless { offRoute }
            }
        }
        val total = stops.lastOrNull()?.distanceMeters
        val lastIdx = stops.lastIndex
        return RouteBus(
            key = key,
            vehicleRef = rep.vehicleRef,
            tripId = rep.tripId,
            status = status,
            scheduledStart = scheduled,
            recordedAt = gpsVisit?.recordedAt,
            lat = gpsVisit?.lat,
            lon = gpsVisit?.lon,
            position = position,
            offRoute = offRoute,
            nextStop = position?.takeIf { status == RouteBusStatus.ACTIVE }?.nextStopIndex?.let { stops.getOrNull(it) },
            nearStop = position?.takeIf { it.nearestStopIndex >= 0 && it.nearestStopMeters <= NEAR_STOP_METERS }?.let { stops[it.nearestStopIndex] },
            etaAtTarget = targetIdx?.let { t -> list.firstOrNull { it.first == t }?.second?.eta },
            etaAtDestination = list.firstOrNull { it.first == lastIdx }?.second?.eta,
            remainingMeters = if (position != null && total != null) (total - position.alongMeters).coerceAtLeast(0.0) else null,
        )
    }

    companion object {
        const val NEAR_STOP_METERS = 150.0
        /** Fix farther than this from the stop-to-stop polyline: no percentage (straight chords between far stops can be ~1 km off the road). */
        const val OFF_ROUTE_METERS = 2_000.0
        val NOT_STARTED_GRACE: Duration = 2.minutes
        /** A departure counts as observed only if the bus was seen at the origin at most this long before. */
        val MAX_DEPARTURE_OBSERVE_GAP: Duration = 150.seconds
    }
}
