package com.paintbymoonlight.print

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.UUID

/** The relay's price for a sheet: what the painter pays there, shipping and tax included. */
@Serializable
data class BigSheetQuote(
    val product: String,
    val currency: String,
    val price: String,
    /** While the relay talks to the print service's sandbox: what the print costs and what is kept. */
    val sandbox: Sandbox? = null,
    /** How to take Google Pay, when the relay offers it (its Stripe publishable key, never a secret). */
    val googlePay: GooglePayConfig? = null,
) {
    @Serializable
    data class Sandbox(val cost: String, val cut: String)

    /** An amount of the relay's ("58.00") as the painter reads money. */
    fun money(amount: String = price): String {
        val value = amount.toBigDecimalOrNull() ?: return amount
        val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
        runCatching { format.currency = Currency.getInstance(currency) }
        return format.format(value)
    }
}

/**
 * What Google Pay needs to make a Stripe token the relay can charge. The relay sends it with a
 * quote once it takes Google Pay (the patch beside this app's brief); without it the screen places
 * test orders, unpaid, as the iOS app does without Apple Pay.
 */
@Serializable
data class GooglePayConfig(
    val gateway: String = "stripe",
    val publishableKey: String,
    val stripeVersion: String = "2018-10-31",
    /** "TEST" or "PRODUCTION": Google Pay's environment, which goes live with the relay's keys. */
    val environment: String = "TEST",
    /** Google Pay's merchant id, from its Business Console; needed in production only. */
    val merchantId: String? = null,
)

/** Where a price is for: the country, and the city, state and postal code where known. */
@Serializable
data class BigSheetPlace(val country: String, val city: String = "", val state: String = "", val zip: String = "")

/** Where the sheet goes. Every field but [line2] and [state] is needed. */
@Serializable
data class BigSheetRecipient(
    val name: String,
    val email: String,
    val line1: String,
    val line2: String = "",
    val city: String,
    val state: String = "",
    val zip: String,
    val country: String,
) {
    val isComplete: Boolean
        get() = listOf(name, line1, city, zip, country).none { it.isBlank() } && email.contains('@') && email.contains('.')

    companion object {
        /** Where a test order goes: the sandbox prints and sends nothing, and the painter types nothing. */
        val testOrder = BigSheetRecipient(
            name = "Test Order", email = "test@example.com", line1 = "1 Test Street",
            city = "New York", state = "NY", zip = "10001", country = "US",
        )
    }
}

/** A Google Pay payment for the relay to charge: the price the painter agreed to and Stripe's token. */
data class BigSheetPayment(
    val price: String,
    val currency: String,
    /** The Stripe token (`tok_…`) Google Pay made with the relay's publishable key. */
    val stripeToken: String,
    val network: String,
    val instrument: String,
    /** Where the price was quoted, which the relay prices the order at again. */
    val place: BigSheetPlace,
)

/**
 * One Big Sheet's side of the print relay (paint-by-number's feedback-relay/print.js, whose header
 * is the contract): a price, the sheet kept under the order's key, and the order.
 */
class PrintRelay(private val base: String = BASE) {
    sealed class Failure(message: String) : IOException(message) {
        /**
         * The relay answered with this status: 402 a payment declined or missing, 409 a price that
         * moved since it was shown, 410 an order the print service refused (refunded), 412 no sheet
         * kept under the key, 451 a sheet the relay's check won't print (nothing charged).
         */
        class Refused(val status: Int) : Failure("HTTP $status")

        /** Under way already, or the relay couldn't tell: trying again won't order it twice. */
        class Pending : Failure("pending")

        class Malformed : Failure("malformed answer")
    }

    suspend fun quote(product: BigSheetProduct, place: BigSheetPlace): BigSheetQuote {
        val body = QuoteBody(product.id, place.country, place.city.ifEmpty { null }, place.state.ifEmpty { null }, place.zip.ifEmpty { null })
        val (status, text) = call("POST", "/v1/print/quotes", "application/json", json.encodeToString(body).toByteArray(), 30_000)
        if (status !in 200..299) throw Failure.Refused(status)
        return runCatching { json.decodeFromString<BigSheetQuote>(text) }.getOrElse { throw Failure.Malformed() }
    }

    /** Keeps the sheet's PDF under the order's key before it is paid for, so paying waits on the charge alone. */
    suspend fun upload(sheet: ByteArray, key: String) {
        val (status, _) = call("PUT", "/v1/print/sheets/$key", "application/pdf", sheet, 120_000)
        if (status !in 200..299) throw Failure.Refused(status)
    }

    /** Places the order (its sheet went up before, by its key); answers the order's name and whether it is a test. */
    suspend fun order(product: BigSheetProduct, recipient: BigSheetRecipient, payment: BigSheetPayment?, key: String): Placed {
        val body = OrderBody(
            key = key, product = product.id, recipient = recipient,
            price = payment?.price, currency = payment?.currency,
            payment = payment?.let { PaymentBody(it.stripeToken, it.network, it.instrument) },
            quotedAt = payment?.place,
        )
        // Only the charge waits behind it: the relay places the order on its own after.
        val (status, text) = call("POST", "/v1/print/orders", "application/json", json.encodeToString(body).toByteArray(), 25_000)
        if (status == 202) throw Failure.Pending()
        if (status !in 200..299) throw Failure.Refused(status)
        return runCatching { json.decodeFromString<Placed>(text) }.getOrElse { throw Failure.Malformed() }
    }

    @Serializable
    data class Placed(val order: String, val sandbox: Boolean = true, val paid: Boolean = false)

    @Serializable
    private data class QuoteBody(val product: String, val country: String, val city: String?, val state: String?, val zip: String?)

    @Serializable
    private data class OrderBody(
        val key: String,
        val product: String,
        val recipient: BigSheetRecipient,
        val price: String?,
        val currency: String?,
        val payment: PaymentBody?,
        val quotedAt: BigSheetPlace?,
    )

    /** The relay's `payment` for Google Pay: Stripe's token in place of Apple Pay's `data`. */
    @Serializable
    private data class PaymentBody(
        val stripeToken: String,
        val network: String,
        val instrument: String,
    )

    private suspend fun call(method: String, path: String, type: String, body: ByteArray, timeout: Int): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val connection = URL(base + path).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 15_000
                connection.readTimeout = timeout
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", type)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val stream = if (status < 400) connection.inputStream else connection.errorStream
                status to (stream?.use { it.readBytes().decodeToString() } ?: "")
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        /** The relay behind Give Feedback and One Big Sheet, as the iOS app's `FeedbackUploader.endpoint`. */
        const val BASE = "https://moonlight-feedback.paul-bordjadze.workers.dev"

        /** A key for a new order: 32 hex digits, the same on every try of one order. */
        fun newKey(): String = UUID.randomUUID().toString().replace("-", "").lowercase(Locale.ROOT)

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    }
}
