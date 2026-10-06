package il.kavmonitor.shared

import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import kotlin.time.Clock
import kotlin.time.Instant

fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

fun ilTime(hhmmss: String, date: String = "2026-10-06"): Instant = Instant.parse("${date}T$hhmmss+03:00")

class MutableClock(var now: Instant) : Clock {
    override fun now(): Instant = now
}

// Real coordinates from Stride (line 194 Egged, line_ref 37121).
val ORIGIN_20001 = RouteStop(1, "20001", "ת. רכבת תל אביב- סבידור/רציפים A", "תל אביב יפו", 32.083157, 34.796089)
val STOP_21472 = RouteStop(5, "21472", "קניון עזריאלי/דרך מנחם בגין", "תל אביב יפו", 32.074584, 34.790607)
val STOP_36926 = RouteStop(13, "36926", "שלמה לוי/שער יפו", "ראשון לציון", 31.962154, 34.827882)

fun spec194(trigger: TriggerType, stop: RouteStop = ORIGIN_20001, minutes: Int = 60) = WatchSpec(
    lineRef = 37121, lineName = "194", operatorRef = 3, agencyName = "אגד",
    directionLabel = "ת. רכבת הראשונים-ראשון לציון", trigger = trigger, stop = stop, origin = ORIGIN_20001,
    durationMinutes = minutes,
)
