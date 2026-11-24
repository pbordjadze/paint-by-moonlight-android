package com.paintbymoonlight

import android.content.Context
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.museum.MuseumImages
import com.paintbymoonlight.museum.MuseumLibrary
import com.paintbymoonlight.pictures.NightSchedule
import com.paintbymoonlight.pictures.Nights
import com.paintbymoonlight.ui.create.Picked
import com.paintbymoonlight.ui.museum.MuseumScreen
import com.paintbymoonlight.ui.museum.MuseumWorkScreen
import java.io.File

/**
 * The features iOS keeps behind `#if UNRELEASED` (its SideStore and Debug builds), which only
 * debug builds have, Paul's beta: the Museum and Tonight's Picture from the museums' nightly
 * schedule (painting-company rows 368 and 369). Their code lives in src/debug alone, so a release
 * build has none of it; src/release's copy of this object answers with nothing.
 */
object Unreleased {
    const val ON = true

    fun nights(context: Context): NightSchedule? = Nights.of(context)

    /** A Museum work's full image, fetched if it isn't on the device. */
    suspend fun museumPicture(context: Context, url: String): File = MuseumImages.of(context).file(url)

    /** A painting of a Museum work was saved: the work hangs framed from now on. */
    fun museumPainted(context: Context, work: String) = MuseumLibrary.of(context).record(work)

    /** The Museum's wall ([MUSEUM]) and a work's page, in the Paint page's navigation. */
    fun museumRoutes(builder: NavGraphBuilder, nav: NavController, library: Library, onPicked: (Picked) -> Unit, onResume: (String) -> Unit) {
        builder.composable(MUSEUM) {
            MuseumScreen(library = library, onWork = { nav.navigate("$MUSEUM/${android.net.Uri.encode(it)}") }, onClose = { nav.popBackStack() })
        }
        builder.composable("$MUSEUM/{work}") { entry ->
            val id = entry.arguments?.getString("work") ?: return@composable
            MuseumWorkScreen(id = id, library = library, onPaint = onPicked, onResume = onResume, onBack = { nav.popBackStack() })
        }
    }

    const val MUSEUM = "museum"
}
