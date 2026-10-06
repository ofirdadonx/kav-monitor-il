package il.kavmonitor.shared

import il.kavmonitor.shared.data.CurlbusParser
import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.LeaveInfo
import il.kavmonitor.shared.trigger.LeaveStatus
import il.kavmonitor.shared.trigger.TriggerDetector
import il.kavmonitor.shared.trigger.TriggerEvent
import il.kavmonitor.shared.trigger.TriggerReason
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Replays the live "time to leave" run of 2026-10-06 16:25–17:31 (`leaveLiveSmoke 36926 6 80`): every curlbus poll of
 * stop 36926 (Shlomo Levi/Sha'ar Yafo, #13 on Egged 194), recorded every ~35 s and trimmed by
 * `scripts/trim_leave_fixtures.py`. Walk 6 min + 1 min buffer = 7 min lead, as in the live run.
 */
class LeaveReplayTest {
    private class Poll(val at: Instant, val visits: List<LiveVisit>, val events: List<TriggerEvent>, val info: Map<String, LeaveInfo>)

    private fun replay(lead: Int): List<Poll> {
        val spec = spec194(TriggerType.LEAVE_FOR_STOP, STOP_36926)
        val d = TriggerDetector(spec)
        val dir = File(requireNotNull(javaClass.getResource("/fixtures/leave_36926")).toURI())
        return dir.listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.name }.map { f ->
            val n = f.name.removeSuffix(".json")
            val snap = CurlbusParser.parse(f.readText(), "36926", ilTime("${n.substring(0, 2)}:${n.substring(2, 4)}:${n.substring(4, 6)}"))
            val visits = snap.forLine(spec.lineRef, spec.lineName, spec.operatorRef)
            val events = d.onSnapshot(snap.fetchedAt, visits, reliable = snap.sourceErrors == null && snap.allVisits.isNotEmpty(), leadMinutes = lead)
            Poll(snap.fetchedAt, visits, events, visits.associate { it.vehicleRef.orEmpty() to d.leaveInfo(it, snap.fetchedAt, lead) })
        }
    }

    @Test
    fun liveRunAt36926_walk6() {
        val polls = replay(lead = 7)
        val first = polls.first()
        assertEquals(ilTime("16:25:36"), first.at)
        // 7695769 (ETA 16:31) needed leaving at 16:24, 1.5 min before it was first seen: skipped, never alerts
        assertEquals(LeaveInfo(ilTime("16:24:00"), LeaveStatus.NOT_CATCHABLE), first.info["7695769"])
        assertEquals(LeaveInfo(ilTime("16:47:00"), LeaveStatus.WAIT), first.info["23401502"])

        val alerts = polls.flatMap { p -> p.events.map { p.at to it } }
        // bus 23401502: ETA slid 16:54 → 16:58 while waiting; the alert follows the newest ETA, once, at leaveAt 16:51
        val (at1, ev1) = alerts.first()
        assertEquals(ilTime("16:51:00"), at1)
        assertEquals(TriggerReason.LEAVE_NOW, ev1.reason)
        assertEquals("23401502", ev1.vehicleRef)
        assertEquals(ilTime("16:58:00"), ev1.eta)
        assertTrue(ev1.live)
        assertEquals(7, ev1.leadMinutes)
        assertTrue(alerts.none { it.second.vehicleRef == "7695769" })
        assertEquals(1, alerts.count { it.second.vehicleRef == "23401502" }, "ETA moving later after the alert (→ 17:00) must not re-alert")
        // it was last listed at 16:59:52 and gone at 17:00:28 (arrived/passed): walking 6 min from 16:51:00 → at the stop 16:57,
        // ~3 min before the bus
        val lastSeen = polls.last { p -> p.visits.any { it.vehicleRef == "23401502" } }.at
        assertEquals(ilTime("16:59:52"), lastSeen)
        assertTrue(lastSeen > at1 + 6.minutes)
        // no alert before leaveAt
        polls.filter { it.at < ilTime("16:51:00") }.forEach { assertTrue(it.events.isEmpty(), "early alert at ${it.at}") }
        // the next bus (23459302): ETA 17:16 when first seen, slid to 17:30; alert at 17:23:28 (leaveAt 17:23)
        assertEquals(LeaveStatus.WAIT, polls.single { it.at == ilTime("17:05:11") }.info["23459302"]?.status)
        val (at2, ev2) = alerts[1]
        assertEquals(ilTime("17:23:28"), at2)
        assertEquals("23459302", ev2.vehicleRef)
        assertEquals(ilTime("17:30:00"), ev2.eta)
        assertTrue(ev2.live)
        val lastSeen2 = polls.last { p -> p.visits.any { it.vehicleRef == "23459302" } }.at
        assertEquals(ilTime("17:30:33"), lastSeen2)
        assertTrue(lastSeen2 > at2 + 6.minutes)
        assertEquals(2, alerts.size, "one alert per bus; 23401902 (leave 17:43) is after the end of the recording")
    }

    @Test
    fun longerWalkMakesBusesUncatchableAtFirstSight() {
        // 31 min walk + 1 buffer: 23401502 (ETA 16:54 at 16:25:36) needed leaving at 16:22; 23459302 (ETA 17:16 when
        // first seen at 16:46:17) needed 16:44. Both are skipped, nothing alerts.
        val polls = replay(lead = 32)
        assertEquals(LeaveStatus.NOT_CATCHABLE, polls.first().info["23401502"]?.status)
        assertEquals(LeaveStatus.NOT_CATCHABLE, polls.single { it.at == ilTime("16:46:17") }.info["23459302"]?.status)
        assertTrue(polls.filter { it.at <= ilTime("17:05:11") }.all { it.events.isEmpty() })
        // 23401902 (ETA 17:39 at 17:09:18) is not catchable either
        assertEquals(LeaveStatus.NOT_CATCHABLE, polls.single { it.at == ilTime("17:09:18") }.info["23401902"]?.status)
    }
}
