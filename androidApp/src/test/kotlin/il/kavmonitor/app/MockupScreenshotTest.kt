package il.kavmonitor.app

import androidx.compose.runtime.Composable
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import il.kavmonitor.shared.data.ApiJson
import il.kavmonitor.shared.data.CurlbusParser
import il.kavmonitor.shared.data.GtfsRideStopDto
import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.data.MultiStopFetcher
import il.kavmonitor.shared.data.StopServerException
import il.kavmonitor.shared.data.StrideStaticDataSource
import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.model.StopSnapshot
import il.kavmonitor.shared.route.RouteLiveFetcher
import il.kavmonitor.shared.route.RouteLiveRequest
import il.kavmonitor.shared.route.RouteLiveSnapshot
import il.kavmonitor.shared.ui.mockup.CompactBusRows
import il.kavmonitor.shared.ui.mockup.HeroStates
import il.kavmonitor.shared.ui.mockup.HeroModel
import il.kavmonitor.shared.ui.mockup.HeroTone
import il.kavmonitor.shared.ui.mockup.LineDiagram
import il.kavmonitor.shared.ui.mockup.MockupFrame
import il.kavmonitor.shared.ui.mockup.MockupModels
import il.kavmonitor.shared.ui.mockup.MockupTheme
import il.kavmonitor.shared.ui.mockup.WatchScreenMockup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.time.Instant

/**
 * v0.6 UI MOCKUP renders (not the app's screens): hero, line diagram, compact bus rows and the combined watch screen,
 * fed by the REAL recorded full-route answer of line 194 at 2026-10-06 19:34:47 (one multi-stop request for its 24
 * working stops), user's stop 13 "שלמה לוי/שער יפו" (36926). Phone size, light + dark, Hebrew RTL.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = TestApp::class, qualifiers = "w411dp-h914dp-port-420dpi")
class MockupScreenshotTest {
    private val outDir = File("../docs/screenshots/mockup-v0.6").apply { mkdirs() }
    private val broken = setOf("20876", "20725", "25141", "27117")
    private val now: Instant = Instant.parse("2026-10-06T19:34:47+03:00")
    private val stops: List<RouteStop> = StrideStaticDataSource.mapRideStops(
        ApiJson.decodeFromString(ListSerializer(GtfsRideStopDto.serializer()), fixture("stride_gtfs_ride_stops_37121_2026-10-05.json")),
    )
    private val userIndex = stops.indexOfFirst { it.code == "36926" }
    private val user = stops[userIndex]

    /** The recorded full-route answer; a request containing a broken stop → HTTP 500 (as the real server). */
    private val live = object : LiveDataSource {
        val full = CurlbusParser.parse(fixture("full_route_194/full_193447.json"), "full", now)
        override suspend fun stopVisits(stopCode: String): StopSnapshot =
            if (stopCode in broken) throw StopServerException(stopCode, 500) else full.slice(stopCode)
        override suspend fun multiStopVisits(stopCodes: List<String>): StopSnapshot {
            if (stopCodes.any { it in broken }) throw StopServerException(stopCodes.joinToString("+"), 500)
            return StopSnapshot.merge(stopCodes.joinToString("+"), stopCodes.map { full.slice(it) })
        }
    }
    private val snap: RouteLiveSnapshot = runBlocking {
        RouteLiveFetcher(live, RouteLiveRequest(37121, "194", 3, stops, user.code), clock = object : kotlin.time.Clock { override fun now() = now },
            multi = MultiStopFetcher(live, null)).refresh()
    }
    private val firstEta = MockupModels.minutesUntil(MockupModels.approaching(snap, user, now).first().second, now)
    private fun hero(walk: Int) = MockupModels.hero(snap, "194", user, walk, now)
    /** The 4 states from the same real data, by walking time: 7 min to spare, 2 min, leave now, too far. */
    private val heroes: List<HeroModel> = listOf(hero(firstEta - 7), hero(firstEta - 2), hero(firstEta), hero(firstEta + 60))
    private val diagram = MockupModels.diagram(stops, snap, userIndex, now)
    private val rows = MockupModels.busRows(stops, snap, userIndex, now)
    private val title = "קו 194 · אגד → ת. רכבת הראשונים"
    private val subtitle = "מגיע ל־13. שלמה לוי/שער יפו · עודכן 19:34:47 · נתונים מ־24 תחנות"

    private fun render(name: String, dark: Boolean, content: @Composable () -> Unit) {
        val path = File(outDir, "${name}_${if (dark) "dark" else "light"}.png").path
        captureRoboImage(path) { MockupTheme(dark) { content() } }
    }

    @Test
    fun renderMockups() {
        assertEquals(listOf(HeroTone.PLENTY, HeroTone.GET_READY, HeroTone.LEAVE_NOW, HeroTone.NONE), heroes.map { it.tone })
        File(outDir, "mockup_data.txt").writeText(
            "line 194 (37121), recorded full-route answer 2026-10-06 19:34:47, user stop #${userIndex + 1} ${user.code} ${user.name}\n" +
                "first bus at the user's stop in $firstEta min; hero walk minutes: ${firstEta - 7}, ${firstEta - 2}, $firstEta, ${firstEta + 60}\n" +
                "heroes:\n" + heroes.joinToString("\n") + "\n\ndiagram:\n" + diagram.joinToString("\n") + "\n\nrows:\n" + rows.joinToString("\n") + "\n",
        )
        for (dark in listOf(false, true)) {
            render("01_hero_states", dark) {
                MockupFrame("רעיון 1 · כרטיס עליון: מתי לצאת (4 מצבים)") {
                    HeroStates(heroes)
                }
            }
            render("02_line_diagram", dark) {
                MockupFrame("רעיון 2 · תרשים הקו (אוטובוסים לפי GPS)") { LineDiagram(diagram) }
            }
            render("03_bus_rows", dark) {
                MockupFrame("רעיון 3 · שורות אוטובוס (השנייה פתוחה)") { CompactBusRows(rows, initiallyExpanded = rows.getOrNull(1)?.key ?: rows.first().key) }
            }
            render("04_watch_screen_combined", dark) {
                WatchScreenMockup(title, subtitle, heroes[0], diagram, rows, expanded = null)
            }
        }
    }

    /** The whole combined screen (taller than a phone, to show everything below the fold). */
    @Test
    @Config(qualifiers = "w411dp-h1900dp-port-420dpi")
    fun renderCombinedFullHeight() {
        for (dark in listOf(false, true)) render("05_watch_screen_combined_full", dark) {
            WatchScreenMockup(title, subtitle, heroes[1], diagram, rows, expanded = rows.first().key, scroll = false)
        }
    }
}
