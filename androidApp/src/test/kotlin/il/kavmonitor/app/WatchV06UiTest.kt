package il.kavmonitor.app

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import kotlin.time.Duration.Companion.minutes
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.trigger.LeaveStatus
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchStateHolder
import org.junit.After
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

/**
 * v0.6 watch screen (hero card, line diagram, compact rows) on the real app with replayed data, in light and dark:
 * the four hero colours of a time-to-leave watch (driven by walk-time edits of the running watch), the departs-origin
 * and arrives-at-stop heroes, a broken-stop (fallback) watch, diagram tap → row expands, long-press → actions sheet,
 * Stop → no more requests. Screenshots: docs/screenshots/v0.6/<name>_<light|dark>.png.
 */
abstract class WatchV06Screens(private val theme: String) {
    companion object {
        @JvmStatic @BeforeClass fun config() { TestConfig.reset() }
        @JvmStatic @AfterClass fun done() { TestConfig.reset() }
        val NEAR_36926 = 31.9625 to 34.8283
        val EAST_1200 = 31.962154 to 34.840600
        const val BUS_7692269 = "585417608_061026"
        val START = System.currentTimeMillis()
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val lm get() = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val c get() = app.graph.controller
    private val outDir = File("../docs/screenshots/v0.6").apply { mkdirs() }
    private val timeout = 20_000L
    private val log = StringBuilder()
    init { File("../docs/screenshots/v0.6/v06_log_$theme.txt").let { if (it.exists() && it.lastModified() < START) it.delete() } }

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(outDir, "${name}_$theme.png").path)
    }
    private fun note(s: String) { log.append(s).append('\n'); File(outDir, "v06_log_$theme.txt").appendText(s + "\n") }
    private fun has(t: String, sub: Boolean = true) = compose.onAllNodes(hasText(t, substring = sub)).fetchSemanticsNodes().isNotEmpty()
    // (unmerged: tags inside a clickable row are merged away in the default tree)
    private fun hasNode(m: SemanticsMatcher) = compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(t: String) = compose.waitReal(timeout) { has(t) }
    private fun waitTag(tag: String) = compose.waitReal(timeout) { hasNode(hasTestTag(tag)) }
    private fun click(t: String, sub: Boolean = true) { waitFor(t); compose.onAllNodes(hasText(t, substring = sub)).onFirst().performClick() }
    private fun scrollTo(m: SemanticsMatcher) {
        compose.waitReal(timeout) { runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(m) }.isSuccess }
        compose.waitForIdle()
    }
    private fun scrollTo(t: String) = scrollTo(hasText(t, substring = true))
    private fun scrollTop() { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0); compose.waitForIdle() }
    private fun heroText(): String = heroTexts(compose)

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
        TestConfig.reset() // FallbackReplay / TestClock / WalkReplay set by an earlier test of this class
        WatchStateHolder.update(null)
        TestHttp.clear()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) shadowOf(lm).setProviderEnabled(p, true)
    }

    private fun openDirection194() {
        waitFor("חיפוש קו")
        click("חיפוש קו")
        compose.onNode(hasSetTextAction()).performTextReplacement("194")
        click("חפש", sub = false)
        waitFor("ת. רכבת תל אביב- סבידור/רציפים A")
        click("ת. רכבת תל אביב- סבידור/רציפים A", sub = false)
        waitFor("התחנה הקרובה אליך במסלול")
    }

    private fun pickStop(name: String, code: String) {
        val row = hasText(name, substring = true) and hasText(code, substring = true) and hasClickAction()
        scrollTo(row)
        compose.onAllNodes(row).onLast().performClick()
        compose.waitReal(timeout) { c.targetStopCode == code }
    }

    private var running: ServiceController<WatchService>? = null

    /** Never leave a polling service behind for the next test (WatchStateHolder is process-wide). */
    @After fun tearDown() { runCatching { running?.destroy() }; running = null; TestConfig.reset() }

    private fun start(): ServiceController<WatchService> {
        click("התחל מעקב")
        val intent = shadowOf(app).nextStartedService
        assertNotNull(intent)
        val service = Robolectric.buildService(WatchService::class.java, intent).create().startCommand(0, 1)
        running = service
        compose.waitReal(timeout) { WatchStateHolder.state.value?.lastSuccessAt != null }
        return service
    }

    private fun stopAndAssertQuiet(service: ServiceController<WatchService>) {
        scrollTop()
        click("עצור מעקב")
        service.withIntent(shadowOf(app).nextStartedService).startCommand(0, 2)
        compose.waitReal(timeout) { WatchStateHolder.state.value?.phase == WatchPhase.STOPPED }
        assertFalse(service.get().isPolling)
        compose.passTime(500)
        waitTag("hero-NONE")
        assertTrue(has("המעקב נעצר"))
        val before = TestHttp.count()
        compose.passTime(4_000)
        compose.mainClock.advanceTimeBy(4_000)
        compose.passTime(500)
        assertEquals("no HTTP after Stop: ${TestHttp.snapshot().drop(before).map { it.host + it.path }}", before, TestHttp.count())
    }

    private fun setWalk(minutes: Int) {
        val spec = WatchStateHolder.state.value!!.spec
        compose.runOnUiThread { c.setWatchWalkMinutes(spec, minutes) }
        compose.waitReal(timeout) { WatchStateHolder.state.value?.leavePlan?.walkMinutes == minutes }
    }

    /** Time to leave (36926): green → orange → gray → red by changing the walk time of the running watch. */
    @Test
    fun leaveWatch_heroStates_diagramTap_longPress_stop() {
        WalkReplay.down = true
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        openDirection194()
        setLocation(NEAR_36926)
        waitFor("13. \u2068שלמה לוי/שער יפו")
        click("התראה כשהאוטובוס מגיע לתחנה הזו")
        compose.waitReal(timeout) { c.targetStopCode == "36926" }
        setLocation(EAST_1200)
        scrollTo("זמן לצאת: כשצריך לצאת")
        click("זמן לצאת: כשצריך לצאת")
        compose.waitReal(timeout) { c.trigger == TriggerType.LEAVE_FOR_STOP }
        assertEquals("36926", c.targetStopCode)
        // new-watch route view: diagram + compact rows with minutes to the chosen stop
        scrollTo(hasTestTag("line-diagram"))
        shot("10_new_watch_route_view")

        val service = start()
        // walk 20 + 1: 23373402 (bus in +17:40) can't be caught, 7692269 (+25:40) → leave in ~4:40 → green
        waitTag("hero-PLENTY")
        assertTrue(has("צא בעוד"))
        assertEquals(LeaveStatus.WAIT, WatchStateHolder.state.value!!.buses.single { it.vehicleRef == "7692269" }.leaveStatus)
        shot("01_leave_hero_green")
        note("green: ${heroText()}")

        // the rest of the screen: line diagram (user stop highlighted) and the compact rows
        scrollTo(hasTestTag("diagram-user-stop"))
        shot("05_leave_line_diagram")
        // tap the bus in the diagram = tap its row: the row expands (and the list scrolls to it)
        scrollTo(hasTestTag("diagram-bus-$BUS_7692269"))
        compose.onAllNodes(hasTestTag("diagram-bus-$BUS_7692269")).onFirst().performClick()
        waitTag("bus-details-$BUS_7692269")
        compose.passTime(800)
        assertTrue(has("רכב 7692269"))
        shot("06_leave_row_expanded_from_diagram")
        // tap the row again: collapses
        compose.onAllNodes(hasTestTag("bus-card-$BUS_7692269")).onFirst().performClick()
        compose.waitReal(timeout) { !hasNode(hasTestTag("bus-details-$BUS_7692269")) }
        // long-press keeps the vehicle actions sheet (follow bus)
        compose.onAllNodes(hasTestTag("bus-card-$BUS_7692269")).onFirst().performTouchInput { longClick() }
        waitTag("bus-actions-sheet")
        assertTrue(has("פעולות לרכב"))
        shot("07_leave_row_long_press_actions")
        click("ביטול", sub = false)
        compose.waitReal(timeout) { !hasNode(hasTestTag("bus-actions-sheet")) }
        // long-press on the bus in the diagram opens the same sheet
        scrollTo(hasTestTag("diagram-bus-$BUS_7692269"))
        compose.onAllNodes(hasTestTag("diagram-bus-$BUS_7692269")).onFirst().performTouchInput { longClick() }
        waitTag("bus-actions-sheet")
        click("ביטול", sub = false)
        scrollTop()

        // walk 22: leave in ~2:40 → orange
        setWalk(22)
        waitTag("hero-GET_READY")
        shot("02_leave_hero_orange")
        note("orange: ${heroText()}")
        // walk 60: nothing catchable → gray
        setWalk(60)
        waitTag("hero-NONE")
        assertTrue(has("אין אוטובוס שאפשר להספיק"))
        shot("03_leave_hero_gray")
        note("gray: ${heroText()}")
        // walk 25: 7692269 is due now → the leave alert fires → red
        setWalk(25)
        waitTag("hero-NOW")
        compose.waitReal(timeout) { WatchStateHolder.state.value!!.alerts.any { it.reason == TriggerReason.LEAVE_NOW } }
        assertTrue(has("צא עכשיו"))
        shot("04_leave_hero_red")
        note("red: ${heroText()}")
        stopAndAssertQuiet(service)
        shot("09_watch_stopped")
    }

    @Test
    fun departsOriginWatch() {
        openDirection194()
        scrollTo("כשהאוטובוס יוצא מתחנת המוצא")
        click("כשהאוטובוס יוצא מתחנת המוצא")
        val service = start()
        compose.waitReal(timeout) { hasNode(hasTestTag("hero-PLENTY") or hasTestTag("hero-GET_READY") or hasTestTag("hero-NOW")) }
        // next departure, or "יצא" for two minutes after a departure the watch detected
        assertTrue(heroText(), has("האוטובוס יוצא בעוד") || has("יוצא עכשיו") || has("האוטובוס יצא"))
        shot("11_departs_hero")
        note("departs: ${heroText()}")
        scrollTo(hasTestTag("line-diagram"))
        shot("12_departs_diagram_rows")
        stopAndAssertQuiet(service)
    }

    @Test
    fun arrivesAtStopWatch() {
        openDirection194()
        scrollTo("כשהאוטובוס מגיע לתחנה שאבחר")
        click("כשהאוטובוס מגיע לתחנה שאבחר")
        pickStop("שלמה לוי/שער יפו", "36926")
        val service = start()
        compose.waitReal(timeout) { hasNode(hasTestTag("hero-PLENTY") or hasTestTag("hero-GET_READY") or hasTestTag("hero-NOW")) }
        assertTrue(has("מגיע לתחנה בעוד") || has("מגיע עכשיו"))
        shot("13_arrives_hero")
        note("arrives: ${heroText()}")
        scrollTo(hasText("אוטובוסים לתחנה שלך", substring = true))
        shot("14_arrives_rows")
        stopAndAssertQuiet(service)
    }

    /** 25141 isn't served by curlbus: the watch uses its neighbours; hero, diagram and rows say "(הערכה)". */
    @Test
    fun fallbackStopWatch() {
        FallbackReplay.active = true
        TestClock.set(FallbackUiTest.REPLAY_START, 1.0)
        openDirection194()
        scrollTo("כשהאוטובוס מגיע לתחנה שאבחר")
        click("כשהאוטובוס מגיע לתחנה שאבחר")
        pickStop("דרך מנחם בגין/נחמני", "25141")
        compose.waitReal(timeout) { c.routeLive.state?.snapshot?.failedStops?.any { it.code == "25141" } == true }
        app.graph.brokenStops.clear("25141")
        TestClock.set(FallbackUiTest.REPLAY_START, FallbackUiTest.SPEED)
        val service = start()
        compose.waitReal(timeout) { WatchStateHolder.state.value?.fallback?.downstream?.code == "31492" && WatchStateHolder.state.value?.buses?.isNotEmpty() == true }
        compose.waitReal(timeout) { hasNode(hasTestTag("hero-PLENTY") or hasTestTag("hero-GET_READY") or hasTestTag("hero-NOW")) }
        assertTrue(heroText(), heroText().contains("הערכה"))
        scrollTop()
        shot("15_fallback_hero")
        note("fallback: ${heroText()}")
        scrollTo(hasTestTag("diagram-user-stop"))
        waitFor("התחנה לא מדווחת ב-curlbus · זמנים משוערים")
        shot("16_fallback_diagram")
        val est = hasText("דק׳ (הערכה)", substring = true) and hasClickAction()
        scrollTo(est)
        compose.onAllNodes(est).onFirst().performClick()
        waitFor("משוער לפי תחנה סמוכה")
        shot("17_fallback_row_expanded")
        stopAndAssertQuiet(service)
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h914dp-port-420dpi")
class WatchV06LightUiTest : WatchV06Screens("light")

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h914dp-port-night-420dpi")
class WatchV06DarkUiTest : WatchV06Screens("dark")

/** All texts on the hero card ("label | big | sub"). */
fun heroTexts(compose: ComposeTestRule): String = listOf("PLENTY", "GET_READY", "NOW", "NONE").flatMap { t ->
    compose.onAllNodes(hasAnyAncestor(hasTestTag("hero-$t")), useUnmergedTree = true).fetchSemanticsNodes()
        .flatMap { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
}.joinToString(" | ")

/**
 * The hero's countdown runs on the local clock between polls: with polls 10 min apart and the clock at 60×, the minutes
 * go down on screen while not a single request is made.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h914dp-port-420dpi")
class WatchCountdownUiTest {
    companion object {
        @JvmStatic @BeforeClass fun config() { TestConfig.reset(); TestConfig.pollInterval = 10.minutes; TestConfig.routeLiveInterval = 10.minutes }
        @JvmStatic @AfterClass fun done() { TestConfig.reset() }
    }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()
    private val c get() = app.graph.controller
    private val timeout = 20_000L
    private fun has(t: String) = compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun click(t: String, sub: Boolean = true) {
        compose.waitReal(timeout) { compose.onAllNodes(hasText(t, substring = sub)).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(t, substring = sub)).onFirst().performClick()
    }
    private fun heroMinutes(): Int? = Regex("""(\d+) דק׳""").find(heroTexts(compose).substringBefore(" | קו"))?.groupValues?.get(1)?.toInt()

    private var running: ServiceController<WatchService>? = null
    @After fun tearDown() { runCatching { running?.destroy() }; running = null; TestClock.reset() }
    @Before fun setUp() { WatchStateHolder.update(null); TestHttp.clear(); shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS) }

    @Test
    fun heroCountsDownBetweenPollsWithoutRequests() {
        click("חיפוש קו")
        compose.onNode(hasSetTextAction()).performTextReplacement("194")
        click("חפש", sub = false)
        click("ת. רכבת תל אביב- סבידור/רציפים A", sub = false)
        compose.waitReal(timeout) { has("התחנה הקרובה אליך במסלול") }
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("כשהאוטובוס מגיע לתחנה שאבחר", substring = true))
        click("כשהאוטובוס מגיע לתחנה שאבחר")
        val row = hasText("שלמה לוי/שער יפו", substring = true) and hasText("36926", substring = true) and hasClickAction()
        compose.waitReal(timeout) { runCatching { compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(row) }.isSuccess }
        compose.onAllNodes(row).onLast().performClick()
        compose.waitReal(timeout) { c.targetStopCode == "36926" }
        click("התחל מעקב")
        val service = Robolectric.buildService(WatchService::class.java, shadowOf(app).nextStartedService).create().startCommand(0, 1)
        running = service
        compose.waitReal(timeout) { WatchStateHolder.state.value?.lastSuccessAt != null }
        compose.waitReal(timeout) { c.routeLive.state?.snapshot != null && heroMinutes() != null }
        compose.passTime(1_500)
        val polls = WatchStateHolder.state.value!!.polls
        val requests = TestHttp.count()
        val m0 = heroMinutes()!!
        val text0 = heroTexts(compose)
        TestClock.setSpeed(60.0)
        compose.passTime(5_000) // ≈ 5 fake minutes
        compose.mainClock.advanceTimeBy(2_000) // the screen's 1 s ticker runs on the (test) frame clock
        compose.passTime(300)
        val m1 = heroMinutes()!!
        val text1 = heroTexts(compose)
        File("../docs/screenshots/v0.6").mkdirs()
        File("../docs/screenshots/v0.6/countdown_log.txt").writeText(
            "before: $text0\nafter ~5 fake min: $text1\npolls $polls → ${WatchStateHolder.state.value!!.polls}, requests $requests → ${TestHttp.count()}\n")
        assertTrue("countdown went down: $m0 → $m1", m0 - m1 >= 3)
        assertEquals("no poll in between", polls, WatchStateHolder.state.value!!.polls)
        assertEquals("no request in between", requests, TestHttp.count())
        TestClock.setSpeed(1.0)
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)
        click("עצור מעקב")
        service.withIntent(shadowOf(app).nextStartedService).startCommand(0, 2)
        compose.waitReal(timeout) { WatchStateHolder.state.value?.phase == WatchPhase.STOPPED }
    }
}
