package il.kavmonitor.shared

import com.russhwolf.settings.MapSettings
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.recents.RecentsStore
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentsStoreTest {
    @Test
    fun keepsFiveMostRecentDedupedAndPersists() {
        val settings = MapSettings()
        val store = RecentsStore(settings)
        val a = spec194(TriggerType.DEPARTS_ORIGIN)
        store.add(a)
        store.add(spec194(TriggerType.ARRIVES_AT_STOP, STOP_21472))
        store.add(a.copy(durationMinutes = 30)) // same combo → moves to top, replaces
        assertEquals(2, store.recents.value.size)
        assertEquals(30, store.recents.value.first().durationMinutes)
        repeat(10) { i -> store.add(a.copy(lineRef = 1000 + i, lineName = "$i")) }
        assertEquals(RecentsStore.MAX_RECENTS, store.recents.value.size)
        assertEquals("9", store.recents.value.first().lineName)
        // Reload from the same settings
        assertEquals(store.recents.value, RecentsStore(settings).recents.value)
    }
}
