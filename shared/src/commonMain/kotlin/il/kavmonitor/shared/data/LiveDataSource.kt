package il.kavmonitor.shared.data

import il.kavmonitor.shared.model.StopSnapshot

/**
 * Realtime source: upcoming visits at ONE stop. Implemented by curlbus today; designed so it can be swapped
 * for our own proxy (or the MOT SIRI feed) later without touching the trigger logic or UI.
 */
interface LiveDataSource {
    suspend fun stopVisits(stopCode: String): StopSnapshot
}
