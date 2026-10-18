package il.kavmonitor.shared.ui

import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.watch.LeavePlanHolder
import il.kavmonitor.shared.resources.walk_in_use
import il.kavmonitor.shared.resources.walk_in_use_manual
import il.kavmonitor.shared.resources.walk_in_use_default

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import il.kavmonitor.shared.resources.watch_route_line
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
import il.kavmonitor.shared.resources.new_watch_again
import il.kavmonitor.shared.resources.restart_watch
import il.kavmonitor.shared.resources.route_live_need_stops
import il.kavmonitor.shared.resources.unknown
import il.kavmonitor.shared.resources.watch_alert_close
import il.kavmonitor.shared.resources.watch_alert_set
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
import il.kavmonitor.shared.map.mapBuses
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
    val liveState = c.routeLive.state?.takeIf { spec != null && it.request.lineRef == spec.lineRef }
    val routeSnap = liveState?.snapshot
    val observed = observedDepartures(state, routeSnap)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var showAlerts by rememberSaveable { mutableStateOf(false) }
    val (destName, destCity) = if (spec != null) destinationParts(spec) else null to null

    ScreenFrame(
        title = if (spec != null) lineTitle(spec) else stringResource(Res.string.watch_title),
        subtitle = spec?.let {
            stringResource(Res.string.watch_route_line, isolate(it.origin.name), isolate(destName + (destCity?.let { city -> ", $city" } ?: "")))
        },
    ) { inner ->
    if (showAlerts && state != null && state.alerts.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showAlerts = false },
            confirmButton = { TextButton(onClick = { showAlerts = false }) { Text(stringResource(Res.string.watch_alert_close)) } },
            title = { Text(stringResource(Res.string.watch_alerts_header)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.alerts.reversed().forEach { a ->
                        Text(
                            stringResource(
                                Res.string.watch_alert_item, a.at.israelClock(withSeconds = true),
                                a.vehicleRef ?: stringResource(Res.string.unknown), reasonText(a.reason),
                            ),
                        )
                    }
                }
            },
        )
    }
    LazyColumn(
        Modifier.fillMaxSize().consumeWindowInsets(inner),
        contentPadding = inner.withPagePadding(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state == null) {
            item { Loading() }
            return@LazyColumn
        }
        val lineRef = state.spec.lineRef
        val lineName = state.spec.lineName
        val rows = WatchRows.build(state, stops?.stops, routeSnap, observed, now)
        // vehicle refs by bus key (follow / actions sheet): the watched stop's view first, else the route view's
        val refs = buildMap {
            routeSnap?.buses?.forEach { put(it.key, it.toBusRef(lineRef, lineName)) }
            state.buses.forEach { v -> put(v.key, v.toBusRef(lineRef, lineName, rows.firstOrNull { it.key == v.key }?.fraction)) }
        }
        val followed = c.followed?.takeIf { it.lineRef == lineRef }
        val isFollowed = { k: String -> followed != null && refs[k]?.let { il.kavmonitor.shared.follow.FollowBus.sameBus(followed, it) } == true }

        item(key = "w-hero") { HeroCard(LineModels.hero(state, rows, now)) }
        item(key = "w-map") {
            CompactRouteMap(
                stops = stops?.stops,
                highlightCode = state.spec.stop.code,
                userLat = c.location.fix?.lat,
                userLon = c.location.fix?.lon,
                buses = routeSnap?.mapBuses().orEmpty(),
                onOpen = {
                    val st = stops ?: return@CompactRouteMap
                    c.openMap(RouteLiveRequest(lineRef, lineName, state.spec.operatorRef, st.stops, state.spec.stop.code), lineName)
                },
            )
        }
        item(key = "w-info") {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val walkSub = if (state.spec.trigger == TriggerType.LEAVE_FOR_STOP) {
                        val plan = (if (state.isActive) LeavePlanHolder.planFor(state.spec) else null) ?: state.leavePlan ?: state.spec.leave
                        plan?.let {
                            val how = when {
                                it.manual -> stringResource(Res.string.walk_in_use_manual)
                                it.walkDistanceMeters != null && it.walkRouted -> stringResource(Res.string.walk_in_use_routed, metersText(it.walkDistanceMeters))
                                else -> stringResource(Res.string.walk_in_use_default)
                            }
                            stringResource(Res.string.walk_in_use, it.walkMinutes, it.bufferMinutes, how)
                        }
                    } else null
                    LabeledRow(stringResource(Res.string.label_trigger), triggerRowText(state.spec), walkSub, valueStyle = MaterialTheme.typography.titleSmall)
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
                    Text(
                        listOfNotNull(phase, updated).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.alerts.isNotEmpty()) {
                        TextButton(onClick = { showAlerts = true }) { Text(stringResource(Res.string.watch_alert_set)) }
                    }
                    Text(stringResource(Res.string.watch_buses_seen, state.busesSeen), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.fallback?.let { fb ->
                        Text(fallbackText(state.spec.trigger, fb), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (err != null && state.isActive) {
                        Text(errorText(err), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            state.lastSuccessAt?.let { stringResource(Res.string.watch_retrying_since, it.israelClock()) }
                                ?: stringResource(Res.string.watch_retrying_none),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item(key = "w-stop") {
            if (state.isActive) {
                Button(
                    onClick = { c.stopWatch() },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(Res.string.watch_stop)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { c.startWatch(state.spec) }, shape = CircleShape, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.restart_watch)) }
                    OutlinedButton(onClick = { c.openNewWatch() }, shape = CircleShape, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.new_watch_again)) }
                }
            }
        }
        if (state.isActive) item(key = "watch-edit") { WatchEditRow(c, state) }
        // Followed bus: refreshed by this screen's route view, i.e. only while the screen is visible and the watch runs.
        if (followed != null && stops != null && state.isActive) {
            item(key = "watch-follow") {
                val preferred = state.spec.stop.takeIf { state.spec.trigger != TriggerType.DEPARTS_ORIGIN }
                c.followInfo(routeSnap, stops.stops, preferred, now)?.let { FollowCard(followed, it, now, onUnfollow = c::unfollow) }
            }
        }
        if (state.isActive && stops == null) {
            item(key = "w-need-stops") { Text(stringResource(Res.string.route_live_need_stops), style = MaterialTheme.typography.bodySmall) }
        }
        val userIndex = stops?.stops?.indexOfFirst { it.code == state.spec.stop.code }?.takeIf { it >= 0 }
        liveLineItems(
            keyPrefix = "w", state = liveState, stops = stops?.stops, userIndex = userIndex, now = now,
            rows = rows,
            rowsTitle = { stringResource(Res.string.watch_buses_header, isolate(state.spec.stop.name)) },
            noRowsText = if (state.lastSuccessAt != null) ({ stringResource(Res.string.watch_no_buses) }) else null,
            expanded = expanded, onExpand = { expanded = it }, listState = listState, scope = scope,
            onLongPress = if (state.isActive) { k -> refs[k]?.let { c.openBusActions(it) } } else null,
            isFollowed = isFollowed,
            // the watch's own ETA for its rows; the diagram chips use the same (incl. the fallback estimate)
            diagramEta = { b -> rows.firstOrNull { it.key == b.key }?.eta?.let { e -> e to rows.first { it.key == b.key }.etaEstimated } },
            showLive = state.isActive && stops != null,
        )
        // Nearest stop to the user (foreground location, only while the watch runs and this item is on screen).
        if (stops != null && state.isActive) {
            item(key = "watch-nearest") {
                NearestStopSection(c.location, stops.stops, now, watchedStop = state.spec.stop, isBroken = c::isStopBroken,
                    walk = c::autoWalk, requestWalk = c::requestWalk)
            }
        }
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
                    OutlinedButton(onClick = { c.extendWatch(m) }, shape = CircleShape) { Text(stringResource(Res.string.watch_extend_by, m)) }
                }
            }
        }
        if (state.spec.trigger == TriggerType.LEAVE_FOR_STOP) {
            val plan = LeavePlanHolder.planFor(state.spec) ?: state.leavePlan
            if (plan != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(Res.string.watch_walk_edit), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { c.setWatchWalkMinutes(state.spec, plan.walkMinutes - 1) }, enabled = plan.walkMinutes > 0, shape = CircleShape) { Text("−") }
                    Text(stringResource(Res.string.walk_minutes, plan.walkMinutes), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = { c.setWatchWalkMinutes(state.spec, plan.walkMinutes + 1) },
                        enabled = plan.walkMinutes < il.kavmonitor.shared.trigger.TriggerConfig.MAX_WALK_MINUTES,
                        shape = CircleShape,
                    ) { Text("+") }
                }
            }
        }
    }
}
