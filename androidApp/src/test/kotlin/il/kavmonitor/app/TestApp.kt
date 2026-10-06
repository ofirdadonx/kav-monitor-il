package il.kavmonitor.app

import com.russhwolf.settings.MapSettings
import il.kavmonitor.shared.AppGraph
import il.kavmonitor.shared.data.ApiJson
import il.kavmonitor.shared.data.CurlbusLiveDataSource
import il.kavmonitor.shared.data.StrideStaticDataSource
import il.kavmonitor.shared.trigger.TriggerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Robolectric application. Default: Stride + curlbus answered from real recorded responses (deterministic).
 * With -Pkav.live=true the real production graph (live APIs) is used.
 */
class TestApp : KavMonitorApp() {
    override fun createGraph(): AppGraph {
        if (LIVE) return super.createGraph()
        val client = HttpClient(MockEngine { req ->
            val path = req.url.encodedPath
            val q = req.url.parameters
            val body = when {
                path == "/gtfs_routes/list" && q["route_short_name"] == "194" -> fixture("stride_gtfs_routes_194_2026-10-06.json")
                path == "/gtfs_routes/list" && q["line_refs"] == "37121" -> fixture("stride_gtfs_routes_37121_previous_days.json")
                path == "/gtfs_rides/list" && q["gtfs_route_id"] == "9696855" -> fixture("stride_gtfs_rides_9696855_2026-10-06.json")
                path == "/gtfs_rides/list" && q["gtfs_route_id"] == "9690392" -> fixture("stride_gtfs_rides_9690392_2026-10-05.json")
                path == "/gtfs_ride_stops/list" && q["gtfs_ride_ids"] == "158617555" -> fixture("stride_gtfs_ride_stops_37121_2026-10-05.json")
                path.startsWith("/gtfs") || path.startsWith("/siri") -> "[]"
                else -> FakeCurlbus.next() // curlbus: /<stop code>
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) {
            expectSuccess = true
            install(ContentNegotiation) { json(ApiJson) }
        }
        return AppGraph(
            settings = MapSettings(),
            launcher = launcher,
            httpClient = client,
            static = StrideStaticDataSource(client, baseUrl = "http://stride.test"),
            live = CurlbusLiveDataSource(client, baseUrl = "http://curlbus.test"),
            triggerConfig = TriggerConfig(pollInterval = 2.seconds),
        )
    }

    companion object {
        val LIVE: Boolean get() = System.getProperty("kav.live") == "true"
    }
}

fun fixture(name: String): String =
    requireNotNull(TestApp::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

/**
 * Scripted curlbus responses for stop 20001, replaying the real 10:17 departure of Egged 194 (vehicle 7628169).
 * All timestamps are shifted so that the recording's 10:15:30 maps to "now" when the test starts.
 */
object FakeCurlbus {
    private val queue = ArrayDeque<String>()
    private var last: String = "{\"errors\":null,\"visits\":{}}"
    var calls = 0; private set

    fun reset(vararg fixtures: String) {
        val shift = Clock.System.now() - Instant.parse("2026-10-06T10:15:30+03:00")
        queue.clear(); calls = 0
        fixtures.forEach { queue += shiftTimes(fixture(it), shift) }
        last = queue.lastOrNull() ?: last
    }

    @Synchronized
    fun next(): String { calls++; return queue.removeFirstOrNull() ?: last }

    private val ts = Regex("""2026-10-06[ T](\d\d:\d\d:\d\d)\+03:00""")
    private fun shiftTimes(json: String, shift: kotlin.time.Duration): String = ts.replace(json) { m ->
        val shifted = Instant.parse("2026-10-06T${m.groupValues[1]}+03:00") + shift
        val local = java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(shifted.epochSeconds), java.time.ZoneId.of("Asia/Jerusalem"))
        local.toString().replace('T', ' ').let { if (it.length == 22) it.substring(0, 16) + ":00" + it.substring(16) else it }
    }
}
