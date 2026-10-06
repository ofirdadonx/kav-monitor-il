package il.kavmonitor.shared

import il.kavmonitor.shared.data.MultiStopFetcher
import il.kavmonitor.shared.route.RouteLiveFetcher
import il.kavmonitor.shared.route.RouteLiveRequest
import il.kavmonitor.shared.route.RouteLiveSnapshot
import il.kavmonitor.shared.ui.mockup.DiagramRow
import il.kavmonitor.shared.ui.mockup.Freshness
import il.kavmonitor.shared.ui.mockup.HeroTone
import il.kavmonitor.shared.ui.mockup.MockupModels
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** v0.6 mockup view models on the real 194 full-route answer of 19:34:47. */
class MockupModelsTest {
    private val stops = stops194()
    private val userIndex = stops.indexOfFirst { it.code == "36926" }
    private val live = FullRouteReplayLive("193447")
    private val now = live.fetchedAt
    private val snap: RouteLiveSnapshot = runBlocking {
        val f = RouteLiveFetcher(live, RouteLiveRequest(37121, "194", 3, stops, "36926"), MutableClock(now), multi = MultiStopFetcher(live, null))
        f.refresh()
    }

    @Test fun printForTheMockup() {
        println("MOCKUP buses: " + snap.buses.joinToString("\n") { "${it.key} ${it.status} veh=${it.vehicleRef} pos=${it.position?.let { p -> "${p.alongMeters.toInt()} m near#${p.nearestStopIndex} ${p.nearestStopMeters.toInt()} m next#${p.nextStopIndex}" }} eta36926=${it.etaByStop["36926"]}" })
        println("MOCKUP diagram:\n" + MockupModels.diagram(stops, snap, userIndex, now).joinToString("\n"))
        println("MOCKUP rows:\n" + MockupModels.busRows(stops, snap, userIndex, now).joinToString("\n"))
    }

    @Test fun heroToneFollowsTheTimeToLeave() {
        val first = MockupModels.approaching(snap, stops[userIndex], now).first()
        val eta = MockupModels.minutesUntil(first.second, now)
        assertTrue(eta >= 8, "first bus $eta min away")
        fun tone(walk: Int) = MockupModels.hero(snap, "194", stops[userIndex], walk, now)
        assertEquals(HeroTone.PLENTY, tone(eta - 7).tone); assertEquals("7 דק׳", tone(eta - 7).big)
        assertEquals(HeroTone.GET_READY, tone(eta - 3).tone)
        assertEquals(HeroTone.GET_READY, tone(eta - 1).tone)
        assertEquals(HeroTone.LEAVE_NOW, tone(eta).tone)
        val none = tone(200)
        assertEquals(HeroTone.NONE, none.tone)
    }

    @Test fun diagramKeepsEndsUserStopAndBusNeighboursAndCollapsesTheRest() {
        val rows = MockupModels.diagram(stops, snap, userIndex, now)
        val shown = rows.filterIsInstance<DiagramRow.Stop>().map { it.index }
        assertTrue(0 in shown && stops.lastIndex in shown && userIndex in shown)
        assertEquals(shown.sorted(), shown)
        assertTrue(rows.any { it is DiagramRow.Collapsed })
        // every stop accounted for exactly once (shown or inside a collapsed run)
        assertEquals(stops.size, shown.size + rows.filterIsInstance<DiagramRow.Collapsed>().sumOf { it.count })
        assertTrue(rows.filterIsInstance<DiagramRow.Collapsed>().all { it.count >= 2 })
        // each active bus with a position appears once (at a stop or between stops)
        val inDiagram = rows.flatMap { r -> when (r) { is DiagramRow.Stop -> r.busesAt; is DiagramRow.Between -> r.buses; else -> emptyList() } }
        assertEquals(snap.buses.filter { it.position != null || it.status.name == "NOT_STARTED" }.map { it.key }.toSet(), inDiagram.map { it.key }.toSet())
        // a bus between stops: both neighbours shown
        rows.filterIsInstance<DiagramRow.Between>().forEach { b -> assertTrue(b.afterIndex in shown && b.afterIndex + 1 in shown) }
    }

    @Test fun rowsSortedByEtaWithFreshness() {
        val rows = MockupModels.busRows(stops, snap, userIndex, now)
        assertTrue(rows.isNotEmpty())
        assertEquals(rows.sortedBy { it.etaMinutes }, rows)
        assertTrue(rows.any { it.freshness != Freshness.SCHEDULED })
    }
}
