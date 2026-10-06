package il.kavmonitor.shared.watch

import il.kavmonitor.shared.model.LeavePlan
import il.kavmonitor.shared.model.WatchSpec
import kotlin.concurrent.Volatile

/**
 * Current walk plan of the running LEAVE_FOR_STOP watch. The UI updates it from fresh location fixes while the app is
 * visible (unless the user set the walk time manually); the background watch only reads the last value, so no
 * background location is needed. Keyed by [WatchSpec.recentKey] so a stale plan never leaks into another watch.
 */
object LeavePlanHolder {
    private class Entry(val key: String, val plan: LeavePlan)

    @Volatile private var entry: Entry? = null

    fun set(spec: WatchSpec, plan: LeavePlan) { entry = Entry(spec.recentKey, plan) }

    /** The plan in effect: the updated one for this watch, else the one it was started with. */
    fun planFor(spec: WatchSpec): LeavePlan? = entry?.takeIf { it.key == spec.recentKey }?.plan ?: spec.leave

    fun clear() { entry = null }
}
