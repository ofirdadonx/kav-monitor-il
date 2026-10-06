package il.kavmonitor.shared.watch

import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.trigger.TriggerEvent
import kotlin.time.Instant

enum class WatchPhase { RUNNING, TIMED_OUT, STOPPED }

/** One bus of the watched line currently listed at the watched stop. */
data class BusView(
    val key: String,
    val vehicleRef: String?,
    val tripId: String?,
    val eta: Instant?,
    val scheduledDeparture: Instant?,
    val recordedAt: Instant?,
    val hasFreshGps: Boolean,
    val distanceToStopMeters: Double?,
    val alerted: Boolean,
    val lat: Double? = null,
    val lon: Double? = null,
)

data class WatchState(
    val spec: WatchSpec,
    val phase: WatchPhase,
    val startedAt: Instant,
    val endsAt: Instant,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    /** Error of the most recent poll (null when the last poll succeeded). */
    val lastError: String? = null,
    val buses: List<BusView> = emptyList(),
    val busesSeen: Int = 0,
    val alerts: List<TriggerEvent> = emptyList(),
    val polls: Int = 0,
) {
    val isActive: Boolean get() = phase == WatchPhase.RUNNING
}
