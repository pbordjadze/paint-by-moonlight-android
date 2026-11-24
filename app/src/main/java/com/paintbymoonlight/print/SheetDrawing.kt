package com.paintbymoonlight.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
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
    private val content = RectF(0f, 0f, pageWidth / factor, pageHeight / factor).apply { inset(PrintPage.MARGIN, PrintPage.MARGIN) }
    private val key: PrintPage.KeyLayout
    private val keyRect: RectF
    /** Where the template draws, in laid-out units. */
    private val picture: RectF

    init {
        val body = RectF(content.left, content.top + PrintPage.HEADER, content.right, content.bottom)
        val room = RectF(body.left, body.top, body.right, body.top + body.height() * 0.4f)
        key = PrintPage.keyLayout(scene, room)
        val used = key.height
        keyRect = RectF(body.left, body.bottom - used, body.right, body.bottom)
        if (used > 0) body.bottom -= used + PrintPage.KEY_GAP * 2
        picture = PrintPage.fit(scene.width / scene.height, body)
    }

    /** Sheet points per canvas unit of the template. */
    private val pictureScale: Float get() = picture.width() / scene.width * factor

    /** Whether any number prints under its legible floor: an ordinary area's 2.6 pt, a detail area's 2 pt. */
    val printsSmallNumbers: Boolean
        get() = scene.labels.any { label ->
            val floor = if (scene.regions[label.region].isDetail) Sheets.MINIMUM_DETAIL_NUMBER else Sheets.MINIMUM_NUMBER
            PrintPage.numberSize(scene, label.fontSize) * pictureScale < floor
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
        canvas.drawColor(PrintPage.WHITE)
        canvas.scale(k, k)
        draw(canvas)
        return bitmap
    }

    /** Draws the page on [canvas], whose units are the page's points. */
    fun draw(canvas: Canvas) {
        canvas.save()
        canvas.scale(factor, factor)
        PrintPage.header(canvas, content, title, PrintPage.statsText(scene))
        if (key.count > 0) PrintPage.legend(canvas, scene, key, keyRect)
        PrintPage.template(canvas, scene, picture.left, picture.top, picture.width() / scene.width)
        canvas.drawRect(picture, PrintPage.stroke(PrintPage.gray(0.75f), 0.5f))
        PrintPage.footer(canvas, content)
        canvas.restore()
    }

    companion object {
        private const val LETTER_WIDTH = 612f
    }
}
