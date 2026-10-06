package il.kavmonitor.shared.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.russhwolf.settings.Settings
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Stops that curlbus answers with HTTP 5xx, remembered across runs (multiplatform-settings) with a TTL so they are
 * tried again later. A successful direct fetch clears the entry. Compose-observable ([version]) for the "estimated"
 * badges in the stop pickers.
 */
class BrokenStops(
    private val settings: Settings?,
    private val clock: Clock = Clock.System,
    val ttl: Duration = DEFAULT_TTL,
) {
    private val serializer = MapSerializer(String.serializer(), Long.serializer())
    @kotlin.concurrent.Volatile
    private var map: Map<String, Long> = load() // immutable snapshots, replaced on write
    var version by mutableStateOf(0)
        private set

    private fun load(): Map<String, Long> = runCatching {
        settings?.getStringOrNull(KEY)?.let { ApiJson.decodeFromString(serializer, it) }
    }.getOrNull().orEmpty()

    private fun save() {
        settings?.putString(KEY, ApiJson.encodeToString(serializer, map))
        version++
    }

    fun isBroken(code: String): Boolean {
        val at = map[code] ?: return false
        return clock.now() - Instant.fromEpochMilliseconds(at) < ttl
    }

    fun mark(code: String) {
        if (isBroken(code)) return
        map = map + (code to clock.now().toEpochMilliseconds())
        save()
    }

    fun clear(code: String) {
        if (code !in map) return
        map = map - code
        save()
    }

    fun known(): Set<String> = map.keys.filter { isBroken(it) }.toSet()

    companion object {
        const val KEY = "broken_stops_v1"
        val DEFAULT_TTL = 6.hours
    }
}

/**
 * Wraps a [LiveDataSource] and keeps [BrokenStops] up to date from every fetch (watch polls and the live route view):
 * [threshold] consecutive HTTP 5xx answers mark a stop broken, any success clears it.
 */
class BrokenStopTracker(
    private val delegate: LiveDataSource,
    private val broken: BrokenStops,
    private val threshold: Int = 2,
) : LiveDataSource {
    @kotlin.concurrent.Volatile
    private var failures: Map<String, Int> = emptyMap()

    override suspend fun stopVisits(stopCode: String): il.kavmonitor.shared.model.StopSnapshot {
        try {
            val snap = delegate.stopVisits(stopCode)
            if (stopCode in failures) failures = failures - stopCode
            broken.clear(stopCode)
            return snap
        } catch (e: StopServerException) {
            val n = (failures[stopCode] ?: 0) + 1
            failures = failures + (stopCode to n)
            if (n >= threshold) broken.mark(stopCode)
            throw e
        }
    }

    /** Multi-stop: a success clears every stop in it; a 5xx is left to [MultiStopFetcher]'s bisect (it marks the culprit). */
    override suspend fun multiStopVisits(stopCodes: List<String>): il.kavmonitor.shared.model.StopSnapshot {
        if (stopCodes.size == 1) return stopVisits(stopCodes.single())
        val snap = delegate.multiStopVisits(stopCodes)
        stopCodes.forEach { broken.clear(it) }
        return snap
    }
}
