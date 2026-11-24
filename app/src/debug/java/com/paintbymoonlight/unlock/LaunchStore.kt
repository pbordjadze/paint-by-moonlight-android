package com.paintbymoonlight.unlock

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** Debug builds, their tests and CI never ask Play: they buy from [FakeUnlockStore]. */
internal fun launchStore(context: Context, scope: CoroutineScope): UnlockStore = FakeUnlockStore()
