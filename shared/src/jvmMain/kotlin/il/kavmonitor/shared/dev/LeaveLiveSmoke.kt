package il.kavmonitor.shared.dev

import il.kavmonitor.shared.data.CurlbusParser
import il.kavmonitor.shared.data.RouteRepository
import il.kavmonitor.shared.data.StrideStaticDataSource
import il.kavmonitor.shared.data.createHttpClient
import il.kavmonitor.shared.model.LeavePlan
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.trigger.TriggerConfig
import il.kavmonitor.shared.trigger.TriggerDetector
import il.kavmonitor.shared.util.Geo
import il.kavmonitor.shared.util.israelClock
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Live "time to leave" run: polls ONE boarding stop like the real watch (one curlbus call per 35 s), runs the LEAVE_FOR_STOP
 * detector with a fixed walk time, saves every raw response (for replay tests) and measures, per alerted bus, when the bus
 * really reached the stop (first fresh GPS fix ≤ 150 m from the stop, or the poll where a live bus dropped off the list).
 *   ./gradlew :shared:leaveLiveSmoke -PstopCode=36926 -PwalkMinutes=6 -Pminutes=75 -PoutDir=/tmp/leave_36926
 */
fun main(args: Array<String>) = runBlocking {
    val stopCode = args.getOrElse(0) { "36926" }
    val walk = args.getOrElse(1) { "6" }.toInt()
    val minutes = args.getOrElse(2) { "60" }.toInt()
    val outDir = File(args.getOrElse(3) { "/tmp/leave_$stopCode" }).apply { mkdirs() }
    val config = TriggerConfig.Default
    val client = createHttpClient()
    val repo = RouteRepository(StrideStaticDataSource(client))
    val dir = repo.directions("194").first { it.operatorRef == 3 && it.parsedName.origin.stop.contains("סבידור") }
    val stops = repo.stops(dir).stops
    val stop = stops.first { it.code == stopCode }
    val plan = LeavePlan(walkMinutes = walk, bufferMinutes = TriggerConfig.LEAVE_BUFFER_MINUTES, manual = true)
    val spec = WatchSpec(dir.lineRef, dir.routeShortName, dir.operatorRef, dir.agencyName, dir.destinationLabel,
        TriggerType.LEAVE_FOR_STOP, stop, stops.first(), minutes, plan)
    val det = TriggerDetector(spec, config)
    println("== LEAVE_FOR_STOP @ ${stop.sequence}. ${stop.code} ${stop.name}: walk $walk + buffer ${plan.bufferMinutes} = lead ${plan.leadMinutes} min, $minutes min ==")

    data class Alert(val key: String, val veh: String?, val at: Instant, val eta: Instant?, val live: Boolean)
    val alerts = mutableListOf<Alert>()
    val arrival = mutableMapOf<String, Pair<Instant, String>>()
    val lastSeen = mutableMapOf<String, Pair<Instant, Boolean>>() // key -> (last seen, had GPS)
    val firstSeenStatus = mutableMapOf<String, String>()
    val end = Clock.System.now() + kotlin.time.Duration.parse("${minutes}m")
    while (Clock.System.now() < end) {
        val now = Clock.System.now()
        val body = try {
            client.get("https://curlbus.app/$stopCode").bodyAsText()
        } catch (e: Exception) {
            println("[${now.israelClock(true)}] ERROR ${e.message?.lineSequence()?.first()}"); delay(config.pollInterval); continue
        }
        File(outDir, now.israelClock(true).replace(":", "") + ".json").writeText(body)
        val snap = CurlbusParser.parse(body, stopCode, now)
        val visits = snap.forLine(spec.lineRef, spec.lineName, spec.operatorRef)
        val events = det.onSnapshot(now, visits, reliable = snap.sourceErrors == null && snap.allVisits.isNotEmpty(), leadMinutes = plan.leadMinutes)
        val present = visits.map { it.key }.toSet()
        // live buses that dropped off the list → reached/passed the stop
        lastSeen.filter { (k, v) -> k !in present && v.second && k !in arrival }.forEach { (k, v) -> arrival[k] = now to "dropped (last seen ${v.first.israelClock(true)})" }
        lastSeen.keys.retainAll(present + lastSeen.keys.filter { it in arrival })
        val line = visits.groupBy { it.key }.values.map { g -> g.maxBy { if (it.hasGps) 1 else 0 } }.sortedBy { it.eta }.joinToString(" | ") { v ->
            val d = if (v.lat != null && v.lon != null && stop.lat != null && stop.lon != null) Geo.distanceMeters(v.lat, v.lon, stop.lat, stop.lon) else null
            val fresh = v.hasGps && v.recordedAt != null && now - v.recordedAt <= config.gpsFreshMaxAge
            if (fresh && d != null && d <= config.arriveRadiusMeters && v.key !in arrival) arrival[v.key] = v.recordedAt!! to "GPS ${d.toInt()} m"
            lastSeen[v.key] = now to (v.hasGps || (lastSeen[v.key]?.second ?: false))
            val info = det.leaveInfo(v, now, plan.leadMinutes)
            firstSeenStatus.getOrPut(v.key) { info.status.name }
            "${v.vehicleRef} sched=${v.originDepartureTime?.israelClock()} eta=${v.eta?.israelClock()} ${if (fresh) "LIVE" else "sched"}" +
                (d?.let { " ${it.toInt()}m" } ?: "") + " leave=${info.leaveAt?.israelClock()} ${info.status}"
        }
        println("[${now.israelClock(true)}] ${visits.size} visits err=${snap.sourceErrors} | $line")
        events.forEach { e ->
            alerts += Alert(e.key, e.vehicleRef, e.at, e.eta, e.live)
            println(">>> LEAVE NOW veh=${e.vehicleRef} trip=${e.tripId} eta=${e.eta?.israelClock()} live=${e.live} at=${e.at.israelClock(true)}")
        }
        delay(config.pollInterval)
    }
    println("\n== Summary (lead ${plan.leadMinutes} min = walk $walk + buffer ${plan.bufferMinutes}) ==")
    println("first-seen status per bus: $firstSeenStatus")
    for (a in alerts) {
        val arr = arrival[a.key]
        val userAtStop = a.at + kotlin.time.Duration.parse("${walk}m")
        val margin = arr?.let { (it.first - userAtStop).inWholeSeconds }
        println("veh=${a.veh} alert=${a.at.israelClock(true)} (eta then ${a.eta?.israelClock()}, ${if (a.live) "live" else "scheduled"}) " +
            "→ user at stop ${userAtStop.israelClock(true)}; bus actually at stop ${arr?.first?.israelClock(true) ?: "not observed"} " +
            "(${arr?.second ?: "-"}); margin ${margin?.let { "${it}s" } ?: "n/a"} (target ≈ +${plan.bufferMinutes * 60}s)")
    }
    client.close()
}
