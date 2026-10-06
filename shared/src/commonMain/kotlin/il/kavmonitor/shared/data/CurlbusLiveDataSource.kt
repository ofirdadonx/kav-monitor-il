package il.kavmonitor.shared.data

import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.StopSnapshot
import il.kavmonitor.shared.util.parseApiInstant
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import kotlin.math.roundToLong
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.time.Clock
import kotlin.time.Instant

class CurlbusLiveDataSource(
    private val client: HttpClient,
    private val baseUrl: String = "https://curlbus.app",
    private val clock: Clock = Clock.System,
) : LiveDataSource, NearbySource {
    override suspend fun stopVisits(stopCode: String): StopSnapshot {
        val body = try {
            client.get("$baseUrl/$stopCode").bodyAsText()
        } catch (e: io.ktor.client.plugins.ServerResponseException) {
            // HTTP 5xx for this stop (some stops answer 500 all the time): typed, so callers can fall back
            throw StopServerException(stopCode, e.response.status.value, e)
        }
        return CurlbusParser.parse(body, stopCode, clock.now())
    }

    /** One request for up to 30 stops: `/<a>+<b>+…` (same JSON, visits keyed by stop code). Longer timeout (~9 s seen). */
    override suspend fun multiStopVisits(stopCodes: List<String>): StopSnapshot {
        require(stopCodes.isNotEmpty() && stopCodes.size <= LiveDataSource.MAX_STOPS_PER_REQUEST) { "1..30 stops per request" }
        if (stopCodes.size == 1) return stopVisits(stopCodes.single())
        val joined = stopCodes.joinToString("+")
        val body = try {
            client.get("$baseUrl/$joined") {
                if (client.pluginOrNull(HttpTimeout) != null) {
                    timeout { requestTimeoutMillis = MULTI_TIMEOUT_MS; socketTimeoutMillis = MULTI_TIMEOUT_MS }
                }
            }.bodyAsText()
        } catch (e: io.ktor.client.plugins.ServerResponseException) {
            throw StopServerException(joined, e.response.status.value, e)
        }
        return CurlbusParser.parse(body, joined, clock.now())
    }

    /** Stops around a point (`/nearby`), nearest first. [radius] must be 5, 10 or 50..1000 in steps of 50. */
    override suspend fun nearby(lat: Double, lon: Double, radius: Int): List<NearbyStop> {
        require(radius in NearbyStops.ALLOWED_RADII) { "radius not allowed by curlbus: $radius" }
        val body = client.get("$baseUrl/nearby?lat=${fmt(lat)}&lon=${fmt(lon)}&radius=$radius").bodyAsText()
        return NearbyStops.parse(body)
    }

    private fun fmt(d: Double): String = ((d * 100000).roundToLong() / 100000.0).toString()

    companion object {
        const val MULTI_TIMEOUT_MS = 30_000L
    }
}

object CurlbusParser {
    fun parse(json: String, stopCode: String, fetchedAt: Instant): StopSnapshot {
        val dto = ApiJson.decodeFromString(CurlbusResponseDto.serializer(), json)
        val visits = dto.visits.orEmpty().flatMap { (code, list) -> list.map { it.toModel(code) } }
        val errors = dto.errors?.takeUnless { it is JsonNull }?.toString()
        return StopSnapshot(
            stopCode = stopCode,
            fetchedAt = fetchedAt,
            serverTime = parseApiInstant(dto.timestamp),
            allVisits = visits,
            sourceErrors = errors,
        )
    }

    private fun CurlbusVisitDto.toModel(keyStopCode: String): LiveVisit {
        val lat = location.coord("lat")
        val lon = location.coord("lon")
        return LiveVisit(
            stopCode = stopCode ?: keyStopCode,
            lineName = lineName,
            lineRef = (lineId ?: routeId)?.toIntOrNull(),
            operatorRef = operatorId?.toIntOrNull(),
            vehicleRef = vehicleRef?.takeIf { it.isNotBlank() },
            tripId = tripId?.takeIf { it.isNotBlank() },
            eta = parseApiInstant(eta),
            originDepartureTime = parseApiInstant(departed),
            recordedAt = parseApiInstant(timestamp),
            // (0,0) or missing coordinates are treated as "no GPS"
            lat = lat.takeIf { lat != null && lon != null && !(lat == 0.0 && lon == 0.0) },
            lon = lon.takeIf { lat != null && lon != null && !(lat == 0.0 && lon == 0.0) },
            destinationName = staticInfo.path("route", "destination", "name", "HE"),
            agencyName = staticInfo.path("route", "agency", "name", "HE"),
        )
    }

    private fun JsonObject?.path(vararg keys: String): String? {
        var cur: kotlinx.serialization.json.JsonElement? = this
        for (k in keys) cur = (cur as? JsonObject)?.get(k)
        return (cur as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject?.coord(name: String): Double? =
        (this?.get(name) as? JsonPrimitive)?.doubleOrNull
}
