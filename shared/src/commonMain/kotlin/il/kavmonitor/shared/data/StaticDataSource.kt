package il.kavmonitor.shared.data

import il.kavmonitor.shared.model.Direction
import il.kavmonitor.shared.model.StopList
import kotlin.time.Instant

/** The next published departure of one direction, if the feed has one. [time] is HH:mm in Israel, or null when only the day is known. */
data class NextDeparture(val date: String, val time: String?)

/** Static (scheduled) data: lines, directions and stop sequences. Implemented by Stride today. */
interface StaticDataSource {
    /**
     * Directions of [lineNumber]. Prefers [date]. If that day has no rows, returns the latest published
     * definition (marked [Direction.runsToday] false) so the line can still be opened.
     */
    suspend fun directions(lineNumber: String, date: String): List<Direction>

    /** Ordered stop list for a direction. */
    suspend fun stops(direction: Direction): StopList

    /** Next departure of [lineRef] on or after [fromDate]. Null when the feed has no later day. */
    suspend fun nextDeparture(lineRef: Int, fromDate: String, now: Instant): NextDeparture? = null
}
