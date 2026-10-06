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
import il.kavmonitor.shared.location.LocationController
import il.kavmonitor.shared.location.NearestStop
import il.kavmonitor.shared.location.NearestStops
import il.kavmonitor.shared.model.LeavePlan
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.trigger.WalkTime
import il.kavmonitor.shared.watch.LeavePlanHolder
import il.kavmonitor.shared.location.NoLocationProvider
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
    /** Foreground location for the "nearest stop" sections. */
    val location: LocationController = LocationController(NoLocationProvider, settings = null),
    /** Stops curlbus can't answer: shown with an "estimated" badge in the pickers (a watch falls back to neighbours). */
    val brokenStops: il.kavmonitor.shared.data.BrokenStops = il.kavmonitor.shared.data.BrokenStops(null),
) {
    /** Loadable.Failed carries an [il.kavmonitor.shared.data.ErrorKind] name; the raw text only goes to the log. */
    private fun friendlyFailure(e: Exception): String {
        il.kavmonitor.shared.data.ErrorLog.w("load failed", e)
        return il.kavmonitor.shared.data.FetchErrors.classify(e).kind.name
    }

    fun isStopBroken(code: String): Boolean {
        brokenStops.version // observe
        return brokenStops.isBroken(code)
    }

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
    /** Time to leave: walk minutes set by the user (null = computed from the location). */
    var walkOverrideMinutes by mutableStateOf<Int?>(null)

    private var job: Job? = null

    fun openNewWatch() {
        directions = Loadable.Idle; selectedDirection = null; stops = Loadable.Idle
        trigger = TriggerType.DEPARTS_ORIGIN; targetStopCode = null
        durationMinutes = TriggerConfig.DEFAULT_DURATION_MINUTES
        walkOverrideMinutes = null
        screen = Screen.NEW_WATCH
    }

    fun openWatch() { screen = Screen.WATCH }

    /** Called by the platform when the app goes to the foreground/background (Activity onStart/onStop). */
    fun setAppVisible(visible: Boolean) {
        routeLive.setAppVisible(visible)
        location.setAppVisible(visible)
    }

    /** One-tap "use the stop nearest to me": origin → departs trigger, any other stop → arrives-at-stop trigger. */
    fun useNearestStop(n: NearestStop) {
        if (trigger == TriggerType.LEAVE_FOR_STOP) {
            targetStopCode = n.stop.code // boarding stop (the origin is fine too)
        } else if (n.index == 0) {
            trigger = TriggerType.DEPARTS_ORIGIN
        } else {
            trigger = TriggerType.ARRIVES_AT_STOP
            targetStopCode = n.stop.code
        }
    }

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
                Loadable.Failed(friendlyFailure(e))
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
                Loadable.Failed(friendlyFailure(e))
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

    /** Selects "time to leave"; the boarding stop defaults to the stop nearest to the user (if known and nothing chosen). */
    fun selectLeaveTrigger() {
        trigger = TriggerType.LEAVE_FOR_STOP
        defaultBoardingStop()
    }

    /** Called when a location fix arrives: fills in the nearest stop as boarding stop once, never over a user choice. */
    fun defaultBoardingStop() {
        if (trigger != TriggerType.LEAVE_FOR_STOP || targetStopCode != null) return
        val list = (stops as? Loadable.Ready)?.value?.stops ?: return
        val fix = location.fix ?: return
        NearestStops.find(list, fix.lat, fix.lon)?.let { targetStopCode = it.stop.code }
    }

    /** Walk time computed from the current location to [stop] (null without a fix or stop coordinates). */
    fun autoWalk(stop: RouteStop?): Pair<Int, Double>? {
        val fix = location.fix ?: return null
        val d = stop?.let { NearestStops.distanceTo(it, fix.lat, fix.lon) } ?: return null
        return WalkTime.minutes(d) to d
    }

    /** The walk plan the new watch would start with. */
    fun leavePlan(stop: RouteStop?): LeavePlan {
        val buffer = TriggerConfig.LEAVE_BUFFER_MINUTES
        walkOverrideMinutes?.let { return LeavePlan(it, buffer, manual = true) }
        autoWalk(stop)?.let { (m, d) -> return LeavePlan(m, buffer, manual = false, walkDistanceMeters = d) }
        return LeavePlan(TriggerConfig.DEFAULT_WALK_MINUTES, buffer, manual = false, walkDistanceMeters = null)
    }

    fun changeWalkMinutes(delta: Int, stop: RouteStop?) {
        val current = walkOverrideMinutes ?: leavePlan(stop).walkMinutes
        walkOverrideMinutes = (current + delta).coerceIn(0, TriggerConfig.MAX_WALK_MINUTES)
    }

    /**
     * Running time-to-leave watch: while the app is visible, refresh the walk time from fresh fixes (unless set
     * manually). The background service only reads the latest value ([LeavePlanHolder]).
     */
    fun refreshWatchWalk(spec: WatchSpec) {
        if (spec.trigger != TriggerType.LEAVE_FOR_STOP) return
        val current = LeavePlanHolder.planFor(spec) ?: return
        if (current.manual) return
        val (m, d) = autoWalk(spec.stop) ?: return
        if (m != current.walkMinutes || current.walkDistanceMeters == null) LeavePlanHolder.set(spec, current.copy(walkMinutes = m, walkDistanceMeters = d))
    }

    fun startNewWatch() {
        val d = selectedDirection ?: return
        val list = (stops as? Loadable.Ready)?.value?.stops ?: return
        val origin = list.first()
        var trig = trigger
        val stop = if (trig != TriggerType.DEPARTS_ORIGIN) list.firstOrNull { it.code == targetStopCode } ?: return else origin
        if (trig == TriggerType.ARRIVES_AT_STOP && stop.code == origin.code) trig = TriggerType.DEPARTS_ORIGIN
        startWatch(
            WatchSpec(
                lineRef = d.lineRef, lineName = d.routeShortName, operatorRef = d.operatorRef,
                agencyName = Operators.displayName(d.operatorRef, d.agencyName),
                directionLabel = list.last().let { last -> if (last.city.isNullOrBlank()) last.name else "${last.name}, ${last.city}" },
                trigger = trig, stop = stop, origin = origin,
                durationMinutes = durationMinutes,
                leave = if (trig == TriggerType.LEAVE_FOR_STOP) leavePlan(stop) else null,
            ),
        )
    }

    fun startWatch(spec: WatchSpec) {
        recents.add(spec)
        LeavePlanHolder.clear()
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
                set(Loadable.Failed(friendlyFailure(e)))
            }
        }
    }
}
