package il.kavmonitor.shared.data

import il.kavmonitor.shared.model.Direction
import il.kavmonitor.shared.model.StopList
import il.kavmonitor.shared.util.todayInIsrael
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Resolves directions/stops once per (line, day) / (line_ref, day) and caches them in memory. */
class RouteRepository(
    private val static: StaticDataSource,
    private val clock: Clock = Clock.System,
) {
    private val mutex = Mutex()
    private val directionsCache = mutableMapOf<String, List<Direction>>()
    private val stopsCache = mutableMapOf<String, StopList>()

    fun today(): String = todayInIsrael(clock).toString()

    suspend fun directions(lineNumber: String): List<Direction> {
        val key = "${lineNumber.trim()}@${today()}"
        mutex.withLock { directionsCache[key] }?.let { return it }
        val result = static.directions(lineNumber.trim(), today())
        mutex.withLock { directionsCache[key] = result }
        return result
    }

    suspend fun stops(direction: Direction): StopList {
        val key = "${direction.lineRef}@${direction.date}"
        mutex.withLock { stopsCache[key] }?.let { return it }
        val result = static.stops(direction)
        mutex.withLock { stopsCache[key] = result }
        return result
    }
}
