package il.kavmonitor.shared.notify

import il.kavmonitor.shared.data.ErrorKind
import il.kavmonitor.shared.data.FetchError
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.trigger.LeaveStatus
import il.kavmonitor.shared.trigger.TriggerEvent
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.util.israelClock
import il.kavmonitor.shared.watch.BusView
import il.kavmonitor.shared.watch.WatchState
import kotlin.math.ceil
import kotlin.time.Instant

/**
 * Collapsed notification: [title] = the one key fact, [body] = one short line to act on. [big] = the expanded view
 * (secondary details: rows, vehicle, codes, last update). Collapsed texts never carry vehicle numbers, trip ids, stop
 * codes or seconds.
 */
data class NotifText(val title: String, val body: String, val big: String? = null)

/**
 * Text of every notification (ongoing watch, the three alert types, errors, fallback, finished) — plain Kotlin so it is
 * unit-tested on the JVM and shared with the in-app status line. Hebrew only (the app is Hebrew-only).
 * Stop / line names are wrapped in Unicode isolates so "רציפים A" or "194" can't reorder the surrounding Hebrew.
 */
object NotificationTexts {
    const val MAX_TITLE = 40
    const val MAX_BODY = 60
    const val STOP_MAX = 22

    private const val FSI = '\u2068'
    private const val PDI = '\u2069'
    fun iso(s: String) = "$FSI$s$PDI"

    /** Visible length (without the isolate marks). */
    fun visibleLength(s: String) = s.count { it != FSI && it != PDI }

    private val prefixes = listOf("ת.רק\"ל ", "ת.רק''ל ", "ת. רק\"ל ", "תחנת ", "ת. ", "ת.")

    /**
     * "ת. רכבת תל אביב- סבידור/רציפים A" → "רכבת תל אביב- סבידור/…": drops "ת." / "תחנת" / "ת.רק"ל" prefixes and a
     * ", city" suffix, then cuts long names at a word boundary with an ellipsis.
     */
    fun shortStop(name: String, max: Int = STOP_MAX): String {
        var s = name.trim().substringBefore(", ").trim()
        prefixes.firstOrNull { s.startsWith(it) }?.let { s = s.removePrefix(it).trim() }
        s = s.replace(Regex("\\s+"), " ")
        if (s.length <= max) return s
        val slash = s.indexOf('/')
        if (slash in 1..(max - 2)) return s.substring(0, slash + 1) + "…"
        val cut = s.substring(0, max - 1)
        val i = cut.lastIndexOfAny(charArrayOf(' ', '/', '-'))
        val head = if (i >= max / 2) cut.substring(0, i + if (cut[i] == '/') 1 else 0) else cut
        return head.trimEnd(' ', '-') + "…"
    }

    private fun stop(name: String) = iso(shortStop(name))
    private fun line(spec: WatchSpec) = iso(spec.lineName)
    private fun t(i: Instant) = i.israelClock()
    private fun minsUntil(at: Instant, now: Instant) = ceil((at - now).inWholeSeconds / 60.0).toInt().coerceAtLeast(0)

    fun errorShort(e: FetchError): String = when (e.kind) {
        ErrorKind.SERVER -> "אין נתונים מהשרת"
        ErrorKind.NETWORK -> "אין חיבור לאינטרנט"
        ErrorKind.TIMEOUT -> "השרת לא עונה"
        ErrorKind.SOURCE -> "תקלה זמנית במקור הנתונים"
        ErrorKind.OTHER -> "שגיאה בקבלת הנתונים"
    }

    private fun errorDetail(e: FetchError): String = when {
        e.kind == ErrorKind.SERVER && e.stopCode != null -> "השרת (curlbus) לא מחזיר נתונים לתחנה ${e.stopCode}"
        else -> errorShort(e)
    }

    private fun triggerShort(spec: WatchSpec) = when (spec.trigger) {
        TriggerType.DEPARTS_ORIGIN -> "יציאה מהמוצא"
        TriggerType.ARRIVES_AT_STOP -> "הגעה ל" + stop(spec.stop.name)
        TriggerType.LEAVE_FOR_STOP -> "זמן לצאת"
    }

    private fun triggerRow(spec: WatchSpec) = when (spec.trigger) {
        TriggerType.DEPARTS_ORIGIN -> "יציאה מהמוצא"
        TriggerType.ARRIVES_AT_STOP -> "הגעה ל" + iso(spec.stop.name)
        TriggerType.LEAVE_FOR_STOP -> "זמן לצאת ל" + iso(spec.stop.name)
    }

    private fun rows(spec: WatchSpec) =
        "מוצא: ${iso(spec.origin.name)}\nיעד: ${iso(spec.directionLabel)}\nהתראה: ${triggerRow(spec)}"

    /** The next relevant bus for the collapsed line (null when there is none). */
    private fun nextLine(spec: WatchSpec, st: WatchState, now: Instant): String? {
        val fb = st.fallback != null && spec.trigger != TriggerType.DEPARTS_ORIGIN
        val est = if (fb) " (הערכה)" else ""
        val estIn = if (fb) ", הערכה" else ""
        return when (spec.trigger) {
            TriggerType.LEAVE_FOR_STOP -> {
                val b = st.nextLeaveBus ?: return "אין כרגע אוטובוס שאפשר להספיק"
                val leave = b.leaveAt ?: return null
                if (b.leaveStatus == LeaveStatus.LEAVE_NOW || leave <= now) "צא עכשיו · האוטובוס ב-${b.eta?.let(::t) ?: "—"}$est"
                else "יציאה הבאה ב-${t(leave)} (בעוד ${minsUntil(leave, now)} דק׳$estIn)"
            }
            TriggerType.DEPARTS_ORIGIN -> {
                val b = st.buses.filter { !it.alerted && it.scheduledDeparture != null }.minByOrNull { it.scheduledDeparture!! }
                    ?: return "אין כרגע אוטובוס בתחנת המוצא"
                "הבא יוצא ב-${t(b.scheduledDeparture!!)}"
            }
            TriggerType.ARRIVES_AT_STOP -> {
                val b = st.buses.filter { !it.alerted && it.eta != null && it.eta >= now - il.kavmonitor.shared.trigger.Polling.INTERVAL }
                    .minByOrNull { it.eta!! } ?: return "אין כרגע אוטובוס בדרך לתחנה"
                "הבא מגיע ב-${t(b.eta!!)} (בעוד ${minsUntil(b.eta!!, now)} דק׳$estIn)"
            }
        }
    }

    /** Ongoing watch notification. [state] null = just started. */
    fun ongoing(spec: WatchSpec, st: WatchState?, now: Instant): NotifText {
        val title = "מעקב ${line(spec)} · ${triggerShort(spec)}"
        val until = st?.endsAt?.let { " · עד ${t(it)}" } ?: ""
        val err = st?.lastError
        val body = when {
            st == null || (st.lastSuccessAt == null && err == null) -> "ממתין לעדכון ראשון$until"
            err != null -> "${errorShort(err)} · מנסה שוב$until"
            else -> (nextLine(spec, st, now) ?: "מעקב פעיל") + until
        }
        val big = buildString {
            append(rows(spec))
            if (st != null) {
                if (err != null) {
                    append('\n').append(errorDetail(err)).append(" · ממשיך לנסות")
                    st.lastSuccessAt?.let { append(" (עדכון אחרון שהצליח ${t(it)})") }
                } else if (st.lastSuccessAt != null) {
                    nextLine(spec, st, now)?.let { append('\n').append(it) }
                }
                st.fallback?.let { fb ->
                    val names = listOfNotNull(fb.upstream, fb.downstream).joinToString(" ו-") { stop(it.name) }
                    append('\n').append("הערכה: התחנה לא מדווחת, מחושב לפי $names")
                }
                st.lastSuccessAt?.let { append('\n').append("עודכן ${t(it)} · נראו ${st.busesSeen} · עד ${t(st.endsAt)}") }
            }
        }
        return NotifText(title, body, big)
    }

    /** One alert (departs origin / arrives at stop / time to leave). [estimated]: derived from neighbouring stops. */
    fun alert(spec: WatchSpec, e: TriggerEvent, estimated: Boolean = false, walkMinutes: Int? = null): NotifText {
        val est = if (estimated) " (הערכה)" else ""
        val (title, body) = when (e.reason) {
            TriggerReason.LEFT_ORIGIN_DROPPED, TriggerReason.LEFT_ORIGIN_GPS, TriggerReason.LEFT_ORIGIN_SCHEDULED ->
                "${line(spec)} יצא מהמוצא" to (
                    (if (e.reason == TriggerReason.LEFT_ORIGIN_SCHEDULED) "לפי הלו״ז: יציאה ב-" else "יצא ב-") +
                        t(e.at) + " מ" + stop(spec.origin.name) + est)
            TriggerReason.AT_STOP_GPS -> "${line(spec)} מגיע ל${stop(spec.stop.name)}" to "האוטובוס ליד התחנה עכשיו$est"
            TriggerReason.AT_STOP_ETA -> "${line(spec)} מגיע ל${stop(spec.stop.name)}" to "מגיע לתחנה ב-${e.eta?.let(::t) ?: t(e.at)}$est"
            TriggerReason.DROPPED_AT_STOP -> "${line(spec)} הגיע ל${stop(spec.stop.name)}" to "הגיע לתחנה או עבר אותה עכשיו$est"
            TriggerReason.LEAVE_NOW -> {
                val walk = walkMinutes ?: spec.leave?.walkMinutes
                "צא עכשיו · ${line(spec)}" to (
                    "מגיע ל${stop(spec.stop.name)} ב-${e.eta?.let(::t) ?: "—"}" + (walk?.let { " · $it דק׳ הליכה" } ?: "") +
                        (if (!e.live) " (לפי לו״ז)" else "") + est)
            }
        }
        val big = buildString {
            append(body)
            append('\n').append(rows(spec))
            e.vehicleRef?.let { append('\n').append("רכב ").append(it) }
            e.scheduledDeparture?.let { append(" · יציאה מתוכננת מהמוצא ${t(it)}") }
            if (e.reason == TriggerReason.LEAVE_NOW) {
                append('\n').append(if (e.live) "זמן הגעה חי (GPS)" else "זמן הגעה לפי הלו״ז")
                spec.leave?.bufferMinutes?.let { append(" · מרווח $it דק׳") }
            }
            if (estimated) append('\n').append("התחנה לא מדווחת ב-curlbus: חושב לפי תחנה סמוכה")
        }
        return NotifText(title, body, big)
    }

    fun finished(spec: WatchSpec, minutes: Int): NotifText =
        NotifText("מעקב ${line(spec)} הסתיים", "עבר זמן המעקב ($minutes דק׳)", null)

    /** Line for the in-app status card (same wording as the collapsed ongoing notification, without the end time). */
    fun statusLine(spec: WatchSpec, st: WatchState, now: Instant): String? =
        if (st.lastSuccessAt == null || st.lastError != null) null else nextLine(spec, st, now)
}
