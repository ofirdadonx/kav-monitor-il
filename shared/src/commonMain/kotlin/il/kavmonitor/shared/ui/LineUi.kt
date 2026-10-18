package il.kavmonitor.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.bus_alerted
import il.kavmonitor.shared.resources.bus_at_origin
import il.kavmonitor.shared.resources.bus_departed_early
import il.kavmonitor.shared.resources.bus_departed_late
import il.kavmonitor.shared.resources.bus_departed_on_time
import il.kavmonitor.shared.resources.bus_departure
import il.kavmonitor.shared.resources.bus_dep_eta
import il.kavmonitor.shared.resources.bus_dep_eta_now
import il.kavmonitor.shared.resources.bus_distance
import il.kavmonitor.shared.resources.bus_eta_at
import il.kavmonitor.shared.resources.bus_eta_at_now
import il.kavmonitor.shared.resources.bus_eta_destination
import il.kavmonitor.shared.resources.bus_last_update
import il.kavmonitor.shared.resources.bus_near_stop
import il.kavmonitor.shared.resources.bus_next_stop
import il.kavmonitor.shared.resources.bus_not_started
import il.kavmonitor.shared.resources.bus_not_started_unknown
import il.kavmonitor.shared.resources.bus_off_route
import il.kavmonitor.shared.resources.bus_progress
import il.kavmonitor.shared.resources.bus_vehicle
import il.kavmonitor.shared.resources.eta_estimated
import il.kavmonitor.shared.resources.eta_live
import il.kavmonitor.shared.resources.eta_scheduled
import il.kavmonitor.shared.resources.gps_fresh
import il.kavmonitor.shared.resources.gps_none
import il.kavmonitor.shared.resources.gps_old
import il.kavmonitor.shared.resources.gps_stale
import il.kavmonitor.shared.resources.leave_alerted
import il.kavmonitor.shared.resources.leave_at
import il.kavmonitor.shared.resources.leave_no_eta
import il.kavmonitor.shared.resources.leave_not_catchable
import il.kavmonitor.shared.resources.leave_now
import il.kavmonitor.shared.resources.unknown
import il.kavmonitor.shared.trigger.LeaveStatus
import il.kavmonitor.shared.ui.LineModels.DiagramBus
import il.kavmonitor.shared.ui.LineModels.DiagramRow
import il.kavmonitor.shared.util.israelClock
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/*
 * v0.6: the watch screen's hero card, line diagram and compact bus rows (from the approved v0.6 mockup).
 */

@Composable
private fun isDark() = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3 < 0.5f }

@Composable
fun toneColor(t: HeroTone): Color = statusFill(t, isDark())

fun freshnessColor(f: Freshness): Color = when (f) {
    Freshness.FRESH -> Color(0xFF2EAD4B)
    Freshness.AGING -> Color(0xFFF08C00)
    Freshness.SCHEDULED -> Color(0xFF9E9E9E)
}

/** "~33" kept in that order inside Hebrew text. */
private fun approx(s: String, estimated: Boolean) = if (estimated) "\u2066~$s\u2069" else s

// ---------------------------------------------------------------- hero

@Composable
fun HeroCard(m: HeroModel, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth().testTag("hero-${m.tone}"),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = toneColor(m.tone), contentColor = Color.White),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (m.label != null) Text(m.label, style = MaterialTheme.typography.titleLarge)
            Text(
                m.big,
                style = if (m.tone == HeroTone.NONE) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(m.sub, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.92f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- line diagram

private val RAIL = 36.dp

private fun DrawScope.rail(color: Color, top: Boolean, bottom: Boolean, dashed: Boolean = false) {
    val x = size.width / 2
    val w = 4.dp.toPx()
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())) else null
    if (top) drawLine(color, Offset(x, 0f), Offset(x, size.height / 2), w, StrokeCap.Butt, effect)
    if (bottom) drawLine(color, Offset(x, size.height / 2), Offset(x, size.height), w, StrokeCap.Butt, effect)
}

/** Material bus symbol. A not-yet-started bus is the same icon, dimmed. */
@Composable
fun BusGlyph(color: Color, modifier: Modifier = Modifier, hollow: Boolean = false) {
    Icon(
        Icons.Filled.DirectionsBus,
        contentDescription = null,
        tint = if (hollow) color.copy(alpha = 0.45f) else color,
        modifier = modifier.size(20.dp),
    )
}

private fun Modifier.busGestures(key: String, onTap: ((String) -> Unit)?, onLongPress: ((String) -> Unit)?): Modifier =
    if (onTap == null && onLongPress == null) this
    else combinedClickable(onClick = { onTap?.invoke(key) }, onLongClick = onLongPress?.let { f -> { f(key) } })

@Composable
private fun BusChip(b: DiagramBus, glyph: Boolean, onTap: ((String) -> Unit)?, onLongPress: ((String) -> Unit)?) {
    Row(
        Modifier.busGestures(b.key, onTap, onLongPress).testTag("diagram-bus-${b.key}").padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (glyph) BusGlyph(if (b.notStarted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, hollow = b.notStarted)
        Box(Modifier.size(8.dp).clip(CircleShape).background(freshnessColor(b.freshness)))
        Text(
            listOfNotNull(
                if (b.followed) "📍" else null,
                b.vehicle?.let { "רכב $it" },
                if (b.notStarted) "טרם יצא" else null,
                b.etaMinutes?.let { approx("$it", b.etaEstimated) + " דק׳ לתחנה שלך" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Vertical strip of the route: stops (short names), buses at / between stops, quiet runs collapsed. */
@Composable
fun LineDiagram(
    rows: List<DiagramRow>,
    modifier: Modifier = Modifier,
    onBusTap: ((String) -> Unit)? = null,
    onBusLongPress: ((String) -> Unit)? = null,
) {
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().testTag("line-diagram")) {
        rows.forEachIndexed { i, r ->
            val first = i == 0
            val last = i == rows.lastIndex
            when (r) {
                is DiagramRow.Stop -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(RAIL).fillMaxHeight()) {
                        Canvas(Modifier.fillMaxSize()) {
                            rail(railColor, top = !first, bottom = !last)
                            val c = Offset(size.width / 2, size.height / 2)
                            when {
                                r.isUser -> { drawCircle(primary, radius = 11.dp.toPx(), center = c); drawCircle(surface, radius = 5.dp.toPx(), center = c) }
                                r.isOrigin || r.isDestination -> { drawCircle(railColor, radius = 8.dp.toPx(), center = c); drawCircle(surface, radius = 4.dp.toPx(), center = c) }
                                else -> { drawCircle(surface, radius = 6.dp.toPx(), center = c); drawCircle(railColor, radius = 6.dp.toPx(), center = c, style = Stroke(3.dp.toPx())) }
                            }
                        }
                    }
                    Column(Modifier.weight(1f).padding(vertical = 6.dp, horizontal = 6.dp)) {
                        val userStyle = if (r.isUser) Modifier.clip(MaterialTheme.shapes.small).background(primary.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier
                        Row(userStyle.testTag(if (r.isUser) "diagram-user-stop" else "diagram-stop-${r.index}"), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${r.index + 1}. ${isolate(r.name)}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (r.isUser || r.isOrigin || r.isDestination) FontWeight.Bold else FontWeight.Normal,
                                color = if (r.isUser) primary else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                            )
                            if (r.isUser) Text("  · התחנה שלך", style = MaterialTheme.typography.labelMedium, color = primary)
                        }
                        // broken on curlbus: neighbours stand in, times there are estimates
                        if (r.broken) Text(
                            if (r.isUser) "התחנה לא מדווחת ב-curlbus · זמנים משוערים לפי תחנות סמוכות" else "לא מדווחת ב-curlbus",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary,
                        )
                        r.busesAt.forEach { BusChip(it, glyph = true, onBusTap, onBusLongPress) }
                    }
                }
                is DiagramRow.Between -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(RAIL).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) { rail(railColor, top = true, bottom = true) }
                        BusGlyph(primary, Modifier.busGestures(r.buses.first().key, onBusTap, onBusLongPress))
                    }
                    Column(Modifier.weight(1f).padding(vertical = 4.dp, horizontal = 6.dp)) { r.buses.forEach { BusChip(it, glyph = false, onBusTap, onBusLongPress) } }
                }
                is DiagramRow.Collapsed -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(RAIL).fillMaxHeight()) {
                        Canvas(Modifier.fillMaxSize()) { rail(railColor, top = true, bottom = true, dashed = true) }
                    }
                    Text("… ${r.count} תחנות", style = MaterialTheme.typography.bodyMedium, color = muted,
                        modifier = Modifier.padding(vertical = 7.dp, horizontal = 6.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- compact bus rows

/** Collapsed: one short line about the bus (leave time for time-to-leave, else where it is / when it starts). */
@Composable
private fun rowLine(b: BusDetails, now: Instant): String? {
    b.leaveStatus?.let { st ->
        val leave = b.leaveAt
        return when (st) {
            LeaveStatus.WAIT -> leave?.let { stringResource(Res.string.leave_at, it.israelClock(), minutesUntil(it, now)) }
            LeaveStatus.LEAVE_NOW -> stringResource(Res.string.leave_now)
            LeaveStatus.ALERTED -> stringResource(Res.string.leave_alerted, leave?.israelClock() ?: "?")
            LeaveStatus.NOT_CATCHABLE -> stringResource(Res.string.leave_not_catchable, leave?.israelClock() ?: "?")
            LeaveStatus.NO_ETA -> stringResource(Res.string.leave_no_eta)
        }
    }
    val sched = b.scheduledStart?.israelClock()
    return when {
        b.phase == BusPhase.NOT_STARTED -> if (sched != null) stringResource(Res.string.bus_not_started, sched) else stringResource(Res.string.bus_not_started_unknown)
        b.phase == BusPhase.AT_ORIGIN -> if (sched != null) stringResource(Res.string.bus_at_origin, sched) else stringResource(Res.string.bus_not_started_unknown)
        b.nearStop != null -> stringResource(Res.string.bus_near_stop, isolate(b.nearStop.name))
        b.nextStop != null -> stringResource(Res.string.bus_next_stop, isolate(b.nextStop.name))
        b.phase == BusPhase.NO_GPS -> stringResource(Res.string.gps_none)
        else -> null
    }
}

/**
 * One bus: big minutes to the stop of interest (to departure for the origin; progress % when there is no ETA), a thin
 * progress bar, a freshness dot; tap = expand the details, long-press = vehicle actions (follow).
 */
@Composable
fun CompactBusRow(
    b: BusDetails,
    now: Instant,
    expanded: Boolean,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    followed: Boolean = false,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall
    val fresh = LineModels.freshness(b, now)
    val border = if (followed) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) else Modifier
    Card(
        Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .testTag("bus-card-" + b.key),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.fillMaxWidth().then(border).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val eta = b.eta
                    val (big, unit) = when {
                        eta != null -> approx("${minutesUntil(eta, now)}", b.etaEstimated) to (if (b.etaEstimated) "דק׳ (הערכה)" else "דק׳")
                        b.fraction != null -> "${(b.fraction * 100).roundToInt()}%" to "מהמסלול"
                        else -> "–" to "דק׳"
                    }
                    Text(big, style = MaterialTheme.typography.headlineLarge,
                        color = if (b.etaEstimated) muted else MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Text(unit, style = MaterialTheme.typography.labelMedium, color = muted)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    val tags = listOfNotNull(
                        if (followed) "📍 במעקב" else null,
                        if (b.alerted) stringResource(Res.string.bus_alerted) else null,
                    )
                    if (tags.isNotEmpty()) Text(tags.joinToString("  "), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    rowLine(b, now)?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Filled.DirectionsBus, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (b.leaveStatus == LeaveStatus.NOT_CATCHABLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (b.fraction ?: 0.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = if (fresh == Freshness.SCHEDULED) muted else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                        drawStopIndicator = {},
                    )
                }
                Box(Modifier.size(12.dp).clip(CircleShape).background(freshnessColor(fresh)).testTag("fresh-${b.key}-$fresh"))
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Column(Modifier.testTag("bus-details-${b.key}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BusDetailLines(b, now, small, muted)
                }
            }
        }
    }
}

/** Everything about one bus (the v0.5 card's lines), shown when a row is expanded. */
@Composable
private fun BusDetailLines(b: BusDetails, now: Instant, small: androidx.compose.ui.text.TextStyle, muted: Color) {
    @Composable fun line(s: String, color: Color = muted) = Text(s, style = small, color = color)
    line(stringResource(Res.string.bus_vehicle, b.vehicleRef ?: stringResource(Res.string.unknown)), MaterialTheme.colorScheme.onSurface)
    val sched = b.scheduledStart?.israelClock()
    if (sched != null && (b.phase == BusPhase.ACTIVE || b.phase == BusPhase.NO_GPS)) line(stringResource(Res.string.bus_departure, sched))
    b.departedAt?.let { dep ->
        val s0 = b.scheduledStart ?: return@let
        val delta = ((dep - s0).inWholeSeconds / 60.0).roundToInt()
        line(
            when {
                delta > 0 -> stringResource(Res.string.bus_departed_late, dep.israelClock(), delta)
                delta < 0 -> stringResource(Res.string.bus_departed_early, dep.israelClock(), -delta)
                else -> stringResource(Res.string.bus_departed_on_time, dep.israelClock())
            },
        )
    }
    if (b.fraction != null && b.phase == BusPhase.ACTIVE) {
        line(stringResource(Res.string.bus_progress, (b.fraction * 100).roundToInt(), b.remainingMeters?.let { kmText(it) } ?: "?"))
    }
    if (b.offRoute) line(stringResource(Res.string.bus_off_route), MaterialTheme.colorScheme.error)
    if (b.leaveStatus != null) {
        // collapsed line shows the leave time: show where the bus is here
        when {
            b.nearStop != null -> line(stringResource(Res.string.bus_near_stop, isolate(b.nearStop.name)))
            b.nextStop != null -> line(stringResource(Res.string.bus_next_stop, isolate(b.nextStop.name)))
        }
    }
    if (b.eta != null && b.etaStopName != null) {
        val m = minutesUntil(b.eta, now)
        val clock = b.eta.israelClock()
        line(
            when {
                b.etaIsDeparture && m <= 0 -> stringResource(Res.string.bus_dep_eta_now, clock)
                b.etaIsDeparture -> stringResource(Res.string.bus_dep_eta, clock, m)
                m <= 0 -> stringResource(Res.string.bus_eta_at_now, isolate(b.etaStopName), clock)
                else -> stringResource(Res.string.bus_eta_at, isolate(b.etaStopName), clock, m)
            } + (if (b.etaEstimated) " · " + stringResource(Res.string.eta_estimated) else "") +
                (b.etaLive?.let { " · " + stringResource(if (it) Res.string.eta_live else Res.string.eta_scheduled) } ?: ""),
            MaterialTheme.colorScheme.onSurface,
        )
    }
    b.etaDestination?.let { line(stringResource(Res.string.bus_eta_destination, it.israelClock())) }
    b.distanceToStopMeters?.let { line(stringResource(Res.string.bus_distance, it.toInt())) }
    if (b.phase == BusPhase.ACTIVE || b.phase == BusPhase.AT_ORIGIN) {
        b.recordedAt?.let { rec ->
            val age = now - rec
            val fresh = stringResource(
                when {
                    age <= 2.minutes -> Res.string.gps_fresh
                    age <= 5.minutes -> Res.string.gps_stale
                    else -> Res.string.gps_old
                },
            )
            line(stringResource(Res.string.bus_last_update, agoText(age), rec.israelClock(withSeconds = true)) + " · " + fresh)
        }
    } else if (b.phase == BusPhase.NO_GPS) line(stringResource(Res.string.gps_none))
}
