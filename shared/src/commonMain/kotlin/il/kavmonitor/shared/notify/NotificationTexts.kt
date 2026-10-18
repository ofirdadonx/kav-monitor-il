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
 * Collapsed notification: [title] is the one fact to act on, [body] is one short line that does not repeat it.
 * [big] is the expanded view. Collapsed texts never carry vehicle numbers, trip ids, stop codes or seconds.
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

    private fun triggerRow(spec: WatchSpec) = when (spec.trigger) {
        TriggerType.DEPARTS_ORIGIN -> "יציאה מהמוצא"
        TriggerType.ARRIVES_AT_STOP -> "הגעה ל" + iso(spec.stop.name)
        TriggerType.LEAVE_FOR_STOP -> "זמן לצאת ל" + iso(spec.stop.name)
    }

    private fun rows(spec: WatchSpec) =
        "מוצא: ${iso(spec.origin.name)}\nיעד: ${iso(spec.directionLabel)}\nהתראה: ${triggerRow(spec)}"

    private fun estSuffix(estimated: Boolean) = if (estimated) " · הערכה" else ""

    /** Ongoing watch notification. [state] null = just started. End time stays in the expanded text. */
    fun ongoing(spec: WatchSpec, st: WatchState?, now: Instant): NotifText {
        val err = st?.lastError
        val fb = st?.fallback != null && spec.trigger != TriggerType.DEPARTS_ORIGIN
        val (title, body) = when {
            st == null || (st.lastSuccessAt == null && err == null) -> "מעקב ${line(spec)}" to "ממתין לעדכון"
            err != null -> "מעקב ${line(spec)}" to errorShort(err)
            else -> glance(spec, st, now, fb)
        }
        val big = buildString {
            append(rows(spec))
            if (st != null) {
                if (err != null) {
                    append('\n').append(errorDetail(err))
                    st.lastSuccessAt?.let { append(" · עדכון אחרון ${t(it)}") }
                }
                st.fallback?.let { plan ->
                    val names = listOfNotNull(plan.upstream, plan.downstream).joinToString(" ו-") { stop(it.name) }
                    append('\n').append("הערכה לפי $names")
                }
                st.endsAt.let { append('\n').append("עד ${t(it)}") }
                st.lastSuccessAt?.let { append(" · עודכן ${t(it)}") }
            }
        }
        return NotifText(title, body, big)
    }

    /** Title = the fact. Body = line or stop, not a repeat of the title. */
    private fun glance(spec: WatchSpec, st: WatchState, now: Instant, estimated: Boolean): Pair<String, String> {
        val est = estSuffix(estimated)
        return when (spec.trigger) {
            TriggerType.LEAVE_FOR_STOP -> {
                val b = st.nextLeaveBus
                val leave = b?.leaveAt
                when {
                    b == null || (leave == null && b.leaveStatus != LeaveStatus.LEAVE_NOW) ->
                        "אין אוטובוס להספיק" to "קו ${line(spec)}"
                    b.leaveStatus == LeaveStatus.LEAVE_NOW || (leave != null && leave <= now) ->
                        "צא עכשיו" to "קו ${line(spec)}$est"
                    else -> "צא ב-${t(leave!!)}" to "קו ${line(spec)} · בעוד ${minsUntil(leave, now)} דק׳$est"
                }
            }
            TriggerType.DEPARTS_ORIGIN -> {
                val b = st.buses.filter { !it.alerted && it.scheduledDeparture != null }.minByOrNull { it.scheduledDeparture!! }
                if (b == null) "אין אוטובוס" to "קו ${line(spec)}"
                else "${line(spec)} יוצא ב-${t(b.scheduledDeparture!!)}" to "מתחנת המוצא"
            }
            TriggerType.ARRIVES_AT_STOP -> {
                val b = st.buses.filter { !it.alerted && it.eta != null && it.eta >= now - il.kavmonitor.shared.trigger.Polling.INTERVAL }
                    .minByOrNull { it.eta!! }
                if (b == null) "אין אוטובוס" to "קו ${line(spec)}"
                else "${line(spec)} ב-${t(b.eta!!)}" to "${stop(spec.stop.name)} · בעוד ${minsUntil(b.eta, now)} דק׳$est"
            }
        }
    }

    /** One alert. [estimated]: derived from neighbouring stops. Walk minutes stay in the expanded text. */
    fun alert(spec: WatchSpec, e: TriggerEvent, estimated: Boolean = false, walkMinutes: Int? = null): NotifText {
        val est = estSuffix(estimated)
        val (title, body) = when (e.reason) {
            TriggerReason.LEFT_ORIGIN_DROPPED, TriggerReason.LEFT_ORIGIN_GPS ->
                "${line(spec)} יצא" to (t(e.at) + " · " + stop(spec.origin.name) + est)
            TriggerReason.LEFT_ORIGIN_SCHEDULED ->
                "${line(spec)} יצא" to ("לו״ז " + t(e.at) + " · " + stop(spec.origin.name) + est)
            TriggerReason.AT_STOP_GPS -> "${line(spec)} בתחנה" to (stop(spec.stop.name) + est)
            TriggerReason.AT_STOP_ETA -> "${line(spec)} מגיע" to (stop(spec.stop.name) + " · " + (e.eta?.let(::t) ?: t(e.at)) + est)
            TriggerReason.DROPPED_AT_STOP -> "${line(spec)} הגיע" to (stop(spec.stop.name) + est)
            TriggerReason.LEAVE_NOW -> "צא עכשיו" to ("${line(spec)} · " + stop(spec.stop.name) + (if (!e.live) " · לו״ז" else "") + est)
        }
        val big = buildString {
            append(rows(spec))
            e.vehicleRef?.let { append('\n').append("רכב ").append(it) }
            e.scheduledDeparture?.let { append(" · יציאה מתוכננת ${t(it)}") }
            if (e.reason == TriggerReason.LEAVE_NOW) {
                val walk = walkMinutes ?: spec.leave?.walkMinutes
                append('\n').append(if (e.live) "הגעה חיה" else "הגעה לפי לו״ז")
                walk?.let { append(" · הליכה $it דק׳") }
                spec.leave?.bufferMinutes?.let { append(" · מרווח $it דק׳") }
            }
            if (estimated) append('\n').append("הערכה לפי תחנה סמוכה")
        }
        return NotifText(title, body, big)
    }

    fun finished(spec: WatchSpec, minutes: Int): NotifText =
        NotifText("המעקב הסתיים", "קו ${line(spec)} · $minutes דק׳", null)

    /** One line for an in-app status, without the end time. */
    fun statusLine(spec: WatchSpec, st: WatchState, now: Instant): String? =
        if (st.lastSuccessAt == null || st.lastError != null) null else ongoing(spec, st, now).body
}
