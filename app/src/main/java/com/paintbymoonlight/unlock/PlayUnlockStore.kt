package com.paintbymoonlight.unlock

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/**
 * Google Play Billing: the one-time product [UnlimitedPaintings.PRODUCT_ID], bought, acknowledged
 * (Play refunds a purchase left unacknowledged for three days, so every check acknowledges what it
 * finds) and found again with [owned], which is all Restore needs on Play. Pending purchases are
 * accepted and unlock once they go through. Calls on the main thread, as the library's callbacks
 * come.
 */
class PlayUnlockStore(context: Context, private val scope: CoroutineScope) : UnlockStore, PurchasesUpdatedListener {
    private val client = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        // Reconnects by itself when the Play Store's service drops a connection once made.
        .enableAutoServiceReconnection()
        .build()
    private val connecting = Mutex()
    private var product: ProductDetails? = null
    /** The answer [buy] waits for, which [onPurchasesUpdated] brings. */
    private var buying: CompletableDeferred<PurchaseOutcome>? = null
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val changes: Flow<Unit> = _changes

    override suspend fun price(): String =
        details().oneTimePurchaseOfferDetails?.formattedPrice ?: throw UnlockException.unavailable

    override suspend fun owned(): Boolean? {
        if (!connect()) return null
        val result = client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build())
        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "purchases: ${result.billingResult.debugMessage}")
            return null
        }
        return settle(result.purchasesList)
    }

    override suspend fun buy(activity: Activity): PurchaseOutcome {
        val details = details()
        val offer = details.oneTimePurchaseOfferDetails ?: throw UnlockException.unavailable
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .apply { offer.offerToken?.let { setOfferToken(it) } }
                    .build()))
            .build()
        val waiting = CompletableDeferred<PurchaseOutcome>()
        buying = waiting
        try {
            val launched = client.launchBillingFlow(activity, params)
            return when (launched.responseCode) {
                BillingResponseCode.OK -> waiting.await()
                BillingResponseCode.USER_CANCELED -> PurchaseOutcome.Cancelled
                // Bought before, on this account: settle it as Restore would.
                BillingResponseCode.ITEM_ALREADY_OWNED ->
                    if (owned() == true) PurchaseOutcome.Unlocked else throw UnlockException.failed
                else -> {
                    Log.w(TAG, "purchase sheet: ${launched.responseCode} ${launched.debugMessage}")
                    throw UnlockException.unavailable
                }
            }
        } finally {
            buying = null
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        scope.launch {
            val ours = purchases.orEmpty().filter { UnlimitedPaintings.PRODUCT_ID in it.products }
            val outcome = when (result.responseCode) {
                BillingResponseCode.OK -> when {
                    settle(ours) -> PurchaseOutcome.Unlocked
                    ours.any { it.purchaseState == Purchase.PurchaseState.PENDING } -> PurchaseOutcome.Pending
                    else -> PurchaseOutcome.Cancelled
                }
                BillingResponseCode.USER_CANCELED -> PurchaseOutcome.Cancelled
                BillingResponseCode.ITEM_ALREADY_OWNED -> if (owned() == true) PurchaseOutcome.Unlocked else null
                else -> null
            }
            val waiting = buying
            when {
                waiting == null -> _changes.tryEmit(Unit)
                outcome == null -> {
                    Log.w(TAG, "purchase: ${result.responseCode} ${result.debugMessage}")
                    waiting.completeExceptionally(UnlockException.failed)
                }
                else -> waiting.complete(outcome)
            }
        }
    }

    /** Whether [purchases] hold the unlock, bought and paid for; acknowledges it if it isn't yet. */
    private suspend fun settle(purchases: List<Purchase>): Boolean {
        val bought = purchases.filter {
            UnlimitedPaintings.PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        for (purchase in bought.filterNot { it.isAcknowledged }) {
            val acknowledged = client.acknowledgePurchase(
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build())
            if (acknowledged.responseCode != BillingResponseCode.OK) {
                // Asked again at the next check: the app comes to the foreground, or Restore.
                Log.w(TAG, "acknowledge: ${acknowledged.responseCode} ${acknowledged.debugMessage}")
            }
        }
        return bought.isNotEmpty()
    }

    private suspend fun details(): ProductDetails {
        product?.let { return it }
        if (!connect()) throw UnlockException.unavailable
        val query = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(UnlimitedPaintings.PRODUCT_ID)
                    .setProductType(ProductType.INAPP)
                    .build()))
            .build()
        val result = client.queryProductDetails(query)
        val found = result.productDetailsList?.firstOrNull()
        if (found == null) {
            Log.w(TAG, "no product ${UnlimitedPaintings.PRODUCT_ID}: ${result.billingResult.responseCode} ${result.billingResult.debugMessage}")
            throw UnlockException.unavailable
        }
        product = found
        return found
    }

    /**
     * Connects to the Play Store's billing service, trying again a few times with a growing pause
     * (it can be busy updating itself); false when it can't be reached or this device has no
     * billing at all.
     */
    private suspend fun connect(): Boolean = connecting.withLock {
        if (client.isReady) return@withLock true
        repeat(ATTEMPTS) { attempt ->
            val result = suspendCancellableCoroutine { going ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(result: BillingResult) {
                        if (going.isActive) going.resume(result.responseCode)
                    }

                    override fun onBillingServiceDisconnected() {
                        if (going.isActive) going.resume(BillingResponseCode.SERVICE_DISCONNECTED)
                    }
                })
            }
            when (result) {
                BillingResponseCode.OK -> return@withLock true
                // No Play Store, or one too old for billing: no pause will change that.
                BillingResponseCode.BILLING_UNAVAILABLE -> return@withLock false
            }
            if (attempt < ATTEMPTS - 1) delay(FIRST_PAUSE_MS shl attempt)
        }
        false
    }

    private companion object {
        const val TAG = "UnlockStore"
        const val ATTEMPTS = 3
        const val FIRST_PAUSE_MS = 500L
    }
}
