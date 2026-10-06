package il.kavmonitor.shared

import il.kavmonitor.shared.data.ErrorKind
import il.kavmonitor.shared.data.FetchError
import il.kavmonitor.shared.model.LeavePlan
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.data.MultiStopFetcher
import il.kavmonitor.shared.route.RouteBusStatus
import il.kavmonitor.shared.route.RouteLiveFetcher
import il.kavmonitor.shared.route.RouteLiveRequest
import il.kavmonitor.shared.route.RouteLiveSnapshot
import il.kavmonitor.shared.trigger.LeaveStatus
import il.kavmonitor.shared.trigger.TriggerEvent
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.ui.BusPhase
import il.kavmonitor.shared.ui.Freshness
import il.kavmonitor.shared.ui.HeroTone
import il.kavmonitor.shared.ui.LineModels
import il.kavmonitor.shared.ui.LineModels.DiagramRow
import il.kavmonitor.shared.ui.WatchRows
import il.kavmonitor.shared.ui.toDetails
import il.kavmonitor.shared.watch.BusView
import il.kavmonitor.shared.watch.StopFallback
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** v0.6: hero card per trigger type, its local countdown, the line diagram and the merged watch rows. */
class LineModelsTest {
    private val t0 = ilTime("17:16:20")

    private fun bus(
        v: String, eta: Instant?, sched: Instant? = ilTime("17:00:00"), leaveAt: Instant? = null, status: LeaveStatus? = null,
        alerted: Boolean = false, gps: Boolean = true, key: String = "trip-$v", estimated: Boolean = false,
    ) = BusView(key = key, vehicleRef = v, tripId = key, eta = eta, scheduledDeparture = sched,
        recordedAt = t0 - 20.seconds, hasFreshGps = gps, distanceToStopMeters = 900.0, alerted = alerted,
        lat = if (gps) 32.0 else null, lon = if (gps) 34.8 else null, leaveAt = leaveAt, leaveStatus = status, etaEstimated = estimated)

    private fun state(spec: WatchSpec, vararg buses: BusView, alerts: List<TriggerEvent> = emptyList(), phase: WatchPhase = WatchPhase.RUNNING,
                      success: Instant? = t0 - 10.seconds, err: FetchError? = null, fallback: Boolean = false) =
        WatchState(spec, phase, ilTime("16:30:00"), ilTime("18:30:00"), lastSuccessAt = success, lastError = err,
            buses = buses.toList(), busesSeen = buses.size, alerts = alerts, leavePlan = spec.leave,
            fallback = if (fallback) StopFallback.plan(spec.trigger, stops194(), spec.stop) { it in setOf("25141", "27117") } else null)

    private fun ev(reason: TriggerReason, at: Instant, key: String = "trip-1") =
        TriggerEvent(key, "1", key, reason, at, ilTime("16:58:00"), null, null)

    private val leaveSpec = spec194(TriggerType.LEAVE_FOR_STOP, STOP_36926).copy(leave = LeavePlan(6, 1))
    private val arriveSpec = spec194(TriggerType.ARRIVES_AT_STOP, STOP_36926)
    private val departSpec = spec194(TriggerType.DEPARTS_ORIGIN)

    private fun hero(s: WatchState, now: Instant = t0) =
        LineModels.hero(s, WatchRows.build(s, stops194(), null, emptyMap(), now), now)

    // ------------------------------------------------------------ time to leave

    @Test fun leaveHeroStates() {
        val plenty = hero(state(leaveSpec, bus("7692269", t0 + 17.minutes, leaveAt = t0 + 10.minutes, status = LeaveStatus.WAIT)))
        assertEquals(HeroTone.PLENTY, plenty.tone); assertEquals("צא בעוד", plenty.label); assertEquals("10 דק׳", plenty.big)
        assertTrue("האוטובוס ב-17:33 (בעוד 17 דק׳)" in plenty.sub, plenty.sub)

        val ready = hero(state(leaveSpec, bus("7692269", t0 + 10.minutes, leaveAt = t0 + 3.minutes, status = LeaveStatus.WAIT)))
        assertEquals(HeroTone.GET_READY, ready.tone); assertEquals("3 דק׳", ready.big)

        val now = hero(state(leaveSpec, bus("7692269", t0 + 7.minutes, leaveAt = t0, status = LeaveStatus.LEAVE_NOW)))
        assertEquals(HeroTone.NOW, now.tone); assertEquals("צא עכשיו", now.big); assertNull(now.label)

        val alerted = hero(state(leaveSpec, bus("7692269", t0 + 6.minutes, leaveAt = t0 - 1.minutes, status = LeaveStatus.ALERTED)))
        assertEquals(HeroTone.NOW, alerted.tone)

        val none = hero(state(leaveSpec, bus("23373402", t0 + 4.minutes, leaveAt = t0 - 3.minutes, status = LeaveStatus.NOT_CATCHABLE)))
        assertEquals(HeroTone.NONE, none.tone); assertEquals("אין אוטובוס שאפשר להספיק", none.big)
        assertTrue("לא תספיק (6 דק׳ הליכה)" in none.sub, none.sub)

        // the earliest catchable bus wins over a not-catchable earlier one
        val mixed = hero(state(leaveSpec,
            bus("23373402", t0 + 4.minutes, leaveAt = t0 - 3.minutes, status = LeaveStatus.NOT_CATCHABLE),
            bus("7692269", t0 + 20.minutes, leaveAt = t0 + 13.minutes, status = LeaveStatus.WAIT)))
        assertEquals(HeroTone.PLENTY, mixed.tone); assertEquals("13 דק׳", mixed.big)

        // broken stop: the time is a neighbour-based estimate
        val est = hero(state(leaveSpec, bus("7692269", t0 + 17.minutes, leaveAt = t0 + 10.minutes, status = LeaveStatus.WAIT, estimated = true)))
        assertTrue(est.sub.endsWith("(הערכה)"), est.sub)
    }

    /** The countdown between polls is a pure function of the last poll + the clock: same state, later `now`. */
    @Test fun leaveCountdownBetweenPolls() {
        val s = state(leaveSpec, bus("7692269", t0 + 12.minutes, leaveAt = t0 + 5.minutes, status = LeaveStatus.WAIT))
        val seq = listOf(0, 61, 119, 120, 121, 179, 240, 299, 300).map { sec -> hero(s, t0 + sec.seconds).let { it.tone to it.big } }
        assertEquals(listOf(
            HeroTone.PLENTY to "5 דק׳", HeroTone.PLENTY to "4 דק׳", HeroTone.PLENTY to "4 דק׳", HeroTone.GET_READY to "3 דק׳",
            HeroTone.GET_READY to "3 דק׳", HeroTone.GET_READY to "3 דק׳", HeroTone.GET_READY to "1 דק׳", HeroTone.GET_READY to "1 דק׳",
            HeroTone.NOW to "צא עכשיו",
        ), seq)
        // past the 90 s grace with no new poll: nothing catchable any more
        assertEquals(HeroTone.NONE, hero(s, t0 + 5.minutes + 91.seconds).tone)
    }

    // ------------------------------------------------------------ departs origin

    @Test fun departsHeroStates() {
        val next = state(departSpec, bus("7628169", t0 + 7.minutes, sched = t0 + 7.minutes, gps = false))
        hero(next).let { assertEquals(HeroTone.PLENTY, it.tone); assertEquals("האוטובוס יוצא בעוד", it.label); assertEquals("7 דק׳", it.big) }
        hero(next, t0 + 4.minutes + 30.seconds).let { assertEquals(HeroTone.GET_READY, it.tone); assertEquals("3 דק׳", it.big) }
        hero(next, t0 + 7.minutes).let { assertEquals(HeroTone.NOW, it.tone); assertEquals("יוצא עכשיו", it.big) }

        val left = state(departSpec, alerts = listOf(ev(TriggerReason.LEFT_ORIGIN_DROPPED, t0 - 1.minutes)))
        hero(left).let { assertEquals(HeroTone.NOW, it.tone); assertEquals("האוטובוס יצא", it.big); assertTrue("יצא ב-17:15" in it.sub, it.sub) }
        // two minutes later the hero moves on
        hero(left, t0 + 2.minutes).let { assertEquals(HeroTone.NONE, it.tone); assertEquals("אין כרגע אוטובוס בתחנת המוצא", it.big) }
        val sched = state(departSpec, alerts = listOf(ev(TriggerReason.LEFT_ORIGIN_SCHEDULED, t0)))
        assertTrue("לפי הלו״ז" in hero(sched).sub)
    }

    // ------------------------------------------------------------ arrives at stop

    @Test fun arrivesHeroStates() {
        val s = state(arriveSpec, bus("7692269", t0 + 9.minutes))
        hero(s).let { assertEquals(HeroTone.PLENTY, it.tone); assertEquals("מגיע לתחנה בעוד", it.label); assertEquals("9 דק׳", it.big) }
        hero(s, t0 + 6.minutes).let { assertEquals(HeroTone.GET_READY, it.tone); assertEquals("3 דק׳", it.big) }
        hero(s, t0 + 9.minutes).let { assertEquals(HeroTone.NOW, it.tone); assertEquals("מגיע עכשיו", it.big) }

        val arrived = state(arriveSpec, alerts = listOf(ev(TriggerReason.DROPPED_AT_STOP, t0 - 30.seconds)))
        hero(arrived).let { assertEquals(HeroTone.NOW, it.tone); assertEquals("הגיע לתחנה", it.big) }
        assertEquals("אין כרגע אוטובוס בדרך לתחנה", hero(state(arriveSpec)).big)
        assertEquals(HeroTone.NONE, hero(state(arriveSpec)).tone)

        val fb = state(arriveSpec.copy(stop = stops194().first { it.code == "25141" }), bus("23399602", t0 + 12.minutes, estimated = true), fallback = true)
        hero(fb).let { assertEquals(HeroTone.PLENTY, it.tone); assertTrue(it.sub.endsWith("(הערכה)"), it.sub) }
    }

    @Test fun heroWhenNotRunningOrNoData() {
        assertEquals("המעקב נעצר", hero(state(arriveSpec, phase = WatchPhase.STOPPED)).big)
        assertEquals("המעקב הסתיים", hero(state(arriveSpec, phase = WatchPhase.TIMED_OUT)).big)
        hero(state(arriveSpec, success = null)).let { assertEquals(HeroTone.NONE, it.tone); assertEquals("ממתין לעדכון ראשון", it.big) }
        hero(state(arriveSpec, success = null, err = FetchError(ErrorKind.NETWORK))).let { assertEquals(HeroTone.NONE, it.tone); assertTrue("מנסה שוב" in it.sub) }
    }

    @Test fun freshnessDot() {
        val d = bus("1", t0 + 5.minutes).toDetails(arriveSpec, stops194())
        assertEquals(Freshness.FRESH, LineModels.freshness(d, t0))
        assertEquals(Freshness.AGING, LineModels.freshness(d, t0 + 3.minutes))
        assertEquals(Freshness.SCHEDULED, LineModels.freshness(d, t0 + 6.minutes))
        assertEquals(Freshness.SCHEDULED, LineModels.freshness(bus("2", t0, gps = false).toDetails(arriveSpec, stops194()), t0))
    }

    // ------------------------------------------------------------ line diagram / full route

    private val stops = stops194()
    private fun snapshot(round: String = "193447"): RouteLiveSnapshot = runBlocking {
        val live = FullRouteReplayLive(round)
        val f = RouteLiveFetcher(live, RouteLiveRequest(37121, "194", 3, stops, targetStopCode = "36926"), MutableClock(live.fetchedAt),
            multi = MultiStopFetcher(live, null))
        f.refresh(); f.refresh()
    }

    @Test fun diagramKeepsKeyStopsCollapsesQuietRunsAndMarksBrokenStops() {
        val snap = snapshot()
        val user = stops.indexOfFirst { it.code == "36926" }
        val rows = LineModels.diagram(stops, snap, user, snap.fetchedAt)
        val stopRows = rows.filterIsInstance<DiagramRow.Stop>()
        val collapsed = rows.filterIsInstance<DiagramRow.Collapsed>()
        // every stop is either shown or inside exactly one collapsed run
        assertEquals(stops.size, stopRows.size + collapsed.sumOf { it.count })
        assertEquals(stopRows.map { it.index }.distinct(), stopRows.map { it.index })
        assertTrue(collapsed.isNotEmpty() && collapsed.all { it.count >= 2 }, rows.joinToString("\n"))
        assertTrue(stopRows.first().isOrigin && stopRows.first().index == 0)
        assertTrue(stopRows.last().isDestination && stopRows.last().index == stops.lastIndex)
        assertEquals(user, stopRows.single { it.isUser }.index)
        // shown stops that curlbus can't answer for are marked
        stopRows.forEach { assertEquals(stops[it.index].code in BROKEN_194, it.broken, "stop ${it.index}") }
        // every active bus with a position is drawn exactly once, with an ETA to the user's stop
        val drawn = rows.flatMap { r -> when (r) { is DiagramRow.Stop -> r.busesAt; is DiagramRow.Between -> r.buses; else -> emptyList() } }
        val placed = snap.buses.filter { it.status == RouteBusStatus.NOT_STARTED || (it.position != null && !it.offRoute) }
        assertEquals(placed.map { it.key }.sorted(), drawn.map { it.key }.sorted())
        drawn.filter { !it.notStarted }.forEach { assertNotNull(it.etaMinutes, it.toString()) }
        // not-started trips wait at the origin
        drawn.filter { it.notStarted }.forEach { b -> assertTrue(b in stopRows.first().busesAt) }
        // stops next to a bus are never collapsed
        rows.withIndex().filter { it.value is DiagramRow.Between }.forEach { (i, r) ->
            assertTrue(rows[i - 1] is DiagramRow.Stop && rows[i + 1] is DiagramRow.Stop, "bus between ${(r as DiagramRow.Between).afterIndex}")
        }
    }

    @Test fun etaAtStopUsesCurlbusThenEstimates() {
        val snap = snapshot()
        val user = stops.indexOfFirst { it.code == "36926" }
        val far = snap.active.single { it.tripId == "585301559_061026" }
        assertEquals<Pair<Instant, Boolean>?>(far.etaByStop.getValue("36926") to false, LineModels.etaAtStop(far, stops, user))
        // the not-started trip is listed only at the first stops: its ETA at 36926 is estimated from the schedule offsets
        val ns = snap.buses.single { it.tripId == "585301561_061026" }
        assertNull(ns.etaByStop["36926"])
        val (eta, est) = assertNotNull(LineModels.etaAtStop(ns, stops, user))
        assertTrue(est)
        assertTrue(eta > (ns.etaByStop.values.maxOrNull() ?: Instant.DISTANT_PAST), "after its last listed stop")
        // a stop before the bus (already passed): no ETA
        val passed = stops.indexOfFirst { far.etaByStop.containsKey(it.code) } - 1
        if (passed > 0 && far.etaByStop[stops[passed].code] == null) assertNull(LineModels.etaAtStop(far.copy(status = RouteBusStatus.ACTIVE), stops, passed))
    }

    @Test fun watchRowsMergeTheStopListWithTheRouteView() {
        val snap = snapshot()
        val far = snap.active.single { it.tripId == "585301559_061026" }
        val ns = snap.buses.single { it.tripId == "585301561_061026" }
        val now = snap.fetchedAt
        val eta = far.etaByStop.getValue("36926")
        val v = BusView(key = far.key, vehicleRef = far.vehicleRef, tripId = far.tripId, eta = eta, scheduledDeparture = far.scheduledStart,
            recordedAt = far.recordedAt, hasFreshGps = true, distanceToStopMeters = 5000.0, alerted = false, lat = 32.0, lon = 34.8,
            leaveAt = eta - 7.minutes, leaveStatus = LeaveStatus.WAIT)
        val s = state(leaveSpec, v)
        val rows = WatchRows.build(s, stops, snap, emptyMap(), now)
        val merged = rows.first { it.key == far.key }
        assertEquals(eta, merged.eta)
        assertEquals(LeaveStatus.WAIT, merged.leaveStatus)
        assertEquals(far.nextStop, merged.nextStop, "position from the full-route view")
        assertEquals(BusPhase.ACTIVE, merged.phase)
        // the not-started trip isn't listed at the stop yet: added with an estimated ETA and leave time
        val extra = rows.single { it.key == ns.key }
        assertTrue(extra.etaEstimated)
        assertEquals(extra.eta!! - 7.minutes, extra.leaveAt)
        assertEquals(rows.sortedWith(compareBy(nullsLast()) { it.eta }), rows)
        // a finished watch shows only its own last buses
        assertEquals(listOf(far.key), WatchRows.build(s.copy(phase = WatchPhase.STOPPED), stops, snap, emptyMap(), now).map { it.key })
    }
}
