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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.model.Direction
import il.kavmonitor.shared.model.Operators
import il.kavmonitor.shared.model.StopListSource
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.back
import il.kavmonitor.shared.resources.choose_direction
import il.kavmonitor.shared.resources.choose_stop
import il.kavmonitor.shared.resources.direction_route
import il.kavmonitor.shared.resources.duration_minutes
import il.kavmonitor.shared.resources.duration_title
import il.kavmonitor.shared.resources.error_prefix
import il.kavmonitor.shared.resources.line_number_hint
import il.kavmonitor.shared.resources.line_with_operator
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
import org.jetbrains.compose.resources.stringResource

private val DURATIONS = listOf(15, 30, 45, 60, 90, 120)

@Composable
fun NewWatchScreen(c: AppController) {
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
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

        val selected = c.selectedDirection
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
                        items(d.value, key = { it.lineRef }) { dir -> DirectionCard(dir) { c.selectDirection(dir) } }
                    }
                }
            }
        } else {
            item { DirectionCard(selected, onClick = null) }
            when (val s = c.stops) {
                Loadable.Idle -> {}
                Loadable.Loading -> item { Loading() }
                is Loadable.Failed -> item { ErrorRow(s.message) { c.selectDirection(selected) } }
                is Loadable.Ready -> {
                    val list = s.value
                    when (list.source) {
                        StopListSource.GTFS_PREVIOUS_DAY -> item {
                            Text(stringResource(Res.string.stops_source_previous, list.sourceDate), style = MaterialTheme.typography.bodySmall)
                        }
                        StopListSource.SIRI_HISTORY -> item {
                            Text(stringResource(Res.string.stops_source_siri), style = MaterialTheme.typography.bodySmall)
                        }
                        StopListSource.GTFS_TODAY -> {}
                    }
                    item { Text(stringResource(Res.string.trigger_title), style = MaterialTheme.typography.titleMedium) }
                    item {
                        RadioRow(c.trigger == TriggerType.DEPARTS_ORIGIN, stringResource(Res.string.trigger_departs_origin) +
                            " (" + (list.stops.firstOrNull()?.name ?: "") + ")") { c.trigger = TriggerType.DEPARTS_ORIGIN }
                    }
                    item {
                        RadioRow(c.trigger == TriggerType.ARRIVES_AT_STOP, stringResource(Res.string.trigger_arrives_at_stop)) {
                            c.trigger = TriggerType.ARRIVES_AT_STOP
                        }
                    }
                    item { DurationPicker(c) }
                    item {
                        Button(onClick = { c.startNewWatch() }, enabled = c.canStart(), modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(Res.string.start_watch))
                        }
                    }
                    if (c.trigger == TriggerType.ARRIVES_AT_STOP) {
                        item { Text(stringResource(Res.string.choose_stop), style = MaterialTheme.typography.titleMedium) }
                        items(list.stops.drop(1), key = { it.sequence.toString() + "_" + it.code }) { stop ->
                            RadioRow(
                                selected = c.targetStopCode == stop.code,
                                text = stringResource(Res.string.stop_item, stop.sequence, stop.name),
                                sub = listOfNotNull(stop.city, stringResource(Res.string.stop_code, stop.code)).joinToString(" · "),
                            ) { c.targetStopCode = stop.code }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectionCard(d: Direction, onClick: (() -> Unit)?) {
    val mod = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable { onClick() } else it }
    Card(mod) {
        Column(Modifier.padding(14.dp)) {
            Text(
                stringResource(Res.string.line_with_operator, d.routeShortName, Operators.displayName(d.operatorRef, d.agencyName)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(Res.string.direction_route, d.originLabel, d.destinationLabel), style = MaterialTheme.typography.bodyMedium)
            Text(d.longName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun RadioRow(selected: Boolean, text: String, sub: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(text)
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
        Text(stringResource(Res.string.error_prefix, message), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
    }
}
