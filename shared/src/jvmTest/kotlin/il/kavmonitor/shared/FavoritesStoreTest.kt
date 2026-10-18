package il.kavmonitor.shared

import com.russhwolf.settings.MapSettings
import il.kavmonitor.shared.recents.FavoriteLine
import il.kavmonitor.shared.recents.FavoritesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavoritesStoreTest {
    private fun line(ref: Int, name: String = "$ref") =
        FavoriteLine(ref, name, operatorRef = 3, agencyName = "אגד", originName = "מוצא", destinationLabel = "יעד")

    @Test
    fun toggleDedupesPersistsAndCaps() {
        val settings = MapSettings()
        val store = FavoritesStore(settings)
        val a = line(1, "194")
        store.toggle(a)
        store.toggle(a.copy(originName = "אחר"))
        assertTrue(store.items.value.isEmpty())
        store.toggle(a)
        assertTrue(store.isFavorite(1, 3))
        assertFalse(store.isFavorite(1, 4))
        repeat(20) { store.toggle(line(100 + it)) }
        assertEquals(FavoritesStore.MAX, store.items.value.size)
        assertEquals(store.items.value, FavoritesStore(settings).items.value)
    }
}
