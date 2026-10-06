package il.kavmonitor.shared.data

import il.kavmonitor.shared.model.StopSnapshot
import kotlin.time.Clock

/** Result of one [MultiStopFetcher.fetch]. */
data class MultiStopResult(
    /** Visits of every stop that answered (merged). */
    val snapshot: StopSnapshot,
    /** Stops covered by a successful request. */
    val answered: Set<String>,
    /** Stops left out because they are known broken. */
    val skipped: Set<String>,
    /** Stops found broken by this call's bisect (now in the broken cache). */
    val newlyBroken: List<String>,
    /** HTTP requests this call made (1 per ≤30 stops normally; + bisect requests once when a new broken stop appears). */
    val requests: Int,
)

/**
 * All of a route's stops in as few curlbus requests as possible (1 per ≤ 30 stops).
 *
 * curlbus fails the WHOLE multi-stop request with HTTP 500 if any one stop is broken (≈1 s). Then the culprit(s) are
 * found by bisecting (≈log2 n extra requests; the halves that answer are kept, so no data is lost), confirmed with one
 * single-stop request, stored in [broken] (persistent, with a TTL so they are tried again later), and left out of the
 * next requests. A request that still fails for another reason (timeout, network) is rethrown.
 */
class MultiStopFetcher(
    private val live: LiveDataSource,
    private val broken: BrokenStops?,
    private val clock: Clock = Clock.System,
    private val maxPerRequest: Int = LiveDataSource.MAX_STOPS_PER_REQUEST,
) {
    /** Broken stops when there is no persistent cache (tests / no settings). */
    private val memory = mutableSetOf<String>()
    /** Total requests made (diagnostics / tests). */
    var totalRequests = 0; private set

    fun isBroken(code: String): Boolean = code in memory || broken?.isBroken(code) == true

    suspend fun fetch(codes: List<String>): MultiStopResult {
        val distinct = codes.distinct()
        val skipped = distinct.filter { isBroken(it) }.toSet()
        val use = distinct.filterNot { it in skipped }
        if (use.isEmpty()) throw IllegalStateException("every stop is known broken: $distinct")
        val chunks = chunk(use)
        var requests = 0
        val parts = mutableListOf<StopSnapshot>()
        val answered = mutableSetOf<String>()
        val bad = mutableListOf<String>()
        var lastError: Exception? = null
        for (c in chunks) {
            val r = Attempt()
            try {
                r.run(c)
            } catch (e: StopServerException) {
                lastError = e
            }
            requests += r.requests
            parts += r.snaps
            answered += r.answered
            bad += r.bad
        }
        bad.forEach { markBroken(it) }
        if (parts.isEmpty()) throw lastError ?: IllegalStateException("no stop answered")
        return MultiStopResult(StopSnapshot.merge(use.joinToString("+"), parts), answered, skipped, bad, requests)
    }

    /** Even chunks of at most [maxPerRequest] (e.g. 34 stops → 17 + 17). */
    private fun chunk(codes: List<String>): List<List<String>> {
        val n = (codes.size + maxPerRequest - 1) / maxPerRequest
        val size = (codes.size + n - 1) / n
        return codes.chunked(size)
    }

    private fun markBroken(code: String) {
        memory += code
        broken?.mark(code)
        ErrorLog.w("curlbus: stop $code fails every multi-stop request → left out (broken cache)")
    }

    private inner class Attempt {
        var requests = 0
        val snaps = mutableListOf<StopSnapshot>()
        val answered = mutableSetOf<String>()
        val bad = mutableListOf<String>()

        private suspend fun request(codes: List<String>): StopSnapshot {
            requests++; totalRequests++
            return live.multiStopVisits(codes)
        }

        /** Throws [StopServerException] only if nothing in [codes] could be fetched. */
        suspend fun run(codes: List<String>) {
            try {
                snaps += request(codes); answered += codes
            } catch (e: StopServerException) {
                bisect(codes, requested = true)
                if (snaps.isEmpty()) throw e
            }
        }

        /**
         * [failing] is known to fail as a whole; [requested] = that exact list failed in its own request (otherwise its
         * failure was inferred: the whole failed and the other half answered).
         */
        private suspend fun bisect(failing: List<String>, requested: Boolean) {
            if (failing.size == 1) {
                if (requested) { bad += failing.single(); return } // failed alone: broken
                // inferred only: confirm with one single-stop request (success = the earlier 500 was transient)
                if (!tryFetch(failing)) bad += failing.single()
                return
            }
            val a = failing.subList(0, failing.size / 2)
            val b = failing.subList(failing.size / 2, failing.size)
            if (tryFetch(a)) bisect(b, requested = false) // the culprit is in b: no need to request b as a whole
            else {
                bisect(a, requested = true)
                if (!tryFetch(b)) bisect(b, requested = true)
            }
        }

        private suspend fun tryFetch(codes: List<String>): Boolean = try {
            snaps += request(codes); answered += codes; true
        } catch (_: StopServerException) {
            false
        }
    }
}
