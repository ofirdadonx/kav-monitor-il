package il.kavmonitor.shared.dev

import il.kavmonitor.shared.data.CurlbusLiveDataSource
import il.kavmonitor.shared.data.RouteRepository
import il.kavmonitor.shared.data.StrideStaticDataSource
import il.kavmonitor.shared.data.createHttpClient
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.trigger.TriggerDetector
import il.kavmonitor.shared.util.israelClock
import il.kavmonitor.shared.watch.WatchEngine
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock

/**
 * Live smoke test through the shared client code (no Android needed):
 *   ./gradlew :shared:liveSmoke                        # line 194, Egged (operator 3)
 *   ./gradlew :shared:liveSmoke -Pline=194 -Poperator=3 -PstopCode=21472
 *   ./gradlew :shared:liveSmoke -PstopCode=20001 -PwatchMinutes=10   # run the real WatchEngine on the origin
 */
fun main(args: Array<String>) = runBlocking {
    val line = args.getOrElse(0) { "194" }
    val operator = args.getOrNull(1)?.toIntOrNull() ?: 3
    val extraStop = args.getOrNull(2)?.takeUnless { it == "-" || it.isBlank() }
    val client = createHttpClient()
    val repo = RouteRepository(StrideStaticDataSource(client))
    val live = CurlbusLiveDataSource(client)

    println("== Directions for line $line on ${repo.today()} (Stride gtfs_routes) ==")
    val dirs = repo.directions(line)
    dirs.forEach { println("  line_ref=${it.lineRef} op=${it.operatorRef} ${it.agencyName} dir=${it.routeDirection} | ${it.longName}") }
    val dir = dirs.firstOrNull { it.operatorRef == operator && it.parsedName.origin.stop.contains("סבידור") }
        ?: dirs.firstOrNull { it.operatorRef == operator } ?: error("no direction for operator $operator")
    println("\n== Stops for line_ref=${dir.lineRef} ==")
    val t0 = System.currentTimeMillis()
    val stops = repo.stops(dir)
    println("  source=${stops.source} date=${stops.sourceDate} (${System.currentTimeMillis() - t0} ms), ${stops.stops.size} stops")
    stops.stops.forEach { println("  ${it.sequence.toString().padStart(2)}. ${it.code} ${it.name} (${it.city}) ${it.lat},${it.lon}") }
    val t1 = System.currentTimeMillis(); repo.stops(dir); println("  cached second call: ${System.currentTimeMillis() - t1} ms")

    val origin = stops.stops.first()
    // Probe the origin plus one more stop (-PstopCode, default: mid-route). With -PstopCode=<origin> only the origin.
    val probe = buildList {
        add(origin)
        when (extraStop) {
            null -> add(stops.stops[stops.stops.size / 2])
            origin.code -> Unit
            else -> stops.stops.firstOrNull { it.code == extraStop }?.let(::add)
        }
    }
    for (stop in probe) {
        println("\n== Live visits at ${stop.code} ${stop.name} (curlbus) ==")
        val snap = live.stopVisits(stop.code)
        val mine = snap.forLine(dir.lineRef, dir.routeShortName, dir.operatorRef)
        println("  server=${snap.serverTime} all-lines=${snap.allVisits.size} line ${dir.routeShortName}/${dir.lineRef}=${mine.size} errors=${snap.sourceErrors}")
        val now = Clock.System.now()
        mine.sortedBy { it.eta }.forEach { v ->
            val age = v.recordedAt?.let { (now - it).inWholeSeconds }
            println("  veh=${v.vehicleRef} trip=${v.tripId} eta=${v.eta?.israelClock()} originDep=${v.originDepartureTime?.israelClock()} " +
                "recorded=${v.recordedAt?.israelClock(true)} (age ${age}s) gps=${if (v.hasGps) "${v.lat},${v.lon}" else "none"}")
        }
        val spec = WatchSpec(dir.lineRef, dir.routeShortName, dir.operatorRef, dir.agencyName, dir.destinationLabel,
            if (stop == origin) TriggerType.DEPARTS_ORIGIN else TriggerType.ARRIVES_AT_STOP, stop, origin)
        val fired = TriggerDetector(spec).onSnapshot(now, mine)
        println("  trigger(${spec.trigger}) on a single snapshot: ${fired.map { "${it.reason} veh=${it.vehicleRef}" }.ifEmpty { listOf("none") }}")
    }
    // Optional: run the real WatchEngine against curlbus for N minutes (4th arg), e.g. -PwatchMinutes=10
    val watchMinutes = args.getOrNull(3)?.toIntOrNull() ?: 0
    if (watchMinutes > 0) {
        val target = probe.last()
        val trigger = if (target == origin) TriggerType.DEPARTS_ORIGIN else TriggerType.ARRIVES_AT_STOP
        val spec = WatchSpec(dir.lineRef, dir.routeShortName, dir.operatorRef, dir.agencyName, dir.destinationLabel,
            trigger, target, origin, durationMinutes = watchMinutes)
        println("\n== WatchEngine live run: $trigger @ ${target.code} ${target.name} for $watchMinutes min ==")
        WatchEngine(live).run(
            spec,
            onState = { st ->
                val buses = st.buses.joinToString("; ") { b ->
                    "${b.vehicleRef} eta=${b.eta?.israelClock()} gps=${if (b.hasFreshGps) "live" else "no"}" +
                        (b.distanceToStopMeters?.let { " ${it.toInt()}m" } ?: "")
                }
                println("[${Clock.System.now().israelClock(true)}] ${st.phase} polls=${st.polls} seen=${st.busesSeen} " +
                    "alerts=${st.alerts.size} err=${st.lastError} | $buses")
            },
            onTrigger = { ev, _ ->
                println(">>> TRIGGER ${ev.reason} veh=${ev.vehicleRef} trip=${ev.tripId} sched=${ev.scheduledDeparture?.israelClock()} " +
                    "at=${ev.at.israelClock(true)} dist=${ev.distanceMeters?.toInt()}")
            },
        )
    }
    client.close()
}
