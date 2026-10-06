package il.kavmonitor.shared.platform

import il.kavmonitor.shared.model.WatchSpec

/** Platform hook that runs a watch in the background (Android: foreground service). */
interface WatchLauncher {
    fun start(spec: WatchSpec)
    fun stop()
    /** Extends the running watch by [minutes] without restarting it. */
    fun extend(minutes: Int) {}
}
