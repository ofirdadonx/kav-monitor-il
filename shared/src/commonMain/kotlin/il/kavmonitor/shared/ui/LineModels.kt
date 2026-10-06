package il.kavmonitor.shared.ui

import il.kavmonitor.shared.model.RouteStop
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.notify.NotificationTexts
import il.kavmonitor.shared.route.RouteBus
import il.kavmonitor.shared.route.RouteBusStatus
import il.kavmonitor.shared.route.RouteLiveSnapshot
import il.kavmonitor.shared.trigger.LeaveStatus
import il.kavmonitor.shared.trigger.TriggerConfig
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.util.israelClock
import il.kavmonitor.shared.watch.WatchPhase
import il.kavmonitor.shared.watch.WatchState
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/*
 * v0.6 watch screen: view models of the hero card, the line diagram and the compact bus rows. Pure Kotlin (unit-tested
 * on the JVM); everything is computed from the last poll + the local clock, so the per-second countdown between polls
 * never makes a request.
 */

/** Hero colour: green = plenty of time, orange = get ready (≤ 3 min), red = now, gray = nothing to catch / no data. */
enum class HeroTone { PLENTY, GET_READY, NOW, NONE }

/** [label] small line above [big] (null: [big] alone); [sub] one short line (line · stop · bus time). */
data class HeroModel(val tone: HeroTone, val label: String?, val big: String, val sub: String)

/** Freshness dot: green fresh GPS, orange aging GPS, gray scheduled only (no / old GPS, not started). */
enum class Freshness { FRESH, AGING, SCHEDULED }

object LineModels {
    const val GET_READY_MINUTES = 3
    /** "יצא" / "הגיע" stays on the hero this long after the alert. */
    val JUST_HAPPENED: Duration = 2.minutes
    val FRESH_MAX: Duration = 2.minutes
    val AGING_MAX: Duration = 5.minutes
    const val AT_STOP_METERS = 80.0

    private fun iso(s: String) = NotificationTexts.iso(s)
    private fun stop(name: String) = iso(NotificationTexts.shortStop(name))

    /** Whole minutes until [t], rounded up (the notification uses the same rounding), never negative. */
    fun minutesUntil(t: Instant, now: Instant): Int = ceil((t - now).inWholeSeconds / 60.0).toInt().coerceAtLeast(0)

    fun freshness(d: BusDetails, now: Instant): Freshness {
        val rec = d.recordedAt
        if (rec == null || d.phase == BusPhase.NOT_STARTED || d.phase == BusPhase.NO_GPS) return Freshness.SCHEDULED
        val age = now - rec
        return when {
            age <= FRESH_MAX -> Freshness.FRESH
            age <= AGING_MAX -> Freshness.AGING
            else -> Freshness.SCHEDULED
        }
    }

    // ------------------------------------------------------------------ hero

    /**
     * The hero for any watch, from the merged bus rows ([WatchRows.build]) and the local clock:
     * * time to leave: "צא בעוד N דק׳" (green > 3, orange ≤ 3) / "צא עכשיו" (red) / "אין אוטובוס שאפשר להספיק" (gray);
     * * departs origin: "האוטובוס יוצא בעוד N דק׳" / "יוצא עכשיו" / "יצא" (red, for 2 min after the alert) / gray;
     * * arrives at stop: "מגיע לתחנה בעוד N דק׳" / "מגיע עכשיו" / "הגיע לתחנה" (2 min after the alert) / gray.
     * A finished watch, no data yet or an error with no buses: gray.
     */
    fun hero(state: WatchState, rows: List<BusDetails>, now: Instant): HeroModel {
        val spec = state.spec
        val line = "קו ${iso(spec.lineName)}"
        val est = if (state.fallback != null && spec.trigger != TriggerType.DEPARTS_ORIGIN) " (הערכה)" else ""
        if (state.phase != WatchPhase.RUNNING) {
            return HeroModel(HeroTone.NONE, null, if (state.phase == WatchPhase.STOPPED) "המעקב נעצר" else "המעקב הסתיים", "$line · ${stop(spec.stop.name)}")
        }
        if (state.lastSuccessAt == null) {
            val err = state.lastError
            return HeroModel(
                HeroTone.NONE, null, if (err != null) NotificationTexts.errorShort(err) else "ממתין לעדכון ראשון",
                "$line · ${stop(spec.stop.name)}" + if (err != null) " · מנסה שוב" else "",
            )
        }
        return when (spec.trigger) {
            TriggerType.LEAVE_FOR_STOP -> leaveHero(spec, state, rows, now, line, est)
            TriggerType.DEPARTS_ORIGIN -> departsHero(spec, state, rows, now, line)
            TriggerType.ARRIVES_AT_STOP -> arrivesHero(spec, state, rows, now, line, est)
        }
    }

    private fun tone(minutes: Int) = when {
        minutes <= 0 -> HeroTone.NOW
        minutes <= GET_READY_MINUTES -> HeroTone.GET_READY
        else -> HeroTone.PLENTY
    }

    private fun leaveHero(spec: WatchSpec, state: WatchState, rows: List<BusDetails>, now: Instant, line: String, est: String): HeroModel {
        val grace = TriggerConfig().leaveGrace
        val s = stop(spec.stop.name)
        val candidates = rows.filter { d ->
            val at = d.leaveAt
            d.eta != null && at != null && at >= now - grace &&
                (d.leaveStatus == LeaveStatus.WAIT || d.leaveStatus == LeaveStatus.LEAVE_NOW || d.leaveStatus == LeaveStatus.ALERTED)
        }
        val b = candidates.minByOrNull { it.eta!! }
        if (b == null) {
            val walk = (state.leavePlan ?: spec.leave)?.walkMinutes
            val next = rows.filter { it.eta != null && it.eta >= now }.minByOrNull { it.eta!! }
            val sub = if (next == null) "$line · $s · אין כרגע אוטובוס בדרך לתחנה"
            else "$line · $s · הבא ב-${next.eta!!.israelClock()} — לא תספיק" + (walk?.let { " ($it דק׳ הליכה)" } ?: "")
            return HeroModel(HeroTone.NONE, null, "אין אוטובוס שאפשר להספיק", sub)
        }
        val m = minutesUntil(b.leaveAt!!, now)
        val busEst = if (b.etaEstimated) " (הערכה)" else est
        val sub = "$line · $s · האוטובוס ב-${b.eta!!.israelClock()} (בעוד ${minutesUntil(b.eta, now)} דק׳)$busEst"
        val t = if (b.leaveStatus == LeaveStatus.ALERTED || b.leaveStatus == LeaveStatus.LEAVE_NOW) HeroTone.NOW else tone(m)
        return if (t == HeroTone.NOW) HeroModel(t, null, "צא עכשיו", sub) else HeroModel(t, "צא בעוד", "$m דק׳", sub)
    }

    private fun departsHero(spec: WatchSpec, state: WatchState, rows: List<BusDetails>, now: Instant, line: String): HeroModel {
        val origin = stop(spec.origin.name)
        val left = state.alerts.lastOrNull {
            it.reason == TriggerReason.LEFT_ORIGIN_DROPPED || it.reason == TriggerReason.LEFT_ORIGIN_GPS || it.reason == TriggerReason.LEFT_ORIGIN_SCHEDULED
        }?.takeIf { now - it.at <= JUST_HAPPENED }
        if (left != null) {
            val how = if (left.reason == TriggerReason.LEFT_ORIGIN_SCHEDULED) "לפי הלו״ז: " else ""
            return HeroModel(HeroTone.NOW, null, "האוטובוס יצא", "$line · ${how}יצא ב-${left.at.israelClock()} מ$origin")
        }
        fun dep(d: BusDetails) = d.scheduledStart ?: d.eta
        val next = rows.filter { !it.alerted && dep(it) != null && it.phase != BusPhase.ACTIVE }.minByOrNull { dep(it)!! }
            ?: return HeroModel(HeroTone.NONE, null, "אין כרגע אוטובוס בתחנת המוצא", "$line · $origin")
        val at = dep(next)!!
        val m = minutesUntil(at, now)
        val sub = "$line · מ$origin · יציאה ב-${at.israelClock()}"
        val t = tone(m)
        return if (t == HeroTone.NOW) HeroModel(t, null, "יוצא עכשיו", sub) else HeroModel(t, "האוטובוס יוצא בעוד", "$m דק׳", sub)
    }

    private fun arrivesHero(spec: WatchSpec, state: WatchState, rows: List<BusDetails>, now: Instant, line: String, est: String): HeroModel {
        val s = stop(spec.stop.name)
        val next = rows.filter { it.eta != null && it.eta >= now - 1.minutes }.minByOrNull { it.eta!! }
        val arrived = state.alerts.lastOrNull { it.reason == TriggerReason.DROPPED_AT_STOP }?.takeIf { now - it.at <= JUST_HAPPENED }
        if (arrived != null && (next == null || minutesUntil(next.eta!!, now) > GET_READY_MINUTES)) {
            return HeroModel(HeroTone.NOW, null, "הגיע לתחנה", "$line · $s · ${arrived.at.israelClock()}$est")
        }
        if (next == null) return HeroModel(HeroTone.NONE, null, "אין כרגע אוטובוס בדרך לתחנה", "$line · $s")
        val m = minutesUntil(next.eta!!, now)
        val sub = "$line · $s · ב-${next.eta.israelClock()}" + (if (next.etaEstimated) " (הערכה)" else est)
        val t = tone(m)
        return if (t == HeroTone.NOW) HeroModel(t, null, "מגיע עכשיו", sub) else HeroModel(t, "מגיע לתחנה בעוד", "$m דק׳", sub)
    }

    // ------------------------------------------------------------------ ETA at the user's stop

    /**
     * ETA of a route bus at stop [userIndex]: curlbus' own when it lists the bus there; otherwise (curlbus lists buses only
     * ~30 min ahead) the ETA at the furthest listed stop before it + the scheduled run time between the two
     * (estimate = true); a trip that has not started: scheduled start + run time. Null when the bus has passed the stop.
     */
    fun etaAtStop(b: RouteBus, stops: List<RouteStop>, userIndex: Int): Pair<Instant, Boolean>? {
        val user = stops.getOrNull(userIndex) ?: return null
        b.etaByStop[user.code]?.let { return it to false }
        val userOff = user.offsetSeconds ?: return null
        val ref = stops.withIndex().filter { (i, s) -> i < userIndex && s.offsetSeconds != null && b.etaByStop[s.code] != null }.maxByOrNull { it.index }
        if (ref != null) return b.etaByStop.getValue(ref.value.code) + (userOff - ref.value.offsetSeconds!!).seconds to true
        if (b.status == RouteBusStatus.NOT_STARTED && b.scheduledStart != null) return b.scheduledStart + userOff.seconds to true
        return null
    }

    // ------------------------------------------------------------------ line diagram

    data class DiagramBus(
        val key: String, val vehicle: String?, val etaMinutes: Int?, val etaEstimated: Boolean,
        val freshness: Freshness, val notStarted: Boolean, val followed: Boolean = false,
    )

    sealed interface DiagramRow {
        data class Stop(
            val index: Int, val name: String, val isUser: Boolean, val isOrigin: Boolean, val isDestination: Boolean,
            /** curlbus doesn't answer for this stop (left out of the request; neighbours stand in). */
            val broken: Boolean, val busesAt: List<DiagramBus>,
        ) : DiagramRow
        /** Bus(es) between stop [afterIndex] and the next one. */
        data class Between(val afterIndex: Int, val buses: List<DiagramBus>) : DiagramRow
        /** A run of [count] quiet stops ("… 6 תחנות"). */
        data class Collapsed(val count: Int, val fromIndex: Int) : DiagramRow
    }

    /**
     * The route as a vertical strip: every bus at a stop (within [AT_STOP_METERS]) or between two stops by its GPS
     * progress; origin, destination, the user's stop and the stops next to buses always shown; other runs of ≥ 2 stops
     * collapsed. Not-started trips wait at the origin; trips without GPS are left out (no position).
     */
    fun diagram(
        stops: List<RouteStop>,
        snap: RouteLiveSnapshot,
        userIndex: Int?,
        now: Instant,
        eta: (RouteBus) -> Pair<Instant, Boolean>? = { b -> userIndex?.let { etaAtStop(b, stops, it) } },
        followed: (RouteBus) -> Boolean = { false },
    ): List<DiagramRow> {
        if (stops.size < 2) return emptyList()
        val at = mutableMapOf<Int, MutableList<DiagramBus>>()
        val between = mutableMapOf<Int, MutableList<DiagramBus>>()
        for (b in snap.buses) {
            val p = b.position
            val e = eta(b)
            val d = DiagramBus(
                b.key, b.vehicleRef, e?.let { minutesUntil(it.first, now) }, e?.second == true,
                freshness(b.toDetails(null, null), now), b.status == RouteBusStatus.NOT_STARTED, followed(b),
            )
            when {
                b.status == RouteBusStatus.NOT_STARTED -> at.getOrPut(0) { mutableListOf() } += d
                p == null || b.offRoute -> {}
                p.nearestStopMeters <= AT_STOP_METERS -> at.getOrPut(p.nearestStopIndex) { mutableListOf() } += d
                else -> between.getOrPut((p.nextStopIndex - 1).coerceIn(0, stops.size - 2)) { mutableListOf() } += d
            }
        }
        val broken = snap.failedStops.map { it.code }.toSet()
        val keep = sortedSetOf(0, stops.lastIndex)
        userIndex?.takeIf { it in stops.indices }?.let { keep += it }
        at.keys.forEach { i -> keep += listOf(i - 1, i, i + 1).filter { it in stops.indices } }
        between.keys.forEach { i -> keep += listOf(i, i + 1) }
        fun stopRow(i: Int) = DiagramRow.Stop(
            i, NotificationTexts.shortStop(stops[i].name), i == userIndex, i == 0, i == stops.lastIndex, stops[i].code in broken,
            at[i].orEmpty(),
        )
        val rows = mutableListOf<DiagramRow>()
        var hiddenFrom = -1
        for (i in stops.indices) {
            if (i !in keep) { if (hiddenFrom < 0) hiddenFrom = i; continue }
            if (hiddenFrom >= 0) {
                val n = i - hiddenFrom
                if (n == 1) rows += stopRow(hiddenFrom) else rows += DiagramRow.Collapsed(n, hiddenFrom)
                hiddenFrom = -1
            }
            rows += stopRow(i)
            between[i]?.let { rows += DiagramRow.Between(i, it) }
        }
        return rows
    }
}

/** Rows of the watch screen: the watched stop's buses (authoritative) enriched with the live route view. */
object WatchRows {
    /**
     * One [BusDetails] per bus that still has to reach the watched stop, earliest first:
     * * every bus of the watch's last poll ([WatchState.buses]: ETA, leave time, alert state, fallback estimate), with
     *   its position / next stop / GPS age taken from the full-route snapshot when the route view has it;
     * * plus route-view buses the watched stop doesn't list yet (> ~30 min away, or not started): ETA estimated from
     *   the furthest stop that lists them + the scheduled run time (marked estimated); for time to leave their leave
     *   time is that ETA − walk − buffer.
     */
    fun build(
        state: WatchState,
        stops: List<RouteStop>?,
        snap: RouteLiveSnapshot?,
        observed: Map<String, Instant>,
        now: Instant,
    ): List<BusDetails> {
        val spec = state.spec
        val origin = stops?.firstOrNull()?.code ?: spec.origin.code
        val byKey = snap?.buses?.associateBy { it.key }.orEmpty()
        val userIndex = stops?.indexOfFirst { it.code == spec.stop.code } ?: -1
        val fromWatch = state.buses.map { v ->
            val base = v.toDetails(spec, stops, observed[v.key])
            val r = byKey[v.key] ?: return@map base
            r.toDetails(spec.stop, origin, observed[v.key]).copy(
                eta = base.eta, etaIsDeparture = base.etaIsDeparture, etaStopName = base.etaStopName,
                alerted = base.alerted, leaveAt = base.leaveAt, leaveStatus = base.leaveStatus, etaLive = base.etaLive,
                etaEstimated = base.etaEstimated, distanceToStopMeters = base.distanceToStopMeters,
                scheduledStart = r.scheduledStart ?: base.scheduledStart,
            )
        }
        val seen = state.buses.map { it.key }.toSet()
        val plan = state.leavePlan ?: spec.leave
        val extra = if (snap == null || stops == null || userIndex < 0 || !state.isActive) emptyList() else snap.buses.filter { it.key !in seen }.mapNotNull { r ->
            val (eta, estimated) = LineModels.etaAtStop(r, stops, userIndex) ?: return@mapNotNull null
            if (eta < now) return@mapNotNull null
            val leaveAt = if (spec.trigger == TriggerType.LEAVE_FOR_STOP && plan != null) eta - (plan.walkMinutes + plan.bufferMinutes).minutes else null
            r.toDetails(spec.stop, origin, observed[r.key]).copy(
                eta = eta, etaEstimated = estimated,
                leaveAt = leaveAt,
                leaveStatus = leaveAt?.let { if (it >= now) LeaveStatus.WAIT else LeaveStatus.NOT_CATCHABLE },
            )
        }
        return (fromWatch + extra).sortedWith(compareBy(nullsLast()) { it.eta })
    }
}
