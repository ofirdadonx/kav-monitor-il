package il.kavmonitor.shared.watch

import il.kavmonitor.shared.data.LiveDataSource
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
) {
    suspend fun run(
        spec: WatchSpec,
        onState: (WatchState) -> Unit,
        onTrigger: (TriggerEvent, WatchState) -> Unit,
    ): WatchState {
        val detector = TriggerDetector(spec, config)
        val start = clock.now()
        var state = WatchState(spec, WatchPhase.RUNNING, start, start + spec.durationMinutes.minutes)
        onState(state)
        while (true) {
            val now = clock.now()
            if (now >= state.endsAt) break
            state = try {
                val snap = live.stopVisits(spec.stop.code)
                val visits = snap.forLine(spec.lineRef, spec.lineName, spec.operatorRef)
                val reliable = snap.sourceErrors == null && snap.allVisits.isNotEmpty()
                val polledAt = clock.now()
                val events = detector.onSnapshot(polledAt, visits, reliable)
                val alertedKeys = (state.alerts + events).map { it.key }.toSet()
                val buses = uniqueByKey(visits).sortedBy { it.eta ?: it.originDepartureTime }.map { v ->
                    val fresh = v.hasGps && v.recordedAt != null && polledAt - v.recordedAt <= config.gpsFreshMaxAge
                    BusView(
                        key = v.key, vehicleRef = v.vehicleRef, tripId = v.tripId, eta = v.eta,
                        scheduledDeparture = v.originDepartureTime, recordedAt = v.recordedAt, hasFreshGps = fresh,
                        distanceToStopMeters = if (v.lat != null && v.lon != null && spec.stop.lat != null && spec.stop.lon != null)
                            Geo.distanceMeters(v.lat, v.lon, spec.stop.lat, spec.stop.lon) else null,
                        alerted = v.key in alertedKeys,
                        lat = v.lat, lon = v.lon,
                    )
                }
                val next = state.copy(
                    lastAttemptAt = polledAt, lastSuccessAt = polledAt,
                    lastError = snap.sourceErrors?.let { "curlbus: $it" },
                    buses = buses, busesSeen = detector.busesSeen,
                    alerts = state.alerts + events, polls = state.polls + 1,
                )
                events.forEach { onTrigger(it, next) }
                next
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.copy(lastAttemptAt = clock.now(), lastError = e.shortMessage(), polls = state.polls + 1)
            }
            onState(state)
            val remaining = state.endsAt - clock.now()
            if (remaining <= kotlin.time.Duration.ZERO) break
            delay(minOf(config.pollInterval, remaining))
        }
        state = state.copy(phase = WatchPhase.TIMED_OUT)
        onState(state)
        return state
    }

    private fun Exception.shortMessage(): String =
        (this::class.simpleName ?: "Error") + (message?.let { ": " + it.lineSequence().first().take(120) } ?: "")

    companion object {
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
