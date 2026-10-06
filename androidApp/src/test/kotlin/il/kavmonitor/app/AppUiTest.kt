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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * End-to-end UI test on Robolectric (real Android framework + resources on the JVM, no emulator needed):
 * Home → new watch for 194 → directions → stops → trigger/duration → start → foreground service →
 * ongoing notification → in-app watch screen → departure alert → Stop → recents.
 * Screenshots go to docs/screenshots/ (Pixel 8 size, 1080x2400 @ 420dpi).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h914dp-port-420dpi")
class AppUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val outDir = File(if (TestApp.LIVE) "../docs/screenshots/live" else "../docs/screenshots").apply { mkdirs() }
    private val notifLog = StringBuilder()
    private val timeout = if (TestApp.LIVE) 60_000L else 10_000L

    private fun shot(name: String) = compose.onRoot().captureRoboImage(File(outDir, "$name.png").path)

    private fun waitForText(text: String, substring: Boolean = true) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun click(text: String, substring: Boolean = true) =
        compose.onAllNodes(hasText(text, substring = substring)).onFirst().performClick()

    private fun scrollTo(text: String) =
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))

    private fun dumpNotifications(label: String) {
        notifLog.append("== $label ==\n")
        shadowOf(nm).allNotifications.forEach { n ->
            val ex = n.extras
            notifLog.append("channel=${n.channelId} ongoing=${n.flags and Notification.FLAG_ONGOING_EVENT != 0} " +
                "title=${ex.getCharSequence(Notification.EXTRA_TITLE)} | text=${ex.getCharSequence(Notification.EXTRA_TEXT)} | " +
                "big=${ex.getCharSequence(Notification.EXTRA_BIG_TEXT)} | actions=${n.actions?.joinToString { it.title }}\n")
        }
        File(outDir, "notifications.txt").writeText(notifLog.toString())
    }

    @Before
    fun setUp() {
        WatchStateHolder.update(null)
        FakeCurlbus.reset("20001_101528.json", "20001_101604.json")
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun fullWatchFlow() {
        // 1. Home (no recents yet)
        waitForText("מעקב חדש")
        shot("01_home_empty")

        // 2. New watch → line 194 → directions
        click("מעקב חדש")
        compose.onNode(hasSetTextAction()).performTextInput("194")
        click("חפש", substring = false)
        waitForText("קו 194 · אגד")
        shot("02_new_watch_directions_194")

        // 3. Egged Savidor → Rishon direction → stops loaded, trigger + duration
        click("סבידור/רציפים A-תל אביב יפו<->")
        waitForText("מתי להתריע?")
        shot("03_new_watch_trigger_departs_default")

        click("כשהאוטובוס מגיע לתחנה שאבחר")
        waitForText("בחרו תחנה")
        shot("04_new_watch_trigger_arrives_stop_list")
        scrollTo("קניון עזריאלי")
        click("קניון עזריאלי")
        shot("05_new_watch_stop_selected_azrieli")

        // back to "departs origin", duration 30 min chip visible, then start
        scrollTo("כשהאוטובוס יוצא מתחנת המוצא")
        click("כשהאוטובוס יוצא מתחנת המוצא")
        click("30 דק׳")
        click("60 דק׳")
        shot("06_new_watch_departs_origin_duration")
        click("התחל מעקב")

        // 4. The launcher asked Android to start the foreground service: run it.
        val startIntent = shadowOf(app).nextStartedService
        assertNotNull("service start requested", startIntent)
        val service: ServiceController<WatchService> =
            Robolectric.buildService(WatchService::class.java, startIntent).create().startCommand(0, 1)
        waitForText(if (TestApp.LIVE) "עדכון אחרון:" else "רכב 7628169")
        shot("07_watch_screen_live_buses")
        val ongoing = shadowOf(nm).allNotifications.single { it.channelId == Notifications.CHANNEL_STATUS }
        assertTrue(ongoing.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("עצור", ongoing.actions.single().title.toString())
        assertTrue(shadowOf(service.get()).isLastForegroundNotificationAttached)
        dumpNotifications("after first poll")

        // 7. Departure. Fixtures: the bus drops off the origin list on the 2nd poll.
        // Live (-Pkav.live=true): optionally wait up to -Pkav.liveWaitMin minutes for a real departure.
        val liveWaitMin = System.getProperty("kav.liveWaitMin")?.toLongOrNull() ?: 0L
        if (TestApp.LIVE && liveWaitMin > 0) {
            val gotAlert = runCatching {
                compose.waitUntil(liveWaitMin * 60_000) {
                    shadowOf(nm).allNotifications.any { it.channelId == Notifications.CHANNEL_ALERT }
                }
            }.isSuccess
            dumpNotifications("live: after waiting up to $liveWaitMin min for a departure (alert=$gotAlert)")
            if (gotAlert) { waitForText("התראות שנשלחו"); shot("08_live_watch_screen_after_real_departure_alert") }
        }
        if (!TestApp.LIVE) {
            compose.waitUntil(timeout) { shadowOf(nm).allNotifications.any { it.channelId == Notifications.CHANNEL_ALERT } }
            val alert = shadowOf(nm).allNotifications.first { it.channelId == Notifications.CHANNEL_ALERT }
            assertEquals("קו 194 יצא מת. רכבת תל אביב- סבידור/רציפים A!", alert.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(Notifications.CHANNEL_ALERT).importance)
            waitForText("התראות שנשלחו")
            shot("08_watch_screen_after_departure_alert")
            dumpNotifications("after departure alert")
        }

        // 6. Stop (same path as the notification's Stop action: WatchService.stopIntent)
        val stopIntent: Intent = ongoing.actions.single().actionIntent.let { shadowOf(it).savedIntent }
        assertEquals(WatchService.stopIntent(app).action, stopIntent.action)
        service.withIntent(stopIntent).startCommand(0, 2)
        compose.waitUntil(timeout) { WatchStateHolder.state.value?.phase == WatchPhase.STOPPED }
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        assertNull("ongoing notification removed",
            shadowOf(nm).allNotifications.firstOrNull { it.channelId == Notifications.CHANNEL_STATUS && it.flags and Notification.FLAG_ONGOING_EVENT != 0 })
        waitForText("המעקב נעצר")
        shot("09_watch_screen_stopped")
        dumpNotifications("after Stop")

        // 2b. Back to home: recents
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitForText("אחרונים")
        shot("10_home_with_recents")
        service.destroy()
    }
}
