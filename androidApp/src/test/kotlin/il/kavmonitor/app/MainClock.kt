package il.kavmonitor.app

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Robolectric's paused main looper has a fake clock: coroutines delayed on Dispatchers.Main (the live route view refresh)
 * never fire unless the clock is advanced. These helpers advance it in step with real time, so Main delays and real-time
 * work (HTTP / the service on Dispatchers.Default) progress together – and "quiet" windows really let refreshes come due.
 */
fun ComposeTestRule.waitReal(timeoutMs: Long, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (true) {
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        waitForIdle()
        if (cond()) return
        if (System.currentTimeMillis() >= end) {
            runCatching { java.io.File("/tmp/semantics_dump.txt").writeText(onRoot(useUnmergedTree = true).printToString(maxDepth = 40)) }
        }
        check(System.currentTimeMillis() < end) {
            val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<TestApp>()
            val rl = runCatching { app.graph.controller.routeLive }.getOrNull()
            "condition not met within $timeoutMs ms; http=${TestHttp.snapshot().groupingBy { it.host }.eachCount()} " +
                "last=${TestHttp.snapshot().takeLast(6).map { it.path }} routeLive.refreshing=${rl?.isRefreshing} " +
                "refreshes=${rl?.refreshes} state=${rl?.state?.let { "loading=${it.loading} err=${it.error} snap=${it.snapshot?.buses?.size}" }}"
        }
        Thread.sleep(100)
    }
}

/** Lets [ms] of real AND main-looper time pass. */
fun ComposeTestRule.passTime(ms: Long) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        waitForIdle()
        Thread.sleep(100)
    }
}
