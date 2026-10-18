package il.kavmonitor.shared.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.route.RouteLiveSnapshot
import kotlin.math.cos
import kotlin.math.roundToInt

/** A live bus on the map. */
data class MapBus(val key: String, val vehicle: String, val lat: Double, val lon: Double, val percent: Int?) {
    val label: String get() = vehicle + (percent?.let { " · $it%" } ?: "")
}

/**
 * Everything the map shows. The route path is the stop-to-stop polyline: Open Bus Stride has no GTFS shapes endpoint
 * (checked 2026-10-06: no `shape` path in its OpenAPI), so the trip's real shape isn't available.
 */
data class RouteMapData(
    val stops: List<RouteStop>,
    val highlightCode: String? = null,
    val userLat: Double? = null,
    val userLon: Double? = null,
    val buses: List<MapBus> = emptyList(),
) {
    val path: List<Pair<Double, Double>> get() = stops.mapNotNull { s -> if (s.lat != null && s.lon != null) s.lat to s.lon else null }
}

fun RouteLiveSnapshot.mapBuses(): List<MapBus> = buses.mapNotNull { b ->
    if (!b.isActive || b.lat == null || b.lon == null) return@mapNotNull null
    MapBus(b.key, b.vehicleRef ?: "?", b.lat, b.lon, b.position?.fraction?.let { (it * 100).roundToInt() })
}

/** Platform map (Android: osmdroid with OpenStreetMap tiles). Only composed while the map screen is open. */
@Composable
expect fun RouteMapView(data: RouteMapData, modifier: Modifier = Modifier)

/** Tile-less fallback drawing (route, stops, buses, user) in an equirectangular projection; used by the JVM target. */
@Composable
fun RouteMapCanvas(data: RouteMapData, modifier: Modifier = Modifier) {
    val pts = data.path + data.buses.map { it.lat to it.lon } + listOfNotNull(data.userLat?.let { la -> data.userLon?.let { la to it } })
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier.background(scheme.surfaceContainerLow)) {
        if (pts.isEmpty()) return@Canvas
        val minLat = pts.minOf { it.first }; val maxLat = pts.maxOf { it.first }
        val minLon = pts.minOf { it.second }; val maxLon = pts.maxOf { it.second }
        val k = cos((minLat + maxLat) / 2 * kotlin.math.PI / 180)
        val w = ((maxLon - minLon) * k).coerceAtLeast(1e-6); val h = (maxLat - minLat).coerceAtLeast(1e-6)
        val pad = 40f
        val scale = minOf((size.width - 2 * pad) / w.toFloat(), (size.height - 2 * pad) / h.toFloat())
        fun p(lat: Double, lon: Double) = Offset(pad + ((lon - minLon) * k).toFloat() * scale, size.height - pad - (lat - minLat).toFloat() * scale)
        val path = Path()
        data.path.forEachIndexed { i, (la, lo) -> val o = p(la, lo); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, scheme.primary, style = Stroke(width = 6f))
        data.stops.forEach { s ->
            if (s.lat == null || s.lon == null) return@forEach
            val hi = s.code == data.highlightCode
            drawCircle(if (hi) scheme.tertiary else scheme.surface, radius = if (hi) 12f else 6f, center = p(s.lat, s.lon))
            drawCircle(scheme.primary, radius = if (hi) 12f else 6f, center = p(s.lat, s.lon), style = Stroke(2f))
        }
        data.buses.forEach { b -> drawCircle(scheme.secondary, radius = 14f, center = p(b.lat, b.lon)) }
        if (data.userLat != null && data.userLon != null) drawCircle(scheme.error, radius = 10f, center = p(data.userLat, data.userLon))
    }
}
