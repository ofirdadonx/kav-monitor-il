package il.kavmonitor.app

import android.content.Context
import androidx.core.content.ContextCompat
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.platform.WatchLauncher

/**
 * Starts/stops [WatchService]. If an activity registered a [permissionGate] (Android 13+ POST_NOTIFICATIONS),
 * the gate runs first and the service is started after the user answered (it runs either way; without the
 * permission the alerts are simply not shown).
 */
class AndroidWatchLauncher(private val context: Context) : WatchLauncher {
    var permissionGate: ((onDone: () -> Unit) -> Unit)? = null

    override fun start(spec: WatchSpec) {
        val go = { ContextCompat.startForegroundService(context, WatchService.startIntent(context, spec)) }
        permissionGate?.invoke { go() } ?: go()
    }

    override fun stop() {
        context.startService(WatchService.stopIntent(context))
    }
}
