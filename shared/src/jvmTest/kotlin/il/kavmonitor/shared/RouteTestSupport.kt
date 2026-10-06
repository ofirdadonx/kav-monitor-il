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
