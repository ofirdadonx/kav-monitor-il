package il.kavmonitor.shared.recents

import il.kavmonitor.shared.data.ApiJson
import il.kavmonitor.shared.model.WatchSpec
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer

/** Last [MAX_RECENTS] watch configurations, most recent first, persisted as JSON in [Settings]. */
class RecentsStore(private val settings: Settings) {
    private val _recents = MutableStateFlow(load())
    val recents: StateFlow<List<WatchSpec>> = _recents.asStateFlow()

    fun add(spec: WatchSpec) {
        val updated = (listOf(spec) + _recents.value.filterNot { it.recentKey == spec.recentKey }).take(MAX_RECENTS)
        save(updated)
    }

    fun remove(spec: WatchSpec) = save(_recents.value.filterNot { it.recentKey == spec.recentKey })

    private fun save(list: List<WatchSpec>) {
        settings.putString(KEY, ApiJson.encodeToString(serializer, list))
        _recents.value = list
    }

    private fun load(): List<WatchSpec> = try {
        settings.getStringOrNull(KEY)?.let { ApiJson.decodeFromString(serializer, it) }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val MAX_RECENTS = 5
        private const val KEY = "recents_v1"
        private val serializer = ListSerializer(WatchSpec.serializer())
    }
}
