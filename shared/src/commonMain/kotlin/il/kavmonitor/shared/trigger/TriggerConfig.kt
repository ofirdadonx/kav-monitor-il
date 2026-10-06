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
    val arriveRadiusMeters: Double = 150.0,
    /** Live (GPS-tracked) bus whose ETA to the stop is at most this → "arriving". */
    val arriveEtaThreshold: Duration = 1.minutes,
    /**
     * ...but only if the bus is not obviously far away: SIRI ETAs are minute-granular and sometimes slide
     * (seen live: Azrieli ETA 10:09→10:10→10:11 while the bus crawled ~900 m away).
     */
    val arriveEtaMaxDistanceMeters: Double = 1000.0,
    /** Bus dropped off the stop's list while its last ETA was within this window → it reached/passed the stop. */
    val passedEtaWindow: Duration = 3.minutes,

    // ---- time to leave ----
    /** A bus whose leave time is more than this in the past can no longer be caught (shown as such, never alerted). */
    val leaveGrace: Duration = 1.minutes,
    /** After a "leave now" alert, another bus is alerted only if at least this much later (no back-to-back spam). */
    val leaveMinAlertGap: Duration = 3.minutes,
) {
    companion object {
        val Default = TriggerConfig()
        const val DEFAULT_DURATION_MINUTES = 60
        const val MAX_DURATION_MINUTES = 120

        /** Straight line → walking path (streets aren't straight). */
        const val WALK_DETOUR_FACTOR = 1.3
        /** Walking speed, meters per minute (≈ 4.8 km/h). */
        const val WALK_SPEED_M_PER_MIN = 80.0
        /** Extra minutes on top of the walk. */
        const val LEAVE_BUFFER_MINUTES = 1
        /** Walk time used when there's no location (user can change it). */
        const val DEFAULT_WALK_MINUTES = 5
        const val MAX_WALK_MINUTES = 60
    }
}
