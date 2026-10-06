package il.kavmonitor.shared.ui

import androidx.compose.runtime.Composable
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.ago_hours
import il.kavmonitor.shared.resources.ago_minutes
import il.kavmonitor.shared.resources.ago_seconds
import il.kavmonitor.shared.resources.line_with_operator
import il.kavmonitor.shared.resources.trigger_arrives_short
import il.kavmonitor.shared.resources.trigger_departs_short
import il.kavmonitor.shared.resources.reason_at_stop_eta
import il.kavmonitor.shared.resources.reason_at_stop_gps
import il.kavmonitor.shared.resources.reason_dropped_at_stop
import il.kavmonitor.shared.resources.reason_left_dropped
import il.kavmonitor.shared.resources.reason_left_gps
import il.kavmonitor.shared.resources.reason_left_scheduled
import il.kavmonitor.shared.trigger.TriggerReason
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

@Composable
fun lineTitle(spec: WatchSpec): String = stringResource(Res.string.line_with_operator, spec.lineName, spec.agencyName)

/**
 * Wraps text in Unicode FIRST STRONG ISOLATE … POP DIRECTIONAL ISOLATE so its internal direction cannot leak into the
 * surrounding Hebrew text (e.g. "רציפים A" followed by " · 60 דק׳" was rendered with the number glued to the "A").
 */
fun isolate(text: String): String = "\u2068$text\u2069"

@Composable
fun triggerText(spec: WatchSpec): String = when (spec.trigger) {
    TriggerType.DEPARTS_ORIGIN -> stringResource(Res.string.trigger_departs_short, isolate(spec.origin.name))
    TriggerType.ARRIVES_AT_STOP -> stringResource(Res.string.trigger_arrives_short, isolate(spec.stop.name))
}

/** "2026-10-05" → "05/10" (day/month, Israeli order). */
fun shortDate(iso: String): String {
    val parts = iso.split("-")
    return if (parts.size == 3) "${parts[2]}/${parts[1]}" else iso
}

/** Meters → "12.3" (km, one decimal). */
fun kmText(meters: Double): String {
    val tenths = kotlin.math.round(meters / 100.0).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

/** Whole minutes from [now] until [t], rounded up (0 when due/past). */
fun minutesUntil(t: kotlin.time.Instant, now: kotlin.time.Instant): Int =
    kotlin.math.ceil((t - now).inWholeSeconds / 60.0).toInt().coerceAtLeast(0)

@Composable
fun agoText(d: Duration): String {
    val s = d.inWholeSeconds.coerceAtLeast(0)
    return when {
        s < 90 -> stringResource(Res.string.ago_seconds, s.toInt())
        s < 90 * 60 -> stringResource(Res.string.ago_minutes, (s / 60).toInt())
        else -> stringResource(Res.string.ago_hours, (s / 3600).toInt())
    }
}

@Composable
fun reasonText(r: TriggerReason): String = stringResource(
    when (r) {
        TriggerReason.LEFT_ORIGIN_DROPPED -> Res.string.reason_left_dropped
        TriggerReason.LEFT_ORIGIN_GPS -> Res.string.reason_left_gps
        TriggerReason.LEFT_ORIGIN_SCHEDULED -> Res.string.reason_left_scheduled
        TriggerReason.AT_STOP_GPS -> Res.string.reason_at_stop_gps
        TriggerReason.AT_STOP_ETA -> Res.string.reason_at_stop_eta
        TriggerReason.DROPPED_AT_STOP -> Res.string.reason_dropped_at_stop
    },
)
