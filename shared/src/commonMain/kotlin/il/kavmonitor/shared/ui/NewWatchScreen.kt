package il.kavmonitor.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.model.Direction
import il.kavmonitor.shared.model.Operators
import il.kavmonitor.shared.model.RouteEnd
import il.kavmonitor.shared.model.StopList
import il.kavmonitor.shared.model.StopListSource
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.back
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
import il.kavmonitor.shared.resources.error_prefix
import il.kavmonitor.shared.resources.line_label
import il.kavmonitor.shared.resources.line_number_hint
import il.kavmonitor.shared.resources.new_watch_title
import il.kavmonitor.shared.resources.no_directions
import il.kavmonitor.shared.resources.retry
import il.kavmonitor.shared.resources.search
import il.kavmonitor.shared.resources.start_watch
import il.kavmonitor.shared.resources.stop_code
import il.kavmonitor.shared.resources.stop_item
import il.kavmonitor.shared.resources.stops_source_previous
import il.kavmonitor.shared.resources.stops_source_siri
import il.kavmonitor.shared.resources.trigger_arrives_at_stop
import il.kavmonitor.shared.resources.trigger_departs_origin
import il.kavmonitor.shared.resources.trigger_title
import il.kavmonitor.shared.resources.unknown
import il.kavmonitor.shared.route.RouteLiveRequest
import il.kavmonitor.shared.resources.trigger_leave
import il.kavmonitor.shared.resources.choose_boarding_stop
import il.kavmonitor.shared.resources.walk_title
import il.kavmonitor.shared.resources.walk_auto
import il.kavmonitor.shared.resources.walk_manual
import il.kavmonitor.shared.resources.walk_default
import il.kavmonitor.shared.resources.walk_reset
import il.kavmonitor.shared.resources.walk_summary
import il.kavmonitor.shared.resources.walk_minutes
import il.kavmonitor.shared.resources.walk_need_stop
import il.kavmonitor.shared.trigger.TriggerConfig
import androidx.compose.material3.OutlinedButton
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private val DURATIONS = listOf(15, 30, 45, 60, 90, 120)

@Composable
fun NewWatchScreen(c: AppController) {
    val now by produceState(Clock.System.now()) { while (true) { delay(1000); value = Clock.System.now() } }
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
        val target = if (c.trigger != TriggerType.DEPARTS_ORIGIN) c.targetStopCode else null
        DisposableEffect(selected.lineRef, readyStops, target) {
            val token = c.routeLive.attach(
                RouteLiveRequest(selected.lineRef, selected.routeShortName, selected.operatorRef, readyStops.stops, target),
            )
            onDispose { c.routeLive.detach(token) }
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        LazyColumn(
            Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { c.back() }) { Text("→ " + stringResource(Res.string.back)) }
                    Text(stringResource(Res.string.new_watch_title), style = MaterialTheme.typography.headlineSmall)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = c.lineInput,
                        onValueChange = { c.lineInput = it.filter { ch -> ch.isLetterOrDigit() }.take(6) },
                        label = { Text(stringResource(Res.string.line_number_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { c.searchLine() }),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { c.searchLine() }, enabled = c.lineInput.isNotBlank()) { Text(stringResource(Res.string.search)) }
                }
            }

            if (selected == null) {
                when (val d = c.directions) {
                    Loadable.Idle -> {}
                    Loadable.Loading -> item { Loading() }
                    is Loadable.Failed -> item { ErrorRow(d.message) { c.searchLine() } }
                    is Loadable.Ready -> {
                        if (d.value.isEmpty()) {
                            item { Text(stringResource(Res.string.no_directions, c.lineInput)) }
                        } else {
                            item { Text(stringResource(Res.string.choose_direction), style = MaterialTheme.typography.titleMedium) }
                            items(d.value, key = { it.lineRef }) { dir ->
                                // Composed only when visible: prefetches the stop list (count + real end names) lazily.
                                LaunchedEffect(dir.lineRef) { c.ensureStops(dir) }
                                DirectionCard(dir, c.directionStops[dir.lineRef]) { c.selectDirection(dir) }
                            }
                        }
                    }
                }
            } else {
                item(key = "selected") { DirectionCard(selected, c.directionStops[selected.lineRef] ?: c.stops, onClick = null) }
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
                            )
                        }
                        val target = if (c.trigger != TriggerType.DEPARTS_ORIGIN) list.stops.firstOrNull { it.code == c.targetStopCode } else null
                        routeLiveItems(c.routeLive.state?.takeIf { it.request.lineRef == selected.lineRef }, now, target, keyPrefix = "nw")
                        item { HorizontalDivider() }
                        item { Text(stringResource(Res.string.trigger_title), style = MaterialTheme.typography.titleMedium) }
                        item {
                            RadioRow(c.trigger == TriggerType.DEPARTS_ORIGIN, stringResource(Res.string.trigger_departs_origin) +
                                " (" + isolate(list.stops.firstOrNull()?.name ?: "") + ")") { c.trigger = TriggerType.DEPARTS_ORIGIN }
                        }
                        item {
                            RadioRow(c.trigger == TriggerType.ARRIVES_AT_STOP, stringResource(Res.string.trigger_arrives_at_stop)) {
                                c.trigger = TriggerType.ARRIVES_AT_STOP
                            }
                        }
                        item {
                            RadioRow(c.trigger == TriggerType.LEAVE_FOR_STOP, stringResource(Res.string.trigger_leave)) { c.selectLeaveTrigger() }
                        }
                        if (c.trigger == TriggerType.LEAVE_FOR_STOP) {
                            item(key = "walk") { WalkPanel(c, list.stops.firstOrNull { it.code == c.targetStopCode }) }
                        }
                        item { DurationPicker(c) }
                        if (c.trigger != TriggerType.DEPARTS_ORIGIN) {
                            val leave = c.trigger == TriggerType.LEAVE_FOR_STOP
                            item { Text(stringResource(if (leave) Res.string.choose_boarding_stop else Res.string.choose_stop), style = MaterialTheme.typography.titleMedium) }
                            items(if (leave) list.stops else list.stops.drop(1), key = { "stop_" + it.sequence + "_" + it.code }) { stop ->
                                RadioRow(
                                    selected = c.targetStopCode == stop.code,
                                    text = stringResource(Res.string.stop_item, stop.sequence, stop.name),
                                    sub = listOfNotNull(stop.city, stringResource(Res.string.stop_code, stop.code)).joinToString(" · "),
                                    estimated = c.isStopBroken(stop.code),
                                ) { c.targetStopCode = stop.code }
                            }
                        }
                        item { Row(Modifier.padding(bottom = 8.dp)) {} }
                    }
                }
            }
        }
        // Sticky bottom bar: Start is always reachable, also with a long stop list.
        if (selected != null && readyStops != null) {
            Surface(tonalElevation = 3.dp, shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (c.trigger != TriggerType.DEPARTS_ORIGIN && c.targetStopCode == null) {
                        Text(stringResource(Res.string.choose_stop_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { c.startNewWatch() }, enabled = c.canStart(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.start_watch))
                    }
                }
            }
        }
    }
}

/**
 * Direction card with labeled rows. Ends come from the real stop list when it is loaded (first/last stop + city),
 * otherwise from the parsed GTFS long name; the stop count fills in when the list arrives.
 */
@Composable
private fun DirectionCard(d: Direction, stops: Loadable<StopList>?, onClick: (() -> Unit)?) {
    val mod = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }
    val list = (stops as? Loadable.Ready)?.value?.stops?.takeIf { it.size >= 2 }
    val parsed = d.parsedName
    val origin = list?.first()?.let { RouteEnd(it.name, it.city) } ?: parsed.origin
    val dest = list?.last()?.let { RouteEnd(it.name, it.city) } ?: parsed.destination
    Card(mod, colors = if (onClick == null) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.line_label, d.routeShortName), style = MaterialTheme.typography.titleLarge)
                Text("  ·  " + Operators.displayName(d.operatorRef, d.agencyName), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                if (d.routeAlternative.isNotBlank() && d.routeAlternative != "#" && d.routeAlternative != "0") {
                    Text(stringResource(Res.string.dir_alternative, d.routeAlternative), style = MaterialTheme.typography.labelMedium)
                }
            }
            LabeledRow(stringResource(Res.string.dir_origin), origin.stop.ifBlank { stringResource(Res.string.unknown) }, origin.city)
            LabeledRow(stringResource(Res.string.dir_destination), dest.stop.ifBlank { stringResource(Res.string.unknown) }, dest.city)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.dir_stops), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
                when (stops) {
                    is Loadable.Ready -> Text(stringResource(Res.string.dir_stops_count, stops.value.stops.size), style = MaterialTheme.typography.bodyLarge)
                    is Loadable.Failed -> Text(stringResource(Res.string.unknown), style = MaterialTheme.typography.bodyLarge)
                    else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(stringResource(Res.string.dir_stops_loading), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: String, sub: String?) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp).padding(top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(value, style = MaterialTheme.typography.bodyLarge)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Time to leave: walk time (computed from the location, or set by the user) + buffer. */
@Composable
private fun WalkPanel(c: AppController, stop: il.kavmonitor.shared.model.RouteStop?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(Res.string.walk_title), style = MaterialTheme.typography.titleMedium)
            if (stop == null) {
                Text(stringResource(Res.string.walk_need_stop), style = MaterialTheme.typography.bodySmall, color = muted)
                return@Column
            }
            val plan = c.leavePlan(stop)
            val auto = c.autoWalk(stop)
            Text(
                when {
                    plan.manual -> stringResource(Res.string.walk_manual, plan.walkMinutes)
                    plan.walkDistanceMeters != null -> stringResource(
                        Res.string.walk_auto, plan.walkMinutes, distanceText(plan.walkDistanceMeters),
                        TriggerConfig.WALK_DETOUR_FACTOR.toString(), TriggerConfig.WALK_SPEED_M_PER_MIN.toInt(),
                    )
                    else -> stringResource(Res.string.walk_default, plan.walkMinutes)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { c.changeWalkMinutes(-1, stop) }, enabled = plan.walkMinutes > 0) { Text("−") }
                Text(stringResource(Res.string.walk_minutes, plan.walkMinutes), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { c.changeWalkMinutes(+1, stop) }, enabled = plan.walkMinutes < TriggerConfig.MAX_WALK_MINUTES) { Text("+") }
                if (plan.manual && auto != null) TextButton(onClick = { c.walkOverrideMinutes = null }) { Text(stringResource(Res.string.walk_reset)) }
            }
            Text(stringResource(Res.string.walk_summary, plan.bufferMinutes, plan.leadMinutes), style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun DurationPicker(c: AppController) {
    Column {
        Text(stringResource(Res.string.duration_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DURATIONS.forEach { m ->
                FilterChip(
                    selected = c.durationMinutes == m,
                    onClick = { c.durationMinutes = m },
                    label = { Text(stringResource(Res.string.duration_minutes, m)) },
                )
            }
        }
    }
}

@Composable
private fun RadioRow(selected: Boolean, text: String, sub: String? = null, estimated: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
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
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    Column {
        Text(stringResource(Res.string.error_prefix, errorText(message)), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
    }
}
