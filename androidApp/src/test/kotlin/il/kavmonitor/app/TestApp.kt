package il.kavmonitor.app

import com.russhwolf.settings.MapSettings
import il.kavmonitor.shared.AppGraph
import il.kavmonitor.shared.data.ApiJson
import il.kavmonitor.shared.data.CurlbusLiveDataSource
import il.kavmonitor.shared.data.StrideStaticDataSource
import il.kavmonitor.shared.data.createHttpClient
import il.kavmonitor.shared.trigger.TriggerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Per-test-class knobs, set in @BeforeClass (the Application is created before @Before runs). */
object TestConfig {
    var pollInterval: Duration = 2.seconds
    var routeLiveInterval: Duration = 3.seconds
    fun reset() { pollInterval = 2.seconds; routeLiveInterval = 3.seconds; TestClock.reset(); FallbackReplay.active = false }
}

/** Wall clock that can run faster (time-out tests). */
object TestClock : Clock {
    @Volatile private var anchorReal = System.currentTimeMillis()
    @Volatile private var anchorFake = anchorReal
    @Volatile private var speed = 1.0
    override fun now(): Instant = Instant.fromEpochMilliseconds(anchorFake + ((System.currentTimeMillis() - anchorReal) * speed).toLong())
    @Synchronized fun setSpeed(s: Double) { val n = now().toEpochMilliseconds(); anchorReal = System.currentTimeMillis(); anchorFake = n; speed = s }
    /** Jump to [t] (e.g. the time of a recording) and run at [s]× from there. */
    @Synchronized fun set(t: Instant, s: Double = 1.0) { anchorReal = System.currentTimeMillis(); anchorFake = t.toEpochMilliseconds(); speed = s }
    @Synchronized fun reset() { anchorReal = System.currentTimeMillis(); anchorFake = anchorReal; speed = 1.0 }
}

/** Every HTTP request the app makes (fixtures and live mode), for the "no request after Stop" assertions. */
object TestHttp {
    data class Req(val atMs: Long, val host: String, val path: String)
    private val log = mutableListOf<Req>()
    @Synchronized fun record(host: String, path: String) { log += Req(System.currentTimeMillis(), host, path) }
    @Synchronized fun snapshot(): List<Req> = log.toList()
    @Synchronized fun clear() = log.clear()
    fun count(host: String? = null) = snapshot().count { host == null || it.host == host }
}

/**
 * Robolectric application. Default: Stride + curlbus answered from real recorded responses (deterministic):
 * host curlbus.test = the watch service, curlbus-route.test = the live route view.
 * With -Pkav.live=true the real APIs are used (requests still counted).
 */
class TestApp : KavMonitorApp() {
    override fun createGraph(): AppGraph {
        if (LIVE) {
            val client = createHttpClient()
            client.plugin(HttpSend).intercept { req ->
                val u = req.url.build()
                TestHttp.record(u.host, u.encodedPath)
                execute(req)
            }
            return AppGraph(
                settings = MapSettings(), launcher = launcher, httpClient = client,
                appVersion = BuildConfig.VERSION_NAME, clock = TestClock,
                triggerConfig = TriggerConfig(pollInterval = TestConfig.pollInterval),
                locationProvider = locationProvider,
            )
        }
        val client = HttpClient(MockEngine { req ->
            val host = req.url.host
            val path = req.url.encodedPath
            TestHttp.record(host, path)
            val body = when (host) {
                "stride.test" -> FixtureStride.respond(path, req.url.parameters)
                "curlbus.test" -> if (FallbackReplay.active) FallbackReplay.stop(path.trim('/'))
                    else if (path == "/20001") FakeCurlbus.next() else RouteReplay.stop(path.trim('/'))
                "curlbus-route.test" -> if (FallbackReplay.active) FallbackReplay.stop(path.trim('/')) else RouteReplay.stop(path.trim('/'))
                else -> null
            }
            if (body == null) respond("500 Internal Server Error", HttpStatusCode.InternalServerError)
            else respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
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
            routeLiveSource = CurlbusLiveDataSource(client, baseUrl = "http://curlbus-route.test"),
            triggerConfig = TriggerConfig(pollInterval = TestConfig.pollInterval),
            clock = TestClock,
            appVersion = BuildConfig.VERSION_NAME,
            routeLiveInterval = TestConfig.routeLiveInterval,
            locationProvider = locationProvider,
        )
    }

    companion object {
        val LIVE: Boolean get() = System.getProperty("kav.live") == "true"
    }
}

fun fixture(name: String): String =
    requireNotNull(TestApp::class.java.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

fun fixtureOrNull(name: String): String? = TestApp::class.java.getResource("/fixtures/$name")?.readText()

/** Stride answered from real responses recorded 2026-10-06 (today's gtfs_ride_stops not loaded, like in reality). */
object FixtureStride {
    private val dir: File? = TestApp::class.java.getResource("/fixtures")?.let { File(it.toURI()) }
    /** ride id → "<line_ref>_<date>" from the recorded gtfs_rides files. */
    private val rideKeys: Map<Long, String> by lazy {
        dir!!.listFiles { f -> f.name.startsWith("stride_gtfs_rides_") }!!.flatMap { f ->
            Json.parseToJsonElement(f.readText()).jsonArray.map { r ->
                val o = r.jsonObject
                o["id"]!!.jsonPrimitive.long to "${o["gtfs_route__line_ref"]!!.jsonPrimitive.int}_${o["gtfs_route__date"]!!.jsonPrimitive.content}"
            }
        }.toMap()
    }

    fun respond(path: String, q: io.ktor.http.Parameters): String = when (path) {
        "/gtfs_routes/list" -> when {
            q["route_short_name"] != null -> fixtureOrNull("stride_gtfs_routes_${q["route_short_name"]}_2026-10-06.json") ?: "[]"
            q["line_refs"] != null -> fixtureOrNull("stride_gtfs_routes_${q["line_refs"]}_previous_days.json") ?: "[]"
            else -> "[]"
        }
        "/gtfs_rides/list" -> dir!!.listFiles { f -> f.name.startsWith("stride_gtfs_rides_${q["gtfs_route_id"]}_") }
            ?.firstOrNull()?.readText() ?: "[]"
        "/gtfs_ride_stops/list" -> rideKeys[q["gtfs_ride_ids"]?.toLongOrNull()]
            ?.let { fixtureOrNull("stride_gtfs_ride_stops_$it.json") } ?: "[]"
        else -> "[]"
    }
}

private val TS = Regex("""2026-10-06[ T](\d\d:\d\d:\d\d)\+03:00""")

/** Shifts every "2026-10-06 HH:MM:SS+03:00" so that [anchor] maps to now (keeps ETAs/ages realistic). */
fun shiftTimes(json: String, anchor: Instant, now: Instant = TestClock.now()): String {
    val shift = now - anchor
    return TS.replace(json) { m ->
        val shifted = Instant.parse("2026-10-06T${m.groupValues[1]}+03:00") + shift
        val local = java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochSecond(shifted.epochSeconds), java.time.ZoneId.of("Asia/Jerusalem"))
        local.toString().replace('T', ' ').let { if (it.length == 22) it.substring(0, 16) + ":00" + it.substring(16) else it }
    }
}

/**
 * Live route view replay: the real curlbus scan of all 194 stops at 14:51 (stops that answered HTTP 500 then → 500 now),
 * time-shifted so that 14:52:20 is "now".
 */
object RouteReplay {
    private val anchor = Instant.parse("2026-10-06T14:52:20+03:00")
    fun stop(code: String): String? = fixtureOrNull("route194_1451/$code.json")?.let { shiftTimes(it, anchor) }
}

/**
 * Scripted curlbus responses for stop 20001 (watch service), replaying the real 10:17 departure of Egged 194
 * (vehicle 7628169). Timestamps shifted so that the recording's 10:15:30 maps to "now" when [reset] is called.
 */
object FakeCurlbus {
    private val queue = ArrayDeque<String>()
    private var last: String = "{\"errors\":null,\"visits\":{}}"
    var calls = 0; private set

    @Synchronized
    fun reset(vararg fixtures: String) {
        val anchor = Instant.parse("2026-10-06T10:15:30+03:00")
        queue.clear(); calls = 0
        fixtures.forEach { queue += shiftTimes(fixture(it), anchor) }
        last = queue.lastOrNull() ?: last
    }

    @Synchronized
    fun next(): String { calls++; return queue.removeFirstOrNull() ?: last }
}

/**
 * Replays the curlbus polls recorded live on 2026-10-06 18:00–18:48 around stop 25141 (which answered HTTP 500 the whole
 * time, as did 27117): each request gets the newest recording at or before [TestClock] (set to the recording's time,
 * running faster), unshifted. Stops without a recording → HTTP 500.
 */
object FallbackReplay {
    @Volatile var active = false
    private val files: Map<String, List<Pair<Instant, String>>> by lazy {
        val dir = File(requireNotNull(TestApp::class.java.getResource("/fixtures/fallback_25141")).toURI())
        dir.listFiles()!!.filter { it.isDirectory }.associate { d ->
            d.name to d.listFiles()!!.filter { it.name.endsWith(".json") }.sortedBy { it.name }.map { f ->
                val n = f.name.removeSuffix(".json")
                Instant.parse("2026-10-06T${n.substring(0, 2)}:${n.substring(2, 4)}:${n.substring(4, 6)}+03:00") to f.readText()
            }
        }
    }
    /** Time of the last recording of [code] (the replay "ends" there). */
    fun recordedUntil(code: String): Instant? = files[code]?.lastOrNull()?.first
    fun stop(code: String): String? {
        val list = files[code] ?: return null
        val now = TestClock.now()
        return (list.lastOrNull { it.first <= now } ?: list.first()).second
    }
}
