package org.example.project

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import org.example.project.scheduler.persistence.AndroidSchedulerStoreHolder

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidSchedulerStoreHolder.context = applicationContext
        maybeRequestNotificationPermission()

        // The foreground service owns the single shared scheduler (VM + engine); start it and render that
        // same instance, so the UI and the background service never run two competing copies of the state.
        val host = SchedulerHolder.ensure(applicationContext)
        SchedulerService.start(applicationContext)

        setContent {
            // store = null: the host already carries the live store-backed VM, so App must not open a second DB.
            App(store = null, host = host)
        }
    }

    /** PRD §11: on API 33+ the POST_NOTIFICATIONS runtime grant is required or notifications are dropped. */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
