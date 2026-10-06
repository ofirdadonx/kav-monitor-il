package il.kavmonitor.shared.ui

import il.kavmonitor.shared.trigger.LeaveStatus
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.resources.bus_departed_late
import il.kavmonitor.shared.resources.bus_departed_early
import il.kavmonitor.shared.resources.bus_departed_on_time
import il.kavmonitor.shared.resources.leave_at
import il.kavmonitor.shared.resources.leave_now
import il.kavmonitor.shared.resources.leave_alerted
import il.kavmonitor.shared.resources.leave_not_catchable
import il.kavmonitor.shared.resources.leave_no_eta
import il.kavmonitor.shared.resources.eta_estimated
import il.kavmonitor.shared.resources.eta_live
import il.kavmonitor.shared.resources.eta_scheduled

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.bus_alerted
import il.kavmonitor.shared.resources.bus_at_origin
import il.kavmonitor.shared.resources.bus_departure
import il.kavmonitor.shared.resources.bus_dep_eta
import il.kavmonitor.shared.resources.bus_dep_eta_now
import il.kavmonitor.shared.resources.bus_distance
import il.kavmonitor.shared.resources.bus_eta_at
import il.kavmonitor.shared.resources.bus_eta_at_now
import il.kavmonitor.shared.resources.bus_eta_destination
import il.kavmonitor.shared.resources.bus_last_update
import il.kavmonitor.shared.resources.bus_near_stop
import il.kavmonitor.shared.resources.bus_next_stop
import il.kavmonitor.shared.resources.bus_not_started
import il.kavmonitor.shared.resources.bus_not_started_unknown
import il.kavmonitor.shared.resources.bus_off_route
import il.kavmonitor.shared.resources.bus_progress
import il.kavmonitor.shared.resources.bus_progress_short
import il.kavmonitor.shared.resources.bus_vehicle
import il.kavmonitor.shared.resources.gps_fresh
import il.kavmonitor.shared.resources.gps_none
import il.kavmonitor.shared.resources.gps_old
import il.kavmonitor.shared.resources.gps_stale
import il.kavmonitor.shared.resources.unknown
import il.kavmonitor.shared.route.RouteBus
import il.kavmonitor.shared.route.RouteBusStatus
import il.kavmonitor.shared.route.RouteGeometry
import il.kavmonitor.shared.route.RouteLiveFetcher
import il.kavmonitor.shared.util.israelClock
import il.kavmonitor.shared.watch.BusView
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

enum class BusPhase { NOT_STARTED, AT_ORIGIN, ACTIVE, NO_GPS }

/** Everything one bus card shows; built from a live-route [RouteBus] or a watch-screen [BusView]. */
data class BusDetails(
    val key: String,
    val vehicleRef: String?,
    val phase: BusPhase,
    val scheduledStart: Instant?,
    val recordedAt: Instant?,
    val fraction: Double?,
    val remainingMeters: Double?,
    val offRoute: Boolean,
    val nextStop: RouteStop?,
    val nearStop: RouteStop?,
    /** Stop the ETA refers to (target/watched stop); null hides the line. */
    val etaStopName: String?,
    val eta: Instant?,
    /** The ETA stop is the origin: show it as a departure. */
    val etaIsDeparture: Boolean,
    val etaDestination: Instant?,
    val distanceToStopMeters: Double? = null,
    val alerted: Boolean = false,
    /** Time-to-leave watch: leave time and status for this bus (null on other screens). */
    val leaveAt: Instant? = null,
    val leaveStatus: LeaveStatus? = null,
    /** Whether [eta] is a live (GPS-tracked) prediction; null = don't label. */
    val etaLive: Boolean? = null,
    /** Observed departure from the origin (null unless actually observed). */
    val departedAt: Instant? = null,
    /** [eta] derived from a neighbouring stop (watched stop not reported by curlbus). */
    val etaEstimated: Boolean = false,
)

fun RouteBus.toDetails(targetStop: RouteStop?, originCode: String?, observedDeparture: Instant? = null): BusDetails = BusDetails(
    key = key,
    vehicleRef = vehicleRef,
    phase = when (status) {
        RouteBusStatus.ACTIVE -> BusPhase.ACTIVE
        RouteBusStatus.AT_ORIGIN -> BusPhase.AT_ORIGIN
        RouteBusStatus.NOT_STARTED -> BusPhase.NOT_STARTED
        RouteBusStatus.NO_GPS -> BusPhase.NO_GPS
    },
    scheduledStart = scheduledStart,
    recordedAt = recordedAt,
    fraction = position?.fraction,
    remainingMeters = remainingMeters,
    offRoute = offRoute,
    nextStop = nextStop,
    nearStop = nearStop,
    etaStopName = targetStop?.name,
    eta = etaAtTarget,
    etaIsDeparture = targetStop != null && targetStop.code == originCode,
    etaDestination = etaAtDestination,
    departedAt = departedObservedAt ?: observedDeparture,
)

/**
 * Watch-screen bus (from the watched stop's list). The bus is listed at the watched stop, so it has not passed it:
 * its GPS is projected onto the stops before (and including) the watched stop.
 */
fun BusView.toDetails(spec: WatchSpec, stops: List<RouteStop>?, observedDeparture: Instant? = null): BusDetails {
    val atOriginWatch = spec.stop.code == spec.origin.code
    val hasGps = lat != null && lon != null
    val phase = when {
        !hasGps -> BusPhase.NOT_STARTED
        atOriginWatch -> BusPhase.AT_ORIGIN
        else -> BusPhase.ACTIVE
    }
    val watchedIdx = stops?.indexOfFirst { it.code == spec.stop.code } ?: -1
    val pos = if (phase == BusPhase.ACTIVE && stops != null && watchedIdx > 0) RouteGeometry.project(stops, lat!!, lon!!, 0, watchedIdx) else null
    val offRoute = pos != null && pos.offRouteMeters > RouteLiveFetcher.OFF_ROUTE_METERS
    val usable = pos?.takeUnless { offRoute }
    val total = stops?.lastOrNull()?.distanceMeters
    return BusDetails(
        key = key,
        vehicleRef = vehicleRef,
        phase = phase,
        scheduledStart = scheduledDeparture,
        recordedAt = if (hasGps) recordedAt else null,
        fraction = usable?.fraction ?: if (phase == BusPhase.AT_ORIGIN) 0.0 else null,
        remainingMeters = if (usable != null && total != null) (total - usable.alongMeters).coerceAtLeast(0.0) else null,
        offRoute = offRoute,
        nextStop = usable?.let { stops?.getOrNull(it.nextStopIndex) },
        nearStop = usable?.takeIf { it.nearestStopIndex >= 0 && it.nearestStopMeters <= RouteLiveFetcher.NEAR_STOP_METERS }
            ?.let { stops?.getOrNull(it.nearestStopIndex) },
        etaStopName = spec.stop.name,
        eta = eta,
        etaIsDeparture = atOriginWatch,
        etaDestination = null,
        distanceToStopMeters = if (phase == BusPhase.ACTIVE) distanceToStopMeters else null,
        alerted = alerted && leaveStatus == null,
        leaveAt = leaveAt,
        leaveStatus = leaveStatus,
        etaLive = if (spec.trigger == TriggerType.LEAVE_FOR_STOP) hasFreshGps else null,
        departedAt = observedDeparture,
        etaEstimated = etaEstimated,
    )
}

@Composable
fun BusDetailsCard(b: BusDetails, now: Instant, modifier: Modifier = Modifier) {
    val small = MaterialTheme.typography.bodySmall
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.bus_vehicle, b.vehicleRef ?: stringResource(Res.string.unknown)),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                if (b.alerted) Text(stringResource(Res.string.bus_alerted), color = MaterialTheme.colorScheme.primary)
                b.fraction?.let {
                    Text(
                        "  " + stringResource(Res.string.bus_progress_short, (it * 100).roundToInt()),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // Status / schedule
            val sched = b.scheduledStart?.israelClock()
            when (b.phase) {
                BusPhase.NOT_STARTED -> Text(
                    if (sched != null) stringResource(Res.string.bus_not_started, sched) else stringResource(Res.string.bus_not_started_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
                BusPhase.AT_ORIGIN -> Text(
                    if (sched != null) stringResource(Res.string.bus_at_origin, sched) else stringResource(Res.string.bus_not_started_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
                BusPhase.ACTIVE, BusPhase.NO_GPS -> sched?.let { Text(stringResource(Res.string.bus_departure, it), style = small) }
            }
            b.departedAt?.let { dep ->
                val s0 = b.scheduledStart
                if (s0 != null) {
                    val delta = ((dep - s0).inWholeSeconds / 60.0).roundToInt()
                    Text(
                        when {
                            delta > 0 -> stringResource(Res.string.bus_departed_late, dep.israelClock(), delta)
                            delta < 0 -> stringResource(Res.string.bus_departed_early, dep.israelClock(), -delta)
                            else -> stringResource(Res.string.bus_departed_on_time, dep.israelClock())
                        },
                        style = small,
                    )
                }
            }
            // Time to leave
            b.leaveStatus?.let { st ->
                val leave = b.leaveAt
                when (st) {
                    LeaveStatus.WAIT -> if (leave != null) Text(stringResource(Res.string.leave_at, leave.israelClock(), minutesUntil(leave, now)),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    LeaveStatus.LEAVE_NOW -> Text(stringResource(Res.string.leave_now), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    LeaveStatus.ALERTED -> Text(stringResource(Res.string.leave_alerted, leave?.israelClock() ?: "?"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    LeaveStatus.NOT_CATCHABLE -> Text(stringResource(Res.string.leave_not_catchable, leave?.israelClock() ?: "?"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    LeaveStatus.NO_ETA -> Text(stringResource(Res.string.leave_no_eta), style = small)
                }
            }
            // Progress
            if (b.fraction != null && b.phase == BusPhase.ACTIVE) {
                LinearProgressIndicator(progress = { b.fraction.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                Text(
                    stringResource(Res.string.bus_progress, (b.fraction * 100).roundToInt(), b.remainingMeters?.let { kmText(it) } ?: "?"),
                    style = small,
                )
            }
            if (b.offRoute) Text(stringResource(Res.string.bus_off_route), style = small, color = MaterialTheme.colorScheme.error)
            when {
                b.nearStop != null -> Text(stringResource(Res.string.bus_near_stop, isolate(b.nearStop.name)), style = small)
                b.nextStop != null -> Text(stringResource(Res.string.bus_next_stop, isolate(b.nextStop.name)), style = small)
            }
            // ETA to the stop of interest
            if (b.eta != null && b.etaStopName != null) {
                val m = minutesUntil(b.eta, now)
                val clock = b.eta.israelClock()
                Text(
                    when {
                        b.etaIsDeparture && m <= 0 -> stringResource(Res.string.bus_dep_eta_now, clock)
                        b.etaIsDeparture -> stringResource(Res.string.bus_dep_eta, clock, m)
                        m <= 0 -> stringResource(Res.string.bus_eta_at_now, isolate(b.etaStopName), clock)
                        else -> stringResource(Res.string.bus_eta_at, isolate(b.etaStopName), clock, m)
                    } + (if (b.etaEstimated) " · " + stringResource(Res.string.eta_estimated) else "") +
                        (b.etaLive?.let { " · " + stringResource(if (it) Res.string.eta_live else Res.string.eta_scheduled) } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            b.etaDestination?.let { Text(stringResource(Res.string.bus_eta_destination, it.israelClock()), style = small) }
            b.distanceToStopMeters?.let { Text(stringResource(Res.string.bus_distance, it.toInt()), style = small) }
            // Freshness (never for trips that have not started: their SIRI record is hours old)
            if (b.phase == BusPhase.ACTIVE || b.phase == BusPhase.AT_ORIGIN) {
                val rec = b.recordedAt
                if (rec != null) {
                    val age = now - rec
                    val fresh = stringResource(
                        when {
                            age <= 2.minutes -> Res.string.gps_fresh
                            age <= 5.minutes -> Res.string.gps_stale
                            else -> Res.string.gps_old
                        },
                    )
                    Text(stringResource(Res.string.bus_last_update, agoText(age), rec.israelClock(withSeconds = true)) + " · " + fresh, style = small)
                }
            } else if (b.phase == BusPhase.NO_GPS) {
                Text(stringResource(Res.string.gps_none), style = small)
            }
        }
    }
}
