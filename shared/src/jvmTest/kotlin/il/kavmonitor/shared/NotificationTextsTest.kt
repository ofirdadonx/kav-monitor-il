package il.kavmonitor.shared

import il.kavmonitor.shared.data.ErrorKind
import il.kavmonitor.shared.data.FetchError
import il.kavmonitor.shared.model.LeavePlan
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.notify.NotifText
import il.kavmonitor.shared.notify.NotificationTexts
import il.kavmonitor.shared.notify.NotificationTexts.shortStop
import il.kavmonitor.shared.trigger.LeaveStatus
import il.kavmonitor.shared.trigger.TriggerEvent
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.watch.BusView
import il.kavmonitor.shared.watch.StopFallback
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** v0.4: notification texts are short, readable at a glance, and never show raw codes in the collapsed view. */
class NotificationTextsTest {
    private val now = ilTime("17:16:20")
    private val stop25141 = stops194().first { it.code == "25141" }

    private fun bus(v: String, eta: String, sched: String = "17:00:00", leaveAt: String? = null, status: LeaveStatus? = null, alerted: Boolean = false) =
        BusView(key = "trip-$v", vehicleRef = v, tripId = "58530$v", eta = ilTime(eta), scheduledDeparture = ilTime(sched),
            recordedAt = ilTime("17:15:51"), hasFreshGps = true, distanceToStopMeters = 900.0, alerted = alerted,
            leaveAt = leaveAt?.let(::ilTime), leaveStatus = status)

    private fun state(spec: il.kavmonitor.shared.model.WatchSpec, vararg buses: BusView, err: FetchError? = null, fallback: Boolean = false) =
        WatchState(spec, WatchPhase.RUNNING, ilTime("16:30:00"), ilTime("18:30:00"), lastSuccessAt = ilTime("17:15:58"),
            lastError = err, buses = buses.toList(), busesSeen = buses.size,
            fallback = if (fallback) StopFallback.plan(spec.trigger, stops194(), spec.stop) { it in setOf("25141", "27117") } else null)

    private fun ev(reason: TriggerReason, eta: String? = "17:30:00", live: Boolean = true) = TriggerEvent(
        key = "trip-7692269", vehicleRef = "7692269", tripId = "585301551", reason = reason, at = ilTime("17:16:20"),
        scheduledDeparture = ilTime("16:58:00"), eta = eta?.let(::ilTime), distanceMeters = 420.0, live = live, leadMinutes = 7)

    private val leaveSpec = spec194(TriggerType.LEAVE_FOR_STOP, STOP_36926).copy(leave = LeavePlan(6, 1))
    private val arriveSpec = spec194(TriggerType.ARRIVES_AT_STOP, STOP_36926)
    private val fallbackSpec = spec194(TriggerType.ARRIVES_AT_STOP, stop25141)
    private val departSpec = spec194(TriggerType.DEPARTS_ORIGIN)

    /** All cases: name → text (the examples in docs/screenshots/v0.4/notifications.txt). */
    private val cases: List<Pair<String, NotifText>> by lazy { listOf(
        "ongoing · departs · first poll pending" to NotificationTexts.ongoing(departSpec, null, now),
        "ongoing · departs" to NotificationTexts.ongoing(departSpec, state(departSpec, bus("7628169", "17:24:00", sched = "17:24:00")), now),
        "ongoing · arrives" to NotificationTexts.ongoing(arriveSpec, state(arriveSpec, bus("7692269", "17:22:00")), now),
        "ongoing · arrives · broken stop (fallback)" to NotificationTexts.ongoing(fallbackSpec, state(fallbackSpec, bus("23399602", "17:40:00"), fallback = true), now),
        "ongoing · server error on the stop" to NotificationTexts.ongoing(fallbackSpec, state(fallbackSpec, err = FetchError(ErrorKind.SERVER, "25141")), now),
        "ongoing · no internet" to NotificationTexts.ongoing(arriveSpec, state(arriveSpec, err = FetchError(ErrorKind.NETWORK)), now),
        "ongoing · time to leave · next" to NotificationTexts.ongoing(leaveSpec, state(leaveSpec, bus("7692269", "17:31:00", leaveAt = "17:24:00", status = LeaveStatus.WAIT)), now),
        "ongoing · time to leave · now" to NotificationTexts.ongoing(leaveSpec, state(leaveSpec, bus("7692269", "17:23:00", leaveAt = "17:16:00", status = LeaveStatus.LEAVE_NOW)), now),
        "ongoing · time to leave · none catchable" to NotificationTexts.ongoing(leaveSpec, state(leaveSpec, bus("7692269", "17:18:00", leaveAt = "17:11:00", status = LeaveStatus.NOT_CATCHABLE)), now),
        "alert · departed origin" to NotificationTexts.alert(departSpec, ev(TriggerReason.LEFT_ORIGIN_DROPPED)),
        "alert · departed origin (schedule only)" to NotificationTexts.alert(departSpec, ev(TriggerReason.LEFT_ORIGIN_SCHEDULED)),
        "alert · arriving (ETA)" to NotificationTexts.alert(arriveSpec, ev(TriggerReason.AT_STOP_ETA)),
        "alert · at the stop (GPS)" to NotificationTexts.alert(arriveSpec, ev(TriggerReason.AT_STOP_GPS)),
        "alert · reached/passed (dropped)" to NotificationTexts.alert(arriveSpec, ev(TriggerReason.DROPPED_AT_STOP)),
        "alert · arriving, estimated (broken stop)" to NotificationTexts.alert(fallbackSpec, ev(TriggerReason.AT_STOP_ETA), estimated = true),
        "alert · time to leave" to NotificationTexts.alert(leaveSpec, ev(TriggerReason.LEAVE_NOW), walkMinutes = 6),
        "alert · time to leave (schedule ETA)" to NotificationTexts.alert(leaveSpec, ev(TriggerReason.LEAVE_NOW, live = false), walkMinutes = 6),
        "finished" to NotificationTexts.finished(departSpec, 60),
    ) }

    @Test
    fun shortStopNames() {
        assertEquals("רכבת תל אביב- סבידור/…", shortStop("ת. רכבת תל אביב- סבידור/רציפים A"))
        assertEquals("קרליבך/דרך מנחם בגין A", shortStop("ת.רק\"ל קרליבך/דרך מנחם בגין A"))
        assertEquals("שלמה לוי/שער יפו", shortStop("שלמה לוי/שער יפו"))
        assertEquals("רכבת הראשונים", shortStop("ת. רכבת הראשונים, ראשון לציון"))
        assertEquals("רכבת השלום", shortStop("תחנת רכבת השלום"))
        assertTrue(shortStop("דרך מנחם בגין/נחמני פינת רחוב ארוך מאוד").length <= NotificationTexts.STOP_MAX)
    }

    @Test
    fun collapsedTextsAreShortAndFreeOfCodes() {
        val raw = Regex("""\d{4,}""") // vehicle numbers, trip ids, stop codes
        val seconds = Regex("""\d\d:\d\d:\d\d""")
        for ((name, t) in cases) {
            assertTrue(NotificationTexts.visibleLength(t.title) <= NotificationTexts.MAX_TITLE, "$name: title too long: ${t.title}")
            assertTrue(NotificationTexts.visibleLength(t.body) <= NotificationTexts.MAX_BODY, "$name: body too long: ${t.body}")
            for (s in listOf(t.title, t.body)) {
                assertFalse(raw.containsMatchIn(s), "$name: raw code in collapsed text: $s")
                assertFalse(seconds.containsMatchIn(s), "$name: seconds in collapsed text: $s")
                assertFalse("\n" in s, "$name: one line only")
            }
        }
        // details are still there when expanded
        val leave = cases.toMap()["alert · time to leave"]!!
        assertTrue(leave.big!!.contains("7692269"))
        assertEquals("צא עכשיו · \u2068194\u2069", leave.title)
        assertEquals("מגיע ל\u2068שלמה לוי/שער יפו\u2069 ב-17:30 · 6 דק׳ הליכה", leave.body)
        assertEquals("יציאה הבאה ב-17:24 (בעוד 8 דק׳) · עד 18:30", cases.toMap()["ongoing · time to leave · next"]!!.body)
        assertEquals("מעקב \u2068194\u2069 · זמן לצאת", cases.toMap()["ongoing · time to leave · next"]!!.title)
        assertTrue(cases.toMap()["ongoing · server error on the stop"]!!.big!!.contains("25141"))
        assertTrue(cases.toMap()["alert · arriving, estimated (broken stop)"]!!.body.endsWith("(הערכה)"))
    }

    /** Writes the before/after examples (before = v0.3.1 texts captured by the UI tests). */
    @Test
    fun writeExamples() {
        val before = """
            |ongoing · departs        title: קו 194 · יציאה מת. רכבת תל אביב- סבידור/רציפים A
            |                         text:  עודכן 18:23:33 · נראו 3 · עד 19:23
            |ongoing · time to leave  title: קו 194 · זמן לצאת לשלמה לוי/שער יפו
            |                         text:  צאו ב-18:28 (בעוד 5 דק׳) · האוטובוס ב-18:49 (חי)
            |ongoing · error          title: קו 194 · הגעה לדרך מנחם בגין/נחמני
            |                         text:  ממתין לעדכון ראשון… · עד 18:58
            |                         big:   שירות הנתונים לא מחזיר נתונים לתחנה 25141 · ממשיך לנסות … הערכה לפי תחנה סמוכה (התחנה לא מדווחת)
            |alert · departed         title: קו 194 יצא מת. רכבת תל אביב- סבידור/רציפים A!
            |                         text:  רכב 7628169 · יציאה מתוכננת מהמוצא 18:24
            |alert · arriving         title: קו 194 מגיע לדרך מנחם בגין/נחמני!
            |                         text:  רכב 7687869 · יציאה מתוכננת 17:40 · זמן לצאת לתחנה
            |alert · time to leave    title: צא עכשיו — 194 מגיע לשלמה לוי/שער יפו ב-18:49
            |                         text:  רכב 7692269 · הליכה 25 דק׳ + מרווח 1 דק׳ · זמן הגעה חי (GPS)
            |finished                 title: המעקב אחרי קו 194 הסתיים
            |                         text:  עבר זמן המעקב (60 דק׳)
        """.trimMargin()
        val strip = { s: String -> s.replace("\u2068", "").replace("\u2069", "") }
        val after = cases.joinToString("\n\n") { (name, t) ->
            "$name\n  title: ${strip(t.title)}\n  text:  ${strip(t.body)}" + (t.big?.let { "\n  expanded:\n    " + strip(it).replace("\n", "\n    ") } ?: "")
        }
        val out = File("../docs/screenshots/v0.4/notifications.txt")
        out.parentFile.mkdirs()
        out.writeText(
            "Notification texts, before (v0.3.1, captured by the UI tests) and after (v0.4, NotificationTexts).\n" +
                "Collapsed: title ≤ ${NotificationTexts.MAX_TITLE} chars, text ≤ ${NotificationTexts.MAX_BODY} chars, one line, no vehicle numbers / trip ids /\n" +
                "stop codes / seconds (tested in NotificationTextsTest). Unicode isolate marks around names are not shown here.\n" +
                "Ongoing actions: עצור · +15 דק׳ (setOnlyAlertOnce; time to leave: countdown to the leave time in the header).\n\n" +
                "=== BEFORE (v0.3.1) ===\n$before\n\n=== AFTER (v0.4) ===\n$after\n",
        )
    }
}
