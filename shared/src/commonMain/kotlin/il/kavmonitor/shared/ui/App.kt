package il.kavmonitor.shared.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import il.kavmonitor.shared.watch.WatchStateHolder

private fun Screen.depth(): Int = when (this) {
    Screen.HOME -> 0
    Screen.NEW_WATCH, Screen.WATCH, Screen.STOP -> 1
    Screen.MAP -> 2
}

@Composable
fun App(controller: AppController) {
    // The UI is Hebrew-only: force RTL regardless of the device locale.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        KavMonitorTheme {
            Surface(Modifier.fillMaxSize()) {
                val watch by WatchStateHolder.state.collectAsState()
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                SystemBackHandler(enabled = controller.screen != Screen.HOME || controller.busActions != null) {
                    controller.back()
                }
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.widthIn(max = 840.dp).fillMaxHeight().fillMaxWidth().align(Alignment.TopCenter),
                    ) {
                        AnimatedContent(
                            targetState = controller.screen,
                            transitionSpec = {
                                val forward = targetState.depth() >= initialState.depth()
                                val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
                                (slideInHorizontally(tween(250)) { sign * it / 5 } + fadeIn(tween(250))) togetherWith
                                    (slideOutHorizontally(tween(200)) { -sign * it / 5 } + fadeOut(tween(200)))
                            },
                            label = "screen",
                        ) { screen ->
                            when (screen) {
                                Screen.HOME -> HomeScreen(controller, watch)
                                Screen.NEW_WATCH -> NewWatchScreen(controller)
                                Screen.WATCH -> WatchScreen(controller, watch)
                                Screen.MAP -> RouteMapScreen(controller)
                                Screen.STOP -> StopScreen(controller)
                            }
                        }
                    }
                    BusActionsSheet(controller)
                }
            }
        }
    }
}
