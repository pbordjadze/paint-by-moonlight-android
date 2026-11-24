package com.paintbymoonlight.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.paintbymoonlight.core.PaintingLength

/** The painter's settings, as state Compose reads; setting one saves it. */
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private var zen by mutableStateOf(prefs.getBoolean(ZEN, false))
    private var length by mutableStateOf(
        runCatching { PaintingLength.valueOf(prefs.getString(LENGTH, null) ?: "") }.getOrDefault(PaintingLength.Relaxed))
    private var feel by mutableStateOf(prefs.getBoolean(HAPTICS, true))

    /** After each fill the canvas drifts to the next area of the paint in hand (iOS's Zen Mode). */
    var zenMode: Boolean
        get() = zen
        set(on) { zen = on; prefs.edit().putBoolean(ZEN, on).apply() }

    var paintingLength: PaintingLength
        get() = length
        set(value) { length = value; prefs.edit().putString(LENGTH, value.name).apply() }

    var haptics: Boolean
        get() = feel
        set(on) { feel = on; prefs.edit().putBoolean(HAPTICS, on).apply() }

    private companion object {
        const val ZEN = "zenMode"
        const val LENGTH = "paintingLength"
        const val HAPTICS = "haptics"
    }
}
