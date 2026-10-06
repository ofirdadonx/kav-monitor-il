package il.kavmonitor.shared

import il.kavmonitor.shared.data.CurlbusParser
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.TriggerConfig
import il.kavmonitor.shared.trigger.TriggerDetector
import il.kavmonitor.shared.trigger.TriggerEvent
import il.kavmonitor.shared.trigger.TriggerReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays real curlbus polls recorded on 2026-10-06 for Egged 194 (line_ref 37121) through the detector,
 * exactly as the watch engine would (line filter + reliability check).
 */
class TriggerReplayTest {
    private fun replay(stop: RouteStop, trigger: TriggerType, polls: List<String>, cfg: TriggerConfig = TriggerConfig.Default):
        List<Pair<String, List<TriggerEvent>>> {
        val spec = spec194(trigger, stop)
        val d = TriggerDetector(spec, cfg)
        return polls.map { name ->
            val hhmmss = name.substringAfter('_').let { "${it.substring(0, 2)}:${it.substring(2, 4)}:${it.substring(4, 6)}" }
            val snap = CurlbusParser.parse(fixture("replay/$name.json"), stop.code, ilTime(hhmmss))
            val visits = snap.forLine(spec.lineRef, spec.lineName, spec.operatorRef)
            name to d.onSnapshot(snap.fetchedAt, visits, reliable = snap.sourceErrors == null && snap.allVisits.isNotEmpty())
        }
    }

    @Test
    fun originDepartureOf1002Bus() {
        // 10:01:06 bus 18379504 (sched 10:02) listed at Savidor, no GPS, record from 05:55.
        // 10:01:42 it is gone from the origin list (its first GPS fix, 10:00:31, is ~50 m from the platform).
        // 10:02:19 the next trip (10:32, vehicle 18330504) appears — the prototype would have alerted on THAT.
        val r = replay(ORIGIN_20001, TriggerType.DEPARTS_ORIGIN, listOf("20001_100106", "20001_100142", "20001_100219"))
        assertTrue(r[0].second.isEmpty())
        val ev = r[1].second.single()
        assertEquals(TriggerReason.LEFT_ORIGIN_DROPPED, ev.reason)
        assertEquals("18379504", ev.vehicleRef)
        assertEquals(ilTime("10:02:00"), ev.scheduledDeparture)
        assertTrue(r[2].second.isEmpty(), "a newly listed trip is not a departure")
    }

    @Test
    fun arrivalAtShlomoLeviByEta() {
        // Bus 23401702: 10:07:17 live fix (10:06:24) ~600 m away, ETA 10:08 → fires "arriving".
        val r = replay(STOP_36926, TriggerType.ARRIVES_AT_STOP, listOf("36926_100640", "36926_100717", "36926_100754"))
        assertTrue(r[0].second.isEmpty())
        val ev = r[1].second.single()
        assertEquals(TriggerReason.AT_STOP_ETA, ev.reason)
        assertEquals("23401702", ev.vehicleRef)
        assertTrue(r[2].second.isEmpty(), "fires once per trip")
    }

    @Test
    fun arrivalAtShlomoLeviByDropWhenEtaRuleDisabled() {
        val cfg = TriggerConfig(arriveEtaThreshold = kotlin.time.Duration.ZERO)
        val r = replay(STOP_36926, TriggerType.ARRIVES_AT_STOP, listOf("36926_100640", "36926_100717", "36926_100754"), cfg)
        assertTrue(r[0].second.isEmpty() && r[1].second.isEmpty())
        assertEquals(TriggerReason.DROPPED_AT_STOP, r[2].second.single().reason)
    }

    @Test
    fun secondStopNearOriginDoesNotFireOnGpsRadiusWhileBusIsAtPlatform() {
        val stop2 = RouteStop(2, "20762", "ת.רק''ל ארלוזורוב/על פרשת דרכים", "תל אביב יפו", 32.081538, 34.796208)
        val r = replay(stop2, TriggerType.ARRIVES_AT_STOP, listOf("20762_100256", "20762_100333"))
        // bus at the Savidor platform (~180 m away) with ETA 10:03 at 10:02:56 → "arriving in <1 min", not "at stop by GPS"
        assertEquals(listOf(TriggerReason.AT_STOP_ETA), r[0].second.map { it.reason })
        assertTrue(r[0].second.single().distanceMeters!! > 150)
    }

    @Test
    fun arrivalAtAzrieliInHeavyTraffic() {
        // Bus 18379504 crawled out of Savidor: 10:09:08 fix ~840 m away (ETA 10:11), 10:09:45 fix ~650 m (ETA 10:11),
        // 10:10:27 same fix, ETA now 33 s away → "arriving"; 10:11:03 dropped off the Azrieli list (arrived).
        val polls = listOf("21472_100908", "21472_100945", "21472_101027", "21472_101103")
        val r = replay(STOP_21472, TriggerType.ARRIVES_AT_STOP, polls)
        assertEquals(listOf(0, 0, 1, 0), r.map { it.second.size })
        assertEquals(TriggerReason.AT_STOP_ETA, r[2].second.single().reason)
        assertEquals("18379504", r[2].second.single().vehicleRef)

        val noEta = replay(STOP_21472, TriggerType.ARRIVES_AT_STOP, polls, TriggerConfig(arriveEtaThreshold = kotlin.time.Duration.ZERO))
        assertEquals(TriggerReason.DROPPED_AT_STOP, noEta[3].second.single().reason)
    }

    @Test
    fun originDepartureOf1017Bus() {
        // 10:15:28 vehicle 7628169 (sched 10:17) listed; 10:16:04 gone (first GPS fix 10:15:22 at the platform).
        val r = replay(ORIGIN_20001, TriggerType.DEPARTS_ORIGIN, listOf("20001_101528", "20001_101604"))
        assertTrue(r[0].second.isEmpty())
        assertEquals(listOf("7628169" to TriggerReason.LEFT_ORIGIN_DROPPED), r[1].second.map { it.vehicleRef to it.reason })
    }
}
