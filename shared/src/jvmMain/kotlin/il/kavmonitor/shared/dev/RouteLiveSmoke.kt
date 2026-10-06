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
    var bytesNote = ""
    val counting = object : LiveDataSource {
        override suspend fun stopVisits(stopCode: String): StopSnapshot {
            calls++
            val t0 = System.currentTimeMillis()
            return try {
                curlbus.stopVisits(stopCode).also { println("  GET curlbus/$stopCode ok ${System.currentTimeMillis() - t0} ms, ${it.allVisits.size} visits") }
            } catch (e: Exception) {
                println("  GET curlbus/$stopCode FAILED ${System.currentTimeMillis() - t0} ms ${e::class.simpleName}: ${e.message?.lineSequence()?.first()}"); throw e
            }
        }
        override suspend fun multiStopVisits(stopCodes: List<String>): StopSnapshot {
            if (stopCodes.size == 1) return stopVisits(stopCodes.single())
            calls++
            val t0 = System.currentTimeMillis()
            return try {
                curlbus.multiStopVisits(stopCodes).also { println("  GET curlbus/<${stopCodes.size} stops> ok ${System.currentTimeMillis() - t0} ms, ${it.allVisits.size} visits") }
            } catch (e: Exception) {
                println("  GET curlbus/<${stopCodes.size} stops: ${stopCodes.joinToString("+")}> FAILED ${System.currentTimeMillis() - t0} ms ${e::class.simpleName}"); throw e
            }
        }
    }
    val maxDirs = args.getOrElse(3) { "9" }.toInt()
    for (dir in repo.directions(line).filter { it.operatorRef == operator }.take(maxDirs)) {
        val stops = repo.stops(dir)
        println("\n== line_ref=${dir.lineRef} ${dir.parsedName.origin.label} → ${dir.parsedName.destination.label}: ${stops.stops.size} stops, " +
            "length ${stops.lengthMeters?.roundToInt()} m, source ${stops.source} ${stops.sourceDate}")
        val req = RouteLiveRequest(dir.lineRef, dir.routeShortName, dir.operatorRef, stops.stops)
        // v0.5 full-route mode, fresh broken-stop memory (the first refresh bisects for real)
        val broken = il.kavmonitor.shared.data.BrokenStops(null)
        val f = RouteLiveFetcher(counting, req, multi = il.kavmonitor.shared.data.MultiStopFetcher(counting, broken))
        val sampled = RouteLiveFetcher(counting, req)
        repeat(refreshes) { r ->
            if (r > 0) delay(60.seconds)
            calls = 0
            val t0 = System.currentTimeMillis()
            val snap = f.refresh()
            val ms = System.currentTimeMillis() - t0
            val now = Clock.System.now()
            println("FULL refresh ${r + 1} at ${snap.fetchedAt.israelClock(true)}: $calls requests in $ms ms, stops answered=${snap.sampledStops.size}, broken=${broken.known()}, " +
                "active=${snap.active.size}, notStarted=${snap.notStarted.size}, noGps=${snap.noGps.size}")
            snap.buses.forEach { b ->
                val lag = b.recordedAt?.let { (now - it).inWholeSeconds }
                println("   ${b.status} veh=${b.vehicleRef} sched=${b.scheduledStart?.israelClock()} " +
                    "pct=${b.position?.fraction?.let { (it * 100).roundToInt() }} next=${b.nextStop?.name} gpsAge=${lag}s etas=${b.etaByStop.size} stops")
            }
            calls = 0
            val s2 = sampled.refresh()
            println("SAMPLED (same minute): $calls requests, active=${s2.active.size}, notStarted=${s2.notStarted.size}, noGps=${s2.noGps.size}; " +
                "missing vs full: ${(snap.buses.map { it.key } - s2.buses.map { it.key }.toSet())}")
        }
    }
    client.close()
}
