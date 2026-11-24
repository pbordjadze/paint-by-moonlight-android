package com.paintbymoonlight.print

import java.time.LocalDate
import java.util.Locale

/**
 * What One Big Sheet prints on (painting-company row 380): rolled cotton canvas for acrylic and
 * cold press watercolor paper, each in two poster sizes, by the id the print relay knows them by
 * (`products` in paint-by-number's feedback-relay/print.js, the iOS app's `BigSheetProduct`).
 */
enum class BigSheetProduct(val id: String, val surface: Surface, val shortInches: Int, val longInches: Int) {
    Canvas16x24("canvas-16x24", Surface.Canvas, 16, 24),
    Canvas24x36("canvas-24x36", Surface.Canvas, 24, 36),
    Watercolor18x24("watercolor-18x24", Surface.WatercolorPaper, 18, 24),
    Watercolor24x36("watercolor-24x36", Surface.WatercolorPaper, 24, 36);

    enum class Surface(val label: String) {
        Canvas("Canvas"),
        WatercolorPaper("Watercolor Paper");

        /** Its sizes, smallest first. */
        val products: List<BigSheetProduct> get() = BigSheetProduct.entries.filter { it.surface == this }
    }

    /** The sheet's short and long sides in PDF points. */
    val shortPoints: Int get() = shortInches * 72
    val longPoints: Int get() = longInches * 72

    /** The same size class on [other]: the smaller sheet stays the smaller one. */
    fun on(other: Surface): BigSheetProduct {
        if (other == surface) return this
        val index = surface.products.indexOf(this)
        return other.products.getOrElse(index) { other.products.last() }
    }

    companion object {
        /** The sheet the screen opens on: canvas, the larger size. */
        val default = Canvas24x36
    }
}

/**
 * The countries One Big Sheet sends to: where Prodigi prints near (the US, the UK, Europe,
 * Australia) and its neighbours, as the iOS app's `BigSheetCountries`.
 */
object BigSheetCountries {
    val codes = listOf(
        "US", "CA", "GB", "IE", "AU", "NZ", "DE", "FR", "ES", "IT", "NL", "BE", "AT", "CH", "SE", "NO", "DK", "FI", "PT", "JP",
    )

    fun default(locale: Locale = Locale.getDefault()): String = locale.country.takeIf { it in codes } ?: "US"

    fun name(code: String, locale: Locale = Locale.getDefault()): String =
        Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifEmpty { code }
}

/**
 * Christmas's last day to order a sheet that arrives for it, by where it goes: a few days before
 * the print service's own order-by dates for standard shipping, as the iOS app's `BigSheetSeason`.
 * Said from November 15 until the day passes; nothing the rest of the year.
 */
object BigSheetSeason {
    private val decemberCutoffs = mapOf(
        "US" to 10, "GB" to 14, "IE" to 9, "CA" to 8, "AU" to 10, "NZ" to 8,
        "DE" to 9, "FR" to 9, "ES" to 9, "IT" to 9, "NL" to 9, "BE" to 9, "AT" to 9, "CH" to 8, "SE" to 8, "NO" to 8,
        "DK" to 9, "FI" to 8, "PT" to 8,
    )

    fun cutoff(country: String, today: LocalDate = LocalDate.now()): LocalDate? {
        val day = decemberCutoffs[country] ?: return null
        val start = LocalDate.of(today.year, 11, 15)
        val cutoff = LocalDate.of(today.year, 12, day)
        return if (!today.isBefore(start) && !today.isAfter(cutoff)) cutoff else null
    }
}
