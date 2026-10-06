package il.kavmonitor.shared.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** One direction/variant of a line for a given day, as published in GTFS (Stride `gtfs_routes`). */
@Serializable
data class Direction(
    val lineRef: Int,
    val routeShortName: String,
    val longName: String,
    val agencyName: String,
    val operatorRef: Int,
    val routeDirection: String,
    val routeAlternative: String,
    val gtfsRouteId: Long,
    val date: String,
) {
    /** Origin/destination parsed from GTFS `route_long_name` (display fallback when the stop list is not loaded). */
    val parsedName: RouteName get() = RouteName.parse(longName, routeDirection, routeAlternative)

    /** Destination label stored in [WatchSpec.directionLabel]: "stop, city". */
    val destinationLabel: String get() = parsedName.destination.label.ifBlank { longName }
}

@Serializable
data class RouteStop(
    val sequence: Int,
    val code: String,
    val name: String,
    val city: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Distance along the route from the first stop (GTFS shape_dist_traveled, meters; or straight-line estimate). */
    val distanceMeters: Double? = null,
    /** Scheduled seconds from the first stop's departure (from one representative GTFS ride). */
    val offsetSeconds: Int? = null,
)

/** Where the stop list came from (GTFS for today, GTFS from an earlier day, or SIRI history). */
enum class StopListSource { GTFS_TODAY, GTFS_PREVIOUS_DAY, SIRI_HISTORY }

data class StopList(val lineRef: Int, val stops: List<RouteStop>, val source: StopListSource, val sourceDate: String) {
    /** Route length in meters (distance of the last stop), when known. */
    val lengthMeters: Double? get() = stops.lastOrNull()?.distanceMeters?.takeIf { it > 0 }
}

/** A single realtime "visit" (upcoming call) of a vehicle at a stop, normalized from the live source. */
data class LiveVisit(
    val stopCode: String,
    val lineName: String?,
    /** Same value as Stride `line_ref` (curlbus `line_id` / `route_id`). */
    val lineRef: Int?,
    val operatorRef: Int?,
    val vehicleRef: String?,
    val tripId: String?,
    /** Predicted arrival at [stopCode]. */
    val eta: Instant?,
    /** curlbus `departed`: the trip's (aimed) departure time from its ORIGIN, not a "has departed" flag. */
    val originDepartureTime: Instant?,
    /** curlbus `timestamp`: when the SIRI record was recorded. Stale (hours old) before the trip starts. */
    val recordedAt: Instant?,
    val lat: Double?,
    val lon: Double?,
) {
    val hasGps: Boolean get() = lat != null && lon != null
    /** Stable key for a bus on a trip. Trip id first: vehicles can be swapped on a trip. */
    val key: String get() = tripId?.takeIf { it.isNotBlank() } ?: "veh:${vehicleRef.orEmpty()}"
}

/** Result of one poll of one stop. */
data class StopSnapshot(
    val stopCode: String,
    val fetchedAt: Instant,
    val serverTime: Instant?,
    /** All visits at the stop (all lines). */
    val allVisits: List<LiveVisit>,
    val sourceErrors: String? = null,
) {
    fun forLine(lineRef: Int, lineName: String? = null, operatorRef: Int? = null): List<LiveVisit> =
        allVisits.filter { v ->
            if (v.lineRef != null) v.lineRef == lineRef
            else v.lineName == lineName && (operatorRef == null || v.operatorRef == null || v.operatorRef == operatorRef)
        }
}

@Serializable
enum class TriggerType {
    DEPARTS_ORIGIN,
    ARRIVES_AT_STOP,
    /** "Time to leave": alert when it's time to walk to the boarding stop [WatchSpec.stop] (see [LeavePlan]). */
    LEAVE_FOR_STOP,
}

/**
 * Walking plan of a LEAVE_FOR_STOP watch: alert at (ETA at the boarding stop − [walkMinutes] − [bufferMinutes]).
 * [walkMinutes] is computed from the user's location (straight line × detour ÷ speed) unless [manual].
 */
@Serializable
data class LeavePlan(
    val walkMinutes: Int,
    val bufferMinutes: Int = 1,
    val manual: Boolean = false,
    /** Straight-line distance user → boarding stop the walk time was computed from (null for manual). */
    val walkDistanceMeters: Double? = null,
) {
    val leadMinutes: Int get() = walkMinutes + bufferMinutes
}

/** Everything needed to run a watch without touching the static API again (also what recents store). */
@Serializable
data class WatchSpec(
    val lineRef: Int,
    val lineName: String,
    val operatorRef: Int,
    val agencyName: String,
    val directionLabel: String,
    val trigger: TriggerType,
    /** The stop that is polled: origin for DEPARTS_ORIGIN, chosen stop for ARRIVES_AT_STOP, boarding stop for LEAVE_FOR_STOP. */
    val stop: RouteStop,
    val origin: RouteStop,
    val durationMinutes: Int = 60,
    /** LEAVE_FOR_STOP only: walk time + buffer when the watch was started. */
    val leave: LeavePlan? = null,
) {
    /** Identity used for de-duplicating recents (line+operator+direction+trigger(+stop)). */
    val recentKey: String get() = "$lineRef|$operatorRef|$trigger|${stop.code}"
}
