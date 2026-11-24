package com.paintbymoonlight.unlock

import android.app.Activity
import kotlinx.coroutines.flow.Flow

/**
 * Where Unlimited Paintings is bought: Google Play ([PlayUnlockStore]) in release builds, and in
 * debug builds, their tests and CI, [FakeUnlockStore], so nothing but a release build ever asks
 * Play for anything.
 */
interface UnlockStore {
    /** The price as Play writes it for this painter ("$4.99", "4,99 €"). */
    suspend fun price(): String

    /** Whether this Google account owns the unlock (bought, not refunded); null when the store can't be asked right now. */
    suspend fun owned(): Boolean?

    /** Opens the store's purchase sheet over [activity] and waits for its answer. */
    suspend fun buy(activity: Activity): PurchaseOutcome

    /** A purchase arrived outside [buy]: a pending one went through, or one was made elsewhere. */
    val changes: Flow<Unit>
}

/** What buying came to. */
enum class PurchaseOutcome {
    Unlocked,
    /** Waiting for a payment that takes time (cash at a shop, a family's approval): [UnlockStore.changes] hears when it goes through. */
    Pending,
    Cancelled,
}

/** Why the unlock couldn't be bought; its message is the painter's. */
class UnlockException(message: String) : Exception(message) {
    companion object {
        val unavailable get() = UnlockException("Unlimited Paintings can’t be bought right now. Check your connection and try again.")
        val failed get() = UnlockException("Google Play couldn’t finish the purchase. Try again in a moment.")
    }
}
