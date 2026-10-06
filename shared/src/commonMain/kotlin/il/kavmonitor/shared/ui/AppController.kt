package il.kavmonitor.shared.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import il.kavmonitor.shared.data.RouteRepository
import il.kavmonitor.shared.model.Direction
import il.kavmonitor.shared.model.Operators
import il.kavmonitor.shared.model.StopList
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.platform.WatchLauncher
import il.kavmonitor.shared.recents.RecentsStore
import il.kavmonitor.shared.trigger.TriggerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

enum class Screen { HOME, NEW_WATCH, WATCH }

sealed interface Loadable<out T> {
    data object Idle : Loadable<Nothing>
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T) : Loadable<T>
    data class Failed(val message: String) : Loadable<Nothing>
}

/** UI state + actions. Lives as long as the process (survives rotation), not tied to a platform ViewModel. */
class AppController(
    private val routes: RouteRepository,
    val recents: RecentsStore,
    private val launcher: WatchLauncher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    var screen by mutableStateOf(Screen.HOME); private set

    // ---- new watch flow ----
    var lineInput by mutableStateOf("")
    var directions by mutableStateOf<Loadable<List<Direction>>>(Loadable.Idle); private set
    var selectedDirection by mutableStateOf<Direction?>(null); private set
    var stops by mutableStateOf<Loadable<StopList>>(Loadable.Idle); private set
    var trigger by mutableStateOf(TriggerType.DEPARTS_ORIGIN)
    var targetStopCode by mutableStateOf<String?>(null)
    var durationMinutes by mutableStateOf(TriggerConfig.DEFAULT_DURATION_MINUTES)

    private var job: Job? = null

    fun openNewWatch() {
        directions = Loadable.Idle; selectedDirection = null; stops = Loadable.Idle
        trigger = TriggerType.DEPARTS_ORIGIN; targetStopCode = null
        durationMinutes = TriggerConfig.DEFAULT_DURATION_MINUTES
        screen = Screen.NEW_WATCH
    }

    fun openWatch() { screen = Screen.WATCH }

    /** @return true if handled (navigated back inside the app). */
    fun back(): Boolean = when (screen) {
        Screen.HOME -> false
        Screen.NEW_WATCH -> {
            if (selectedDirection != null) { selectedDirection = null; stops = Loadable.Idle } else screen = Screen.HOME
            true
        }
        Screen.WATCH -> { screen = Screen.HOME; true }
    }

    fun searchLine() {
        val line = lineInput.trim()
        if (line.isEmpty()) return
        selectedDirection = null; stops = Loadable.Idle
        load({ directions = it }) { routes.directions(line) }
    }

    fun selectDirection(d: Direction) {
        selectedDirection = d; trigger = TriggerType.DEPARTS_ORIGIN; targetStopCode = null
        load({ stops = it }) { routes.stops(d) }
    }

    fun canStart(): Boolean {
        val s = (stops as? Loadable.Ready)?.value ?: return false
        return s.stops.isNotEmpty() && (trigger == TriggerType.DEPARTS_ORIGIN || targetStopCode != null)
    }

    fun startNewWatch() {
        val d = selectedDirection ?: return
        val list = (stops as? Loadable.Ready)?.value?.stops ?: return
        val origin = list.first()
        var trig = trigger
        val stop = if (trig == TriggerType.ARRIVES_AT_STOP) list.firstOrNull { it.code == targetStopCode } ?: return else origin
        if (stop.code == origin.code) trig = TriggerType.DEPARTS_ORIGIN
        startWatch(
            WatchSpec(
                lineRef = d.lineRef, lineName = d.routeShortName, operatorRef = d.operatorRef,
                agencyName = Operators.displayName(d.operatorRef, d.agencyName),
                directionLabel = d.destinationLabel, trigger = trig, stop = stop, origin = origin,
                durationMinutes = durationMinutes,
            ),
        )
    }

    fun startWatch(spec: WatchSpec) {
        recents.add(spec)
        launcher.start(spec)
        screen = Screen.WATCH
    }

    fun stopWatch() = launcher.stop()

    private fun <T> load(set: (Loadable<T>) -> Unit, block: suspend () -> T) {
        job?.cancel()
        set(Loadable.Loading)
        job = scope.launch {
            try {
                set(Loadable.Ready(block()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                set(Loadable.Failed(e.message?.lineSequence()?.firstOrNull()?.take(160) ?: e::class.simpleName.orEmpty()))
            }
        }
    }
}
