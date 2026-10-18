package il.kavmonitor.shared.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
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

/** Opaque brand blue. A value without the alpha byte is fully transparent, so the route line never drew. */
private val ROUTE_BLUE = Color.rgb(0x15, 0x65, 0xC0)

@Composable
actual fun RouteMapView(data: RouteMapData, modifier: Modifier, interactive: Boolean, onClick: (() -> Unit)?) {
    val context = LocalContext.current
    val map = remember { createMap(context) }
    val fitted = remember { booleanArrayOf(false) }
    val click = remember { arrayOfNulls<(() -> Unit)?>(1) }
    click[0] = onClick
    AndroidView(factory = { map }, modifier = modifier, update = { m ->
        m.setMultiTouchControls(interactive)
        if (interactive) {
            // Keep the page list from stealing the drag so the preview can pan.
            m.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        } else m.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) click[0]?.invoke()
            true
        }
        m.overlays.clear()
        val path = data.path.map { GeoPoint(it.first, it.second) }
        if (path.size >= 2) {
            m.overlays += Polyline(m).apply {
                setPoints(path)
                outlinePaint.color = ROUTE_BLUE
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
                    ROUTE_BLUE, if (s.code == data.highlightCode) 16 else 8)
            }
        }
        data.buses.forEach { b ->
            val icon = busPlate(context, b.vehicle)
            m.overlays += Marker(m).apply {
                position = GeoPoint(b.lat, b.lon)
                title = b.label
                this.icon = icon
                setAnchor(0.5f, icon.anchorY)
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

private class PlateIcon(private val drawable: android.graphics.drawable.Drawable, val anchorY: Float) : android.graphics.drawable.Drawable() {
    init { setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight) }
    override fun draw(canvas: Canvas) = drawable.draw(canvas)
    override fun setAlpha(alpha: Int) = drawable.setAlpha(alpha)
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { drawable.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = drawable.opacity
    override fun getIntrinsicWidth() = drawable.intrinsicWidth
    override fun getIntrinsicHeight() = drawable.intrinsicHeight
    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        super.setBounds(left, top, right, bottom)
        drawable.setBounds(left, top, right, bottom)
    }
}

/** Yellow Israeli-style plate with the vehicle number, above a circular bus icon. Anchor is the circle center. */
private fun busPlate(context: Context, vehicle: String): PlateIcon {
    val d = context.resources.displayMetrics.density
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 12f * d
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val label = vehicle.ifBlank { "?" }
    val plateH = 18f * d
    val plateW = textPaint.measureText(label) + 12f * d
    val circle = 28f * d
    val gap = 3f * d
    val w = maxOf(plateW, circle).toInt() + 2
    val h = (plateH + gap + circle).toInt() + 2
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val left = (w - plateW) / 2f
    val plate = RectF(left, 1f, left + plateW, plateH)
    canvas.drawRoundRect(plate, 3f * d, 3f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xF5, 0xC4, 0x00) })
    canvas.drawRoundRect(plate, 3f * d, 3f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = d
    })
    val baseline = plateH / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(label, w / 2f, baseline, textPaint)
    val cx = w / 2f
    val cy = plateH + gap + circle / 2f
    canvas.drawCircle(cx, cy, circle / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ROUTE_BLUE })
    val bus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val bw = circle * 0.55f
    val bh = circle * 0.36f
    canvas.drawRoundRect(RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f), 2f * d, 2f * d, bus)
    val wheel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ROUTE_BLUE }
    canvas.drawCircle(cx - bw * 0.22f, cy + bh * 0.32f, 1.5f * d, wheel)
    canvas.drawCircle(cx + bw * 0.22f, cy + bh * 0.32f, 1.5f * d, wheel)
    val drawable = android.graphics.drawable.BitmapDrawable(context.resources, bmp)
    drawable.setBounds(0, 0, w, h)
    return PlateIcon(drawable, cy / h)
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
