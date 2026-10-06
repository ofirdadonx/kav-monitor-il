package il.kavmonitor.shared

import il.kavmonitor.shared.data.CurlbusParser
import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.data.NearbyStops
import il.kavmonitor.shared.model.StopSnapshot
import il.kavmonitor.shared.stop.StopArrivals
import il.kavmonitor.shared.stop.StopArrivalsController
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** curlbus `/nearby` parsing (real answers) and the stop screen (real `/21472` answer, Azrieli, 87 visits). */
class NearbyAndStopArrivalsTest {
    @Test fun nearbyIsParsedAndSortedByDistance() {
        // real answer near 36926 (Rishon, Shlomo Levi), radius 300: 3 stops, unsorted by curlbus
        val list = NearbyStops.parse(fixture("nearby/nearby_lat31.9625_lon34.8283_radius300.json"))
        assertEquals(3, list.size)
        assertEquals(list.sortedBy { it.distanceMeters }, list)
        assertTrue(list.any { it.code == "36926" && it.nameHe == "שלמה לוי/שער יפו" }, "$list")
        assertTrue(list.all { it.lat != null && it.lon != null && it.distanceMeters <= 300 })
        val savidor = NearbyStops.parse(fixture("nearby/nearby_lat32.0835_lon34.7990_radius300.json"))
        assertEquals(35, savidor.size)
        assertEquals(savidor.sortedBy { it.distanceMeters }, savidor)
        val wide = NearbyStops.parse(fixture("nearby/nearby_lat31.9900_lon34.7700_radius1000.json"))
        assertEquals(61, wide.size)
    }

    @Test fun radiiAndCacheCells() {
        assertTrue(300 in NearbyStops.ALLOWED_RADII && 1000 in NearbyStops.ALLOWED_RADII && 5 in NearbyStops.ALLOWED_RADII)
        assertTrue(320 !in NearbyStops.ALLOWED_RADII && 1050 !in NearbyStops.ALLOWED_RADII)
        assertTrue(NearbyStops.EXPANDING_RADII.all { it in NearbyStops.ALLOWED_RADII })
        // ~100 m cells: a few metres apart = same key; ~150 m apart = another key
        assertEquals(NearbyStops.cacheKey(31.96210, 34.82810), NearbyStops.cacheKey(31.96240, 34.82790))
        assertTrue(NearbyStops.cacheKey(31.96210, 34.82810) != NearbyStops.cacheKey(31.96360, 34.82810))
    }

    private val at = ilTime("19:30:59")
    private fun snap21472(name: String = "stop_21472_193059.json", fetchedAt: Instant = at) =
        CurlbusParser.parse(fixture("stop_21472/$name"), "21472", fetchedAt)

    @Test fun arrivalsForAllLinesWithDestinationOperatorAndLiveFlag() {
        val list = StopArrivals.from(snap21472(), at)
        assertTrue(list.size >= 60, "${list.size}")
        assertEquals(list.sortedBy { it.eta }.map { it.eta }, list.map { it.eta })
        assertTrue(list.all { it.eta >= at - StopArrivals.GRACE })
        assertEquals(list.size, list.map { it.key }.toSet().size)
        val l567 = list.first { it.lineName == "567" }
        assertEquals("ת.מרכזית תל אביב קומה 6/הורדה", l567.destination)
        assertEquals("מטרופולין", l567.operatorName)
        assertTrue(list.any { it.live } && list.any { !it.live }, "both live GPS and scheduled-only arrivals")
        assertTrue(list.map { it.lineName }.toSet().size >= 20, "many lines")
        assertTrue(list.all { it.minutes(at) >= 0 })
    }

    private fun TestScope.clock(): Clock = object : Clock {
        override fun now(): Instant = at + testScheduler.currentTime.milliseconds
    }

    private class Counting(val snap: (Instant) -> StopSnapshot, val clock: Clock) : LiveDataSource {
        val calls = mutableListOf<String>()
        override suspend fun stopVisits(stopCode: String): StopSnapshot { calls += stopCode; return snap(clock.now()) }
    }

    @Test fun stopScreenPollsOncePerMinuteOnlyWhileShownAndVisible() = runTest {
        val clock = clock()
        val live = Counting({ snap21472(fetchedAt = it) }, clock)
        val c = StopArrivalsController(live, backgroundScope, clock, 60.seconds)
        val t = c.attach("21472", "קניון עזריאלי/דרך מנחם בגין")
        runCurrent()
        assertEquals(listOf("21472"), live.calls)
        assertNotNull(c.state?.snapshot)
        advanceTimeBy(60.seconds); runCurrent()
        assertEquals(2, live.calls.size, "one /<stop> request per 60 s")
        // app in background: nothing
        c.setAppVisible(false)
        advanceTimeBy(5.minutes); runCurrent()
        assertEquals(2, live.calls.size)
        // back in the foreground after ≥ 60 s: one request right away, then every 60 s
        c.setAppVisible(true); runCurrent()
        assertEquals(3, live.calls.size)
        // left the screen: no more requests, ever
        c.detach(t)
        advanceTimeBy(10.minutes); runCurrent()
        assertEquals(3, live.calls.size)
        assertTrue(!c.isRefreshing)
    }

    @Test fun reopeningWithinAMinuteDoesNotBurst() = runTest {
        val clock = clock()
        val live = Counting({ snap21472(fetchedAt = it) }, clock)
        val c = StopArrivalsController(live, backgroundScope, clock, 60.seconds)
        val t = c.attach("21472", "עזריאלי"); runCurrent()
        advanceTimeBy(20.seconds); c.detach(t)
        val t2 = c.attach("21472", "עזריאלי"); runCurrent()
        assertEquals(1, live.calls.size, "reopened 20 s later: the last answer is reused")
        advanceTimeBy(40.seconds); runCurrent()
        assertEquals(2, live.calls.size)
        c.detach(t2)
        advanceTimeBy(5.minutes); runCurrent()
        assertEquals(2, live.calls.size)
    }
}
