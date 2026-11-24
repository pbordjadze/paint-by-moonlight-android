package com.paintbymoonlight.ui.paint

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.RequiresApi

/**
 * The painting's touch, iOS's `HapticsPlayer` moment for moment: a fill is a press and a swell
 * that grow with the size of what it painted (a drag's fills at most one every 45 ms, a texture
 * and not a buzz); a tap on another paint's area two soft knocks 85 ms apart; a finished color
 * the fill's press, then three rising taps from 0.12 s, 75 ms apart; the finished painting a
 * swell rising for 0.55 s and nine sparkles falling away from 0.6 s; a pick, an undo or a
 * drag's start a light tick.
 *
 * Played as the vibrator's primitives where the phone has them (Android 11+ on most recent
 * phones), as touch feedback (Android 13+ scales or silences it by the phone's touch feedback
 * setting), and only while that setting is on. Without primitives, the system's own touch
 * feedback, which keeps to the setting by itself. Core Haptics' sharpness has no counterpart:
 * the primitive chosen carries it (a click bright, a low tick dull).
 */
class Haptics(private val view: View) {
    private val vibrator: Vibrator? = run {
        val context = view.context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }?.takeIf { it.hasVibrator() }
    private var lastPaint = 0L

    /** The phone's touch feedback setting (on unless the painter turned it off). */
    private val touchFeedbackOn: Boolean
        get() {
            if (!view.isHapticFeedbackEnabled) return false
            // From Android 13 the setting reaches touch-usage vibrations itself (and the key is retired).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
            @Suppress("DEPRECATION")
            return Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
        }

    /** A composition, where the phone has every one of [primitives]. */
    @SuppressLint("WrongConstant") // Each caller passes Composition.PRIMITIVE_ constants.
    private fun composition(vararg primitives: Int): VibrationEffect.Composition? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val v = vibrator ?: return null
        if (!v.areAllPrimitivesSupported(*primitives)) return null
        return VibrationEffect.startComposition()
    }

    /** Plays [composition] as touch feedback. */
    private fun play(composition: VibrationEffect.Composition) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(composition.compose(), VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(composition.compose())
        }
    }

    /** The system's touch feedback, the fallback. */
    private fun system(constant: Int) {
        view.performHapticFeedback(constant)
    }

    /** The fill's press: a click, firmer for a bigger area. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun VibrationEffect.Composition.press(s: Float) =
        addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.45f + 0.4f * s)

    /** A fill: [size] is how big it was against the painting (0…1). */
    fun paint(size: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPaint < 45) return
        lastPaint = now
        if (!touchFeedbackOn) return
        val s = size.coerceIn(0f, 1f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            composition(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)?.let {
                it.press(s)
                // The swell, dying away as the paint spreads.
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.25f + 0.35f * s, 15)
                play(it)
                return
            }
        }
        system(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A tap on another paint's area. */
    fun reject() {
        if (!touchFeedbackOn) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            composition(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)?.let {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.5f)
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.35f, 85)
                play(it)
                return
            }
        }
        system(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }

    /**
     * The fill that finishes a paint: its press, then three rising taps (in one pattern, since a
     * second would cut the first off). A primitive's delay counts from the end of the one before,
     * a click lasting about 12 ms, so the taps land at about 0.12, 0.195 and 0.27 s.
     */
    fun colorComplete(size: Float) {
        lastPaint = SystemClock.uptimeMillis()
        if (!touchFeedbackOn) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            composition(VibrationEffect.Composition.PRIMITIVE_CLICK)?.let {
                it.press(size.coerceIn(0f, 1f))
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f, 108)
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f, 63)
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.95f, 63)
                play(it)
                return
            }
        }
        system(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }

    /** The whole painting is finished: the last fill's press, a swell, then sparkles falling away. */
    fun celebrate(size: Float) {
        lastPaint = SystemClock.uptimeMillis()
        if (!touchFeedbackOn) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            composition(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            )?.let {
                it.press(size.coerceIn(0f, 1f))
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.6f)
                for (i in 0 until 9) {
                    // About 90 ms apart, each up to 30 ms late, as iOS scatters them.
                    val delay = if (i == 0) 60 else 80 + (i * 7) % 20
                    it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.9f - 0.08f * i, delay)
                }
                play(it)
                return
            }
        }
        system(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
    }

    /** A light detent: picking a paint, an undo, a drag's brush starting. */
    fun tick() {
        if (!touchFeedbackOn) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            composition(VibrationEffect.Composition.PRIMITIVE_TICK)?.let {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)
                play(it)
                return
            }
        }
        system(HapticFeedbackConstants.CLOCK_TICK)
    }
}
