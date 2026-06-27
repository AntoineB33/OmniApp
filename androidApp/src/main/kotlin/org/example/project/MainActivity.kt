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
import org.example.project.scheduler.sync.AndroidStartupLogin
import org.example.project.scheduler.sync.StartupLogin

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidSchedulerStoreHolder.context = applicationContext
        // Non-interactive launch (account3-deploy-android script): credentials handed in as Intent extras
        // must be staged BEFORE the shared VM is built (SchedulerHolder.ensure) so its auto-login sees them.
        captureStartupLogin()
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

    /**
     * Stages `omniapp_login_user`/`omniapp_login_pass` launch-Intent extras for the shared VM's auto-login.
     * Only the very first signed-in launch needs these — the session is then cached in the on-device DB and
     * restored on later (e.g. boot) launches, which carry no extras.
     */
    private fun captureStartupLogin() {
        val user = intent?.getStringExtra("omniapp_login_user")?.takeIf { it.isNotBlank() }
        val pass = intent?.getStringExtra("omniapp_login_pass")?.takeIf { it.isNotBlank() }
        if (user != null && pass != null) AndroidStartupLogin.creds = StartupLogin(user, pass)
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
