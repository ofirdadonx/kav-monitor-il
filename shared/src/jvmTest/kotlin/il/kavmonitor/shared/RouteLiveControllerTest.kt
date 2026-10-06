package il.kavmonitor.shared

import il.kavmonitor.shared.data.BrokenStops
import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.model.StopSnapshot
import il.kavmonitor.shared.route.RouteLiveController
import il.kavmonitor.shared.route.RouteLiveRequest
import kotlinx.coroutines.delay
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The live route view: v0.5 = ONE multi-stop request per refresh for all working stops (bisect once to find the
 * broken ones), refreshing only while attached AND the app is visible; zero requests otherwise; requests never overlap.
 * Replay: the real 194 scan of 14:51 (25141, 27117, 20876, 20725 answered HTTP 500).
 */
class RouteLiveControllerTest {
    private val stops = stops194()
    private val req = RouteLiveRequest(37121, "194", 3, stops)
    private val brokenAt1451 = setOf("20876", "20725", "25141", "27117")

    private fun TestScope.clock(): Clock {
        val start = ilTime("14:52:20")
        return object : Clock { override fun now(): Instant = start + testScheduler.currentTime.milliseconds }
    }

    private fun TestScope.setup(fullRoute: Boolean = true, broken: BrokenStops? = null): Pair<ScanReplayLive, RouteLiveController> {
        val live = ScanReplayLive()
        return live to RouteLiveController(live, backgroundScope, clock(), interval = 60.seconds, broken = broken, fullRoute = fullRoute)
    }

    @Test fun oneRequestPerRefreshAfterAOneTimeBisectAndNothingAfterDetach() = runTest {
        val broken = BrokenStops(null, clock())
        val (live, c) = setup(broken = broken)
        val token = c.attach(req)
        runCurrent()
        // first refresh: all 28 stops → 500 → bisect finds the 4 broken stops (22 requests, once); the halves that
        // answered are kept, so this refresh already has every working stop
        assertEquals(22, live.calls.size, live.calls.joinToString("\n"))
        assertEquals(brokenAt1451, broken.known())
        val snap = assertNotNull(c.state?.snapshot)
        assertEquals(24, snap.sampledStops.size)
        assertEquals(brokenAt1451, snap.failedStops.map { it.code }.toSet())
        // later refreshes: exactly one request with the 24 working stops
        advanceTimeBy(60.seconds); runCurrent()
        assertEquals(23, live.calls.size)
        assertEquals(24, live.calls.last().split('+').size)
        assertEquals(1, c.lastRequests)
        advanceTimeBy(60.seconds); runCurrent()
        assertEquals(24, live.calls.size)

        c.detach(token)
        assertFalse(c.isRefreshing)
        advanceTimeBy(30.minutes); runCurrent()
        assertEquals(24, live.calls.size, "no request after leaving the screen")
    }

    @Test fun knownBrokenStopsAreLeftOutFromTheStart() = runTest {
        val broken = BrokenStops(null, clock()).apply { brokenAt1451.forEach { mark(it) } }
        val (live, c) = setup(broken = broken)
        c.attach(req)
        runCurrent()
        assertEquals(1, live.calls.size)
        assertEquals(24, c.state?.sampledStopCount)
    }

    @Test fun pausesWhileAppInBackgroundAndDoesNotBurstOnReturn() = runTest {
        val broken = BrokenStops(null, clock()).apply { brokenAt1451.forEach { mark(it) } }
        val (live, c) = setup(broken = broken)
        c.attach(req)
        runCurrent()
        assertEquals(1, live.calls.size)
        advanceTimeBy(10.seconds)
        c.setAppVisible(false)
        advanceTimeBy(20.minutes); runCurrent()
        assertEquals(1, live.calls.size, "no request while the app is in the background")
        assertFalse(c.isRefreshing)

        c.setAppVisible(true)
        runCurrent()
        assertEquals(2, live.calls.size) // last refresh was > 60 s ago → refresh now
        c.setAppVisible(false); advanceTimeBy(5.seconds); c.setAppVisible(true); runCurrent()
        assertEquals(2, live.calls.size) // back within the interval: no burst
        advanceTimeBy(55.seconds); runCurrent()
        assertEquals(3, live.calls.size)
    }

    @Test fun targetChangeNeedsNoRequestAndReattachWaits() = runTest {
        val broken = BrokenStops(null, clock()).apply { brokenAt1451.forEach { mark(it) } }
        val (live, c) = setup(broken = broken)
        val t1 = c.attach(req)
        runCurrent()
        assertEquals(1, live.calls.size)
        c.detach(t1); val t2 = c.attach(req); runCurrent()
        assertEquals(1, live.calls.size)
        // a target stop chosen: same stops are already fetched → ETAs for it at once, no request
        c.detach(t2); c.attach(req.copy(targetStopCode = "36926")); runCurrent()
        assertEquals(1, live.calls.size)
        val snap = assertNotNull(c.state?.snapshot)
        assertEquals("36926", snap.targetStopCode)
        assertTrue(snap.buses.any { it.etaAtTarget != null })
        advanceTimeBy(60.seconds); runCurrent()
        assertEquals(2, live.calls.size)
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

    /** A slow multi-stop request (up to 30 s timeout) never overlaps the next tick; an overrun tick is skipped. */
    @Test fun requestsNeverOverlapAndAnOverrunTickIsSkipped() = runTest {
        var inFlight = 0; var maxInFlight = 0
        val starts = mutableListOf<Duration>()
        val inner = ScanReplayLive()
        var slow = 25.seconds
        val live = object : LiveDataSource {
            override suspend fun stopVisits(stopCode: String) = inner.stopVisits(stopCode)
            override suspend fun multiStopVisits(stopCodes: List<String>): StopSnapshot {
                inFlight++; maxInFlight = maxOf(maxInFlight, inFlight); starts += testScheduler.currentTime.milliseconds
                try { delay(slow); return inner.multiStopVisits(stopCodes) } finally { inFlight-- }
            }
        }
        val broken = BrokenStops(null, clock()).apply { brokenAt1451.forEach { mark(it) } }
        val c = RouteLiveController(live, backgroundScope, clock(), interval = 60.seconds, broken = broken)
        c.attach(req)
        runCurrent()
        assertTrue(c.inFlight)
        advanceTimeBy(26.seconds); runCurrent()
        assertFalse(c.inFlight)
        advanceTimeBy(34.seconds); runCurrent() // t = 60: next tick (fixed rate, not 60 s after the answer)
        assertEquals(listOf(0.seconds, 60.seconds), starts)
        slow = 70.seconds // this one overruns the t = 120 tick
        advanceTimeBy(60.seconds); runCurrent()
        assertEquals(listOf(0.seconds, 60.seconds, 120.seconds), starts)
        advanceTimeBy(60.seconds); runCurrent() // t = 180: still in flight → the tick is skipped
        assertEquals(3, starts.size)
        advanceTimeBy(60.seconds); runCurrent() // t = 240: next tick
        assertEquals(240.seconds, starts.last())
        assertEquals(1, maxInFlight)
    }

    /** v0.4 sampled mode (kept for comparison): ~5 single-stop calls per refresh. */
    @Test fun sampledModeStillWorks() = runTest {
        val (live, c) = setup(fullRoute = false)
        val token = c.attach(req)
        runCurrent()
        assertEquals(7, live.calls.size) // first refresh incl. the 27117 → neighbour fallback
        advanceTimeBy(60.seconds); runCurrent()
        assertEquals(12, live.calls.size)
        c.detach(token)
        advanceTimeBy(30.minutes); runCurrent()
        assertEquals(12, live.calls.size)
    }
}
