package com.paintbymoonlight.outside

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.paintbymoonlight.MainActivity

/**
 * The app's own links, which the widget and the launcher's shortcuts open and the NavHost
 * answers (its deep links). The app sends them as explicit intents to [MainActivity], so the
 * manifest needs no intent filter; but MainActivity is exported, so another app can send them
 * too. They carry nothing but a painting's id, which opens only a painting already in the
 * library (an unknown one shows the paint screen's error), or open the photo picker.
 */
object Links {
    private const val SCHEME = "paintbymoonlight"
    const val PAINT_PATTERN = "$SCHEME://paint/{id}"
    const val NEW = "$SCHEME://new"

    fun paint(id: String): Uri = "$SCHEME://paint/${Uri.encode(id)}".toUri()

    fun new(): Uri = NEW.toUri()

    /**
     * Opens [uri] in a task of its own, as a launcher shortcut does: the NavHost builds the
     * gallery under the painting, so Back goes there, and starts no second activity to do it.
     */
    fun intent(context: Context, uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
}
