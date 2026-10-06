package il.kavmonitor.app

import android.Manifest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import il.kavmonitor.shared.watch.WatchStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds

/**
 * The live route view refreshes only while its screen is shown and the app is in the foreground:
 * leaving the screen or backgrounding the app → zero route requests; returning resumes.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h914dp-port-420dpi")
class RouteLiveLifecycleTest {
    companion object {
        @JvmStatic @BeforeClass
        fun config() { TestConfig.reset(); TestConfig.routeLiveInterval = 400.milliseconds }
        const val ROUTE = "curlbus-route.test"
    }

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<TestApp>()

    @Before
    fun setUp() {
        WatchStateHolder.update(null)
        TestHttp.clear()
        shadowOf().grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun shadowOf() = org.robolectric.Shadows.shadowOf(app)
    private fun has(t: String) = compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun click(t: String, sub: Boolean = true) = compose.onAllNodes(hasText(t, substring = sub)).onFirst().performClick()

    private fun quiet(ms: Long = 3_000) {
        val before = TestHttp.count(ROUTE)
        compose.passTime(ms)
        assertEquals("no route-view request in ${ms}ms", before, TestHttp.count(ROUTE))
    }

    @Test
    fun refreshOnlyWhileScreenVisible() {
        click("מעקב חדש")
        compose.onNode(hasSetTextAction()).performTextInput("194")
        click("חפש", sub = false)
        compose.waitReal(15_000) { has("ת. רכבת תל אביב- סבידור/רציפים A") }
        click("ת. רכבת תל אביב- סבידור/רציפים A", sub = false)
        compose.waitReal(15_000) { has("נתונים מ-") }
        val c = app.graph.controller

        // refreshing every 400 ms while shown
        val n0 = TestHttp.count(ROUTE)
        compose.waitReal(10_000) { TestHttp.count(ROUTE) >= n0 + 10 }
        assertTrue(c.routeLive.isRefreshing)

        // app goes to background (Activity onStop) → no requests
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse(c.routeLive.isRefreshing)
        quiet()

        // foreground again → resumes
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        val n1 = TestHttp.count(ROUTE)
        compose.waitReal(10_000) { TestHttp.count(ROUTE) > n1 }

        // leave the screen (back to the direction list, then home) → no requests
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertFalse(c.routeLive.isRefreshing)
        quiet()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        quiet()
    }
}
