package il.kavmonitor.shared.recents

import com.russhwolf.settings.Settings
import il.kavmonitor.shared.data.ApiJson
import il.kavmonitor.shared.model.Direction
import il.kavmonitor.shared.model.StopList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A line direction pinned on the home screen. Opening it shows the route. It does not start a watch. */
@Serializable
data class FavoriteLine(
    val lineRef: Int,
    val lineName: String,
    val operatorRef: Int,
    val agencyName: String,
    val originName: String,
    val destinationLabel: String,
) {
    val key: String get() = "$lineRef|$operatorRef"

    companion object {
        fun from(d: Direction, stops: StopList? = null): FavoriteLine {
            val list = stops?.stops?.takeIf { it.size >= 2 }
            val origin = list?.first()?.name?.ifBlank { null } ?: d.parsedName.origin.stop.ifBlank { d.longName }
            val last = list?.last()
            val dest = when {
                last == null -> d.destinationLabel
                last.city.isNullOrBlank() -> last.name
                else -> "${last.name}, ${last.city}"
            }
            return FavoriteLine(d.lineRef, d.routeShortName, d.operatorRef, d.agencyName, origin, dest)
        }
    }
}

/** Favorite lines, most recently added first. [settings] null keeps them in memory only (tests). */
class FavoritesStore(private val settings: Settings?) {
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<FavoriteLine>> = _items.asStateFlow()

    fun isFavorite(lineRef: Int, operatorRef: Int): Boolean = _items.value.any { it.lineRef == lineRef && it.operatorRef == operatorRef }

    fun toggle(line: FavoriteLine) {
        val cur = _items.value
        val next = if (cur.any { it.key == line.key }) cur.filterNot { it.key == line.key }
        else (listOf(line) + cur.filterNot { it.key == line.key }).take(MAX)
        save(next)
    }

    fun remove(line: FavoriteLine) = save(_items.value.filterNot { it.key == line.key })

    private fun save(list: List<FavoriteLine>) {
        settings?.putString(KEY, ApiJson.encodeToString(serializer, list))
        _items.value = list
    }

    private fun load(): List<FavoriteLine> = try {
        settings?.getStringOrNull(KEY)?.let { ApiJson.decodeFromString(serializer, it) }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        const val MAX = 12
        private const val KEY = "favorites_v1"
        private val serializer = ListSerializer(FavoriteLine.serializer())
    }
}
