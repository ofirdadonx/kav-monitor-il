package il.kavmonitor.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowPowerManager
import kotlin.time.Duration.Companion.milliseconds

/**
 * Stop really stops: after the notification's Stop action, a time-out, or a restart, the poll job is cancelled, the wake
 * lock released, the ongoing notification gone and NO further HTTP request is made (counted at the HTTP engine).
 * Poll interval 300 ms, so "quiet" windows below cover ≥ 10 poll intervals.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = TestApp::class)
class WatchServiceStopTest {
    companion object {
        @JvmStatic @BeforeClass
        fun config() { TestConfig.reset(); TestConfig.pollInterval = 300.milliseconds }
        private const val POLL_MS = 300L
    }

    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    private val origin = RouteStop(1, "20001", "ת. רכבת תל אביב- סבידור/רציפים A", "תל אביב יפו", 32.083157, 34.796089)
    private val azrieli = RouteStop(5, "21472", "קניון עזריאלי/דרך מנחם בגין", "תל אביב יפו", 32.074584, 34.790607)
    private fun spec(stop: RouteStop = origin, minutes: Int = 60) = WatchSpec(
        37121, "194", 3, "אגד", "ת. רכבת הראשונים, ראשון לציון",
        if (stop == origin) TriggerType.DEPARTS_ORIGIN else TriggerType.ARRIVES_AT_STOP, stop, origin, minutes,
    )

    @Before
    fun setUp() {
        TestConfig.reset(); TestConfig.pollInterval = 300.milliseconds
        WatchStateHolder.update(null)
        TestHttp.clear()
        FakeCurlbus.reset("20001_101528.json") // bus stays listed: no alert, plain polling
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun start(spec: WatchSpec): ServiceController<WatchService> =
        Robolectric.buildService(WatchService::class.java, WatchService.startIntent(app, spec)).create().startCommand(0, 1)

    private fun waitUntil(ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < end) { "condition not met in $ms ms" }
            ShadowLooper.idleMainLooper(); Thread.sleep(20)
        }
    }

    private fun ongoing(): Notification? =
        shadowOf(nm).allNotifications.firstOrNull { it.channelId == Notifications.CHANNEL_STATUS && it.flags and Notification.FLAG_ONGOING_EVENT != 0 }

    private fun assertQuiet(ms: Long = 12 * POLL_MS) {
        val before = TestHttp.count()
        Thread.sleep(ms)
        ShadowLooper.idleMainLooper()
        assertEquals("no HTTP request in ${ms}ms after stop: ${TestHttp.snapshot().takeLast(3)}", before, TestHttp.count())
        assertNull("no ongoing notification re-posted", ongoing())
    }

    private fun assertFullyStopped(sc: ServiceController<WatchService>) {
        assertFalse("poll job cancelled", sc.get().isPolling)
        assertFalse("wake lock released", sc.get().isWakeLockHeld)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertTrue(shadowOf(sc.get()).isStoppedBySelf)
        assertNull("ongoing notification removed", ongoing())
    }

    @Test
    fun notificationStopActionStopsEverything() {
        val sc = start(spec())
        waitUntil { TestHttp.count("curlbus.test") >= 3 }
        assertTrue(sc.get().isPolling)
        assertTrue(sc.get().isWakeLockHeld)
        assertNotNull(ongoing())
        val stop = ongoing()!!.actions.single()
        assertEquals("עצור", stop.title.toString())
        val stopIntent: Intent = shadowOf(stop.actionIntent).savedIntent
        sc.withIntent(stopIntent).startCommand(0, 2)

        assertEquals(WatchPhase.STOPPED, WatchStateHolder.state.value?.phase)
        assertFullyStopped(sc)
        assertQuiet()
        sc.destroy()
        assertQuiet(4 * POLL_MS)
    }

    @Test
    fun stopDuringInFlightRequestDropsItsResult() {
        val sc = start(spec())
        waitUntil { TestHttp.count("curlbus.test") >= 1 }
        // stop immediately after a request went out; the old run must not resurrect the notification/state
        sc.withIntent(WatchService.stopIntent(app)).startCommand(0, 2)
        assertFullyStopped(sc)
        assertQuiet()
        assertEquals(WatchPhase.STOPPED, WatchStateHolder.state.value?.phase)
    }

    @Test
    fun timeLimitEndsTheWatchAndAllPolling() {
        TestClock.setSpeed(600.0) // 15 min watch ≈ 1.5 s
        val sc = start(spec(minutes = 15))
        waitUntil(10_000) { WatchStateHolder.state.value?.phase == WatchPhase.TIMED_OUT }
        waitUntil { !sc.get().isPolling }
        assertFullyStopped(sc)
        assertNotNull("finished notification",
            shadowOf(nm).allNotifications.firstOrNull { it.extras.getCharSequence(Notification.EXTRA_TITLE)?.contains("הסתיים") == true })
        assertQuiet()
    }

    @Test
    fun secondStartReplacesThePollerInsteadOfAddingOne() {
        val sc = start(spec())
        waitUntil { TestHttp.count("curlbus.test") >= 2 }
        sc.withIntent(WatchService.startIntent(app, spec(azrieli))).startCommand(0, 2)
        val switchedAt = System.currentTimeMillis()
        Thread.sleep(10 * POLL_MS)
        val after = TestHttp.snapshot().filter { it.atMs > switchedAt + POLL_MS }
        assertTrue("still polling", after.isNotEmpty())
        assertTrue("old poller (20001) gone: $after", after.none { it.path == "/20001" })
        assertTrue("one poller ≈ 1 request/interval, got ${after.size} in ${9 * POLL_MS}ms", after.size <= 12)
        assertEquals("21472", WatchStateHolder.state.value?.spec?.stop?.code)
        assertTrue(sc.get().isWakeLockHeld)

        sc.withIntent(WatchService.stopIntent(app)).startCommand(0, 3)
        assertFullyStopped(sc)
        assertQuiet()
    }

    @Test
    fun systemTimeoutCallbackStopsEverything() {
        val sc = start(spec())
        waitUntil { TestHttp.count("curlbus.test") >= 2 }
        sc.get().onTimeout(1, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertFullyStopped(sc)
        assertQuiet()
    }
}
