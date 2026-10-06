package il.kavmonitor.shared.trigger

import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.util.Geo
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

enum class TriggerReason {
    /** Bus was listed at the origin and dropped off the list around/after its scheduled departure. */
    LEFT_ORIGIN_DROPPED,
    /** Bus is still listed at the origin but its fresh GPS fix is already away from the origin stop. */
    LEFT_ORIGIN_GPS,
    /**
     * Trip dropped off the origin list well before its scheduled departure (driver activated the trip early);
     * fired when the scheduled departure time is reached.
     */
    LEFT_ORIGIN_SCHEDULED,
    /** Fresh GPS fix within the radius of the chosen stop. */
    AT_STOP_GPS,
    /** GPS-tracked bus with ETA ≤ threshold. */
    AT_STOP_ETA,
    /**
     * Bus dropped off the chosen stop's upcoming-visits list. Observed live: SIRI removes a visit when the bus
     * is at/near the stop (≈ within 100 m / 1 min), so for a live trip this means "arrived now or just passed".
     */
    DROPPED_AT_STOP,
    /** Time to leave: now ≥ ETA at the boarding stop − walk time − buffer, for the next bus that can still be caught. */
    LEAVE_NOW,
}

data class TriggerEvent(
    val key: String,
    val vehicleRef: String?,
    val tripId: String?,
    val reason: TriggerReason,
    val at: Instant,
    val scheduledDeparture: Instant?,
    val eta: Instant?,
    val distanceMeters: Double?,
    /** Whether the bus had a fresh GPS fix (live ETA) when this fired; false = schedule-based ETA. */
    val live: Boolean = false,
    /** LEAVE_NOW: the walk+buffer lead (minutes) used. */
    val leadMinutes: Int? = null,
)

/**
 * Pure, stateful trigger logic. Feed it the line-filtered visits of the watched stop after each successful
 * poll; it returns the triggers that fired on this poll. Each bus (trip) fires at most once per watch.
 */
class TriggerDetector(
    private val spec: WatchSpec,
    private val config: TriggerConfig = TriggerConfig.Default,
) {
    private class Tracked(var last: LiveVisit, var lastSeenAt: Instant)

    private val tracked = mutableMapOf<String, Tracked>()
    /** DEPARTS_ORIGIN: trips that dropped off the origin list too early; fire at their scheduled departure. */
    private val pendingEarly = mutableMapOf<String, LiveVisit>()
    private val fired = mutableSetOf<String>()
    private val seenKeys = mutableSetOf<String>()

    /** Distinct buses (trips) seen for this line at the watched stop since the watch started. */
    val busesSeen: Int get() = seenKeys.size

    /**
     * @param visits visits of the watched line at the watched stop (already filtered).
     * @param reliable false when the response looks broken (e.g. the stop came back with no visits at all,
     *   or the source reported errors): presence is still used, but disappearances are ignored.
     */
    fun onSnapshot(now: Instant, visits: List<LiveVisit>, reliable: Boolean = true, leadMinutes: Int = 0): List<TriggerEvent> {
        if (spec.trigger == TriggerType.LEAVE_FOR_STOP) return onLeaveSnapshot(now, visits, leadMinutes)
        val events = mutableListOf<TriggerEvent>()
        val presentKeys = visits.map { it.key }.toSet()

        for (v in visits) {
            seenKeys += v.key
            pendingEarly.remove(v.key) // listed again: back to normal tracking
            if (v.key !in fired) {
                val ev = when (spec.trigger) {
                    TriggerType.DEPARTS_ORIGIN -> checkDepartedWhileListed(now, v)
                    TriggerType.ARRIVES_AT_STOP -> checkArrivingWhileListed(now, v)
                    TriggerType.LEAVE_FOR_STOP -> null
                }
                if (ev != null) { fired += v.key; events += ev }
            }
            tracked[v.key] = Tracked(v, now)
        }

        if (reliable) {
            val gone = tracked.keys.filter { it !in presentKeys }
            for (key in gone) {
                val t = tracked.remove(key) ?: continue
                if (key in fired) continue
                val ev = when (spec.trigger) {
                    TriggerType.DEPARTS_ORIGIN -> checkDroppedFromOrigin(now, t.last)
                    TriggerType.ARRIVES_AT_STOP -> checkDroppedFromStop(now, t.last, t.lastSeenAt)
                    TriggerType.LEAVE_FOR_STOP -> null
                }
                if (ev != null) { fired += key; events += ev }
            }
        }

        // Early-activated trips whose scheduled departure time has now been reached.
        val due = pendingEarly.values.filter { v -> v.originDepartureTime.let { it == null || now >= it } }
        for (v in due) {
            pendingEarly.remove(v.key)
            if (fired.add(v.key)) events += event(v, TriggerReason.LEFT_ORIGIN_SCHEDULED, now, null)
        }
        return events
    }

    private var lastLeaveAlertAt: Instant? = null

    /** Whether a "leave now" alert was already sent for this bus. */
    fun alerted(key: String): Boolean = key in fired

    /** Leave time / status of one bus for display (same rules as the alert). */
    fun leaveInfo(v: LiveVisit, now: Instant, leadMinutes: Int): LeaveInfo =
        LeavePlanner.info(v.eta, leadMinutes.minutes, now, v.key in fired, config.leaveGrace)

    /**
     * LEAVE_FOR_STOP. Uses the newest ETA every poll (ETAs of not-started trips are schedule-based and get re-evaluated
     * as they update). Each bus alerts at most once: an ETA that moves later after the alert never re-alerts; an ETA that
     * moves earlier for a not-yet-alerted bus simply brings its alert forward. Only the next catchable bus can fire, and
     * not within [TriggerConfig.leaveMinAlertGap] of the previous alert. Buses whose leave time is already more than
     * [TriggerConfig.leaveGrace] in the past are not catchable and never alert.
     */
    private fun onLeaveSnapshot(now: Instant, visits: List<LiveVisit>, leadMinutes: Int): List<TriggerEvent> {
        val lead = leadMinutes.minutes
        val unique = visits.groupBy { it.key }.values.map { g -> g.maxWith(compareBy({ it.hasGps }, { it.recordedAt ?: Instant.DISTANT_PAST })) }
        unique.forEach { seenKeys += it.key }
        val next = unique
            .filter { it.key !in fired && it.eta != null }
            .filter { LeavePlanner.catchable(LeavePlanner.leaveAt(it.eta!!, lead), now, config.leaveGrace) }
            .minByOrNull { it.eta!! } ?: return emptyList()
        val leaveAt = LeavePlanner.leaveAt(next.eta!!, lead)
        if (now < leaveAt) return emptyList()
        val last = lastLeaveAlertAt
        if (last != null && now - last < config.leaveMinAlertGap) return emptyList()
        fired += next.key
        lastLeaveAlertAt = now
        return listOf(
            event(next, TriggerReason.LEAVE_NOW, now, distanceTo(next, spec.stop.lat, spec.stop.lon))
                .copy(live = freshGps(now, next), leadMinutes = leadMinutes),
        )
    }

    private fun freshGps(now: Instant, v: LiveVisit): Boolean {
        if (!v.hasGps) return false
        val rec = v.recordedAt ?: return false
        return now - rec <= config.gpsFreshMaxAge
    }

    private fun distanceTo(v: LiveVisit, lat: Double?, lon: Double?): Double? =
        if (v.lat != null && v.lon != null && lat != null && lon != null) Geo.distanceMeters(v.lat, v.lon, lat, lon) else null

    private fun pastDepartureWindow(now: Instant, v: LiveVisit): Boolean {
        val sched = v.originDepartureTime ?: return true // unknown schedule: trust the drop
        return now >= sched - config.departEarlyTolerance
    }

    private fun checkDepartedWhileListed(now: Instant, v: LiveVisit): TriggerEvent? {
        if (!freshGps(now, v) || !pastDepartureWindow(now, v)) return null
        val d = distanceTo(v, spec.origin.lat, spec.origin.lon) ?: return null
        return if (d >= config.departDistanceMeters) event(v, TriggerReason.LEFT_ORIGIN_GPS, now, d) else null
    }

    private fun checkDroppedFromOrigin(now: Instant, last: LiveVisit): TriggerEvent? {
        if (pastDepartureWindow(now, last)) return event(last, TriggerReason.LEFT_ORIGIN_DROPPED, now, null)
        pendingEarly[last.key] = last
        return null
    }

    private fun checkArrivingWhileListed(now: Instant, v: LiveVisit): TriggerEvent? {
        if (!freshGps(now, v)) return null
        val d = distanceTo(v, spec.stop.lat, spec.stop.lon)
        if (d != null && d <= config.arriveRadiusMeters) return event(v, TriggerReason.AT_STOP_GPS, now, d)
        val eta = v.eta ?: return null
        val notFar = d == null || d <= config.arriveEtaMaxDistanceMeters
        return if (eta - now <= config.arriveEtaThreshold && notFar) event(v, TriggerReason.AT_STOP_ETA, now, d) else null
    }

    /**
     * A trip that was already live (had GPS) only leaves a stop's list after passing it. A trip that never had GPS
     * counts only if its ETA was imminent when last seen (otherwise it is more likely a cancellation/re-plan).
     */
    private fun checkDroppedFromStop(now: Instant, last: LiveVisit, lastSeenAt: Instant): TriggerEvent? {
        val imminent = last.eta?.let { it - lastSeenAt <= config.passedEtaWindow } ?: false
        return if (last.hasGps || imminent) event(last, TriggerReason.DROPPED_AT_STOP, now, null) else null
    }

    private fun event(v: LiveVisit, reason: TriggerReason, now: Instant, d: Double?) = TriggerEvent(
        key = v.key, vehicleRef = v.vehicleRef, tripId = v.tripId, reason = reason, at = now,
        scheduledDeparture = v.originDepartureTime, eta = v.eta, distanceMeters = d,
    )
}
