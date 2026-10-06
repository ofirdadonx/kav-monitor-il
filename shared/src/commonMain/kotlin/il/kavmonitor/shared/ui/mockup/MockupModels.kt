package il.kavmonitor.shared.ui.mockup

import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.notify.NotificationTexts
import il.kavmonitor.shared.route.RouteBus
import il.kavmonitor.shared.route.RouteBusStatus
import il.kavmonitor.shared.route.RouteLiveSnapshot
import il.kavmonitor.shared.util.israelClock
import kotlin.math.floor
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/*
 * v0.6 UI MOCKUP (not wired into the app): view models for the hero, the line diagram and the compact bus rows,
 * computed from a real RouteLiveSnapshot (full-route mode). Pure functions, so the previews show real data.
 */

/** Hero colour: green = plenty of time, orange = get ready (≤ 3 min), red = leave now, gray = no catchable bus. */
enum class HeroTone { PLENTY, GET_READY, LEAVE_NOW, NONE }

data class HeroModel(val tone: HeroTone, val label: String?, val big: String, val sub: String)

enum class Freshness { FRESH, AGING, SCHEDULED }

data class DiagramBus(val key: String, val vehicle: String?, val etaToUserMinutes: Int?, val freshness: Freshness, val notStarted: Boolean, val etaEstimated: Boolean = false)

sealed interface DiagramRow {
    data class Stop(
        val index: Int, val name: String, val isUser: Boolean, val isOrigin: Boolean, val isDestination: Boolean,
        val busesAt: List<DiagramBus>,
    ) : DiagramRow
    /** Bus(es) between stop [afterIndex] and the next one. */
    data class Between(val afterIndex: Int, val buses: List<DiagramBus>) : DiagramRow
    data class Collapsed(val count: Int) : DiagramRow
}

data class BusRowModel(
    val key: String,
    val vehicle: String?,
    val etaMinutes: Int?,
    /** true = curlbus lists the bus only at stops before the user's (> ~30 min away): ETA there + scheduled run time. */
    val etaEstimated: Boolean,
    val progress: Float,
    val freshness: Freshness,
    val where: String,
    val scheduledStart: String?,
    val lastUpdate: String?,
    val ageSeconds: Long?,
    val distanceToUserMeters: Double?,
)

object MockupModels {
    const val AT_STOP_METERS = 80.0
    const val FRESH_SECONDS = 90L
    const val AGING_SECONDS = 180L
    const val GET_READY_MINUTES = 3

    fun minutesUntil(t: Instant, now: Instant): Int = floor((t - now).inWholeSeconds / 60.0).toInt()

    fun freshness(b: RouteBus, now: Instant): Freshness {
        if (b.status == RouteBusStatus.NOT_STARTED || b.lat == null || b.recordedAt == null) return Freshness.SCHEDULED
        val age = (now - b.recordedAt).inWholeSeconds
        return if (age <= FRESH_SECONDS) Freshness.FRESH else if (age <= AGING_SECONDS) Freshness.AGING else Freshness.SCHEDULED
    }

    fun short(name: String, max: Int = 22) = NotificationTexts.shortStop(name, max)

    /** Buses that still have to reach [user] (they have an ETA there), earliest first. */
    fun approaching(snap: RouteLiveSnapshot, user: RouteStop, now: Instant): List<Pair<RouteBus, Instant>> =
        snap.buses.mapNotNull { b -> b.etaByStop[user.code]?.takeIf { it >= now }?.let { b to it } }.sortedBy { it.second }

    /** Time to leave for the first bus that can still be caught with [walkMinutes] of walking. */
    fun hero(snap: RouteLiveSnapshot, lineName: String, user: RouteStop, walkMinutes: Int, now: Instant): HeroModel {
        val buses = approaching(snap, user, now)
        val stop = short(user.name)
        val catchable = buses.firstOrNull { minutesUntil(it.second, now) >= walkMinutes }
        if (catchable == null) {
            val next = buses.firstOrNull()
            val sub = if (next == null) "קו $lineName · $stop · אין אוטובוסים בדרך"
            else "קו $lineName · $stop · הבא בעוד ${minutesUntil(next.second, now)} דק׳ — לא תספיק ($walkMinutes דק׳ הליכה)"
            return HeroModel(HeroTone.NONE, null, "אין אוטובוס שאפשר להספיק", sub)
        }
        val eta = minutesUntil(catchable.second, now)
        val leave = eta - walkMinutes
        val sub = "קו $lineName · $stop · האוטובוס בעוד $eta דק׳ (${catchable.second.israelClock()})"
        return when {
            leave <= 0 -> HeroModel(HeroTone.LEAVE_NOW, null, "צא עכשיו", sub)
            leave <= GET_READY_MINUTES -> HeroModel(HeroTone.GET_READY, "צא בעוד", "$leave דק׳", sub)
            else -> HeroModel(HeroTone.PLENTY, "צא בעוד", "$leave דק׳", sub)
        }
    }

    /**
     * ETA at the user's stop: curlbus' own when it lists the bus there; otherwise (curlbus lists buses only ~30 min
     * ahead) the ETA at the furthest listed stop before it + the scheduled run time between the two (estimate); a bus
     * that has not started: scheduled start + run time. Null when the bus has passed the stop.
     */
    fun etaAtUser(b: RouteBus, stops: List<RouteStop>, userIndex: Int): Pair<Instant, Boolean>? {
        val user = stops[userIndex]
        b.etaByStop[user.code]?.let { return it to false }
        val userOff = user.offsetSeconds ?: return null
        val ref = stops.withIndex().filter { (i, s) -> i < userIndex && s.offsetSeconds != null && b.etaByStop[s.code] != null }.maxByOrNull { it.index }
        if (ref != null) return b.etaByStop.getValue(ref.value.code) + (userOff - ref.value.offsetSeconds!!).seconds to true
        if (b.status == RouteBusStatus.NOT_STARTED && b.scheduledStart != null) return b.scheduledStart + userOff.seconds to true
        return null
    }

    private fun diagramBus(b: RouteBus, stops: List<RouteStop>, userIndex: Int, now: Instant): DiagramBus {
        val eta = etaAtUser(b, stops, userIndex)
        return DiagramBus(
            b.key, b.vehicleRef, eta?.let { minutesUntil(it.first, now).coerceAtLeast(0) }, freshness(b, now),
            b.status == RouteBusStatus.NOT_STARTED, eta?.second == true,
        )
    }

    /**
     * The route as a vertical strip: every bus at a stop (within [AT_STOP_METERS]) or between two stops (by its GPS
     * progress); origin, destination, the user's stop and the stops next to buses always shown; other runs of ≥ 2 stops
     * collapsed ("… 6 תחנות"). Not-started buses wait at the origin; buses without GPS are left out (no position).
     */
    fun diagram(stops: List<RouteStop>, snap: RouteLiveSnapshot, userIndex: Int, now: Instant): List<DiagramRow> {
        val at = mutableMapOf<Int, MutableList<DiagramBus>>()
        val between = mutableMapOf<Int, MutableList<DiagramBus>>()
        for (b in snap.buses) {
            val p = b.position
            when {
                b.status == RouteBusStatus.NOT_STARTED -> at.getOrPut(0) { mutableListOf() } += diagramBus(b, stops, userIndex, now)
                p == null -> {}
                p.nearestStopMeters <= AT_STOP_METERS -> at.getOrPut(p.nearestStopIndex) { mutableListOf() } += diagramBus(b, stops, userIndex, now)
                else -> {
                    val after = (p.nextStopIndex - 1).coerceIn(0, stops.size - 2)
                    between.getOrPut(after) { mutableListOf() } += diagramBus(b, stops, userIndex, now)
                }
            }
        }
        val keep = sortedSetOf(0, stops.lastIndex, userIndex)
        at.keys.forEach { i -> keep += listOf(i - 1, i, i + 1).filter { it in stops.indices } }
        between.keys.forEach { i -> keep += listOf(i, i + 1) }
        val rows = mutableListOf<DiagramRow>()
        var hidden = 0
        fun flush(nextIndex: Int) {
            when (hidden) {
                0 -> {}
                1 -> stopRow(stops, nextIndex - 1, userIndex, at).let { rows += it }
                else -> rows += DiagramRow.Collapsed(hidden)
            }
            hidden = 0
        }
        for (i in stops.indices) {
            if (i !in keep) { hidden++; continue }
            flush(i)
            rows += stopRow(stops, i, userIndex, at)
            between[i]?.let { rows += DiagramRow.Between(i, it) }
        }
        return rows
    }

    private fun stopRow(stops: List<RouteStop>, i: Int, userIndex: Int, at: Map<Int, List<DiagramBus>>) = DiagramRow.Stop(
        i, short(stops[i].name), i == userIndex, i == 0, i == stops.lastIndex, at[i].orEmpty(),
    )

    /** Compact rows: buses that still have to reach the user's stop, earliest first. */
    fun busRows(stops: List<RouteStop>, snap: RouteLiveSnapshot, userIndex: Int, now: Instant): List<BusRowModel> {
        val user = stops[userIndex]
        val userAlong = user.distanceMeters
        return snap.buses.mapNotNull { b -> etaAtUser(b, stops, userIndex)?.takeIf { it.first >= now }?.let { b to it } }
            .sortedBy { it.second.first }.map { (b, e) ->
            val (eta, estimated) = e
            val along = b.position?.alongMeters
            BusRowModel(
                key = b.key,
                vehicle = b.vehicleRef,
                etaMinutes = minutesUntil(eta, now).coerceAtLeast(0),
                etaEstimated = estimated,
                progress = (b.position?.fraction ?: 0.0).toFloat(),
                freshness = freshness(b, now),
                where = when {
                    b.status == RouteBusStatus.NOT_STARTED -> "טרם יצא מהמוצא"
                    b.nextStop != null -> "לפני " + short(b.nextStop.name, 20)
                    else -> "בדרך"
                },
                scheduledStart = b.scheduledStart?.israelClock(),
                lastUpdate = b.recordedAt?.israelClock(true),
                ageSeconds = b.recordedAt?.let { (now - it).inWholeSeconds },
                distanceToUserMeters = if (along != null && userAlong != null) (userAlong - along).coerceAtLeast(0.0) else userAlong?.takeIf { b.status == RouteBusStatus.NOT_STARTED },
            )
        }
    }
}
