package il.kavmonitor.shared.watch

import il.kavmonitor.shared.model.TriggerType

import il.kavmonitor.shared.data.BrokenStops
import il.kavmonitor.shared.data.ErrorKind
import il.kavmonitor.shared.data.ErrorLog
import il.kavmonitor.shared.data.FetchError
import il.kavmonitor.shared.data.FetchErrors
import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.data.StopServerException
import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.RouteStop
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.trigger.TriggerConfig
import il.kavmonitor.shared.trigger.TriggerDetector
import il.kavmonitor.shared.trigger.TriggerEvent
import il.kavmonitor.shared.util.Geo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * Runs one watch: polls the single relevant stop every [TriggerConfig.pollInterval] until the time limit,
 * feeds the [TriggerDetector], and reports state + triggers. Network errors are reported and polling continues.
 */
class WatchEngine(
    private val live: LiveDataSource,
    private val clock: Clock = Clock.System,
    private val config: TriggerConfig = TriggerConfig.Default,
    /** Stops curlbus can't answer (persisted with a TTL); null = no memory across runs. */
    private val brokenStops: BrokenStops? = null,
    /** The direction's stop list, needed to pick stand-in stops when the watched stop is broken. */
    private val stopsFor: (suspend (WatchSpec) -> List<RouteStop>)? = null,
) {
    private class Polled(val visits: List<LiveVisit>, val reliable: Boolean, val sourceErrors: String?)

    /** Stand-in stops that answered 5xx [BROKEN_AFTER_SERVER_ERRORS] times in a row during this run. */
    private val brokenThisRun = mutableSetOf<String>()
    private val standInErrors = mutableMapOf<String, Int>()
    private fun isBroken(code: String) = code in brokenThisRun || brokenStops?.isBroken(code) == true

    suspend fun run(
        spec: WatchSpec,
        onState: (WatchState) -> Unit,
        onTrigger: (TriggerEvent, WatchState) -> Unit,
    ): WatchState {
        val detector = TriggerDetector(spec, config)
        val start = clock.now()
        var state = WatchState(spec, WatchPhase.RUNNING, start, WatchEdits.endsAt(spec, start),
            leavePlan = if (spec.trigger == TriggerType.LEAVE_FOR_STOP) LeavePlanHolder.planFor(spec) else null)
        var fallback: FallbackPlan? = null
        var serverErrors = 0

        suspend fun planFallback(): FallbackPlan? {
            val stops = try {
                stopsFor?.invoke(spec)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.w("stop list for fallback failed", e); null
            } ?: return null
            return StopFallback.plan(spec.trigger, stops, spec.stop) { it == spec.stop.code || isBroken(it) }
        }

        if (brokenStops?.isBroken(spec.stop.code) == true) fallback = planFallback()
        state = state.copy(fallback = fallback)
        onState(state)
        while (true) {
            val now = clock.now()
            state = state.copy(endsAt = WatchEdits.endsAt(spec, start)) // "extend" edits apply without a restart
            if (now >= state.endsAt) break
            var retrySoon = false
            state = try {
                val fb = fallback
                val polled = if (fb == null) {
                    val snap = live.stopVisits(spec.stop.code)
                    serverErrors = 0
                    brokenStops?.clear(spec.stop.code)
                    Polled(snap.forLine(spec.lineRef, spec.lineName, spec.operatorRef), snap.sourceErrors == null && snap.allVisits.isNotEmpty(), snap.sourceErrors)
                } else {
                    pollFallback(spec, fb, clock.now()).also {
                        // a stand-in turned out broken too: pick the next working neighbours and poll them right away
                        if (fb.polled.any { s -> isBroken(s.code) }) {
                            fallback = planFallback() ?: fallback
                            retrySoon = true
                        }
                    }
                }
                val visits = polled.visits
                val polledAt = clock.now()
                val plan = if (spec.trigger == TriggerType.LEAVE_FOR_STOP) LeavePlanHolder.planFor(spec) else null
                val lead = plan?.leadMinutes ?: 0
                val events = detector.onSnapshot(polledAt, visits, polled.reliable, lead)
                val alertedKeys = (state.alerts + events).map { it.key }.toSet()
                val estimated = fb != null && spec.trigger != TriggerType.DEPARTS_ORIGIN
                val buses = uniqueByKey(visits).sortedBy { it.eta ?: it.originDepartureTime }.map { v ->
                    val fresh = v.hasGps && v.recordedAt != null && polledAt - v.recordedAt <= config.gpsFreshMaxAge
                    BusView(
                        key = v.key, vehicleRef = v.vehicleRef, tripId = v.tripId, eta = v.eta,
                        scheduledDeparture = v.originDepartureTime, recordedAt = v.recordedAt, hasFreshGps = fresh,
                        distanceToStopMeters = if (v.lat != null && v.lon != null && spec.stop.lat != null && spec.stop.lon != null)
                            Geo.distanceMeters(v.lat, v.lon, spec.stop.lat, spec.stop.lon) else null,
                        alerted = v.key in alertedKeys,
                        lat = v.lat, lon = v.lon,
                        etaEstimated = estimated,
                    ).let { b ->
                        if (plan == null) b else detector.leaveInfo(v, polledAt, lead).let { b.copy(leaveAt = it.leaveAt, leaveStatus = it.status) }
                    }
                }
                polled.sourceErrors?.let { ErrorLog.w("curlbus errors field: $it") }
                val next = state.copy(
                    lastAttemptAt = polledAt, lastSuccessAt = polledAt,
                    lastError = polled.sourceErrors?.let { FetchError(ErrorKind.SOURCE, spec.stop.code) },
                    buses = buses, busesSeen = detector.busesSeen,
                    alerts = state.alerts + events, polls = state.polls + 1,
                    leavePlan = plan, fallback = fallback,
                )
                events.forEach { onTrigger(it, next) }
                next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.w("poll failed (stop ${spec.stop.code}, fallback=${fallback?.polled?.map { it.code }})", e)
                val err = FetchErrors.classify(e, spec.stop.code)
                if (fallback == null && e is StopServerException && e.stopCode == spec.stop.code) {
                    serverErrors++
                    if (serverErrors >= BROKEN_AFTER_SERVER_ERRORS) {
                        brokenStops?.mark(spec.stop.code)
                        fallback = planFallback()
                    }
                    retrySoon = true // confirm quickly / switch to the stand-in stops right away
                } else if (fallback != null && fallback!!.polled.any { isBroken(it.code) }) {
                    fallback = planFallback() ?: fallback // every stand-in failed and one of them is now known broken
                    retrySoon = true
                }
                state.copy(lastAttemptAt = clock.now(), lastError = err, polls = state.polls + 1, fallback = fallback)
            }
            state = state.copy(endsAt = WatchEdits.endsAt(spec, start))
            onState(state)
            val remaining = state.endsAt - clock.now()
            if (remaining <= kotlin.time.Duration.ZERO) break
            delay(minOf(if (retrySoon) config.serverErrorRetry else config.pollInterval, remaining))
        }
        state = state.copy(phase = WatchPhase.TIMED_OUT)
        onState(state)
        return state
    }

    /** Polls the stand-in stop(s) (≤ 2 calls, in parallel). Fails only if every stand-in failed. */
    private suspend fun pollFallback(spec: WatchSpec, fb: FallbackPlan, now: kotlin.time.Instant): Polled {
        val results = coroutineScope {
            fb.polled.map { stop ->
                async {
                    try {
                        Result.success(live.stopVisits(stop.code))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        val byCode = fb.polled.zip(results).associate { (stop, r) -> stop.code to r }
        fb.polled.zip(results).forEach { (stop, r) ->
            val e = r.exceptionOrNull()
            if (e == null) {
                standInErrors.remove(stop.code)
            } else {
                ErrorLog.w("stand-in stop ${stop.code} failed", e)
                if (e is StopServerException) {
                    // a stand-in is replaced after its first 5xx in this run (another neighbour is as good);
                    // it is remembered across runs only after repeated 5xx (BrokenStopTracker / here)
                    brokenThisRun += stop.code
                    val n = (standInErrors[stop.code] ?: 0) + 1
                    standInErrors[stop.code] = n
                    if (n >= BROKEN_AFTER_SERVER_ERRORS) brokenStops?.mark(stop.code)
                }
            }
        }
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        val snaps = results.mapNotNull { it.getOrNull() }
        val reliable = results.all { it.isSuccess } && snaps.all { it.sourceErrors == null } && snaps.any { it.allVisits.isNotEmpty() }
        fun visitsAt(stop: RouteStop?) = stop?.let { byCode[it.code]?.getOrNull() }?.forLine(spec.lineRef, spec.lineName, spec.operatorRef)
        val visits = if (spec.trigger == TriggerType.DEPARTS_ORIGIN) {
            visitsAt(fb.downstream).orEmpty() // drop-off / GPS-distance rules on the next working stop
        } else {
            StopFallback.derive(fb, visitsAt(fb.upstream), visitsAt(fb.downstream), now, config.gpsFreshMaxAge)
        }
        return Polled(visits, reliable, snaps.firstNotNullOfOrNull { it.sourceErrors })
    }

    companion object {
        /** Consecutive HTTP 5xx answers from the watched stop before it is treated as broken. */
        const val BROKEN_AFTER_SERVER_ERRORS = 2

        /**
         * One entry per bus key. SIRI sometimes lists the same trip twice with different vehicles (seen 2026-10-06 14:51 at
         * 20001: trip 585301551 with vehicles 23400602 and 23401402); duplicate keys crash a keyed LazyColumn. Keeps the
         * visit with GPS / the newest record.
         */
        fun uniqueByKey(visits: List<il.kavmonitor.shared.model.LiveVisit>) = visits.groupBy { it.key }.values.map { group ->
            group.maxWith(compareBy({ it.hasGps }, { it.recordedAt ?: kotlin.time.Instant.DISTANT_PAST }))
        }
    }
}
