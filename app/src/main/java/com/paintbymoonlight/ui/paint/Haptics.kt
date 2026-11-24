package com.paintbymoonlight.ui.paint

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The painting's touch, after iOS's `HapticsPlayer`: a fill is a tap that swells with the size
 * of what it painted, a rejected tap two soft knocks, a finished color three rising taps, the
 * finished painting a swell and a scatter of sparkles, a pick or an undo a light tick. Played
 * as the vibrator's primitives where it has them (Android 11+ on most recent phones); else the
 * system's own touch feedback, which also keeps to the phone's touch feedback setting.
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

    /** Primitives, where the phone has them all and the painter has touch feedback on. */
    private fun composition(vararg primitives: Int): VibrationEffect.Composition? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val v = vibrator ?: return null
        if (!view.isHapticFeedbackEnabled || !v.areAllPrimitivesSupported(*primitives)) return null
        return VibrationEffect.startComposition()
    }

    private fun play(composition: VibrationEffect.Composition) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vibrator?.vibrate(composition.compose())
    }

    /** A fill: [size] is how big it was against the painting (0…1). */
    fun paint(size: Float) {
        val s = size.coerceIn(0f, 1f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            composition(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_QUICK_FALL)?.let {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.45f + 0.4f * s)
                // The swell, for a fill big enough to feel one.
                if (s > 0.15f) it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.25f + 0.35f * s, 15)
                play(it)
                return
            }
        }
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A tap on another paint's area. */
    fun reject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            composition(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)?.let {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.5f)
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.35f, 85)
                play(it)
                return
            }
        }
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    /** Every area of a paint is painted. */
    fun colorComplete() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            composition(VibrationEffect.Composition.PRIMITIVE_CLICK)?.let {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f, 120)
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f, 60)
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.95f, 60)
                play(it)
                return
            }
        }
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /** The whole painting is finished: a swell, then sparkles falling away. */
    fun celebrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            composition(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, VibrationEffect.Composition.PRIMITIVE_TICK)?.let {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.6f)
                for (i in 0 until 9) {
                    it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.9f - 0.08f * i, if (i == 0) 60 else 80 + (i * 7) % 20)
                }
                play(it)
                return
            }
        }
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    }

    /** A light detent: picking a paint, an undo. */
    fun tick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            composition(VibrationEffect.Composition.PRIMITIVE_TICK)?.let {
                it.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)
                play(it)
                return
            }
        }
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
