package il.kavmonitor.shared.data

import il.kavmonitor.shared.model.Direction
import il.kavmonitor.shared.model.StopList

/** Static (scheduled) data: lines, directions and stop sequences. Implemented by Stride today. */
interface StaticDataSource {
    /** Directions of [lineNumber] valid on [date] (yyyy-MM-dd, Israel), all operators. */
    suspend fun directions(lineNumber: String, date: String): List<Direction>

    /** Ordered stop list for a direction. */
    suspend fun stops(direction: Direction): StopList
}
