package com.paintbymoonlight.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import com.paintbymoonlight.core.Scene
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * One Big Sheet's print file: the blank template whole on one page of the sheet's size, its title
 * above and its color key below, as the iOS app's `PDFExporter.sheet` lays it. The page is laid
 * out as one of letter's width and scaled up to the sheet ([factor]), so the header, the key and
 * the margins keep their proportion to the picture, while the template itself is drawn at the
 * sheet's size, as vectors: its lines, the line art inside areas, and every number. Never the
 * photo, and no paint but the key's swatches.
 */
class SheetDrawing(private val scene: Scene, private val title: String, shortPoints: Int, longPoints: Int) {
    private val wide = scene.width > scene.height

    /** The page in PDF points, turned the picture's way. */
    val pageWidth: Int = if (wide) longPoints else shortPoints
    val pageHeight: Int = if (wide) shortPoints else longPoints

    /** How much larger than on a letter page the type, key and margins draw: grown with the sheet, by a little over half. */
    private val factor = max(1f, min(pageWidth, pageHeight) / LETTER_WIDTH * 0.6f)
    private val content = RectF(0f, 0f, pageWidth / factor, pageHeight / factor).apply { inset(MARGIN, MARGIN) }
    private val counts = scene.regionsByPaint.map { it.size }
    private val key: KeyLayout
    private val keyRect: RectF
    /** Where the template draws, in laid-out units. */
    private val picture: RectF

    init {
        val body = RectF(content.left, content.top + HEADER, content.right, content.bottom)
        val room = RectF(body.left, body.top, body.right, body.top + body.height() * 0.4f)
        val widest = scene.paints.maxOfOrNull { text(8f, bold = true).measureText(it.name) } ?: 0f
        key = KeyLayout.of(scene.paints.size, KEY_NAME_INSET + widest, room)
        val used = key.height
        keyRect = RectF(body.left, body.bottom - used, body.right, body.bottom)
        if (used > 0) body.bottom -= used + KEY_GAP * 2
        picture = fit(scene.width / scene.height, body)
    }

    /** Sheet points per canvas unit of the template. */
    private val pictureScale: Float get() = picture.width() / scene.width * factor

    /** Whether any number prints under its legible floor: an ordinary area's 2.6 pt, a detail area's 2 pt. */
    val printsSmallNumbers: Boolean
        get() = scene.labels.any { label ->
            val floor = if (scene.regions[label.region].isDetail) MINIMUM_DETAIL_NUMBER else MINIMUM_NUMBER
            label.fontSize * pictureScale < floor
        }

    /** The one-page PDF the relay keeps and the printer prints. */
    fun pdf(): ByteArray {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
            draw(page.canvas)
            document.finishPage(page)
            return ByteArrayOutputStream().also { document.writeTo(it) }.toByteArray()
        } finally {
            document.close()
        }
    }

    /** The sheet as it will print, for the screen: [longSide] pixels on its long side, on white. */
    fun image(longSide: Int): Bitmap {
        val k = longSide / max(pageWidth, pageHeight).toFloat()
        val bitmap = Bitmap.createBitmap(
            (pageWidth * k).toInt().coerceAtLeast(1), (pageHeight * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(WHITE)
        canvas.scale(k, k)
        draw(canvas)
        return bitmap
    }

    /** Draws the page on [canvas], whose units are the page's points. */
    fun draw(canvas: Canvas) {
        canvas.save()
        canvas.scale(factor, factor)
        header(canvas)
        if (key.count > 0) legend(canvas)
        template(canvas)
        val frame = stroke(gray(0.75f), 0.5f)
        canvas.drawRect(picture, frame)
        canvas.drawText(APP_NAME, content.left, content.bottom + 16f, text(7.5f, color = gray(0.6f)))
        canvas.restore()
    }

    private fun header(canvas: Canvas) {
        canvas.drawText(ellipsized(title, text(17f, bold = true), content.width() * 0.7f), content.left, content.top + 14f,
            text(17f, bold = true, color = gray(0.1f)))
        val colors = scene.paints.size
        val areas = scene.regions.size
        val detail = "$colors ${if (colors == 1) "color" else "colors"} · ${areasText(areas)}"
        canvas.drawText(detail, content.right, content.top + 13f, text(9f, color = gray(0.45f), align = Paint.Align.RIGHT))
        canvas.drawLine(content.left, content.top + 24f, content.right, content.top + 24f, stroke(gray(0.85f), 0.5f))
    }

    private fun template(canvas: Canvas) {
        val s = picture.width() / scene.width
        canvas.save()
        canvas.translate(picture.left, picture.top)
        canvas.scale(s, s)
        val line = stroke(LINE, OUTLINE / s).apply {
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawPath(scene.lines, line)
        if (!scene.strokes.isEmpty) canvas.drawPath(scene.strokes, line)
        val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = NUMBER
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        for (label in scene.labels) {
            val paint = scene.regions.getOrNull(label.region)?.paint ?: continue
            number.textSize = label.fontSize
            canvas.drawText((paint + 1).toString(), label.x, label.y + label.fontSize * 0.36f, number)
        }
        canvas.restore()
    }

    /** The color key: number, a dot of the paint and its name, with its hex value and area count below. */
    private fun legend(canvas: Canvas) {
        val s = key.scale
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val ring = stroke(gray(0f, 0.35f), 0.5f)
        val numberText = text(8f * s, bold = true, color = gray(0.12f), align = Paint.Align.RIGHT)
        val nameText = text(8f * s, bold = true, color = gray(0.12f))
        val detailText = text(6f * s, color = gray(0.5f))
        val textWidth = key.columnWidth - KEY_NAME_INSET * s
        scene.paints.forEachIndexed { i, paint ->
            val x = keyRect.left + (i / key.rows) * (key.columnWidth + KEY_GAP)
            val y = keyRect.top + (i % key.rows) * key.rowHeight
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
    }

    /**
     * How the key's entries are laid out, column by column: the largest scale (1 down to 0.6),
     * then the fewest columns (3–5, or 4–6 on a wide page) whose rows fit and whose widest entry
     * fits a column, as the iOS app's `PDFExporter.keyLayout`.
     */
    private class KeyLayout(val scale: Float, val columnWidth: Float, val rowHeight: Float, val rows: Int, val count: Int) {
        val height: Float get() = if (count == 0) 0f else rows * rowHeight

        companion object {
            fun of(count: Int, widestEntry: Float, rect: RectF): KeyLayout {
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
                        if (candidate.height <= rect.height() && widestEntry * scale <= candidate.columnWidth) return candidate
                    }
                }
                return layout(0.6f, base + 2)
            }
        }
    }

    companion object {
        private const val LETTER_WIDTH = 612f
        private const val MARGIN = 36f
        private const val HEADER = 36f
        private const val KEY_GAP = 12f
        private const val KEY_NAME_INSET = 36f
        /** The template's lines, in laid-out points (iOS's printable style). */
        private const val OUTLINE = 0.4f
        const val MINIMUM_NUMBER = 2.6f
        const val MINIMUM_DETAIL_NUMBER = 2f
        private const val APP_NAME = "Paint by Moonlight"
        private const val WHITE = 0xFFFFFFFF.toInt()
        private val LINE = rgb(0.45f, 0.47f, 0.5f)
        private val NUMBER = rgb(0.35f, 0.37f, 0.4f)

        private fun areasText(n: Int) = "$n ${if (n == 1) "area" else "areas"}"

        private fun fit(aspect: Float, rect: RectF): RectF {
            var w = rect.width()
            var h = w / aspect
            if (h > rect.height()) {
                h = rect.height(); w = h * aspect
            }
            val left = rect.centerX() - w / 2
            return RectF(left, rect.top, left + w, rect.top + h)
        }

        private fun text(size: Float, bold: Boolean = false, color: Int = 0xFF000000.toInt(), align: Paint.Align = Paint.Align.LEFT) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                this.color = color
                textAlign = align
            }

        private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            this.color = color
        }

        private fun ellipsized(string: String, paint: TextPaint, width: Float): String =
            TextUtils.ellipsize(string, paint, width, TextUtils.TruncateAt.END).toString()

        private fun gray(white: Float, alpha: Float = 1f): Int = rgb(white, white, white, alpha)

        private fun rgb(r: Float, g: Float, b: Float, a: Float = 1f): Int {
            fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
        }
    }
}
