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

    enum class OrderFailure { Declined, Pending, PriceMoved, PaymentDeclined, GooglePay, Other }

    /** One order's key for every try of it, until it is placed or refused. */
    private var orderKey = PrintRelay.newKey()
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
                val meta = library.paintings.value.firstOrNull { it.id == id } ?: error("no painting $id")
                title = meta.title
                val loaded = Core.scene(library.template(id), meta.seed)
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
        product = next
        // Another size is another order: an earlier try's key never answers for it.
        orderKey = PrintRelay.newKey()
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

    /** The sheet sent under the order's key, once per key; a failed send goes again. */
    private fun sendSheet(): Deferred<Unit> {
        val key = orderKey
        val product = product
        upload?.let { if (it.key == key && it.product == product && !it.failed) return it.task }
        val scene = scene ?: error("no scene")
        val title = title
        lateinit var sent: Upload
        val task = viewModelScope.async(start = CoroutineStart.LAZY) {
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

    /** Google Pay's sheet is about to open: the sheet goes up while the painter looks it over. */
    fun willPay() {
        if (scene == null) return
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

    /** Google Pay paid at [agreed]'s price: the order goes to the relay with Stripe's token. */
    fun paid(data: PaymentData, agreed: BigSheetQuote) {
        val paid = GooglePay.paid(data)
        if (paid == null) {
            placing = false
            failure = OrderFailure.Other
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

    private fun place(recipient: BigSheetRecipient, payment: BigSheetPayment?) {
        val product = product
        viewModelScope.launch {
            try {
                // The key as it stands now: a refused try gave its key up, and the sheet goes up again under the new one.
                val key = orderKey
                sendSheet().await()
                placed = relay.order(product, recipient, payment, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Placing a big sheet's order failed", e)
                settle(e)
            } finally {
                placing = false
            }
        }
    }

    /**
     * After a try that failed: only one the relay refused (it gave the order up) takes a new key;
     * one that may be under way, or whose answer never came, keeps it, so trying again can't order
     * it twice.
     */
    private fun settle(error: Exception) {
        val status = (error as? PrintRelay.Failure.Refused)?.status
        failure = when {
            error is PrintRelay.Failure.Pending -> OrderFailure.Pending
            status == 451 -> OrderFailure.Declined
            status == 409 -> OrderFailure.PriceMoved
            status == 402 -> OrderFailure.PaymentDeclined
            else -> OrderFailure.Other
        }
        if (status != null) orderKey = PrintRelay.newKey()
        // A price that moved since it was shown is fetched again for the painter to see.
        if (status == 409) fetchQuote()
    }

    companion object {
        private const val TAG = "BigSheet"
        private const val LETTER_SHORT = 612
        private const val LETTER_LONG = 792
    }
}
