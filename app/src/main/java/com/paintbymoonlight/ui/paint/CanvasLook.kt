package com.paintbymoonlight.ui.paint

import android.graphics.Color
import android.graphics.ColorSpace
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * A color in linear Display P3, where iOS's canvas mixes its colors (Shaders.metal), so a tint
 * or a hatch made here is the one iOS makes. [pack] gives the Display P3 color a Paint draws
 * with `setColor(long)`: exact on a wide-gamut window, converted to sRGB on any other.
 */
class Linear(val r: Float, val g: Float, val b: Float) {
    fun mix(to: Linear, t: Float) = Linear(r + (to.r - r) * t, g + (to.g - g) * t, b + (to.b - b) * t)
    operator fun times(k: Float) = Linear(r * k, g * k, b * k)
    val luminance get() = 0.2126f * r + 0.7152f * g + 0.0722f * b

    fun pack(alpha: Float = 1f): Long =
        Color.pack(encode(r), encode(g), encode(b), alpha.coerceIn(0f, 1f), P3)

    companion object {
        val P3: ColorSpace = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        private val SRGB: ColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)

        /** A packed color of any space, as linear Display P3. */
        fun of(color: Long): Linear {
            val p3 = Color.convert(color, P3)
            return Linear(decode(Color.red(p3)), decode(Color.green(p3)), decode(Color.blue(p3)))
        }

        /** An sRGB 0xRRGGBB, as linear Display P3. */
        fun srgb(hex: Int) = of(Color.pack(((hex shr 16) and 0xFF) / 255f, ((hex shr 8) and 0xFF) / 255f, (hex and 0xFF) / 255f, 1f, SRGB))

        /** Display P3 shares sRGB's transfer curve. */
        fun decode(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        fun encode(c: Float): Float {
            val v = c.coerceIn(0f, 1f)
            return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1 / 2.4f) - 0.055f
        }
    }
}

/**
 * How the painting draws, from iOS's `CanvasPalette`, `ClassicLook`, `ColoringBookLook` and
 * `CanvasLook`: the sheet is its own warm paper and ink in either appearance, the backdrop
 * round it the app's paper; lines finer and lighter zoomed out; numbers in the ink, half
 * strong, the paint in hand's nearly whole. Sizes are in points (dp), opacities 0…1.
 */
object CanvasLook {
    /** The sheet (design token `paper`, #F4EFE6) and its ink (#1E1A22). */
    val paper = Linear.srgb(0xF4EFE6)
    val ink = Linear.srgb(0x1E1A22)
    /** The sheet's drop shadow, stronger on the dark backdrop where it would vanish. */
    fun shadowOpacity(dark: Boolean) = if (dark) 0.55f else 0.16f

    /** The classic line: its opacity, as a fraction of the full ink, and its width, `depth` zoom doublings in. */
    const val outlineOpacity = 0.62f
    fun classicStrength(depth: Float) = min(1f, 0.7f + 0.15f * depth)
    fun classicWidth(depth: Float) = min(1.0f, 0.5f + 0.22f * depth)
    /** The paint in hand's cells are outlined this much wider, and 1.3× as strong. */
    const val selectedExtraWidth = 0.55f

    /** A coloring book's line: 1.5 pt fitted, growing far slower than the drawing as it zooms. */
    fun bookWidth(depth: Float) = 1.5f + 0.7f * depth.coerceAtLeast(0f)

    /** Numbers fade in between these on-screen sizes, and grow no bigger than [numberMax]. */
    const val numberFadeFrom = 6.5f
    const val numberFadeTo = 8.5f
    const val numberMax = 22f
    /** A number tapped with the wrong paint pops up to at least this, for [bumpSeconds]. */
    const val numberBumped = 16f
    const val bumpSeconds = 1.4f
    const val numberOpacity = 0.5f
    const val selectedNumberOpacity = 0.9f

    /** The hatch: a line every 6 pt, 1.2 pt wide, the hand's paint at most this luminance. */
    const val hatchPeriod = 6f
    const val hatchLine = 1.2f
    const val hatchCeiling = 0.4f

    /** How a fresh pick glides in: 1 at once, fading to 0 (`highlightPaper`'s intro). */
    fun intro(since: Float) = exp(-since * 2.5f)
    /** How far the hatch has glided along (points). */
    fun hatchPhase(since: Float) = 6f * (1 - exp(-since * 1.8f))

    /** The unpainted cells of the paint in hand: the paper tinted with it. */
    fun tint(paint: Linear, since: Float) = paper.mix(paint, 0.14f + 0.12f * intro(since))

    /** The hatch's ink: the paint darkened until it reads on the paper, part way from the paper. */
    fun hatch(paint: Linear, since: Float): Linear {
        val lum = paint.luminance
        val ink = if (lum > hatchCeiling) paint * (hatchCeiling / lum) else paint
        return paper.mix(ink, 0.55f + 0.25f * intro(since))
    }

    /**
     * The hint's beat: three throbs a 0.66 s apart, dying away over 2 s; under Reduce Motion
     * ([still]) a light that only fades.
     */
    fun pulse(t: Float, still: Boolean): Float {
        if (t < 0f || t > 2f) return 0f
        val wave = if (still) 1f else 0.5f - 0.5f * kotlin.math.cos(t * 6.2831853f / 0.66f)
        return wave * (1 - t / 2)
    }

    /** A drag's brush: 11 pt on screen, at most this many canvas units (iOS's `maxBrushRadius`). */
    const val brushRadius = 11f
    const val maxBrushRadius = 96f

    /** A fill spreads this long: a little longer the bigger it is on screen ([reach] in points). */
    fun fillSeconds(reach: Float) = (0.2f + reach / 700f).coerceIn(0.25f, 0.6f)
    /** The fill that finishes the painting lingers. */
    const val lastFillSlowdown = 1.5f
    /** Wet paint reads a little deeper, settling over this long once it has spread. */
    const val wetSettle = 0.7f
    const val unpaintSeconds = 0.25f

    /** A finished color's gloss: it sweeps once its last fill has landed, this long. */
    const val shineDelay = 0.35f
    const val shineSeconds = 1.1f
}
