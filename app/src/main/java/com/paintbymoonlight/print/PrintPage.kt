package com.paintbymoonlight.print

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.paintbymoonlight.core.Scene
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.pow

/**
 * What every printed page draws, in page points with y down: the header, the template's lines and
 * numbers, the color key and the finished picture, as the iOS app's `PDFExporter` draws them.
 * Shared by the printable pages ([TemplatePrint]) and One Big Sheet ([SheetDrawing]).
 */
internal object PrintPage {
    const val MARGIN = Sheets.MARGIN
    const val HEADER = Sheets.HEADER
    const val KEY_GAP = 12f
    private const val KEY_NAME_INSET = 36f
    /** The template's lines, in points (iOS's printable style). */
    private const val OUTLINE = 0.4f
    const val APP_NAME = "Paint by Moonlight"
    const val WHITE = 0xFFFFFFFF.toInt()
    private val LINE = rgb(0.45f, 0.47f, 0.5f)
    private val NUMBER = rgb(0.35f, 0.37f, 0.4f)

    fun areasText(n: Int) = "$n ${if (n == 1) "area" else "areas"}"
    fun colorsText(n: Int) = "$n ${if (n == 1) "color" else "colors"}"
    fun statsText(scene: Scene) = "${colorsText(scene.paints.size)} · ${areasText(scene.regions.size)}"

    /** Draws a page's title and [detail] across the top of [content]; returns the body below them. */
    fun header(canvas: Canvas, content: RectF, title: String, detail: String): RectF {
        val titlePaint = text(17f, bold = true, color = gray(0.1f))
        canvas.drawText(ellipsized(title, titlePaint, content.width() * 0.7f), content.left, content.top + 14f, titlePaint)
        canvas.drawText(detail, content.right, content.top + 13f, text(9f, color = gray(0.45f), align = Paint.Align.RIGHT))
        canvas.drawLine(content.left, content.top + 24f, content.right, content.top + 24f, stroke(gray(0.85f), 0.5f))
        return RectF(content.left, content.top + HEADER, content.right, content.bottom)
    }

    /** The app's name at a page's foot. */
    fun footer(canvas: Canvas, content: RectF) {
        canvas.drawText(APP_NAME, content.left, content.bottom + 16f, text(7.5f, color = gray(0.6f)))
    }

    /**
     * The printed size of each number (canvas units): the scene's, which keeps to its area's room,
     * held to [cap] where a larger number would stand out (`LabelSizing.sheetMaximumFraction`) or
     * not fit a sheet's overlap.
     */
    fun numberSize(scene: Scene, labelSize: Float, cap: Float = defaultCap(scene)): Float = minOf(labelSize, cap)

    fun defaultCap(scene: Scene): Float = Sheets.MAXIMUM_NUMBER_FRACTION * max(scene.width, scene.height)

    /**
     * The template at [scale] points per canvas unit with its top-left at ([left], [top]): each
     * area washed in its paint when [hints], every line, the line art inside areas, and the
     * numbers when [numbers]. Vectors throughout.
     */
    fun template(
        canvas: Canvas, scene: Scene, left: Float, top: Float, scale: Float,
        numbers: Boolean = true, hints: Boolean = false, numberCap: Float = defaultCap(scene),
    ) {
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        if (hints) {
            // Stroked a hair as well as filled, so neighbours meet without a seam of paper.
            val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 0.3f / scale }
            val washes = scene.paints.map { sketchColor(it.color) }
            for (region in scene.regions) {
                wash.color = washes.getOrElse(region.paint) { WHITE }
                canvas.drawPath(region.path, wash)
            }
        }
        val line = stroke(LINE, OUTLINE / scale).apply {
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(scene.lines, line)
        if (!scene.strokes.isEmpty) canvas.drawPath(scene.strokes, line)
        if (numbers) {
            val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = NUMBER
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            }
            for (label in scene.labels) {
                val paint = scene.regions.getOrNull(label.region)?.paint ?: continue
                val size = numberSize(scene, label.fontSize, numberCap)
                number.textSize = size
                canvas.drawText((paint + 1).toString(), label.x, label.y + size * 0.36f, number)
            }
        }
        canvas.restore()
    }

    /** The finished picture, every area in its paint, in [rect]. */
    fun painting(canvas: Canvas, scene: Scene, rect: RectF) {
        val s = rect.width() / scene.width
        canvas.save()
        canvas.translate(rect.left, rect.top)
        canvas.scale(s, s)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = 0.3f / s }
        for (region in scene.regions) {
            fill.color = scene.paints.getOrNull(region.paint)?.color ?: continue
            canvas.drawPath(region.path, fill)
        }
        canvas.restore()
    }

    /**
     * How the key's entries are laid out, column by column: the largest scale (1 down to 0.6),
     * then the fewest columns (3–5, or 4–6 on a wide page) whose rows fit and whose widest entry
     * fits a column, as the iOS app's `PDFExporter.keyLayout`.
     */
    class KeyLayout(val scale: Float, val columnWidth: Float, val rowHeight: Float, val rows: Int, val count: Int) {
        val height: Float get() = if (count == 0) 0f else rows * rowHeight
    }

    fun keyLayout(scene: Scene, rect: RectF): KeyLayout {
        val count = scene.paints.size
        val widest = KEY_NAME_INSET + (scene.paints.maxOfOrNull { text(8f, bold = true).measureText(it.name) } ?: 0f)
        val base = if (rect.width() > 600f) 4 else 3
        fun layout(scale: Float, columns: Int) = KeyLayout(
            scale = scale,
            columnWidth = (rect.width() - KEY_GAP * (columns - 1)) / columns,
            rowHeight = 24f * scale,
            rows = max(1, (count + columns - 1) / columns),
            count = count,
        )
        for (scale in floatArrayOf(1f, 0.9f, 0.8f, 0.7f, 0.6f)) {
            for (columns in base..base + 2) {
                val candidate = layout(scale, columns)
                if (candidate.height <= rect.height() && widest * scale <= candidate.columnWidth) return candidate
            }
        }
        return layout(0.6f, base + 2)
    }

    /**
     * The color key in [rect] as [key] lays it: number, a dot of the paint and its name, with its
     * hex value and area count below. Returns the height it took.
     */
    fun legend(canvas: Canvas, scene: Scene, key: KeyLayout, rect: RectF): Float {
        val s = key.scale
        val counts = scene.regionsByPaint.map { it.size }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        // A ring dark enough that the palest paints still show on white.
        val ring = stroke(gray(0f, 0.35f), 0.5f)
        val numberText = text(8f * s, bold = true, color = gray(0.12f), align = Paint.Align.RIGHT)
        val nameText = text(8f * s, bold = true, color = gray(0.12f))
        val detailText = text(6f * s, color = gray(0.5f))
        val textWidth = key.columnWidth - KEY_NAME_INSET * s
        scene.paints.forEachIndexed { i, paint ->
            val x = rect.left + (i / key.rows) * (key.columnWidth + KEY_GAP)
            val y = rect.top + (i % key.rows) * key.rowHeight
            canvas.drawText("${i + 1}", x + 18f * s, y + 10f * s, numberText)
            dot.color = paint.color
            val radius = 4.5f * s
            canvas.drawCircle(x + 26.5f * s, y + 7f * s, radius, dot)
            canvas.drawCircle(x + 26.5f * s, y + 7f * s, radius, ring)
            val textX = x + KEY_NAME_INSET * s
            canvas.drawText(ellipsized(paint.name, nameText, textWidth), textX, y + 10f * s, nameText)
            val hex = "#%06X".format(paint.color and 0xFFFFFF)
            canvas.drawText(ellipsized("$hex · ${areasText(counts.getOrElse(i) { 0 })}", detailText, textWidth), textX, y + 19f * s, detailText)
        }
        return key.height
    }

    /** The largest rect of [aspect] (width / height) in [rect], centred across and at its top. */
    fun fit(aspect: Float, rect: RectF): RectF {
        var w = rect.width()
        var h = w / aspect
        if (h > rect.height()) {
            h = rect.height(); w = h * aspect
        }
        val left = rect.centerX() - w / 2
        return RectF(left, rect.top, left + w, rect.top + h)
    }

    fun text(size: Float, bold: Boolean = false, color: Int = 0xFF000000.toInt(), align: Paint.Align = Paint.Align.LEFT) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            this.color = color
            textAlign = align
        }

    fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.color = color
    }

    fun ellipsized(string: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(string, paint, width, TextUtils.TruncateAt.END).toString()

    fun gray(white: Float, alpha: Float = 1f): Int = rgb(white, white, white, alpha)

    fun rgb(r: Float, g: Float, b: Float, a: Float = 1f): Int {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }

    /**
     * A paint's Color Hints wash: its OKLab lightness lifted toward the paper and its chroma
     * halved (the iOS app's `TemplateRasterizer.sketchColor`), so the picture shows through soft.
     */
    fun sketchColor(color: Int): Int {
        fun linear(c: Int): Float {
            val v = c / 255f
            return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        }
        fun encoded(v: Float): Float {
            val c = v.coerceIn(0f, 1f)
            return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1 / 2.4f) - 0.055f
        }
        val r = linear(color shr 16 and 0xFF)
        val g = linear(color shr 8 and 0xFF)
        val b = linear(color and 0xFF)
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        val lightness = (0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s).coerceIn(0f, 1f)
        val a = (1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s) * 0.5f
        val bb = (0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s) * 0.5f
        val lw = 0.68f + 0.30f * lightness
        val l2 = (lw + 0.3963377774f * a + 0.2158037573f * bb).pow(3)
        val m2 = (lw - 0.1055613458f * a - 0.0638541728f * bb).pow(3)
        val s2 = (lw - 0.0894841775f * a - 1.2914855480f * bb).pow(3)
        return rgb(
            encoded(4.0767416621f * l2 - 3.3077115913f * m2 + 0.2309699292f * s2),
            encoded(-1.2684380046f * l2 + 2.6097574011f * m2 - 0.3413193965f * s2),
            encoded(-0.0041960863f * l2 - 0.7034186147f * m2 + 1.7076147010f * s2),
        )
    }
}
