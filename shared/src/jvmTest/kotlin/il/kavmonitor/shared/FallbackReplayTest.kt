package il.kavmonitor.shared

import il.kavmonitor.shared.data.CurlbusParser
import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.TriggerConfig
import il.kavmonitor.shared.trigger.TriggerDetector
import il.kavmonitor.shared.trigger.TriggerEvent
import il.kavmonitor.shared.util.Geo
import il.kavmonitor.shared.watch.StopFallback
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Replays curlbus polls recorded live on 2026-10-06 ~18:00–18:48 around stop 25141 (דרך מנחם בגין/נחמני, #8 on Egged 194),
 * which answered HTTP 500 on every poll (as did 27117). Stand-ins: 20750 (#7, upstream) + 31492 (#10, downstream).
 * Ground truth: GPS fixes of the same trip, from any recorded listing (21472, 20750, 31492), closest to 25141.
 */
class FallbackReplayTest {
    private val stops = stops194()
    private val target = stops.first { it.code == "25141" }
    private val plan = StopFallback.plan(TriggerType.ARRIVES_AT_STOP, stops, target) { it in setOf("20876", "20725", "25141", "27117") }!!
    private val dir = File(requireNotNull(javaClass.getResource("/fixtures/fallback_25141")).toURI())

    private fun polls(stop: String): Map<Instant, List<LiveVisit>> =
        File(dir, stop).listFiles()!!.filter { it.name.endsWith(".json") }.associate { f ->
            val n = f.name.removeSuffix(".json")
            val at = ilTime("${n.substring(0, 2)}:${n.substring(2, 4)}:${n.substring(4, 6)}")
            at to CurlbusParser.parse(f.readText(), stop, at).forLine(37121, "194", 3)
        }

    private class Round(val at: Instant, val derived: List<LiveVisit>, val events: List<TriggerEvent>)

    private fun replay(trigger: TriggerType, lead: Int = 0): List<Round> {
        val up = polls("20750"); val down = polls("31492")
        val d = TriggerDetector(spec194(trigger, target), TriggerConfig())
        return up.keys.intersect(down.keys).sorted().map { t ->
            val derived = StopFallback.derive(plan, up[t], down[t], t, TriggerConfig().gpsFreshMaxAge)
            Round(t, derived, d.onSnapshot(t, derived, reliable = true, leadMinutes = lead))
        }
    }

    /** Closest recorded fix of [trip] to 25141: (fix time, meters). */
    private fun truth(trip: String): Pair<Instant, Double> =
        listOf("21472", "20750", "31492", "33117", "32939", "36053", "20001").flatMap { polls(it).values.flatten() }
            .filter { it.tripId == trip && it.hasGps && it.recordedAt != null }
            .distinctBy { it.recordedAt }
            .map { it.recordedAt!! to Geo.distanceMeters(it.lat!!, it.lon!!, target.lat!!, target.lon!!) }
            .minBy { it.second }

    private fun replayEvery(n: Int): List<Round> {
        val up = polls("20750"); val down = polls("31492")
        val d = TriggerDetector(spec194(TriggerType.ARRIVES_AT_STOP, target), TriggerConfig())
        return up.keys.intersect(down.keys).sorted().filterIndexed { i, _ -> i % n == 0 }.map { t ->
            val derived = StopFallback.derive(plan, up[t], down[t], t, TriggerConfig().gpsFreshMaxAge)
            Round(t, derived, d.onSnapshot(t, derived, reliable = true))
        }
    }

    /**
     * 23399602 (sched. 18:22) reached 25141 at 18:41:39 (GPS fix 4 m from the stop, from the 33117 listing).
     * The fallback estimate (20750 upstream ETA + GTFS distance share) alerted "arriving" at 18:36:54 with an
     * estimated 18:37:42: ≈4 min early, because curlbus' own 20750 ETA was ≈2.5 min optimistic in heavy traffic
     * on Begin (it listed 18:37, the bus passed 20750 at ≈18:39:35). Early is the safe side for an "arrives" alert,
     * and the notification says "הערכה".
     */
    @Test
    fun liveRunAt30sAnd60sCadence() {
        val trip = polls("31492").values.flatten().first { it.vehicleRef == "23399602" }.tripId!!
        val truth = truth(trip)
        assertEquals(ilTime("18:41:39"), truth.first)
        assertTrue(truth.second < 10.0)
        for (n in listOf(1, 2)) { // recorded cadence ≈30 s, and every other poll ≈ the app's 60 s
            val rounds = replayEvery(n)
            val events = rounds.flatMap { it.events }
            val e = events.single { it.vehicleRef == "23399602" }
            val lead = truth.first - e.at
            println("cadence ${30 * n}s: alert ${e.at} (${e.reason}) truth ${truth.first} → ${lead.inWholeSeconds}s early")
            assertTrue(lead > Duration.ZERO && lead < 6.minutes, "alert before the real arrival, ≤ 6 min early: $lead")
            // never more than one alert per bus; 7687869 (passed 25141 at 17:57:37) only from a GPS fix at the stop
            assertEquals(events.size, events.distinctBy { it.vehicleRef }.size)
            events.filter { it.vehicleRef == "7687869" }.forEach { assertEquals("AT_STOP_GPS", it.reason.name) }
            // estimated ETA vs the real arrival, for the evidence table
            rounds.filter { r -> r.derived.any { it.vehicleRef == "23399602" && it.recordedAt != null && it.recordedAt!! > ilTime("18:30:00") } }
                .forEach { r ->
                    val v = r.derived.first { it.vehicleRef == "23399602" }
                    println("  ${r.at} est ${v.eta} err ${((v.eta!! - truth.first).inWholeSeconds)}s")
                }
        }
    }

    @Test
    fun printTrace() {
        val r = replay(TriggerType.ARRIVES_AT_STOP)
        r.forEach { x -> println("${x.at} " + x.derived.joinToString(" | ") { "${it.vehicleRef} eta=${it.eta} gps=${it.recordedAt}" } + x.events.joinToString("") { " >>> ${it.reason} ${it.vehicleRef}" }) }
        r.flatMap { it.events }.forEach { e -> println("truth ${e.vehicleRef}: ${truth(e.tripId!!)} alert ${e.at}") }
    }
}
