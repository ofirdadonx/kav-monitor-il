package il.kavmonitor.shared

import com.russhwolf.settings.MapSettings
import il.kavmonitor.shared.data.BrokenStopTracker
import il.kavmonitor.shared.data.BrokenStops
import il.kavmonitor.shared.data.ErrorKind
import il.kavmonitor.shared.data.FetchErrors
import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.data.StopServerException
import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.StopSnapshot
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.TriggerConfig
import il.kavmonitor.shared.trigger.TriggerEvent
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.watch.StopFallback
import il.kavmonitor.shared.watch.WatchEngine
import il.kavmonitor.shared.watch.WatchState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StopFallbackTest {
    private val stops = stops194()
    private fun stop(code: String) = stops.first { it.code == code }
    private val broken194 = setOf("20876", "20725", "25141", "27117")

    @Test
    fun picksNearestWorkingNeighbours() {
        // 25141 (#8): upstream 20750 (#7), downstream 27117 (#9) is broken too → 31492 (#10)
        val p = StopFallback.plan(TriggerType.ARRIVES_AT_STOP, stops, stop("25141")) { it in broken194 }!!
        assertEquals("20750", p.upstream?.code)
        assertEquals("31492", p.downstream?.code)
        assertEquals(listOf("20750", "31492"), p.polled.map { it.code })
        // departs origin with a broken origin: only the next working stop downstream
        val d = StopFallback.plan(TriggerType.DEPARTS_ORIGIN, stops, stop("20001")) { it == "20001" }!!
        assertNull(d.upstream)
        assertEquals("20762", d.downstream?.code)
        // 20725 (#6): upstream 21472 (#5, 20876 #4 is broken but not needed), downstream 20750
        val q = StopFallback.plan(TriggerType.LEAVE_FOR_STOP, stops, stop("20725")) { it in broken194 }!!
        assertEquals("21472" to "20750", q.upstream?.code to q.downstream?.code)
    }

    @Test
    fun travelTimeFromGtfsTimesElseDistance() {
        // GTFS: 20750 06:39:01 → 25141 06:39:43 → 31492 06:49:36
        assertEquals(42.seconds, StopFallback.travel(stop("20750"), stop("25141")))
        assertEquals(593.seconds, StopFallback.travel(stop("25141"), stop("31492")))
        // without times: shape distance (2806 → 3302 m = 496 m) at 5 m/s
        val a = stop("25141").copy(offsetSeconds = null)
        val b = stop("27117").copy(offsetSeconds = null)
        assertEquals(99.seconds, StopFallback.travel(a, b))
    }

    private fun v(stop: String, trip: String, eta: String?, fix: String? = null, lat: Double? = null, lon: Double? = null) = LiveVisit(
        stopCode = stop, lineName = "194", lineRef = 37121, operatorRef = 3, vehicleRef = "v$trip", tripId = trip,
        eta = eta?.let { ilTime(it) }, originDepartureTime = ilTime("17:40:00"), recordedAt = fix?.let { ilTime(it) }, lat = lat, lon = lon,
    )

    @Test
    fun derivesTargetEtaFromUpstreamThenDownstream() {
        val p = StopFallback.plan(TriggerType.ARRIVES_AT_STOP, stops, stop("25141")) { it in broken194 }!!
        val now = ilTime("17:55:00")
        // still listed upstream: ETA 17:57 at 20750 + 42 s
        val a = StopFallback.derive(p, listOf(v("20750", "T", "17:57:00")), listOf(v("31492", "T", "18:08:00")), now, 3.minutes).single()
        assertEquals("25141", a.stopCode)
        assertEquals(ilTime("17:57:42"), a.eta)
        // passed upstream: 31492 ETA 18:11 − 9:53, GPS 51 m from 25141 kept
        val b = StopFallback.derive(p, emptyList(), listOf(v("31492", "T", "18:11:00", "17:57:37", 32.064809, 34.781370)), ilTime("17:58:24"), 3.minutes).single()
        assertEquals(ilTime("18:01:07"), b.eta)
        assertEquals(32.064809, b.lat)
        // live fix 398 m past 25141 towards 31492 (recorded 18:01:09): passed → left out
        val c = StopFallback.derive(p, emptyList(), listOf(v("31492", "T", "18:11:00", "18:01:09", 32.061033, 34.780007)), ilTime("18:01:27"), 3.minutes)
        assertTrue(c.isEmpty())
    }

    @Test
    fun brokenStopsPersistWithTtlAndTrackerNeedsTwoErrors() = runTest {
        val settings = MapSettings()
        val clock = MutableClock(ilTime("18:00:00"))
        val store = BrokenStops(settings, clock)
        var fail = true
        val tracker = BrokenStopTracker(object : LiveDataSource {
            override suspend fun stopVisits(stopCode: String): StopSnapshot =
                if (fail) throw StopServerException(stopCode, 500) else StopSnapshot(stopCode, clock.now(), null, emptyList())
        }, store)
        runCatching { tracker.stopVisits("25141") }
        assertFalse(store.isBroken("25141"), "one 500 is not enough")
        runCatching { tracker.stopVisits("25141") }
        assertTrue(store.isBroken("25141"))
        assertTrue(BrokenStops(settings, clock).isBroken("25141"), "persists across runs")
        clock.now = clock.now + 7.hours
        assertFalse(BrokenStops(settings, clock).isBroken("25141"), "retried after the TTL")
        clock.now = ilTime("18:10:00")
        fail = false
        tracker.stopVisits("25141")
        assertFalse(store.isBroken("25141"), "a success clears it")
    }

    @Test
    fun errorsAreClassified() {
        assertEquals(ErrorKind.SERVER, FetchErrors.classify(StopServerException("25141", 500)).kind)
        assertEquals("25141", FetchErrors.classify(StopServerException("25141", 500)).stopCode)
        assertEquals(ErrorKind.NETWORK, FetchErrors.classify(java.net.UnknownHostException("curlbus.app")).kind)
        assertEquals(ErrorKind.TIMEOUT, FetchErrors.classify(java.net.SocketTimeoutException("read")).kind)
        assertEquals(ErrorKind.NETWORK, FetchErrors.classify(RuntimeException(java.net.ConnectException("refused"))).kind)
        assertEquals(ErrorKind.OTHER, FetchErrors.classify(IllegalStateException("x")).kind)
    }

    /** Fake curlbus: 25141 always 500; others answer from [visits] (stop → visits at that time). */
    private class FakeCurlbus(val clock: Clock, val visits: (String, Instant) -> List<LiveVisit>) : LiveDataSource {
        val calls = mutableListOf<Pair<Instant, String>>()
        override suspend fun stopVisits(stopCode: String): StopSnapshot {
            val now = clock.now()
            calls += now to stopCode
            if (stopCode in setOf("25141", "27117", "20001x")) throw StopServerException(stopCode, 500)
            val other = LiveVisit(stopCode, "18", 9999, 5, "x", "other", null, null, null, null, null)
            return StopSnapshot(stopCode, now, now, visits(stopCode, now) + other)
        }
    }

    @Test
    fun engineSwitchesToNeighboursAfterTwoServerErrorsAndFiresArrival() = runTest {
        val start = ilTime("17:50:00")
        val clock = object : Clock { override fun now(): Instant = start + testScheduler.currentTime.milliseconds }
        // bus T approaching: listed at 20750 until 17:57:10, GPS 51 m from 25141 at 17:57:37 (listed at 31492 only)
        val live = FakeCurlbus(clock) { code, now ->
            when {
                code == "20750" && now < ilTime("17:57:10") -> listOf(v("20750", "T", "17:57:00", "17:54:00", 32.0700, 34.7860))
                code == "31492" && now < ilTime("17:57:30") -> listOf(v("31492", "T", "18:08:00", "17:54:00", 32.0700, 34.7860))
                code == "31492" -> listOf(v("31492", "T", "18:11:00", "17:57:37", 32.064809, 34.781370))
                else -> emptyList()
            }
        }
        val store = BrokenStops(MapSettings(), clock)
        val engine = WatchEngine(live, clock, TriggerConfig(), store) { stops }
        val states = mutableListOf<WatchState>()
        val events = mutableListOf<TriggerEvent>()
        engine.run(spec194(TriggerType.ARRIVES_AT_STOP, stop("25141"), minutes = 10), { states += it }, { e, _ -> events += e })

        // 2 direct attempts 5 s apart, then only the two stand-ins per poll
        assertEquals(listOf("25141", "25141"), live.calls.take(2).map { it.second })
        assertEquals(5.seconds, live.calls[1].first - live.calls[0].first)
        assertTrue(store.isBroken("25141"), "marked")
        val after = live.calls.drop(2)
        // fresh store: 27117 (next downstream) is tried once, answers 500, and is replaced by 31492 5 s later
        assertTrue(after.all { it.second in setOf("20750", "27117", "31492") }, "only stand-ins: $after")
        assertEquals(1, after.count { it.second == "27117" })
        val swap = after.indexOfFirst { it.second == "31492" }
        assertEquals(5.seconds, after[swap].first - after.first { it.second == "27117" }.first)
        assertTrue(after.groupBy { it.first }.values.all { it.size <= 2 }, "≤ 2 calls per poll")
        val firstErr = states.first { it.lastError != null }.lastError!!
        assertEquals(ErrorKind.SERVER, firstErr.kind)
        val fb = states.last().fallback
        assertNotNull(fb)
        assertEquals("20750" to "31492", fb.upstream?.code to fb.downstream?.code)
        assertTrue(states.last().lastError == null, "error cleared")
        val ev = events.single()
        assertEquals("T", ev.tripId)
        assertTrue(ev.reason == TriggerReason.AT_STOP_ETA || ev.reason == TriggerReason.AT_STOP_GPS, "reason ${ev.reason} at ${ev.at}")
        assertTrue(ev.at in ilTime("17:56:00")..ilTime("17:58:30"), "fired at ${ev.at}")
        assertTrue(states.last { it.buses.isNotEmpty() }.buses.all { it.etaEstimated }, "estimated")
    }

    @Test
    fun knownBrokenStopGoesStraightToFallback_departsUsesNextStop() = runTest {
        val start = ilTime("10:00:00")
        val clock = object : Clock { override fun now(): Instant = start + testScheduler.currentTime.milliseconds }
        val store = BrokenStops(MapSettings(), clock).apply { mark("20001") }
        // trip D listed at 20762 (stop 2) until 10:02:40 → drop = departed (past its 10:02 schedule)
        val live = FakeCurlbus(clock) { code, now ->
            if (code == "20762" && now < ilTime("10:02:40"))
                listOf(v("20762", "D", "10:03:00").copy(originDepartureTime = ilTime("10:02:00")))
            else emptyList()
        }
        val events = mutableListOf<TriggerEvent>()
        WatchEngine(live, clock, TriggerConfig(), store) { stops }
            .run(spec194(TriggerType.DEPARTS_ORIGIN, minutes = 5), { }, { e, _ -> events += e })
        assertTrue(live.calls.none { it.second == "20001" }, "known broken: never polled")
        assertTrue(live.calls.all { it.second == "20762" })
        val ev = events.single()
        assertEquals(TriggerReason.LEFT_ORIGIN_DROPPED, ev.reason)
        assertTrue(ev.at >= ilTime("10:02:40"))
    }
}
