package il.kavmonitor.shared.data

import il.kavmonitor.shared.model.Direction
import il.kavmonitor.shared.model.StopList
import il.kavmonitor.shared.util.todayInIsrael
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Resolves directions/stops once per (line, day) / (line_ref, day) and caches them in memory. Concurrent requests for
 * the same key share one in-flight call (the direction cards prefetch stop lists in parallel; selecting a card while its
 * prefetch is running must not fetch again). Failures are not cached.
 */
class RouteRepository(
    private val static: StaticDataSource,
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    private val mutex = Mutex()
    private val directionsCache = mutableMapOf<String, Deferred<List<Direction>>>()
    private val stopsCache = mutableMapOf<String, Deferred<StopList>>()

    fun today(): String = todayInIsrael(clock).toString()

    suspend fun directions(lineNumber: String): List<Direction> {
        val line = lineNumber.trim()
        return shared(directionsCache, "$line@${today()}") { static.directions(line, today()) }
    }

    suspend fun stops(direction: Direction): StopList =
        shared(stopsCache, "${direction.lineRef}@${direction.date}") { static.stops(direction) }

    /** Already-loaded stop list (no network), or null. */
    suspend fun cachedStops(direction: Direction): StopList? = mutex.withLock {
        stopsCache["${direction.lineRef}@${direction.date}"]?.takeIf { it.isCompleted && !it.isCancelled }
    }?.let { runCatching { it.await() }.getOrNull() }

    /** Stop list for a saved watch (recents/watch screen): finds today's direction by line_ref. */
    suspend fun stopsForLine(lineRef: Int, lineName: String): StopList {
        val d = directions(lineName).firstOrNull { it.lineRef == lineRef }
            ?: throw IllegalStateException("line_ref $lineRef not found for line $lineName today")
        return stops(d)
    }

    private suspend fun <T> shared(cache: MutableMap<String, Deferred<T>>, key: String, load: suspend () -> T): T {
        val d = mutex.withLock {
            cache[key] ?: scope.async { load() }.also { cache[key] = it }
        }
        return try {
            d.await()
        } catch (e: Exception) {
            // drop failed loads (not a caller's own cancellation while the shared load is still running)
            if (d.isCompleted) mutex.withLock { if (cache[key] === d) cache.remove(key) }
            throw e
        }
    }
}
