package com.paintbymoonlight.unlock

import android.app.Activity
import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/**
 * Unlimited Paintings, the app's one purchase, as on iOS: free to download, paid once, it lets a
 * painter make as many paintings as they like. Without it they have [FreePaintings]: five, one
 * back each day, every new painting taking one. No ads, no subscription.
 *
 * Start Painting asks [mayStart] (through [UnlockGate]) and reports [started] once the painting
 * is saved; [UnlockSheet] sells it. Purchases are checked with Google Play on the device: no
 * server and no account of ours. Ownership is remembered from Play's last answer, so a painter
 * who bought it keeps it offline or without the Play Store, and loses it once Play says it was
 * refunded.
 */
class UnlimitedPaintings(
    private val store: UnlockStore,
    private val memory: UnlockMemory,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val _unlocked = MutableStateFlow(memory.owned)
    /** Bought, as Play last said. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _price = MutableStateFlow<String?>(null)
    /** The unlock's price as Play writes it, once loaded. */
    val price: StateFlow<String?> = _price.asStateFlow()

    private val _free = MutableStateFlow(memory.free)
    /** The free paintings as last counted; [left] reads them as of now. */
    val free: StateFlow<FreePaintings> = _free.asStateFlow()

    /** Play has answered whether the unlock is owned, this launch. */
    private var checked = false

    /** Free paintings left now, of [free]. */
    fun left(free: FreePaintings = _free.value): Int = free.at(now(), zone()).left

    /**
     * Whether a new painting may start: once the unlock is bought or while a free painting is
     * left. The first time, it asks Play what is owned, waiting a moment at most: Play out of
     * reach answers with what it said before.
     */
    suspend fun mayStart(): Boolean {
        if (!checked) withTimeoutOrNull(FIRST_CHECK_MS) { refresh() }
        return _unlocked.value || left() > 0
    }

    /** A new painting was saved: it took a free painting, unless the unlock is bought. */
    fun started() {
        if (_unlocked.value) return
        val taken = _free.value.take(now(), zone()) ?: return
        _free.value = taken
        memory.free = taken
    }

    /** Asks Play whether the unlock is owned (also acknowledging a purchase not yet acknowledged); null when it couldn't be asked. */
    suspend fun refresh(): Boolean? {
        val owned = store.owned() ?: return null
        checked = true
        _unlocked.value = owned
        memory.owned = owned
        return owned
    }

    suspend fun loadPrice() {
        if (_price.value == null) _price.value = store.price()
    }

    suspend fun buy(activity: Activity): PurchaseOutcome {
        val outcome = store.buy(activity)
        if (outcome == PurchaseOutcome.Unlocked) {
            checked = true
            _unlocked.value = true
            memory.owned = true
        }
        return outcome
    }

    /** Follows purchases that arrive outside [buy] for as long as [scope] lives. */
    fun follow(scope: CoroutineScope) {
        scope.launch { store.changes.collect { refresh() } }
    }

    companion object {
        const val PRODUCT_ID = "unlimited_paintings"
        private const val FIRST_CHECK_MS = 4_000L

        /**
         * The running app's: Play in release builds, [FakeUnlockStore] in debug ones
         * ([launchStore]). What is owned is asked again each time the app comes to the
         * foreground, as Play asks of apps: a promo code redeemed in the Play Store, or a pending
         * payment that went through while the app was closed, arrives that way.
         */
        fun forLaunch(app: Application): UnlimitedPaintings {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val paintings = UnlimitedPaintings(launchStore(app, scope), UnlockMemory.of(app))
            paintings.follow(scope)
            ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) scope.launch { paintings.refresh() }
            })
            return paintings
        }
    }
}
