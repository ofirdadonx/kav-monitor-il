package il.kavmonitor.shared.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** curlbus.app/<stop> with Accept: application/json. Only the fields we use; everything else is ignored. */
@Serializable
data class CurlbusResponseDto(
    val errors: JsonElement? = null,
    val timestamp: String? = null,
    val visits: Map<String, List<CurlbusVisitDto>>? = null,
    @SerialName("stop_info") val stopInfo: JsonObject? = null,
)

@Serializable
data class CurlbusVisitDto(
    val timestamp: String? = null,
    @SerialName("stop_code") val stopCode: String? = null,
    @SerialName("line_id") val lineId: String? = null,
    @SerialName("route_id") val routeId: String? = null,
    @SerialName("line_name") val lineName: String? = null,
    @SerialName("operator_id") val operatorId: String? = null,
    @SerialName("vehicle_ref") val vehicleRef: String? = null,
    val eta: String? = null,
    @SerialName("trip_id") val tripId: String? = null,
    val departed: String? = null,
    /** {"lat": "32.06", "lon": "34.78"}; values arrive as strings, sometimes the object is null. */
    val location: JsonObject? = null,
)
