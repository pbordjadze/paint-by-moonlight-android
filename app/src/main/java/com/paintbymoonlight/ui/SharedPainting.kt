package com.paintbymoonlight.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/** The app's shared transition, around its NavHost. */
val LocalSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** The destination's enter and exit, which a shared element follows. */
val LocalDestinationTransition = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Bounds a painting's picture shares between screens, so a painting grows out of its gallery
 * card into the canvas and goes back into it. Nothing outside a shared transition (a preview).
 */
@Composable
fun paintingBounds(id: String, clip: Shape): Modifier {
    val shared = LocalSharedTransition.current ?: return Modifier
    val destination = LocalDestinationTransition.current ?: return Modifier
    return with(shared) {
        Modifier.sharedBounds(
            rememberSharedContentState("painting-$id"),
            destination,
            clipInOverlayDuringTransition = OverlayClip(clip),
        )
    }
}
