package il.kavmonitor.shared

import il.kavmonitor.shared.data.CurlbusParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real curlbus responses recorded 2026-10-06 for Egged 194 (line_ref 37121). */
class CurlbusParserTest {
    @Test
    fun originBeforeDeparture() {
        val snap = CurlbusParser.parse(fixture("curlbus_20001_before_departure.json"), "20001", ilTime("09:59:00"))
        assertTrue(snap.allVisits.size > 5, "busy terminal lists many lines")
        assertNull(snap.sourceErrors)
        assertNotNull(snap.serverTime)
        val mine = snap.forLine(37121)
        val bus = mine.single { it.vehicleRef == "18379504" }
        assertEquals("585301544_061026", bus.tripId)
        assertEquals(ilTime("10:02:00"), bus.originDepartureTime)
        assertEquals(ilTime("10:02:00"), bus.eta)       // at the origin, eta == planned departure
        assertEquals(ilTime("05:55:32"), bus.recordedAt) // hours-old record before the trip starts
        assertFalse(bus.hasGps)                          // and no location
        // Filtering by line_ref keeps Kavim's "194" (other line_ref) out
        assertTrue(snap.allVisits.filter { it.lineName == "194" }.all { it.lineRef == 37121 || it.operatorRef != 3 })
    }

    @Test
    fun midRouteBusHasStringGpsParsedToDoubles() {
        val snap = CurlbusParser.parse(fixture("curlbus_36926_live_bus.json"), "36926", ilTime("09:58:32"))
        val bus = snap.forLine(37121).single { it.vehicleRef == "23401702" }
        assertTrue(bus.hasGps)
        assertEquals(32.00491, bus.lat!!, 1e-9)
        assertEquals(34.826717, bus.lon!!, 1e-9)
        assertEquals(ilTime("09:32:00"), bus.originDepartureTime)
        assertEquals(ilTime("09:58:11"), bus.recordedAt)
    }
}
