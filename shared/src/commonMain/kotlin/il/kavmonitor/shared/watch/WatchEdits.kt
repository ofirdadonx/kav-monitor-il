package il.kavmonitor.shared.watch

import il.kavmonitor.shared.model.WatchSpec
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Edits of the running watch that must not restart it (no lost state, no second poller): extra minutes on top of
 * [WatchSpec.durationMinutes]. The engine re-reads the end time on every loop turn; the service applies an edit and
 * refreshes the notification/wake lock right away. (Walk-time edits go through [LeavePlanHolder].)
 */
object WatchEdits {
    /** Upper bound of a watch including extensions. */
    const val MAX_TOTAL_MINUTES = 240
    val EXTEND_CHOICES = listOf(15, 30, 60)

    private class Entry(val key: String, val extraMinutes: Int)

    @Volatile private var entry: Entry? = null

    fun extraMinutes(spec: WatchSpec): Int = entry?.takeIf { it.key == spec.recentKey }?.extraMinutes ?: 0

    fun endsAt(spec: WatchSpec, startedAt: Instant): Instant = startedAt + (spec.durationMinutes + extraMinutes(spec)).minutes

    /** Adds [minutes] (capped at [MAX_TOTAL_MINUTES] in total); returns the extra minutes now in effect. */
    fun extend(spec: WatchSpec, minutes: Int): Int {
        val n = (extraMinutes(spec) + minutes).coerceIn(0, (MAX_TOTAL_MINUTES - spec.durationMinutes).coerceAtLeast(0))
        entry = Entry(spec.recentKey, n)
        return n
    }

    fun canExtend(spec: WatchSpec): Boolean = spec.durationMinutes + extraMinutes(spec) < MAX_TOTAL_MINUTES

    fun clear() { entry = null }
}
