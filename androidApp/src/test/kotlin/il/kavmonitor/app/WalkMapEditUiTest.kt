package il.kavmonitor.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
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
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.ui.Loadable
import il.kavmonitor.shared.ui.Screen
import il.kavmonitor.shared.map.mapBuses
import il.kavmonitor.shared.util.israelClock
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchStateHolder
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/**
 * v0.4: real walking routes (recorded FOSSGIS OSRM answers) vs the straight-line fallback, the watch card rows
 * (מוצא / יעד / התראה), editing a running watch (extend, walk time; also from the notification action) without a restart,
 * and the on-demand map that refreshes only while it is open. Data: the 14:51 scan of line 194, time-shifted to "now".
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h914dp-port-420dpi")
class WalkMapEditUiTest {
    companion object {
        @JvmStatic @BeforeClass
        fun config() { TestConfig.reset(); il.kavmonitor.shared.map.MapConfig.offline = true }
        @JvmStatic @AfterClass
        fun done() { TestConfig.reset() }
        val NEAR_36926 = 31.9625 to 34.8283
        val EAST_1200 = 31.962154 to 34.840600
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val lm get() = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val c get() = app.graph.controller
    private val outDir = File("../docs/screenshots/v0.4").apply { mkdirs() }
    private val log = StringBuilder()
    private val timeout = 15_000L

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path)
    }
    private fun has(t: String, sub: Boolean = true) = compose.onAllNodes(hasText(t, substring = sub)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String) = compose.waitReal(timeout) { has(t) }
    private fun click(t: String, sub: Boolean = true) {
        waitFor(t)
        compose.onAllNodes(hasText(t, substring = sub)).onFirst().performClick()
    }
    private fun scrollTo(t: String) {
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(t, substring = true))
        compose.waitForIdle()
    }
    private fun scrollTop() = compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)
    private fun note(s: String) { log.append(s).append('\n'); File(outDir, "walk_map_edit_log.txt").writeText(log.toString()) }
    private fun status() = shadowOf(nm).allNotifications.single { it.channelId == Notifications.CHANNEL_STATUS }
    private fun bigText(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()

    private fun setLocation(p: Pair<Double, Double>) {
        ShadowLooper.idleMainLooper(21, TimeUnit.SECONDS)
        for (provider in lm.allProviders) {
            val loc = Location(provider).apply {
                latitude = p.first; longitude = p.second; accuracy = 8f
                time = System.currentTimeMillis(); elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            }
            shadowOf(lm).setLastKnownLocation(provider, loc)
            shadowOf(lm).simulateLocation(loc)
        }
        compose.waitReal(timeout) { c.location.fix?.lat == p.first }
    }

    @Before
    fun setUp() {
        WatchStateHolder.update(null)
        TestHttp.clear()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) shadowOf(lm).setProviderEnabled(p, true)
    }

    @Test
    fun routedWalk_watchCard_editRunningWatch_mapOnlyWhileOpen() {
        waitFor("מספר קו")
        compose.onNode(hasSetTextAction()).performTextReplacement("194")
        compose.onNode(hasSetTextAction()).performImeAction()
        click("ת. רכבת תל אביב- סבידור/רציפים A", sub = false)
        waitFor("התחנה הקרובה אליך במסלול")

        // 1. nearest stop: straight-line distance + the real walking route (one OSRM request for this ~50 m cell)
        setLocation(NEAR_36926)
        waitFor("13. \u2068שלמה לוי/שער יפו")
        waitFor("מ׳ הליכה ·")
        val stop36926 = (c.stops as Loadable.Ready).value.stops.first { it.code == "36926" }
        val w1 = c.autoWalk(stop36926)!!
        note("near 36926: routed=${w1.routed} ${w1.distanceMeters.toInt()} m ${w1.minutes} min (straight ~55 m)")
        assertTrue(w1.routed)
        assertEquals(89.0, w1.distanceMeters, 2.0)
        shot("01_nearest_stop_routed_walk")

        // 2. time to leave from ~1.2 km east: routed 2611 m / 35 min vs straight line 1200 m → 20 min (×1.3 ÷ 80 m/min)
        setLocation(EAST_1200)
        click("התחל מעקב")
        val setup = hasTestTag("watch-setup-list")
        compose.onNode(setup).performScrollToNode(hasText("זמן לצאת: כשצריך לצאת", substring = true))
        click("זמן לצאת: כשצריך לצאת")
        compose.waitReal(timeout) { c.trigger == TriggerType.LEAVE_FOR_STOP && c.targetStopCode == "36926" }
        compose.onNode(setup).performScrollToNode(hasText("זמן הליכה לתחנה", substring = true))
        waitFor("2611 מ׳ הליכה · 35 דק׳")
        assertTrue(has("מסלול הליכה אמיתי (OSRM"))
        val osrm = TestHttp.snapshot().filter { it.host == "osrm.test" }
        note("walk panel: ${c.leavePlan(stop36926)}; osrm requests=${osrm.size}")
        shot("02_walk_panel_routed")

        // 3. routing down → straight line, labeled as an estimate
        WalkReplay.down = true
        setLocation(31.9622 to 34.8400) // another cell, no recorded route
        compose.waitReal(timeout) { c.leavePlan(stop36926).let { !it.walkRouted && it.walkMinutes > 0 } }
        assertFalse(has("בקו אווירי"))
        shot("03_walk_panel_straight_line_fallback")
        WalkReplay.down = false
        setLocation(EAST_1200) // cached: no new request
        waitFor("2611 מ׳ הליכה · 35 דק׳")
        assertEquals(osrm.size + 1, TestHttp.snapshot().count { it.host == "osrm.test" })

        // 4. start: watch card rows מוצא / יעד / התראה
        compose.onNode(hasTestTag("watch-setup-confirm")).performClick()
        val startIntent = shadowOf(app).nextStartedService
        assertNotNull(startIntent)
        val service: ServiceController<WatchService> = Robolectric.buildService(WatchService::class.java, startIntent).create().startCommand(0, 1)
        compose.waitReal(timeout) { WatchStateHolder.state.value?.lastSuccessAt != null }
        val st0 = WatchStateHolder.state.value!!
        assertEquals(35, st0.leavePlan!!.walkMinutes)
        assertTrue(st0.leavePlan!!.walkRouted)
        // v0.6: origin → destination in the compact header, the trigger in the info card under the hero
        waitFor("סבידור/רציפים A\u2069 ← \u2068ת. רכבת הראשונים, ראשון לציון"); waitFor("זמן לצאת ל")
        compose.waitReal(timeout) { compose.onAllNodes(hasTestTag("hero-PLENTY") or hasTestTag("hero-GET_READY") or hasTestTag("hero-NOW") or hasTestTag("hero-NONE")).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(has("לפי מסלול הליכה, 2611 מ׳"))
        shot("04_watch_card_rows_leave")
        val n0 = status()
        note("ongoing (start): title=${n0.extras.getCharSequence(Notification.EXTRA_TITLE)}\n${bigText(n0)}\nactions=${n0.actions?.joinToString { it.title }}")
        assertTrue(bigText(n0), bigText(n0).replace("\u2068", "").replace("\u2069", "").contains("מוצא: ת. רכבת תל אביב- סבידור/רציפים A\nיעד: ת. רכבת הראשונים, ראשון לציון\nהתראה: זמן לצאת לשלמה לוי/שער יפו"))
        assertTrue(n0.actions!!.any { it.title == "+15 דק׳" })

        // 5. extend +30 from the screen: same run (no restart), new end time in the card and the notification
        val polls0 = WatchStateHolder.state.value!!.polls
        click("+30", sub = false)
        service.withIntent(shadowOf(app).nextStartedService).startCommand(0, 2)
        compose.waitReal(timeout) { WatchStateHolder.state.value!!.endsAt == st0.endsAt + 30.minutes }
        assertTrue(service.get().isPolling)
        assertEquals(st0.startedAt, WatchStateHolder.state.value!!.startedAt)
        compose.waitReal(timeout) { WatchStateHolder.state.value!!.polls > polls0 + 1 }
        assertEquals("buses seen kept", st0.busesSeen, WatchStateHolder.state.value!!.busesSeen)
        val until = WatchStateHolder.state.value!!.endsAt.israelClock()
        waitFor("פעיל עד $until")
        assertTrue(bigText(status()).contains("עד $until"))
        // walk time edit on the running watch (manual from now on)
        scrollTo("זמן הליכה:")
        compose.onAllNodes(hasText("+", substring = false) and hasClickAction()).onFirst().performClick()
        compose.waitReal(timeout) { il.kavmonitor.shared.watch.LeavePlanHolder.planFor(st0.spec)?.walkMinutes == 36 }
        scrollTop()
        waitFor("36 דק׳ + מרווח")
        shot("05_watch_extended_and_walk_edited")

        // 6. "extend 15 min" from the notification action
        val action = status().actions!!.single { it.title == "+15 דק׳" }
        service.withIntent(shadowOf(action.actionIntent).savedIntent).startCommand(0, 3)
        compose.waitReal(timeout) { WatchStateHolder.state.value!!.endsAt == st0.endsAt + 45.minutes }
        val n1 = status()
        note("ongoing (after +30 and +15): ${n1.extras.getCharSequence(Notification.EXTRA_TEXT)}\n${bigText(n1)}")

        // 7. map: the compact map under the hero opens full screen on tap
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("map-fullscreen"))
        compose.waitForIdle()
        compose.onAllNodes(hasTestTag("map-fullscreen")).onFirst().performClick()
        compose.waitReal(timeout) { c.screen == Screen.MAP }
        compose.waitReal(timeout) { c.mapLive.isRefreshing && c.mapLive.state?.snapshot != null }
        waitFor("אוטובוסים על המפה")
        val buses = c.mapLive.state!!.snapshot!!.mapBuses()
        note("map: ${buses.size} buses ${buses.map { it.label }}, stops=${c.mapRequest!!.stops.size}, highlight=${c.mapRequest!!.targetStopCode}")
        assertEquals("36926", c.mapRequest!!.targetStopCode)
        assertFalse("the watch screen's route view is detached while the map is open", c.routeLive.isRefreshing)
        compose.passTime(4_000)
        shot("06_map_open")
        val mapRefreshes = c.mapLive.refreshes
        click("סגירת המפה")
        compose.waitReal(timeout) { c.screen == Screen.WATCH }
        compose.waitReal(timeout) { !c.mapLive.isRefreshing }
        compose.passTime(7_000) // > 2 route-view intervals (3 s in tests)
        assertEquals("map stopped refreshing after close", mapRefreshes, c.mapLive.refreshes)
        note("map closed: refreshes stayed at $mapRefreshes")

        // 8. Stop: zero requests afterwards (watch, route view, map, walking)
        scrollTop()
        click("עצור מעקב")
        service.withIntent(shadowOf(app).nextStartedService).startCommand(0, 4)
        compose.waitReal(timeout) { WatchStateHolder.state.value?.phase == WatchPhase.STOPPED }
        assertFalse(service.get().isPolling)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.passTime(500)
        val before = TestHttp.count()
        compose.passTime(5_000)
        assertEquals("no HTTP after Stop", before, TestHttp.count())

        // 9. recents with the same rows
        waitFor("אחרונים")
        shot("07_recents_rows")
    }
}
