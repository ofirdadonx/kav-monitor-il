package il.kavmonitor.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import il.kavmonitor.shared.data.ErrorKind
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchStateHolder
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import java.io.File
import kotlin.time.Instant

/**
 * v0.3.1: a watched stop that curlbus answers with HTTP 500 (25141, seen on the user's phone). Friendly error while it is
 * being confirmed, then the neighbouring stops stand in (20750 upstream, 31492 downstream; 27117 is broken too) and the
 * arrival at 25141 is derived. Replays the polls recorded live on 2026-10-06 (FallbackReplay); with -Pkav.live=true the
 * live variant runs against the real curlbus.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h914dp-port-420dpi")
class FallbackUiTest {
    companion object {
        /** Recording time the replay starts at (bus 7687869 is 51 m past 25141 at 17:57:37). */
        val REPLAY_START: Instant = Instant.parse("2026-10-06T17:58:10+03:00")
        const val SPEED = 15.0

        @JvmStatic @BeforeClass
        fun config() { TestConfig.reset() }

        @JvmStatic @AfterClass
        fun done() { TestConfig.reset() }
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val c get() = app.graph.controller
    private val outDir = File(if (TestApp.LIVE) "../docs/screenshots/v0.4/fallback_live" else "../docs/screenshots/v0.4/fallback").apply { mkdirs() }
    private val log = StringBuilder()
    private val timeout = 20_000L

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path)
    }
    private fun has(t: String, sub: Boolean = true) = compose.onAllNodes(hasText(t, substring = sub)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String, ms: Long = timeout) = compose.waitReal(ms) { has(t) }
    private fun click(t: String, sub: Boolean = true) {
        compose.waitReal(timeout) { compose.onAllNodes(hasText(t, substring = sub)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(t, substring = sub)).onFirst().performClick()
    }
    private fun scrollTo(t: String) {
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(t, substring = true))
        compose.waitForIdle()
    }
    private fun scrollTop() = compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)
    private fun note(s: String) { log.append(s).append('\n'); File(outDir, "fallback_log.txt").writeText(log.toString()) }
    private fun allNotificationText() = shadowOf(nm).allNotifications.joinToString("\n") {
        "${it.extras.getCharSequence(Notification.EXTRA_TITLE)} | ${it.extras.getCharSequence(Notification.EXTRA_TEXT)}"
    }

    @Before
    fun setUp() {
        WatchStateHolder.update(null)
        TestHttp.clear()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startArrivesWatchAt25141(beforeStart: () -> Unit = {}): ServiceController<WatchService> {
        waitFor("מעקב חדש")
        click("מעקב חדש")
        compose.onNode(hasSetTextAction()).performTextReplacement("194")
        click("חפש", sub = false)
        waitFor("ת. רכבת תל אביב- סבידור/רציפים A")
        click("ת. רכבת תל אביב- סבידור/רציפים A", sub = false)
        waitFor("התחנה הקרובה אליך במסלול")
        scrollTo("כשהאוטובוס מגיע לתחנה שאבחר")
        click("כשהאוטובוס מגיע לתחנה שאבחר")
        // the stop row in the picker (the live route panel above may mention the same stop name)
        val row = hasText("דרך מנחם בגין/נחמני", substring = true) and hasText("25141", substring = true) and hasClickAction()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(row)
        compose.waitForIdle()
        compose.onAllNodes(row).onLast().performClick()
        try { compose.waitReal(timeout) { c.targetStopCode == "25141" } } catch (e: Throwable) {
            note("debug: trigger=${c.trigger} target=${c.targetStopCode} rows=${compose.onAllNodes(row).fetchSemanticsNodes().size}")
            shot("debug_picker"); throw e
        }
        shot("01_stop_picker")
        // Show the detection from scratch (the live route view may already have flagged 25141 by now).
        app.graph.brokenStops.clear("25141")
        beforeStart()
        click("התחל מעקב")
        val startIntent = shadowOf(app).nextStartedService
        assertNotNull(startIntent)
        return Robolectric.buildService(WatchService::class.java, startIntent).create().startCommand(0, 1)
    }

    @Test
    fun brokenStopFallsBackToNeighbours_replay() {
        assumeTrue(!TestApp.LIVE)
        FallbackReplay.active = true
        TestClock.set(REPLAY_START, 1.0)
        val service = startArrivesWatchAt25141 { TestClock.set(REPLAY_START, SPEED) }

        // first answer: HTTP 500 → short Hebrew message + "still retrying", no exception text anywhere
        compose.waitReal(timeout) { WatchStateHolder.state.value?.lastError != null }
        val err = WatchStateHolder.state.value!!.lastError!!
        assertEquals(ErrorKind.SERVER, err.kind)
        waitFor("לא מחזיר נתונים לתחנה 25141")
        assertTrue(has("ממשיך לנסות"))
        assertFalse(has("Exception"))
        assertFalse(has("500"))
        shot("02_friendly_error_while_confirming")
        note("error: ${allNotificationText()}")
        assertFalse(allNotificationText().contains("Exception"))

        // second 500 → stand-ins: 20750 (upstream), 27117 tried and broken too → 31492 (downstream)
        compose.waitReal(timeout) { WatchStateHolder.state.value?.fallback?.downstream?.code == "31492" && WatchStateHolder.state.value?.buses?.isNotEmpty() == true }
        val st = WatchStateHolder.state.value!!
        assertEquals("20750", st.fallback!!.upstream?.code)
        assertTrue(app.graph.brokenStops.isBroken("25141"))
        waitFor("התחנה לא מדווחת ב-curlbus")
        waitFor("משוער לפי תחנה סמוכה")
        scrollTop()
        shot("03_fallback_watch_screen")
        note("fallback: ${st.fallback} buses=${st.buses.map { "${it.vehicleRef} eta=${it.eta}" }}")
        val polledAfter = TestHttp.snapshot().filter { it.host == "curlbus.test" }.map { it.path }
        note("watch requests: $polledAfter")
        assertTrue(polledAfter.drop(2).all { it in setOf("/20750", "/27117", "/31492") })

        // 7687869 passed 25141 at ~17:57:40 (closest fix 51 m at 17:57:37), before the watch could switch to the
        // neighbours: it must not produce a (late, false) arrival alert. 23399602 is listed with an estimated ETA.
        compose.waitReal(timeout) { WatchStateHolder.state.value?.buses?.any { it.vehicleRef == "23399602" } == true }
        assertTrue(WatchStateHolder.state.value!!.alerts.none { it.vehicleRef == "7687869" })
        assertTrue(WatchStateHolder.state.value!!.buses.none { it.vehicleRef == "7687869" })
        assertTrue(WatchStateHolder.state.value!!.buses.all { it.etaEstimated })

        // arrival of 23399602 at 25141 (~18:35), derived from the neighbours, when the recording covers it
        val until = FallbackReplay.recordedUntil("31492")
        if (until != null && until > Instant.parse("2026-10-06T18:40:00+03:00")) {
            compose.waitReal(240_000) { shadowOf(nm).allNotifications.any { it.channelId == Notifications.CHANNEL_ALERT } }
            val ev = WatchStateHolder.state.value!!.alerts.first()
            note("alert: ${ev.reason} ${ev.vehicleRef} at ${ev.at} eta=${ev.eta} | ${allNotificationText()}")
            assertEquals("23399602", ev.vehicleRef)
            assertTrue(ev.reason in setOf(TriggerReason.AT_STOP_GPS, TriggerReason.AT_STOP_ETA, TriggerReason.DROPPED_AT_STOP))
            waitFor("התראות: 1")
            shot("04_fallback_arrival_alert")
            assertTrue(allNotificationText().contains("הערכה"))
            assertFalse(allNotificationText().contains("Exception"))
        } else note("recording ends ${until}: arrival alert of 23399602 not replayed")

        // Stop: no more requests
        scrollTop()
        click("עצור מעקב")
        service.withIntent(shadowOf(app).nextStartedService).startCommand(0, 2)
        compose.waitReal(timeout) { WatchStateHolder.state.value?.phase == WatchPhase.STOPPED }
        assertFalse(service.get().isPolling)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.passTime(500)
        val before = TestHttp.count("curlbus.test")
        compose.passTime(4_000)
        assertEquals("no watch HTTP after Stop", before, TestHttp.count("curlbus.test"))

        // new watch: the stop picker marks the broken stops as estimated
        waitFor("מעקב חדש")
        click("מעקב חדש")
        compose.onNode(hasSetTextAction()).performTextReplacement("194")
        click("חפש", sub = false)
        waitFor("ת. רכבת תל אביב- סבידור/רציפים A")
        click("ת. רכבת תל אביב- סבידור/רציפים A", sub = false)
        waitFor("התחנה הקרובה אליך במסלול")
        scrollTo("כשהאוטובוס מגיע לתחנה שאבחר")
        click("כשהאוטובוס מגיע לתחנה שאבחר")
        val row = hasText("דרך מנחם בגין/נחמני", substring = true) and hasText("25141", substring = true) and hasClickAction()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(row)
        compose.waitForIdle()
        compose.onAllNodes(row and hasText("הערכה", substring = true)).onLast().assertExists()
        shot("05_stop_picker_estimated_badge")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    }

    /** -Pkav.live=true only: the same watch against the real curlbus (25141 answers 500 live). */
    @Test
    fun brokenStopFallsBackToNeighbours_live() {
        assumeTrue(TestApp.LIVE)
        val service = startArrivesWatchAt25141()
        compose.waitReal(30_000) { WatchStateHolder.state.value?.lastError != null || WatchStateHolder.state.value?.fallback != null }
        if (WatchStateHolder.state.value?.fallback == null) shot("live_02_friendly_error_while_confirming")
        compose.waitReal(60_000) { WatchStateHolder.state.value?.fallback != null && WatchStateHolder.state.value?.lastSuccessAt != null }
        compose.passTime(3_000)
        scrollTop()
        shot("live_03_fallback_watch_screen")
        val st = WatchStateHolder.state.value!!
        note("live fallback: ${st.fallback} buses=${st.buses.map { "${it.vehicleRef} eta=${it.eta} gps=${it.hasFreshGps}" }} err=${st.lastError}")
        assertFalse(has("Exception"))
        scrollTop()
        click("עצור מעקב")
        service.withIntent(shadowOf(app).nextStartedService).startCommand(0, 2)
        compose.waitReal(timeout) { WatchStateHolder.state.value?.phase == WatchPhase.STOPPED }
    }
}
