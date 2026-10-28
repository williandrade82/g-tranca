package com.gtranca.ui.shop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtranca.R
import com.gtranca.ui.boosters.boosterKindRes
import com.gtranca.ui.inventory.boosterIconRes
import com.gtranca.ui.theme.FitText
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.GDialog
import com.gtranca.ui.theme.GTitle
import com.gtranca.ui.theme.Spacing
import com.gtranca.ui.theme.TabularNums
import kotlin.math.roundToInt

/**
 * Medidas do painel da loja nas peças de `docs/boosters/Loja` (em pixels da imagem do topo, 653 de largura): o corpo começa
 * quase no fim do topo, as laterais repetem de 103 em 103, o centro de 69 em 69 e o rodapé entra com as bordas alinhadas.
 */
private object Panel {
    const val WIDTH = 653f
    const val TOP_H = 228
    const val BODY_Y = 227f
    const val LEFT_X = 40f
    const val RIGHT_X = 562f
    const val CENTER_X = 116f
    const val CENTER_END = 570f
    const val FOOT_X = 37f
    const val FOOT_VISIBLE = 113

    /** Onde cabe o conteúdo (entre as molduras) e o respiro abaixo do toldo. */
    const val CONTENT_START = 128f
    const val CONTENT_END = 560f
    const val CONTENT_TOP = 240f
    const val SIDE_STEP = 103
    const val CENTER_STEP = 69
}

/** §17.10 Loja (docs/telas.md 3.20): as nove ofertas no painel de madeira e o modal *Comprar booster*. */
@Composable
fun ShopScreen(viewModel: ShopViewModel, onBack: () -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    GBackground(Modifier.fillMaxSize().testTag("shop-screen")) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GTitle(stringResource(R.string.shop_title), Modifier.semantics { heading() })
            Text(
                stringResource(R.string.shop_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = GColors.OnTable,
                textAlign = TextAlign.Center,
            )
            ui.bought?.let { item ->
                Text(
                    stringResource(R.string.shop_bought, item.def.name, unitsText(item.offer.quantity)),
                    style = MaterialTheme.typography.titleSmall,
                    color = GColors.Gold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("shop-bought"),
                )
            }
            ShopPanel(Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
                ui.items.forEachIndexed { index, item ->
                    if (index > 0 && ui.items[index - 1].def.id != item.def.id) androidx.compose.foundation.layout.Spacer(Modifier.size(Spacing.sm))
                    OfferRow(item, index + 1) { viewModel.onOffer(item) }
                }
            }
            GButton(onBack, Modifier.heightIn(min = 48.dp).testTag("shop-back"), kind = GButtonKind.Text) { Text(stringResource(R.string.back)) }
        }
    }
    ui.selected?.let { PurchaseDialog(ui, it, viewModel::onBuy, viewModel::onCancel) }
}

@Composable
private fun unitsText(quantity: Int): String = LocalContext.current.resources.getQuantityString(R.plurals.shop_units, quantity, quantity)

/**
 * O painel: topo com o robô e o toldo, laterais e centro de madeira repetidos até a altura do conteúdo e o rodapé. Tudo é
 * desenhado em pixels inteiros (cada trecho repetido termina onde o seguinte começa, com 1 px a mais de sobreposição) para
 * não aparecerem linhas de costura.
 */
@Composable
private fun ShopPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val top = ImageBitmap.imageResource(R.drawable.shop_top)
    val bottom = ImageBitmap.imageResource(R.drawable.shop_bottom)
    val center = ImageBitmap.imageResource(R.drawable.shop_center)
    val left = ImageBitmap.imageResource(R.drawable.shop_left)
    val right = ImageBitmap.imageResource(R.drawable.shop_right)
    // A escala vem da largura do painel, já conhecida na hora de pôr as margens do conteúdo.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val scale = constraints.maxWidth / Panel.WIDTH
        val density = LocalDensity.current
        fun dp(px: Float) = with(density) { (px * scale).toDp() }
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind { drawPanel(scale, top, bottom, center, left, right) }
                .padding(
                    start = dp(Panel.CONTENT_START),
                    end = dp(Panel.WIDTH - Panel.CONTENT_END),
                    top = dp(Panel.CONTENT_TOP),
                    bottom = dp(Panel.FOOT_VISIBLE.toFloat()) + Spacing.sm,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) { content() }
    }
}

private fun DrawScope.drawPanel(scale: Float, top: ImageBitmap, bottom: ImageBitmap, center: ImageBitmap, left: ImageBitmap, right: ImageBitmap) {
    fun px(v: Float) = (v * scale).roundToInt()
    val bodyTop = px(Panel.BODY_Y)
    val footTop = size.height.roundToInt() - px(Panel.FOOT_VISIBLE.toFloat())

    /** Repete [image] na vertical, em [x]..[x]+[width], de [step] em [step] pixels da imagem, até o rodapé. */
    fun tile(image: ImageBitmap, x: Float, width: Float, step: Int) {
        var i = 0
        while (true) {
            val y0 = bodyTop + px(i * step.toFloat())
            if (y0 >= footTop) break
            val y1 = minOf(bodyTop + px((i + 1) * step.toFloat()), footTop)
            val srcH = if (y1 - y0 >= px(step.toFloat())) step else maxOf(1, ((y1 - y0) / scale).roundToInt()).coerceAtMost(step)
            drawImage(
                image,
                IntOffset.Zero, IntSize(image.width, srcH),
                IntOffset(px(x), y0), IntSize(px(width), (y1 - y0) + if (y1 < footTop) 1 else 0),
                filterQuality = FilterQuality.Medium,
            )
            i++
        }
    }
    tile(center, Panel.CENTER_X, Panel.CENTER_END - Panel.CENTER_X, Panel.CENTER_STEP)
    tile(left, Panel.LEFT_X, left.width.toFloat(), Panel.SIDE_STEP)
    tile(right, Panel.RIGHT_X, right.width.toFloat(), Panel.SIDE_STEP)
    drawImage(top, IntOffset.Zero, IntSize(top.width, top.height), IntOffset.Zero, IntSize(size.width.roundToInt(), px(Panel.TOP_H.toFloat())), filterQuality = FilterQuality.Medium)
    drawImage(
        bottom,
        IntOffset.Zero, IntSize(bottom.width, Panel.FOOT_VISIBLE),
        IntOffset(px(Panel.FOOT_X), footTop), IntSize(px(bottom.width.toFloat()), size.height.roundToInt() - footTop),
        filterQuality = FilterQuality.Medium,
    )
}

/** Uma oferta: a imagem do booster, o nome com a quantidade do pacote e o valor em moedas. */
@Composable
private fun OfferRow(item: ShopItem, number: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val units = unitsText(item.offer.quantity)
    val description = stringResource(R.string.shop_offer_desc, item.def.name, units, item.offer.price)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(GColors.Midnight.copy(alpha = 0.72f), shape)
            .border(1.dp, GColors.Gold.copy(alpha = 0.55f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("shop-offer-$number"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        boosterIconRes(item.def.id)?.let { Image(painterResource(it), null, Modifier.size(44.dp), contentScale = ContentScale.Fit) }
        Column(Modifier.weight(1f)) {
            FitText(item.def.name, TextStyle(color = GColors.Gold, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp), minFontSp = 10)
            Text(units, style = MaterialTheme.typography.labelMedium, color = GColors.Ivory)
        }
        Price(item.offer.price)
    }
}

@Composable
private fun Price(price: Int, color: Color = GColors.Champagne) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.coin_bag_small), null, Modifier.size(20.dp))
        Text(price.toString(), color = color, fontWeight = FontWeight.Bold, style = TextStyle(fontFeatureSettings = TabularNums))
    }
}

/** Modal *Comprar booster* (docs/telas.md 4): o que é, a quantidade, o valor total e o saldo de agora e de depois. */
@Composable
private fun PurchaseDialog(ui: ShopUi, item: ShopItem, onBuy: () -> Unit, onCancel: () -> Unit) {
    val balance = ui.balance
    val after = balance?.let { item.balanceAfter(it) }
    GDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("shop-dialog"),
        title = { Text(stringResource(R.string.shop_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    boosterIconRes(item.def.id)?.let { Image(painterResource(it), null, Modifier.size(56.dp), contentScale = ContentScale.Fit) }
                    Column(Modifier.weight(1f)) {
                        Text(item.def.name, style = MaterialTheme.typography.titleMedium, color = GColors.Gold, fontWeight = FontWeight.ExtraBold)
                        Text(stringResource(boosterKindRes(item.def.kind)), style = MaterialTheme.typography.labelLarge, color = GColors.Champagne)
                    }
                }
                Text(item.def.summary, style = MaterialTheme.typography.bodyMedium)
                DialogLine(stringResource(R.string.shop_dialog_quantity)) { Text(item.offer.quantity.toString(), Modifier.testTag("shop-quantity")) }
                DialogLine(stringResource(R.string.shop_dialog_total)) { Price(item.offer.price, color = GColors.Ivory) }
                DialogLine(stringResource(R.string.shop_dialog_have)) { Text(balance?.toString().orEmpty(), Modifier.testTag("shop-balance")) }
                DialogLine(stringResource(R.string.shop_dialog_after)) {
                    if (after != null && after >= 0) {
                        Text(after.toString(), Modifier.testTag("shop-after"))
                    } else if (after != null) {
                        Text(stringResource(R.string.shop_insufficient), color = GColors.Ruby, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("shop-insufficient"))
                    }
                }
                when (ui.notice) {
                    ShopNotice.FAILED -> Text(stringResource(R.string.shop_failed), color = GColors.Ruby, modifier = Modifier.testTag("shop-failed"))
                    ShopNotice.INSUFFICIENT, null -> Unit
                }
            }
        },
        dismissButton = {
            TextButton(onCancel, Modifier.heightIn(min = 48.dp).testTag("shop-cancel")) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            TextButton(onBuy, Modifier.heightIn(min = 48.dp).testTag("shop-buy"), enabled = ui.canBuy) {
                Text(stringResource(R.string.shop_buy), fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun DialogLine(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = GColors.Steel)
        content()
    }
}
