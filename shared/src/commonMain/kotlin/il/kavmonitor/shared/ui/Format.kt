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
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

@Composable
fun lineTitle(spec: WatchSpec): String = stringResource(Res.string.line_with_operator, spec.lineName, spec.agencyName)

@Composable
fun triggerText(spec: WatchSpec): String = when (spec.trigger) {
    TriggerType.DEPARTS_ORIGIN -> stringResource(Res.string.trigger_departs_short, spec.origin.name)
    TriggerType.ARRIVES_AT_STOP -> stringResource(Res.string.trigger_arrives_short, spec.stop.name)
}

@Composable
fun agoText(d: Duration): String {
    val s = d.inWholeSeconds.coerceAtLeast(0)
    return when {
        s < 90 -> stringResource(Res.string.ago_seconds, s.toInt())
        s < 90 * 60 -> stringResource(Res.string.ago_minutes, (s / 60).toInt())
        else -> stringResource(Res.string.ago_hours, (s / 3600).toInt())
    }
}
