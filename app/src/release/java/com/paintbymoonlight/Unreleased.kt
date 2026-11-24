package com.paintbymoonlight

import android.content.Context
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.pictures.NightSchedule
import com.paintbymoonlight.ui.create.Picked
import java.io.File
import java.io.IOException

/** Release builds leave out what iOS keeps behind `#if UNRELEASED` (the debug build's copy says what): nothing here. */
object Unreleased {
    const val ON = false

    fun nights(context: Context): NightSchedule? = null

    suspend fun museumPicture(context: Context, url: String): File = throw IOException("no Museum in this build")

    fun museumPainted(context: Context, work: String) = Unit

    fun museumRoutes(builder: NavGraphBuilder, nav: NavController, library: Library, onPicked: (Picked) -> Unit, onResume: (String) -> Unit) = Unit

    const val MUSEUM = "museum"
}
