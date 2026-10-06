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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.back
import il.kavmonitor.shared.resources.bus_alerted
import il.kavmonitor.shared.resources.bus_departure
import il.kavmonitor.shared.resources.bus_distance
import il.kavmonitor.shared.resources.bus_eta
import il.kavmonitor.shared.resources.bus_eta_now
import il.kavmonitor.shared.resources.bus_live
import il.kavmonitor.shared.resources.bus_not_live
import il.kavmonitor.shared.resources.bus_updated_ago
import il.kavmonitor.shared.resources.bus_vehicle
import il.kavmonitor.shared.resources.new_watch_again
import il.kavmonitor.shared.resources.restart_watch
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
import il.kavmonitor.shared.util.israelClock
import il.kavmonitor.shared.watch.BusView
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchState
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun WatchScreen(c: AppController, state: WatchState?) {
    val now by produceState(Clock.System.now()) { while (true) { delay(1000); value = Clock.System.now() } }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { c.back() }) { Text("→ " + stringResource(Res.string.back)) }
            Text(stringResource(Res.string.watch_title), style = MaterialTheme.typography.headlineSmall)
        }
        if (state == null) {
            Loading()
            return@Column
        }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(lineTitle(state.spec), style = MaterialTheme.typography.titleLarge)
                Text("← " + state.spec.directionLabel)
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
        Text(stringResource(Res.string.watch_buses_header, state.spec.stop.name), style = MaterialTheme.typography.titleMedium)
        if (state.buses.isEmpty() && state.lastSuccessAt != null) {
            Text(stringResource(Res.string.watch_no_buses), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.buses, key = { it.key }) { BusCard(it, now) }
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
        }
    }
}

@Composable
private fun BusCard(b: BusView, now: Instant) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text(stringResource(Res.string.bus_vehicle, b.vehicleRef ?: stringResource(Res.string.unknown)),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (b.alerted) Text(stringResource(Res.string.bus_alerted), color = MaterialTheme.colorScheme.primary)
            }
            b.eta?.let { eta ->
                val mins = ((eta - now).inWholeSeconds / 60.0).let { kotlin.math.ceil(it).toInt() }
                Text(if (mins <= 0) stringResource(Res.string.bus_eta_now, eta.israelClock())
                     else stringResource(Res.string.bus_eta, eta.israelClock(), mins))
            }
            b.scheduledDeparture?.let { Text(stringResource(Res.string.bus_departure, it.israelClock()), style = MaterialTheme.typography.bodySmall) }
            val ago = b.recordedAt?.let { agoText(now - it) } ?: stringResource(Res.string.unknown)
            Text(
                if (b.hasFreshGps) stringResource(Res.string.bus_live, stringResource(Res.string.bus_updated_ago, ago))
                else stringResource(Res.string.bus_not_live) + " · " + stringResource(Res.string.bus_updated_ago, ago),
                style = MaterialTheme.typography.bodySmall,
            )
            b.distanceToStopMeters?.let { Text(stringResource(Res.string.bus_distance, it.toInt()), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
