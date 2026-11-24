package com.paintbymoonlight.print

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wallet.PaymentData
import com.paintbymoonlight.core.Core
import com.paintbymoonlight.core.Scene
import com.paintbymoonlight.data.Library
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One Big Sheet's screen state: the painting's scene, the sheet picked and where it goes, the
 * relay's price, and the order. The sheet's PDF leaves the device only when the painter orders:
 * it goes up while Google Pay's sheet is open, under the order's key, so paying waits on the
 * charge alone (the iOS app's `BigSheetView`).
 */
class BigSheetViewModel(
    private val library: Library,
    private val id: String,
    private val relay: PrintRelay = PrintRelay(),
) : ViewModel() {
    var title by mutableStateOf("")
        private set
    var scene by mutableStateOf<Scene?>(null)
        private set

    /** A wide painting's sheet is drawn and named long side across. */
    val isWide: Boolean get() = scene?.let { it.width > it.height } ?: false
    var loadFailed by mutableStateOf(false)
        private set

    var product by mutableStateOf(BigSheetProduct.default)
        private set
    var country by mutableStateOf(BigSheetCountries.default())
        private set

    var quote by mutableStateOf<BigSheetQuote?>(null)
        private set
    var quoteFailed by mutableStateOf(false)
        private set

    /** The sheet as it will print, and the painter's own letter page at the same scale beside it. */
    var picture by mutableStateOf<Bitmap?>(null)
        private set
    var pagePicture by mutableStateOf<Bitmap?>(null)
        private set
    var printsSmallNumbers by mutableStateOf(false)
        private set

    var placing by mutableStateOf(false)
        private set
    var placed by mutableStateOf<PrintRelay.Placed?>(null)
        private set
    var failure by mutableStateOf<OrderFailure?>(null)
        private set

    enum class OrderFailure { Declined, Pending, PriceMoved, PaymentDeclined, Refunded, GooglePay, Address, Other }

    /** The quote Google Pay's sheet shows, which the painter agrees to by paying. */
    private var agreed: BigSheetQuote? = null

    /**
     * Each sheet's order key, the same for every try of one order until it is placed or certainly
     * refused; kept on disk once a try starts ([PendingOrders]), so leaving mid order can't
     * order it twice.
     */
    private val keys = mutableMapOf<String, String>()
    private val pending = PendingOrders(library.folder(id))
    private var upload: Upload? = null
    private var quoteJob: Job? = null
    private var drawJob: Job? = null

    private class Upload(val key: String, val product: BigSheetProduct, val task: Deferred<Unit>) {
        @Volatile var failed = false
    }

    /** The quote on screen, when it is for the sheet and country picked. */
    val currentQuote: BigSheetQuote? get() = quote?.takeIf { it.product == product.id }

    init {
        viewModelScope.launch {
            try {
                keys.putAll(pending.load())
                val meta = library.meta(id) ?: error("no painting $id")
                title = meta.title
                val loaded = Core.scene(library.template(id), meta.seed, meta.nicknameVersion)
                pagePicture = withContext(Dispatchers.Default) {
                    SheetDrawing(loaded, meta.title, LETTER_SHORT, LETTER_LONG).image(500)
                }
                scene = loaded
                draw()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Loading a painting for One Big Sheet failed", e)
                loadFailed = true
            }
        }
        fetchQuote()
    }

    fun choose(surface: BigSheetProduct.Surface) = choose(product.on(surface))

    fun choose(next: BigSheetProduct) {
        if (next == product) return
        // Another size is another order, under its own key.
        product = next
        draw()
        fetchQuote()
    }

    fun shipTo(code: String) {
        if (code == country) return
        country = code
        // The price on screen is the country's: the last one's is never paid at.
        quote = null
        fetchQuote()
    }

    fun retryQuote() = fetchQuote()

    fun dismissFailure() {
        failure = null
    }

    private fun draw() {
        val scene = scene ?: return
        val product = product
        drawJob?.cancel()
        drawJob = viewModelScope.launch {
            val (image, small) = withContext(Dispatchers.Default) {
                val sheet = SheetDrawing(scene, title, product.shortPoints, product.longPoints)
                sheet.image(1400) to sheet.printsSmallNumbers
            }
            picture = image
            printsSmallNumbers = small
        }
    }

    private fun fetchQuote() {
        val product = product
        val place = BigSheetPlace(country)
        quoteFailed = false
        quoteJob?.cancel()
        quoteJob = viewModelScope.launch {
            try {
                quote = relay.quote(product, place)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Pricing a big sheet failed", e)
                quoteFailed = true
            }
        }
    }

    private fun keyFor(product: BigSheetProduct): String = keys.getOrPut(product.id) { PrintRelay.newKey() }

    /** The sheet sent under the order's key, once per key; a failed send goes again. */
    private fun sendSheet(): Deferred<Unit> {
        val product = product
        val key = keyFor(product)
        upload?.let { if (it.key == key && it.product == product && !it.failed) return it.task }
        val scene = scene ?: error("no scene")
        val title = title
        lateinit var sent: Upload
        val relay = relay
        val task = PendingOrders.scope.async(start = CoroutineStart.LAZY) {
            try {
                val pdf = withContext(Dispatchers.Default) { SheetDrawing(scene, title, product.shortPoints, product.longPoints).pdf() }
                relay.upload(pdf, key)
            } catch (e: Exception) {
                sent.failed = true
                throw e
            }
        }
        sent = Upload(key, product, task)
        upload = sent
        task.start()
        return task
    }

    /** Google Pay's sheet is about to open at [quote]'s price: the sheet goes up while the painter looks it over. */
    fun willPay(quote: BigSheetQuote) {
        if (scene == null) return
        agreed = quote
        placing = true
        failure = null
        sendSheet()
    }

    /** Google Pay's sheet went away without paying. */
    fun paymentCancelled() {
        placing = false
    }

    fun paymentFailed() {
        placing = false
        failure = OrderFailure.GooglePay
    }

    /** Google Pay paid at the agreed price: the order goes to the relay with Stripe's token. */
    fun paid(data: PaymentData) {
        val agreed = agreed
        val paid = GooglePay.paid(data)
        if (agreed == null || paid == null) {
            placing = false
            failure = if (agreed == null) OrderFailure.GooglePay else OrderFailure.Address
            return
        }
        val payment = BigSheetPayment(
            price = agreed.price, currency = agreed.currency, stripeToken = paid.stripeToken,
            network = paid.network, instrument = paid.instrument, place = BigSheetPlace(country),
        )
        place(paid.recipient, payment)
    }

    /** A test order, unpaid, to a test address: the relay's sandbox prints and sends nothing. */
    fun placeTestOrder() {
        if (placing || scene == null) return
        placing = true
        failure = null
        place(BigSheetRecipient.testOrder, null)
    }

    /**
     * Places the order in the app's scope, not the screen's: once a try starts, its key is on
     * disk, and leaving the screen neither drops the order nor lets a later try order it again.
     */
    private fun place(recipient: BigSheetRecipient, payment: BigSheetPayment?) {
        val product = product
        val key = keyFor(product)
        val sheet = sendSheet()
        val relay = relay
        val pending = pending
        PendingOrders.scope.launch {
            try {
                pending.put(product, key)
                sheet.await()
                val order = relay.order(product, recipient, payment, key)
                pending.remove(product, key)
                if (keys[product.id] == key) keys.remove(product.id)
                placed = order
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Placing a big sheet's order failed", e)
                if (settle(e, product)) {
                    pending.remove(product, key)
                    if (keys[product.id] == key) keys.remove(product.id)
                }
            } finally {
                placing = false
            }
        }
    }

    /**
     * Says what went wrong with a try, and whether the relay certainly gave the order up, so it
     * takes a new key. One that may be under way, or whose answer never came, keeps its key, so
     * trying again can't order it twice.
     */
    private fun settle(error: Exception, product: BigSheetProduct): Boolean {
        val status = (error as? PrintRelay.Failure.Refused)?.status
        val dropped = status in DROPPED
        failure = when {
            status == 451 -> OrderFailure.Declined
            status == 409 -> OrderFailure.PriceMoved
            status == 402 -> OrderFailure.PaymentDeclined
            status == 410 -> OrderFailure.Refunded
            error is PrintRelay.Failure.Pending || (status != null && !dropped) -> OrderFailure.Pending
            else -> OrderFailure.Other
        }
        // A price that moved since it was shown is fetched again for the painter to see.
        if (status == 409 && product == this.product) fetchQuote()
        return dropped
    }

    companion object {
        private const val TAG = "BigSheet"
        private const val LETTER_SHORT = 612
        private const val LETTER_LONG = 792

        /** The relay's answers after which it certainly holds no order under the key (print.js's header). */
        private val DROPPED = setOf(400, 402, 409, 410, 412, 413, 415, 429, 451, 502)
    }
}
