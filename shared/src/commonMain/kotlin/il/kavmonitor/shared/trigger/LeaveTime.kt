package il.kavmonitor.shared.trigger

import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Instant

/** Walking time from a straight-line distance: distance × detour factor ÷ walking speed, rounded up to whole minutes. */
object WalkTime {
    fun minutes(
        straightLineMeters: Double,
        detourFactor: Double = TriggerConfig.WALK_DETOUR_FACTOR,
        speedMetersPerMinute: Double = TriggerConfig.WALK_SPEED_M_PER_MIN,
    ): Int {
        if (straightLineMeters <= 0.0) return 0
        // tiny epsilon so 80.0000001 m doesn't become 2 min through float noise
        return ceil(straightLineMeters * detourFactor / speedMetersPerMinute - 1e-9).toInt().coerceIn(0, TriggerConfig.MAX_WALK_MINUTES)
    }
}

enum class LeaveStatus {
    /** Leave time is in the future. */
    WAIT,
    /** Leave time reached (alert fired or about to fire on this poll). */
    LEAVE_NOW,
    /** A "leave now" alert was sent for this bus. */
    ALERTED,
    /** Leave time is more than [TriggerConfig.leaveGrace] in the past: can't make it. */
    NOT_CATCHABLE,
    /** No ETA at the boarding stop. */
    NO_ETA,
}

data class LeaveInfo(val leaveAt: Instant?, val status: LeaveStatus)

object LeavePlanner {
    fun leaveAt(eta: Instant, lead: Duration): Instant = eta - lead

    fun catchable(leaveAt: Instant, now: Instant, grace: Duration): Boolean = leaveAt >= now - grace

    fun info(eta: Instant?, lead: Duration, now: Instant, alerted: Boolean, grace: Duration): LeaveInfo {
        if (eta == null) return LeaveInfo(null, if (alerted) LeaveStatus.ALERTED else LeaveStatus.NO_ETA)
        val at = leaveAt(eta, lead)
        val status = when {
            alerted -> LeaveStatus.ALERTED
            !catchable(at, now, grace) -> LeaveStatus.NOT_CATCHABLE
            now >= at -> LeaveStatus.LEAVE_NOW
            else -> LeaveStatus.WAIT
        }
        return LeaveInfo(at, status)
    }
}
