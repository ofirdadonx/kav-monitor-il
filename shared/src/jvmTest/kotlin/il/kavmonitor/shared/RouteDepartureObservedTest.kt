package il.kavmonitor.shared

import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.StopSnapshot
import il.kavmonitor.shared.route.RouteLiveFetcher
import il.kavmonitor.shared.route.RouteLiveRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** "Actually departed" is shown only when the route view saw the bus at the origin and then live and gone from it. */
class RouteDepartureObservedTest {
    private val stops = stops194()
    private val sched = ilTime("16:00:00")

    /** Bus X: listed at the origin (no GPS) while [atOrigin], else live and listed only at the stops after the origin. */
    private class Scripted(val clock: MutableClock) : LiveDataSource {
        var atOrigin = true
        var originFails = false
        override suspend fun stopVisits(stopCode: String): StopSnapshot {
            if (stopCode == "20001" && originFails) throw IllegalStateException("500")
            val v = LiveVisit(stopCode, "194", 37121, 3, "veh1", "tripX", clock.now() + 600.seconds, ilTime("16:00:00"),
                if (atOrigin) ilTime("05:00:00") else clock.now() - 20.seconds,
                if (atOrigin) null else 32.0800, if (atOrigin) null else 34.7930)
            val other = LiveVisit(stopCode, "1", 1, 3, "o", "o", clock.now(), null, null, null, null)
            val list = if (stopCode == "20001") (if (atOrigin) listOf(v, other) else listOf(other)) else listOf(v, other)
            return StopSnapshot(stopCode, clock.now(), null, list)
        }
    }

    @Test
    fun departureObservedBetweenTwoRefreshes() = runTest {
        val clock = MutableClock(ilTime("16:00:30"))
        val live = Scripted(clock)
        val f = RouteLiveFetcher(live, RouteLiveRequest(37121, "194", 3, stops), clock)
        assertNull(f.refresh().buses.single { it.key == "tripX" }.departedObservedAt)
        clock.now = ilTime("16:01:15"); live.atOrigin = false
        val bus = f.refresh().buses.single { it.key == "tripX" }
        assertEquals(ilTime("16:01:15"), bus.departedObservedAt)
        assertEquals(sched, bus.scheduledStart)
        clock.now = ilTime("16:02:00")
        assertEquals(ilTime("16:01:15"), f.refresh().buses.single { it.key == "tripX" }.departedObservedAt, "kept")
    }

    @Test
    fun notObservedWhenTheGapIsTooLongOrTheOriginWasNotPolled() = runTest {
        val clock = MutableClock(ilTime("16:00:30"))
        val live = Scripted(clock)
        val f = RouteLiveFetcher(live, RouteLiveRequest(37121, "194", 3, stops), clock)
        f.refresh()
        clock.now = ilTime("16:03:30"); live.atOrigin = false // 3 min later (> 150 s): we didn't see when it left
        assertNull(f.refresh().buses.single { it.key == "tripX" }.departedObservedAt)

        val clock2 = MutableClock(ilTime("16:00:30"))
        val live2 = Scripted(clock2)
        val f2 = RouteLiveFetcher(live2, RouteLiveRequest(37121, "194", 3, stops), clock2)
        f2.refresh()
        clock2.now = ilTime("16:01:15"); live2.atOrigin = false; live2.originFails = true
        // origin (and its neighbours) unavailable → the fetcher may use a neighbour, never infer a departure from it
        val b = f2.refresh().buses.single { it.key == "tripX" }
        assertNull(b.departedObservedAt)
    }

    @Suppress("unused") private fun unused(i: Instant) = i
}
