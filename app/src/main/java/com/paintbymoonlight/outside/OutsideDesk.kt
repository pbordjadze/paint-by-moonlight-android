package com.paintbymoonlight.outside

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.paintbymoonlight.R
import com.paintbymoonlight.data.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * What the app keeps outside itself: the home-screen widget ([PaintingWidget]) and the
 * launcher's Continue shortcut, both of the painting in progress. The library's list changes
 * with every save, rename, new painting and delete, so following it is the one hook needed.
 */
object OutsideDesk {
    private const val TAG = "Outside"
    private const val CONTINUE = "continue-"

    @OptIn(FlowPreview::class)
    fun start(context: Context, library: Library) {
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            library.awaitLoaded()
            // Every save moves the widget's picture; only another painting, or a new name, the shortcut.
            library.paintings
                .map { list -> Outside(inProgress(list)?.let { Painting(it.id, it.title) }, list.map { it.id }.toSet()) }
                .distinctUntilChanged()
                .collect { outside -> guarded("update the shortcuts") { updateShortcuts(app, library, outside) } }
        }
        scope.launch {
            library.awaitLoaded()
            library.paintings
                .map { list -> inProgress(list) }
                .distinctUntilChanged()
                // A painter fills areas quickly; draw the widget again once they pause.
                .debounce(800)
                .collect { guarded("update the widget") { PaintingWidget().updateAll(app) } }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) scope.launch { publishPreviews(app) }
    }

    private data class Painting(val id: String, val title: String)

    /** What the shortcuts show: the painting in progress, and which paintings exist. */
    private data class Outside(val now: Painting?, val ids: Set<String>)

    /**
     * One dynamic shortcut, Continue, for the painting in progress; a pinned one whose
     * painting was deleted is disabled, so it can't open a painting that's gone.
     */
    private fun updateShortcuts(context: Context, library: Library, outside: Outside) {
        val now = outside.now
        if (now == null) {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        } else {
            val shortcut = ShortcutInfoCompat.Builder(context, CONTINUE + now.id)
                .setShortLabel(now.title)
                .setLongLabel("Continue ${now.title}")
                .setIcon(icon(context, library, now.id))
                .setIntent(Links.intent(context, Links.paint(now.id)))
                .build()
            val others = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it != shortcut.id }
            if (others.isNotEmpty()) ShortcutManagerCompat.removeDynamicShortcuts(context, others)
            // Pushed rather than set, so the launcher learns it's the one used.
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
        val gone = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
            .map { it.id }
            .filter { it.startsWith(CONTINUE) && it.removePrefix(CONTINUE) !in outside.ids }
        if (gone.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, gone, "This painting was deleted.")
    }

    /** The painting itself, as an adaptive icon: the launcher's mask shows its middle. */
    private fun icon(context: Context, library: Library, id: String): IconCompat {
        val thumb = BitmapFactory.decodeFile(library.thumbnailFile(id).path)
            ?: return IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        val side = min(thumb.width, thumb.height)
        val crop = Rect((thumb.width - side) / 2, (thumb.height - side) / 2, (thumb.width + side) / 2, (thumb.height + side) / 2)
        val icon = createBitmap(ICON_PIXELS, ICON_PIXELS)
        Canvas(icon).drawBitmap(thumb, crop, Rect(0, 0, ICON_PIXELS, ICON_PIXELS), Paint(Paint.FILTER_BITMAP_FLAG))
        return IconCompat.createWithAdaptiveBitmap(icon)
    }

    /**
     * Android 15's widget picker shows a preview the widget draws (its [PaintingWidget.providePreview]).
     * The system limits how often an app may publish one, so it goes once per app version.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun publishPreviews(context: Context) {
        val prefs = context.getSharedPreferences("outside", Context.MODE_PRIVATE)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        if (prefs.getLong("previews", -1) == version) return
        guarded("publish the widget's preview") {
            val result = GlanceAppWidgetManager(context).setWidgetPreviews(PaintingWidgetReceiver::class)
            if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) {
                prefs.edit { putLong("previews", version) }
            }
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "couldn't $what", e)
        }
    }

    private const val ICON_PIXELS = 288
}
