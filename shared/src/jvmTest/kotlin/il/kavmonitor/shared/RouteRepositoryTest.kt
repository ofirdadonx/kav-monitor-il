package il.kavmonitor.shared

import il.kavmonitor.shared.data.RouteRepository
import il.kavmonitor.shared.data.StaticDataSource
import il.kavmonitor.shared.model.Direction
import il.kavmonitor.shared.model.StopList
import il.kavmonitor.shared.model.StopListSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RouteRepositoryTest {
    private val dir = Direction(37121, "194", "x<->y-1#", "אגד", 3, "1", "#", 9696855, "2026-10-06")

    @Test fun concurrentStopRequestsShareOneCallAndFailuresAreRetried() = runTest {
        var calls = 0
        var fail = true
        val gate = CompletableDeferred<Unit>()
        val static = object : StaticDataSource {
            override suspend fun directions(lineNumber: String, date: String) = listOf(dir)
            override suspend fun stops(direction: Direction): StopList {
                calls++
                gate.await()
                if (fail) throw IllegalStateException("boom")
                return StopList(direction.lineRef, stops194(), StopListSource.GTFS_TODAY, direction.date)
            }
        }
        val repo = RouteRepository(static, MutableClock(ilTime("12:00:00")), CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        val a = async { runCatching { repo.stops(dir) } }
        val b = async { runCatching { repo.stops(dir) } }
        testScheduler.runCurrent()
        gate.complete(Unit)
        listOf(a, b).awaitAll().forEach { assertFailsWith<IllegalStateException> { it.getOrThrow() } }
        assertEquals(1, calls, "card prefetch + selection share one in-flight call")

        fail = false
        assertEquals(28, repo.stops(dir).stops.size) // failure not cached
        assertEquals(28, repo.stops(dir).stops.size) // success cached
        assertEquals(2, calls)
    }
}
