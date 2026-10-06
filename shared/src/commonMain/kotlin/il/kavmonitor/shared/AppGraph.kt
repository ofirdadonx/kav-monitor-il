package il.kavmonitor.shared

import com.russhwolf.settings.Settings
import il.kavmonitor.shared.data.CurlbusLiveDataSource
import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.data.RouteRepository
import il.kavmonitor.shared.data.StaticDataSource
import il.kavmonitor.shared.data.StrideStaticDataSource
import il.kavmonitor.shared.data.createHttpClient
import il.kavmonitor.shared.platform.WatchLauncher
import il.kavmonitor.shared.recents.RecentsStore
import il.kavmonitor.shared.ui.AppController
import io.ktor.client.HttpClient

/** Tiny manual DI container. Swap [live]/[static] here to point at a different backend. */
class AppGraph(
    settings: Settings,
    val launcher: WatchLauncher,
    val httpClient: HttpClient = createHttpClient(),
    val static: StaticDataSource = StrideStaticDataSource(httpClient),
    val live: LiveDataSource = CurlbusLiveDataSource(httpClient),
) {
    val routes = RouteRepository(static)
    val recents = RecentsStore(settings)
    val controller: AppController by lazy { AppController(routes, recents, launcher) }
}
