package il.kavmonitor.shared.trigger

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** All tunables of the watch/trigger logic in one place. See README "Trigger logic" for the evidence. */
data class TriggerConfig(
    /** Poll period: one curlbus call per poll. */
    val pollInterval: Duration = 35.seconds,
    /** A GPS fix older than this is not used for any decision. */
    val gpsFreshMaxAge: Duration = 3.minutes,

    // ---- departs origin ----
    /**
     * A bus that drops off the origin's visit list counts as departed only if this is no earlier than
     * (scheduled origin departure - [departEarlyTolerance]). Earlier drops are treated as re-planning/cancellation.
     */
    val departEarlyTolerance: Duration = 3.minutes,
    /** A bus still listed at the origin but with a fresh GPS fix this far from the origin stop counts as departed. */
    val departDistanceMeters: Double = 300.0,

    // ---- arrives at stop ----
    /** Fresh GPS within this radius of the chosen stop → "at the stop". */
    val arriveRadiusMeters: Double = 200.0,
    /** Live (GPS-tracked) bus whose ETA to the stop is at most this → "arriving". */
    val arriveEtaThreshold: Duration = 1.minutes,
    /** Bus dropped off the stop's list while its last ETA was within this window → it reached/passed the stop. */
    val passedEtaWindow: Duration = 3.minutes,
) {
    companion object {
        val Default = TriggerConfig()
        const val DEFAULT_DURATION_MINUTES = 60
        const val MAX_DURATION_MINUTES = 120
    }
}
