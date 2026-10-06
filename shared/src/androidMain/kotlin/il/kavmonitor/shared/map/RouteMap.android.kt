package il.kavmonitor.shared.map

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** osmdroid user agent (OSM tile usage policy: identify the app). Set by the app at startup. */
object MapConfig {
    var userAgent: String = "kav-monitor-app"
    /** Tests: never download tiles (the overlays still draw). */
    var offline: Boolean = false
}

@Composable
actual fun RouteMapView(data: RouteMapData, modifier: Modifier) {
    val context = LocalContext.current
    val map = remember { createMap(context) }
    val fitted = remember { booleanArrayOf(false) }
    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.overlays.clear()
        val path = data.path.map { GeoPoint(it.first, it.second) }
        if (path.size >= 2) {
            m.overlays += Polyline(m).apply {
                setPoints(path)
                outlinePaint.color = Color.rgb(0x1B, 0x7F, 0x3A)
                outlinePaint.strokeWidth = 10f
                outlinePaint.strokeCap = Paint.Cap.ROUND
            }
        }
        data.stops.forEach { s ->
            if (s.lat == null || s.lon == null) return@forEach
            m.overlays += Marker(m).apply {
                position = GeoPoint(s.lat, s.lon)
                title = "${s.sequence}. ${s.name}"
                snippet = listOfNotNull(s.city, s.code).joinToString(" · ")
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = dot(context, if (s.code == data.highlightCode) Color.rgb(0xE6, 0x51, 0x00) else Color.WHITE,
                    Color.rgb(0x1B, 0x7F, 0x3A), if (s.code == data.highlightCode) 16 else 8)
            }
        }
        data.buses.forEach { b ->
            m.overlays += Marker(m).apply {
                position = GeoPoint(b.lat, b.lon)
                title = b.label
                setTextIcon(b.label)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                textLabelBackgroundColor = Color.rgb(0x15, 0x65, 0xC0)
                textLabelForegroundColor = Color.WHITE
                textLabelFontSize = 34
            }
        }
        if (data.userLat != null && data.userLon != null) {
            m.overlays += Marker(m).apply {
                position = GeoPoint(data.userLat, data.userLon)
                title = "המיקום שלך"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = dot(context, Color.rgb(0xD8, 0x1B, 0x60), Color.WHITE, 10)
            }
        }
        if (!fitted[0] && path.size >= 2) {
            fitted[0] = true
            val box = BoundingBox.fromGeoPoints(path)
            m.post { runCatching { m.zoomToBoundingBox(box.increaseByScale(1.15f), false) } }
            m.controller.setCenter(box.centerWithDateLine)
            m.controller.setZoom(13.0)
        }
        m.invalidate()
    })
}

private fun createMap(context: Context): MapView {
    Configuration.getInstance().apply {
        userAgentValue = MapConfig.userAgent
        osmdroidBasePath = context.cacheDir.resolve("osmdroid")
        osmdroidTileCache = context.cacheDir.resolve("osmdroid/tiles")
    }
    return MapView(context).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        if (MapConfig.offline) setUseDataConnection(false)
        setMultiTouchControls(true)
        isTilesScaledToDpi = true
        minZoomLevel = 8.0
        maxZoomLevel = 19.0
    }
}

private fun dot(context: Context, fill: Int, stroke: Int, radiusDp: Int): android.graphics.drawable.Drawable {
    val px = (radiusDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(4)
    return android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(fill)
        setStroke((px / 4).coerceAtLeast(2), stroke)
        setSize(px * 2, px * 2)
    }
}
