package il.kavmonitor.shared

import il.kavmonitor.shared.model.LeavePlan
import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.LeaveStatus
import il.kavmonitor.shared.trigger.TriggerDetector
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.trigger.WalkTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LeaveTimeTest {
    @Test
    fun walkingTimeIsStraightLineTimesDetourOverSpeedRoundedUp() {
        assertEquals(0, WalkTime.minutes(0.0))
        assertEquals(1, WalkTime.minutes(50.0))      // 50 × 1.3 / 80 = 0.81 → 1
        assertEquals(2, WalkTime.minutes(80.0))      // 1.3 → 2
        assertEquals(7, WalkTime.minutes(400.0))     // 6.5 → 7
        assertEquals(10, WalkTime.minutes(800.0 / 1.3)) // exactly 10.0 → 10 (no float noise)
        assertEquals(17, WalkTime.minutes(1000.0))   // 16.25 → 17
        assertEquals(60, WalkTime.minutes(50_000.0)) // capped
        val plan = LeavePlan(walkMinutes = 7, bufferMinutes = 1)
        assertEquals(8, plan.leadMinutes)
    }

    private val t0 = ilTime("16:00:00")
    private val spec = spec194(TriggerType.LEAVE_FOR_STOP, STOP_36926)

    private fun bus(id: String, eta: Instant?, gps: Boolean = true) = LiveVisit(
        stopCode = STOP_36926.code, lineName = "194", lineRef = 37121, operatorRef = 3, vehicleRef = "v$id", tripId = "trip$id",
        eta = eta, originDepartureTime = t0 - 20.minutes,
        recordedAt = if (gps) null else t0 - 5.minutes, lat = if (gps) 31.99 else null, lon = if (gps) 34.80 else null,
    ).let { if (gps) it.copy(recordedAt = Instant.DISTANT_FUTURE) else it }

    @Test
    fun firesOnceWhenLeaveTimeIsReachedForTheNextCatchableBus() {
        val d = TriggerDetector(spec)
        val lead = 8 // ETA 16:15 → leave 16:07
        val a = bus("A", ilTime("16:15:00"))
        assertTrue(d.onSnapshot(t0, listOf(a), leadMinutes = lead).isEmpty())
        assertEquals(LeaveStatus.WAIT, d.leaveInfo(a, t0, lead).status)
        assertEquals(ilTime("16:07:00"), d.leaveInfo(a, t0, lead).leaveAt)
        assertTrue(d.onSnapshot(ilTime("16:06:40"), listOf(a), leadMinutes = lead).isEmpty())
        val ev = d.onSnapshot(ilTime("16:07:10"), listOf(a), leadMinutes = lead).single()
        assertEquals(TriggerReason.LEAVE_NOW, ev.reason)
        assertEquals("vA", ev.vehicleRef)
        assertEquals(lead, ev.leadMinutes)
        assertEquals(ilTime("16:15:00"), ev.eta)
        assertTrue(d.onSnapshot(ilTime("16:07:45"), listOf(a), leadMinutes = lead).isEmpty(), "never twice")
        assertEquals(LeaveStatus.ALERTED, d.leaveInfo(a, ilTime("16:07:45"), lead).status)
    }

    @Test
    fun busAlreadyUncatchableAtFirstSightIsSkippedAndTheNextOneAlerts() {
        val d = TriggerDetector(spec)
        val lead = 8
        val gone = bus("A", ilTime("16:06:00")) // leave 15:58, 2 min ago → not catchable
        val edge = bus("B", ilTime("16:07:30")) // leave 15:59:30, 30 s ago → still catchable (grace 1 min) → fires now
        val later = bus("C", ilTime("16:40:00"))
        assertEquals(LeaveStatus.NOT_CATCHABLE, d.leaveInfo(gone, t0, lead).status)
        val ev = d.onSnapshot(t0, listOf(gone, edge, later), leadMinutes = lead).single()
        assertEquals("vB", ev.vehicleRef)
        assertEquals(LeaveStatus.NOT_CATCHABLE, d.leaveInfo(gone, t0 + 1.minutes, lead).status)
        // A is never alerted, C alerts at its own leave time (16:32)
        assertTrue(d.onSnapshot(ilTime("16:31:30"), listOf(gone, later), leadMinutes = lead).isEmpty())
        assertEquals("vC", d.onSnapshot(ilTime("16:32:05"), listOf(later), leadMinutes = lead).single().vehicleRef)
    }

    @Test
    fun etaMovingLaterAfterTheAlertDoesNotReAlert_andEarlierEtaBringsAlertForward() {
        val d = TriggerDetector(spec)
        val lead = 5
        // B: not alerted yet, ETA slides later then jumps earlier → its alert follows the newest ETA
        assertTrue(d.onSnapshot(t0, listOf(bus("B", ilTime("16:20:00"))), leadMinutes = lead).isEmpty())
        assertTrue(d.onSnapshot(ilTime("16:10:00"), listOf(bus("B", ilTime("16:22:00"))), leadMinutes = lead).isEmpty()) // leave 16:17
        val ev = d.onSnapshot(ilTime("16:13:00"), listOf(bus("B", ilTime("16:18:00"))), leadMinutes = lead).single()      // leave 16:13
        assertEquals("vB", ev.vehicleRef)
        // after the alert the ETA moves later and earlier again: no more alerts for B
        assertTrue(d.onSnapshot(ilTime("16:14:00"), listOf(bus("B", ilTime("16:24:00"))), leadMinutes = lead).isEmpty())
        assertTrue(d.onSnapshot(ilTime("16:20:00"), listOf(bus("B", ilTime("16:21:00"))), leadMinutes = lead).isEmpty())
    }

    @Test
    fun noBackToBackAlertsForBusesCloseTogether() {
        val d = TriggerDetector(spec)
        val lead = 6
        val a = bus("A", ilTime("16:10:00")) // leave 16:04
        val b = bus("B", ilTime("16:11:00")) // leave 16:05 – within 3 min of A's alert
        assertEquals("vA", d.onSnapshot(ilTime("16:04:00"), listOf(a, b), leadMinutes = lead).single().vehicleRef)
        assertTrue(d.onSnapshot(ilTime("16:05:10"), listOf(a, b), leadMinutes = lead).isEmpty())
        assertTrue(d.onSnapshot(ilTime("16:06:30"), listOf(b), leadMinutes = lead).isEmpty())
        // by the time the gap is over B can no longer be caught
        assertTrue(d.onSnapshot(ilTime("16:07:10"), listOf(b), leadMinutes = lead).isEmpty())
        assertEquals(LeaveStatus.NOT_CATCHABLE, d.leaveInfo(b, ilTime("16:07:10"), lead).status)
    }

    @Test
    fun scheduledOnlyBusAlertsAndIsMarkedNotLive_andWalkTimeChangesApply() {
        val d = TriggerDetector(spec)
        val s = bus("S", ilTime("16:30:00"), gps = false)
        assertTrue(d.onSnapshot(ilTime("16:20:00"), listOf(s), leadMinutes = 8).isEmpty()) // leave 16:22
        // the user walked closer (walk time refreshed while the app was visible): lead 4 → leave 16:26
        assertTrue(d.onSnapshot(ilTime("16:22:30"), listOf(s), leadMinutes = 4).isEmpty())
        val ev = d.onSnapshot(ilTime("16:26:00"), listOf(s), leadMinutes = 4).single()
        assertFalse(ev.live)
        assertEquals(4, ev.leadMinutes)
    }

    @Test
    fun busWithoutEtaNeverAlerts() {
        val d = TriggerDetector(spec)
        val n = bus("N", null)
        assertTrue(d.onSnapshot(t0, listOf(n), leadMinutes = 3).isEmpty())
        assertEquals(LeaveStatus.NO_ETA, d.leaveInfo(n, t0, 3).status)
    }

    @Test
    fun duplicateTripListedTwiceAlertsOnce() {
        val d = TriggerDetector(spec)
        val a1 = bus("A", ilTime("16:05:00"))
        val a2 = a1.copy(vehicleRef = "other")
        assertEquals(1, d.onSnapshot(t0 + 30.seconds, listOf(a1, a2), leadMinutes = 5).size)
    }
}
