package il.kavmonitor.shared.follow

import il.kavmonitor.shared.location.DistanceLabel
import il.kavmonitor.shared.location.formatDistance
import il.kavmonitor.shared.notify.NotificationTexts
import il.kavmonitor.shared.util.israelClock
import kotlin.time.Instant

/** Lines of the followed-bus card (pure, unit-tested). Names are bidi-isolated; no trip ids. */
object FollowTexts {
    private fun iso(s: String) = NotificationTexts.iso(s)

    fun distance(meters: Double): String {
        val d = formatDistance(meters)
        return if (d.unit == DistanceLabel.Unit.METERS) "${d.value} מ׳" else "${d.value} ק״מ"
    }

    fun title(ref: BusRef): String =
        "עוקב אחרי ${iso(ref.lineName)}" + (ref.vehicleRef?.let { " · רכב ${iso(it)}" } ?: "")

    /** The one prominent line. */
    fun headline(info: FollowInfo, now: Instant): String {
        val stop = info.refStop?.let { iso(NotificationTexts.shortStop(it.name)) }
        return when (info.status) {
            FollowStatus.WAITING -> "ממתין לעדכון ראשון…"
            FollowStatus.GONE -> "האוטובוס כבר לא מופיע בנתונים החיים"
            FollowStatus.PASSED_STOP -> if (stop != null) "האוטובוס כבר עבר את $stop" else "האוטובוס עבר את התחנה"
            FollowStatus.NO_GPS -> "אין מיקום GPS לאוטובוס כרגע"
            FollowStatus.TRACKING -> {
                val m = info.minutesToStop
                when {
                    stop == null || m == null -> "האוטובוס בדרך" + (info.percent?.let { " · $it% מהמסלול" } ?: "")
                    m <= 0 -> "מגיע ל$stop עכשיו"
                    else -> "מגיע ל$stop בעוד $m דק׳" + if (info.etaFromCurlbus) "" else " (הערכה)"
                }
            }
        }
    }

    /** Detail lines under the headline. */
    fun details(info: FollowInfo, now: Instant): List<String> = buildList {
        when (info.status) {
            FollowStatus.GONE -> {
                add("סיים את הנסיעה, או שאבד ה-GPS שלו." + (info.lastSeenAt?.let { " נראה לאחרונה ב-${it.israelClock()}" } ?: ""))
                add("אפשר להפסיק לעקוב, או להמתין — אם יופיע שוב, הכרטיס יתעדכן.")
            }
            else -> {
                val stop = info.refStop?.let { iso(NotificationTexts.shortStop(it.name)) }
                if (info.etaAtStop != null && stop != null) {
                    add(if (info.etaFromCurlbus) "לפי curlbus: מגיע ל$stop ב-${info.etaAtStop.israelClock()}"
                        else "הערכה לפי המרחק: ב-${info.etaAtStop.israelClock()} (curlbus לא מסר זמן לתחנה זו)")
                }
                info.distanceToUserMeters?.let { add("${distance(it)} ממך · בקו אווירי") }
                val parts = buildList {
                    info.percent?.let { add("$it% מהמסלול") }
                    info.nextStop?.let { add("תחנה הבאה: ${iso(NotificationTexts.shortStop(it.name))}") }
                }
                if (parts.isNotEmpty()) add(parts.joinToString(" · "))
                info.gpsAge?.let { age ->
                    val s = age.inWholeSeconds.coerceAtLeast(0)
                    add("מיקום עודכן לפני " + if (s < 90) "$s שנ׳" else "${s / 60} דק׳")
                }
            }
        }
    }
}
