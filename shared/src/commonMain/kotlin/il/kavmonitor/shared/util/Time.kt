package il.kavmonitor.shared.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlin.time.Clock
import kotlin.time.Instant

val IsraelTz: TimeZone = TimeZone.of("Asia/Jerusalem")

fun Instant.israelDate(): LocalDate = toLocalDateTime(IsraelTz).date

fun todayInIsrael(clock: Clock = Clock.System): LocalDate = clock.now().israelDate()

fun LocalDate.minusDays(n: Int): LocalDate = this.minus(DatePeriod(days = n))

/** "HH:mm" (or "HH:mm:ss") in Israel time. */
fun Instant.israelClock(withSeconds: Boolean = false): String {
    val t = toLocalDateTime(IsraelTz)
    fun p(i: Int) = i.toString().padStart(2, '0')
    return if (withSeconds) "${p(t.hour)}:${p(t.minute)}:${p(t.second)}" else "${p(t.hour)}:${p(t.minute)}"
}

/**
 * Parses the timestamp formats seen in the APIs:
 * curlbus: "2026-10-06 10:02:00+03:00" (space separator) and "2026-10-06T09:49:22+03:00";
 * Stride: "2026-10-05T06:32:00+00:00". Returns null on anything unparseable.
 */
fun parseApiInstant(raw: String?): Instant? {
    if (raw.isNullOrBlank()) return null
    val s = raw.trim().replace(' ', 'T')
    return try {
        Instant.parse(s)
    } catch (_: Exception) {
        null
    }
}
