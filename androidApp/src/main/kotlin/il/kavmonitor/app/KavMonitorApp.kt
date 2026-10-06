package il.kavmonitor.app

import android.app.Application
import android.content.Context
import com.russhwolf.settings.SharedPreferencesSettings
import il.kavmonitor.shared.AppGraph

open class KavMonitorApp : Application() {
    lateinit var graph: AppGraph
        private set
    val launcher by lazy { AndroidWatchLauncher(this) }
    val locationProvider by lazy { AndroidLocationProvider(this) }

    override fun onCreate() {
        super.onCreate()
        // raw error details (exception text) go to logcat only; the UI shows short Hebrew messages
        il.kavmonitor.shared.data.ErrorLog.sink = { msg, e -> android.util.Log.w("KavMonitor", msg, e) }
        Notifications.createChannels(this)
        graph = createGraph()
    }

    /** Overridden in Robolectric tests to plug in fixture-backed data sources. */
    protected open fun createGraph(): AppGraph = AppGraph(
        settings = SharedPreferencesSettings(getSharedPreferences("kav_monitor", Context.MODE_PRIVATE)),
        launcher = launcher,
        appVersion = BuildConfig.VERSION_NAME,
        locationProvider = locationProvider,
    )
}

val Context.app: KavMonitorApp get() = applicationContext as KavMonitorApp
