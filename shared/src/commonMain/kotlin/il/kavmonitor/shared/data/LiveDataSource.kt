package il.kavmonitor.shared.data

import il.kavmonitor.shared.model.StopSnapshot

/**
 * Realtime source: upcoming visits at stops. Implemented by curlbus today; designed so it can be swapped
 * for our own proxy (or the MOT SIRI feed) later without touching the trigger logic or UI.
 */
interface LiveDataSource {
    suspend fun stopVisits(stopCode: String): StopSnapshot

    /**
     * Visits at several stops in ONE request (curlbus `/<a>+<b>+…`, at most [MAX_STOPS_PER_REQUEST]). Like curlbus, the
     * whole request fails (HTTP 5xx → [StopServerException]) if any one stop is broken. The default (for test fakes)
     * emulates that with single-stop calls.
     */
    suspend fun multiStopVisits(stopCodes: List<String>): StopSnapshot {
        if (stopCodes.size == 1) return stopVisits(stopCodes.single())
        val snaps = stopCodes.map { code ->
            try {
                stopVisits(code)
            } catch (e: StopServerException) {
                throw StopServerException(stopCodes.joinToString("+"), e.status, e)
            }
        }
        return StopSnapshot.merge(stopCodes.joinToString("+"), snaps)
    }

    companion object {
        /** curlbus rejects more than 30 stops per request (HTTP 400). */
        const val MAX_STOPS_PER_REQUEST = 30
    }
}
