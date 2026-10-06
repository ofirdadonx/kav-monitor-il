package il.kavmonitor.shared

import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.TriggerDetector
import il.kavmonitor.shared.trigger.TriggerReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Synthetic edge cases. Real-data replays are in [TriggerReplayTest]. */
class TriggerDetectorTest {
    private fun v(
        trip: String, sched: String, eta: String = sched, rec: String = "05:55:32",
        lat: Double? = null, lon: Double? = null, stop: String = "20001",
    ) = LiveVisit(stop, "194", 37121, 3, "veh-$trip", trip, ilTime(eta), ilTime(sched), ilTime(rec), lat, lon)

    private val origin = spec194(TriggerType.DEPARTS_ORIGIN)

    @Test
    fun newTripAppearingAtOriginDoesNotFire() {
        val d = TriggerDetector(origin)
        assertTrue(d.onSnapshot(ilTime("09:50:00"), listOf(v("A", "10:02:00"))).isEmpty())
        // The prototype alerted here ("new vehicle at origin"); that only means "scheduled", not "left".
        assertTrue(d.onSnapshot(ilTime("09:50:35"), listOf(v("A", "10:02:00"), v("B", "10:17:00"))).isEmpty())
        assertEquals(2, d.busesSeen)
    }

    @Test
    fun dropNearScheduledDepartureFiresOnceAndNextBusFiresToo() {
        val d = TriggerDetector(origin)
        d.onSnapshot(ilTime("10:01:00"), listOf(v("A", "10:02:00"), v("B", "10:17:00")))
        val e1 = d.onSnapshot(ilTime("10:01:42"), listOf(v("B", "10:17:00")))
        assertEquals(listOf("A" to TriggerReason.LEFT_ORIGIN_DROPPED), e1.map { it.tripId to it.reason })
        assertTrue(d.onSnapshot(ilTime("10:02:20"), listOf(v("B", "10:17:00"), v("C", "10:32:00"))).isEmpty())
        val e2 = d.onSnapshot(ilTime("10:17:10"), listOf(v("C", "10:32:00")))
        assertEquals(listOf("B"), e2.map { it.tripId })
    }

    @Test
    fun unreliableSnapshotDoesNotCountAsDrop() {
        val d = TriggerDetector(origin)
        d.onSnapshot(ilTime("10:01:00"), listOf(v("A", "10:02:00")))
        assertTrue(d.onSnapshot(ilTime("10:01:35"), emptyList(), reliable = false).isEmpty())
        assertTrue(d.onSnapshot(ilTime("10:02:10"), listOf(v("A", "10:02:00"))).isEmpty())
        assertEquals(1, d.onSnapshot(ilTime("10:02:45"), emptyList(), reliable = true).size)
    }

    @Test
    fun earlyDropIsHeldUntilScheduledDeparture() {
        val d = TriggerDetector(origin)
        d.onSnapshot(ilTime("09:40:00"), listOf(v("A", "10:02:00")))
        assertTrue(d.onSnapshot(ilTime("09:41:00"), emptyList()).isEmpty(), "20 min early: trip activated, not departed")
        assertTrue(d.onSnapshot(ilTime("10:01:30"), emptyList()).isEmpty())
        val e = d.onSnapshot(ilTime("10:02:05"), emptyList())
        assertEquals(listOf(TriggerReason.LEFT_ORIGIN_SCHEDULED), e.map { it.reason })
    }

    @Test
    fun stillListedButGpsFarFromOriginFires() {
        val d = TriggerDetector(origin)
        // fresh fix ~770 m south of Savidor
        val e = d.onSnapshot(ilTime("10:03:00"), listOf(v("A", "10:02:00", rec = "10:02:40", lat = 32.076242, lon = 34.79235)))
        assertEquals(TriggerReason.LEFT_ORIGIN_GPS, e.single().reason)
        // a fix at the platform does not fire
        val d2 = TriggerDetector(origin)
        assertTrue(d2.onSnapshot(ilTime("10:02:20"), listOf(v("A", "10:02:00", rec = "10:02:07", lat = 32.083172, lon = 34.796131))).isEmpty())
    }

    private val azrieli = spec194(TriggerType.ARRIVES_AT_STOP, STOP_21472)

    private fun az(trip: String, eta: String, rec: String, lat: Double?, lon: Double?) =
        v(trip, "10:02:00", eta = eta, rec = rec, lat = lat, lon = lon, stop = "21472")

    @Test
    fun arrivalByGpsRadius() {
        val d = TriggerDetector(azrieli)
        assertTrue(d.onSnapshot(ilTime("10:04:00"), listOf(az("A", "10:07:00", "10:03:40", 32.0790, 34.7935))).isEmpty()) // ~570 m
        val e = d.onSnapshot(ilTime("10:05:10"), listOf(az("A", "10:07:00", "10:04:55", 32.0755, 34.7912)))              // ~115 m
        assertEquals(TriggerReason.AT_STOP_GPS, e.single().reason)
        assertTrue(d.onSnapshot(ilTime("10:05:45"), listOf(az("A", "10:07:00", "10:05:30", 32.0746, 34.7906))).isEmpty(), "fires once per trip")
    }

    @Test
    fun arrivalByEtaNeedsLiveGps() {
        val d = TriggerDetector(azrieli)
        // ETA in 40 s but no GPS (trip not started) → nothing
        assertTrue(d.onSnapshot(ilTime("10:06:20"), listOf(az("A", "10:07:00", "05:55:32", null, null))).isEmpty())
        // live bus 600 m away with ETA in 50 s → fires
        val e = d.onSnapshot(ilTime("10:06:30"), listOf(az("A", "10:07:00", "05:55:32", null, null), az("B", "10:07:00", "10:06:10", 32.0790, 34.7935)))
        assertEquals(TriggerReason.AT_STOP_ETA, e.single().reason)
    }

    @Test
    fun liveBusDroppingOffTheStopListPassedIt() {
        val d = TriggerDetector(azrieli)
        d.onSnapshot(ilTime("10:04:00"), listOf(az("A", "10:09:00", "10:03:40", 32.0790, 34.7935), az("N", "10:30:00", "05:00:00", null, null)))
        val e = d.onSnapshot(ilTime("10:04:35"), listOf(az("N", "10:30:00", "05:00:00", null, null)))
        assertEquals(listOf("A" to TriggerReason.DROPPED_AT_STOP), e.map { it.tripId to it.reason })
    }

    @Test
    fun nonLiveTripDroppingFarFromEtaIsIgnored() {
        val d = TriggerDetector(azrieli)
        d.onSnapshot(ilTime("10:00:00"), listOf(az("A", "10:30:00", "05:00:00", null, null)))
        assertTrue(d.onSnapshot(ilTime("10:00:35"), emptyList()).isEmpty())
    }

    @Test
    fun staleGpsIsIgnored() {
        val d = TriggerDetector(azrieli)
        val e = d.onSnapshot(ilTime("10:10:00"), listOf(az("A", "10:10:30", "10:05:00", 32.0746, 34.7906)))
        assertTrue(e.isEmpty())
    }
}
