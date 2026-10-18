package il.kavmonitor.shared.map

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun RouteMapView(data: RouteMapData, modifier: Modifier, interactive: Boolean, onClick: (() -> Unit)?) {
    val mod = if (interactive) modifier else modifier.clickable { onClick?.invoke() }
    RouteMapCanvas(data, mod)
}
