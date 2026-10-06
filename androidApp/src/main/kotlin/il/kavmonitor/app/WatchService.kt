package il.kavmonitor.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import il.kavmonitor.shared.data.ApiJson
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.watch.WatchEngine
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that runs one watch at a time. Polling + trigger logic live in the shared
 * [WatchEngine]; this class only owns the Android lifecycle, the wake lock and the notifications.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val spec = intent.getStringExtra(EXTRA_SPEC)?.let { runCatching { ApiJson.decodeFromString(WatchSpec.serializer(), it) }.getOrNull() }
                if (spec == null) { stopEverything(); return START_NOT_STICKY }
                startWatch(spec)
            }
            ACTION_STOP -> stopEverything()
            else -> stopEverything() // restarted by the system without a spec: nothing to resume
        }
        return START_NOT_STICKY
    }

    private fun startWatch(spec: WatchSpec) {
        ServiceCompat.startForeground(
            this, Notifications.ID_STATUS, Notifications.status(this, spec, null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        acquireWakeLock(spec.durationMinutes)
        job?.cancel()
        val engine = WatchEngine(app.graph.live)
        job = scope.launch {
            engine.run(
                spec,
                onState = { st ->
                    WatchStateHolder.update(st)
                    if (st.isActive) Notifications.updateStatus(this@WatchService, spec, st)
                },
                onTrigger = { ev, _ -> Notifications.alert(this@WatchService, spec, ev) },
            )
            // Time limit reached.
            Notifications.finished(this@WatchService, spec)
            finish()
        }
    }

    private fun stopEverything() {
        job?.cancel()
        job = null
        WatchStateHolder.state.value?.let { if (it.isActive) WatchStateHolder.update(it.copy(phase = WatchPhase.STOPPED)) }
        finish()
    }

    private fun finish() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15+: dataSync services have a daily time budget; stop cleanly if we ever hit it. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopEverything()
    }

    /**
     * Partial wake lock for the (bounded) duration of the watch so the poll timer keeps running with the screen off.
     * Always time-limited, released on stop/timeout.
     */
    private fun acquireWakeLock(minutes: Int) {
        releaseWakeLock()
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kavmonitor:watch").apply {
            setReferenceCounted(false)
            acquire((minutes + 2) * 60_000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "il.kavmonitor.app.action.START"
        private const val ACTION_STOP = "il.kavmonitor.app.action.STOP"
        private const val EXTRA_SPEC = "spec"

        fun startIntent(context: Context, spec: WatchSpec): Intent = Intent(context, WatchService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_SPEC, ApiJson.encodeToString(WatchSpec.serializer(), spec))

        fun stopIntent(context: Context): Intent = Intent(context, WatchService::class.java).setAction(ACTION_STOP)
    }
}
