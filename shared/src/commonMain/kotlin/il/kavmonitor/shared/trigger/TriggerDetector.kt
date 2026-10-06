package il.kavmonitor.shared.trigger

import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.util.Geo
import kotlin.time.Instant

enum class TriggerReason {
    /** Bus was listed at the origin and dropped off the list around/after its scheduled departure. */
    LEFT_ORIGIN_DROPPED,
    /** Bus is still listed at the origin but its fresh GPS fix is already away from the origin stop. */
    LEFT_ORIGIN_GPS,
    /** Fresh GPS fix within the radius of the chosen stop. */
    AT_STOP_GPS,
    /** GPS-tracked bus with ETA ≤ threshold. */
    AT_STOP_ETA,
    /** Bus dropped off the chosen stop's list while its ETA was imminent (passed between polls). */
    PASSED_STOP,
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
    private val fired = mutableSetOf<String>()
    private val seenKeys = mutableSetOf<String>()

    /** Distinct buses (trips) seen for this line at the watched stop since the watch started. */
    val busesSeen: Int get() = seenKeys.size

    /**
     * @param visits visits of the watched line at the watched stop (already filtered).
     * @param reliable false when the response looks broken (e.g. the stop came back with no visits at all,
     *   or the source reported errors): presence is still used, but disappearances are ignored.
     */
    fun onSnapshot(now: Instant, visits: List<LiveVisit>, reliable: Boolean = true): List<TriggerEvent> {
        val events = mutableListOf<TriggerEvent>()
        val presentKeys = visits.map { it.key }.toSet()

        for (v in visits) {
            seenKeys += v.key
            if (v.key !in fired) {
                val ev = when (spec.trigger) {
                    TriggerType.DEPARTS_ORIGIN -> checkDepartedWhileListed(now, v)
                    TriggerType.ARRIVES_AT_STOP -> checkArrivingWhileListed(now, v)
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
                }
                if (ev != null) { fired += key; events += ev }
            }
        }
        return events
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

    private fun checkDroppedFromOrigin(now: Instant, last: LiveVisit): TriggerEvent? =
        if (pastDepartureWindow(now, last)) event(last, TriggerReason.LEFT_ORIGIN_DROPPED, now, null) else null

    private fun checkArrivingWhileListed(now: Instant, v: LiveVisit): TriggerEvent? {
        if (!freshGps(now, v)) return null
        val d = distanceTo(v, spec.stop.lat, spec.stop.lon)
        if (d != null && d <= config.arriveRadiusMeters) return event(v, TriggerReason.AT_STOP_GPS, now, d)
        val eta = v.eta ?: return null
        return if (eta - now <= config.arriveEtaThreshold) event(v, TriggerReason.AT_STOP_ETA, now, d) else null
    }

    private fun checkDroppedFromStop(now: Instant, last: LiveVisit, lastSeenAt: Instant): TriggerEvent? {
        val eta = last.eta ?: return null
        return if (eta - lastSeenAt <= config.passedEtaWindow) event(last, TriggerReason.PASSED_STOP, now, null) else null
    }

    private fun event(v: LiveVisit, reason: TriggerReason, now: Instant, d: Double?) = TriggerEvent(
        key = v.key, vehicleRef = v.vehicleRef, tripId = v.tripId, reason = reason, at = now,
        scheduledDeparture = v.originDepartureTime, eta = v.eta, distanceMeters = d,
    )
}
