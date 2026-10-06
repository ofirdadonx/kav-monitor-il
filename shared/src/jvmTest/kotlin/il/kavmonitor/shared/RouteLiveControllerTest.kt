package il.kavmonitor.shared

import il.kavmonitor.shared.route.RouteLiveController
import il.kavmonitor.shared.route.RouteLiveRequest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The live route view refreshes only while attached AND the app is visible; zero requests otherwise. */
class RouteLiveControllerTest {
    private val stops = stops194()
    private val req = RouteLiveRequest(37121, "194", 3, stops)

    private fun TestScope.setup(): Pair<ScanReplayLive, RouteLiveController> {
        val start = ilTime("14:52:20")
        val clock = object : Clock { override fun now(): Instant = start + testScheduler.currentTime.milliseconds }
        val live = ScanReplayLive()
        return live to RouteLiveController(live, backgroundScope, clock, interval = 45.seconds)
    }

    @Test fun refreshesEvery45sWhileAttachedAndStopsCompletelyOnDetach() = runTest {
        val (live, c) = setup()
        val token = c.attach(req)
        runCurrent()
        assertEquals(7, live.calls.size) // first refresh incl. the 27117 → neighbour fallback
        assertNotNull(c.state?.snapshot)
        advanceTimeBy(45.seconds); runCurrent()
        assertEquals(12, live.calls.size) // +5
        advanceTimeBy(45.seconds); runCurrent()
        assertEquals(17, live.calls.size)

        c.detach(token)
        assertFalse(c.isRefreshing)
        advanceTimeBy(30.minutes); runCurrent()
        assertEquals(17, live.calls.size, "no request after leaving the screen")
    }

    @Test fun pausesWhileAppInBackgroundAndDoesNotBurstOnReturn() = runTest {
        val (live, c) = setup()
        c.attach(req)
        runCurrent()
        val afterFirst = live.calls.size
        advanceTimeBy(10.seconds)
        c.setAppVisible(false)
        advanceTimeBy(20.minutes); runCurrent()
        assertEquals(afterFirst, live.calls.size, "no request while the app is in the background")
        assertFalse(c.isRefreshing)

        c.setAppVisible(true)
        runCurrent()
        // last refresh was > 45 s ago → refresh immediately
        assertEquals(afterFirst + 5, live.calls.size)
        // leave and come back within the interval: no extra burst
        c.setAppVisible(false); advanceTimeBy(5.seconds); c.setAppVisible(true); runCurrent()
        assertEquals(afterFirst + 5, live.calls.size)
        advanceTimeBy(40.seconds); runCurrent()
        assertEquals(afterFirst + 10, live.calls.size)
    }

    @Test fun reattachAndTargetChangeRespectGaps() = runTest {
        val (live, c) = setup()
        val t1 = c.attach(req)
        runCurrent()
        val n = live.calls.size
        // screen re-composed (detach + attach of the same request): waits for the interval
        c.detach(t1); val t2 = c.attach(req); runCurrent()
        assertEquals(n, live.calls.size)
        // target stop chosen: refetch after at most 10 s, not immediately
        c.detach(t2); c.attach(req.copy(targetStopCode = "36926")); runCurrent()
        assertEquals(n, live.calls.size)
        advanceTimeBy(10.seconds); runCurrent()
        assertTrue(live.calls.size > n)
        assertEquals("36926", c.state?.snapshot?.targetStopCode)
    }

    @Test fun staleDetachTokenDoesNotStopTheNewScreen() = runTest {
        val (live, c) = setup()
        val old = c.attach(req)
        c.attach(req.copy(targetStopCode = "36926")) // new screen attached before the old one disposed
        c.detach(old)
        assertTrue(c.isRefreshing)
        runCurrent()
        assertTrue(live.calls.isNotEmpty())
    }
}
