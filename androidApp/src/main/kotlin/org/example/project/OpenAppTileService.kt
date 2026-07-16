package org.example.project

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Quick Settings tile that opens the app. Stateless — no active/inactive toggle, just a launcher
 * shortcut in the notification shade. The user adds it via the QS panel's edit (pencil) mode.
 */
class OpenAppTileService : TileService() {

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { launchApp() } else launchApp()
    }

    private fun launchApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ rejects the Intent overload; the PendingIntent variant is required.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
