package il.kavmonitor.shared.watch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-wide current watch state: written by the background runner, observed by the UI. */
object WatchStateHolder {
    private val _state = MutableStateFlow<WatchState?>(null)
    val state: StateFlow<WatchState?> = _state.asStateFlow()

    fun update(state: WatchState?) { _state.value = state }
}
