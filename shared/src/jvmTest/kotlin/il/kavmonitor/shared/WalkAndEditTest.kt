package il.kavmonitor.shared

import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.model.StopSnapshot
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.Polling
import il.kavmonitor.shared.trigger.TriggerConfig
import il.kavmonitor.shared.walk.OsrmFootRouter
import il.kavmonitor.shared.walk.WalkPlanner
import il.kavmonitor.shared.walk.WalkRoute
import il.kavmonitor.shared.walk.WalkingRouter
import il.kavmonitor.shared.watch.WatchEdits
import il.kavmonitor.shared.watch.WatchEngine
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchState
import il.kavmonitor.shared.route.RouteLiveController
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** v0.4: one poll constant, real walking routes (OSRM, recorded) with straight-line fallback, extending a running watch. */
class WalkAndEditTest {
    @AfterTest fun clear() = WatchEdits.clear()

    @Test
    fun onePollIntervalForWatchRouteViewAndMap() {
        assertEquals(60.seconds, Polling.INTERVAL)
        assertEquals(Polling.INTERVAL, TriggerConfig.Default.pollInterval)
        assertEquals(Polling.INTERVAL, RouteLiveController.DEFAULT_INTERVAL)
        // thresholds sized for 60 s polls
        assertEquals(2.minutes, TriggerConfig.Default.arriveEtaThreshold)
        assertEquals(90.seconds, TriggerConfig.Default.leaveGrace)
        assertTrue(TriggerConfig.Default.leaveMinAlertGap >= Polling.INTERVAL * 3)
    }

    private fun osrmClient(calls: MutableList<String>) = HttpClient(MockEngine { req ->
        val coords = java.net.URLDecoder.decode(req.url.encodedPath, "UTF-8").substringAfter("/route/v1/foot/")
        calls += coords
        val body = javaClass.getResource("/fixtures/osrm/$coords.json")?.readText()
        if (body == null) respond("err", HttpStatusCode.InternalServerError) else respond(body, HttpStatusCode.OK)
    }) { expectSuccess = true }

    @Test
    fun osrmRouteIsUsedAndCachedPerCell_straightLineWhenItFails() = kotlinx.coroutines.runBlocking {
        // real time (the mock HTTP engine runs on its own dispatcher; virtual time would fire the 5 s timeout at once)
        val calls = mutableListOf<String>()
        val planner = WalkPlanner(OsrmFootRouter(osrmClient(calls), "http://osrm.test"))
        // Savidor station entrance → 20001 (recorded 2026-10-06): 943 m / 12.6 min routed vs 277 m straight (5 min estimate)
        val w = planner.walk(32.0835, 34.7990, "20001", 32.083157, 34.796089)
        assertTrue(w.routed)
        assertEquals(943.0, w.distanceMeters, 1.0)
        assertEquals(13, w.minutes)
        val straight = WalkPlanner.straightLine(32.0835, 34.7990, 32.083157, 34.796089)
        assertFalse(straight.routed)
        assertEquals(277.0, straight.distanceMeters, 3.0)
        assertEquals(5, straight.minutes)
        // a few meters away (same ~50 m cell): cached, no request
        assertEquals(w, planner.walk(32.08352, 34.79902, "20001", 32.083157, 34.796089))
        assertEquals(1, calls.size)
        assertFalse(planner.shouldRequest(32.0835, 34.7990, "20001"))
        // no recorded route (router answers 500) → straight line, not cached, not retried for a while
        val f = planner.walk(32.0900, 34.8000, "20001", 32.083157, 34.796089)
        assertFalse(f.routed)
        assertFalse(planner.shouldRequest(32.0900, 34.8000, "20001"))
    }

    @Test
    fun slowRouterTimesOutAfter5sAndFallsBack() = runTest {
        val slow = object : WalkingRouter {
            override val name = "slow"
            override suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): WalkRoute? {
                delay(30.seconds); return WalkRoute(1.0, 1.0, true)
            }
        }
        val planner = WalkPlanner(slow)
        val t0 = testScheduler.currentTime
        val w = planner.walk(32.0835, 34.7990, "20001", 32.083157, 34.796089)
        assertFalse(w.routed)
        assertEquals(5_000, testScheduler.currentTime - t0)
    }

    @Test
    fun extendingARunningWatchKeepsItsStateAndMovesTheTimeLimit() = runTest {
        val start = ilTime("10:00:00")
        val clock = object : Clock { override fun now(): Instant = start + testScheduler.currentTime.milliseconds }
        var calls = 0
        val live = object : LiveDataSource {
            override suspend fun stopVisits(stopCode: String): StopSnapshot { calls++; val n = clock.now(); return StopSnapshot(stopCode, n, n, emptyList()) }
        }
        val spec = spec194(TriggerType.DEPARTS_ORIGIN, minutes = 15)
        val states = mutableListOf<WatchState>()
        val job = launch { WatchEngine(live, clock, TriggerConfig.Default).run(spec, { states += it }, { _, _ -> }) }
        testScheduler.advanceTimeBy(10.minutes); testScheduler.runCurrent()
        val before = states.last()
        assertEquals(start + 15.minutes, before.endsAt)
        WatchEdits.extend(spec, 15)
        testScheduler.advanceTimeBy(Polling.INTERVAL); testScheduler.runCurrent()
        val after = states.last()
        assertEquals(start + 30.minutes, after.endsAt, "new time limit picked up on the next poll")
        assertEquals(before.startedAt, after.startedAt)
        assertEquals(before.polls + 1, after.polls, "same run continues (no restart, polls keep counting)")
        job.join()
        val final = states.last()
        assertEquals(WatchPhase.TIMED_OUT, final.phase)
        assertEquals(start + 30.minutes, final.endsAt)
        // one poll per 60 s for 30 min: 0..29 min
        assertEquals(30, calls)
        // capped in total
        WatchEdits.extend(spec, 10_000)
        assertEquals(WatchEdits.MAX_TOTAL_MINUTES, spec.durationMinutes + WatchEdits.extraMinutes(spec))
        assertFalse(WatchEdits.canExtend(spec))
    }
}
