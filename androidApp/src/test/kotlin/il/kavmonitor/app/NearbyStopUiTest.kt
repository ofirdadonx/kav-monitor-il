package il.kavmonitor.app

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.stop.StopArrivals
import il.kavmonitor.shared.ui.Loadable
import il.kavmonitor.shared.ui.Screen
import il.kavmonitor.shared.watch.WatchStateHolder
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * v0.5: home "תחנות קרובות" (real curlbus `/nearby` answer at Azrieli), the stop screen (real `/21472` answer: all lines,
 * live/scheduled), tapping a line → the new-watch flow prefilled (line 194, direction 37121, ARRIVES at 21472), the stop
 * screen polling only while shown, and the route view with ONE multi-stop request per refresh.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h914dp-port-420dpi")
class NearbyStopUiTest {
    companion object {
        @JvmStatic @BeforeClass
        fun config() { TestConfig.reset(); StopReplay.active = true; TestConfig.routeLiveInterval = 3.seconds; il.kavmonitor.shared.map.MapConfig.offline = true; File("../docs/screenshots/v0.5/nearby_stop_log.txt").delete() }
        @JvmStatic @AfterClass
        fun done() { TestConfig.reset() }
        val AZRIELI = 32.0746 to 34.7916 // ~15 m from where the /nearby answer was recorded (32.0745, 34.7915)
        const val CURLBUS = "curlbus.test"
        const val ROUTE = "curlbus-route.test"
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val c get() = app.graph.controller
    private val lm get() = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val outDir = File("../docs/screenshots/v0.5").apply { mkdirs() }
    private val log = StringBuilder()
    private val timeout = 15_000L

    private fun note(s: String) { log.append(s).append('\n'); File(outDir, "nearby_stop_log.txt").appendText(s + "\n") }
    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path)
    }
    private fun has(t: String) = compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun hasTag(t: String) = compose.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String) = compose.waitReal(timeout) { has(t) }
    private fun waitTag(t: String) = compose.waitReal(timeout) { hasTag(t) }
    private fun stopRequests() = c.stopArrivals.requests

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
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) shadowOf(lm).setProviderEnabled(p, true)
    }

    @Test
    fun withoutLocationPermission_quietEnableRow_noNearbyRequest() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        waitTag("nearby-enable")
        assertTrue(has("הפעלת מיקום"))
        assertFalse(hasTag("nearby-section"))
        compose.passTime(2_000)
        assertEquals(0, TestHttp.snapshot().count { it.path == "/nearby" })
        shot("00_home_nearby_no_permission")
    }

    @Test
    fun nearbyStops_stopScreen_lineToNewWatch_routeView() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        waitFor("מעקב חדש")
        // the activity was started (rule) before the grant: re-check like onStart does when the user comes back
        compose.runOnUiThread { c.location.setAppVisible(true) }
        setLocation(AZRIELI)

        // 1. home: the 5 nearest stops from ONE /nearby request (radius 300; the real answer has 11 stops)
        waitTag("nearby-section")
        waitTag("nearby-21472")
        val near = (c.nearby as Loadable.Ready).value
        note("nearby: ${near.map { "${it.code} ${it.nameHe} ${it.distanceMeters.toInt()} m" }}")
        assertEquals(listOf("23014", "21472", "21669", "17046", "21022"), near.map { it.code })
        note("nearby requests: ${NearbyReplay.asked}")
        assertEquals(NearbyReplay.asked.toString(), 1, TestHttp.snapshot().count { it.path == "/nearby" })
        assertTrue(has("37 מ׳ בקו אווירי"))
        // routed walking time for the top stop only (real FOSSGIS OSRM answer: 222 m / 3 min — Begin Rd. has to be crossed)
        waitFor("מ׳ הליכה ·")
        assertEquals(1, TestHttp.snapshot().count { it.host == "osrm.test" })
        note("top stop walk: ${c.autoWalk(il.kavmonitor.shared.model.RouteStop(0, near[0].code, near[0].nameHe, null, near[0].lat, near[0].lon))}")
        // same ~100 m cell again: cached, no request
        c.loadNearby(AZRIELI.first + 0.0002, AZRIELI.second)
        compose.passTime(500)
        assertEquals(1, TestHttp.snapshot().count { it.path == "/nearby" })
        shot("01_home_nearby_stops")

        // 2. stop screen: all lines from ONE /21472 request, refreshed every interval only while shown
        compose.onAllNodes(hasTestTag("nearby-21472")).onFirst().performClick()
        compose.waitReal(timeout) { c.screen == Screen.STOP }
        waitFor("● חי (GPS)")
        assertTrue(has("○ לפי לו״ז"))
        assertTrue(has("מטרופולין"))
        val arrivals = StopArrivals.from(c.stopArrivals.state!!.snapshot!!, app.graph.clock.now())
        note("stop 21472: ${arrivals.size} arrivals, ${arrivals.count { it.live }} live; first: ${arrivals.take(5).map { "${it.lineName}→${it.destination} ${it.operatorName} live=${it.live}" }}")
        assertTrue(arrivals.size >= 60)
        assertEquals(1, stopRequests())
        shot("02_stop_screen_21472")
        compose.passTime(7_000) // 3 s interval in tests
        val whileShown = stopRequests()
        note("stop requests after ~7 s on the screen: $whileShown")
        assertTrue("refreshes while shown: $whileShown", whileShown in 2..4)

        // 3. tap line 194 → new watch prefilled: line 194, direction 37121, "arrives at" 21472
        val a194 = arrivals.first { it.lineName == "194" }
        val routeBefore = TestHttp.count(ROUTE) // the stop screen's own /21472 requests (same host)
        val tag = "arrival-${a194.key}"
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(tag))
        compose.onAllNodes(hasTestTag(tag)).onFirst().performClick()
        compose.waitReal(timeout) {
            c.screen == Screen.NEW_WATCH && c.selectedDirection?.lineRef == 37121 && c.trigger == TriggerType.ARRIVES_AT_STOP && c.targetStopCode == "21472"
        }
        assertEquals("194", c.lineInput)
        val left = stopRequests()
        compose.passTime(7_000)
        assertEquals("no stop-screen request after leaving it", left, stopRequests())
        assertFalse(c.stopArrivals.isRefreshing)

        // 4. route view of that direction: ONE multi-stop request per refresh (+ the one-time bisect)
        waitFor("נתונים מ-")
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)
        shot("03_new_watch_prefilled_from_stop")
        val first = TestHttp.snapshot().count { it.host == ROUTE }
        compose.passTime(7_000)
        val later = TestHttp.snapshot().filter { it.host == ROUTE }.drop(first)
        note("route view: ${first - routeBefore} requests up to screenshot 03 (one-time bisect of 18 + 1 per refresh since), then ${later.size} in ~7 s: ${later.map { it.path.split('+').size }} stops each")
        assertTrue(later.isNotEmpty() && later.all { it.path.split('+').size == 24 })
        compose.waitReal(timeout) { runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("נתונים מ-", substring = true)) }.isSuccess }
        shot("04_route_view_full_route")
    }
}
