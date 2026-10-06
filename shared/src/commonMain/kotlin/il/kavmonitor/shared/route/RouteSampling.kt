package il.kavmonitor.shared.route

import il.kavmonitor.shared.model.RouteStop

/**
 * Picks the few stops whose live lists together cover every bus on the route.
 *
 * Evidence (curlbus scan of all 28 stops of 194, 2026-10-06 14:51): a stop lists visits only up to ~30 min ahead
 * (max ETA seen per stop: 21–29 min), and a live bus is listed at every stop ahead of it within that horizon.
 * So stops spaced at most [maxGapSeconds] of scheduled run time apart (15 min, leaving ~15 min margin for delays) see
 * every active bus at least once. Origin is always included (shows trips that have not started yet).
 */
object RouteSampling {
    const val DEFAULT_MAX_GAP_SECONDS = 15 * 60
    /** Fallback spacing when schedule offsets are unknown (~15 min at ~24 km/h). */
    const val DEFAULT_MAX_GAP_METERS = 6_000.0

    fun sampleIndices(
        stops: List<RouteStop>,
        maxGapSeconds: Int = DEFAULT_MAX_GAP_SECONDS,
        maxGapMeters: Double = DEFAULT_MAX_GAP_METERS,
    ): List<Int> {
        if (stops.isEmpty()) return emptyList()
        if (stops.size == 1) return listOf(0)
        val offsets = stops.map { it.offsetSeconds }
        val distances = stops.map { it.distanceMeters }
        val metric: List<Double>? = when {
            offsets.all { it != null } && offsets.zipWithNext().all { (a, b) -> b!! >= a!! } -> offsets.map { it!!.toDouble() }
            distances.all { it != null } -> distances.map { it!! }
            else -> null
        }
        val gap = if (metric != null && offsets.all { it != null }) maxGapSeconds.toDouble() else maxGapMeters
        val result = mutableListOf(0)
        if (metric == null) {
            val step = ((stops.size - 1) / 4).coerceAtLeast(1)
            var i = step
            while (i < stops.lastIndex) { result += i; i += step }
        } else {
            var cur = 0
            while (cur < stops.lastIndex) {
                var next = cur + 1
                while (next + 1 <= stops.lastIndex && metric[next + 1] - metric[cur] <= gap) next++
                if (next != stops.lastIndex) result += next
                cur = next
            }
        }
        result += stops.lastIndex
        return result.distinct().sorted()
    }
}
