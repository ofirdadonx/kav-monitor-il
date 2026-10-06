package il.kavmonitor.shared

import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.route.RouteGeometry
import il.kavmonitor.shared.route.RouteSampling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteGeometryTest {
    private val stops = stops194()

    @Test fun mapsShapeDistanceAndScheduleOffsets() {
        assertEquals(28, stops.size)
        assertEquals(0.0, stops.first().distanceMeters)
        assertEquals(25510.0, stops.last().distanceMeters)
        assertEquals(0, stops.first().offsetSeconds)
        assertEquals(45 * 60 + 59, stops.last().offsetSeconds) // 06:32:00 → 07:17:59
    }

    @Test fun gpsAtAStopProjectsToThatStopsDistance() {
        val s13 = stops[12] // 36926, 19 024 m
        val p = assertNotNull(RouteGeometry.project(stops, s13.lat!!, s13.lon!!))
        assertEquals(19024.0, p.alongMeters, 1.0)
        assertEquals(12, p.nearestStopIndex)
        assertTrue(p.nearestStopMeters < 1.0)
        assertEquals(19024.0 / 25510.0, p.fraction, 0.001)
    }

    @Test fun midpointBetweenStopsInterpolatesAndRangeIsRespected() {
        val a = stops[9]; val b = stops[10] // 31492 (12 800) → 33737 (13 373)
        val lat = (a.lat!! + b.lat!!) / 2; val lon = (a.lon!! + b.lon!!) / 2
        val p = assertNotNull(RouteGeometry.project(stops, lat, lon, 8, 12))
        assertEquals((12800.0 + 13373.0) / 2, p.alongMeters, 15.0)
        assertEquals(10, p.nextStopIndex)
        // restricted to a later part of the route the fix can only snap there (and is far off)
        val q = assertNotNull(RouteGeometry.project(stops, lat, lon, 20, 27))
        assertTrue(q.alongMeters >= stops[20].distanceMeters!!)
        assertTrue(q.offRouteMeters > 3000)
    }

    @Test fun straightLineDistancesWhenShapeDistanceMissing() {
        val noShape = stops.map { it.copy(distanceMeters = null) }
        val filled = RouteGeometry.withDistances(noShape)
        assertEquals(0.0, filled.first().distanceMeters)
        val total = filled.last().distanceMeters!!
        assertTrue(total in 18000.0..25510.0, "straight-line total $total") // chords are shorter than the road
        val noCoords = noShape.mapIndexed { i, s -> if (i == 3) s.copy(lat = null) else s }
        assertNull(RouteGeometry.withDistances(noCoords).last().distanceMeters)
        assertNull(RouteGeometry.project(RouteGeometry.withDistances(noCoords), 32.0, 34.8))
    }

    @Test fun samplingKeepsGapsUnder15MinutesOfScheduledRunTime() {
        val idx = RouteSampling.sampleIndices(stops)
        assertEquals(listOf(0, 8, 11, 20, 27), idx) // #1 20001, #9 27117, #12 33117, #21 32939, #28 36053
        idx.zipWithNext().forEach { (a, b) -> assertTrue(stops[b].offsetSeconds!! - stops[a].offsetSeconds!! <= 15 * 60) }
    }

    @Test fun samplingFallsBackToDistanceThenToCount() {
        val byDistance = RouteSampling.sampleIndices(stops.map { it.copy(offsetSeconds = null) })
        assertEquals(0, byDistance.first()); assertEquals(27, byDistance.last())
        byDistance.zipWithNext().forEach { (a, b) ->
            assertTrue(stops[b].distanceMeters!! - stops[a].distanceMeters!! <= 6000.0 || b == a + 1)
        }
        val bare = List(13) { RouteStop(it + 1, "$it", "s$it") }
        assertEquals(listOf(0, 3, 6, 9, 12), RouteSampling.sampleIndices(bare))
        assertEquals(listOf(0), RouteSampling.sampleIndices(bare.take(1)))
    }
}
