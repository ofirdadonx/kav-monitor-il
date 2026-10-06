package il.kavmonitor.shared.data

import il.kavmonitor.shared.model.RouteName

import il.kavmonitor.shared.model.Direction
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.model.StopList
import il.kavmonitor.shared.model.StopListSource
import il.kavmonitor.shared.route.RouteGeometry
import il.kavmonitor.shared.util.minusDays
import il.kavmonitor.shared.util.parseApiInstant
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate

/**
 * Open Bus Stride (Hasadna) static data.
 *
 * Stop list strategy (observed 2026-10-06: today's `gtfs_ride_stops` were NOT loaded yet at ~10:00 while
 * today's `gtfs_routes`/`gtfs_rides` were):
 *  1. GTFS for the requested day: first ride of the route → `gtfs_ride_stops` (has names + coords).
 *  2. Same `line_ref` on up to [LOOKBACK_DAYS] previous days (the stop pattern of a line_ref is stable).
 *  3. Last resort (the original script's approach): SIRI ride history + `gtfs_stops` lookups for names.
 */
class StrideStaticDataSource(
    private val client: HttpClient,
    private val baseUrl: String = "https://open-bus-stride-api.hasadna.org.il",
) : StaticDataSource {

    override suspend fun directions(lineNumber: String, date: String): List<Direction> {
        val rows: List<GtfsRouteDto> = client.get("$baseUrl/gtfs_routes/list") {
            parameter("route_short_name", lineNumber.trim())
            parameter("date_from", date)
            parameter("date_to", date)
            parameter("limit", 100)
        }.body()
        return rows
            .filter { it.lineRef != null }
            .distinctBy { it.lineRef }
            .map { r ->
                Direction(
                    lineRef = r.lineRef!!,
                    routeShortName = r.routeShortName ?: lineNumber,
                    longName = r.routeLongName.orEmpty(),
                    agencyName = r.agencyName.orEmpty(),
                    operatorRef = r.operatorRef ?: -1,
                    routeDirection = r.routeDirection.orEmpty(),
                    routeAlternative = r.routeAlternative.orEmpty(),
                    gtfsRouteId = r.id,
                    date = r.date ?: date,
                )
            }
            .sortedWith(compareBy({ it.agencyName }, { it.routeDirection }, { it.routeAlternative }, { it.lineRef }))
    }

    override suspend fun stops(direction: Direction): StopList {
        stopsForGtfsRoute(direction.gtfsRouteId)?.let {
            return StopList(direction.lineRef, it, StopListSource.GTFS_TODAY, direction.date)
        }
        val day = LocalDate.parse(direction.date)
        val previous: List<GtfsRouteDto> = runCatchingApi {
            client.get("$baseUrl/gtfs_routes/list") {
                parameter("line_refs", direction.lineRef)
                parameter("date_from", day.minusDays(LOOKBACK_DAYS).toString())
                parameter("date_to", day.minusDays(1).toString())
                parameter("limit", LOOKBACK_DAYS + 1)
            }.body<List<GtfsRouteDto>>()
        }.orEmpty().sortedByDescending { it.date }
        for (route in previous) {
            stopsForGtfsRoute(route.id)?.let {
                return StopList(direction.lineRef, it, StopListSource.GTFS_PREVIOUS_DAY, route.date ?: "")
            }
        }
        val siri = siriFallback(direction.lineRef, day)
        if (siri.size >= 2) return StopList(direction.lineRef, siri, StopListSource.SIRI_HISTORY, direction.date)
        throw IllegalStateException("No stop sequence found for line_ref=${direction.lineRef}")
    }

    /** Returns null when the route has no rides or the ride has no stops loaded. */
    private suspend fun stopsForGtfsRoute(gtfsRouteId: Long): List<RouteStop>? {
        val rides: List<GtfsRideDto> = client.get("$baseUrl/gtfs_rides/list") {
            parameter("gtfs_route_id", gtfsRouteId)
            parameter("limit", 1)
        }.body()
        val ride = rides.firstOrNull() ?: return null
        val rows: List<GtfsRideStopDto> = client.get("$baseUrl/gtfs_ride_stops/list") {
            parameter("gtfs_ride_ids", ride.id)
            parameter("order_by", "stop_sequence asc")
            parameter("limit", 300)
        }.body()
        return mapRideStops(rows).takeIf { it.size >= 2 }
    }

    private suspend fun siriFallback(lineRef: Int, day: LocalDate): List<RouteStop> {
        val rides: List<SiriRideDto> = runCatchingApi {
            client.get("$baseUrl/siri_rides/list") {
                parameter("siri_route__line_refs", lineRef)
                parameter("order_by", "scheduled_start_time desc")
                parameter("limit", 5)
            }.body<List<SiriRideDto>>()
        }.orEmpty()
        var best: List<SiriRideStopDto> = emptyList()
        for (ride in rides) {
            val cand: List<SiriRideStopDto> = runCatchingApi {
                client.get("$baseUrl/siri_ride_stops/list") {
                    parameter("siri_ride_ids", ride.id)
                    parameter("order_by", "order")
                    parameter("limit", 300)
                }.body<List<SiriRideStopDto>>()
            }.orEmpty()
            if (cand.size > best.size) best = cand
        }
        val codes = best.sortedBy { it.order ?: Int.MAX_VALUE }.mapNotNull { it.stopCode?.toString() }.distinct()
        return codes.mapIndexed { i, code ->
            val info = runCatchingApi {
                client.get("$baseUrl/gtfs_stops/list") {
                    parameter("code", code)
                    parameter("date_from", day.minusDays(LOOKBACK_DAYS).toString())
                    parameter("date_to", day.toString())
                    parameter("limit", 1)
                }.body<List<GtfsStopDto>>()
            }?.firstOrNull()
            RouteStop(i + 1, code, info?.name?.let(RouteName::cleanName) ?: code, info?.city, info?.lat, info?.lon)
        }.let { RouteGeometry.withDistances(it) }
    }

    private suspend fun <T> runCatchingApi(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        const val LOOKBACK_DAYS = 7

        fun mapRideStops(rows: List<GtfsRideStopDto>): List<RouteStop> {
            val sorted = rows.filter { it.stopCode != null }
                .sortedBy { it.stopSequence ?: Int.MAX_VALUE }
                .distinctBy { it.stopCode }
            val start = sorted.firstOrNull()?.let { parseApiInstant(it.departureTime) ?: parseApiInstant(it.arrivalTime) }
            val stops = sorted.mapIndexed { i, r ->
                val t = parseApiInstant(r.arrivalTime) ?: parseApiInstant(r.departureTime)
                RouteStop(
                    sequence = r.stopSequence ?: (i + 1),
                    code = r.stopCode!!.toString(),
                    name = r.stopName?.let(RouteName::cleanName) ?: r.stopCode.toString(),
                    city = r.stopCity,
                    lat = r.stopLat,
                    lon = r.stopLon,
                    // GTFS leaves shape_dist_traveled empty on the first stop
                    distanceMeters = r.shapeDistTraveled ?: if (i == 0) 0.0 else null,
                    offsetSeconds = if (start != null && t != null) (t - start).inWholeSeconds.toInt() else null,
                )
            }
            return RouteGeometry.withDistances(stops)
        }
    }
}
