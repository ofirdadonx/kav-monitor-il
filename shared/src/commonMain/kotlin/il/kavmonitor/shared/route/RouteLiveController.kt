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
) {
    var state by mutableStateOf<RouteLiveState?>(null); private set

    private var fetcher: RouteLiveFetcher? = null
    private var runningFetcher: RouteLiveFetcher? = null
    private var job: Job? = null
    private var tokens = 0
    private var attachedToken: Int? = null
    private var appVisible = true

    /** Number of completed refresh cycles (tests/diagnostics). */
    var refreshes = 0; private set
    val isRefreshing: Boolean get() = job?.isActive == true

    fun attach(request: RouteLiveRequest): Int {
        val token = ++tokens
        attachedToken = token
        if (fetcher?.request != request) {
            val f = RouteLiveFetcher(live, request, clock)
            fetcher = f
            val keep = state?.takeIf { it.request.lineRef == request.lineRef }?.snapshot
            state = RouteLiveState(request, f.sampleIndices.size, snapshot = keep)
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
            job?.cancel(); job = null; runningFetcher = null
            state?.let { if (it.loading) state = it.copy(loading = false) }
            return
        }
        if (job?.isActive == true && runningFetcher === f) return
        job?.cancel()
        runningFetcher = f
        job = scope.launch { loop(f!!) }
    }

    private suspend fun loop(f: RouteLiveFetcher) {
        // Same line already fetched recently (screen re-entered, or only the target stop changed): don't burst.
        val last = state?.takeIf { it.request.lineRef == f.request.lineRef }?.snapshot
        if (last != null) {
            val gap = if (last.targetStopCode == f.request.targetStopCode) interval else MIN_GAP_ON_TARGET_CHANGE
            val wait = gap - (clock.now() - last.fetchedAt)
            if (wait > Duration.ZERO) delay(wait)
        }
        while (scope.isActive) {
            state = (state ?: RouteLiveState(f.request, f.sampleIndices.size)).copy(loading = true)
            state = try {
                val snap = f.refresh()
                state!!.copy(snapshot = snap, loading = false, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                il.kavmonitor.shared.data.ErrorLog.w("route view refresh failed", e)
                state!!.copy(loading = false, error = il.kavmonitor.shared.data.FetchErrors.classify(e).kind.name)
            }
            refreshes++
            delay(interval)
        }
    }

    companion object {
        val DEFAULT_INTERVAL: Duration = 45.seconds
        val MIN_GAP_ON_TARGET_CHANGE: Duration = 10.seconds
    }
}
