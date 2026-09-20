package org.example.project.scheduler.model

import org.example.project.scheduler.platform.AlertSound

/**
 * PRD §11/§14/§18: **how one alarm, timer or reminder makes itself heard** — the four independent channels
 * the user switches per row, and which of the [AlertSound]s the sound channel rings with.
 *
 * One type for all three because it is one question asked of all three: an alarm, a timer and a reminder
 * differ in *when* they are due (a time of day on a set of weekdays / one stored instant / a recurrence), and
 * in nothing else once they are. A second spelling of "does this one vibrate" per kind is exactly the drift
 * this codebase keeps deleting — so the row holds an [AlertSettings], the ring reads it, and
 * `SchedulerEngine.notifyUser` reads the same one.
 *
 * **The four are independent**, and each is a different way of reaching the user: [sound] carries to another
 * room, [vibrate] to a pocket, [notification] to a screen being looked at, [voice] to one that is not. A row
 * with all four off is deliberately allowed — that is what "no alert, just the tag on the calendar" is, and
 * for a reminder it is a perfectly ordinary thing to want ([announces] is then false and nothing fires).
 *
 * **They are gated, not overridden, by the account's switches** (PRD §11): the left-menu **Notifications**
 * switch silences every channel of every row, and the **Voice** switch silences [voice] alone. These say what
 * *this row* is allowed to do when the account is not muted; they can never make a muted account speak.
 *
 * Authoritative user data (CLAUDE.md § *State*) — it rides with the row it belongs to, is persisted and
 * synced with it, and is never re-derived.
 */
data class AlertSettings(
    /** Play [tone] out loud on the ringing device. */
    val sound: Boolean = true,
    /**
     * Which of the small set of sounds [sound] rings with. Ignored (but kept) while [sound] is off, so
     * turning the sound back on restores the one the row was set to rather than the default.
     */
    val tone: AlertSound = AlertSound.DEFAULT,
    /** Say the alert aloud — the spoken half of the one notification funnel (PRD §11). */
    val voice: Boolean = true,
    /** Post the OS notification. */
    val notification: Boolean = true,
    /** Vibrate the phone alongside the sound. Ignored on a desktop, which cannot. */
    val vibrate: Boolean = true,
) {
    /**
     * Whether this row reaches the user at all. False = every channel is off, so the engine has nothing to
     * fire and an alarm holding it is not schedulable ([AlarmEntry.schedulable]) — a row that would ring
     * nowhere is not worth arming an OS alarm slot for.
     */
    val announces: Boolean
        get() = sound || voice || notification || vibrate

    /**
     * Whether the **ring seam** has anything to do — the sound, the vibration, or both. The notification and
     * the voice go through `notifyUser` instead, so a row with only those two never touches the audio line.
     */
    val rings: Boolean
        get() = sound || vibrate

    /**
     * The channels that are on, as one short phrase for the Diagnostics timeline — `Guitar+voice+notification`,
     * or `silent` when nothing is. What a ring actually did is the first thing asked of a cue that seemed not
     * to fire, and the row's own switches are now part of that answer.
     */
    fun describe(): String {
        val on = buildList {
            if (sound) add(tone.name)
            if (voice) add("voice")
            if (notification) add("notification")
            if (vibrate) add("vibrate")
        }
        return if (on.isEmpty()) "silent" else on.joinToString("+")
    }

    companion object {
        /**
         * What a new **alarm or timer** does: everything. An alarm exists to be impossible to miss, so it
         * starts out reaching the user every way it can and the user turns channels off from there.
         */
        val RING: AlertSettings = AlertSettings()

        /**
         * What a new **reminder** does: it is announced and spoken, but it does not ring or buzz. A reminder
         * is a tag on the day — *the plants want watering* — not an alarm clock: it says so when the moment
         * comes round and leaves it at that. A user who wants one to ring turns its sound on.
         */
        val REMINDER: AlertSettings = AlertSettings(sound = false, vibrate = false)

        /**
         * How long a reminder's ring lasts, in seconds — as long as a freshly added alarm rings for.
         *
         * A reminder row has no *Rings for* field, unlike an alarm's and a timer's, and that is the
         * difference between the two things rather than an omission: an alarm is meant to go on until it is
         * dealt with, so its length is the user's business; a reminder says its piece at its moment and
         * stops. One constant, read by the engine wherever a reminder rings.
         */
        const val REMINDER_SOUND_SECONDS: Int = 3
    }
}
