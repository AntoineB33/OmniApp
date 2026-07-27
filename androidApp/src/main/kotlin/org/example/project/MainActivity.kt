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

    // Lint's InvalidFragmentVersionForActivityResult only applies to FragmentActivity/Fragment (old
    // androidx.fragment had a broken onRequestPermissionsResult). This is a ComponentActivity using
    // androidx.activity 1.13.0's registerForActivityResult directly, so the check is a false positive.
    @Suppress("InvalidFragmentVersionForActivityResult")
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidSchedulerStoreHolder.context = applicationContext
        // Non-interactive launch (account3-deploy-android script): credentials handed in as Intent extras
        // must be staged BEFORE the shared VM is built (SchedulerHolder.ensure) so its auto-login sees them.
        captureStartupLogin()
        captureBreakOverrides()
        maybeRequestNotificationPermission()
        maybePromptKeepAliveOnce()

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
     * PRD §15 / ARCHITECTURE.md §8, scenario #3: this phone became the active app — (re)claim the account's
     * last-logged-in phone so the previous phone is told to cancel its scheduled pause-end cue and only this
     * phone speaks. Done on every resume (not just onCreate) because the foreground service keeps the process
     * — and the already-started engine — alive across resumes, so onCreate alone would miss a resume-handoff.
     * Idempotent: re-claiming the same phone fires no cancel push (the DB trigger keys on a device-id change).
     */
    override fun onResume() {
        super.onResume()
        SchedulerHolder.ensure(applicationContext).engine.onAppForegrounded()
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

    /**
     * Debug fast-break override for testing the pause-cue voice message on real phones: the deploy script
     * passes `--es omniapp_break_duration_ms 5000` (the 5-min break's length) and/or
     * `--es omniapp_break_interval_ms 5000` (how long after the previous pause it comes due) so a break — and
     * the cron's server→phone cue — arrives in seconds. `--es omniapp_break_pause_threshold_ms` decouples
     * the qualifying-pause length from the drawn length (place a short break only after a long pause). Must be
     * applied BEFORE [SchedulerHolder.ensure] builds the VM (which seeds the screen breaks). Absent extras
     * leave production timings.
     *
     * [AndroidDebugFlagStore] also REMEMBERS them for the install, so a reboot (which restarts the engine
     * through the service with no Activity and no extras) keeps the same 5-min-break rules the server-side
     * `break_config.length_ms` was set to.
     */
    private fun captureBreakOverrides() {
        AndroidDebugFlagStore.captureFromLaunchIntent(this, intent)
    }

    /** PRD §11: on API 33+ the POST_NOTIFICATIONS runtime grant is required or notifications are dropped. */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * PRD §15, ONE-TIME at first startup: the foreground service must keep the engine running (writing this
     * device's `device_heartbeat`) while the phone is unlocked/backgrounded, so ask the OS to exempt the app
     * from battery optimization (Doze would stop the heartbeat), and on OEM ROMs with an autostart allowlist
     * (MIUI/HyperOS etc.) open that settings screen too. Both are best-effort — a denial changes nothing
     * functionally except that the OS may stop the heartbeat sooner — and neither is ever asked again (flag in
     * SharedPreferences).
     */
    private fun maybePromptKeepAliveOnce() {
        // The debug fast-break deploy scripts grant the Doze exemption (and attempt the MIUI AUTO_START
        // appops op) over adb after each reinstall, then pass this extra so we don't ALSO pop the OS
        // battery dialog + the MIUI autostart app-list screen on every launch (the uninstall/reinstall
        // wipes the "already prompted" flag, so without this the prompt fires every deploy). The release
        // deploy carries no such extra, so genuine first-run installs still get the prompt.
        if (intent?.getStringExtra("omniapp_skip_keepalive_prompt") == "true") return
        val prefs = getSharedPreferences("omniapp_prompts", MODE_PRIVATE)
        if (prefs.getBoolean("keep_alive_prompted", false)) return
        prefs.edit().putBoolean("keep_alive_prompted", true).apply()

        val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }
        }
        // OEM autostart allowlist (no public API): try the known settings activities, first match wins.
        val autostartIntents = listOf(
            android.content.Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            android.content.Intent().setClassName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            android.content.Intent().setClassName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            android.content.Intent().setClassName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
        )
        for (autostart in autostartIntents) {
            if (runCatching { startActivity(autostart); true }.getOrDefault(false)) break
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
