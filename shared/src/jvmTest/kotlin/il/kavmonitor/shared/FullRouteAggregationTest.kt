package il.kavmonitor.shared

import il.kavmonitor.shared.data.MultiStopFetcher
import il.kavmonitor.shared.route.RouteBusStatus
import il.kavmonitor.shared.route.RouteLiveFetcher
import il.kavmonitor.shared.route.RouteLiveRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Multi-stop parsing and aggregation on the REAL recorded full-route answers of 194 (2026-10-06 19:30:57–19:40:47,
 * one request for the 24 working stops, 341–371 KB each, trimmed to line 194 + one other visit per stop).
 */
class FullRouteAggregationTest {
    private val stops = stops194()

    @Test fun parsesTheMultiStopAnswerKeyedByStop() {
        val live = FullRouteReplayLive("193447")
        val codes = live.full.allVisits.map { it.stopCode }.toSet()
        assertEquals(24, codes.size, "24 working stops answered")
        assertTrue(codes.none { it in BROKEN_194 })
        assertEquals(stops.map { it.code }.filterNot { it in BROKEN_194 }.toSet(), codes)
        val mine = live.full.allVisits.filter { it.lineRef == 37121 }
        // 19:34:47: 3 trips of 194 – 585301559 listed at 17 stops ahead, 585301560 at 6, 585301561 (not started) at 2
        assertEquals(mapOf<String?, Int>("585301559_061026" to 17, "585301560_061026" to 6, "585301561_061026" to 2),
            mine.groupBy { it.tripId }.mapValues { it.value.size })
    }

    @Test fun oneRequestAggregatesEveryBusWithEtasAtEveryStopAhead() = runBlocking {
        val live = FullRouteReplayLive("193447")
        val f = RouteLiveFetcher(live, RouteLiveRequest(37121, "194", 3, stops, targetStopCode = "36926"), MutableClock(live.fetchedAt),
            multi = MultiStopFetcher(live, null))
        f.refresh() // first: bisect finds the 4 broken stops
        live.calls.clear()
        val snap = f.refresh()
        assertEquals(1, live.calls.size, "one request per refresh")
        assertEquals(24, live.calls.single().split('+').size)
        assertEquals(24, snap.sampledStops.size)
        val active = snap.active
        assertEquals(2, active.size, snap.buses.joinToString("\n"))
        val far = active.single { it.tripId == "585301559_061026" }
        assertNotNull(far.position); assertNotNull(far.nextStop)
        assertTrue(far.etaByStop.size >= 16, "ETA at every stop ahead: ${far.etaByStop.size}")
        assertNotNull(far.etaAtTarget)
        assertEquals(far.etaByStop["36926"], far.etaAtTarget)
        // ETAs increase along the route
        val etas = stops.mapNotNull { far.etaByStop[it.code] }
        assertEquals(etas.sorted(), etas)
        assertEquals(1, snap.buses.count { it.status == RouteBusStatus.NOT_STARTED })
    }

    /**
     * Coverage: the v0.4 sampled approach (5–7 single stops) vs the v0.5 full request, from the SAME recorded answer
     * (the sampled stops get exactly their slice of it). Printed for the report.
     */
    @Test fun coverageSampledVersusFullOnTheSameMoment() = runBlocking {
        var fullBuses = 0; var sampledBuses = 0; var fullEtas = 0; var sampledEtas = 0
        val rows = mutableListOf<String>()
        for (round in FULL_ROUNDS) {
            val live = FullRouteReplayLive(round)
            val req = RouteLiveRequest(37121, "194", 3, stops)
            val clock = MutableClock(live.fetchedAt)
            val full = RouteLiveFetcher(live, req, clock, multi = MultiStopFetcher(live, null)).refresh()
            val sampled = RouteLiveFetcher(live, req, clock).refresh()
            val fk = full.buses.map { it.key }.toSet(); val sk = sampled.buses.map { it.key }.toSet()
            assertTrue(fk.containsAll(sk), "$round: the full request sees every bus the samples see")
            fullBuses += fk.size; sampledBuses += sk.size
            fullEtas += full.buses.sumOf { it.etaByStop.size }; sampledEtas += sampled.buses.sumOf { it.etaByStop.size }
            rows += "$round full=${fk.size} (${full.active.size} active) sampled=${sk.size} (${sampled.active.size} active) " +
                "etas full=${full.buses.sumOf { it.etaByStop.size }} sampled=${sampled.buses.sumOf { it.etaByStop.size }} missing=${fk - sk}"
        }
        println("COVERAGE\n" + rows.joinToString("\n") + "\nTOTAL buses full=$fullBuses sampled=$sampledBuses; stop ETAs full=$fullEtas sampled=$sampledEtas")
        assertTrue(fullBuses >= sampledBuses)
        assertTrue(fullEtas > sampledEtas)
    }
}
