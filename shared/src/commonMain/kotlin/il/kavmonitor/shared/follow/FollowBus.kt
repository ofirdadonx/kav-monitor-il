package il.kavmonitor.shared.follow

import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.route.RouteBus
import il.kavmonitor.shared.route.RouteBusStatus
import il.kavmonitor.shared.route.RouteGeometry
import il.kavmonitor.shared.route.RouteLiveSnapshot
import il.kavmonitor.shared.util.Geo
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One bus the user long-pressed, as shown in the "פעולות לרכב" sheet header. Identified by the vehicle number;
 * the trip id is the fallback when the vehicle number is missing (or the operator swaps vehicles mid-trip).
 */
data class BusRef(
    val lineRef: Int,
    val lineName: String,
    val key: String,
    val vehicleRef: String?,
    val tripId: String?,
    val scheduledStart: Instant?,
    val percent: Int?,
)

/** One entry of the vehicle actions sheet. Extensible: add entries in [BusActions.forBus]. */
data class BusAction(val id: String, val label: String)

object BusActions {
    const val FOLLOW = "follow"
    const val UNFOLLOW = "unfollow"

    /** Actions for [bus]; [followed] = the bus currently followed (if any). */
    fun forBus(bus: BusRef, followed: BusRef?): List<BusAction> = buildList {
        if (followed != null && FollowBus.sameBus(followed, bus)) add(BusAction(UNFOLLOW, "הפסק לעקוב אחרי האוטובוס"))
        else add(BusAction(FOLLOW, "עקוב אחרי האוטובוס הזה"))
    }
}

enum class FollowStatus {
    /** Live GPS fix, still before the reference stop. */
    TRACKING,
    /** Listed by curlbus (planned / before the start) but no GPS fix yet. */
    NO_GPS,
    /** The bus is past the reference stop. */
    PASSED_STOP,
    /** Not in the latest refresh: trip finished, or SIRI lost it. */
    GONE,
    /** No refresh yet since following started. */
    WAITING,
}

data class FollowInfo(
    val status: FollowStatus,
    val bus: RouteBus?,
    /** Stop the minutes refer to: the boarding/watched stop, else the stop nearest the user. */
    val refStop: RouteStop?,
    val etaAtStop: Instant?,
    /** true = curlbus prediction for [refStop]; false = estimate from the remaining distance at [FollowBus.AVG_SPEED_MPS]. */
    val etaFromCurlbus: Boolean,
    val minutesToStop: Int?,
    /** Straight-line ("בקו אווירי") distance from the bus to the user; null without both fixes. */
    val distanceToUserMeters: Double?,
    val gpsAge: Duration?,
    val nextStop: RouteStop?,
    val percent: Int?,
    /** Last refresh in which the bus was seen (for [FollowStatus.GONE]). */
    val lastSeenAt: Instant?,
)

/**
 * Follow one vehicle using the route view's refresh (no polling of its own). v0.5: the route view fetches every working
 * stop in one request, so curlbus's ETA of the bus at the user's stop comes straight from that answer.
 */
object FollowBus {
    /** City-bus average including stops, for the fallback estimate only (labelled "הערכה"). */
    const val AVG_SPEED_MPS = 4.5
    /** Past the reference stop by more than this along the route = passed. */
    const val PASSED_MARGIN_METERS = 60.0

    fun sameBus(a: BusRef, b: BusRef): Boolean = a.lineRef == b.lineRef && (
        (a.vehicleRef != null && a.vehicleRef == b.vehicleRef) ||
            (a.vehicleRef == null || b.vehicleRef == null) && a.tripId != null && a.tripId == b.tripId
        )

    /** The followed bus in [snap]: vehicle number first, trip id as fallback. */
    fun find(snap: RouteLiveSnapshot, ref: BusRef): RouteBus? =
        ref.vehicleRef?.let { v -> snap.buses.firstOrNull { it.vehicleRef == v } }
            ?: ref.tripId?.let { t -> snap.buses.firstOrNull { it.tripId == t } }

    fun compute(
        snap: RouteLiveSnapshot?,
        ref: BusRef,
        stops: List<RouteStop>,
        refStop: RouteStop?,
        userLat: Double?,
        userLon: Double?,
        now: Instant,
        lastSeenAt: Instant?,
    ): FollowInfo {
        if (snap == null) return FollowInfo(FollowStatus.WAITING, null, refStop, null, false, null, null, null, null, ref.percent, lastSeenAt)
        val bus = find(snap, ref)
            ?: return FollowInfo(FollowStatus.GONE, null, refStop, null, false, null, null, null, null, null, lastSeenAt)
        val percent = bus.position?.let { (it.fraction * 100).roundToInt() }
        val gpsAge = bus.gpsAge(now)
        val dist = if (bus.hasGps && userLat != null && userLon != null) Geo.distanceMeters(bus.lat!!, bus.lon!!, userLat, userLon) else null
        if (!bus.hasGps || bus.status == RouteBusStatus.NOT_STARTED || bus.status == RouteBusStatus.NO_GPS) {
            val eta = curlbusEtaAt(snap, bus, refStop)
            return FollowInfo(FollowStatus.NO_GPS, bus, refStop, eta, eta != null, eta?.let { minutesUntil(it, now) }, null, null, bus.nextStop, percent, snap.fetchedAt)
        }
        val withDist = RouteGeometry.withDistances(stops)
        val refAlong = refStop?.let { r -> withDist.firstOrNull { it.code == r.code }?.distanceMeters }
        val along = bus.position?.alongMeters
        val passed = refAlong != null && along != null && along > refAlong + PASSED_MARGIN_METERS
        if (passed) {
            return FollowInfo(FollowStatus.PASSED_STOP, bus, refStop, null, false, null, dist, gpsAge, bus.nextStop, percent, snap.fetchedAt)
        }
        val curlbusEta = curlbusEtaAt(snap, bus, refStop)
        val eta = curlbusEta ?: if (refAlong != null && along != null && bus.recordedAt != null) {
            bus.recordedAt + ((refAlong - along).coerceAtLeast(0.0) / AVG_SPEED_MPS).seconds
        } else null
        return FollowInfo(
            FollowStatus.TRACKING, bus, refStop, eta, curlbusEta != null, eta?.let { minutesUntil(it, now) },
            dist, gpsAge, bus.nextStop, percent, snap.fetchedAt,
        )
    }

    /** curlbus's own ETA of [bus] at [refStop]: from the full-route answer (every stop), or the route view's target. */
    fun curlbusEtaAt(snap: RouteLiveSnapshot, bus: RouteBus, refStop: RouteStop?): Instant? {
        refStop ?: return null
        return bus.etaByStop[refStop.code] ?: if (snap.targetStopCode == refStop.code) bus.etaAtTarget else null
    }

    /** Whole minutes until [t], rounded up, never negative. */
    fun minutesUntil(t: Instant, now: Instant): Int = ceil((t - now).inWholeSeconds.coerceAtLeast(0) / 60.0).toInt()

    /** Reference stop: the boarding/watched stop if given, else the stop nearest the user (if located). */
    fun refStop(stops: List<RouteStop>, preferred: RouteStop?, userLat: Double?, userLon: Double?): RouteStop? =
        preferred ?: if (userLat != null && userLon != null) il.kavmonitor.shared.location.NearestStops.find(stops, userLat, userLon)?.stop else null
}
