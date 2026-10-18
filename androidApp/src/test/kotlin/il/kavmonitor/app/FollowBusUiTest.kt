package il.kavmonitor.app

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import il.kavmonitor.shared.util.israelClock
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Follow one bus: long-press a bus card in the live route view → "פעולות לרכב" → follow → followed-bus card
 * (curlbus ETA to the stop nearest the user, straight-line distance, next stop, update age); the bus disappearing;
 * no requests once the screen is left (still following). Replay: the real 194 scan of 14:51, shifted to now.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h914dp-port-420dpi")
class FollowBusUiTest {
    companion object {
        @JvmStatic @BeforeClass
        fun config() { TestConfig.reset(); TestConfig.routeLiveInterval = 1.seconds }
        const val ROUTE = "curlbus-route.test"
        /** ~40 m from 20750 Carlebach (32.065556, 34.783121). */
        val NEAR_20750 = 32.0658 to 34.7834
        const val BUS_KEY = "585417608_061026" // vehicle 7692269, 9% of the route at 14:51
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val c get() = app.graph.controller
    private val lm get() = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val outDir = File("../docs/screenshots/v0.4/follow_bus").apply { mkdirs() }
    private val log = StringBuilder()
    private val timeout = 15_000L

    private fun note(s: String) { log.append(s).append('\n'); File(outDir, "follow_bus_log.txt").writeText(log.toString()) }
    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "$name.png").path)
    }
    private fun has(t: String) = compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun hasTag(t: String) = compose.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String) = compose.waitReal(timeout) { has(t) }
    private fun click(t: String, sub: Boolean = true) = compose.onAllNodes(hasText(t, substring = sub)).onFirst().performClick()
    private fun scrollToTag(t: String) = compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag(t))
    private fun texts(tag: String): String = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()
        .joinToString(" | ") { n -> n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.joinToString() } +
        compose.onAllNodes(androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes()
            .joinToString(" | ") { n -> n.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.joinToString() }

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

    /** The follow card is a LazyColumn item: scroll to it before looking for its text. */
    private fun waitCard(t: String) = compose.waitReal(timeout) { runCatching { scrollToTag("follow-card") }; has(t) }

    private fun quiet(ms: Long = 3_000) {
        val before = TestHttp.count(ROUTE)
        compose.passTime(ms)
        assertEquals("no route-view request in ${ms}ms", before, TestHttp.count(ROUTE))
    }

    @Before
    fun setUp() {
        WatchStateHolder.update(null)
        TestHttp.clear()
        c.unfollow()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) shadowOf(lm).setProviderEnabled(p, true)
    }

    @Test
    fun followOneBus() {
        waitFor("מספר קו")
        compose.onNode(hasSetTextAction()).performTextInput("194")
        compose.onNode(hasSetTextAction()).performImeAction()
        waitFor("ת. רכבת תל אביב- סבידור/רציפים A")
        click("ת. רכבת תל אביב- סבידור/רציפים A", sub = false)
        waitFor("נתונים מ-")
        setLocation(NEAR_20750)

        // 1. long-press the card of vehicle 7692269 → the actions sheet
        compose.waitReal(timeout) { runCatching { scrollToTag("bus-card-$BUS_KEY") }.isSuccess && hasTag("bus-card-$BUS_KEY") }
        compose.onAllNodes(hasTestTag("bus-card-$BUS_KEY")).onFirst().performTouchInput { longClick() }
        compose.waitReal(timeout) { c.busActions != null }
        waitFor("פעולות לרכב")
        assertTrue(has("רכב \u20687692269\u2069"))
        assertTrue(has("יציאה מתוכננת " + c.busActions!!.scheduledStart!!.israelClock()))
        assertTrue(has("${c.busActions!!.percent}% מהמסלול"))
        assertTrue(has("עקוב אחרי האוטובוס הזה"))
        note("sheet: ${c.busActions}")
        shot("08_bus_actions_sheet")

        // 2. follow → prominent card; the ETA at the stop nearest the user (20750) comes from the same full-route request
        val beforeFollow = TestHttp.count(ROUTE)
        click("עקוב אחרי האוטובוס הזה")
        compose.waitReal(timeout) { c.followed != null && c.busActions == null }
        assertEquals("7692269", c.followed?.vehicleRef)
        waitCard("לפי curlbus: מגיע ל")
        // v0.5: the ETA at the user's stop (20750) comes from the same full-route request: following adds no request
        assertTrue("full-route request covers 20750", TestHttp.snapshot().last { it.host == ROUTE }.path.split('+').contains("20750"))
        note("route requests while following: ${TestHttp.count(ROUTE) - beforeFollow} (1 per refresh)")
        scrollToTag("follow-card")
        val card = texts("follow-card")
        note("card: $card")
        assertTrue(card, card.contains("עוקב אחרי"))
        assertTrue(card, Regex("מגיע ל.*(בעוד \\d+ דק׳|עכשיו)").containsMatchIn(card))
        assertFalse(card, card.contains("בקו אווירי"))
        assertTrue(card, card.contains("תחנה הבאה:"))
        assertTrue(card, card.contains("מיקום עודכן לפני"))
        assertFalse("no trip id on the card", card.contains("_061026"))
        shot("09_followed_bus_card")
        scrollToTag("bus-card-$BUS_KEY")
        assertTrue("bus card marked as followed", has("📍 במעקב"))

        // 3. the trip disappears from curlbus (ended / lost GPS) → clear message + unfollow offered
        RouteReplay.hiddenVehicles += "7692269"
        waitCard("כבר לא מופיע בנתונים החיים")
        scrollToTag("follow-card")
        assertTrue(has("נראה לאחרונה ב-"))
        note("gone: ${texts("follow-card")}")
        shot("10_followed_bus_disappeared")
        RouteReplay.hiddenVehicles.clear()
        waitCard("לפי curlbus: מגיע ל")
        note("reappeared → card tracking again")

        // 4. leave the screen while still following → no requests (the follow has no poller of its own)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertNotNull(c.followed)
        assertFalse(c.routeLive.isRefreshing)
        quiet()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        quiet()
        note("left the screen while following: no route requests")

        // 5. back on the line: unfollow from the card
        compose.onNode(hasSetTextAction()).performTextReplacement("194")
        compose.onNode(hasSetTextAction()).performImeAction()
        waitFor("ת. רכבת תל אביב- סבידור/רציפים A")
        click("ת. רכבת תל אביב- סבידור/רציפים A", sub = false)
        compose.waitReal(timeout) { hasTag("follow-card") || runCatching { scrollToTag("follow-card") }.isSuccess }
        scrollToTag("follow-card")
        click("הפסק לעקוב")
        compose.waitReal(timeout) { c.followed == null }
        assertNull(c.followed)
        assertFalse(hasTag("follow-card"))
        note("unfollowed")
    }
}
