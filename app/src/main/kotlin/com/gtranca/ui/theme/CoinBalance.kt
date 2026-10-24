package com.gtranca.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gtranca.R

/** Saldo de moedas do jogador para o indicador do topo (docs/telas.md 0); `null` enquanto lê. */
val LocalCoinBalance = compositionLocalOf<Int?> { null }

/** Indicador pequeno do saldo: saco de moedas e o número (tag `coin-balance`), atualizado ao vivo. */
@Composable
fun CoinBalanceChip(balance: Int, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.coins_balance, balance)
    Row(
        modifier
            .background(GColors.Midnight.copy(alpha = 0.7f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag("coin-balance"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.coin_bag_small), null, Modifier.size(20.dp))
        Text(balance.toString(), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TabularNums), fontWeight = FontWeight.Bold, color = GColors.Gold)
    }
}

/** Faixa do topo com o saldo no centro (telas fora da mesa de jogo). */
@Composable
fun CoinBalanceBar(modifier: Modifier = Modifier) {
    val balance = LocalCoinBalance.current ?: return
    Box(modifier.fillMaxWidth().background(GColors.Midnight).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
        CoinBalanceChip(balance)
    }
}
