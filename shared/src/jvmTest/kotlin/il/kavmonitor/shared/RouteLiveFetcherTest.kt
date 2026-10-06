package il.kavmonitor.shared

import il.kavmonitor.shared.route.RouteBusStatus
import il.kavmonitor.shared.route.RouteLiveFetcher
import il.kavmonitor.shared.route.RouteLiveRequest
import il.kavmonitor.shared.util.israelClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Replays the real curlbus scan of all 194 stops (2026-10-06 14:51:31–14:52:19). Ground truth from that scan
 * (SCAN_SUMMARY.txt): 3 live buses – 7646269 (14:04, at #22, ETA +6 min at the terminus), 23373402 (14:26, on the highway ~6 min before
 * 31492 = Beit Dagan), 7692269 (14:40, at 20750 Carlebach) – and the 15:04 trip not started (listed twice).
 */
class RouteLiveFetcherTest {
    private val stops = stops194()
    private fun request(target: String? = null) = RouteLiveRequest(37121, "194", 3, stops, target)

    @Test fun findsAllActiveBusesWithProgressFromFiveSampledStops() = runTest {
        val live = ScanReplayLive()
        val f = RouteLiveFetcher(live, request(), MutableClock(ilTime("14:52:20")))
        val snap = f.refresh()

        assertEquals(listOf("7646269", "23373402", "7692269"), snap.active.map { it.vehicleRef })
        val (a, b, c) = snap.active
        assertTrue(a.position!!.fraction in 0.85..0.92, "7646269 at #22 (22 645 m of 25 510): ${a.position}")
        // on the 9.5 km highway leg #9 → #10 (3 302 → 12 800 m), ~6 min before #10
        assertTrue(b.position!!.fraction in 0.2..0.35, "23373402 between Salomon and Beit Dagan: ${b.position}")
        assertEquals(9, b.position!!.nextStopIndex)
        assertTrue(c.position!!.fraction in 0.05..0.2, "7692269 around Carlebach: ${c.position}")
        snap.active.forEach {
            assertEquals(RouteBusStatus.ACTIVE, it.status)
            // straight chords between stops: up to ~0.5 km from the highway on the long leg
            assertTrue(it.position!!.offRouteMeters < 800, "off route ${it.position}")
            assertNotNull(it.nextStop); assertNotNull(it.recordedAt)
        }
        // scan: listed at #23 with ETA +0 → GPS 41 m before #22 35542
        assertEquals("35542", a.nearStop!!.code)
        assertEquals("35542", a.nextStop!!.code)
        assertNotNull(a.etaAtDestination) // listed at the terminus

        // 15:04 trip: listed twice (2 vehicles, same trip id) → one not-started entry, no GPS/age
        assertEquals(1, snap.notStarted.size)
        val ns = snap.notStarted.single()
        assertEquals("15:04", ns.scheduledStart!!.israelClock())
        assertNull(ns.position); assertNull(ns.recordedAt)
        assertEquals(snap.buses.size, snap.buses.map { it.key }.toSet().size)
    }

    @Test fun failingSampleIsReplacedByANeighbourAndRemembered() = runTest {
        val live = ScanReplayLive()
        val f = RouteLiveFetcher(live, request(), MutableClock(ilTime("14:52:20")))
        f.refresh()
        // 27117 (#9) and its neighbour 25141 (#8) answer 500 → 31492 (#10) is used instead
        assertTrue(live.calls.containsAll(listOf("27117", "25141", "31492")))
        assertEquals(7, live.calls.size)
        live.calls.clear()
        val snap = f.refresh()
        assertEquals(listOf("20001", "31492", "33117", "32939", "36053").sorted(), live.calls.sorted())
        assertEquals(5, snap.sampledStops.size)
        assertTrue(snap.failedStops.isEmpty())
    }

    @Test fun targetStopIsSampledAndGivesEtas() = runTest {
        val live = ScanReplayLive()
        val f = RouteLiveFetcher(live, request(target = "36926"), MutableClock(ilTime("14:52:20")))
        assertTrue(12 in f.sampleIndices)
        val snap = f.refresh()
        val b = snap.active.first { it.vehicleRef == "23373402" }
        assertEquals(ilTime("15:10:00"), b.etaAtTarget) // scan: ETA +18 min at 36926
        assertNull(snap.active.first { it.vehicleRef == "7646269" }.etaAtTarget) // already past it
    }

    @Test fun allSamplesFailingIsAnError() = runTest {
        val dead = object : il.kavmonitor.shared.data.LiveDataSource {
            override suspend fun stopVisits(stopCode: String) = throw IllegalStateException("offline")
        }
        assertFailsWith<IllegalStateException> { RouteLiveFetcher(dead, request(), MutableClock(ilTime("14:52:20"))).refresh() }
    }
}
