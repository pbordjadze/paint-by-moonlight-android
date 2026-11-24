package com.paintbymoonlight.unlock

import android.app.Activity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Debug builds' stand-in for Google Play, so no debug launch, test or CI run asks Play for
 * anything (the iOS app's `UnlockStoreStub`). Buying unlocks after a moment, in memory: each
 * launch starts locked again, while the free paintings are counted as in a release build.
 * [purchase] says what buying does, for tests of the sheet's other answers.
 */
class FakeUnlockStore(
    private var isOwned: Boolean = false,
    /** Null fails, as Play out of reach would. */
    var purchase: PurchaseOutcome? = PurchaseOutcome.Unlocked,
    /** What Restore finds. */
    var restorable: Boolean = false,
) : UnlockStore {
    override suspend fun price() = "$4.99"

    override suspend fun owned(): Boolean {
        if (restorable) isOwned = true
        return isOwned
    }

    override suspend fun buy(activity: Activity): PurchaseOutcome {
        delay(300)
        val outcome = purchase ?: throw UnlockException.unavailable
        if (outcome == PurchaseOutcome.Unlocked) isOwned = true
        return outcome
    }

    override val changes: Flow<Unit> = emptyFlow()
}
