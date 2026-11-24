package com.paintbymoonlight

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.Thumbnails
import com.paintbymoonlight.print.BigSheetScreen
import com.paintbymoonlight.print.BigSheetViewModel
import com.paintbymoonlight.ui.create.CreateScreen
import com.paintbymoonlight.ui.create.CreateViewModel
import com.paintbymoonlight.ui.gallery.GalleryScreen
import com.paintbymoonlight.ui.paint.PaintScreen
import com.paintbymoonlight.ui.paint.PaintViewModel
import com.paintbymoonlight.ui.theme.MoonlightTheme
import com.paintbymoonlight.unlock.rememberUnlockGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as MoonlightApp
        setContent {
            MoonlightTheme {
                MoonlightNav(app)
            }
        }
    }
}

private object Routes {
    const val GALLERY = "gallery"
    const val CREATE = "create?photo={photo}"
    const val PAINT = "paint/{id}"
    const val PRINT = "print/{id}"
    fun create(photo: Uri) = "create?photo=${Uri.encode(photo.toString())}"
    fun paint(id: String) = "paint/$id"
    fun print(id: String) = "print/$id"
}

@Composable
private fun MoonlightNav(app: MoonlightApp) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = app.library

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) nav.navigate(Routes.create(uri))
    }
    // A new painting takes a free painting: with none left, the gate offers Unlimited Paintings first.
    val gate = rememberUnlockGate(app.paintings)
    val share: (String) -> Unit = { id -> scope.launch { sharePainting(context, library, id) } }

    NavHost(navController = nav, startDestination = Routes.GALLERY) {
        composable(Routes.GALLERY) {
            GalleryScreen(
                library = library,
                onNew = { gate.start { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
                onOpen = { nav.navigate(Routes.paint(it)) },
                onShare = share,
                onPrint = { id -> nav.navigate(Routes.print(id)) },
                onRename = { id, title -> scope.launch { library.rename(id, title) } },
                onDelete = { id -> scope.launch { library.delete(id) } },
            )
        }
        composable(Routes.CREATE) { entry ->
            val photo = Uri.parse(entry.arguments?.getString("photo") ?: "")
            val model = viewModel { CreateViewModel(library, app.contentResolver, photo, app.paintings) }
            CreateScreen(
                model = model,
                preferences = app.preferences,
                onBack = { nav.popBackStack() },
                onMade = { id ->
                    nav.navigate(Routes.paint(id)) { popUpTo(Routes.GALLERY) }
                },
            )
        }
        composable(Routes.PAINT) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val model = viewModel { PaintViewModel(library, id) }
            PaintScreen(
                model = model,
                preferences = app.preferences,
                onBack = { nav.popBackStack() },
                // Both read the painting from disk: wait for the last fill to be saved.
                onShare = { scope.launch { model.saveNow().join(); sharePainting(context, library, id) } },
                onPrint = {
                    scope.launch {
                        model.saveNow().join()
                        nav.navigate(Routes.print(id))
                    }
                },
            )
        }
        composable(Routes.PRINT) { entry ->
            val id = entry.arguments?.getString("id") ?: return@composable
            val model = viewModel { BigSheetViewModel(library, id) }
            BigSheetScreen(model = model, onBack = { nav.popBackStack() })
        }
    }
    gate.Sheet()
}

/** Shares the painting as a PNG: finished, every area in its paint; in progress, as it stands. */
private suspend fun sharePainting(context: Context, library: Library, id: String) {
    val meta = library.meta(id) ?: return
    val file = try {
        withContext(Dispatchers.Default) {
            val scene = Core.scene(library.template(id), meta.seed, meta.nicknameVersion)
            val progress = library.progress(id, scene.regions.size)
            val bitmap = if (progress.isComplete) Thumbnails.finished(scene) else Thumbnails.render(scene, progress, 2048)
            val dir = File(context.cacheDir, "shared").apply { deleteRecursively(); mkdirs() }
            File(dir, "${meta.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").ifBlank { "Painting" }}.png").also { out ->
                out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e("Share", "couldn't draw the painting to share", e)
        Toast.makeText(context, "This painting couldn’t be shared.", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, meta.title))
}
