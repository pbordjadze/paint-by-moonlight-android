package com.paintbymoonlight.print

import android.graphics.Bitmap
import android.icu.util.LocaleData
import android.icu.util.ULocale
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wallet.contract.TaskResultContracts
import com.google.pay.button.ButtonTheme
import com.google.pay.button.ButtonType
import com.google.pay.button.PayButton
import com.paintbymoonlight.ui.theme.Moonlight
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * One Big Sheet (painting-company row 380): the painting's template printed whole on one poster
 * sheet of canvas or watercolor paper and mailed. The sheet as it will print beside a letter page
 * at one scale, what it prints on and its size, where it goes, the price with shipping, and Google
 * Pay, whose sheet asks for the address. The relay's sandbox prints and charges nothing, and the
 * screen says so.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BigSheetScreen(model: BigSheetViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val config = model.currentQuote?.googlePay
    val client = remember(config) { config?.let { GooglePay.client(context, it) } }
    var googlePayReady by remember(client) { mutableStateOf(false) }
    LaunchedEffect(client) { googlePayReady = client != null && GooglePay.isReady(client) }
    val payLauncher = rememberLauncherForActivityResult(TaskResultContracts.GetPaymentDataResult()) { result ->
        val data = result.result
        when {
            result.status.statusCode == CommonStatusCodes.SUCCESS && data != null -> model.paid(data)
            result.status.statusCode == CommonStatusCodes.CANCELED -> model.paymentCancelled()
            else -> model.paymentFailed()
        }
    }

    // While an order is under way the screen stays: its answer is what the painter waits for.
    BackHandler(enabled = model.placing) {}
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("One Big Sheet") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !model.placing) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            val placed = model.placed
            if (placed == null && !model.loadFailed) {
                OrderBar(
                    model = model,
                    googlePayReady = googlePayReady,
                    onGooglePay = { quote, payConfig ->
                        val payments = client ?: return@OrderBar
                        model.willPay(quote)
                        val request = GooglePay.request(payConfig, quote, "One Big Sheet", model.country)
                        payLauncher.launch(payments.loadPaymentData(request))
                    },
                )
            }
        },
    ) { padding ->
        val placed = model.placed
        when {
            model.loadFailed -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text("This painting couldn’t be opened.", style = MaterialTheme.typography.bodyLarge)
            }
            placed != null -> Confirmation(model.picture, placed, Modifier.padding(padding))
            else -> Choices(model, Modifier.padding(padding))
        }
    }

    model.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = model::dismissFailure,
            title = { Text("Couldn’t Place the Order") },
            text = {
                Text(
                    when (failure) {
                        BigSheetViewModel.OrderFailure.Declined -> "We can’t print this picture. Nothing was charged."
                        BigSheetViewModel.OrderFailure.Pending -> "This order may already be on its way. Check your email before ordering again."
                        BigSheetViewModel.OrderFailure.PriceMoved -> "The price changed. Nothing was charged: check the new price and order again."
                        BigSheetViewModel.OrderFailure.PaymentDeclined -> "The payment didn’t go through. Nothing was charged."
                        BigSheetViewModel.OrderFailure.Refunded -> "The printer couldn’t take this order; your payment was refunded."
                        BigSheetViewModel.OrderFailure.Address -> "Google Pay didn’t share a full address with a postal code."
                        BigSheetViewModel.OrderFailure.GooglePay -> "Google Pay couldn’t take the payment. Nothing was charged."
                        BigSheetViewModel.OrderFailure.Other -> "Check your connection and try again."
                    },
                )
            },
            confirmButton = { TextButton(onClick = model::dismissFailure) { Text("OK") } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Choices(model: BigSheetViewModel, modifier: Modifier) {
    val product = model.product
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Preview(model)
        Spacer(Modifier.height(8.dp))

        Label("Print on")
        Connected(
            options = BigSheetProduct.Surface.entries,
            selected = product.surface,
            label = { it.label },
            onSelect = model::choose,
        )
        Spacer(Modifier.height(16.dp))

        Label("Size")
        Connected(
            options = product.surface.products,
            selected = product,
            label = { sizeText(it.shortInches.toFloat(), it.longInches.toFloat()) },
            onSelect = model::choose,
        )
        Spacer(Modifier.height(16.dp))

        Label("Ship to")
        ShipTo(model.country, model::shipTo)
        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Price", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val quote = model.currentQuote
            when {
                quote != null -> Text(
                    "${quote.money()}, shipping included",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                model.quoteFailed -> TextButton(onClick = model::retryQuote) { Text("Try Again") }
                else -> LoadingIndicator(Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Notes(model)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
    )
}

/** A Material 3 Expressive connected button group: one choice of two. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> Connected(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Text(label(option), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ShipTo(country: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(BigSheetCountries.name(country), modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BigSheetCountries.codes
                .sortedBy { BigSheetCountries.name(it) }
                .forEach { code ->
                    DropdownMenuItem(text = { Text(BigSheetCountries.name(code)) }, onClick = { open = false; onPick(code) })
                }
        }
    }
}

@Composable
private fun Notes(model: BigSheetViewModel) {
    val notes = buildList {
        add(
            when (model.product.surface) {
                BigSheetProduct.Surface.Canvas ->
                    "Cotton canvas for acrylic paint. It comes rolled: lay it flat under something heavy for a day before painting."
                BigSheetProduct.Surface.WatercolorPaper ->
                    "Cotton watercolor paper with a soft texture. It comes rolled: lay it flat under something heavy for a day before painting."
            },
        )
        BigSheetSeason.cutoff(model.country)?.let { cutoff ->
            add("Order by ${cutoff.format(DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault()))} to have it for Christmas")
        }
        if (model.printsSmallNumbers) add("Some numbers print small at this size: the larger sheet reads more easily.")
        model.currentQuote?.let { quote ->
            quote.sandbox?.let { add("Test price: the print costs ${quote.money(it.cost)}, and ${quote.money(it.cut)} is yours.") }
        }
        add("Made to order and shipped within a week. Print only pictures that are yours to print.")
        add("Only this sheet and the address go to the printer, never your photo.")
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * The sheet beside the page a painter prints at home, both to one scale and named by their sizes,
 * so the size reads at a glance; the page shrinks as a larger sheet is picked.
 */
@Composable
private fun Preview(model: BigSheetViewModel) {
    val wide = model.scene?.let { it.width > it.height } ?: false
    val short by animateFloatAsState(model.product.shortInches.toFloat(), MaterialTheme.motionScheme.defaultSpatialSpec(), label = "short")
    val long by animateFloatAsState(model.product.longInches.toFloat(), MaterialTheme.motionScheme.defaultSpatialSpec(), label = "long")
    val sheetW = if (wide) long else short
    val sheetH = if (wide) short else long
    val pageW = if (wide) 11f else 8.5f
    val pageH = if (wide) 8.5f else 11f
    val gap = 1.5f
    val caption = 28.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(320.dp).padding(vertical = 16.dp)) {
        val unit: Dp = min(maxWidth.value / (sheetW + gap + pageW), (maxHeight - caption).value / max(sheetH, pageH)).dp
        Row(
            horizontalArrangement = Arrangement.spacedBy(unit * gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = "The sheet as it will print, ${sizeText(model.product.shortInches.toFloat(), model.product.longInches.toFloat())}"
                },
            ) {
                Paper(model.picture, unit * sheetW, unit * sheetH)
                Spacer(Modifier.height(8.dp))
                Text(sizeText(if (wide) model.product.longInches.toFloat() else model.product.shortInches.toFloat(),
                    if (wide) model.product.shortInches.toFloat() else model.product.longInches.toFloat()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clearAndSetSemantics { }) {
                Paper(model.pagePicture, unit * pageW, unit * pageH)
                Spacer(Modifier.height(8.dp))
                Text(sizeText(pageW, pageH), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A sheet of paper showing [picture], white whatever the screen's appearance. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Paper(picture: Bitmap?, width: Dp, height: Dp) {
    Box(
        Modifier
            .size(width, height)
            .shadow(8.dp, RoundedCornerShape(1.dp))
            .background(Moonlight.sheet),
        contentAlignment = Alignment.Center,
    ) {
        if (picture != null) {
            val image = remember(picture) { picture.asImageBitmap() }
            Image(image, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        } else {
            LoadingIndicator(Modifier.size(32.dp), color = Color.Gray)
        }
    }
}

/**
 * Google Pay where the relay takes it (its quote says how) and the device can; a test order,
 * unpaid, while the relay doesn't take it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OrderBar(
    model: BigSheetViewModel,
    googlePayReady: Boolean,
    onGooglePay: (BigSheetQuote, GooglePayConfig) -> Unit,
) {
    val quote = model.currentQuote
    val offered = quote?.googlePay
    val googlePay = offered?.takeIf { googlePayReady }
    val ready = quote != null && !model.placing && model.scene != null
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            when {
                // Google Pay's sheet is up, or the paid order is on its way to the relay.
                googlePay != null && model.placing -> Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) {
                    LoadingIndicator(Modifier.size(40.dp))
                }
                googlePay != null -> PayButton(
                    onClick = { onGooglePay(quote, googlePay) },
                    allowedPaymentMethods = GooglePay.allowedPaymentMethods(googlePay),
                    theme = if (isSystemInDarkTheme()) ButtonTheme.Light else ButtonTheme.Dark,
                    type = ButtonType.Order,
                    radius = 100.dp,
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
                offered == null -> Button(
                    onClick = model::placeTestOrder,
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    if (model.placing) {
                        LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Rounded.LocalShipping, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("Place Test Order")
                    }
                }
                else -> Text(
                    "Ordering a sheet needs Google Pay on this device.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The sheet on its way, with the seal of an order placed on its corner. */
@Composable
private fun Confirmation(picture: Bitmap?, placed: PrintRelay.Placed, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (picture != null) {
            val image = remember(picture) { picture.asImageBitmap() }
            val aspect = picture.width / picture.height.toFloat()
            Box(contentAlignment = Alignment.BottomEnd) {
                Image(
                    image, contentDescription = null,
                    modifier = Modifier
                        .height(240.dp)
                        .width(240.dp * aspect)
                        .shadow(10.dp, RoundedCornerShape(1.dp))
                        .clip(RoundedCornerShape(1.dp)),
                )
                Icon(
                    Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp).offset(14.dp, 14.dp).background(Color.White, RoundedCornerShape(50)),
                )
            }
            Spacer(Modifier.height(32.dp))
        }
        Text(
            if (placed.sandbox) "Test Order Placed" else "Order Placed",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (placed.sandbox) {
                "Order ${placed.order}. A test order prints nothing; a real one would ship within a week."
            } else {
                "Order ${placed.order}. It ships within a week, and your receipt is on its way to your email."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A size as the painter measures, width by height: inches where they're used, else centimeters. */
private fun sizeText(width: Float, height: Float): String {
    val inches = LocaleData.getMeasurementSystem(ULocale.getDefault()) == LocaleData.MeasurementSystem.US
    fun length(value: Float): String {
        val measure = if (inches) value else value * 2.54f
        val rounded = Math.round(measure * 10) / 10f
        return if (rounded % 1f == 0f) rounded.toInt().toString() else "%.1f".format(rounded)
    }
    return "${length(width)} × ${length(height)} ${if (inches) "in" else "cm"}"
}
