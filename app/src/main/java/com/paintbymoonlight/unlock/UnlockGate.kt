package com.paintbymoonlight.unlock

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Stands before every new painting: [start] goes on at once while the painter may start one, and
 * otherwise opens [UnlockSheet], whose purchase then goes on. Place [Sheet] once in the screen.
 */
class UnlockGate internal constructor(val paintings: UnlimitedPaintings, private val scope: CoroutineScope) {
    /** What goes on once the unlock is bought, while the sheet is up. */
    private var waiting by mutableStateOf<(() -> Unit)?>(null)
    private var asking = false

    fun start(then: () -> Unit) {
        if (asking) return
        asking = true
        scope.launch {
            try {
                if (paintings.mayStart()) then() else waiting = then
            } finally {
                asking = false
            }
        }
    }

    @Composable
    fun Sheet() {
        val then = waiting ?: return
        UnlockSheet(paintings, onDismiss = { waiting = null }, onUnlocked = { waiting = null; then() })
    }
}

@Composable
fun rememberUnlockGate(paintings: UnlimitedPaintings): UnlockGate {
    val scope = rememberCoroutineScope()
    return remember(paintings) { UnlockGate(paintings, scope) }
}

/** The quiet line under Start: the free paintings left, until the unlock is bought. */
@Composable
fun FreePaintingsLeft(paintings: UnlimitedPaintings, modifier: Modifier = Modifier) {
    val unlocked by paintings.unlocked.collectAsStateWithLifecycle()
    val free by paintings.free.collectAsStateWithLifecycle()
    if (unlocked) return
    val left = paintings.left(free)
    Text(
        when (left) {
            0 -> "Your next free painting comes tomorrow."
            1 -> "1 free painting left"
            else -> "$left free paintings left"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}
