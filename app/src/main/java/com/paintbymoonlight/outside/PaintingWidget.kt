package com.paintbymoonlight.outside

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.paintbymoonlight.MoonlightApp
import com.paintbymoonlight.R
import com.paintbymoonlight.data.Library
import com.paintbymoonlight.data.PaintingMeta
import com.paintbymoonlight.ui.theme.DarkColors
import com.paintbymoonlight.ui.theme.LightColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Painting in Progress, on the home screen: the painting as it stands, its title and how much
 * of it is painted; a tap opens it. With nothing in progress it invites a new painting, and a
 * tap starts one. It reads the library itself, so it is right whenever it is drawn, and the app
 * asks for it to be drawn again whenever a painting is saved ([OutsideDesk]).
 */
class PaintingWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))
    override val previewSizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val library = (context.applicationContext as MoonlightApp).library
        library.awaitLoaded()
        val first = inProgress(library.paintings.value)
        val firstKey = first?.let { it.id to it.updated }
        val firstPicture = first?.let { picture(library, it) }
        provideContent {
            // While the widget's session lives it follows the library itself; the picture read
            // before the session began stands until the painting is saved again.
            val paintings by library.paintings.collectAsState()
            val now = inProgress(paintings)
            val key = now?.let { it.id to it.updated }
            val picture by produceState(if (key == firstKey) firstPicture else null, key) {
                if (key != firstKey) value = now?.let { picture(library, it) }
            }
            GlanceTheme(colors = widgetColors(context)) {
                PaintingWidgetContent(now?.let { Shown(it.id, it.title, it.paintedCount, it.regionCount, picture?.let(::ImageProvider)) })
            }
        }
    }

    /** The picker's picture (Android 15 on): a painting of moonlit hills, two thirds painted. */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent {
            GlanceTheme(colors = widgetColors(context)) {
                PaintingWidgetContent(Shown("", "Moonlit Hills", 2, 3, ImageProvider(R.drawable.widget_preview_painting)))
            }
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 110.dp)
        val WIDE = DpSize(250.dp, 110.dp)
        val LARGE = DpSize(250.dp, 250.dp)
    }
}

class PaintingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = PaintingWidget()
}

/** What the widget shows of a painting; [id] empty in the picker's preview, which opens nothing. */
data class Shown(val id: String, val title: String, val painted: Int, val total: Int, val picture: ImageProvider?) {
    val percent get() = percentPainted(painted, total)
}

/** The thumbnail the gallery shows, which every save draws again. */
private suspend fun picture(library: Library, meta: PaintingMeta): Bitmap? = withContext(Dispatchers.IO) {
    BitmapFactory.decodeFile(library.thumbnailFile(meta.id).path)
}

/**
 * Moonlight's colors, with the home screen's own surfaces on Android 12 and later, so the
 * widget sits in the wallpaper's tint while its accent stays Nightshade.
 */
private fun widgetColors(context: Context) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    ColorProviders(
        light = dynamicLightColorScheme(context).withAccent(LightColors),
        dark = dynamicDarkColorScheme(context).withAccent(DarkColors),
    )
} else {
    ColorProviders(light = LightColors, dark = DarkColors)
}

private fun ColorScheme.withAccent(moonlight: ColorScheme) = copy(
    primary = moonlight.primary,
    onPrimary = moonlight.onPrimary,
    primaryContainer = moonlight.primaryContainer,
    onPrimaryContainer = moonlight.onPrimaryContainer,
)

@Composable
fun PaintingWidgetContent(shown: Shown?) {
    val context = LocalContext.current
    val open: Action? = when {
        shown == null -> actionStartActivity(Links.intent(context, Links.new()))
        shown.id.isEmpty() -> null
        else -> actionStartActivity(Links.intent(context, Links.paint(shown.id)))
    }
    var modifier = GlanceModifier.fillMaxSize().appWidgetBackground().background(GlanceTheme.colors.background)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) modifier = modifier.cornerRadius(android.R.dimen.system_app_widget_background_radius)
    if (open != null) modifier = modifier.clickable(open)
    Box(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
        val size = LocalSize.current
        when {
            shown == null -> Empty()
            size.width >= PaintingWidget.WIDE.width && size.height >= PaintingWidget.LARGE.height -> Large(shown)
            size.width >= PaintingWidget.WIDE.width -> Wide(shown)
            else -> Small(shown)
        }
    }
}

@Composable
private fun Small(shown: Shown) {
    Column(GlanceModifier.fillMaxSize().semantics { contentDescription = describe(shown) }, horizontalAlignment = Alignment.CenterHorizontally) {
        Picture(shown, GlanceModifier.defaultWeight().fillMaxWidth())
        if (LocalSize.current.height >= 140.dp) {
            Spacer(GlanceModifier.height(6.dp))
            Text("${shown.percent}%", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, fontFamily = FontFamily.Serif))
        }
    }
}

@Composable
private fun Wide(shown: Shown) {
    Row(GlanceModifier.fillMaxSize().semantics { contentDescription = describe(shown) }, verticalAlignment = Alignment.CenterVertically) {
        Picture(shown, GlanceModifier.defaultWeight().fillMaxSize())
        Spacer(GlanceModifier.width(14.dp))
        Column(GlanceModifier.defaultWeight()) {
            Words(shown, maxLines = 3)
        }
    }
}

@Composable
private fun Large(shown: Shown) {
    Column(GlanceModifier.fillMaxSize().semantics { contentDescription = describe(shown) }) {
        Picture(shown, GlanceModifier.defaultWeight().fillMaxWidth())
        Spacer(GlanceModifier.height(12.dp))
        Words(shown, maxLines = 2)
    }
}

@Composable
private fun Picture(shown: Shown, modifier: GlanceModifier) {
    val picture = shown.picture
    Box(modifier, contentAlignment = Alignment.Center) {
        if (picture != null) {
            var image = GlanceModifier.fillMaxSize()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) image = image.cornerRadius(android.R.dimen.system_app_widget_inner_radius)
            Image(picture, contentDescription = null, modifier = image, contentScale = androidx.glance.layout.ContentScale.Fit)
        }
    }
}

@Composable
private fun Words(shown: Shown, maxLines: Int) {
    Text(
        shown.title,
        maxLines = maxLines,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Serif),
    )
    Spacer(GlanceModifier.height(4.dp))
    Text(
        "${shown.percent}% painted",
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
    )
    Spacer(GlanceModifier.height(10.dp))
    LinearProgressIndicator(
        progress = if (shown.total > 0) shown.painted / shown.total.toFloat() else 0f,
        modifier = GlanceModifier.fillMaxWidth().height(4.dp),
        color = GlanceTheme.colors.primary,
        backgroundColor = GlanceTheme.colors.surfaceVariant,
    )
}

@Composable
private fun Empty() {
    Column(
        GlanceModifier.fillMaxSize().semantics { contentDescription = "Paint by Moonlight. Start a painting and it waits for you here." },
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(ImageProvider(R.drawable.ic_moon_stars), contentDescription = null, modifier = GlanceModifier.size(28.dp))
        Spacer(GlanceModifier.height(8.dp))
        Text(
            "Start a painting and it waits for you here.",
            maxLines = 3,
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center),
        )
    }
}

private fun describe(shown: Shown) = "${shown.title}, ${shown.percent}% painted"
