package il.kavmonitor.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import il.kavmonitor.shared.location.LocationAccess
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.LeaveStatus
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit
import org.robolectric.shadows.ShadowLooper
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * v0.3: "nearest stop to me" (mocked locations through the real LocationManager shadow), the permission-denied path,
 * location updates stopping when the screen is left, and a "time to leave" watch end to end (replayed real data:
 * the 14:51 scan of 36926, time-shifted to "now").
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h914dp-port-420dpi")
class NearestLeaveUiTest {
    companion object {
        @JvmStatic @BeforeClass
        fun config() { TestConfig.reset() }
        val sharedLog = StringBuilder()
        val SAVIDOR = 32.0835 to 34.7990
        /** ~55 m from 36926 Shlomo Levi/Sha'ar Yafo (31.962154, 34.827882). */
        val NEAR_36926 = 31.9625 to 34.8283
        /** ~1.2 km / ~1.5 km east of 36926: walk 20 / 25 min. */
        val EAST_1200 = 31.962154 to 34.840600
        val EAST_1510 = 31.962154 to 34.843900
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val lm get() = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val c get() = app.graph.controller
    private val outDir = File("../docs/screenshots/v0.4/nearest_leave").apply { mkdirs() }
    private val log = sharedLog
    private val timeout = 15_000L

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path)
    }
    private fun has(t: String, sub: Boolean = true) = compose.onAllNodes(hasText(t, substring = sub)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String) = compose.waitReal(timeout) { has(t) }
    private fun click(t: String, sub: Boolean = true) = compose.onAllNodes(hasText(t, substring = sub)).onFirst().performClick()
    private fun scrollTo(t: String) = compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(t, substring = true))
    private fun scrollTop() = compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)
    private fun note(s: String) { log.append(s).append('\n'); File(outDir, "nearest_leave_log.txt").writeText(log.toString()) }

    private fun setLocation(p: Pair<Double, Double>, accuracy: Float = 8f) {
        // ShadowLocationManager honours the request's min interval (20 s): let that much (fake) time pass first.
        ShadowLooper.idleMainLooper(21, TimeUnit.SECONDS)
        for (provider in lm.allProviders) {
            val loc = Location(provider).apply {
                latitude = p.first; longitude = p.second; this.accuracy = accuracy
                time = System.currentTimeMillis(); elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            }
            shadowOf(lm).setLastKnownLocation(provider, loc)
            shadowOf(lm).simulateLocation(loc)
        }
        compose.waitReal(timeout) { c.location.fix?.lat == p.first }
        note("location set: ${p.first},${p.second}")
    }

    /**
     * What the system does when the permission dialog closes: clear the Activity's "request in flight" flag (normally done
     * by the hidden dispatchRequestPermissionsResult) and deliver the result. Calling onRequestPermissionsResult alone
     * would leave the flag set, and the next request would be answered "denied" immediately.
     */
    private fun deliverPermissionResult(activity: android.app.Activity, req: org.robolectric.shadows.ShadowActivity.PermissionsRequest, result: Int) {
        android.app.Activity::class.java.getDeclaredField("mHasCurrentPermissionsRequest").apply { isAccessible = true }.setBoolean(activity, false)
        activity.onRequestPermissionsResult(req.requestCode, req.requestedPermissions, IntArray(req.requestedPermissions.size) { result })
    }

    private val listeners get() = shadowOf(lm).locationUpdateListeners.size

    private fun openDirection194() {
        waitFor("מעקב חדש")
        click("מעקב חדש")
        compose.onNode(hasSetTextAction()).performTextInput("194")
        click("חפש", sub = false)
        waitFor("ת. רכבת תל אביב- סבידור/רציפים A")
        click("ת. רכבת תל אביב- סבידור/רציפים A", sub = false)
        waitFor("התחנה הקרובה אליך במסלול")
    }

    @Before
    fun setUp() {
        WatchStateHolder.update(null)
        TestHttp.clear()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) shadowOf(lm).setProviderEnabled(p, true)
    }

    @Test
    fun nearestStopAndTimeToLeave() {
        // walking router "down" (HTTP 500): the straight-line estimate is used and labeled as such (the routed path is
        // covered in WalkMapEditUiTest); keeps this replay's walk times (20 / 25 min) as recorded for v0.3
        WalkReplay.down = true
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        openDirection194()
        assertEquals(LocationAccess.GRANTED, c.location.access)
        assertTrue("location updates requested while the section is shown", listeners > 0)

        // 1. Near Savidor → the origin, straight-line distance
        setLocation(SAVIDOR)
        waitFor("ממך בקו אווירי")
        assertTrue(has("מ׳ ממך בקו אווירי"))
        assertTrue("origin already is the departs stop", has("נבחרה כתחנת ההתראה"))
        note("near Savidor: nearest=${c.location.fix} → 20001")
        shot("01_nearest_stop_near_savidor")

        // 2. Near a mid-route stop → 36926, one tap makes it the "arrives at" stop
        setLocation(NEAR_36926)
        waitFor("13. \u2068שלמה לוי/שער יפו")
        shot("02_nearest_stop_mid_route")
        click("התראה כשהאוטובוס מגיע לתחנה הזו")
        compose.waitReal(timeout) { c.trigger == TriggerType.ARRIVES_AT_STOP && c.targetStopCode == "36926" }
        waitFor("נבחרה כתחנת ההתראה")
        shot("03_nearest_used_as_arrives_stop")

        // 3. Time to leave: boarding stop 36926 (kept), walk time from the location (~1.2 km → 20 min) + 1 min buffer
        setLocation(EAST_1200)
        scrollTo("זמן לצאת: כשצריך לצאת")
        click("זמן לצאת: כשצריך לצאת")
        compose.waitReal(timeout) { c.trigger == TriggerType.LEAVE_FOR_STOP }
        assertEquals("36926", c.targetStopCode)
        scrollTo("זמן הליכה לתחנה")
        waitFor("20 דק׳ הליכה")
        assertTrue(has("ההתראה תגיע 21 דק׳ לפני"))
        shot("04_leave_walk_time_from_location")
        // manual override and back
        click("+", sub = false)
        waitFor("21 דק׳ הליכה (הוגדר ידנית)")
        shot("05_leave_walk_time_manual")
        click("חישוב לפי מיקום")
        waitFor("20 דק׳ הליכה ·")
        assertTrue(has("בקו אווירי (הערכה"))

        // 4. Start: the service polls ONLY the boarding stop; per-bus leave times
        click("התחל מעקב")
        val startIntent = shadowOf(app).nextStartedService
        assertNotNull(startIntent)
        val service: ServiceController<WatchService> = Robolectric.buildService(WatchService::class.java, startIntent).create().startCommand(0, 1)
        compose.waitReal(timeout) { WatchStateHolder.state.value?.lastSuccessAt != null }
        val st = WatchStateHolder.state.value!!
        assertEquals(TriggerType.LEAVE_FOR_STOP, st.spec.trigger)
        assertEquals(20, st.leavePlan!!.walkMinutes)
        // 15:10 bus (eta +17:40): leave −3:20 → not catchable; 15:18 bus (eta +25:40): leave in ~4:40
        assertEquals(LeaveStatus.NOT_CATCHABLE, st.buses.single { it.vehicleRef == "23373402" }.leaveStatus)
        assertEquals(LeaveStatus.WAIT, st.buses.single { it.vehicleRef == "7692269" }.leaveStatus)
        assertTrue(TestHttp.snapshot().filter { it.host == "curlbus.test" }.all { it.path == "/36926" })
        waitFor("לא ניתן להספיק")
        waitFor("צאו ב-")
        val ongoing = shadowOf(nm).allNotifications.single { it.channelId == Notifications.CHANNEL_STATUS }
        note("ongoing: ${ongoing.extras.getCharSequence(Notification.EXTRA_TITLE)} | ${ongoing.extras.getCharSequence(Notification.EXTRA_TEXT)} | chronometer=${ongoing.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)} countdown=${ongoing.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)}")
        val ongoingText = ongoing.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(ongoingText, ongoingText.startsWith("יציאה הבאה ב-") && ongoingText.contains("דק׳) · עד "))
        assertTrue(ongoing.extras.getCharSequence(Notification.EXTRA_TITLE).toString().replace("\u2068", "").replace("\u2069", "") == "מעקב 194 · זמן לצאת")
        assertTrue(ongoing.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        shot("06_leave_watch_screen")

        // 5. While the app is visible the walk time follows the location: farther away (25 min) → the 15:18 bus is due now
        setLocation(EAST_1510)
        compose.waitReal(timeout) { shadowOf(nm).allNotifications.any { it.channelId == Notifications.CHANNEL_ALERT } }
        val alert = shadowOf(nm).allNotifications.single { it.channelId == Notifications.CHANNEL_ALERT }
        val title = alert.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        note("alert: $title | ${alert.extras.getCharSequence(Notification.EXTRA_TEXT)}")
        assertEquals("צא עכשיו · 194", title.replace("\u2068", "").replace("\u2069", ""))
        val body = alert.extras.getCharSequence(Notification.EXTRA_TEXT).toString().replace("\u2068", "").replace("\u2069", "")
        assertTrue(body, body.startsWith("מגיע לשלמה לוי/שער יפו ב-") && body.contains("דק׳ הליכה"))
        val ev = WatchStateHolder.state.value!!.alerts.single()
        assertEquals(TriggerReason.LEAVE_NOW, ev.reason)
        assertEquals("7692269", ev.vehicleRef)
        assertEquals(26, ev.leadMinutes)
        assertTrue(ev.live)
        compose.waitReal(timeout) { WatchStateHolder.state.value!!.buses.any { it.leaveStatus == LeaveStatus.ALERTED } }
        waitFor("נשלחה התראה לצאת")
        shot("07_leave_alert_sent")
        // more polls: no second alert for the same bus
        compose.passTime(5_000)
        assertEquals(1, shadowOf(nm).allNotifications.count { it.channelId == Notifications.CHANNEL_ALERT })

        // 6. Stop: service, polling, wake lock and location all stop; no more requests
        scrollTop()
        click("עצור מעקב")
        val stopIntent = shadowOf(app).nextStartedService
        service.withIntent(stopIntent).startCommand(0, 2)
        compose.waitReal(timeout) { WatchStateHolder.state.value?.phase == WatchPhase.STOPPED }
        assertFalse(service.get().isPolling)
        assertFalse(service.get().isWakeLockHeld)
        compose.waitReal(timeout) { !c.location.isUpdating }
        assertEquals("location listeners removed after Stop", 0, listeners)
        val before = TestHttp.count()
        compose.passTime(5_000)
        assertEquals("no HTTP after Stop", before, TestHttp.count())
        note("after stop: requests stayed at $before, location listeners=$listeners")
    }

    @Test
    fun locationDeniedShowsQuietRow_andUpdatesStopWhenTheScreenIsLeft() {
        openDirection194()
        // first time the section is shown: rationale + one system dialog
        assertEquals(LocationAccess.ASKING, c.location.access)
        assertTrue(has("כדי להראות את התחנה הקרובה אליך"))
        val activity = compose.activity
        val req = shadowOf(activity).lastRequestedPermission
        assertNotNull(req)
        assertTrue(req.requestedPermissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        shot("08_location_rationale_first_time")
        // user denies
        deliverPermissionResult(activity, req, PackageManager.PERMISSION_DENIED)
        compose.waitReal(timeout) { c.location.access == LocationAccess.DENIED }
        waitFor("אין גישה למיקום")
        assertTrue(has("הפעלת מיקום"))
        assertEquals(0, listeners)
        shot("09_location_denied_row")
        // leave and come back: no new dialog (no nagging)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        click("ת. רכבת תל אביב- סבידור/רציפים A", sub = false)
        waitFor("אין גישה למיקום")
        assertSame("no second automatic permission request", req, shadowOf(activity).lastRequestedPermission)
        // explicit tap asks again → granted → nearest stop appears, updates run
        click("הפעלת מיקום")
        val req2 = shadowOf(activity).lastRequestedPermission
        assertNotSame(req, req2)
        assertEquals("the explicit tap opens the system dialog", LocationAccess.ASKING, c.location.access)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        deliverPermissionResult(activity, req2, PackageManager.PERMISSION_GRANTED)
        compose.waitForIdle()
        note("after grant: access=${c.location.access} updating=${c.location.isUpdating} perm=${app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)}")
        compose.waitReal(timeout) { c.location.isUpdating }
        assertTrue(listeners > 0)
        setLocation(SAVIDOR)
        waitFor("ממך בקו אווירי")
        // app to background → updates stop; foreground → resume
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitReal(timeout) { !c.location.isUpdating }
        assertEquals(0, listeners)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitReal(timeout) { c.location.isUpdating }
        // leave the screen (back to the direction list, then home) → updates stop
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitReal(timeout) { !c.location.isUpdating }
        assertEquals("no location listener after leaving the screen", 0, listeners)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.passTime(1_000)
        // v0.5: home shows "תחנות קרובות" now that the permission is granted → location runs while home is shown…
        assertEquals("home's nearby section uses location while shown", 1, listeners)
        // …and never in the background
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitReal(timeout) { !c.location.isUpdating }
        assertEquals(0, listeners)
        note("denied path ok; starts=${c.location.starts}")
    }
}
