package il.kavmonitor.shared.ui

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
    val now by produceState(Clock.System.now()) { while (true) { delay(1000); value = Clock.System.now() } }
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
                    Text("← " + isolate(state.spec.directionLabel))
                    Text(triggerText(state.spec), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (state.phase) {
                            WatchPhase.RUNNING -> stringResource(Res.string.watch_status_running, state.endsAt.israelClock())
                            WatchPhase.TIMED_OUT -> stringResource(Res.string.watch_status_timed_out)
                            WatchPhase.STOPPED -> stringResource(Res.string.watch_status_stopped)
                        },
                    )
                    Text(
                        state.lastSuccessAt?.let { stringResource(Res.string.watch_last_update, it.israelClock(withSeconds = true)) }
                            ?: stringResource(Res.string.watch_no_update_yet),
                    )
                    Text(stringResource(Res.string.watch_buses_seen, state.busesSeen) + " · " + stringResource(Res.string.watch_alerts, state.alerts.size))
                    state.lastError?.let { Text(stringResource(Res.string.watch_error, it), color = MaterialTheme.colorScheme.error) }
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
        item { Text(stringResource(Res.string.watch_buses_header, isolate(state.spec.stop.name)), style = MaterialTheme.typography.titleMedium) }
        if (state.buses.isEmpty() && state.lastSuccessAt != null) {
            item { Text(stringResource(Res.string.watch_no_buses), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(state.buses, key = { "watch-" + it.key }) { BusDetailsCard(it.toDetails(state.spec, stops?.stops), now) }
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
        item { HorizontalDivider() }
        if (!state.isActive) {
            // no live route view for a finished watch
        } else if (stops == null) {
            item { Text(stringResource(Res.string.route_live_need_stops), style = MaterialTheme.typography.bodySmall) }
        } else {
            routeLiveItems(c.routeLive.state?.takeIf { it.request.lineRef == state.spec.lineRef }, now, state.spec.stop, keyPrefix = "w")
        }
    }
}
