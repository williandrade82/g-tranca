package com.gtranca.ui.shop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.gtranca.ui.theme.loungeDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.aspectRatio
import com.gtranca.ui.theme.GDialog
import com.gtranca.ui.theme.GTitle
import com.gtranca.ui.theme.Spacing
import com.gtranca.ui.theme.TabularNums
import kotlin.math.roundToInt

/** §17.10 Loja (docs/telas.md 3.20): as nove ofertas no painel de madeira e o modal *Comprar booster*. */
@Composable
fun ShopScreen(viewModel: ShopViewModel, onBack: () -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    ShopContent(ui, "shop-screen", "shop", "shop-back", viewModel::onOffer, viewModel::onBuy, viewModel::onCancel, onBack)
}

/**
 * O corpo da Loja (3.20) e da Loja online (3.20a, §18.5, [com.gtranca.ui.online.OnlineShopScreen]): o mesmo painel e
 * o mesmo modal *Comprar booster* — só a origem do saldo/inventário muda (local ou online) por quem chama.
 * [screenTag]/[offerTagPrefix]/[backTag] distinguem as duas telas nos testes; o modal usa sempre as mesmas tags
 * (`shop-dialog`, `shop-buy`, `shop-cancel`, `shop-insufficient`, docs/telas.md 4, porque é literalmente o mesmo
 * modal). [extra] entra entre o título e a dica (a Loja online usa para o saldo online e o aviso de rede).
 */
@Composable
internal fun ShopContent(
    ui: ShopUi,
    screenTag: String,
    offerTagPrefix: String,
    backTag: String,
    onOffer: (ShopItem) -> Unit,
    onBuy: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    GBackground(Modifier.fillMaxSize().testTag(screenTag)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GTitle(stringResource(R.string.shop_title), Modifier.semantics { heading() })
            extra()
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
            ShopPanel(Modifier.fillMaxWidth().widthIn(max = 520.dp).weight(1f)) {
                ui.items.forEachIndexed { index, item ->
                    if (index > 0 && ui.items[index - 1].def.id != item.def.id) androidx.compose.foundation.layout.Spacer(Modifier.size(Spacing.sm))
                    OfferRow(item, index + 1, offerTagPrefix) { onOffer(item) }
                }
            }
            GButton(onBack, Modifier.heightIn(min = 48.dp).testTag(backTag), kind = GButtonKind.Text) { Text(stringResource(R.string.back)) }
        }
    }
    ui.selected?.let { PurchaseDialog(ui, it, onBuy, onCancel) }
}

@Composable
private fun unitsText(quantity: Int): String = LocalContext.current.resources.getQuantityString(R.plurals.shop_units, quantity, quantity)

/**
 * O painel da loja no estilo do salão (docs/telas-app.md 3.20): azul-noite com moldura dourada, o brasão no alto (fixo) e
 * o miolo com as ofertas rolando.
 */
@Composable
private fun ShopPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.loungeDialog(shape).clip(shape), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painterResource(R.drawable.home_crest), null,
            Modifier.padding(top = Spacing.sm).height(56.dp).aspectRatio(640f / 298f).testTag("shop-top"),
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.sm).testTag("shop-scroll"),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) { content() }
        androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.sm).testTag("shop-bottom"))
    }
}
/** Uma oferta: a imagem do booster, o nome com a quantidade do pacote e o valor em moedas. */
@Composable
private fun OfferRow(item: ShopItem, number: Int, tagPrefix: String = "shop", onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val units = unitsText(item.offer.quantity)
    val description = stringResource(R.string.shop_offer_desc, item.def.name, units, item.offer.price)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(Brush.verticalGradient(listOf(GColors.IndigoLight.copy(alpha = 0.8f), GColors.Midnight.copy(alpha = 0.85f))), shape)
            .border(1.5.dp, Brush.verticalGradient(listOf(GColors.Champagne, GColors.GoldDeep)), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("$tagPrefix-offer-$number"),
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
