package il.kavmonitor.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import il.kavmonitor.shared.watch.WatchStateHolder

private val Green = Color(0xFF1B7F3B)

@Composable
fun App(controller: AppController) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF7BD88F)) else lightColorScheme(primary = Green)
    // The UI is Hebrew-only: force RTL regardless of the device locale.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = colors) {
            Surface(Modifier.fillMaxSize()) {
                val watch by WatchStateHolder.state.collectAsState()
                Box(Modifier.fillMaxSize()) {
                    when (controller.screen) {
                        Screen.HOME -> HomeScreen(controller, watch)
                        Screen.NEW_WATCH -> NewWatchScreen(controller)
                        Screen.WATCH -> WatchScreen(controller, watch)
                        Screen.MAP -> RouteMapScreen(controller)
                        Screen.STOP -> StopScreen(controller)
                    }
                    BusActionsSheet(controller)
                }
            }
        }
    }
}
