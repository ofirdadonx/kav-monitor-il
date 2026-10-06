package il.kavmonitor.shared

import il.kavmonitor.shared.follow.BusActions
import il.kavmonitor.shared.follow.BusRef
import il.kavmonitor.shared.follow.FollowBus
import il.kavmonitor.shared.follow.FollowStatus
import il.kavmonitor.shared.follow.FollowTexts
import il.kavmonitor.shared.route.RouteLiveController
import il.kavmonitor.shared.route.RouteLiveFetcher
import il.kavmonitor.shared.route.RouteLiveRequest
import il.kavmonitor.shared.route.RouteLiveSnapshot
import il.kavmonitor.shared.util.Geo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Following one bus, on the real 194 scan of 14:51 (fixtures/route194_1451): 7692269 (2 426 m, before 20750),
 * 23373402 (6 441 m, before 31492), 7646269 (22 645 m), 23401402 not started.
 */
class FollowBusTest {
    private val stops = stops194()
    private val now = ilTime("14:52:20")
    private fun stop(code: String) = stops.first { it.code == code }
    private fun snap(target: String?): RouteLiveSnapshot = runBlocking {
        RouteLiveFetcher(ScanReplayLive(), RouteLiveRequest(37121, "194", 3, stops, target), MutableClock(now)).refresh()
    }
    private fun ref(vehicle: String?, trip: String?) = BusRef(37121, "194", trip ?: "k", vehicle, trip, null, null)

    @Test fun selectionByVehicleThenTripId() {
        val s = snap(null)
        assertEquals("7692269", FollowBus.find(s, ref("7692269", null))?.vehicleRef)
        // vehicle number missing → trip id
        assertEquals("7692269", FollowBus.find(s, ref(null, "585417608_061026"))?.vehicleRef)
        // vehicle swapped by the operator but same trip → still found through the trip id
        assertEquals("7692269", FollowBus.find(s, ref("9999999", "585417608_061026"))?.vehicleRef)
        assertNull(FollowBus.find(s, ref("9999999", "nope")))
        // the same vehicle on another line is not the same bus
        assertFalse(FollowBus.sameBus(ref("7692269", null), ref("7692269", null).copy(lineRef = 1)))
        assertTrue(FollowBus.sameBus(ref("7692269", "a"), ref("7692269", "b")))

        val bus = ref("7692269", "585417608_061026")
        assertEquals(listOf(BusActions.FOLLOW), BusActions.forBus(bus, null).map { it.id })
        assertEquals("עקוב אחרי האוטובוס הזה", BusActions.forBus(bus, null).single().label)
        assertEquals(listOf(BusActions.UNFOLLOW), BusActions.forBus(bus, bus).map { it.id })
        assertEquals(listOf(BusActions.FOLLOW), BusActions.forBus(ref("7646269", null), bus).map { it.id })
    }

    @Test fun curlbusEtaWhenTheRouteViewTargetsTheReferenceStop() {
        val s = snap("33117")
        val bus = FollowBus.find(s, ref("23373402", null))!!
        val eta = assertNotNull(bus.etaAtTarget)
        val info = FollowBus.compute(s, ref("23373402", null), stops, stop("33117"), null, null, now, null)
        assertEquals(FollowStatus.TRACKING, info.status)
        assertTrue(info.etaFromCurlbus)
        assertEquals(eta, info.etaAtStop)
        assertEquals(FollowBus.minutesUntil(eta, now), info.minutesToStop)
        assertNull(info.distanceToUserMeters, "no user location → no distance")
        assertEquals(25, info.percent)
        val head = FollowTexts.headline(info, now)
        assertTrue(head.startsWith("מגיע ל") && head.contains("בעוד ${info.minutesToStop} דק׳") && !head.contains("הערכה"), head)
        assertTrue(FollowTexts.details(info, now).any { it.startsWith("לפי curlbus: מגיע ל") }, FollowTexts.details(info, now).toString())
    }

    @Test fun estimateAndStraightLineDistanceWithoutCurlbusEta() {
        // 27117 answers HTTP 500 → no curlbus ETA there; estimate from 876 m left at 4.5 m/s from the 14:51:34 fix
        val s = snap(null)
        val user = stop("25141")
        val info = FollowBus.compute(s, ref("7692269", null), stops, stop("27117"), user.lat, user.lon, now, null)
        assertEquals(FollowStatus.TRACKING, info.status)
        assertFalse(info.etaFromCurlbus)
        assertEquals(ilTime("14:51:34") + ((3302.0 - 2426.5436710881695) / 4.5).seconds, info.etaAtStop)
        assertEquals(3, info.minutesToStop) // 14:54:49 − 14:52:20 = 2.5 min → 3
        val expected = Geo.distanceMeters(32.06665, 34.784431, user.lat!!, user.lon!!)
        assertEquals(expected, info.distanceToUserMeters!!, 0.01)
        assertTrue(expected in 300.0..450.0, "$expected")
        assertEquals(46, info.gpsAge!!.inWholeSeconds)
        assertEquals("20750", info.nextStop?.code)
        assertTrue(FollowTexts.headline(info, now).endsWith("בעוד 3 דק׳ (הערכה)"), FollowTexts.headline(info, now))
        val details = FollowTexts.details(info, now)
        assertTrue(details.any { it.endsWith("מ׳ ממך · בקו אווירי") }, details.toString())
        assertTrue(details.any { it == "מיקום עודכן לפני 46 שנ׳" }, details.toString())
        assertEquals("1.2 ק״מ", FollowTexts.distance(1234.0))
        assertEquals("380 מ׳", FollowTexts.distance(380.4))
    }

    @Test fun passedStopAndNotStarted() {
        val s = snap(null)
        val passed = FollowBus.compute(s, ref("7692269", null), stops, stop("21472"), null, null, now, null)
        assertEquals(FollowStatus.PASSED_STOP, passed.status)
        assertNull(passed.minutesToStop)
        assertTrue(FollowTexts.headline(passed, now).startsWith("האוטובוס כבר עבר את"))
        val planned = FollowBus.compute(s, ref("23401402", null), stops, stop("21472"), null, null, now, null)
        assertEquals(FollowStatus.NO_GPS, planned.status)
        assertEquals("אין מיקום GPS לאוטובוס כרגע", FollowTexts.headline(planned, now))
    }

    @Test fun disappearedBusIsReportedWithLastSeen() {
        val s = snap(null)
        val r = ref("7692269", "585417608_061026")
        val seen = FollowBus.compute(s, r, stops, stop("27117"), null, null, now, null)
        assertEquals(FollowStatus.TRACKING, seen.status)
        assertEquals(s.fetchedAt, seen.lastSeenAt)
        // next refresh: the trip ended / SIRI lost it (neither the vehicle nor the trip is listed)
        val later = s.copy(fetchedAt = now + 1.minutes, buses = s.buses.filter { it.vehicleRef != "7692269" })
        val gone = FollowBus.compute(later, r, stops, stop("27117"), 32.0, 34.8, now + 1.minutes, seen.lastSeenAt)
        assertEquals(FollowStatus.GONE, gone.status)
        assertNull(gone.bus); assertNull(gone.minutesToStop); assertNull(gone.distanceToUserMeters)
        assertEquals(now, gone.lastSeenAt)
        assertEquals("האוטובוס כבר לא מופיע בנתונים החיים", FollowTexts.headline(gone, now))
        assertTrue(FollowTexts.details(gone, now).first().contains("נראה לאחרונה ב-14:52"), FollowTexts.details(gone, now).toString())
        // no refresh yet
        assertEquals(FollowStatus.WAITING, FollowBus.compute(null, r, stops, null, null, null, now, null).status)
    }

    @Test fun refStopPrefersBoardingStopElseNearestToUser() {
        assertEquals("36926", FollowBus.refStop(stops, stop("36926"), 32.08, 34.79)?.code)
        assertEquals("25141", FollowBus.refStop(stops, null, 32.0644, 34.7815)?.code)
        assertNull(FollowBus.refStop(stops, null, null, null))
    }

    /** Following adds no poller: requests come only from the attached route view, and stop when it is detached. */
    @Test fun noRequestsAfterLeavingTheScreen() = runTest {
        val start = now
        val clock = object : Clock { override fun now(): Instant = start + testScheduler.currentTime.milliseconds }
        val live = ScanReplayLive()
        val c = RouteLiveController(live, backgroundScope, clock, interval = 60.seconds)
        val token = c.attach(RouteLiveRequest(37121, "194", 3, stops, "27117"))
        runCurrent()
        val first = live.calls.size
        assertTrue(first in 5..9, "one refresh of the sampled stops (+ the reference stop): $first")
        assertNotNull(c.state?.snapshot)
        advanceTimeBy(60.seconds); runCurrent()
        assertTrue(live.calls.size in first + 1..first * 2, "one refresh per 60 s")
        val info = FollowBus.compute(c.state?.snapshot, ref("7692269", null), stops, stop("27117"), null, null, clock.now(), null)
        assertEquals(FollowStatus.TRACKING, info.status)
        val n = live.calls.size
        c.detach(token)
        advanceTimeBy(30.minutes); runCurrent()
        assertEquals(n, live.calls.size, "no request after leaving the screen while following")
    }
}
