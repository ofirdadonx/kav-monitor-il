package il.kavmonitor.shared

import il.kavmonitor.shared.data.ApiJson
import il.kavmonitor.shared.data.CurlbusParser
import il.kavmonitor.shared.data.GtfsRideStopDto
import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.data.StopServerException
import il.kavmonitor.shared.data.StrideStaticDataSource
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.model.StopSnapshot
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Instant

/** 194 (37121) stop list from the real 2026-10-05 GTFS ride (28 stops, 25 510 m). */
fun stops194(): List<RouteStop> = StrideStaticDataSource.mapRideStops(
    ApiJson.decodeFromString(ListSerializer(GtfsRideStopDto.serializer()), fixture("stride_gtfs_ride_stops_37121_2026-10-05.json")),
)

/**
 * curlbus replay of the full 194 scan at 14:51 (fixtures/route194_1451). Stops whose real response was HTTP 500
 * (20876, 20725, 25141, 27117) have no file and throw, like the real API.
 */
class ScanReplayLive(private val fetchedAt: Instant = ilTime("14:52:20")) : LiveDataSource {
    val calls = mutableListOf<String>()
    override suspend fun stopVisits(stopCode: String): StopSnapshot {
        calls += stopCode
        val res = javaClass.getResource("/fixtures/route194_1451/$stopCode.json")
            ?: throw StopServerException(stopCode, 500)
        return CurlbusParser.parse(res.readText(), stopCode, fetchedAt)
    }

    /** Like curlbus: one request for all the stops, HTTP 500 for the whole request if any stop is broken. */
    override suspend fun multiStopVisits(stopCodes: List<String>): StopSnapshot {
        if (stopCodes.size == 1) return stopVisits(stopCodes.single())
        val joined = stopCodes.joinToString("+")
        calls += joined
        val parts = stopCodes.map { code ->
            val res = javaClass.getResource("/fixtures/route194_1451/$code.json") ?: throw StopServerException(joined, 500)
            CurlbusParser.parse(res.readText(), code, fetchedAt)
        }
        return StopSnapshot.merge(joined, parts)
    }
}

/** The 11 real full-route answers (FULL24 = the 24 working stops of 194 in ONE request), 2026-10-06 19:30:57–19:40:47. */
val FULL_ROUNDS = listOf("193057", "193147", "193247", "193347", "193447", "193547", "193647", "193747", "193847", "193947", "194047")
val BROKEN_194 = setOf("20876", "20725", "25141", "27117")

/**
 * curlbus replay of ONE recorded full-route answer (fixtures/full_route_194/full_<round>.json): a multi-stop request
 * gets the slice of the recorded answer for exactly the asked stops; any known-broken stop → HTTP 500 for the whole
 * request (like the real server); a single-stop request gets that stop's slice (what `/<stop>` returned at that moment).
 */
class FullRouteReplayLive(round: String) : LiveDataSource {
    val fetchedAt: Instant = ilTime("${round.substring(0, 2)}:${round.substring(2, 4)}:${round.substring(4, 6)}")
    val full: StopSnapshot = CurlbusParser.parse(fixture("full_route_194/full_$round.json"), "full", fetchedAt)
    val calls = mutableListOf<String>()
    override suspend fun stopVisits(stopCode: String): StopSnapshot {
        calls += stopCode
        if (stopCode in BROKEN_194) throw StopServerException(stopCode, 500)
        return full.slice(stopCode)
    }
    override suspend fun multiStopVisits(stopCodes: List<String>): StopSnapshot {
        if (stopCodes.size == 1) return stopVisits(stopCodes.single())
        val joined = stopCodes.joinToString("+")
        calls += joined
        if (stopCodes.any { it in BROKEN_194 }) throw StopServerException(joined, 500)
        return StopSnapshot.merge(joined, stopCodes.map { full.slice(it) })
    }
}
