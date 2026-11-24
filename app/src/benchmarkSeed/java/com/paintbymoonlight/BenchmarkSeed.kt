package com.paintbymoonlight

import android.content.ContentProvider
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.core.PaintingLength
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.PaintingMeta
import com.paintbymoonlight.data.Progress
import com.paintbymoonlight.data.Thumbnails
import kotlinx.coroutines.runBlocking

/**
 * The macrobenchmarks' library, in the benchmark builds only (app/build.gradle.kts): the tour's
 * two paintings, Lighthouse finished and Parrots a third painted, made by the core on the device.
 * A release build can't be written to from outside, so the benchmark asks the app itself, from
 * the shell: `content call --uri content://<applicationId>.benchmarkseed --method seed`, which
 * returns once both are saved (at once when they already are).
 */
class BenchmarkSeed : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "seed" || Binder.getCallingUid() !in setOf(Process.SHELL_UID, ROOT_UID)) return null
        val app = context!!.applicationContext as MoonlightApp
        runBlocking { seed(app.library) }
        return Bundle()
    }

    private suspend fun seed(library: Library) {
        library.awaitLoaded()
        val titles = library.paintings.value.map { it.title }.toSet()
        if ("Lighthouse" !in titles) make(library, "lighthouse.jpg", "Lighthouse", PaintingLength.Quick, paintedShare = 1f)
        if ("Parrots" !in titles) make(library, "parrots.jpg", "Parrots", PaintingLength.Relaxed, paintedShare = 0.35f)
    }

    private suspend fun make(library: Library, asset: String, title: String, length: PaintingLength, paintedShare: Float) {
        val photo = context!!.assets.open(asset).use { BitmapFactory.decodeStream(it) }
        val made = Core.make(photo, length)
        val scene = Core.scene(made.template, SEED, Core.NICKNAME_VERSION)
        val progress = Progress(scene.regions.size)
        scene.regions.indices.sortedByDescending { scene.regions[it].area }
            .take((scene.regions.size * paintedShare).toInt())
            .forEach { progress.paint(it) }
        val meta = PaintingMeta(
            id = Library.newId(), title = title, created = System.currentTimeMillis(), length = length.name,
            seed = SEED, pipelineVersion = Core.pipelineVersion, settings = made.settingsJson, regionCount = scene.regions.size,
            nicknameVersion = Core.NICKNAME_VERSION, aspect = scene.width / scene.height,
        )
        library.add(meta, made.template, photo, Thumbnails.render(scene, Progress(scene.regions.size)))
        library.save(meta.id, progress.snapshot()) { Thumbnails.render(scene, progress) }
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?) = null
    override fun getType(uri: Uri) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    private companion object {
        const val SEED = 7L
        const val ROOT_UID = 0
    }
}
