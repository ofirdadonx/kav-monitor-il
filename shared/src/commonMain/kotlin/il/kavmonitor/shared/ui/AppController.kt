package il.kavmonitor.shared.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import il.kavmonitor.shared.data.RouteRepository
import il.kavmonitor.shared.model.Direction
import il.kavmonitor.shared.model.Operators
import il.kavmonitor.shared.model.StopList
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.platform.WatchLauncher
import il.kavmonitor.shared.recents.RecentsStore
import il.kavmonitor.shared.trigger.TriggerConfig
import il.kavmonitor.shared.watch.WatchStateHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.runtime.mutableStateMapOf
import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.route.RouteLiveController
import kotlin.time.Clock
import kotlin.time.Duration

enum class Screen { HOME, NEW_WATCH, WATCH }

sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>
}

/** UI state + actions. Lives as long as the process (survives rotation), not tied to a platform ViewModel. */
class AppController(
    private val routes: RouteRepository,
    val recents: RecentsStore,
    private val launcher: WatchLauncher,
    live: LiveDataSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    clock: Clock = Clock.System,
    routeLiveInterval: Duration = RouteLiveController.DEFAULT_INTERVAL,
    /** Full version name shown in the UI, e.g. "0.2-beta". */
    val appVersion: String = "",
) {
    var screen by mutableStateOf(Screen.HOME); private set

    /** Live route view (foreground only; see [RouteLiveController]). */
    val routeLive = RouteLiveController(live, scope, clock, routeLiveInterval)

    /** Stop lists per direction (line_ref), prefetched for the visible direction cards (stop counts, real end names). */
    val directionStops = mutableStateMapOf<Int, Loadable<StopList>>()
    private val prefetchPermits = Semaphore(PREFETCH_PARALLELISM)

    /** Stop list of the watch shown on the watch screen (for per-bus progress and the route view there). */
    var watchStops by mutableStateOf<Loadable<StopList>>(Loadable.Idle); private set
    private var watchStopsKey: Int? = null

    // ---- new watch flow ----
    var lineInput by mutableStateOf("")
    var directions by mutableStateOf<Loadable<List<Direction>>>(Loadable.Idle); private set
    var selectedDirection by mutableStateOf<Direction?>(null); private set
    var stops by mutableStateOf<Loadable<StopList>>(Loadable.Idle); private set
    var trigger by mutableStateOf(TriggerType.DEPARTS_ORIGIN)
    var targetStopCode by mutableStateOf<String?>(null)
    var durationMinutes by mutableStateOf(TriggerConfig.DEFAULT_DURATION_MINUTES)

    private var job: Job? = null

    fun openNewWatch() {
        directions = Loadable.Idle; selectedDirection = null; stops = Loadable.Idle
        trigger = TriggerType.DEPARTS_ORIGIN; targetStopCode = null
        durationMinutes = TriggerConfig.DEFAULT_DURATION_MINUTES
        screen = Screen.NEW_WATCH
    }

    fun openWatch() { screen = Screen.WATCH }

    /** Called by the platform when the app goes to the foreground/background (Activity onStart/onStop). */
    fun setAppVisible(visible: Boolean) = routeLive.setAppVisible(visible)

    /** Starts (once) a background load of the direction's stop list; at most [PREFETCH_PARALLELISM] at a time. */
    fun ensureStops(d: Direction) {
        if (directionStops[d.lineRef] != null) return
        directionStops[d.lineRef] = Loadable.Loading
        scope.launch {
            val result = try {
                prefetchPermits.withPermit { Loadable.Ready(routes.stops(d)) }
            } catch (e: CancellationException) {
                directionStops.remove(d.lineRef)
                throw e
            } catch (e: Exception) {
                Loadable.Failed(e.message?.take(120) ?: "error")
            }
            directionStops[d.lineRef] = result
        }
    }

    /** Loads the stop list for a running/saved watch (once per line_ref). */
    fun ensureWatchStops(spec: WatchSpec) {
        if (watchStopsKey == spec.lineRef && watchStops !is Loadable.Failed) return
        watchStopsKey = spec.lineRef
        watchStops = Loadable.Loading
        scope.launch {
            watchStops = try {
                Loadable.Ready(routes.stopsForLine(spec.lineRef, spec.lineName))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loadable.Failed(e.message?.take(120) ?: "error")
            }
        }
    }

    /** @return true if handled (navigated back inside the app). */
    fun back(): Boolean = when (screen) {
        Screen.HOME -> false
        Screen.NEW_WATCH -> {
            if (selectedDirection != null) { selectedDirection = null; stops = Loadable.Idle } else screen = Screen.HOME
            true
        }
        Screen.WATCH -> { screen = Screen.HOME; true }
    }

    fun searchLine() {
        val line = lineInput.trim()
        if (line.isEmpty()) return
        selectedDirection = null; stops = Loadable.Idle
        directionStops.clear()
        load({ directions = it }) { routes.directions(line) }
    }

    fun selectDirection(d: Direction) {
        selectedDirection = d; trigger = TriggerType.DEPARTS_ORIGIN; targetStopCode = null
        load({ stops = it }) { routes.stops(d).also { directionStops[d.lineRef] = Loadable.Ready(it) } }
    }

    fun canStart(): Boolean {
        val s = (stops as? Loadable.Ready)?.value ?: return false
        return s.stops.isNotEmpty() && (trigger == TriggerType.DEPARTS_ORIGIN || targetStopCode != null)
    }

    fun startNewWatch() {
        val d = selectedDirection ?: return
        val list = (stops as? Loadable.Ready)?.value?.stops ?: return
        val origin = list.first()
        var trig = trigger
        val stop = if (trig == TriggerType.ARRIVES_AT_STOP) list.firstOrNull { it.code == targetStopCode } ?: return else origin
        if (stop.code == origin.code) trig = TriggerType.DEPARTS_ORIGIN
        startWatch(
            WatchSpec(
                lineRef = d.lineRef, lineName = d.routeShortName, operatorRef = d.operatorRef,
                agencyName = Operators.displayName(d.operatorRef, d.agencyName),
                directionLabel = list.last().let { last -> if (last.city.isNullOrBlank()) last.name else "${last.name}, ${last.city}" },
                trigger = trig, stop = stop, origin = origin,
                durationMinutes = durationMinutes,
            ),
        )
    }

    fun startWatch(spec: WatchSpec) {
        recents.add(spec)
        WatchStateHolder.update(null) // show "starting…" instead of the previous watch until the service reports
        launcher.start(spec)
        screen = Screen.WATCH
    }

    fun stopWatch() = launcher.stop()

    companion object {
        const val PREFETCH_PARALLELISM = 3
    }

    private fun <T> load(set: (Loadable<T>) -> Unit, block: suspend () -> T) {
        job?.cancel()
        set(Loadable.Loading)
        job = scope.launch {
            try {
                set(Loadable.Ready(block()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                set(Loadable.Failed(e.message?.lineSequence()?.firstOrNull()?.take(160) ?: e::class.simpleName.orEmpty()))
            }
        }
    }
}
