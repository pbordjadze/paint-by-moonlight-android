package com.paintbymoonlight.print

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A paper size, portrait, in PDF points. */
data class Paper(val width: Float, val height: Float) {
    companion object {
        val LETTER = Paper(612f, 792f)
        val A4 = Paper(595.28f, 841.89f)

        private val LETTER_COUNTRIES = setOf("US", "CA", "MX", "PH", "CL", "CO", "VE", "GT", "PR", "CR", "DO", "PA", "SV")

        /** Letter where it is the norm, A4 everywhere else (the iOS app's `Paper.default(for:)`). */
        fun forCountry(country: String?): Paper = if (country != null && country.uppercase() in LETTER_COUNTRIES) LETTER else A4
    }
}

/**
 * How the template prints: how big (`across`, sheets along the picture's longer printed side;
 * null is the fewest whose numbers are legible) and which layers, as the iOS app's
 * `PDFExporter.Layout`. The lines always print.
 */
data class PrintChoices(
    val across: Int? = null,
    val numbers: Boolean = true,
    val colorHints: Boolean = false,
    val colorKey: Boolean = true,
)

/** A number's printed size in canvas units, and whether it is a detail area's (its floor is lower). */
class NumberSize(val size: Float, val detail: Boolean)

/**
 * The part of a page the template may take, and how it is cut into overlapping sheets when its
 * smallest number would print too small on one page: the iOS app's `PDFExporter.sheets`.
 * Pure arithmetic, so it is tested on the JVM.
 */
data class Sheets(
    /** Points per canvas unit. */
    val scale: Float,
    val columns: Int,
    val rows: Int,
    /** The page body: the largest part of the template one page shows (points). */
    val bodyWidth: Float,
    val bodyHeight: Float,
    /** The whole template at [scale] (points). */
    val printedWidth: Float,
    val printedHeight: Float,
) {
    val count: Int get() = columns * rows
    val isTiled: Boolean get() = count > 1

    /** The part of the printed template (points, origin top-left) sheet ([column], [row]) shows. */
    fun window(column: Int, row: Int): Window {
        val x = column * (bodyWidth - OVERLAP)
        val y = row * (bodyHeight - OVERLAP)
        return Window(x, y, min(bodyWidth, printedWidth - x), min(bodyHeight, printedHeight - y))
    }

    data class Window(val x: Float, val y: Float, val width: Float, val height: Float)

    /** One of the sizes Print offers: the [PrintChoices.across] that asks for it, and its sheets. */
    data class Size(val across: Int?, val sheets: Sheets)

    companion object {
        /** Smallest number printed (points): smaller digits blur into specks on paper. */
        const val MINIMUM_NUMBER = 2.6f
        /** A detail area's (an area the painter filled, read close up as the canvas is zoomed in). */
        const val MINIMUM_DETAIL_NUMBER = 2f
        /** How far neighbouring sheets overlap (points), room to tape them together. */
        const val OVERLAP = 18f
        const val MARGIN = 36f
        const val HEADER = 36f
        /** Width of one digit, in font sizes (`LabelSizing.digitAdvance`). */
        const val DIGIT_ADVANCE = 0.6f
        /** The largest number a printed template draws, as a share of its long side (`LabelSizing.sheetMaximumFraction`). */
        const val MAXIMUM_NUMBER_FRACTION = 1f / 64

        fun bodyWidth(pageWidth: Float) = pageWidth - 2 * MARGIN
        fun bodyHeight(pageHeight: Float) = pageHeight - 2 * MARGIN - HEADER

        /**
         * The scale that prints the smallest number at [MINIMUM_NUMBER] and a detail area's
         * smallest at [MINIMUM_DETAIL_NUMBER], or null without numbers.
         */
        fun legibleScale(numbers: List<NumberSize>): Float? {
            val usual = numbers.filter { !it.detail }.minOfOrNull { it.size }?.let { MINIMUM_NUMBER / it }
            val detail = numbers.filter { it.detail }.minOfOrNull { it.size }?.let { MINIMUM_DETAIL_NUMBER / it }
            return listOfNotNull(usual, detail).maxOrNull()
        }

        /**
         * How a picture of [width] × [height] canvas units prints on a page of [pageWidth] ×
         * [pageHeight] points: on one page when its numbers are legible there ([legible], points
         * per unit), otherwise on a grid of overlapping sheets at a scale that makes it so, or
         * across [across] sheets when the painter asks for it bigger.
         */
        fun plan(width: Float, height: Float, pageWidth: Float, pageHeight: Float, legible: Float?, across: Int?): Sheets {
            val bw = bodyWidth(pageWidth)
            val bh = bodyHeight(pageHeight)
            val w = max(width, 1f)
            val h = max(height, 1f)
            fun make(scale: Float, columns: Int, rows: Int) = Sheets(scale, columns, rows, bw, bh, w * scale, h * scale)
            val fitted = min(bw / w, bh / h)
            val asked = across?.let { acrossScale(w, h, bw, bh, it) } ?: 0f
            val needed = max(legible ?: 0f, asked)
            if (fitted >= needed) return make(fitted, 1, 1)
            // n sheets with overlap o cover n·(w − o) + o.
            fun count(extent: Float, window: Float) = max(1, ceil(((extent * needed - OVERLAP) / (window - OVERLAP)).toDouble()).toInt())
            val columns = count(w, bw)
            val rows = count(h, bh)
            // Fill the grid: the scale only grows past `needed`, and no row or column is left empty.
            val scale = min((columns * (bw - OVERLAP) + OVERLAP) / w, (rows * (bh - OVERLAP) + OVERLAP) / h)
            return make(scale, columns, rows)
        }

        /** The scale at which the picture spans [sheets] sheets along the side that fills a page first. */
        private fun acrossScale(w: Float, h: Float, bw: Float, bh: Float, sheets: Int): Float {
            val alongWidth = w / bw >= h / bh
            val extent = if (alongWidth) w else h
            val window = if (alongWidth) bw else bh
            // A hair under: at exactly this scale rounding could ask for one sheet more.
            return (max(sheets, 1) * (window - OVERLAP) + OVERLAP) / extent * (1 - 1e-6f)
        }

        /**
         * The sizes Print offers, smallest first: the fewest legible sheets, then the picture
         * across more, each a different count of sheets. At most three.
         */
        fun sizes(width: Float, height: Float, pageWidth: Float, pageHeight: Float, legible: Float?): List<Size> {
            val smallest = plan(width, height, pageWidth, pageHeight, legible, null)
            val sizes = mutableListOf(Size(null, smallest))
            val start = max(smallest.columns, smallest.rows)
            for (n in start + 1..start + 3) {
                if (sizes.size >= 3) break
                val made = plan(width, height, pageWidth, pageHeight, legible, n)
                if (made.count > sizes.last().sheets.count) sizes += Size(n, made)
            }
            return sizes
        }
    }
}
