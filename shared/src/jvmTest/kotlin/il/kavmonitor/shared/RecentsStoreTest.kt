package il.kavmonitor.shared

import com.russhwolf.settings.MapSettings
import il.kavmonitor.shared.recents.FavoriteLine
import il.kavmonitor.shared.recents.RecentsStore
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentsStoreTest {
    @Test
    fun keepsFiveMostRecentLinesDedupedAndPersists() {
        val settings = MapSettings()
        val store = RecentsStore(settings)
        val a = line(194, "194")
        store.add(a)
        store.add(line(5, "5"))
        store.add(a.copy(originName = "רבין")) // same line → moves to top, replaces
        assertEquals(2, store.recents.value.size)
        assertEquals("רבין", store.recents.value.first().originName)
        assertEquals(194, store.recents.value.first().lineRef)
        repeat(10) { i -> store.add(line(1000 + i, "$i")) }
        assertEquals(RecentsStore.MAX_RECENTS, store.recents.value.size)
        assertEquals("9", store.recents.value.first().lineName)
        assertEquals(store.recents.value, RecentsStore(settings).recents.value)
    }

    private fun line(ref: Int, name: String) = FavoriteLine(ref, name, 3, "דן", "מוצא", "יעד")
}
