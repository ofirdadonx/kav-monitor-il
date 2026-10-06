package il.kavmonitor.shared

import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.StopSnapshot
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.TriggerConfig
import il.kavmonitor.shared.trigger.TriggerEvent
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.watch.WatchEngine
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class WatchEngineTest {
    private val start = ilTime("09:55:00")

    private fun visit(trip: String, sched: Instant) = LiveVisit(
        stopCode = "20001", lineName = "194", lineRef = 37121, operatorRef = 3, vehicleRef = "v-$trip", tripId = trip,
        eta = sched, originDepartureTime = sched, recordedAt = ilTime("05:55:32"), lat = null, lon = null,
    )
    private val otherLine = LiveVisit("20001", "18", 9999, 5, "x", "other", null, null, null, null, null)

    @Test
    fun pollsUntilTimeLimitSurvivesErrorsAndFiresOnDeparture() = runTest {
        val clock = object : Clock { override fun now(): Instant = start + testScheduler.currentTime.milliseconds }
        val bus = visit("T1", ilTime("10:02:00"))
        val calls = mutableListOf<Instant>()
        val live = object : LiveDataSource {
            override suspend fun stopVisits(stopCode: String): StopSnapshot {
                val now = clock.now(); calls += now
                if (calls.size == 3) error("HTTP 500")
                val visits = if (now < ilTime("10:02:30")) listOf(bus, otherLine) else listOf(otherLine)
                return StopSnapshot(stopCode, now, now, visits)
            }
        }
        val states = mutableListOf<WatchState>()
        val events = mutableListOf<TriggerEvent>()
        val cfg = TriggerConfig(pollInterval = 35.seconds)
        val final = WatchEngine(live, clock, cfg).run(spec194(TriggerType.DEPARTS_ORIGIN, minutes = 15), { states += it }, { e, _ -> events += e })

        assertEquals(WatchPhase.TIMED_OUT, final.phase)
        assertEquals(start + 15.minutes, final.endsAt)
        // 15 min / 35 s → polls at 0,35,...,875 s = 26 polls, one per interval
        assertEquals(26, calls.size)
        assertTrue(calls.zipWithNext().all { (a, b) -> b - a == 35.seconds })
        assertNotNull(states.first { it.polls == 3 }.lastError, "error is reported in state")
        assertNull(states.first { it.polls == 4 }.lastError, "error clears after a good poll")
        assertEquals(1, events.size)
        assertEquals(TriggerReason.LEFT_ORIGIN_DROPPED, events.single().reason)
        assertEquals("T1", events.single().tripId)
        assertEquals(1, final.busesSeen)
    }

    @Test
    fun duplicateTripListedWithTwoVehiclesYieldsOneBus() = runTest {
        // real 20001 response at 14:51: trip 585301551 listed with vehicles 23400602 and 23401402
        val snap = il.kavmonitor.shared.data.CurlbusParser.parse(fixture("route194_1451/20001.json"), "20001", ilTime("14:51:32"))
        val visits = snap.forLine(37121, "194", 3)
        assertEquals(2, visits.size)
        val unique = WatchEngine.uniqueByKey(visits)
        assertEquals(1, unique.size)
        assertEquals("23401402", unique.single().vehicleRef) // the newer SIRI record (13:04 vs 11:58)

        val live = object : LiveDataSource { override suspend fun stopVisits(stopCode: String) = snap }
        var last: WatchState? = null
        val engine = WatchEngine(live, MutableClock(ilTime("14:51:32")), TriggerConfig(pollInterval = 1.seconds))
        val job = launch { engine.run(spec194(TriggerType.DEPARTS_ORIGIN, minutes = 15), { last = it }, { _, _ -> }) }
        testScheduler.advanceTimeBy(100); testScheduler.runCurrent()
        job.cancel()
        assertEquals(1, last!!.buses.size, "keys must be unique (keyed LazyColumn)")
    }
}
