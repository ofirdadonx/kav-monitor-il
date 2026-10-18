package il.kavmonitor.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.app_version
import il.kavmonitor.shared.resources.dir_destination
import il.kavmonitor.shared.resources.dir_origin
import il.kavmonitor.shared.resources.duration_minutes
import il.kavmonitor.shared.resources.home_active_watch
import il.kavmonitor.shared.resources.home_new_watch
import il.kavmonitor.shared.resources.home_no_recents
import il.kavmonitor.shared.resources.home_open_watch
import il.kavmonitor.shared.resources.home_recents
import il.kavmonitor.shared.resources.home_title
import il.kavmonitor.shared.resources.label_trigger
import il.kavmonitor.shared.resources.recent_remove
import il.kavmonitor.shared.watch.WatchState
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeScreen(c: AppController, watch: WatchState?) {
    val recents by c.recents.recents.collectAsState()
    ScreenFrame(title = stringResource(Res.string.home_title), largeTitle = true) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().consumeWindowInsets(inner),
            contentPadding = inner.withPagePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (watch != null && watch.isActive) {
                item(key = "active") {
                    Card(
                        onClick = { c.openWatch() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(Res.string.home_active_watch), style = MaterialTheme.typography.labelLarge)
                                Text(lineTitle(watch.spec), style = MaterialTheme.typography.titleMedium)
                                Text(triggerText(watch.spec))
                            }
                            TextButton(onClick = { c.openWatch() }) { Text(stringResource(Res.string.home_open_watch)) }
                        }
                    }
                }
            }
            item(key = "new") {
                Button(onClick = { c.openNewWatch() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.home_new_watch))
                }
            }
            item(key = "nearby") { NearbyStopsSection(c) }
            item(key = "recents-title") { Text(stringResource(Res.string.home_recents), style = MaterialTheme.typography.titleMedium) }
            if (recents.isEmpty()) {
                item(key = "recents-empty") { Text(stringResource(Res.string.home_no_recents), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(recents, key = { it.recentKey }) { spec ->
                Card(onClick = { c.startWatch(spec) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(lineTitle(spec), style = MaterialTheme.typography.titleMedium)
                            val small = MaterialTheme.typography.bodyMedium
                            LabeledRow(stringResource(Res.string.dir_origin), isolate(spec.origin.name), null, valueStyle = small)
                            LabeledRow(stringResource(Res.string.dir_destination), isolate(destinationParts(spec).first), null, valueStyle = small)
                            LabeledRow(
                                stringResource(Res.string.label_trigger),
                                triggerRowText(spec) + " · " + stringResource(Res.string.duration_minutes, spec.durationMinutes),
                                null, valueStyle = small,
                            )
                        }
                        TextButton(onClick = { c.recents.remove(spec) }) { Text(stringResource(Res.string.recent_remove)) }
                    }
                }
            }
            if (c.appVersion.isNotBlank()) {
                item(key = "version") {
                    Text(
                        stringResource(Res.string.app_version, isolate("v" + c.appVersion)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
