package il.kavmonitor.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.location.DistanceLabel
import il.kavmonitor.shared.location.LocationAccess
import il.kavmonitor.shared.location.LocationController
import il.kavmonitor.shared.location.NearestStop
import il.kavmonitor.shared.location.NearestStops
import il.kavmonitor.shared.location.formatDistance
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.dist_km
import il.kavmonitor.shared.resources.dist_m
import il.kavmonitor.shared.resources.nearest_accuracy
import il.kavmonitor.shared.resources.nearest_denied
import il.kavmonitor.shared.resources.nearest_distance
import il.kavmonitor.shared.resources.nearest_enable
import il.kavmonitor.shared.resources.nearest_fix_age
import il.kavmonitor.shared.resources.nearest_is_watched
import il.kavmonitor.shared.resources.nearest_locating
import il.kavmonitor.shared.resources.nearest_no_coords
import il.kavmonitor.shared.resources.nearest_rationale
import il.kavmonitor.shared.resources.nearest_selected
import il.kavmonitor.shared.resources.nearest_services_off
import il.kavmonitor.shared.resources.nearest_title
import il.kavmonitor.shared.resources.nearest_to_watched
import il.kavmonitor.shared.resources.nearest_use_arrive
import il.kavmonitor.shared.resources.nearest_use_depart
import il.kavmonitor.shared.resources.stop_code
import il.kavmonitor.shared.resources.stop_item
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

@Composable
fun distanceText(meters: Double): String {
    val d = formatDistance(meters)
    return stringResource(if (d.unit == DistanceLabel.Unit.METERS) Res.string.dist_m else Res.string.dist_km, d.value)
}

/**
 * "Nearest stop on this route" card. Location updates run only while this card is composed (attached) and the app is
 * visible; the first time it is shown the system permission dialog may appear (once ever, see [LocationController]).
 *
 * @param isSelected whether the given nearest stop is already the alert stop (shows a check instead of the button)
 * @param onUse one-tap "alert me for this stop" (null: no action, e.g. on the watch screen)
 * @param watchedStop on the watch screen: the watched stop, to also show the distance to it
 */
@Composable
fun NearestStopSection(
    location: LocationController,
    stops: List<RouteStop>,
    now: Instant,
    isSelected: (NearestStop) -> Boolean = { false },
    onUse: ((NearestStop) -> Unit)? = null,
    watchedStop: RouteStop? = null,
) {
    DisposableEffect(location) {
        val token = location.attach()
        onDispose { location.detach(token) }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.nearest_title), style = MaterialTheme.typography.titleMedium)
            when (location.access) {
                LocationAccess.UNKNOWN, LocationAccess.ASKING ->
                    Text(stringResource(Res.string.nearest_rationale), style = MaterialTheme.typography.bodySmall, color = muted)
                LocationAccess.DENIED -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.nearest_denied), style = MaterialTheme.typography.bodySmall, color = muted,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { location.enableLocation() }) { Text(stringResource(Res.string.nearest_enable)) }
                }
                LocationAccess.GRANTED -> {
                    val fix = location.fix
                    if (location.servicesOff && fix == null) {
                        Text(stringResource(Res.string.nearest_services_off), style = MaterialTheme.typography.bodySmall, color = muted)
                    } else if (fix == null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(stringResource(Res.string.nearest_locating), style = MaterialTheme.typography.bodyMedium, color = muted)
                        }
                    } else {
                        val n = NearestStops.find(stops, fix.lat, fix.lon)
                        if (n == null) {
                            Text(stringResource(Res.string.nearest_no_coords), style = MaterialTheme.typography.bodySmall, color = muted)
                        } else {
                            Text(stringResource(Res.string.stop_item, n.stop.sequence, isolate(n.stop.name)), style = MaterialTheme.typography.bodyLarge)
                            Text(listOfNotNull(n.stop.city, stringResource(Res.string.stop_code, n.stop.code)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = muted)
                            Text(stringResource(Res.string.nearest_distance, distanceText(n.distanceMeters)), style = MaterialTheme.typography.bodyMedium)
                            fix.accuracyMeters?.takeIf { it > ACCURACY_NOTE_METERS }?.let {
                                Text(stringResource(Res.string.nearest_accuracy, distanceText(it)), style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                            (now - fix.time).takeIf { it.inWholeSeconds > STALE_FIX_SECONDS }?.let {
                                Text(stringResource(Res.string.nearest_fix_age, agoText(it)), style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                            if (watchedStop != null) {
                                if (watchedStop.code == n.stop.code) {
                                    Text(stringResource(Res.string.nearest_is_watched), style = MaterialTheme.typography.bodySmall, color = muted)
                                } else {
                                    NearestStops.distanceTo(watchedStop, fix.lat, fix.lon)?.let { d ->
                                        Text(stringResource(Res.string.nearest_to_watched, isolate(watchedStop.name), distanceText(d)),
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (onUse != null) {
                                if (isSelected(n)) {
                                    Text(stringResource(Res.string.nearest_selected), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                } else {
                                    OutlinedButton(onClick = { onUse(n) }, modifier = Modifier.fillMaxWidth()) {
                                        Text(stringResource(if (n.index == 0) Res.string.nearest_use_depart else Res.string.nearest_use_arrive))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val ACCURACY_NOTE_METERS = 100.0
private const val STALE_FIX_SECONDS = 60L
