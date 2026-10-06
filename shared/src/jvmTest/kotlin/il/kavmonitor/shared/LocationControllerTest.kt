package il.kavmonitor.shared

import com.russhwolf.settings.MapSettings
import il.kavmonitor.shared.location.GeoFix
import il.kavmonitor.shared.location.LocationAccess
import il.kavmonitor.shared.location.LocationController
import il.kavmonitor.shared.location.LocationProvider
import il.kavmonitor.shared.location.LocationSubscription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationControllerTest {
    private class FakeProvider : LocationProvider {
        var granted = false
        var canAsk = true
        val requests = mutableListOf<Boolean>()
        var pending: ((Boolean) -> Unit)? = null
        var active = 0
        var listener: ((GeoFix) -> Unit)? = null
        override fun hasPermission() = granted
        override fun requestPermission(userInitiated: Boolean, onResult: (Boolean) -> Unit): Boolean {
            if (!canAsk) return false
            requests += userInitiated; pending = onResult; return true
        }
        fun answer(grant: Boolean) { granted = grant; pending!!.invoke(grant); pending = null }
        override fun start(intervalMillis: Long, minDistanceMeters: Double, onFix: (GeoFix) -> Unit): LocationSubscription {
            active++; listener = onFix
            return LocationSubscription { active--; }
        }
        fun emit(lat: Double) = listener!!.invoke(GeoFix(lat, 34.8, 10.0, ilTime("16:00:00")))
    }

    @Test
    fun asksOnceWhenFirstShown_deniedShowsRowAndNeverNags() {
        val p = FakeProvider(); val settings = MapSettings()
        val c = LocationController(p, settings)
        c.setAppVisible(true)
        assertTrue(p.requests.isEmpty(), "nothing asked before a section is shown")
        val t = c.attach()
        assertEquals(listOf(false), p.requests)
        assertEquals(LocationAccess.ASKING, c.access)
        p.answer(false)
        assertEquals(LocationAccess.DENIED, c.access)
        assertFalse(c.isUpdating)
        c.detach(t); c.attach()
        assertEquals(1, p.requests.size, "no second automatic dialog")
        // a new process with the same settings doesn't ask either
        val c2 = LocationController(p, settings); c2.setAppVisible(true); c2.attach()
        assertEquals(1, p.requests.size)
        assertEquals(LocationAccess.DENIED, c2.access)
        // explicit tap on "enable location" asks again
        c2.enableLocation()
        assertEquals(listOf(false, true), p.requests)
        p.answer(true)
        assertEquals(LocationAccess.GRANTED, c2.access)
        assertTrue(c2.isUpdating)
    }

    @Test
    fun updatesRunOnlyWhileShownAndVisible() {
        val p = FakeProvider().apply { granted = true }
        val c = LocationController(p, MapSettings())
        val t = c.attach()
        assertFalse(c.isUpdating, "app not visible yet")
        c.setAppVisible(true)
        assertTrue(c.isUpdating); assertEquals(1, p.active)
        p.emit(32.0)
        assertEquals(32.0, c.fix!!.lat)
        c.setAppVisible(false)
        assertFalse(c.isUpdating); assertEquals(0, p.active)
        c.setAppVisible(true)
        val t2 = c.attach() // a second section shares the same updates
        assertEquals(1, p.active)
        c.detach(t)
        assertTrue(c.isUpdating)
        c.detach(t2)
        assertFalse(c.isUpdating); assertEquals(0, p.active)
        assertEquals(2, c.starts)
    }

    @Test
    fun lateFixFromAStoppedSubscriptionIsIgnored() {
        val p = FakeProvider().apply { granted = true }
        val c = LocationController(p, MapSettings())
        c.setAppVisible(true)
        val t = c.attach()
        val old = p.listener!!
        c.detach(t)
        old(GeoFix(31.0, 34.0, null, ilTime("16:00:00")))
        assertNull(c.fix)
    }

    @Test
    fun noScreenToAskFrom_tryAgainLater() {
        val p = FakeProvider().apply { canAsk = false }
        val c = LocationController(p, MapSettings())
        c.setAppVisible(true)
        c.attach()
        assertEquals(LocationAccess.UNKNOWN, c.access)
        p.canAsk = true
        c.setAppVisible(true)
        assertEquals(listOf(false), p.requests)
    }
}
