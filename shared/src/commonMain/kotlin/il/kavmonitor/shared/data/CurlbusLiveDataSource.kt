package il.kavmonitor.shared.data

import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.StopSnapshot
import il.kavmonitor.shared.util.parseApiInstant
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
) : LiveDataSource {
    override suspend fun stopVisits(stopCode: String): StopSnapshot {
        val body = try {
            client.get("$baseUrl/$stopCode").bodyAsText()
        } catch (e: io.ktor.client.plugins.ServerResponseException) {
            // HTTP 5xx for this stop (some stops answer 500 all the time): typed, so callers can fall back
            throw StopServerException(stopCode, e.response.status.value, e)
        }
        return CurlbusParser.parse(body, stopCode, clock.now())
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
        )
    }

    private fun JsonObject?.coord(name: String): Double? =
        (this?.get(name) as? JsonPrimitive)?.doubleOrNull
}
