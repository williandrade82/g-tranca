package com.gtranca.ui.online

import com.gtranca.ai.Difficulty
import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.Punicao
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TempoDeJogada
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Etapa B (§18.3): as regras da mesa do servidor viram a configuração da tela do jogo. */
class OnlineTableDriverTest {
    @Test
    fun `18_2 a mesa do servidor vira duplas, alvo 1500, aposta 50 e robos faceis na tela do jogo`() {
        val table = TableRules(
            TableMode.DUPLAS, "{}", targetScore = 1500, bet = 50, skillBoostersAllowed = true,
            tempoDeJogada = TempoDeJogada.QUINZE_MINUTOS, punicao = Punicao.PERDE_APOSTA, robotLevel = BotLevel.FACIL,
        )
        val config = OnlineTableDriver.configOf(table, RuleSet.CLASSICA)
        config.mode shouldBe GameMode.DUPLAS
        config.targetScore shouldBe 1500
        config.stake shouldBe 50
        config.difficulty shouldBe Difficulty.FACIL
        config.rules shouldBe RuleSet.CLASSICA
        config.skillBoosters shouldBe true
    }

    @Test
    fun `os niveis dos robos do servidor viram as dificuldades do app`() {
        val base = TableRules(TableMode.INDIVIDUAL, "{}")
        OnlineTableDriver.configOf(base.copy(robotLevel = BotLevel.MEDIO), RuleSet.CLASSICA).difficulty shouldBe Difficulty.MEDIO
        OnlineTableDriver.configOf(base.copy(robotLevel = BotLevel.DIFICIL), RuleSet.CLASSICA).difficulty shouldBe Difficulty.DIFICIL
        OnlineTableDriver.configOf(base, RuleSet.CLASSICA).mode shouldBe GameMode.INDIVIDUAL
    }
}
