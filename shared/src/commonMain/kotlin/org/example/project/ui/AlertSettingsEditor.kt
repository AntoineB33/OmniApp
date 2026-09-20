package org.example.project.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.example.project.scheduler.model.AlertSettings
import org.example.project.scheduler.platform.AlertSound

/**
 * PRD §11/§14/§18: the **alert block** — the four channels a row announces itself through, and which of the
 * small set of sounds its sound channel rings with.
 *
 * ONE editor, used by the Alarms window's two sections and by the reminders manager, because it is one
 * question ([AlertSettings]) asked of all three. A second copy of these four toggles is exactly the drift
 * CLAUDE.md's *one rule, one funnel* is about — and here it would be visible, as two rows of switches that
 * slowly stopped agreeing about what "Vibrate" means.
 *
 * The toggles are **chips rather than `Switch`es**: four switches with their labels take more width than any
 * of these rows has, and a chip reads its state from its own fill, so the whole block fits on one line beside
 * the sound's name. Each is one press = one change, so each is its own structural History Unit (never
 * absorbed into a text field's edit session — the callers pass no coalesce key for them).
 *
 * [enabled] false greys the block out without hiding it, for a row that cannot announce anything anyway.
 */
@Composable
fun AlertSettingsEditor(
    alert: AlertSettings,
    onChange: (AlertSettings) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Shown before the chips; null for a row too narrow to spare the width. */
    label: String? = "Alert",
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (label != null) {
            Text(text = label, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(2.dp))
        }
        AlertChannelChip(
            text = "Sound",
            on = alert.sound,
            enabled = enabled,
            onToggle = { onChange(alert.copy(sound = it)) },
        )
        // Which sound, offered only where there is a sound to choose: a picker beside a switched-off channel
        // invites the user to set something that does nothing. The choice itself is KEPT while the sound is
        // off (it lives on the row, not here), so switching back on restores it.
        if (alert.sound) {
            AlertSoundDropdown(
                tone = alert.tone,
                enabled = enabled,
                onSelect = { onChange(alert.copy(tone = it)) },
            )
        }
        AlertChannelChip(
            text = "Voice",
            on = alert.voice,
            enabled = enabled,
            onToggle = { onChange(alert.copy(voice = it)) },
        )
        AlertChannelChip(
            text = "Notify",
            on = alert.notification,
            enabled = enabled,
            onToggle = { onChange(alert.copy(notification = it)) },
        )
        // PRD §18: ignored by a device that cannot vibrate (every desktop), which is why it is offered here
        // all the same — the row is the account's and it is the phones the flag is for.
        AlertChannelChip(
            text = "Vibrate",
            on = alert.vibrate,
            enabled = enabled,
            onToggle = { onChange(alert.copy(vibrate = it)) },
        )
    }
}

/** One channel, on or off. Filled = the channel fires; outlined = it does not. */
@Composable
private fun AlertChannelChip(
    text: String,
    on: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val active = on && enabled
    val outline =
        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) outline else MaterialTheme.colorScheme.surface)
            .border(1.dp, outline, RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable { onToggle(!on) } else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The small set of sounds ([AlertSound]), as a dropdown naming the one this row rings with. */
@Composable
private fun AlertSoundDropdown(tone: AlertSound, enabled: Boolean, onSelect: (AlertSound) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val outline =
        if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, outline, RoundedCornerShape(10.dp))
                .then(if (enabled) Modifier.clickable { expanded = true } else Modifier)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = tone.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "▾",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AlertSound.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = {
                        onSelect(entry)
                        expanded = false
                    },
                )
            }
        }
    }
}
