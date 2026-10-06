package il.kavmonitor.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
import il.kavmonitor.shared.resources.walk_minutes
import il.kavmonitor.shared.resources.walk_routed
import il.kavmonitor.shared.resources.walk_straight

private fun NearbyStop.asRouteStop() = RouteStop(0, code, nameHe, null, lat, lon)

/**
 * Home: "תחנות קרובות" (curlbus /nearby, nearest 3–5 within 300 m → 500 → 1000 m). Only with the location permission
 * already granted (location runs only while this is shown); otherwise a quiet "enable location" row (never asks by
 * itself on the home screen).
 */
@Composable
fun NearbyStopsSection(c: AppController) {
    if (!c.hasNearby) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val loc = c.location
    if (loc.access != LocationAccess.GRANTED) {
        Row(Modifier.fillMaxWidth().testTag("nearby-enable"), verticalAlignment = Alignment.CenterVertically) {
            Text("תחנות קרובות אליך — דורש הרשאת מיקום", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.weight(1f))
            TextButton(onClick = { loc.enableLocation() }) { Text("הפעלת מיקום") }
        }
        return
    }
    DisposableEffect(loc) {
        val t = loc.attach()
        onDispose { loc.detach(t) }
    }
    val fix = loc.fix
    if (fix != null) LaunchedEffect(il.kavmonitor.shared.data.NearbyStops.cacheKey(fix.lat, fix.lon)) { c.loadNearby(fix.lat, fix.lon) }
    Card(Modifier.fillMaxWidth().testTag("nearby-section")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("תחנות קרובות", style = MaterialTheme.typography.titleMedium)
            when (val n = c.nearby) {
                is Loadable.Ready -> if (n.value.isEmpty()) {
                    Text("אין תחנות עד ק״מ ממך", style = MaterialTheme.typography.bodySmall, color = muted)
                } else n.value.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider()
                    Row(Modifier.fillMaxWidth().clickable { c.openStop(s) }.padding(vertical = 4.dp).testTag("nearby-${s.code}"),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(isolate(s.nameHe), style = MaterialTheme.typography.bodyLarge)
                            val walkLine = if (i == 0) {
                                LaunchedEffect(s.code, fix?.let { il.kavmonitor.shared.walk.WalkPlanner.key(it.lat, it.lon, s.code) }) { c.requestWalk(s.asRouteStop()) }
                                c.autoWalk(s.asRouteStop())?.let { w ->
                                    if (w.routed) stringResource(Res.string.walk_routed, distanceText(w.distanceMeters), w.minutes)
                                    else stringResource(Res.string.walk_minutes, w.minutes) + " · " + stringResource(Res.string.walk_straight)
                                }
                            } else null
                            Text(
                                listOfNotNull(distanceText(s.distanceMeters) + " בקו אווירי", "תחנה " + s.code, walkLine).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = muted,
                            )
                        }
                        Text("←", style = MaterialTheme.typography.titleMedium, color = muted)
                    }
                }
                is Loadable.Failed -> Text(errorText(n.message), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(if (fix == null) "מאתר מיקום…" else "טוען תחנות קרובות…", style = MaterialTheme.typography.bodySmall, color = muted)
                }
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
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { c.back() }) { Text("→ חזרה") }
            }
            Text(isolate(stop.nameHe), style = MaterialTheme.typography.headlineSmall)
            Text("תחנה ${stop.code} · ${distanceText(stop.distanceMeters)} ממך בקו אווירי", style = MaterialTheme.typography.bodySmall, color = muted)
            val snap = st?.snapshot
            Text(
                when {
                    snap != null -> "עודכן ${snap.fetchedAt.israelClock()} · מתעדכן כל דקה כשהמסך פתוח" + if (st.loading) " · מעדכן…" else ""
                    st?.error != null -> errorText(st.error)
                    else -> "טוען…"
                },
                style = MaterialTheme.typography.bodySmall, color = if (st?.error != null && snap == null) MaterialTheme.colorScheme.error else muted,
            )
            Text("לחיצה על קו פותחת מעקב חדש לתחנה הזו", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        val arrivals = st?.snapshot?.let { StopArrivals.from(it, now) }.orEmpty()
        if (st?.snapshot != null && arrivals.isEmpty()) item { Text("אין אוטובוסים בחצי השעה הקרובה", color = muted) }
        items(arrivals, key = { it.key }) { a ->
            Card(Modifier.fillMaxWidth().clickable { c.watchFromStop(a, stop.code) }.testTag("arrival-${a.key}")) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(isolate(a.lineName), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.destination?.let { "← " + isolate(NotificationTexts.shortStop(it, 28)) } ?: "", style = MaterialTheme.typography.bodyLarge)
                        Text(a.operatorName, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        val m = a.minutes(now)
                        Text(if (m <= 0) "עכשיו" else "$m דק׳", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (a.live) "● חי (GPS)" else "○ לפי לו״ז", style = MaterialTheme.typography.labelSmall,
                                color = if (a.live) Color(0xFF2E7D32) else muted)
                        }
                    }
                }
            }
        }
    }
}
