package il.kavmonitor.shared.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import il.kavmonitor.shared.resources.Res
import il.kavmonitor.shared.resources.back
import il.kavmonitor.shared.resources.home_title
import il.kavmonitor.shared.resources.map_close
import il.kavmonitor.shared.watch.WatchStateHolder
import org.jetbrains.compose.resources.stringResource

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
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val brandHeight = this@BoxWithConstraints.maxHeight * 0.10f
                    Column(Modifier.fillMaxSize()) {
                        AppBrandBar(controller, Modifier.fillMaxWidth().height(brandHeight))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                        }
                    }
                    BusActionsSheet(controller)
                }
            }
        }
    }
}

/** Pinned on every screen: centered app name on the brand blue, 10% of the page. Back sits on the right. */
@Composable
private fun AppBrandBar(controller: AppController, modifier: Modifier = Modifier) {
    val showBack = controller.screen != Screen.HOME || controller.busActions != null
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Box(modifier.background(MaterialTheme.colorScheme.primary).statusBarsPadding(), contentAlignment = Alignment.Center) {
        Text(
            stringResource(Res.string.home_title),
            style = MaterialTheme.typography.headlineSmall,
            color = onPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (showBack) {
            IconButton(
                onClick = { controller.back() },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(if (controller.screen == Screen.MAP) Res.string.map_close else Res.string.back),
                    tint = onPrimary,
                )
            }
        }
    }
}
