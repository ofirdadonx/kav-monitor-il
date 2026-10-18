package il.kavmonitor.shared.recents

import il.kavmonitor.shared.data.ApiJson
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer

/** Last [MAX_RECENTS] line pages the user opened, most recent first. Not tied to starting a watch. */
class RecentsStore(private val settings: Settings) {
    private val _recents = MutableStateFlow(load())
    val recents: StateFlow<List<FavoriteLine>> = _recents.asStateFlow()

    fun add(line: FavoriteLine) {
        val updated = (listOf(line) + _recents.value.filterNot { it.key == line.key }).take(MAX_RECENTS)
        save(updated)
    }

    fun remove(line: FavoriteLine) = save(_recents.value.filterNot { it.key == line.key })

    private fun save(list: List<FavoriteLine>) {
        settings.putString(KEY, ApiJson.encodeToString(serializer, list))
        _recents.value = list
    }

    private fun load(): List<FavoriteLine> = try {
        settings.getStringOrNull(KEY)?.let { ApiJson.decodeFromString(serializer, it) }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val MAX_RECENTS = 5
        private const val KEY = "recents_lines_v1"
        private val serializer = ListSerializer(FavoriteLine.serializer())
    }
}
