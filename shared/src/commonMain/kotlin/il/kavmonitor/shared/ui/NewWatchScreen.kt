package il.kavmonitor.shared.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.model.Direction
import il.kavmonitor.shared.model.Operators
import il.kavmonitor.shared.model.RouteEnd
import il.kavmonitor.shared.model.StopList
import il.kavmonitor.shared.model.StopListSource
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.route_rows_title
import il.kavmonitor.shared.resources.route_rows_to_stop
import il.kavmonitor.shared.resources.choose_direction
import il.kavmonitor.shared.resources.choose_stop
import il.kavmonitor.shared.resources.choose_stop_hint
import il.kavmonitor.shared.resources.dir_alternative
import il.kavmonitor.shared.resources.dir_destination
import il.kavmonitor.shared.resources.dir_origin
import il.kavmonitor.shared.resources.dir_stops
import il.kavmonitor.shared.resources.dir_stops_count
import il.kavmonitor.shared.resources.dir_stops_loading
import il.kavmonitor.shared.resources.duration_minutes
import il.kavmonitor.shared.resources.duration_title
import il.kavmonitor.shared.resources.line_label
import il.kavmonitor.shared.resources.empty_line_body
import il.kavmonitor.shared.resources.new_watch_title
import il.kavmonitor.shared.resources.next_ride
import il.kavmonitor.shared.resources.next_ride_time
import il.kavmonitor.shared.resources.no_directions
import il.kavmonitor.shared.resources.no_service_now
import il.kavmonitor.shared.resources.schedule_from
import il.kavmonitor.shared.resources.schedule_not_published
import il.kavmonitor.shared.resources.retry
import il.kavmonitor.shared.resources.setup_cancel
import il.kavmonitor.shared.resources.start_watch
import il.kavmonitor.shared.resources.stop_code
import il.kavmonitor.shared.resources.stop_item
import il.kavmonitor.shared.resources.stops_source_previous
import il.kavmonitor.shared.resources.stops_source_siri
import il.kavmonitor.shared.resources.trigger_arrives_at_stop
import il.kavmonitor.shared.resources.trigger_departs_origin
import il.kavmonitor.shared.resources.trigger_title
import il.kavmonitor.shared.resources.unknown
import il.kavmonitor.shared.map.mapBuses
import il.kavmonitor.shared.route.RouteLiveRequest
import il.kavmonitor.shared.resources.trigger_leave
import il.kavmonitor.shared.resources.choose_boarding_stop
import il.kavmonitor.shared.resources.walk_title
import il.kavmonitor.shared.resources.favorite_add
import il.kavmonitor.shared.resources.favorite_remove
import il.kavmonitor.shared.resources.walk_routed_source
import il.kavmonitor.shared.resources.walk_routed
import il.kavmonitor.shared.resources.walk_manual
import il.kavmonitor.shared.resources.walk_default
import il.kavmonitor.shared.resources.walk_minutes_plain
import il.kavmonitor.shared.resources.walk_reset
import il.kavmonitor.shared.resources.walk_summary
import il.kavmonitor.shared.resources.walk_minutes
import il.kavmonitor.shared.resources.walk_need_stop
import il.kavmonitor.shared.trigger.TriggerConfig
import androidx.compose.material3.OutlinedButton
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Clock

private const val DURATION_MIN = 10
private const val DURATION_STEP = 10

@Composable
fun NewWatchScreen(c: AppController) {
    val now by produceState(c.clock.now()) { while (true) { delay(1000); value = c.clock.now() } }
    val favorites by c.favorites.items.collectAsState()
    val selected = c.selectedDirection
    val readyStops = (c.stops as? Loadable.Ready)?.value

    // Time to leave needs the location for the walk time: keep it updating while this screen shows that trigger.
    if (selected != null && readyStops != null && c.trigger == TriggerType.LEAVE_FOR_STOP) {
        DisposableEffect(Unit) {
            val token = c.location.attach()
            onDispose { c.location.detach(token) }
        }
        LaunchedEffect(c.location.fix, readyStops) { c.defaultBoardingStop() }
    }

    // Live route view: refreshes only while this screen shows a direction (detached on leave / direction change).
    if (selected != null && readyStops != null) {
        // v0.5: the route view fetches every working stop in one request; the target only selects which ETA to show
        val target = if (c.trigger != TriggerType.DEPARTS_ORIGIN) c.targetStopCode else null
        DisposableEffect(selected.lineRef, readyStops, target) {
            val token = c.routeLive.attach(
                RouteLiveRequest(selected.lineRef, selected.routeShortName, selected.operatorRef, readyStops.stops, target),
            )
            onDispose { c.routeLive.detach(token) }
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var setupOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val readyList = readyStops?.stops
    if (setupOpen && readyList != null) {
        WatchSetupDialog(c, readyList) { setupOpen = false }
    }
    ScreenFrame(
        title = stringResource(Res.string.new_watch_title),
        showTitleBar = selected == null,
        bottomBar = {
            if (selected != null && readyList != null && !setupOpen) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Button(
                        onClick = { setupOpen = true },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("watch-setup-open"),
                    ) {
                        Text(stringResource(Res.string.start_watch))
                    }
                }
            }
        },
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().consumeWindowInsets(inner),
            contentPadding = inner.withPagePadding(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selected == null) {
                item {
                    LineSearchField(
                        value = c.lineInput,
                        onValueChange = { c.lineInput = it },
                        onSearch = { c.searchLine() },
                    )
                }
                when (val d = c.directions) {
                    Loadable.Idle -> {}
                    Loadable.Loading -> item { Loading() }
                    is Loadable.Failed -> item { ErrorRow(d.message) { c.searchLine() } }
                    is Loadable.Ready -> {
                        if (d.value.isEmpty()) {
                            item {
                                EmptyState(
                                    Icons.Filled.Search,
                                    stringResource(Res.string.no_directions, c.lineInput),
                                    stringResource(Res.string.empty_line_body),
                                )
                            }
                        } else {
                            item { Text(stringResource(Res.string.choose_direction), style = MaterialTheme.typography.titleMedium) }
                            items(d.value, key = { it.lineRef }) { dir ->
                                // Composed only when visible: prefetches the stop list (count + real end names) lazily.
                                LaunchedEffect(dir.lineRef) { c.ensureStops(dir) }
                                DirectionCard(
                                    dir, c.directionStops[dir.lineRef],
                                    favorite = favorites.any { it.lineRef == dir.lineRef && it.operatorRef == dir.operatorRef },
                                    onToggleFavorite = { c.toggleFavorite(dir) },
                                ) { c.selectDirection(dir) }
                            }
                        }
                    }
                }
            } else {
                item(key = "selected") {
                            DirectionCard(
                                selected, c.directionStops[selected.lineRef] ?: c.stops,
                                favorite = favorites.any { it.lineRef == selected.lineRef && it.operatorRef == selected.operatorRef },
                                onToggleFavorite = { c.toggleFavorite(selected) },
                                onClick = null,
                            )
                        }
                        item(key = "nw-map") {
                            val list = (c.stops as? Loadable.Ready)?.value?.takeIf { it.lineRef == selected.lineRef }
                            val live = c.routeLive.state?.takeIf { it.request.lineRef == selected.lineRef }?.snapshot
                            val highlight = if (c.trigger != TriggerType.DEPARTS_ORIGIN) c.targetStopCode else null
                            CompactRouteMap(
                                stops = list?.stops,
                                highlightCode = highlight,
                                userLat = c.location.fix?.lat,
                                userLon = c.location.fix?.lon,
                                buses = live?.mapBuses().orEmpty(),
                                onOpen = {
                                    if (list != null) c.openMap(
                                        RouteLiveRequest(selected.lineRef, selected.routeShortName, selected.operatorRef, list.stops, highlight),
                                        selected.routeShortName,
                                    )
                                },
                            )
                        }
                        val liveForLine = c.routeLive.state?.takeIf { it.request.lineRef == selected.lineRef }?.snapshot
                        val noActive = !selected.runsToday || (liveForLine != null && liveForLine.active.isEmpty())
                        if (noActive) {
                            item(key = "no-active") { NoActiveNotice(selected, c.nextDeparture) }
                        }
                when (val s = c.stops) {
                    Loadable.Idle -> {}
                    Loadable.Loading -> item { Loading() }
                    is Loadable.Failed -> item { ErrorRow(s.message) { c.selectDirection(selected) } }
                    is Loadable.Ready -> {
                        val list = s.value
                        when (list.source) {
                            StopListSource.GTFS_PREVIOUS_DAY -> item {
                                Text(stringResource(Res.string.stops_source_previous, shortDate(list.sourceDate)), style = MaterialTheme.typography.bodySmall)
                            }
                            StopListSource.SIRI_HISTORY -> item {
                                Text(stringResource(Res.string.stops_source_siri), style = MaterialTheme.typography.bodySmall)
                            }
                            StopListSource.GTFS_TODAY -> {}
                        }
                        item(key = "nearest") {
                            NearestStopSection(
                                c.location, list.stops, now,
                                isSelected = { n ->
                                    if (c.trigger == TriggerType.LEAVE_FOR_STOP) c.targetStopCode == n.stop.code
                                    else if (n.index == 0) c.trigger == TriggerType.DEPARTS_ORIGIN
                                    else c.trigger == TriggerType.ARRIVES_AT_STOP && c.targetStopCode == n.stop.code
                                },
                                onUse = { c.useNearestStop(it) },
                                isBroken = c::isStopBroken,
                                walk = c::autoWalk, requestWalk = c::requestWalk,
                            )
                        }
                        val target = if (c.trigger != TriggerType.DEPARTS_ORIGIN) list.stops.firstOrNull { it.code == c.targetStopCode } else null
                        val followed = c.followed?.takeIf { it.lineRef == selected.lineRef }
                        val liveState = c.routeLive.state?.takeIf { it.request.lineRef == selected.lineRef }
                        if (followed != null) {
                            item(key = "nw-follow") {
                                c.followInfo(liveState?.snapshot, list.stops, target, now)?.let { FollowCard(followed, it, now, onUnfollow = c::unfollow) }
                            }
                        }
                        val snap = liveState?.snapshot
                        // a snapshot kept from the previous request may be for another target: no ETA then (never mislabel)
                        val etaStop = target?.takeIf { snap != null && it.code == snap.targetStopCode }
                        val targetIndex = etaStop?.let { t -> list.stops.indexOfFirst { it.code == t.code } }?.takeIf { it >= 0 }
                        val refs = snap?.buses.orEmpty().associate { it.key to it.toBusRef(selected.lineRef, selected.routeShortName) }
                        val isFollowed = { k: String -> followed != null && refs[k]?.let { il.kavmonitor.shared.follow.FollowBus.sameBus(followed, it) } == true }
                        val rows = snap?.active.orEmpty().map { b ->
                            val d = b.toDetails(etaStop, list.stops.firstOrNull()?.code)
                            if (d.eta != null || targetIndex == null) d
                            else LineModels.etaAtStop(b, list.stops, targetIndex)?.let { (e, est) -> d.copy(eta = e, etaEstimated = est) } ?: d
                        }.let { r -> if (targetIndex != null) r.sortedWith(compareBy(nullsLast()) { it.eta }) else r.sortedByDescending { it.fraction ?: -1.0 } }
                        liveLineItems(
                            keyPrefix = "nw", state = liveState, stops = list.stops, userIndex = targetIndex, now = now,
                            rows = rows,
                            rowsTitle = { etaStop?.let { stringResource(Res.string.route_rows_to_stop, isolate(it.name)) } ?: stringResource(Res.string.route_rows_title) },
                            noRowsText = null,
                            expanded = expanded, onExpand = { expanded = it }, listState = listState, scope = scope,
                            onLongPress = { k -> refs[k]?.let { c.openBusActions(it) } },
                            isFollowed = isFollowed,
                            showRows = snap != null,
                        )
                        item { Row(Modifier.padding(bottom = 8.dp)) {} }
                    }
                }
            }
        }
    }
}

/** When to alert, for how long, and which stop. Opens from התחל מעקב and submits from here. */
@Composable
private fun WatchSetupDialog(c: AppController, stops: List<il.kavmonitor.shared.model.RouteStop>, onDismiss: () -> Unit) {
    val leave = c.trigger == TriggerType.LEAVE_FOR_STOP
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { c.startNewWatch() },
                enabled = c.canStart(),
                shape = CircleShape,
                modifier = Modifier.testTag("watch-setup-confirm"),
            ) { Text(stringResource(Res.string.start_watch)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.setup_cancel)) }
        },
        title = { Text(stringResource(Res.string.trigger_title)) },
        text = {
            LazyColumn(
                Modifier.height(420.dp).testTag("watch-setup-list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (c.trigger != TriggerType.DEPARTS_ORIGIN && c.targetStopCode == null) {
                    item { Text(stringResource(Res.string.choose_stop_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                item {
                    RadioRow(
                        c.trigger == TriggerType.DEPARTS_ORIGIN,
                        stringResource(Res.string.trigger_departs_origin) + " (" + isolate(stops.firstOrNull()?.name ?: "") + ")",
                    ) { c.trigger = TriggerType.DEPARTS_ORIGIN }
                }
                item {
                    RadioRow(c.trigger == TriggerType.ARRIVES_AT_STOP, stringResource(Res.string.trigger_arrives_at_stop)) {
                        c.trigger = TriggerType.ARRIVES_AT_STOP
                    }
                }
                item {
                    RadioRow(c.trigger == TriggerType.LEAVE_FOR_STOP, stringResource(Res.string.trigger_leave)) { c.selectLeaveTrigger() }
                }
                if (leave) {
                    item { WalkPanel(c, stops.firstOrNull { it.code == c.targetStopCode }) }
                }
                item { DurationPicker(c) }
                if (c.trigger != TriggerType.DEPARTS_ORIGIN) {
                    item {
                        Text(
                            stringResource(if (leave) Res.string.choose_boarding_stop else Res.string.choose_stop),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(if (leave) stops else stops.drop(1), key = { "setup_" + it.sequence + "_" + it.code }) { stop ->
                        RadioRow(
                            selected = c.targetStopCode == stop.code,
                            text = stringResource(Res.string.stop_item, stop.sequence, stop.name),
                            sub = listOfNotNull(stop.city, stringResource(Res.string.stop_code, stop.code)).joinToString(" · "),
                            estimated = c.isStopBroken(stop.code),
                        ) { c.targetStopCode = stop.code }
                    }
                }
            }
        },
    )
}

/**
 * Direction card with labeled rows. Ends come from the real stop list when it is loaded (first/last stop + city),
 * otherwise from the parsed GTFS long name; the stop count fills in when the list arrives.
 */
@Composable
private fun DirectionCard(
    d: Direction,
    stops: Loadable<StopList>?,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: (() -> Unit)?,
) {
    val list = (stops as? Loadable.Ready)?.value?.stops?.takeIf { it.size >= 2 }
    val parsed = d.parsedName
    val origin = list?.first()?.let { RouteEnd(it.name, it.city) } ?: parsed.origin
    val dest = list?.last()?.let { RouteEnd(it.name, it.city) } ?: parsed.destination
    val open = onClick == null
    val colors = if (open) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val body: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.line_label, d.routeShortName), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(if (favorite) Res.string.favorite_remove else Res.string.favorite_add),
                        tint = if (favorite) MaterialTheme.colorScheme.primary else muted,
                    )
                }
                if (d.routeAlternative.isNotBlank() && d.routeAlternative != "#" && d.routeAlternative != "0") {
                    Text(stringResource(Res.string.dir_alternative, d.routeAlternative), style = MaterialTheme.typography.labelMedium, color = muted)
                }
                Surface(
                    color = if (open) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        Operators.displayName(d.operatorRef, d.agencyName),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    origin.stop.ifBlank { stringResource(Res.string.unknown) },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
                Text(
                    dest.stop.ifBlank { stringResource(Res.string.unknown) },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val places = listOfNotNull(origin.city?.takeIf { it.isNotBlank() }, dest.city?.takeIf { it.isNotBlank() })
            if (places.isNotEmpty()) {
                Text(places.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            when (stops) {
                is Loadable.Ready -> Text(stringResource(Res.string.dir_stops_count, stops.value.stops.size), style = MaterialTheme.typography.bodyMedium, color = muted)
                is Loadable.Failed -> Text(stringResource(Res.string.unknown), style = MaterialTheme.typography.bodyMedium, color = muted)
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(stringResource(Res.string.dir_stops_loading), style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
            if (onClick != null && !d.runsToday) {
                Text(noServiceLine(d), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    val shape = MaterialTheme.shapes.large
    if (onClick != null) Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = shape, colors = colors, content = { body() })
    else Card(modifier = Modifier.fillMaxWidth(), shape = shape, colors = colors, content = { body() })
}

@Composable
private fun noServiceLine(d: il.kavmonitor.shared.model.Direction): String {
    val none = stringResource(Res.string.no_service_now)
    val next = d.nextServiceDate?.let { stringResource(Res.string.next_ride, shortDate(it)) }
    return listOfNotNull(none, next).joinToString(" · ")
}

@Composable
private fun NoActiveNotice(d: il.kavmonitor.shared.model.Direction, next: Loadable<il.kavmonitor.shared.data.NextDeparture?>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.no_service_now), style = MaterialTheme.typography.titleMedium)
            val dep = (next as? Loadable.Ready)?.value
            val line = when {
                dep?.time != null -> stringResource(Res.string.next_ride_time, shortDate(dep.date), dep.time)
                dep != null -> stringResource(Res.string.next_ride, shortDate(dep.date))
                next is Loadable.Ready -> stringResource(Res.string.schedule_not_published)
                else -> null
            }
            if (line != null) Text(line, style = MaterialTheme.typography.bodyLarge)
            if (!d.runsToday) {
                Text(stringResource(Res.string.schedule_from, shortDate(d.date)), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** "מוצא / יעד / התראה" rows: a fixed-width label column and the value (direction cards, watch card, recents). */
@Composable
internal fun LabeledRow(
    label: String, value: String, sub: String?,
    valueStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(value, style = valueStyle)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Time to leave: walk time (computed from the location, or set by the user) + buffer. */
@Composable
private fun WalkPanel(c: AppController, stop: il.kavmonitor.shared.model.RouteStop?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.walk_title), style = MaterialTheme.typography.titleMedium)
            if (stop == null) {
                Text(stringResource(Res.string.walk_need_stop), style = MaterialTheme.typography.bodySmall, color = muted)
                return@Column
            }
            // one walking-route request per selected stop / ~50 m of movement (cached), straight line until it answers
            LaunchedEffect(stop.code, c.location.fix?.let { il.kavmonitor.shared.walk.WalkPlanner.key(it.lat, it.lon, stop.code) }) { c.requestWalk(stop) }
            val plan = c.leavePlan(stop)
            val auto = c.autoWalk(stop)
            Text(
                when {
                    plan.manual -> stringResource(Res.string.walk_manual, plan.walkMinutes)
                    plan.walkDistanceMeters != null && plan.walkRouted ->
                        stringResource(Res.string.walk_routed, metersText(plan.walkDistanceMeters), plan.walkMinutes)
                    plan.walkDistanceMeters != null -> stringResource(Res.string.walk_minutes_plain, plan.walkMinutes)
                    else -> stringResource(Res.string.walk_default, plan.walkMinutes)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!plan.manual && plan.walkRouted) {
                Text(stringResource(Res.string.walk_routed_source, c.walkPlanner.routerName), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { c.changeWalkMinutes(-1, stop) }, enabled = plan.walkMinutes > 0, shape = CircleShape) { Text("−") }
                Text(stringResource(Res.string.walk_minutes, plan.walkMinutes), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { c.changeWalkMinutes(+1, stop) }, enabled = plan.walkMinutes < TriggerConfig.MAX_WALK_MINUTES, shape = CircleShape) { Text("+") }
                if (plan.manual && auto != null) TextButton(onClick = { c.walkOverrideMinutes = null }) { Text(stringResource(Res.string.walk_reset)) }
            }
            Text(stringResource(Res.string.walk_summary, plan.bufferMinutes, plan.leadMinutes), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun DurationPicker(c: AppController) {
    val max = TriggerConfig.MAX_DURATION_MINUTES
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(Res.string.duration_title) + " · " + stringResource(Res.string.duration_minutes, c.durationMinutes),
            style = MaterialTheme.typography.titleMedium,
        )
        Slider(
            value = c.durationMinutes.coerceIn(DURATION_MIN, max).toFloat(),
            onValueChange = { raw ->
                c.durationMinutes = ((raw / DURATION_STEP).roundToInt() * DURATION_STEP).coerceIn(DURATION_MIN, max)
            },
            valueRange = DURATION_MIN.toFloat()..max.toFloat(),
            steps = (max - DURATION_MIN) / DURATION_STEP - 1,
        )
    }
}

@Composable
private fun RadioRow(selected: Boolean, text: String, sub: String? = null, estimated: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text, modifier = Modifier.weight(1f, fill = false))
                if (estimated) EstimatedBadge()
            }
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun Loading() {
    LoadingSkeleton(lines = 4, modifier = Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    ErrorNotice(message, onRetry)
}
