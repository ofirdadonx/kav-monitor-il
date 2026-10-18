package il.kavmonitor.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.map.MapBus
import il.kavmonitor.shared.map.RouteMapData
import il.kavmonitor.shared.map.RouteMapPreview
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.empty_buses_title
import il.kavmonitor.shared.resources.map_fullscreen
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
import il.kavmonitor.shared.route.RouteBus
import il.kavmonitor.shared.route.RouteLiveState
import il.kavmonitor.shared.util.israelClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** Compact map height on the line and watch screens. Tap opens the full map. */
val CompactMapHeight = 240.dp

/**
 * Map under the first card. A placeholder shows until the stop list is ready.
 * The preview pans on its own. The corner button opens the full map.
 */
@Composable
fun CompactRouteMap(
    stops: List<RouteStop>?,
    highlightCode: String?,
    userLat: Double?,
    userLon: Double?,
    buses: List<MapBus>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.extraLarge
    val mod = modifier.fillMaxWidth().height(CompactMapHeight).testTag("route-map-preview")
    if (stops.isNullOrEmpty()) {
        Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = mod) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                LoadingSkeleton(lines = 4)
            }
        }
    } else {
        Box(mod) {
            RouteMapPreview(
                RouteMapData(stops, highlightCode = highlightCode, userLat = userLat, userLon = userLon, buses = buses),
                Modifier.fillMaxSize(),
            )
            FilledTonalIconButton(
                onClick = onOpen,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).testTag("map-fullscreen"),
            ) {
                Icon(Icons.Filled.OpenInFull, contentDescription = stringResource(Res.string.map_fullscreen))
            }
        }
    }
}

/**
 * The live line section (watch screen and the new-watch route view): header ("על הקו עכשיו", active count, update
 * time), the line diagram, then the compact bus rows (followed bus pinned first, tap = expand, long-press = vehicle
 * actions), then the no-GPS / upcoming-departures lines and the source note.
 * Tapping a bus in the diagram expands its row and scrolls to it. The map is placed by the screen, above this block.
 */
fun LazyListScope.liveLineItems(
    keyPrefix: String,
    state: RouteLiveState?,
    stops: List<RouteStop>?,
    userIndex: Int?,
    now: Instant,
    rows: List<BusDetails>,
    rowsTitle: @androidx.compose.runtime.Composable () -> String,
    noRowsText: (@androidx.compose.runtime.Composable () -> String)?,
    expanded: String?,
    onExpand: (String?) -> Unit,
    listState: LazyListState,
    scope: CoroutineScope,
    onLongPress: ((String) -> Unit)?,
    isFollowed: (String) -> Boolean,
    /** ETA at the user's stop for the diagram chips (default: curlbus ETA there or the estimate). */
    diagramEta: ((RouteBus) -> Pair<Instant, Boolean>?)? = null,
    showLive: Boolean = true,
    /** false: no rows section yet (route view still loading). */
    showRows: Boolean = true,
) {
    val snap = state?.snapshot
    val ordered = rows.sortedBy { if (isFollowed(it.key)) 0 else 1 } // stable: followed pinned first, rest by ETA
    val diagramKey = "$keyPrefix-diagram"
    if (showLive) {
        item(key = "$keyPrefix-header") { RouteLiveHeader(state) }
        item(key = diagramKey) {
            if (snap != null && stops != null) {
                val d = LineModels.diagram(
                    stops, snap, userIndex, now,
                    eta = diagramEta ?: { b -> userIndex?.let { LineModels.etaAtStop(b, stops, it) } },
                    followed = { b -> isFollowed(b.key) },
                )
                Card(Modifier.fillMaxWidth()) {
                    LineDiagram(
                        d, Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                        onBusTap = { k ->
                            onExpand(k)
                            val i = ordered.indexOfFirst { it.key == k }
                            val di = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == diagramKey }?.index
                            if (i >= 0 && di != null) scope.launch { listState.animateScrollToItem(di + 2 + i) }
                        },
                        onBusLongPress = onLongPress,
                    )
                }
            }
        }
    }
    if (showRows) item(key = "$keyPrefix-rows-title") {
        Text(rowsTitle(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    }
    ordered.forEach { b ->
        item(key = "$keyPrefix-row-${b.key}") {
            CompactBusRow(
                b, now, expanded = expanded == b.key,
                onTap = { onExpand(if (expanded == b.key) null else b.key) },
                onLongPress = onLongPress?.let { f -> { f(b.key) } },
                followed = isFollowed(b.key),
            )
        }
    }
    if (showRows && ordered.isEmpty() && noRowsText != null) {
        item(key = "$keyPrefix-rows-empty") {
            EmptyState(Icons.Filled.DirectionsBus, stringResource(Res.string.empty_buses_title), noRowsText())
        }
    }
    if (showLive && snap != null) {
        if (snap.noGps.isNotEmpty()) {
            item(key = "$keyPrefix-nogps") { Text(stringResource(Res.string.route_live_no_gps, snap.noGps.size), style = MaterialTheme.typography.bodySmall) }
        }
        val upcoming = snap.notStarted.mapNotNull { it.scheduledStart }.distinct().sorted()
        if (upcoming.isNotEmpty()) {
            item(key = "$keyPrefix-upcoming") {
                Text(stringResource(Res.string.route_live_upcoming, upcoming.joinToString(", ") { it.israelClock() }), style = MaterialTheme.typography.bodySmall)
            }
        }
        item(key = "$keyPrefix-source") {
            Text(stringResource(Res.string.route_live_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@androidx.compose.runtime.Composable
fun RouteLiveHeader(state: RouteLiveState?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.route_live_title), style = MaterialTheme.typography.titleMedium)
            if (state?.loading == true) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        val snap = state?.snapshot
        when {
            snap == null && state?.error == null -> {
                Text(stringResource(Res.string.route_live_loading), style = MaterialTheme.typography.bodyMedium)
                LoadingSkeleton(lines = 3)
            }
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
        state?.error?.let { Text(stringResource(Res.string.route_live_error, errorText(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
