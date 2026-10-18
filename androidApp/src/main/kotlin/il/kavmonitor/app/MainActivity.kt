package il.kavmonitor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import il.kavmonitor.shared.ui.App

class MainActivity : ComponentActivity() {
    private var pendingAfterPermission: (() -> Unit)? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_LONG).show()
        pendingAfterPermission?.invoke()
        pendingAfterPermission = null
    }

    private var pendingLocationResult: ((Boolean) -> Unit)? = null
    private var locationAskedAt = 0L
    private var locationAskUserInitiated = false

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.any { it }
        if (!granted && locationAskUserInitiated && SystemClock.elapsedRealtime() - locationAskedAt < SUPPRESSED_DIALOG_MS &&
            AndroidLocationProvider.PERMISSIONS.none { shouldShowRequestPermissionRationale(it) }
        ) {
            // The system answered "denied" without showing a dialog (denied twice / "don't ask again"): the user asked
            // explicitly, so open the app's settings page where location can be allowed.
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
        pendingLocationResult?.invoke(granted)
        pendingLocationResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val controller = app.graph.controller
        handleIntent(intent)
        setContent { App(controller) }
    }

    override fun onStart() {
        super.onStart()
        app.launcher.permissionGate = { onDone -> ensureNotificationPermission(onDone) }
        app.locationProvider.permissionRequester = { userInitiated, onResult ->
            pendingLocationResult = onResult
            locationAskedAt = SystemClock.elapsedRealtime()
            locationAskUserInitiated = userInitiated
            locationPermission.launch(AndroidLocationProvider.PERMISSIONS)
        }
        app.graph.controller.setAppVisible(true) // live route view may refresh
    }

    override fun onStop() {
        app.graph.controller.setAppVisible(false) // stop the live route view refresh in the background
        app.launcher.permissionGate = null
        app.locationProvider.permissionRequester = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_WATCH, false) == true) app.graph.controller.openWatch()
    }

    private fun ensureNotificationPermission(onDone: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAfterPermission = onDone
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onDone()
        }
    }

    companion object {
        const val EXTRA_OPEN_WATCH = "open_watch"
        private const val SUPPRESSED_DIALOG_MS = 400L
    }
}
