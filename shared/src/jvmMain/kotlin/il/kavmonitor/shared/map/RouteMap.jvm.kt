package il.kavmonitor.shared.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun RouteMapView(data: RouteMapData, modifier: Modifier) = RouteMapCanvas(data, modifier)
