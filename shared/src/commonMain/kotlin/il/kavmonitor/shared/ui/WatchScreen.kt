package il.kavmonitor.shared.ui

import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.watch.LeavePlanHolder
import il.kavmonitor.shared.resources.walk_in_use
import il.kavmonitor.shared.resources.walk_in_use_auto
import il.kavmonitor.shared.resources.walk_in_use_manual
import il.kavmonitor.shared.resources.walk_in_use_default

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.walk_minutes
import il.kavmonitor.shared.resources.watch_walk_edit
import il.kavmonitor.shared.resources.watch_extend_by
import il.kavmonitor.shared.resources.watch_extend
import il.kavmonitor.shared.resources.walk_in_use_routed
import il.kavmonitor.shared.resources.label_trigger
import il.kavmonitor.shared.resources.dir_destination
import il.kavmonitor.shared.resources.dir_origin
import il.kavmonitor.shared.resources.back
import il.kavmonitor.shared.resources.new_watch_again
import il.kavmonitor.shared.resources.restart_watch
import il.kavmonitor.shared.resources.route_live_need_stops
import il.kavmonitor.shared.resources.unknown
import il.kavmonitor.shared.resources.watch_alerts
import il.kavmonitor.shared.resources.watch_alerts_header
import il.kavmonitor.shared.resources.watch_alert_item
import il.kavmonitor.shared.resources.watch_buses_header
import il.kavmonitor.shared.resources.watch_buses_seen
import il.kavmonitor.shared.resources.watch_error
import il.kavmonitor.shared.resources.fallback_one
import il.kavmonitor.shared.resources.fallback_two
import il.kavmonitor.shared.resources.fallback_departs
import il.kavmonitor.shared.resources.watch_retrying_none
import il.kavmonitor.shared.resources.watch_retrying_since
import il.kavmonitor.shared.resources.watch_last_update
import il.kavmonitor.shared.resources.watch_no_buses
import il.kavmonitor.shared.resources.watch_no_update_yet
import il.kavmonitor.shared.resources.watch_status_running
import il.kavmonitor.shared.resources.watch_status_stopped
import il.kavmonitor.shared.resources.watch_status_timed_out
import il.kavmonitor.shared.resources.watch_stop
import il.kavmonitor.shared.resources.watch_title
import il.kavmonitor.shared.route.RouteLiveRequest
import il.kavmonitor.shared.util.israelClock
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchState
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

@Composable
fun WatchScreen(c: AppController, state: WatchState?) {
    val now by produceState(c.clock.now()) { while (true) { delay(1000); value = c.clock.now() } }
    val spec = state?.spec
    if (spec != null) LaunchedEffect(spec.lineRef) { c.ensureWatchStops(spec) }
    val stops = (c.watchStops as? Loadable.Ready)?.value?.takeIf { spec != null && it.lineRef == spec.lineRef }

    // Live route view on the watch screen: foreground only, only while the watch runs (Stop ends ALL polling),
    // detached when the screen is left.
    if (spec != null && stops != null && state?.isActive == true) {
        DisposableEffect(spec.lineRef, spec.stop.code, stops) {
            val token = c.routeLive.attach(RouteLiveRequest(spec.lineRef, spec.lineName, spec.operatorRef, stops.stops, spec.stop.code))
            onDispose { c.routeLive.detach(token) }
        }
    }

    // Time to leave: refresh the walk time from the location while this screen is visible and the watch runs.
    if (spec != null && spec.trigger == TriggerType.LEAVE_FOR_STOP && state?.isActive == true) {
        DisposableEffect(spec.recentKey) {
            val token = c.location.attach()
            onDispose { c.location.detach(token) }
        }
        LaunchedEffect(c.location.fix, spec.recentKey, c.walkVersion) { c.refreshWatchWalk(spec) }
    }
    val routeSnap = c.routeLive.state?.snapshot
    val observed = observedDepartures(state, routeSnap)

    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { c.back() }) { Text("→ " + stringResource(Res.string.back)) }
                Text(stringResource(Res.string.watch_title), style = MaterialTheme.typography.headlineSmall)
            }
        }
        if (state == null) {
            item { Loading() }
            return@LazyColumn
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(lineTitle(state.spec), style = MaterialTheme.typography.titleLarge)
                    // labeled rows like the direction cards: origin, destination, then what the alert is about
                    val (destName, destCity) = destinationParts(state.spec)
                    LabeledRow(stringResource(Res.string.dir_origin), isolate(state.spec.origin.name), state.spec.origin.city)
                    LabeledRow(stringResource(Res.string.dir_destination), isolate(destName), destCity)
                    val walkSub = if (state.spec.trigger == TriggerType.LEAVE_FOR_STOP) {
                        val plan = (if (state.isActive) LeavePlanHolder.planFor(state.spec) else null) ?: state.leavePlan ?: state.spec.leave
                        plan?.let {
                            val how = when {
                                it.manual -> stringResource(Res.string.walk_in_use_manual)
                                it.walkDistanceMeters != null && it.walkRouted -> stringResource(Res.string.walk_in_use_routed, distanceText(it.walkDistanceMeters))
                                it.walkDistanceMeters != null -> stringResource(Res.string.walk_in_use_auto, distanceText(it.walkDistanceMeters))
                                else -> stringResource(Res.string.walk_in_use_default)
                            }
                            stringResource(Res.string.walk_in_use, it.walkMinutes, it.bufferMinutes, how)
                        }
                    } else null
                    LabeledRow(stringResource(Res.string.label_trigger), triggerRowText(state.spec), walkSub, valueStyle = MaterialTheme.typography.titleMedium)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    // same short line as the collapsed ongoing notification (next bus / leave time)
                    if (state.isActive) {
                        il.kavmonitor.shared.notify.NotificationTexts.statusLine(state.spec, state, now)?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    val err = state.lastError
                    val phase = when (state.phase) {
                        WatchPhase.RUNNING -> stringResource(Res.string.watch_status_running, state.endsAt.israelClock())
                        WatchPhase.TIMED_OUT -> stringResource(Res.string.watch_status_timed_out)
                        WatchPhase.STOPPED -> stringResource(Res.string.watch_status_stopped)
                    }
                    val updated = if (err == null || !state.isActive) {
                        state.lastSuccessAt?.let { stringResource(Res.string.watch_last_update, it.israelClock()) }
                            ?: stringResource(Res.string.watch_no_update_yet)
                    } else null
                    Text(listOfNotNull(phase, updated).joinToString(" · "))
                    Text(stringResource(Res.string.watch_buses_seen, state.busesSeen) + " · " + stringResource(Res.string.watch_alerts, state.alerts.size))
                    state.fallback?.let { fb ->
                        Text(fallbackText(state.spec.trigger, fb), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (err != null && state.isActive) {
                        Text(errorText(err), color = MaterialTheme.colorScheme.error)
                        Text(
                            state.lastSuccessAt?.let { stringResource(Res.string.watch_retrying_since, it.israelClock()) }
                                ?: stringResource(Res.string.watch_retrying_none),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            if (state.isActive) {
                Button(
                    onClick = { c.stopWatch() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(Res.string.watch_stop)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.startWatch(state.spec) }, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.restart_watch)) }
                    OutlinedButton(onClick = { c.openNewWatch() }, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.new_watch_again)) }
                }
            }
        }
        if (state.isActive) item(key = "watch-edit") { WatchEditRow(c, state) }
        val followed = c.followed?.takeIf { it.lineRef == state.spec.lineRef }
        // Followed bus: refreshed by this screen's route view, i.e. only while the screen is visible and the watch runs.
        if (followed != null && stops != null && state.isActive) {
            item(key = "watch-follow") {
                val preferred = state.spec.stop.takeIf { state.spec.trigger != TriggerType.DEPARTS_ORIGIN }
                val snap = c.routeLive.state?.takeIf { it.request.lineRef == state.spec.lineRef }?.snapshot
                c.followInfo(snap, stops.stops, preferred, now)?.let { FollowCard(followed, it, now, onUnfollow = c::unfollow) }
            }
        }
        item { Text(stringResource(Res.string.watch_buses_header, isolate(state.spec.stop.name)), style = MaterialTheme.typography.titleMedium) }
        if (state.buses.isEmpty() && state.lastSuccessAt != null) {
            item { Text(stringResource(Res.string.watch_no_buses), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.buses, key = { "watch-" + it.key }) { bus ->
            val d = bus.toDetails(state.spec, stops?.stops, observed[bus.key])
            val ref = bus.toBusRef(state.spec.lineRef, state.spec.lineName, d.fraction)
            BusDetailsCard(
                d, now,
                onLongPress = { c.openBusActions(ref) }.takeIf { state.isActive },
                followed = followed != null && il.kavmonitor.shared.follow.FollowBus.sameBus(followed, ref),
            )
        }
        if (state.alerts.isNotEmpty()) {
            item { Text(stringResource(Res.string.watch_alerts_header), style = MaterialTheme.typography.titleMedium) }
            items(state.alerts.reversed(), key = { "alert_" + it.key }) { a ->
                Text(
                    stringResource(
                        Res.string.watch_alert_item, a.at.israelClock(withSeconds = true),
                        a.vehicleRef ?: stringResource(Res.string.unknown), reasonText(a.reason),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // Nearest stop to the user (foreground location, only while the watch runs and this item is on screen).
        if (stops != null && state.isActive) {
            item(key = "watch-nearest") {
                NearestStopSection(c.location, stops.stops, now, watchedStop = state.spec.stop, isBroken = c::isStopBroken,
                    walk = c::autoWalk, requestWalk = c::requestWalk)
            }
        }
        item { HorizontalDivider() }
        if (!state.isActive) {
            // no live route view for a finished watch
        } else if (stops == null) {
            item { Text(stringResource(Res.string.route_live_need_stops), style = MaterialTheme.typography.bodySmall) }
        } else {
            routeLiveItems(
                c.routeLive.state?.takeIf { it.request.lineRef == state.spec.lineRef }, now, state.spec.stop, keyPrefix = "w", observedDepartures = observed,
                onOpenMap = {
                    c.openMap(RouteLiveRequest(state.spec.lineRef, state.spec.lineName, state.spec.operatorRef, stops.stops, state.spec.stop.code), state.spec.lineName)
                },
                onBusLongPress = { c.openBusActions(it.toBusRef(state.spec.lineRef, state.spec.lineName)) },
                followedKey = { b -> followed != null && il.kavmonitor.shared.follow.FollowBus.sameBus(followed, b.toBusRef(state.spec.lineRef, state.spec.lineName)) },
            )
        }
    }
}

/**
 * Departures from the origin that the app actually observed, by bus key: the departs-origin watch's own detections
 * (trip dropped off the origin list / GPS away from it; not the "scheduled time reached" fallback) and the live
 * route view's origin sample.
 */
internal fun observedDepartures(state: WatchState?, snap: il.kavmonitor.shared.route.RouteLiveSnapshot?): Map<String, kotlin.time.Instant> {
    val m = mutableMapOf<String, kotlin.time.Instant>()
    snap?.buses?.forEach { b -> b.departedObservedAt?.let { m[b.key] = it } }
    if (state?.spec?.trigger == TriggerType.DEPARTS_ORIGIN) {
        state.alerts.filter { it.reason == TriggerReason.LEFT_ORIGIN_DROPPED || it.reason == TriggerReason.LEFT_ORIGIN_GPS }
            .forEach { m[it.key] = it.at }
    }
    return m
}

@Composable
internal fun fallbackText(trigger: TriggerType, fb: il.kavmonitor.shared.watch.FallbackPlan): String {
    val up = fb.upstream
    val down = fb.downstream
    return when {
        trigger == TriggerType.DEPARTS_ORIGIN && down != null -> stringResource(Res.string.fallback_departs, isolate(down.name))
        up != null && down != null -> stringResource(Res.string.fallback_two, isolate(up.name), isolate(down.name))
        else -> stringResource(Res.string.fallback_one, isolate((up ?: down)!!.name))
    }
}

/** Edit the running watch in place: extend it (+15/+30/+60 min) and, for time-to-leave, change the walk minutes. */
@Composable
private fun WatchEditRow(c: AppController, state: WatchState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (il.kavmonitor.shared.watch.WatchEdits.canExtend(state.spec)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.watch_extend), style = MaterialTheme.typography.bodyMedium)
                il.kavmonitor.shared.watch.WatchEdits.EXTEND_CHOICES.forEach { m ->
                    OutlinedButton(onClick = { c.extendWatch(m) }) { Text(stringResource(Res.string.watch_extend_by, m)) }
                }
            }
        }
        if (state.spec.trigger == TriggerType.LEAVE_FOR_STOP) {
            val plan = LeavePlanHolder.planFor(state.spec) ?: state.leavePlan
            if (plan != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.watch_walk_edit), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { c.setWatchWalkMinutes(state.spec, plan.walkMinutes - 1) }, enabled = plan.walkMinutes > 0) { Text("−") }
                    Text(stringResource(Res.string.walk_minutes, plan.walkMinutes), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = { c.setWatchWalkMinutes(state.spec, plan.walkMinutes + 1) },
                        enabled = plan.walkMinutes < il.kavmonitor.shared.trigger.TriggerConfig.MAX_WALK_MINUTES,
                    ) { Text("+") }
                }
            }
        }
    }
}
