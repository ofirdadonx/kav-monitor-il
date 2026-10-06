package il.kavmonitor.shared.stop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import il.kavmonitor.shared.data.ErrorLog
import il.kavmonitor.shared.data.FetchErrors
import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.model.Operators
import il.kavmonitor.shared.model.StopSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** One upcoming bus at a stop (all lines). */
data class StopArrival(
    val key: String,
    val lineName: String,
    val lineRef: Int?,
    val operatorRef: Int?,
    val operatorName: String,
    val destination: String?,
    val eta: Instant,
    /** Live GPS-tracked prediction (fix within [StopArrivals.LIVE_MAX_AGE]); false = scheduled only. */
    val live: Boolean,
) {
    fun minutes(now: Instant): Int = ceil((eta - now).inWholeSeconds.coerceAtLeast(0) / 60.0).toInt()
}

object StopArrivals {
    /** A GPS fix older than this is not "live". */
    val LIVE_MAX_AGE: Duration = 3.minutes
    /** Still shown this long after the predicted time (the bus may be at the stop). */
    val GRACE: Duration = 1.minutes

    fun from(snap: StopSnapshot, now: Instant): List<StopArrival> =
        snap.allVisits.filter { it.eta != null && it.eta >= now - GRACE && !it.lineName.isNullOrBlank() }
            .groupBy { it.key + "|" + it.lineName }.values.map { g -> g.maxBy { if (it.hasGps) 1 else 0 } }
            .map { v ->
                StopArrival(
                    key = v.key + "|" + v.lineName,
                    lineName = v.lineName!!,
                    lineRef = v.lineRef,
                    operatorRef = v.operatorRef,
                    operatorName = v.operatorRef?.let { Operators.displayName(it, v.agencyName) } ?: v.agencyName.orEmpty(),
                    destination = v.destinationName,
                    eta = v.eta!!,
                    live = v.hasGps && v.recordedAt != null && now - v.recordedAt <= LIVE_MAX_AGE,
                )
            }
            .sortedWith(compareBy({ it.eta }, { it.lineName }))
}

data class StopScreenState(
    val stopCode: String,
    val stopName: String,
    val snapshot: StopSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * The stop screen's live arrivals: ONE `/<stop>` request every [interval], only while the screen is shown
 * ([attach]/[detach]) and the app is visible. Same no-burst rule as the route view.
 */
class StopArrivalsController(
    private val live: LiveDataSource,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    val interval: Duration = il.kavmonitor.shared.trigger.Polling.INTERVAL,
) {
    var state by mutableStateOf<StopScreenState?>(null); private set
    private var job: Job? = null
    private var attached: Int? = null
    private var tokens = 0
    private var appVisible = true
    var requests = 0; private set
    val isRefreshing: Boolean get() = job?.isActive == true

    fun attach(stopCode: String, stopName: String): Int {
        val t = ++tokens
        attached = t
        if (state?.stopCode != stopCode) state = StopScreenState(stopCode, stopName)
        reconcile(restart = true)
        return t
    }

    fun detach(token: Int) {
        if (attached == token) { attached = null; reconcile() }
    }

    fun setAppVisible(visible: Boolean) { appVisible = visible; reconcile() }

    private fun reconcile(restart: Boolean = false) {
        val run = attached != null && appVisible && state != null
        if (!run) { job?.cancel(); job = null; return }
        if (job?.isActive == true && !restart) return
        job?.cancel()
        job = scope.launch { loop() }
    }

    private suspend fun loop() {
        val code = state?.stopCode ?: return
        state?.snapshot?.let { last ->
            val wait = interval - (clock.now() - last.fetchedAt)
            if (wait > Duration.ZERO) delay(wait)
        }
        while (scope.isActive) {
            val started = clock.now()
            state = state?.copy(loading = true)
            state = try {
                requests++
                val snap = live.stopVisits(code)
                state?.copy(snapshot = snap, loading = false, error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.w("stop screen refresh failed ($code)", e)
                state?.copy(loading = false, error = FetchErrors.classify(e, code).kind.name)
            }
            var wait = interval - (clock.now() - started)
            while (wait <= Duration.ZERO) wait += interval
            delay(wait)
        }
    }
}
