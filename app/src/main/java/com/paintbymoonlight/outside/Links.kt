package com.paintbymoonlight.outside

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.paintbymoonlight.MainActivity

/**
 * The app's own links, which the widget and the launcher's shortcuts open and the NavHost
 * answers (its deep links). They are explicit intents to [MainActivity], so no other app can
 * send them and the manifest needs no intent filter.
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
