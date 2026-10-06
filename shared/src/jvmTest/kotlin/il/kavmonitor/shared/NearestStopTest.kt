package il.kavmonitor.shared

import il.kavmonitor.shared.location.DistanceLabel
import il.kavmonitor.shared.location.NearestStops
import il.kavmonitor.shared.location.formatDistance
import il.kavmonitor.shared.model.RouteStop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NearestStopTest {
    @Test
    fun nearSavidorPicksTheOrigin() {
        val stops = stops194()
        val n = NearestStops.find(stops, 32.0835, 34.7990)!!
        assertEquals("20001", n.stop.code)
        assertEquals(0, n.index)
        assertTrue(n.distanceMeters in 200.0..320.0, "≈ 275 m, was ${n.distanceMeters}")
    }

    @Test
    fun nearAMidRouteStopPicksIt() {
        val stops = stops194()
        val target = stops.first { it.code == "36926" }
        // ~60 m north-east of Shlomo Levi/Sha'ar Yafo
        val n = NearestStops.find(stops, target.lat!! + 0.0004, target.lon!! + 0.0004)!!
        assertEquals("36926", n.stop.code)
        assertTrue(n.distanceMeters < 80)
    }

    @Test
    fun stopsWithoutCoordinatesAreIgnoredAndTiesKeepRouteOrder() {
        val a = RouteStop(1, "1", "a")
        val b = RouteStop(2, "2", "b", lat = 32.0, lon = 34.8)
        val c = RouteStop(3, "3", "c", lat = 32.0, lon = 34.8)
        assertEquals("2", NearestStops.find(listOf(a, b, c), 32.001, 34.8)!!.stop.code)
        assertNull(NearestStops.find(listOf(a), 32.0, 34.8))
        assertNull(NearestStops.distanceTo(a, 32.0, 34.8))
    }

    @Test
    fun distanceFormatting() {
        fun f(m: Double) = formatDistance(m).let { it.value + if (it.unit == DistanceLabel.Unit.METERS) " m" else " km" }
        assertEquals("0 m", f(0.0))
        assertEquals("0 m", f(-3.0))
        assertEquals("350 m", f(349.6))
        assertEquals("999 m", f(999.4))
        assertEquals("1.0 km", f(999.6))
        assertEquals("1.0 km", f(1049.0))
        assertEquals("1.1 km", f(1050.0))
        assertEquals("12.3 km", f(12_345.0))
    }
}
