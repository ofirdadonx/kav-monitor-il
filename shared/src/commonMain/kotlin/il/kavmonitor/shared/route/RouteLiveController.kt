package il.kavmonitor.shared.route

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import il.kavmonitor.shared.data.LiveDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class RouteLiveState(
    val request: RouteLiveRequest,
    val sampledStopCount: Int,
    val snapshot: RouteLiveSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Foreground-only refresher for the live route view. Runs ONLY while a screen is attached ([attach]/[detach], called from
 * the composable's DisposableEffect) AND the app is visible ([setAppVisible], from Activity onStart/onStop). Never used
 * by the background service. Re-attaching within [interval] of the last fetch waits for the remainder (no burst).
 */
class RouteLiveController(
    private val live: LiveDataSource,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    val interval: Duration = DEFAULT_INTERVAL,
    /** Persistent broken-stop cache shared with the watch (full-route mode leaves those stops out). */
    broken: il.kavmonitor.shared.data.BrokenStops? = null,
    /** v0.5 default: one multi-stop request per refresh. false = v0.4 sampled single-stop calls. */
    val fullRoute: Boolean = true,
) {
    var state by mutableStateOf<RouteLiveState?>(null); private set

    private val multi = if (fullRoute) il.kavmonitor.shared.data.MultiStopFetcher(live, broken, clock) else null
    private var fetcher: RouteLiveFetcher? = null
    private var runningKey: Any? = null
    private var job: Job? = null
    private var tokens = 0
    private var attachedToken: Int? = null
    private var appVisible = true

    /** Number of completed refresh cycles (tests/diagnostics). */
    var refreshes = 0; private set
    /** HTTP requests of the last refresh (tests/diagnostics). */
    val lastRequests: Int get() = fetcher?.lastRequests ?: 0
    /** A refresh is in flight right now (a tick that comes while one is in flight is skipped). */
    var inFlight = false; private set
    val isRefreshing: Boolean get() = job?.isActive == true

    /** Requests for the same line and stop list differ only by target: full-route mode reuses the data, no new request. */
    private fun routeKey(r: RouteLiveRequest): Any = if (fullRoute) Pair(r.lineRef, r.stops.map { it.code }) else r

    fun attach(request: RouteLiveRequest): Int {
        val token = ++tokens
        attachedToken = token
        val old = fetcher
        if (old?.request != request) {
            if (old != null && fullRoute && routeKey(old.request) == routeKey(request)) {
                val f = old.retargeted(request)
                fetcher = f
                val prev = state
                state = RouteLiveState(request, f.requestedStopCount,
                    snapshot = prev?.snapshot?.let { f.rebuildLast(it.fetchedAt) ?: it }, loading = prev?.loading == true, error = prev?.error)
            } else {
                val f = RouteLiveFetcher(live, request, clock, multi = multi)
                fetcher = f
                val keep = state?.takeIf { it.request.lineRef == request.lineRef }?.snapshot
                state = RouteLiveState(request, f.requestedStopCount, snapshot = keep)
            }
        }
        reconcile()
        return token
    }

    fun detach(token: Int) {
        if (attachedToken == token) {
            attachedToken = null
            reconcile()
        }
    }

    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        reconcile()
    }

    private fun reconcile() {
        val f = fetcher
        val shouldRun = attachedToken != null && appVisible && f != null
        if (!shouldRun) {
            job?.cancel(); job = null; runningKey = null; inFlight = false
            state?.let { if (it.loading) state = it.copy(loading = false) }
            return
        }
        val key = routeKey(f!!.request)
        if (job?.isActive == true && runningKey == key) return // same route: the loop picks up the (re-targeted) fetcher
        job?.cancel()
        inFlight = false
        runningKey = key
        job = scope.launch { loop() }
    }

    /**
     * Fixed 60 s ticks; requests never overlap: one refresh at a time, and a tick that comes while a (slow, up to 30 s)
     * multi-stop request is still running is skipped rather than queued.
     */
    private suspend fun loop() {
        val first = fetcher ?: return
        // Same line already fetched recently (screen re-entered, or only the target stop changed): don't burst.
        val last = state?.takeIf { it.request.lineRef == first.request.lineRef }?.snapshot
        if (last != null) {
            val gap = if (fullRoute || last.targetStopCode == first.request.targetStopCode) interval else MIN_GAP_ON_TARGET_CHANGE
            val wait = gap - (clock.now() - last.fetchedAt)
            if (wait > Duration.ZERO) delay(wait)
        }
        while (scope.isActive) {
            val f = fetcher ?: return
            val started = clock.now()
            state = (state ?: RouteLiveState(f.request, f.requestedStopCount)).copy(loading = true)
            inFlight = true
            val result = try {
                Result.success(f.refresh())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                il.kavmonitor.shared.data.ErrorLog.w("route view refresh failed", e)
                Result.failure(e)
            } finally {
                inFlight = false
            }
            // the target may have changed while the request was in flight: rebuild for the current one
            val cur = fetcher
            val snap = result.getOrNull()?.let { s -> if (cur !== f && cur != null) { cur.adopt(f); cur.rebuildLast(s.fetchedAt) ?: s } else s }
            state = (state ?: RouteLiveState(f.request, f.requestedStopCount)).let { st ->
                if (snap != null) st.copy(snapshot = snap, loading = false, error = null, sampledStopCount = (cur ?: f).requestedStopCount)
                else st.copy(loading = false, error = il.kavmonitor.shared.data.FetchErrors.classify(result.exceptionOrNull()!!).kind.name)
            }
            refreshes++
            val elapsed = clock.now() - started
            var wait = interval - elapsed
            while (wait <= Duration.ZERO) wait += interval // overran a tick: skip it
            delay(wait)
        }
    }

    companion object {
        val DEFAULT_INTERVAL: Duration = il.kavmonitor.shared.trigger.Polling.INTERVAL
        val MIN_GAP_ON_TARGET_CHANGE: Duration = 10.seconds
    }
}
