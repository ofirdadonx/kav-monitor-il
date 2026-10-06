package il.kavmonitor.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.follow.BusActions
import il.kavmonitor.shared.follow.BusRef
import il.kavmonitor.shared.follow.FollowInfo
import il.kavmonitor.shared.follow.FollowStatus
import il.kavmonitor.shared.follow.FollowTexts
import il.kavmonitor.shared.route.RouteBus
import il.kavmonitor.shared.util.israelClock
import il.kavmonitor.shared.watch.BusView
import kotlin.math.roundToInt
import kotlin.time.Instant

fun RouteBus.toBusRef(lineRef: Int, lineName: String): BusRef =
    BusRef(lineRef, lineName, key, vehicleRef, tripId, scheduledStart, position?.let { (it.fraction * 100).roundToInt() })

fun BusView.toBusRef(lineRef: Int, lineName: String, fraction: Double?): BusRef =
    BusRef(lineRef, lineName, key, vehicleRef, tripId, scheduledDeparture, fraction?.let { (it * 100).roundToInt() })

/**
 * "פעולות לרכב": bottom sheet over the current screen (drawn in-layout, so Back and the harness see it).
 * The actions come from [BusActions.forBus] (extensible list).
 */
@Composable
fun BusActionsSheet(c: AppController) {
    val bus = c.busActions ?: return
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { c.closeBusActions() }.testTag("bus-actions-scrim"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(
            Modifier.fillMaxWidth().safeDrawingPadding().clickable(enabled = false) {}.testTag("bus-actions-sheet"),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("פעולות לרכב", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${isolate(bus.lineName)} · רכב ${isolate(bus.vehicleRef ?: "לא ידוע")}",
                    style = MaterialTheme.typography.titleMedium,
                )
                val sub = buildList {
                    bus.scheduledStart?.let { add("יציאה מתוכננת ${it.israelClock()}") }
                    bus.percent?.let { add("$it% מהמסלול") }
                }
                if (sub.isNotEmpty()) Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                BusActions.forBus(bus, c.followed).forEach { a ->
                    TextButton(onClick = { c.runBusAction(a.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(a.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                    }
                }
                TextButton(onClick = { c.closeBusActions() }, modifier = Modifier.fillMaxWidth()) {
                    Text("ביטול", modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Prominent card for the followed bus (refreshed with the route view; no polling of its own). */
@Composable
fun FollowCard(ref: BusRef, info: FollowInfo, now: Instant, onUnfollow: () -> Unit) {
    val gone = info.status == FollowStatus.GONE
    Card(
        Modifier.fillMaxWidth().testTag("follow-card"),
        colors = CardDefaults.cardColors(
            containerColor = if (gone) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("📍 " + FollowTexts.title(ref), style = MaterialTheme.typography.titleSmall)
            Text(FollowTexts.headline(info, now), style = MaterialTheme.typography.titleLarge)
            FollowTexts.details(info, now).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onUnfollow) { Text("הפסק לעקוב") }
            }
        }
    }
}
