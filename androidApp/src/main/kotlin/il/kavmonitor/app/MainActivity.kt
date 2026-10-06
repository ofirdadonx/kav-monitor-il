package il.kavmonitor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val controller = app.graph.controller
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!controller.back()) finish()
            }
        })
        handleIntent(intent)
        setContent { App(controller) }
    }

    override fun onStart() {
        super.onStart()
        app.launcher.permissionGate = { onDone -> ensureNotificationPermission(onDone) }
    }

    override fun onStop() {
        app.launcher.permissionGate = null
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
    }
}
