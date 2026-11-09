package com.gtranca.ui.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gtranca.R
import com.gtranca.ui.shop.ShopContent
import com.gtranca.ui.theme.GBackground
import com.gtranca.ui.theme.GButton
import com.gtranca.ui.theme.GButtonKind
import com.gtranca.ui.theme.GColors
import com.gtranca.ui.theme.Spacing

/**
 * §17.10/§18.5 Loja online (docs/telas.md 3.20a): o mesmo painel/modal da Loja (3.20, [ShopContent]), com a carteira
 * e o inventário online. Falha de rede ou servidor fora avisa na própria tela, com *Tentar de novo* — nunca fecha a
 * loja (não volta a 3.21 sozinha).
 */
@Composable
fun OnlineShopScreen(viewModel: OnlineShopViewModel, onBack: () -> Unit) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    when (ui.status) {
        OnlineShopStatus.LOADING -> GBackground(Modifier.fillMaxSize().testTag("online-shop-screen")) {
            Box(Modifier.fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = GColors.Gold, modifier = Modifier.testTag("online-shop-loading"))
            }
        }
        OnlineShopStatus.ERROR -> GBackground(Modifier.fillMaxSize().testTag("online-shop-screen")) {
            Column(
                Modifier.fillMaxSize().padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProblemText(ui.problem ?: OnlineProblem.UNEXPECTED, "online-shop-error")
                GButton(viewModel::retry, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-shop-retry")) {
                    Text(stringResource(R.string.online_retry))
                }
                GButton(onBack, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("online-shop-back"), kind = GButtonKind.Secondary) {
                    Text(stringResource(R.string.back))
                }
            }
        }
        OnlineShopStatus.READY -> ShopContent(
            ui.shop, "online-shop-screen", "online-shop", "online-shop-back",
            viewModel::onOffer, viewModel::onBuy, viewModel::onCancel, onBack,
        ) {
            ui.shop.balance?.let {
                Text(
                    stringResource(R.string.coins_balance, it),
                    Modifier.testTag("online-shop-balance"),
                    style = MaterialTheme.typography.titleMedium,
                    color = GColors.Gold,
                )
            }
            if (ui.staleOffers) {
                Text(
                    stringResource(R.string.online_shop_stale_offers),
                    Modifier.testTag("online-shop-stale"),
                    style = MaterialTheme.typography.bodySmall,
                    color = GColors.Champagne,
                    textAlign = TextAlign.Center,
                )
            }
            ui.problem?.let { problem ->
                ProblemText(problem, "online-shop-error")
                GButton(viewModel::retry, Modifier.heightIn(min = 48.dp).testTag("online-shop-retry"), kind = GButtonKind.Secondary) {
                    Text(stringResource(R.string.online_retry))
                }
            }
        }
    }
}
