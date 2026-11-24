package com.paintbymoonlight.data

import android.net.Uri
import androidx.core.content.FileProvider
import com.paintbymoonlight.core.SharedPainting

/**
 * The app's FileProvider, which tells apps a `.moonlight` file's own type (the platform knows
 * no type for the extension and would call it application/octet-stream), so another Paint by
 * Moonlight opens it from wherever it lands.
 */
class MoonlightFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? =
        if (uri.path?.endsWith(".${SharedPainting.EXTENSION}") == true) SharedPainting.MIME_TYPE else super.getType(uri)
}
