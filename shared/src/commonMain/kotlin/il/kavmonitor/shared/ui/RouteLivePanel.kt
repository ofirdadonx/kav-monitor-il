package il.kavmonitor.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.route_live_active
import il.kavmonitor.shared.resources.route_live_active_one
import il.kavmonitor.shared.resources.route_live_error
import il.kavmonitor.shared.resources.route_live_failed_stops
import il.kavmonitor.shared.resources.route_live_loading
import il.kavmonitor.shared.resources.route_live_no_gps
import il.kavmonitor.shared.resources.route_live_none
import il.kavmonitor.shared.resources.route_live_source
import il.kavmonitor.shared.resources.route_live_title
import il.kavmonitor.shared.resources.route_live_upcoming
import il.kavmonitor.shared.resources.route_live_updated
import il.kavmonitor.shared.route.RouteLiveState
import il.kavmonitor.shared.util.israelClock
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Live route view items: active-bus count, one card per active bus, upcoming departures. */
fun LazyListScope.routeLiveItems(state: RouteLiveState?, now: Instant, targetStop: RouteStop?, keyPrefix: String = "route") {
    item(key = "$keyPrefix-header") {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.route_live_title), style = MaterialTheme.typography.titleMedium)
                if (state?.loading == true) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            val snap = state?.snapshot
            when {
                snap == null && state?.error == null -> Text(stringResource(Res.string.route_live_loading), style = MaterialTheme.typography.bodyMedium)
                snap != null -> {
                    val n = snap.active.size
                    Text(
                        when (n) {
                            0 -> stringResource(Res.string.route_live_none)
                            1 -> stringResource(Res.string.route_live_active_one)
                            else -> stringResource(Res.string.route_live_active, n)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val extra = if (snap.failedStops.isNotEmpty()) " · " + stringResource(Res.string.route_live_failed_stops, snap.failedStops.size) else ""
                    Text(
                        stringResource(Res.string.route_live_updated, snap.fetchedAt.israelClock(withSeconds = true), snap.sampledStops.size) + extra,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state?.error?.let { Text(stringResource(Res.string.route_live_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
    val snap = state?.snapshot ?: return
    val origin = state.request.stops.firstOrNull()
    // A snapshot kept from the previous request may have been sampled for another target: no ETA then (never mislabel).
    val etaStop = targetStop?.takeIf { it.code == snap.targetStopCode }
    items(snap.active, key = { "$keyPrefix-bus-" + it.key }) { bus ->
        BusDetailsCard(bus.toDetails(etaStop, origin?.code), now)
    }
    if (snap.noGps.isNotEmpty()) {
        item(key = "$keyPrefix-nogps") {
            Text(stringResource(Res.string.route_live_no_gps, snap.noGps.size), style = MaterialTheme.typography.bodySmall)
        }
    }
    val upcoming = snap.notStarted.mapNotNull { it.scheduledStart }.distinct().sorted()
    if (upcoming.isNotEmpty()) {
        item(key = "$keyPrefix-upcoming") {
            Text(stringResource(Res.string.route_live_upcoming, upcoming.joinToString(", ") { it.israelClock() }), style = MaterialTheme.typography.bodyMedium)
        }
    }
    item(key = "$keyPrefix-source") {
        Text(stringResource(Res.string.route_live_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
