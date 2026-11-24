package com.paintbymoonlight.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paintbymoonlight.core.PaintingLength

/** The painter's settings, as state Compose reads; setting one saves it. */
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE).also {
        // Haptics follow the system's touch feedback setting now (View.performHapticFeedback honors it).
        if (it.contains(RETIRED_HAPTICS)) it.edit().remove(RETIRED_HAPTICS).apply()
    }

    private var zen by mutableStateOf(prefs.getBoolean(ZEN, false))
    private var sound by mutableStateOf(prefs.getBoolean(SOUNDS, true))
    private var length by mutableStateOf(
        runCatching { PaintingLength.valueOf(prefs.getString(LENGTH, null) ?: "") }.getOrDefault(PaintingLength.Relaxed))

    /** After each fill the canvas drifts to the next area of the paint in hand (iOS's Zen Mode). */
    var zenMode: Boolean
        get() = zen
        set(on) { zen = on; prefs.edit().putBoolean(ZEN, on).apply() }

    /** The painting's sounds (iOS's Settings › Sounds; on unless turned off). */
    var sounds: Boolean
        get() = sound
        set(on) { sound = on; prefs.edit().putBoolean(SOUNDS, on).apply() }

    var paintingLength: PaintingLength
        get() = length
        set(value) { length = value; prefs.edit().putString(LENGTH, value.name).apply() }

    private companion object {
        const val ZEN = "zenMode"
        const val SOUNDS = "sounds"
        const val LENGTH = "paintingLength"
        const val RETIRED_HAPTICS = "haptics"
    }
}
