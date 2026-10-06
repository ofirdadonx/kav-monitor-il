package il.kavmonitor.app

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
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
 *
 * Stop guarantees (tested in WatchServiceStopTest): after ACTION_STOP, a time-out or onTimeout the poll job is
 * cancelled (an in-flight HTTP call is cancelled with it), the wake lock is released, the ongoing notification is
 * removed and no further state/notification update from the old run can land: every callback of a run checks its
 * [generation] under [lock], and stopping/restarting bumps the generation. A second START replaces the running poller
 * (never two pollers).
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var generation = 0
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @get:VisibleForTesting
    val isPolling: Boolean get() = synchronized(lock) { job?.isActive == true }

    @get:VisibleForTesting
    val isWakeLockHeld: Boolean get() = synchronized(lock) { wakeLock?.isHeld == true }

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
        val engine = app.graph.newWatchEngine()
        synchronized(lock) {
            job?.cancel() // replace a running watch: never two pollers
            val gen = ++generation
            acquireWakeLock(spec.durationMinutes)
            job = scope.launch {
                engine.run(
                    spec,
                    onState = { st ->
                        ifCurrent(gen) {
                            WatchStateHolder.update(st)
                            if (st.isActive) Notifications.updateStatus(this@WatchService, spec, st)
                        }
                    },
                    onTrigger = { ev, st -> ifCurrent(gen) { Notifications.alert(this@WatchService, spec, ev, estimated = st.fallback != null) } },
                )
                // Time limit reached.
                ifCurrent(gen) {
                    generation++
                    job = null
                    Notifications.finished(this@WatchService, spec)
                    finishLocked()
                }
            }
        }
    }

    private inline fun ifCurrent(gen: Int, block: () -> Unit) = synchronized(lock) { if (gen == generation) block() }

    private fun stopEverything() = synchronized(lock) {
        generation++ // late callbacks of the cancelled run are dropped
        job?.cancel()
        job = null
        WatchStateHolder.state.value?.let { if (it.isActive) WatchStateHolder.update(it.copy(phase = WatchPhase.STOPPED)) }
        finishLocked()
    }

    private fun finishLocked() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(Notifications.ID_STATUS)
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
        synchronized(lock) {
            generation++
            job?.cancel()
            job = null
            releaseWakeLock()
        }
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
