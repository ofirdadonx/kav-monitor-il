package il.kavmonitor.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.app_version
import il.kavmonitor.shared.resources.favorite_remove
import il.kavmonitor.shared.resources.home_active_watch
import il.kavmonitor.shared.resources.home_favorites
import il.kavmonitor.shared.resources.home_no_recents
import il.kavmonitor.shared.resources.home_no_recents_title
import il.kavmonitor.shared.resources.home_open_watch
import il.kavmonitor.shared.resources.home_recents
import il.kavmonitor.shared.resources.home_title
import il.kavmonitor.shared.resources.line_label
import il.kavmonitor.shared.resources.recent_remove
import il.kavmonitor.shared.watch.WatchState
import org.jetbrains.compose.resources.stringResource

@Composable
fun HomeScreen(c: AppController, watch: WatchState?) {
    val recents by c.recents.recents.collectAsState()
    val favorites by c.favorites.items.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    ScreenFrame(
        title = stringResource(Res.string.home_title),
        showTitleBar = false,
        bottomBar = {
            if (c.appVersion.isNotBlank()) {
                Text(
                    stringResource(Res.string.app_version, isolate("v" + c.appVersion)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 8.dp, top = 4.dp),
                )
            }
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner)) {
            LineSearchField(
                value = query,
                onValueChange = { query = it },
                onSearch = { c.searchFromHome(query) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("home-line-search"),
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (watch != null && watch.isActive) {
                    item(key = "active") {
                        Card(
                            onClick = { c.openWatch() },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
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
                if (favorites.isNotEmpty()) {
                    item(key = "favorites-title") { SectionTitle(stringResource(Res.string.home_favorites)) }
                    items(favorites, key = { "fav-" + it.key }) { line ->
                        LineHomeCard(
                            line.lineName, line.originName, line.destinationLabel,
                            onClick = { c.openLine(line.lineName, line.lineRef) },
                            action = {
                                IconButton(onClick = { c.favorites.remove(line) }) {
                                    Icon(Icons.Filled.Star, contentDescription = stringResource(Res.string.favorite_remove), tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                        )
                    }
                }
                item(key = "recents-title") { SectionTitle(stringResource(Res.string.home_recents)) }
                if (recents.isEmpty()) {
                    item(key = "recents-empty") {
                        EmptyState(
                            Icons.Filled.History,
                            stringResource(Res.string.home_no_recents_title),
                            stringResource(Res.string.home_no_recents),
                        )
                    }
                }
                items(recents, key = { it.key }) { line ->
                    LineHomeCard(
                        line.lineName, line.originName, line.destinationLabel,
                        onClick = { c.openLine(line.lineName, line.lineRef) },
                        action = {
                            IconButton(onClick = { c.recents.remove(line) }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.recent_remove))
                            }
                        },
                    )
                }
                item(key = "nearby") { NearbyStopsSection(c) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun LineHomeCard(
    lineName: String,
    origin: String,
    destination: String,
    onClick: () -> Unit,
    action: @Composable () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(stringResource(Res.string.line_label, lineName), style = MaterialTheme.typography.titleMedium)
                Text(isolate(origin) + " ← " + isolate(destination), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action()
        }
    }
}
