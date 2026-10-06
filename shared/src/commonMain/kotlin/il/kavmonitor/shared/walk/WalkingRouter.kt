package il.kavmonitor.shared.walk

import il.kavmonitor.shared.data.ApiJson
import il.kavmonitor.shared.data.ErrorLog
import il.kavmonitor.shared.trigger.TriggerConfig
import il.kavmonitor.shared.util.Geo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A walk to a stop: a real pedestrian route ([routed]) or the straight-line estimate. */
data class WalkRoute(val distanceMeters: Double, val durationSeconds: Double, val routed: Boolean) {
    val minutes: Int get() = ceil(durationSeconds / 60.0 - 1e-9).toInt().coerceIn(0, TriggerConfig.MAX_WALK_MINUTES)
}

/** Pedestrian routing. Implementations return null when they can't answer (the caller falls back to a straight line). */
interface WalkingRouter {
    val name: String
    suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): WalkRoute?
}

object NoWalkingRouter : WalkingRouter {
    override val name = "none"
    override suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): WalkRoute? = null
}

/**
 * FOSSGIS OSRM, foot profile (https://routing.openstreetmap.de, keyless; usage policy: low volume + identifying
 * User-Agent, which the shared HTTP client sends). Measured 2026-10-06: ~0.45 s per request around Tel Aviv.
 */
class OsrmFootRouter(
    private val client: HttpClient,
    private val baseUrl: String = "https://routing.openstreetmap.de/routed-foot",
) : WalkingRouter {
    override val name = "OSRM"
    override suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): WalkRoute? {
        val body = client.get("$baseUrl/route/v1/foot/$fromLon,$fromLat;$toLon,$toLat?overview=false").bodyAsText()
        val o = ApiJson.parseToJsonElement(body).jsonObject
        if (o["code"]?.jsonPrimitive?.content != "Ok") return null
        val r = o["routes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        return WalkRoute(r["distance"]!!.jsonPrimitive.double, r["duration"]!!.jsonPrimitive.double, routed = true)
    }
}

/** OpenRouteService foot-walking; used only when an API key is configured (Gradle property / local.properties). */
class OpenRouteServiceRouter(
    private val client: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.openrouteservice.org",
) : WalkingRouter {
    override val name = "OpenRouteService"
    override suspend fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): WalkRoute? {
        val body = client.get("$baseUrl/v2/directions/foot-walking?api_key=$apiKey&start=$fromLon,$fromLat&end=$toLon,$toLat").bodyAsText()
        val summary = ApiJson.parseToJsonElement(body).jsonObject["features"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("properties")?.jsonObject?.get("summary")?.jsonObject ?: return null
        return WalkRoute(summary["distance"]!!.jsonPrimitive.double, summary["duration"]!!.jsonPrimitive.double, routed = true)
    }
}

/**
 * Wraps a router: caches by origin rounded to a ~50 m grid + stop, times out after [timeout] and falls back to the
 * straight-line estimate (distance × 1.3 ÷ 80 m/min, `routed = false`). Failures aren't cached, so a later call retries.
 */
class WalkPlanner(
    private val router: WalkingRouter,
    private val timeout: Duration = 5.seconds,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val cache = mutableMapOf<String, WalkRoute>()
    private val failedAtMarks = mutableMapOf<String, kotlin.time.TimeMark>()
    private val mutex = Mutex()
    var requests = 0; private set
    val routerName: String get() = router.name

    fun cached(fromLat: Double, fromLon: Double, stopCode: String): WalkRoute? = cache[key(fromLat, fromLon, stopCode)]

    /** False when cached, when routing is off, or when this cell failed less than [RETRY_AFTER_FAILURE] ago. */
    fun shouldRequest(fromLat: Double, fromLon: Double, stopCode: String): Boolean {
        if (router === NoWalkingRouter) return false
        val k = key(fromLat, fromLon, stopCode)
        if (k in cache) return false
        val f = failedAtMarks[k] ?: return true
        return f.elapsedNow() >= RETRY_AFTER_FAILURE
    }

    suspend fun walk(fromLat: Double, fromLon: Double, stopCode: String, toLat: Double, toLon: Double): WalkRoute {
        val k = key(fromLat, fromLon, stopCode)
        mutex.withLock { cache[k] }?.let { return it }
        val routed = if (router === NoWalkingRouter) null else try {
            requests++
            withTimeoutOrNull(timeout) { router.route(fromLat, fromLon, toLat, toLon) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.w("walking route (${router.name}) failed", e); null
        }
        mutex.withLock { if (routed != null) cache[k] = routed else failedAtMarks[k] = timeSource.markNow() }
        return routed ?: straightLine(fromLat, fromLon, toLat, toLon)
    }

    companion object {
        val RETRY_AFTER_FAILURE: Duration = 2.minutes

        /** ~50 m grid (0.0005° lat ≈ 55 m): moving around a little doesn't trigger a new request. */
        fun key(lat: Double, lon: Double, stopCode: String) = "${(lat * 2000).roundToLong()},${(lon * 2000).roundToLong()},$stopCode"

        fun straightLine(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): WalkRoute {
            val d = Geo.distanceMeters(fromLat, fromLon, toLat, toLon)
            val minutes = il.kavmonitor.shared.trigger.WalkTime.minutes(d)
            return WalkRoute(d, minutes * 60.0, routed = false)
        }
    }
}
