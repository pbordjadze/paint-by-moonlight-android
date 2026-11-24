package com.paintbymoonlight.print

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wallet.IsReadyToPayRequest
import com.google.android.gms.wallet.PaymentData
import com.google.android.gms.wallet.PaymentDataRequest
import com.google.android.gms.wallet.PaymentsClient
import com.google.android.gms.wallet.Wallet
import com.google.android.gms.wallet.WalletConstants
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Google Pay for One Big Sheet: Google's sheet with the price, which also asks where the sheet goes
 * (name, address, email), so the screen needs no form, as Apple Pay's sheet does on iOS. Google Pay
 * hands back a Stripe token made with the relay's publishable key (the gateway "stripe"), and the
 * relay charges it as it charges an Apple Pay token. Physical goods are paid for outside Play's
 * billing, which Play's payments policy allows.
 */
object GooglePay {
    private val networks = JSONArray(listOf("AMEX", "DISCOVER", "MASTERCARD", "VISA"))
    private val authMethods = JSONArray(listOf("PAN_ONLY", "CRYPTOGRAM_3DS"))

    fun client(context: Context, config: GooglePayConfig): PaymentsClient {
        val environment = if (config.environment == "PRODUCTION") WalletConstants.ENVIRONMENT_PRODUCTION else WalletConstants.ENVIRONMENT_TEST
        val options = Wallet.WalletOptions.Builder().setEnvironment(environment).build()
        return Wallet.getPaymentsClient(context, options)
    }

    /** Whether this device can pay with Google Pay (a card saved, or one it can add). */
    suspend fun isReady(client: PaymentsClient): Boolean {
        val request = JSONObject()
            .put("apiVersion", 2).put("apiVersionMinor", 0)
            .put("allowedPaymentMethods", JSONArray().put(baseCard()))
        val task = runCatching { client.isReadyToPay(IsReadyToPayRequest.fromJson(request.toString())) }.getOrNull() ?: return false
        return task.awaitOrNull() == true
    }

    /**
     * The sheet's request: the quote's price as final, shipping only to [country] (the country the
     * price was quoted for: Google Pay can't price again as the painter picks an address), and the
     * painter's email, where the printer sends tracking and Stripe the receipt.
     */
    fun request(config: GooglePayConfig, quote: BigSheetQuote, item: String, country: String): PaymentDataRequest {
        val merchant = JSONObject().put("merchantName", "Paint by Moonlight")
        config.merchantId?.let { merchant.put("merchantId", it) }
        val request = JSONObject()
            .put("apiVersion", 2).put("apiVersionMinor", 0)
            .put("allowedPaymentMethods", JSONArray().put(card(config)))
            .put(
                "transactionInfo",
                JSONObject()
                    .put("totalPriceStatus", "FINAL")
                    .put("totalPrice", quote.price)
                    .put("totalPriceLabel", "Total")
                    .put("currencyCode", quote.currency)
                    .put("countryCode", "US")
                    .put("checkoutOption", "COMPLETE_IMMEDIATE_PURCHASE")
                    .put(
                        "displayItems",
                        JSONArray().put(JSONObject().put("label", item).put("type", "LINE_ITEM").put("price", quote.price)),
                    ),
            )
            .put("merchantInfo", merchant)
            .put("emailRequired", true)
            .put("shippingAddressRequired", true)
            .put("shippingAddressParameters", JSONObject().put("allowedCountryCodes", JSONArray().put(country)).put("phoneNumberRequired", false))
        return PaymentDataRequest.fromJson(request.toString())
    }

    /** The Google Pay button's payment methods: the card, tokenized for Stripe. */
    fun allowedPaymentMethods(config: GooglePayConfig): String = JSONArray().put(card(config)).toString()

    private fun card(config: GooglePayConfig): JSONObject = baseCard().put(
        "tokenizationSpecification",
        JSONObject().put("type", "PAYMENT_GATEWAY").put(
            "parameters",
            JSONObject()
                .put("gateway", config.gateway)
                .put("stripe:version", config.stripeVersion)
                .put("stripe:publishableKey", config.publishableKey),
        ),
    )

    /** What Google Pay gave: where the sheet goes and the Stripe token, or null when something needed is missing. */
    class Paid(val recipient: BigSheetRecipient, val stripeToken: String, val network: String, val instrument: String)

    fun paid(data: PaymentData): Paid? = runCatching {
        val json = JSONObject(data.toJson())
        val method = json.getJSONObject("paymentMethodData")
        val token = JSONObject(method.getJSONObject("tokenizationData").getString("token")).getString("id")
        if (!token.startsWith("tok_")) return null
        val info = method.optJSONObject("info")
        val address = json.getJSONObject("shippingAddress")
        val line2 = listOf(address.optString("address2"), address.optString("address3")).filter { it.isNotBlank() }.joinToString(", ")
        val recipient = BigSheetRecipient(
            name = address.optString("name").trim(),
            email = json.optString("email").trim(),
            line1 = address.optString("address1").trim(),
            line2 = line2.trim(),
            city = address.optString("locality").trim(),
            state = address.optString("administrativeArea").trim(),
            zip = address.optString("postalCode").trim(),
            country = address.optString("countryCode").trim().uppercase(),
        )
        if (!recipient.isComplete) return null
        val network = info?.optString("cardNetwork").orEmpty()
        val details = info?.optString("cardDetails").orEmpty()
        Paid(recipient, token, network, listOf(network, details).filter { it.isNotEmpty() }.joinToString(" "))
    }.getOrNull()

    private fun baseCard(): JSONObject = JSONObject()
        .put("type", "CARD")
        .put(
            "parameters",
            JSONObject()
                .put("allowedAuthMethods", authMethods)
                .put("allowedCardNetworks", networks)
                .put("billingAddressRequired", false),
        )

    private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { done ->
            continuation.resume(if (done.isSuccessful) done.result else null)
        }
    }
}
