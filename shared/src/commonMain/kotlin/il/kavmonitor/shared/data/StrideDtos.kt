package il.kavmonitor.shared.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GtfsRouteDto(
    val id: Long,
    val date: String? = null,
    @SerialName("line_ref") val lineRef: Int? = null,
    @SerialName("operator_ref") val operatorRef: Int? = null,
    @SerialName("route_short_name") val routeShortName: String? = null,
    @SerialName("route_long_name") val routeLongName: String? = null,
    @SerialName("route_mkt") val routeMkt: String? = null,
    @SerialName("route_direction") val routeDirection: String? = null,
    @SerialName("route_alternative") val routeAlternative: String? = null,
    @SerialName("agency_name") val agencyName: String? = null,
)

@Serializable
data class GtfsRideDto(
    val id: Long,
    @SerialName("gtfs_route_id") val gtfsRouteId: Long? = null,
    @SerialName("journey_ref") val journeyRef: String? = null,
    @SerialName("start_time") val startTime: String? = null,
)

@Serializable
data class GtfsRideStopDto(
    @SerialName("stop_sequence") val stopSequence: Int? = null,
    @SerialName("arrival_time") val arrivalTime: String? = null,
    @SerialName("departure_time") val departureTime: String? = null,
    @SerialName("shape_dist_traveled") val shapeDistTraveled: Double? = null,
    @SerialName("gtfs_stop__code") val stopCode: Long? = null,
    @SerialName("gtfs_stop__name") val stopName: String? = null,
    @SerialName("gtfs_stop__city") val stopCity: String? = null,
    @SerialName("gtfs_stop__lat") val stopLat: Double? = null,
    @SerialName("gtfs_stop__lon") val stopLon: Double? = null,
)

@Serializable
data class GtfsStopDto(
    val code: Long? = null,
    val name: String? = null,
    val city: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val date: String? = null,
)

@Serializable
data class SiriRideDto(
    val id: Long,
    @SerialName("scheduled_start_time") val scheduledStartTime: String? = null,
    @SerialName("vehicle_ref") val vehicleRef: String? = null,
)

@Serializable
data class SiriRideStopDto(
    val order: Int? = null,
    @SerialName("siri_stop__code") val stopCode: Long? = null,
)
