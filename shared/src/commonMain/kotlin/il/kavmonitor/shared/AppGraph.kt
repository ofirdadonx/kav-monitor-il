package il.kavmonitor.shared

import com.russhwolf.settings.Settings
import il.kavmonitor.shared.data.BrokenStopTracker
import il.kavmonitor.shared.data.BrokenStops
import il.kavmonitor.shared.data.CurlbusLiveDataSource
import il.kavmonitor.shared.watch.WatchEngine
import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.data.RouteRepository
import il.kavmonitor.shared.data.StaticDataSource
import il.kavmonitor.shared.data.StrideStaticDataSource
import il.kavmonitor.shared.data.createHttpClient
import il.kavmonitor.shared.location.LocationController
import il.kavmonitor.shared.location.LocationProvider
import il.kavmonitor.shared.location.NoLocationProvider
import il.kavmonitor.shared.platform.WatchLauncher
import il.kavmonitor.shared.recents.RecentsStore
import il.kavmonitor.shared.trigger.TriggerConfig
import il.kavmonitor.shared.ui.AppController
import io.ktor.client.HttpClient
import il.kavmonitor.shared.route.RouteLiveController
import kotlin.time.Clock
import kotlin.time.Duration

/** Tiny manual DI container. Swap [live]/[static] here to point at a different backend. */
class AppGraph(
    settings: Settings,
    val launcher: WatchLauncher,
    val httpClient: HttpClient = createHttpClient(),
    val static: StaticDataSource = StrideStaticDataSource(httpClient),
    val live: LiveDataSource = CurlbusLiveDataSource(httpClient),
    val triggerConfig: TriggerConfig = TriggerConfig.Default,
    val clock: Clock = Clock.System,
    /** Full version name shown in the UI (e.g. "0.2-beta"). */
    val appVersion: String = "",
    val routeLiveInterval: Duration = RouteLiveController.DEFAULT_INTERVAL,
    /** Live source of the foreground route view (same backend as [live]; separate so tests can tell them apart). */
    val routeLiveSource: LiveDataSource = live,
    /** User location for the "nearest stop" sections (Android: LocationManager; tests: shadowed). */
    val locationProvider: LocationProvider = NoLocationProvider,
) {
    val routes = RouteRepository(static, clock)
    /** curlbus stops answering HTTP 5xx, remembered for [BrokenStops.DEFAULT_TTL]. */
    val brokenStops = BrokenStops(settings, clock)
    private val trackedLive: LiveDataSource = BrokenStopTracker(live, brokenStops)
    private val trackedRouteLive: LiveDataSource = if (routeLiveSource === live) trackedLive else BrokenStopTracker(routeLiveSource, brokenStops)

    /** Engine for one watch run (the service creates one per watch). Falls back to neighbouring stops for broken ones. */
    fun newWatchEngine(): WatchEngine = WatchEngine(trackedLive, clock, triggerConfig, brokenStops) { spec ->
        routes.stopsForLine(spec.lineRef, spec.lineName).stops
    }
    val recents = RecentsStore(settings)
    val location = LocationController(locationProvider, settings)
    val controller: AppController by lazy {
        AppController(routes, recents, launcher, trackedRouteLive, clock = clock, routeLiveInterval = routeLiveInterval, appVersion = appVersion, location = location, brokenStops = brokenStops)
    }
}
