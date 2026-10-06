package il.kavmonitor.shared

import il.kavmonitor.shared.data.ApiJson
import il.kavmonitor.shared.data.StrideStaticDataSource
import il.kavmonitor.shared.model.StopListSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Uses real Stride responses recorded on 2026-10-06 (today's gtfs_ride_stops were not loaded yet). */
class StrideStaticDataSourceTest {
    private val requests = mutableListOf<String>()

    private fun client() = HttpClient(MockEngine { req ->
        val path = req.url.encodedPath
        val q = req.url.parameters
        requests += req.url.toString()
        val body = when {
            path == "/gtfs_routes/list" && q["route_short_name"] == "194" -> fixture("stride_gtfs_routes_194_2026-10-06.json")
            path == "/gtfs_routes/list" && q["line_refs"] == "37121" -> fixture("stride_gtfs_routes_37121_previous_days.json")
            path == "/gtfs_rides/list" && q["gtfs_route_id"] == "9696855" -> fixture("stride_gtfs_rides_9696855_2026-10-06.json")
            path == "/gtfs_rides/list" && q["gtfs_route_id"] == "9690392" -> fixture("stride_gtfs_rides_9690392_2026-10-05.json")
            path == "/gtfs_ride_stops/list" && q["gtfs_ride_ids"] == "158617555" -> fixture("stride_gtfs_ride_stops_37121_2026-10-05.json")
            else -> "[]"
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }) {
        expectSuccess = true
        install(ContentNegotiation) { json(ApiJson) }
    }

    @Test
    fun directionsForLine194() = runTest {
        val dirs = StrideStaticDataSource(client(), baseUrl = "http://stride").directions("194", "2026-10-06")
        assertEquals(4, dirs.size)
        val egged = dirs.single { it.lineRef == 37121 }
        assertEquals("אגד", egged.agencyName)
        assertEquals(3, egged.operatorRef)
        assertEquals(9696855L, egged.gtfsRouteId)
        // v0.2: long name parsed into stop + city (was the raw "stop-city" text)
        assertEquals("ת. רכבת תל אביב- סבידור/רציפים A, תל אביב יפו", egged.parsedName.origin.label)
        assertEquals("ת. רכבת הראשונים, ראשון לציון", egged.destinationLabel)
        assertEquals(setOf(3, 18), dirs.map { it.operatorRef }.toSet()) // Kavim also runs a "194"
    }

    @Test
    fun stopsFallBackToPreviousDayWhenTodayNotLoaded() = runTest {
        val src = StrideStaticDataSource(client(), baseUrl = "http://stride")
        val dir = src.directions("194", "2026-10-06").single { it.lineRef == 37121 }
        val list = src.stops(dir)
        assertEquals(StopListSource.GTFS_PREVIOUS_DAY, list.source)
        assertEquals("2026-10-05", list.sourceDate)
        assertEquals(28, list.stops.size)
        assertEquals("20001", list.stops.first().code)
        assertEquals(32.083157, list.stops.first().lat)
        assertEquals("36053", list.stops.last().code)
        assertEquals("ת. רכבת הראשונים", list.stops.last().name)
        assertEquals((1..28).toList(), list.stops.map { it.sequence })
        assertTrue(requests.any { "gtfs_ride_ids=158834756" in it }, "tried today's ride first")
    }
}
