package il.kavmonitor.shared

import il.kavmonitor.shared.data.BrokenStops
import il.kavmonitor.shared.data.LiveDataSource
import il.kavmonitor.shared.data.MultiStopFetcher
import il.kavmonitor.shared.data.StopServerException
import il.kavmonitor.shared.model.LiveVisit
import il.kavmonitor.shared.model.StopSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Bisect + broken-stop cache on curlbus' "one broken stop = HTTP 500 for the whole request". */
class MultiStopFetcherTest {
    private val now = ilTime("19:30:00")

    /** Fake curlbus: each stop has one visit; [broken] stops fail every request they are in; [transient] fails once. */
    private class Fake(val broken: Set<String>, val transient: MutableSet<String> = mutableSetOf()) : LiveDataSource {
        val calls = mutableListOf<List<String>>()
        override suspend fun stopVisits(stopCode: String) = multiStopVisits(listOf(stopCode))
        override suspend fun multiStopVisits(stopCodes: List<String>): StopSnapshot {
            calls += stopCodes
            require(stopCodes.size <= 30) { "curlbus: HTTP 400 for > 30 stops" }
            require(stopCodes.toSet().size == stopCodes.size) { "duplicate stop" }
            if (stopCodes.any { it in broken }) throw StopServerException(stopCodes.joinToString("+"), 500)
            if (transient.isNotEmpty() && stopCodes.any { it in transient }) {
                transient.clear(); throw StopServerException(stopCodes.joinToString("+"), 502)
            }
            return StopSnapshot(stopCodes.joinToString("+"), ilTime("19:30:00"), null,
                stopCodes.map { LiveVisit(stopCode = it, lineName = "194", lineRef = 37121, operatorRef = 3, vehicleRef = "v$it", tripId = "t$it", eta = null, originDepartureTime = null, recordedAt = null, lat = null, lon = null) })
        }
    }

    private val codes28 = (1..28).map { "s$it" }

    @Test fun oneRequestWhenNothingIsBroken() = runTest {
        val live = Fake(emptySet())
        val r = MultiStopFetcher(live, null).fetch(codes28)
        assertEquals(1, r.requests); assertEquals(1, live.calls.size)
        assertEquals(28, r.answered.size); assertEquals(28, r.snapshot.allVisits.size)
        assertTrue(r.newlyBroken.isEmpty())
    }

    @Test fun oneCulpritIsFoundInAboutLog2NRequestsOnceThenLeftOut() = runTest {
        val clock = MutableClock(now)
        val broken = BrokenStops(null, clock)
        for (culprit in codes28) {
            val live = Fake(setOf(culprit))
            val f = MultiStopFetcher(live, BrokenStops(null, clock))
            val r = f.fetch(codes28)
            // 1 failing request + ≤ 2 per level (the bisect keeps the halves that answer: no data lost, no extra retry)
            assertTrue(r.requests <= 2 + 2 * ceil(log2(28.0)).toInt(), "$culprit: ${r.requests} requests")
            assertEquals(listOf(culprit), r.newlyBroken)
            assertEquals(27, r.answered.size, "every other stop answered in the same refresh")
            assertEquals(27, r.snapshot.allVisits.size)
            // next refresh: one request without the culprit
            val r2 = f.fetch(codes28)
            assertEquals(1, r2.requests); assertEquals(setOf(culprit), r2.skipped); assertEquals(27, live.calls.last().size)
        }
        assertTrue(broken.known().isEmpty())
    }

    @Test fun theFourBrokenStopsOf194AreFoundInOnePassAndPersistedWithATtl() = runTest {
        val clock = MutableClock(now)
        val broken = BrokenStops(null, clock)
        val route = stops194().map { it.code }
        val live = Fake(BROKEN_194)
        val r = MultiStopFetcher(live, broken).fetch(route)
        // same sequence as the live run of 2026-10-06 19:41 (docs/evidence/multistop-2026-10-06.md): 18 requests
        assertEquals(18, r.requests, live.calls.joinToString("\n") { it.joinToString("+") })
        assertEquals(BROKEN_194, r.newlyBroken.toSet())
        assertEquals(BROKEN_194, broken.known())
        assertEquals(24, r.answered.size)
        // a broken stop that failed in its own single-stop request is never asked a second time
        BROKEN_194.forEach { b -> assertEquals(1, live.calls.count { it == listOf(b) }, b) }
        // a new fetcher (e.g. after an app restart) uses the persistent cache: 1 request
        val live2 = Fake(BROKEN_194)
        assertEquals(1, MultiStopFetcher(live2, broken).fetch(route).requests)
        // after the TTL the stops are tried again (and found again)
        clock.now = now + broken.ttl + 1.minutes
        assertTrue(broken.known().isEmpty())
        val live3 = Fake(BROKEN_194)
        val r3 = MultiStopFetcher(live3, broken).fetch(route)
        assertEquals(18, r3.requests); assertEquals(BROKEN_194, broken.known())
        assertEquals(6.hours, broken.ttl)
    }

    @Test fun aTransient500MarksNothing() = runTest {
        val live = Fake(emptySet(), transient = mutableSetOf("s5"))
        val broken = BrokenStops(null, MutableClock(now))
        val r = MultiStopFetcher(live, broken).fetch(codes28)
        assertTrue(r.newlyBroken.isEmpty(), "${r.newlyBroken}")
        assertTrue(broken.known().isEmpty())
        assertEquals(28, r.answered.size)
        // the half that was only inferred to fail is narrowed down to one stop, whose single request then answers
        // (≈ log2 n extra requests; the cheap path for the common case: a stop that stays broken)
        assertTrue(r.requests <= 2 + ceil(log2(28.0)).toInt(), "${r.requests}")
    }

    @Test fun moreThan30StopsAreSplitEvenlyAndDuplicatesDropped() = runTest {
        val live = Fake(emptySet())
        val codes = (1..34).map { "s$it" } + listOf("s1", "s2")
        val r = MultiStopFetcher(live, null).fetch(codes)
        assertEquals(2, r.requests)
        assertEquals(listOf(17, 17), live.calls.map { it.size })
        assertEquals(34, r.answered.size)
    }

    @Test fun everythingFailingIsRethrown() = runTest {
        val live = Fake(setOf("a", "b"))
        assertFailsWith<StopServerException> { MultiStopFetcher(live, null).fetch(listOf("a", "b")) }
        assertFailsWith<IllegalStateException> {
            val f = MultiStopFetcher(live, null)
            runCatching { f.fetch(listOf("a", "b")) }
            f.fetch(listOf("a", "b")) // both known broken now
        }
    }
}
