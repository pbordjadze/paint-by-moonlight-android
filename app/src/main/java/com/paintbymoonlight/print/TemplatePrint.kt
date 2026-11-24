package com.paintbymoonlight.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.paintbymoonlight.core.Scene
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The printable template, as the iOS app's `PDFExporter.document` makes it: the template with its
 * numbers (vectors, crisp at any zoom), then the color key (number, paint and name of every
 * color) with the finished picture for reference.
 *
 * Numbers keep to their areas' room, so a detailed template fitted to one page would print its
 * smallest numbers at about 1 pt. Such a template prints on several overlapping sheets instead
 * ([Sheets]), at the scale that prints its smallest number at 2.6 pt (a detail area's at 2 pt),
 * after an overview page showing how they tape together. Each sheet's numbers are no wider than
 * the overlap, so every number is whole on at least one sheet, and each sheet holds only its
 * part of the template. Without numbers it is a coloring page, with no key.
 *
 * [paper] is portrait; the page turns the picture's way unless [landscape] says otherwise.
 */
class TemplatePrint(
    private val scene: Scene,
    private val title: String,
    val choices: PrintChoices,
    paper: Paper,
    landscape: Boolean? = null,
) {
    private val turned = landscape ?: (scene.width > scene.height)

    /** The page, in whole PDF points. */
    val pageWidth: Int = (if (turned) paper.height else paper.width).roundToInt()
    val pageHeight: Int = (if (turned) paper.width else paper.height).roundToInt()

    /** Each number's printed size in canvas units, by label (on one page; a sheet may hold them smaller). */
    private val numberSizes: List<NumberSize> =
        if (!choices.numbers) emptyList()
        else scene.labels.mapNotNull { label ->
            val region = scene.regions.getOrNull(label.region) ?: return@mapNotNull null
            NumberSize(PrintPage.numberSize(scene, label.fontSize), region.isDetail)
        }

    val sheets: Sheets = Sheets.plan(
        scene.width, scene.height, pageWidth.toFloat(), pageHeight.toFloat(), Sheets.legibleScale(numberSizes), choices.across)

    /** The sizes Print offers on this paper, smallest first. */
    val sizes: List<Sheets.Size>
        get() = Sheets.sizes(scene.width, scene.height, pageWidth.toFloat(), pageHeight.toFloat(), Sheets.legibleScale(numberSizes))

    /**
     * The largest number a sheet prints (canvas units): no wider than the overlap when tiled, so
     * each number is whole on some sheet.
     */
    private val numberCap: Float = run {
        val cap = PrintPage.defaultCap(scene)
        if (!sheets.isTiled) return@run cap
        val widest = scene.paints.size.toString().length * Sheets.DIGIT_ADVANCE
        min(cap, Sheets.OVERLAP / (widest * sheets.scale))
    }

    /** The smallest number printed, in points (an ordinary area's, and a detail area's), or null without them. */
    fun smallestNumber(detail: Boolean): Float? =
        numberSizes.filter { it.detail == detail }.minOfOrNull { min(it.size, numberCap) * sheets.scale }

    enum class PageKind { Template, Overview, Sheet, Key }

    val pages: List<PageKind> = buildList {
        if (sheets.isTiled) {
            add(PageKind.Overview)
            repeat(sheets.count) { add(PageKind.Sheet) }
        } else {
            add(PageKind.Template)
        }
        // A coloring page has no numbers to look up: the key goes with them.
        if (choices.numbers) add(PageKind.Key)
    }

    val pageCount: Int get() = pages.size

    private val content = RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()).apply { inset(PrintPage.MARGIN, PrintPage.MARGIN) }
    private val stats get() = if (choices.numbers) PrintPage.statsText(scene) else ""
    private val aspect get() = scene.width / scene.height

    /**
     * Draws page [index] on [canvas], whose units are the page's points (y down); drawing ends
     * early once [stop] says so.
     */
    fun drawPage(index: Int, canvas: Canvas, stop: () -> Boolean = { false }) {
        when (pages[index]) {
            PageKind.Template -> template(canvas, stop)
            PageKind.Overview -> overview(canvas, stop)
            PageKind.Sheet -> sheet(canvas, index - 1, stop)
            PageKind.Key -> key(canvas)
        }
        PrintPage.footer(canvas, content)
    }

    /** Writes the whole document as a PDF; false, with nothing written, once [cancelled] says so. */
    fun write(out: OutputStream, cancelled: () -> Boolean = { false }): Boolean {
        val document = PdfDocument()
        try {
            for (i in pages.indices) {
                if (cancelled()) return false
                val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, i + 1).create())
                drawPage(i, page.canvas, cancelled)
                document.finishPage(page)
            }
            if (cancelled()) return false
            document.writeTo(out)
            return true
        } finally {
            document.close()
        }
    }

    fun pdf(): ByteArray = ByteArrayOutputStream().also { write(it) }.toByteArray()

    /** Page [index] as it will print, for the screen: [longSide] pixels on its long side, on white. */
    fun pageImage(index: Int, longSide: Int, stop: () -> Boolean = { false }): Bitmap {
        val k = longSide / max(pageWidth, pageHeight).toFloat()
        val bitmap = Bitmap.createBitmap(
            (pageWidth * k).roundToInt().coerceAtLeast(1), (pageHeight * k).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PrintPage.WHITE)
        canvas.scale(k, k)
        drawPage(index, canvas, stop)
        return bitmap
    }

    /**
     * Sheet [w]'s share of the template: what lies in its window (canvas units, with room for a
     * line's width), so each sheet holds its part rather than the whole template under a clip.
     */
    private fun part(w: Sheets.Window): PrintPage.Part {
        val pad = 2f
        val visible = RectF(w.x / sheets.scale - pad, w.y / sheets.scale - pad,
            (w.x + w.width) / sheets.scale + pad, (w.y + w.height) / sheets.scale + pad)
        fun within(polylines: List<FloatArray>, bounds: FloatArray, whole: Path) =
            if (polylines.isEmpty()) whole
            else Path().apply {
                polylines.forEachIndexed { i, p ->
                    if (p.size < 4 || !visible.intersects(bounds[4 * i], bounds[4 * i + 1], bounds[4 * i + 2], bounds[4 * i + 3])) return@forEachIndexed
                    moveTo(p[0], p[1])
                    for (j in 1 until p.size / 2) lineTo(p[2 * j], p[2 * j + 1])
                }
            }
        return PrintPage.Part(visible, within(scene.linePolylines, lineBounds, scene.lines),
            within(scene.strokePolylines, strokeBounds, scene.strokes))
    }

    private val lineBounds by lazy { bounds(scene.linePolylines) }
    private val strokeBounds by lazy { bounds(scene.strokePolylines) }

    private val frame get() = PrintPage.stroke(PrintPage.gray(0.75f), 0.5f)

    private fun template(canvas: Canvas, stop: () -> Boolean) {
        // A coloring page doesn't count its paints: they aren't on it.
        val body = PrintPage.header(canvas, content, title, stats)
        val rect = PrintPage.fit(aspect, body)
        PrintPage.template(canvas, scene, rect.left, rect.top, rect.width() / scene.width, choices.numbers, choices.colorHints, numberCap,
            stop = stop)
        canvas.drawRect(rect, frame)
    }

    /** How the sheets tape together: the template without numbers, each sheet's part outlined and numbered. */
    private fun overview(canvas: Canvas, stop: () -> Boolean) {
        val count = "${sheets.count} sheets"
        val body = PrintPage.header(canvas, content, title, if (choices.numbers) "$stats · $count" else count)
        val rect = PrintPage.fit(aspect, body)
        PrintPage.template(canvas, scene, rect.left, rect.top, rect.width() / scene.width, numbers = false, hints = choices.colorHints, stop = stop)
        val factor = rect.width() / sheets.printedWidth
        val line = PrintPage.stroke(PrintPage.gray(0.3f, 0.8f), 0.75f)
        canvas.drawRect(rect, line)
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PrintPage.gray(1f, 0.9f) }
        for (row in 0 until sheets.rows) {
            for (column in 0 until sheets.columns) {
                val w = sheets.window(column, row)
                val f = RectF(rect.left + w.x * factor, rect.top + w.y * factor, rect.left + (w.x + w.width) * factor, rect.top + (w.y + w.height) * factor)
                f.inset(1f, 1f)
                canvas.drawRect(f, line)
                val size = min(28f, f.height() * 0.3f)
                // On a disc of paper: printed straight on the line art, the number got lost in it.
                val label = "${row * sheets.columns + column + 1}"
                val discWidth = max(size * 1.4f, size * (0.75f + 0.6f * label.length))
                val r = RectF(f.centerX() - discWidth / 2, f.centerY() - size * 0.7f, f.centerX() + discWidth / 2, f.centerY() + size * 0.7f)
                canvas.drawRoundRect(r, size * 0.7f, size * 0.7f, disc)
                canvas.drawText(label, f.centerX(), f.centerY() + size * 0.35f,
                    PrintPage.serif(size, weight = 700, color = PrintPage.gray(0.3f, 0.8f), align = Paint.Align.CENTER))
            }
        }
    }

    /** Sheet [n] (0-based, row by row): its part of the template at full scale, clipped to the body. */
    private fun sheet(canvas: Canvas, n: Int, stop: () -> Boolean) {
        val row = n / sheets.columns
        val column = n % sheets.columns
        val w = sheets.window(column, row)
        val body = PrintPage.header(canvas, content, title,
            "Sheet ${n + 1} of ${sheets.count} · row ${row + 1}, column ${column + 1}")
        val visible = RectF(body.left, body.top, body.left + w.width, body.top + w.height)
        canvas.save()
        canvas.clipRect(visible)
        PrintPage.template(
            canvas, scene, body.left - w.x, body.top - w.y, sheets.scale, choices.numbers, choices.colorHints, numberCap,
            part(w), stop)
        canvas.restore()
        canvas.drawRect(visible, frame)
    }

    /** The color key, and below it the finished picture where there is room. */
    private fun key(canvas: Canvas) {
        val body = PrintPage.header(canvas, content, "Color Key", title)
        val layout = PrintPage.keyLayout(scene, body)
        val used = PrintPage.legend(canvas, scene, layout, body)
        val remaining = RectF(body.left, body.top + used + 24f, body.right, body.bottom)
        if (remaining.height() > 110f) {
            canvas.drawText("Reference", remaining.left, remaining.top + 12f, PrintPage.serif(13f, color = PrintPage.gray(0.2f)))
            val area = RectF(remaining.left, remaining.top + 22f, remaining.right, remaining.bottom)
            PrintPage.painting(canvas, scene, PrintPage.fit(aspect, area))
        }
    }

    private companion object {
        /** Each polyline's bounds, minX, minY, maxX, maxY in turn. */
        fun bounds(polylines: List<FloatArray>): FloatArray {
            val out = FloatArray(polylines.size * 4)
            polylines.forEachIndexed { i, p ->
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (j in 0 until p.size / 2) {
                    minX = min(minX, p[2 * j]); maxX = max(maxX, p[2 * j])
                    minY = min(minY, p[2 * j + 1]); maxY = max(maxY, p[2 * j + 1])
                }
                out[4 * i] = minX; out[4 * i + 1] = minY; out[4 * i + 2] = maxX; out[4 * i + 3] = maxY
            }
            return out
        }
    }
}
