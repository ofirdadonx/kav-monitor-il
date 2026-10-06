package il.kavmonitor.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPowerManager
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end UI test on Robolectric (real Android framework + resources on the JVM, no emulator):
 * Home → new watch 194 → direction cards (stop counts) → live route view → stops (sticky Start) → ETA to a chosen stop →
 * start → foreground service → watch screen (per-bus details + route view) → departure alert → in-app Stop (verifies
 * no further HTTP requests) → recents.
 *
 * Default: real recorded responses replayed (docs/screenshots/v0.2/replay). -Pkav.live=true: real APIs
 * (docs/screenshots/v0.2). Pixel 8 size, 1080x2400 @ 420dpi.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h914dp-port-420dpi")
class AppUiTest {
    companion object {
        @JvmStatic @BeforeClass
        fun config() {
            TestConfig.reset()
            if (TestApp.LIVE) { TestConfig.pollInterval = il.kavmonitor.shared.trigger.Polling.INTERVAL; TestConfig.routeLiveInterval = il.kavmonitor.shared.trigger.Polling.INTERVAL }
        }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val outDir = File(if (TestApp.LIVE) "../docs/screenshots/v0.4/flow_live" else "../docs/screenshots/v0.4/flow_replay").apply { mkdirs() }
    private val notifLog = StringBuilder()
    private val timeout = if (TestApp.LIVE) 90_000L else 15_000L

    private fun shot(name: String) {
        // The screens' 1 s "now" ticker runs on Compose's virtual test clock, which lags real time in this harness:
        // let it tick once so "לפני X" ages in the screenshot are computed from the real current time.
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path)
    }

    private fun has(text: String, substring: Boolean = true) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, substring: Boolean = true, ms: Long = timeout) = compose.waitReal(ms) { has(text, substring) }

    private fun click(text: String, substring: Boolean = true) =
        compose.onAllNodes(hasText(text, substring = substring)).onFirst().performClick()

    private fun scrollTo(text: String) =
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))

    private fun scrollTop() = compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)

    private fun dumpNotifications(label: String) {
        notifLog.append("== $label ==\n")
        shadowOf(nm).allNotifications.forEach { n ->
            val ex = n.extras
            notifLog.append("channel=${n.channelId} ongoing=${n.flags and Notification.FLAG_ONGOING_EVENT != 0} " +
                "title=${ex.getCharSequence(Notification.EXTRA_TITLE)} | text=${ex.getCharSequence(Notification.EXTRA_TEXT)} | " +
                "actions=${n.actions?.joinToString { it.title }}\n")
        }
        File(outDir, "notifications.txt").writeText(notifLog.toString())
    }

    private fun log(line: String) { notifLog.append("# $line\n"); File(outDir, "notifications.txt").writeText(notifLog.toString()) }

    @Before
    fun setUp() {
        WatchStateHolder.update(null)
        TestHttp.clear()
        FakeCurlbus.reset("20001_101528.json", "20001_101604.json")
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun fullWatchFlow() {
        // 1. Home (no recents yet), version footer
        waitForText("מעקב חדש")
        assertTrue(has("v0.5-beta"))
        shot("01_home_empty")

        // 2. New watch → 194 → direction cards; stop counts fill in from parallel prefetches
        click("מעקב חדש")
        compose.onNode(hasSetTextAction()).performTextInput("194")
        click("חפש", substring = false)
        waitForText("ת. רכבת תל אביב- סבידור/רציפים A", substring = false)
        compose.waitReal(timeout) { !has("סופר…") }
        if (!TestApp.LIVE) { assertTrue(has("28 תחנות")); assertTrue(has("27 תחנות")) }
        shot("02_direction_cards")

        // 3. Egged Savidor → Rishon: live route view
        click("ת. רכבת תל אביב- סבידור/רציפים A", substring = false)
        waitForText("על הקו עכשיו")
        waitForText("נתונים מ-") // first live refresh done
        if (!TestApp.LIVE) waitForText("3 אוטובוסים פעילים")
        shot("03_route_live_view")
        scrollTo("נתונים חיים: SIRI")
        shot("04_route_live_more_buses")

        // 4. Arrives at a stop: stop list + sticky Start bar (disabled until a stop is chosen)
        scrollTo("כשהאוטובוס מגיע לתחנה שאבחר")
        click("כשהאוטובוס מגיע לתחנה שאבחר")
        scrollTo("בחרו תחנה")
        waitForText("בחרו תחנה מהרשימה")
        scrollTo("שדרות יעקב/הורד")
        shot("05_arrives_stop_list_sticky_start")

        // 5. Choose 36926 Shlomo Levi: the route view adds it to the sampled stops → ETA per bus
        scrollTo("שלמה לוי/שער יפו")
        click("שלמה לוי/שער יפו")
        compose.waitReal(timeout) { app.graph.controller.routeLive.state?.snapshot?.targetStopCode == "36926" }
        val anyEta = app.graph.controller.routeLive.state!!.snapshot!!.active.any { it.etaAtTarget != null }
        if (anyEta) scrollTo("הגעה ל\u2068שלמה לוי/שער יפו") else scrollTo("על הקו עכשיו")
        if (!TestApp.LIVE) assertTrue(anyEta)
        shot("06_route_live_eta_to_chosen_stop")

        // 6. Back to "departs origin", 60 min, start (sticky button)
        scrollTo("כשהאוטובוס יוצא מתחנת המוצא")
        click("כשהאוטובוס יוצא מתחנת המוצא")
        click("30 דק׳")
        click("60 דק׳")
        shot("07_departs_origin_duration")
        click("התחל מעקב")

        // 7. The launcher asked Android to start the foreground service: run it.
        val startIntent = shadowOf(app).nextStartedService
        assertNotNull("service start requested", startIntent)
        val service: ServiceController<WatchService> =
            Robolectric.buildService(WatchService::class.java, startIntent).create().startCommand(0, 1)
        // (the route-view cards also say "עדכון אחרון:", so wait on the watch state itself)
        compose.waitReal(timeout) { WatchStateHolder.state.value?.lastSuccessAt != null }
        compose.waitReal(timeout) { !has("ממתין לעדכון ראשון") }
        if (!TestApp.LIVE) waitForText("רכב 7628169")
        shot("08_watch_screen")
        scrollTo("על הקו עכשיו")
        compose.waitReal(timeout) { has("נתונים מ-") }
        scrollTo("נתונים חיים: SIRI")
        shot("09_watch_screen_route_view")
        val ongoing = shadowOf(nm).allNotifications.single { it.channelId == Notifications.CHANNEL_STATUS }
        assertTrue(ongoing.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(listOf("עצור", "+15 דק׳"), ongoing.actions.map { it.title.toString() })
        assertTrue(shadowOf(service.get()).isLastForegroundNotificationAttached)
        assertTrue(service.get().isPolling)
        assertTrue(service.get().isWakeLockHeld)
        dumpNotifications("after first poll")

        // 8. Departure alert (replay: the bus drops off the origin list on the 2nd poll; live: optional wait)
        val liveWaitMin = System.getProperty("kav.liveWaitMin")?.toLongOrNull() ?: 0L
        if (TestApp.LIVE && liveWaitMin > 0) {
            val gotAlert = runCatching {
                compose.waitReal(liveWaitMin * 60_000) { shadowOf(nm).allNotifications.any { it.channelId == Notifications.CHANNEL_ALERT } }
            }.isSuccess
            dumpNotifications("live: after waiting up to $liveWaitMin min for a departure (alert=$gotAlert)")
            if (gotAlert) { scrollTop(); scrollTo("התראות שנשלחו"); shot("10_watch_after_real_departure_alert") }
        }
        if (!TestApp.LIVE) {
            compose.waitReal(timeout) { shadowOf(nm).allNotifications.any { it.channelId == Notifications.CHANNEL_ALERT } }
            val alert = shadowOf(nm).allNotifications.first { it.channelId == Notifications.CHANNEL_ALERT }
            assertEquals("194 יצא מהמוצא", alert.extras.getCharSequence(Notification.EXTRA_TITLE).toString().replace("\u2068", "").replace("\u2069", ""))
            scrollTop()
            waitForText("התראות שנשלחו")
            shot("10_watch_after_departure_alert")
            dumpNotifications("after departure alert")
        }

        // 9. In-app Stop button → launcher → WatchService ACTION_STOP
        scrollTop()
        click("עצור מעקב")
        val stopIntent: Intent = shadowOf(app).nextStartedService
        assertEquals(WatchService.stopIntent(app).action, stopIntent.action)
        service.withIntent(stopIntent).startCommand(0, 2)
        compose.waitReal(timeout) { WatchStateHolder.state.value?.phase == WatchPhase.STOPPED }
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        assertFalse("poll job cancelled", service.get().isPolling)
        assertFalse("wake lock released", service.get().isWakeLockHeld)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        assertNull("ongoing notification removed",
            shadowOf(nm).allNotifications.firstOrNull { it.channelId == Notifications.CHANNEL_STATUS })
        waitForText("המעקב נעצר")
        compose.waitForIdle()
        assertFalse("route view stopped with the watch", app.graph.controller.routeLive.isRefreshing)
        // no HTTP request at all (watch service or route view) over several poll/refresh intervals
        val before = TestHttp.snapshot().size
        val quietMs = if (TestApp.LIVE) 50_000L else 7_000L
        compose.passTime(quietMs)
        val after = TestHttp.snapshot()
        log("requests before stop check=$before, after ${quietMs}ms quiet=${after.size}: ${after.drop(before)}")
        assertEquals("no HTTP request after Stop", before, after.size)
        assertNull(shadowOf(nm).allNotifications.firstOrNull { it.channelId == Notifications.CHANNEL_STATUS })
        shot("11_watch_stopped")
        dumpNotifications("after Stop")

        // 10. Back to home: recents (bidi-isolated stop name)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForText("אחרונים")
        shot("12_home_with_recents")
        service.destroy()
    }
}
