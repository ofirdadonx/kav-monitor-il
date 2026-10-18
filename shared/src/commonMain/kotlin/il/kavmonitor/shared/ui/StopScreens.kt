package il.kavmonitor.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.data.NearbyStop
import il.kavmonitor.shared.location.LocationAccess
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.notify.NotificationTexts
import il.kavmonitor.shared.stop.StopArrivals
import il.kavmonitor.shared.util.israelClock
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.empty_nearby_body
import il.kavmonitor.shared.resources.empty_nearby_title
import il.kavmonitor.shared.resources.nearby_collapse
import il.kavmonitor.shared.resources.nearby_expand
import il.kavmonitor.shared.resources.walk_routed

private fun NearbyStop.asRouteStop() = RouteStop(0, code, nameHe, null, lat, lon)

/**
 * Home: "תחנות קרובות" (curlbus /nearby, nearest 3–5 within 300 m → 500 → 1000 m). Only with the location permission
 * already granted (location runs only while this is shown); otherwise a quiet "enable location" row (never asks by
 * itself on the home screen).
 */
@Composable
fun NearbyStopsSection(c: AppController) {
    if (!c.hasNearby) return
    var open by rememberSaveable { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().testTag("nearby-section"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("nearby-header"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("תחנות קרובות", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(if (open) Res.string.nearby_collapse else Res.string.nearby_expand),
            )
        }
        if (open) {
            HorizontalDivider()
            if (c.location.access != LocationAccess.GRANTED) {
                Surface(
                    onClick = { c.location.enableLocation() },
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("nearby-enable"),
                ) {
                    Row(
                        Modifier.defaultMinSize(minHeight = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.MyLocation, contentDescription = null)
                        Column(Modifier.weight(1f)) {
                            Text("הפעלת מיקום", style = MaterialTheme.typography.titleSmall)
                            Text("תחנות קרובות אליך — דורש הרשאת מיקום", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                NearbyStopsList(c)
            }
        }
    }
}

@Composable
private fun NearbyStopsList(c: AppController) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val loc = c.location
    DisposableEffect(loc) {
        val t = loc.attach()
        onDispose { loc.detach(t) }
    }
    val fix = loc.fix
    if (fix != null) LaunchedEffect(il.kavmonitor.shared.data.NearbyStops.cacheKey(fix.lat, fix.lon)) { c.loadNearby(fix.lat, fix.lon) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val n = c.nearby) {
            is Loadable.Ready -> if (n.value.isEmpty()) {
                EmptyState(Icons.Filled.Place, stringResource(Res.string.empty_nearby_title), stringResource(Res.string.empty_nearby_body))
            } else n.value.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).clickable { c.openStop(s) }.padding(vertical = 8.dp).testTag("nearby-${s.code}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(isolate(s.nameHe), style = MaterialTheme.typography.bodyLarge)
                        LaunchedEffect(s.code, fix?.let { il.kavmonitor.shared.walk.WalkPlanner.key(it.lat, it.lon, s.code) }) { c.requestWalk(s.asRouteStop()) }
                        val walkLine = c.autoWalk(s.asRouteStop())?.takeIf { it.routed }?.let { w ->
                            stringResource(Res.string.walk_routed, metersText(w.distanceMeters), w.minutes)
                        }
                        Text(
                            listOfNotNull(walkLine, "תחנה " + s.code).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = muted,
                        )
                    }
                    Text("←", style = MaterialTheme.typography.titleMedium, color = muted)
                }
            }
            is Loadable.Failed -> ErrorNotice(n.message)
            else -> {
                Text(if (fix == null) "מאתר מיקום…" else "טוען תחנות קרובות…", style = MaterialTheme.typography.bodySmall, color = muted)
                LoadingSkeleton(lines = 3)
            }
        }
    }
}

/** One stop's live arrivals (all lines): one `/<stop>` request per 60 s, only while this screen is shown. */
@Composable
fun StopScreen(c: AppController) {
    val stop = c.stopScreen ?: return
    val now by produceState(c.clock.now()) { while (true) { delay(1000); value = c.clock.now() } }
    DisposableEffect(stop.code) {
        val t = c.stopArrivals.attach(stop.code, stop.nameHe)
        onDispose { c.stopArrivals.detach(t) }
    }
    val st = c.stopArrivals.state?.takeIf { it.stopCode == stop.code }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val walkStop = stop.asRouteStop()
    LaunchedEffect(stop.code, c.location.fix?.let { il.kavmonitor.shared.walk.WalkPlanner.key(it.lat, it.lon, stop.code) }) {
        c.requestWalk(walkStop)
    }
    val walk = c.autoWalk(walkStop)?.takeIf { it.routed }
    ScreenFrame(
        title = isolate(stop.nameHe),
        subtitle = if (walk != null) "תחנה ${stop.code} · ${metersText(walk.distanceMeters)} הליכה" else "תחנה ${stop.code}",
    ) { inner ->
    LazyColumn(
        Modifier.fillMaxSize().consumeWindowInsets(inner),
        contentPadding = inner.withPagePadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            val snap = st?.snapshot
            Text(
                when {
                    snap != null -> "עודכן ${snap.fetchedAt.israelClock()} · מתעדכן כל דקה כשהמסך פתוח" + if (st.loading) " · מעדכן…" else ""
                    st?.error != null -> errorText(st.error)
                    else -> "טוען…"
                },
                style = MaterialTheme.typography.bodySmall, color = if (st?.error != null && snap == null) MaterialTheme.colorScheme.error else muted,
            )
            Text("לחיצה על קו מציגה את המסלול. מעקב מתחיל רק בלחיצה על התחל מעקב.", style = MaterialTheme.typography.bodySmall, color = muted)
            if (snap == null && st?.error == null) LoadingSkeleton(lines = 4)
        }
        val arrivals = st?.snapshot?.let { StopArrivals.from(it, now) }.orEmpty()
        if (st?.snapshot != null && arrivals.isEmpty()) item {
            EmptyState(Icons.Filled.DirectionsBus, "אין אוטובוסים בחצי השעה הקרובה")
        }
        items(arrivals, key = { it.key }) { a ->
            Card(onClick = { c.watchFromStop(a, stop.code) }, modifier = Modifier.fillMaxWidth().testTag("arrival-${a.key}")) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(isolate(a.lineName), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(64.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.destination?.let { "← " + isolate(NotificationTexts.shortStop(it, 28)) } ?: "", style = MaterialTheme.typography.bodyLarge)
                        Text(a.operatorName, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val m = a.minutes(now)
                        Text(if (m <= 0) "עכשיו" else "$m דק׳", style = MaterialTheme.typography.titleLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (a.live) "● חי (GPS)" else "○ לפי לו״ז", style = MaterialTheme.typography.labelSmall,
                                color = if (a.live) MaterialTheme.colorScheme.primary else muted)
                        }
                    }
                }
            }
        }
    }
    }
}
