package il.kavmonitor.shared.dev

import il.kavmonitor.shared.data.CurlbusLiveDataSource
import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.data.RouteRepository
import il.kavmonitor.shared.data.StrideStaticDataSource
import il.kavmonitor.shared.data.createHttpClient
import il.kavmonitor.shared.model.StopSnapshot
import il.kavmonitor.shared.route.RouteLiveFetcher
import il.kavmonitor.shared.route.RouteLiveRequest
import il.kavmonitor.shared.util.israelClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Live route view against the real APIs (what the app's route view does), printing every request and the result.
 * ./gradlew :shared:routeLiveSmoke [-Pline=194 -Poperator=3 -Prefreshes=2]
 */
fun main(args: Array<String>) = runBlocking {
    val line = args.getOrElse(0) { "194" }
    val operator = args.getOrElse(1) { "3" }.toInt()
    val refreshes = args.getOrElse(2) { "2" }.toInt()
    val client = createHttpClient()
    val repo = RouteRepository(StrideStaticDataSource(client))
    val curlbus = CurlbusLiveDataSource(client)
    var calls = 0
    val counting = object : LiveDataSource {
        override suspend fun stopVisits(stopCode: String): StopSnapshot {
            calls++
            val t0 = System.currentTimeMillis()
            return try {
                curlbus.stopVisits(stopCode).also { println("  GET curlbus/$stopCode ok ${System.currentTimeMillis() - t0} ms, ${it.allVisits.size} visits") }
            } catch (e: Exception) {
                println("  GET curlbus/$stopCode FAILED ${e::class.simpleName}: ${e.message?.lineSequence()?.first()}"); throw e
            }
        }
    }
    for (dir in repo.directions(line).filter { it.operatorRef == operator }) {
        val stops = repo.stops(dir)
        println("\n== line_ref=${dir.lineRef} ${dir.parsedName.origin.label} → ${dir.parsedName.destination.label}: ${stops.stops.size} stops, " +
            "length ${stops.lengthMeters?.roundToInt()} m, source ${stops.source} ${stops.sourceDate}")
        val f = RouteLiveFetcher(counting, RouteLiveRequest(dir.lineRef, dir.routeShortName, dir.operatorRef, stops.stops))
        println("sampled stops: " + f.sampleIndices.joinToString { "#${stops.stops[it].sequence} ${stops.stops[it].code} (+${(stops.stops[it].offsetSeconds ?: 0) / 60}m)" })
        repeat(refreshes) { r ->
            if (r > 0) delay(45.seconds)
            calls = 0
            val snap = f.refresh()
            val now = Clock.System.now()
            println("refresh ${r + 1} at ${snap.fetchedAt.israelClock(true)}: $calls requests, active=${snap.active.size}, notStarted=${snap.notStarted.size}, noGps=${snap.noGps.size}, failed=${snap.failedStops.map { it.code }}")
            snap.buses.forEach { b ->
                val lag = b.recordedAt?.let { (now - it).inWholeSeconds }
                println("   ${b.status} veh=${b.vehicleRef} sched=${b.scheduledStart?.israelClock()} " +
                    "pct=${b.position?.fraction?.let { (it * 100).roundToInt() }} remaining=${b.remainingMeters?.roundToInt()}m " +
                    "offRoute=${b.position?.offRouteMeters?.roundToInt()}m next=${b.nextStop?.name} near=${b.nearStop?.name} " +
                    "gpsAge=${lag}s etaDest=${b.etaAtDestination?.israelClock()}")
            }
        }
    }
    client.close()
}
