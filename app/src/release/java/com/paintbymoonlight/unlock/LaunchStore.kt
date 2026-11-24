package com.paintbymoonlight.unlock

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** Release builds buy from Google Play. */
internal fun launchStore(context: Context, scope: CoroutineScope): UnlockStore = PlayUnlockStore(context, scope)
