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
import il.kavmonitor.shared.walk.NoWalkingRouter
import il.kavmonitor.shared.walk.WalkPlanner
import il.kavmonitor.shared.walk.WalkRoute
import il.kavmonitor.shared.watch.LeavePlanHolder
import il.kavmonitor.shared.location.NoLocationProvider
import il.kavmonitor.shared.route.RouteLiveController
import kotlin.time.Clock
import kotlin.time.Duration

enum class Screen { HOME, NEW_WATCH, WATCH, MAP }

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
    val clock: Clock = Clock.System,
    routeLiveInterval: Duration = RouteLiveController.DEFAULT_INTERVAL,
    /** Full version name shown in the UI, e.g. "0.2-beta". */
    val appVersion: String = "",
    /** Foreground location for the "nearest stop" sections. */
    val location: LocationController = LocationController(NoLocationProvider, settings = null),
    /** Stops curlbus can't answer: shown with an "estimated" badge in the pickers (a watch falls back to neighbours). */
    val brokenStops: il.kavmonitor.shared.data.BrokenStops = il.kavmonitor.shared.data.BrokenStops(null),
    /** Pedestrian routes to stops (OSRM foot by default; straight-line estimate when it can't answer). */
    val walkPlanner: WalkPlanner = WalkPlanner(NoWalkingRouter),
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

    /**
     * Full-screen map: its own refresher (same sampled-stops logic and [il.kavmonitor.shared.trigger.Polling.INTERVAL]),
     * attached only while the map screen is composed; closing the map detaches it, so it stops refreshing.
     */
    val mapLive = RouteLiveController(live, scope, clock, routeLiveInterval)
    var mapRequest by mutableStateOf<il.kavmonitor.shared.route.RouteLiveRequest?>(null); private set
    var mapTitle by mutableStateOf(""); private set
    private var mapReturn = Screen.HOME

    /** Opens the map (on tap only) for [request]'s line; Back / "close" returns to the current screen. */
    fun openMap(request: il.kavmonitor.shared.route.RouteLiveRequest, title: String) {
        if (screen != Screen.MAP) mapReturn = screen
        mapRequest = request
        mapTitle = title
        screen = Screen.MAP
    }

    fun closeMap() {
        if (screen == Screen.MAP) screen = mapReturn
        mapRequest = null
    }

    // ---- follow one bus (long-press a bus card → "פעולות לרכב") ----
    /** Bus whose actions sheet is open (null = closed). */
    var busActions by mutableStateOf<il.kavmonitor.shared.follow.BusRef?>(null); private set
    /** The followed bus (null = none). Refreshed by the route view only (no polling of its own). */
    var followed by mutableStateOf<il.kavmonitor.shared.follow.BusRef?>(null); private set
    /** Last refresh in which the followed bus was seen (for "disappeared"). */
    var followLastSeen: kotlin.time.Instant? = null; private set

    fun openBusActions(bus: il.kavmonitor.shared.follow.BusRef) { busActions = bus }
    fun closeBusActions() { busActions = null }

    fun runBusAction(id: String) {
        val bus = busActions ?: return
        when (id) {
            il.kavmonitor.shared.follow.BusActions.FOLLOW -> { followed = bus; followLastSeen = null }
            il.kavmonitor.shared.follow.BusActions.UNFOLLOW -> unfollow()
        }
        busActions = null
    }

    fun unfollow() { followed = null; followLastSeen = null }

    /** Followed-bus card data from the route view's latest snapshot; remembers when the bus was last seen. */
    fun followInfo(
        snap: il.kavmonitor.shared.route.RouteLiveSnapshot?,
        stops: List<RouteStop>,
        preferredStop: RouteStop?,
        now: kotlin.time.Instant,
    ): il.kavmonitor.shared.follow.FollowInfo? {
        val f = followed ?: return null
        val fix = location.fix
        val ref = il.kavmonitor.shared.follow.FollowBus.refStop(stops, preferredStop, fix?.lat, fix?.lon)
        val info = il.kavmonitor.shared.follow.FollowBus.compute(snap, f, stops, ref, fix?.lat, fix?.lon, now, followLastSeen)
        if (info.bus != null && snap != null && followLastSeen != snap.fetchedAt) followLastSeen = snap.fetchedAt
        return info
    }

    /** Stop the route view should be targeted at so curlbus returns the followed bus's ETA there (null = none needed). */
    fun followTargetStop(lineRef: Int, stops: List<RouteStop>, preferredStop: RouteStop?): RouteStop? {
        followed?.takeIf { it.lineRef == lineRef } ?: return null
        val fix = location.fix
        return il.kavmonitor.shared.follow.FollowBus.refStop(stops, preferredStop, fix?.lat, fix?.lon)
    }

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
        mapLive.setAppVisible(visible)
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
    fun back(): Boolean = if (busActions != null) { busActions = null; true } else when (screen) {
        Screen.HOME -> false
        Screen.NEW_WATCH -> {
            if (selectedDirection != null) { selectedDirection = null; stops = Loadable.Idle } else screen = Screen.HOME
            true
        }
        Screen.WATCH -> { screen = Screen.HOME; true }
        Screen.MAP -> { closeMap(); true }
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

    /** Bumped when a walking route arrives (observe it to recompose / re-run effects). */
    var walkVersion by mutableStateOf(0); private set
    private val walkInflight = mutableSetOf<String>()

    /**
     * Walk from the current location to [stop]: the routed walk if already known for this ~50 m cell, else the
     * straight-line estimate (null without a fix or stop coordinates). Never does I/O; see [requestWalk].
     */
    fun autoWalk(stop: RouteStop?): WalkRoute? {
        walkVersion // observe
        val fix = location.fix ?: return null
        if (stop?.lat == null || stop.lon == null) return null
        return walkPlanner.cached(fix.lat, fix.lon, stop.code) ?: WalkPlanner.straightLine(fix.lat, fix.lon, stop.lat, stop.lon)
    }

    /**
     * One walking-route request for the current location → [stop] (on stop selection / watch start / watch screen),
     * unless cached for this cell, already in flight or failed recently. Times out after 5 s (straight line then).
     */
    fun requestWalk(stop: RouteStop?) {
        val fix = location.fix ?: return
        if (stop?.lat == null || stop.lon == null) return
        if (!walkPlanner.shouldRequest(fix.lat, fix.lon, stop.code)) return
        val key = WalkPlanner.key(fix.lat, fix.lon, stop.code)
        if (!walkInflight.add(key)) return
        scope.launch {
            try { walkPlanner.walk(fix.lat, fix.lon, stop.code, stop.lat, stop.lon) } finally { walkInflight.remove(key); walkVersion++ }
        }
    }

    /** The walk plan the new watch would start with. */
    fun leavePlan(stop: RouteStop?): LeavePlan {
        val buffer = TriggerConfig.LEAVE_BUFFER_MINUTES
        walkOverrideMinutes?.let { return LeavePlan(it, buffer, manual = true) }
        autoWalk(stop)?.let { w -> return LeavePlan(w.minutes, buffer, manual = false, walkDistanceMeters = w.distanceMeters, walkRouted = w.routed) }
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
        requestWalk(spec.stop)
        val w = autoWalk(spec.stop) ?: return
        if (w.minutes != current.walkMinutes || current.walkDistanceMeters == null || w.routed != current.walkRouted) {
            LeavePlanHolder.set(spec, current.copy(walkMinutes = w.minutes, walkDistanceMeters = w.distanceMeters, walkRouted = w.routed))
        }
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
        il.kavmonitor.shared.watch.WatchEdits.clear()
        WatchStateHolder.update(null) // show "starting…" instead of the previous watch until the service reports
        launcher.start(spec)
        screen = Screen.WATCH
    }

    fun stopWatch() = launcher.stop()

    /** +15/+30/+60 min on the running watch (no restart; the service refreshes the notification and wake lock). */
    fun extendWatch(minutes: Int) = launcher.extend(minutes)

    /** Time to leave: change the walk minutes of the running watch (manual from now on; used from the next poll). */
    fun setWatchWalkMinutes(spec: WatchSpec, minutes: Int) {
        if (spec.trigger != TriggerType.LEAVE_FOR_STOP) return
        val current = LeavePlanHolder.planFor(spec) ?: return
        val m = minutes.coerceIn(0, il.kavmonitor.shared.trigger.TriggerConfig.MAX_WALK_MINUTES)
        LeavePlanHolder.set(spec, current.copy(walkMinutes = m, walkDistanceMeters = null, manual = true, walkRouted = false))
        WatchStateHolder.state.value?.takeIf { it.spec.recentKey == spec.recentKey }?.let {
            WatchStateHolder.update(it.copy(leavePlan = LeavePlanHolder.planFor(spec)))
        }
    }

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
