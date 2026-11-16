package com.gtranca.game

import com.gtranca.engine.RuleSet

/**
 * §3.6 regras-base usadas por testes escritos antes da distribuição certeira: eles assumem a partida começando
 * direto em [com.gtranca.engine.model.Phase.AWAITING_DRAW] (sem a fase de palpite). `RuleSet.DEFAULT` liga
 * `dealGuessBonusEnabled` (base: sim, §14.2); os testes do minijogo em si usam o parâmetro ligado explicitamente.
 */
val NO_DEAL_GUESS: RuleSet = RuleSet.DEFAULT.copy(dealGuessBonusEnabled = false)
