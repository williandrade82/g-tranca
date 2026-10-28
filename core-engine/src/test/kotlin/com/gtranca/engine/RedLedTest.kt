package com.gtranca.engine

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §17.7 b2 Red-LED: cartas da mão que se encaixam nos jogos do adversário. */
class RedLedTest {
    private fun playing(block: RoundStateBuilder.() -> Unit) = round {
        phase = Phase.PLAYING
        block()
    }

    private fun view(state: RoundState, seat: Int = 0) = state.viewFor(Seat(seat))

    @Test
    fun `§17_7 sequencia do adversario - prolonga nas duas pontas, outro naipe ou salto nao`() {
        val s = playing {
            hand(0, "4H' 8H' 9H' 8S' 2C' KD' 5H'")
            meld(1, "5H 6H 7H")
        }
        val v = view(s)
        RedLed.fit(v, c("8H'")) shouldBe OpponentFit.FITS
        RedLed.fit(v, c("4H'")) shouldBe OpponentFit.FITS
        RedLed.fit(v, c("9H'")) shouldBe OpponentFit.NONE // §6.1 falta o 8
        RedLed.fit(v, c("8S'")) shouldBe OpponentFit.NONE // §6.1 outro naipe
        RedLed.fit(v, c("2C'")) shouldBe OpponentFit.FITS // §6.3 coringa acrescentado
        RedLed.fit(v, c("KD'")) shouldBe OpponentFit.NONE
    }

    @Test
    fun `§17_7 grupo do adversario - so o mesmo numero`() {
        val s = playing {
            hand(0, "9H' 9S' TH'")
            meld(1, "9C 9D 9H")
        }
        val v = view(s)
        RedLed.fit(v, c("9S'")) shouldBe OpponentFit.FITS
        RedLed.fit(v, c("TH'")) shouldBe OpponentFit.NONE
    }

    @Test
    fun `§17_7 conjunto que ja tem coringa nao aceita outro coringa (§6_3)`() {
        val s = playing {
            hand(0, "2C' 8H'")
            meld(1, "5H 6H 7H 2D")
        }
        val v = view(s)
        RedLed.fit(v, c("2C'")) shouldBe OpponentFit.NONE
        RedLed.fit(v, c("8H'")) shouldBe OpponentFit.FITS
    }

    @Test
    fun `§17_7 carta que fecha a canastra do adversario (6 para 7 cartas) e marcada para pulsar`() {
        val s = playing {
            hand(0, "TH' 3C' 4H'")
            meld(1, "4H 5H 6H 7H 8H 9H")
        }
        RedLed.fit(view(s), c("TH'")) shouldBe OpponentFit.CLOSES_CANASTA // §7.1
        RedLed.fit(view(s), c("3C'")) shouldBe OpponentFit.NONE // §6.2 nenhum 3
    }

    @Test
    fun `§17_7 canastra ja fechada so recebe o ponto, sem pulsar`() {
        val s = playing {
            hand(0, "JH'")
            meld(1, "4H 5H 6H 7H 8H 9H TH")
        }
        RedLed.fit(view(s), c("JH'")) shouldBe OpponentFit.FITS // §7.3
    }

    @Test
    fun `§17_7 conjuntos do proprio lado nao contam`() {
        val s = playing {
            hand(0, "8H'")
            meld(0, "5H 6H 7H")
        }
        RedLed.fit(view(s), c("8H'")) shouldBe OpponentFit.NONE
    }

    @Test
    fun `§17_7 so na etapa de baixar ou descartar da vez do jogador`() {
        val base: RoundStateBuilder.() -> Unit = {
            hand(0, "8H'")
            meld(1, "5H 6H 7H")
        }
        RedLed.fit(view(playing(base)), c("8H'")) shouldBe OpponentFit.FITS
        // §4.3 etapa 1: ainda não comprou
        RedLed.fit(view(round { base() }), c("8H'")) shouldBe OpponentFit.NONE
        // não é a vez
        RedLed.fit(view(playing { base(); current = 1 }), c("8H'")) shouldBe OpponentFit.NONE
    }

    @Test
    fun `§17_7 em duplas vale a mesa dos adversarios e a mesa da dupla nao`() {
        val s = round(GameMode.DUPLAS) {
            phase = Phase.PLAYING
            hand(0, "8H' 9S'")
            meld(0, "8S 8D 8C") // dupla
            meld(1, "5H 6H 7H") // adversários
        }
        RedLed.marks(view(s)) shouldBe mapOf(c("8H'") to OpponentFit.FITS)
    }

    @Test
    fun `§17_7 e so uma pergunta sobre a mesa - nao altera legalActions`() {
        val s = playing {
            hand(0, "8H' 9S'")
            meld(1, "5H 6H 7H")
        }
        val before = s.legalActions(0)
        RedLed.marks(view(s))
        s.legalActions(0) shouldBe before
    }
}
