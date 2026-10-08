package com.example.voicecamera

import android.content.Context

/** The person's saved defaults: how long to wait before starting, and how long to record. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Seconds to wait before a video or photo starts, when the sentence does not say. */
    var delaySeconds: Int
        get() = prefs.getInt(KEY_DELAY, STANDARD_DELAY).coerceIn(0, CommandParser.MAX_DELAY_SECONDS)
        set(value) = prefs.edit().putInt(KEY_DELAY, value.coerceIn(0, CommandParser.MAX_DELAY_SECONDS)).apply()

    /** Seconds a video records for, when the sentence does not say. */
    var durationSeconds: Int
        get() = prefs.getInt(KEY_DURATION, STANDARD_DURATION).coerceIn(1, CommandParser.MAX_DURATION_SECONDS)
        set(value) = prefs.edit().putInt(KEY_DURATION, value.coerceIn(1, CommandParser.MAX_DURATION_SECONDS)).apply()

    fun defaults() = CommandDefaults(delaySeconds, durationSeconds)

    fun apply(change: DefaultsChange) {
        change.delaySeconds?.let { delaySeconds = it }
        change.durationSeconds?.let { durationSeconds = it }
    }

    fun reset() {
        delaySeconds = STANDARD_DELAY
        durationSeconds = STANDARD_DURATION
    }

    companion object {
        const val STANDARD_DELAY = 0
        const val STANDARD_DURATION = CommandParser.DEFAULT_DURATION_SECONDS
        private const val KEY_DELAY = "default_delay_seconds"
        private const val KEY_DURATION = "default_duration_seconds"
    }
}
