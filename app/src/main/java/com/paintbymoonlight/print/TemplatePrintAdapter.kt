package com.paintbymoonlight.print

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import com.paintbymoonlight.core.Scene
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * The printable template handed to the system's print dialog, which asks for the printer, the
 * paper and which way it turns (Save as PDF among the printers). The pages are laid out again for
 * whatever paper the dialog picks, with the painter's choices from Print's preview kept.
 */
class TemplatePrintAdapter(
    private val scene: Scene,
    private val title: String,
    private val choices: PrintChoices,
) : PrintDocumentAdapter() {
    private var document: TemplatePrint? = null
    private val worker = Executors.newSingleThreadExecutor()

    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes, cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback, extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val media = newAttributes.mediaSize ?: PrintAttributes.MediaSize.NA_LETTER
        // Mils to points, portrait; the media's own orientation turns it.
        val short = minOf(media.widthMils, media.heightMils) * 72f / 1000f
        val long = maxOf(media.widthMils, media.heightMils) * 72f / 1000f
        val laid = TemplatePrint(scene, title, choices, Paper(short, long), landscape = !media.isPortrait)
        val changed = document?.let { it.pageWidth != laid.pageWidth || it.pageHeight != laid.pageHeight } ?: true
        document = laid
        val info = PrintDocumentInfo.Builder(fileName(title))
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(laid.pageCount)
            .build()
        callback.onLayoutFinished(info, changed)
    }

    override fun onWrite(
        pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        val laid = document ?: return callback.onWriteFailed(null)
        // Drawing a poster's sheets takes a moment: off the main thread, where the dialog lives.
        worker.execute {
            try {
                // The framework owns the descriptor and closes it.
                FileOutputStream(destination.fileDescriptor).let { out ->
                    laid.write(out) { cancellationSignal?.isCanceled == true }
                    out.flush()
                }
                if (cancellationSignal?.isCanceled == true) callback.onWriteCancelled()
                else callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                Log.e(TAG, "Writing the template to print failed", e)
                callback.onWriteFailed(e.message)
            }
        }
    }

    override fun onFinish() {
        worker.shutdown()
    }

    companion object {
        private const val TAG = "Print"

        /** Opens the system's print dialog on the template, on the region's paper turned the picture's way. */
        fun print(context: Context, scene: Scene, title: String, choices: PrintChoices) {
            val manager = context.getSystemService(PrintManager::class.java) ?: return
            val paper = Paper.forCountry(context.resources.configuration.locales[0]?.country)
            val media = if (paper == Paper.LETTER) PrintAttributes.MediaSize.NA_LETTER else PrintAttributes.MediaSize.ISO_A4
            val attributes = PrintAttributes.Builder()
                .setMediaSize(if (scene.width > scene.height) media.asLandscape() else media.asPortrait())
                // The key's swatches are the paints to buy or mix.
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                .build()
            manager.print(title, TemplatePrintAdapter(scene, title, choices), attributes)
        }

        /** The file's name where Save as PDF keeps it, and the job's. */
        fun fileName(title: String): String =
            "${title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().ifBlank { "Painting" }} Template.pdf"
    }
}
