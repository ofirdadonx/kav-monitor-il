package il.kavmonitor.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.location.LocationAccess
import il.kavmonitor.shared.map.RouteMapData
import il.kavmonitor.shared.map.RouteMapView
import il.kavmonitor.shared.map.mapBuses
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.map_close
import il.kavmonitor.shared.resources.map_legend
import il.kavmonitor.shared.resources.map_loading
import il.kavmonitor.shared.resources.map_path_note
import il.kavmonitor.shared.resources.map_status
import il.kavmonitor.shared.resources.map_title
import il.kavmonitor.shared.trigger.Polling
import il.kavmonitor.shared.util.israelClock
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen route map, opened on tap only. While it is composed it attaches [AppController.mapLive] (live buses from
 * the sampled stops, every [Polling.INTERVAL]) and, if location permission is already granted, the location (never
 * asks for it here). Leaving the screen detaches both: no refresh after the map is closed.
 */
@Composable
fun RouteMapScreen(c: AppController) {
    val req = c.mapRequest
    if (req != null) {
        DisposableEffect(req) {
            val token = c.mapLive.attach(req)
            onDispose { c.mapLive.detach(token) }
        }
    }
    if (c.location.access == LocationAccess.GRANTED) {
        DisposableEffect(c.location) {
            val token = c.location.attach()
            onDispose { c.location.detach(token) }
        }
    }
    val st = c.mapLive.state?.takeIf { req != null && it.request.lineRef == req.lineRef }
    val snap = st?.snapshot
    val fix = c.location.fix?.takeIf { c.location.access == LocationAccess.GRANTED }
    ScreenFrame(
        title = stringResource(Res.string.map_title, c.mapTitle),
        onBack = { c.closeMap() },
        backDescription = stringResource(Res.string.map_close),
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (snap == null) stringResource(Res.string.map_loading)
                    else stringResource(Res.string.map_status, snap.fetchedAt.israelClock(withSeconds = true), snap.mapBuses().size, Polling.INTERVAL.inWholeSeconds.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                )
                st?.error?.let { Text(errorText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text(stringResource(Res.string.map_legend), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(Res.string.map_path_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (req != null) {
                RouteMapView(
                    RouteMapData(req.stops, highlightCode = req.targetStopCode, userLat = fix?.lat, userLon = fix?.lon, buses = snap?.mapBuses().orEmpty()),
                    Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).clipToBounds(),
                )
            }
        }
    }
}
