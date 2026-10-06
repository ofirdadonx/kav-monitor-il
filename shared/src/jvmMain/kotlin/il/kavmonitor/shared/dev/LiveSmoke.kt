package il.kavmonitor.shared.dev

import il.kavmonitor.shared.data.CurlbusLiveDataSource
import il.kavmonitor.shared.data.RouteRepository
import il.kavmonitor.shared.data.StrideStaticDataSource
import il.kavmonitor.shared.data.createHttpClient
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.trigger.TriggerDetector
import il.kavmonitor.shared.util.israelClock
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock

/**
 * Live smoke test through the shared client code (no Android needed):
 *   ./gradlew :shared:liveSmoke                        # line 194, Egged (operator 3)
 *   ./gradlew :shared:liveSmoke -Pline=194 -Poperator=3 -PstopCode=21472
 */
fun main(args: Array<String>) = runBlocking {
    val line = args.getOrElse(0) { "194" }
    val operator = args.getOrNull(1)?.toIntOrNull() ?: 3
    val extraStop = args.getOrNull(2)
    val client = createHttpClient()
    val repo = RouteRepository(StrideStaticDataSource(client))
    val live = CurlbusLiveDataSource(client)

    println("== Directions for line $line on ${repo.today()} (Stride gtfs_routes) ==")
    val dirs = repo.directions(line)
    dirs.forEach { println("  line_ref=${it.lineRef} op=${it.operatorRef} ${it.agencyName} dir=${it.routeDirection} | ${it.longName}") }
    val dir = dirs.firstOrNull { it.operatorRef == operator && it.originLabel.contains("סבידור") }
        ?: dirs.firstOrNull { it.operatorRef == operator } ?: error("no direction for operator $operator")
    println("\n== Stops for line_ref=${dir.lineRef} ==")
    val t0 = System.currentTimeMillis()
    val stops = repo.stops(dir)
    println("  source=${stops.source} date=${stops.sourceDate} (${System.currentTimeMillis() - t0} ms), ${stops.stops.size} stops")
    stops.stops.forEach { println("  ${it.sequence.toString().padStart(2)}. ${it.code} ${it.name} (${it.city}) ${it.lat},${it.lon}") }
    val t1 = System.currentTimeMillis(); repo.stops(dir); println("  cached second call: ${System.currentTimeMillis() - t1} ms")

    val origin = stops.stops.first()
    val probe = buildList {
        add(origin)
        extraStop?.let { code -> stops.stops.firstOrNull { it.code == code }?.let(::add) }
        if (extraStop == null) add(stops.stops[stops.stops.size / 2])
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
    client.close()
}
