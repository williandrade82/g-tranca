package com.gtranca.engine

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RedThreeLaid
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.ScoreLine
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.cards
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

/**
 * §3.5 / §6.5 / §9.4 / §4.3 o 3 vermelho só é trocado na vez do jogador: no início da vez (antes da compra) para os que
 * estão na mão (distribuição ou morto indireto); na hora, durante a vez, para os que entram pela compra, pelo morto
 * direto ou por reposição.
 */
class TurnStartRedThreesTest {

    /** Cenário: o assento 0 já começou a vez; o 1 ainda não (mão dele com 3 vermelho, como distribuída). */
    private fun RoundState.drawAndDiscard(seat: Int, discard: String? = null): RoundState {
        val drawn = act(seat, Action.DrawFromStock)
        val card = discard?.let { c(it) } ?: drawn.hand(seat).first { !it.isRedThree }
        return drawn.act(seat, Action.Discard(card))
    }

    // ---------- início da vez de quem passa a jogar ----------

    @Test
    fun `3 vermelho na mao so e trocado no inicio da vez do jogador antes da compra`() {
        // §3.5 + §4.3 a vez passa ao assento 1: 3H da mão baixado e reposto do topo do monte antes de comprar
        val before = round {
            turnsBegun = 1
            hand(0, "KS QD")
            hand(1, "3H 5S 6S")
            stock("9S 8S 7S 4S")
        }
        before.hand(1) shouldContain c("3H") // intacta durante a vez do assento 0
        val s = before.drawAndDiscard(0, "KS")
        s.currentSeat shouldBe Seat(1)
        s.phase shouldBe Phase.AWAITING_DRAW
        s.redThreesOf(Side(1)) shouldContainExactly cards("3H")
        s.hand(1) shouldContainExactly cards("5S 6S 8S") // 8S: topo depois da compra do assento 0 (9S)
        s.stock shouldContainExactly cards("7S 4S")
        s.redThreeLog shouldContainExactly listOf(laid(1, "3H", atTurnStart = true))
        s.turnsBegun shouldBe 2
        s.redThreeLogViolation() shouldBe null
    }

    @Test
    fun `reposicao em cadeia no inicio da vez`() {
        // §6.5 a reposição que também é 3 vermelho é baixada e reposta, tudo antes da compra
        val s = round {
            turnsBegun = 1
            hand(0, "KS QD")
            hand(1, "3H 3D 5S")
            stock("9S 3H' 3D' 8S 7S")
        }.drawAndDiscard(0, "KS")
        s.redThreesOf(Side(1)) shouldContainExactly cards("3H 3D 3H' 3D'")
        s.hand(1) shouldContainExactly cards("5S 8S 7S")
        s.redThreeLog.map { it.card } shouldContainExactly cards("3H 3D 3H' 3D'")
        s.redThreeLog.all { it.atTurnStart } shouldBe true
        s.redThreeLogViolation() shouldBe null
    }

    @Test
    fun `inicio da vez com monte vazio faz o morto virar monte para a reposicao`() {
        // §3.5 + §10.1 a reposição do 3 vermelho com o monte vazio: um morto disponível vira o novo monte
        val s = round {
            turnsBegun = 1
            hand(0, "KS QD")
            hand(1, "3H 5S 6S")
            stock("9S")
            morto(0, "AC' KC' QC' JC' TC' 9C' 8C' 7C' 6C' 5C' 4C'")
        }.drawAndDiscard(0, "KS")
        s.mortoStatus[0] shouldBe MortoStatus.BecameStock
        s.hand(1) shouldContainExactly cards("5S 6S AC'")
        s.redThreesOf(Side(1)) shouldContainExactly cards("3H")
        s.stock.size shouldBe 10
        s.phase shouldBe Phase.AWAITING_DRAW
    }

    @Test
    fun `inicio da vez sem monte nem morto baixa o 3 vermelho sem reposicao`() {
        // §6.5 + §10 sem monte nem morto o 3 vermelho é baixado sem reposição; o jogador ainda pode pegar o lixo
        val s = round {
            turnsBegun = 1
            hand(0, "7S KD")
            hand(1, "3H 5S 6S 9C QC")
            stock("KH")
            mortoTaken(0, 0)
            mortoTaken(1, 1)
        }.drawAndDiscard(0, "7S")
        s.redThreesOf(Side(1)) shouldContainExactly cards("3H")
        s.hand(1) shouldContainExactly cards("5S 6S 9C QC")
        s.result shouldBe null
        s.phase shouldBe Phase.AWAITING_DRAW
        s.legalActions(1).takeNewPlans() shouldContain cards("5S 6S").toSet()
    }

    @Test
    fun `fim sem vencedor so e conferido depois da troca do inicio da vez`() {
        // §10.2 / §11.2 sem monte, sem morto e sem poder pegar o lixo: fim sem vencedor, com o 3 vermelho já baixado
        val s = round {
            turnsBegun = 1
            hand(0, "KD QD")
            hand(1, "3H 5S 9C")
            stock("KH")
            mortoTaken(0, 0)
            mortoTaken(1, 1)
        }.drawAndDiscard(0, "KD")
        s.result shouldBe RoundResult.NoWinner
        s.redThreesOf(Side(1)) shouldContainExactly cards("3H")
        s.hand(1) shouldContainExactly cards("5S 9C")
    }

    // ---------- durante a vez: troca na hora ----------

    @Test
    fun `3 vermelho comprado continua trocando na hora e fica fora do inicio da vez no registro`() {
        // §6.5 compra do monte: troca imediata (atTurnStart falso), depois da troca do início da vez (verdadeiro)
        val s = round {
            turnsBegun = 1
            hand(0, "KS QD")
            hand(1, "3H 5S 6S")
            stock("9S 8S 3D 7S 4S")
        }.drawAndDiscard(0, "KS").act(1, Action.DrawFromStock)
        s.redThreeLog shouldContainExactly listOf(
            laid(1, "3H", atTurnStart = true), // início da vez (repôs com 8S)
            laid(1, "3D", atTurnStart = false), // comprado (repôs com 7S)
        )
        s.hand(1) shouldContainExactly cards("5S 6S 8S 7S")
        s.hand(1).none { it.isRedThree } shouldBe true
        s.redThreeLogViolation() shouldBe null
    }

    @Test
    fun `morto direto troca os 3 vermelhos na hora`() {
        // §9.2 + §9.4 morto direto: 3 vermelho do morto baixado e reposto imediatamente (atTurnStart falso)
        val s = round {
            hand(0, "5H 6H 7H")
            hand(1, "9C 9D")
            morto(0, "3H 4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC'")
            stock("AS KS")
            phase = Phase.PLAYING
        }.act(0, create("5H 6H 7H"))
        s.hand(0).none { it.isRedThree } shouldBe true
        s.redThreeLog shouldContainExactly listOf(laid(0, "3H", atTurnStart = false))
        s.unsettledMortoSeats.shouldBeEmpty()
    }

    // ---------- morto indireto ----------

    @Test
    fun `morto indireto deixa os 3 vermelhos na mao e troca so no inicio da proxima vez do dono`() {
        // §9.3 + §9.4 descartou a última carta e pegou o morto: o 3 vermelho dele só sai no início da próxima vez
        val afterDiscard = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            morto(0, "3H 3D 4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC'")
            stock("AS QS JS TS")
            phase = Phase.PLAYING
        }.act(0, discardCard("KS"))
        afterDiscard.currentSeat shouldBe Seat(1)
        afterDiscard.hand(0) shouldContain c("3H")
        afterDiscard.hand(0) shouldContain c("3D")
        afterDiscard.redThrees.flatten().shouldBeEmpty()
        afterDiscard.redThreeLog.shouldBeEmpty()
        afterDiscard.unsettledMortoSeats shouldContainExactly listOf(Seat(0))
        afterDiscard.mortoStatus[0] shouldBe MortoStatus.Taken(Side(0))

        val back = afterDiscard.drawAndDiscard(1)
        back.currentSeat shouldBe Seat(0)
        back.hand(0).none { it.isRedThree } shouldBe true
        back.hand(0).size shouldBe 11 // 11 do morto, 2 baixados, 2 repostos
        back.redThrees[0] shouldContainExactly cards("3H 3D")
        back.redThreeLog shouldContainExactly listOf(laid(0, "3H", atTurnStart = true), laid(0, "3D", atTurnStart = true))
        back.unsettledMortoSeats.shouldBeEmpty()
        back.phase shouldBe Phase.AWAITING_DRAW
    }

    @Test
    fun `morto indireto pendente aparece na vista de todos sem revelar o conteudo`() {
        // §9.4 a marca é pública e independe de o morto ter ou não 3 vermelho
        val withoutRed = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            stock("AS QS")
            phase = Phase.PLAYING
        }.act(0, discardCard("KS"))
        withoutRed.unsettledMortoSeats shouldContainExactly listOf(Seat(0))
        withoutRed.viewFor(Seat(1)).unsettledMortoSeats shouldContainExactly listOf(Seat(0))
    }

    // ---------- pontuação (§12.2) ----------

    @Test
    fun `3 vermelho que sobra na mao de quem ainda nao jogou vale menos 5 ao fim da partida`() {
        // §12.2 + §3.5 a partida termina antes de o assento 1 ter a sua vez: o 3 vermelho dele continua na mão (−5)
        val s = round {
            turnsBegun = 1
            hand(0, "KS KD KC")
            hand(1, "3H 9C")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H 9H TH")
            phase = Phase.PLAYING
        }.act(0, create("KS KD KC"))
        s.result shouldBe RoundResult.GoOut(Side(0), Seat(0))
        val line = scoreRound(s)[1]
        line.hand.redThrees shouldBe ScoreLine(1, -5)
        line.hand.points shouldBe -5 - 8
        line.redThrees shouldBe ScoreLine(0, 0) // na mão não conta como baixado (§12.1)
    }

    @Test
    fun `3 vermelho do morto indireto ainda pendente vale menos 5 se o outro lado bate`() {
        // §12.2 + §9.4 o lado 0 pegou o morto indireto (com 3D) e o lado 1 bate antes da próxima vez do assento 0
        val afterDiscard = round {
            hand(0, "KS")
            hand(1, "5H' 6H'")
            morto(0, "3D 4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC'")
            mortoTaken(1, 1)
            meld(1, "4H 5H 6H 7H 8H 9H TH")
            stock("7H' QS")
            phase = Phase.PLAYING
        }.act(0, discardCard("KS"))
        afterDiscard.hand(0) shouldContain c("3D")
        val s = afterDiscard.act(1, Action.DrawFromStock).act(1, create("5H' 6H' 7H'"))
        s.result shouldBe RoundResult.GoOut(Side(1), Seat(1))
        s.hand(0) shouldContain c("3D") // intocada: a vez dele não chegou
        scoreRound(s)[0].hand.redThrees shouldBe ScoreLine(1, -5)
        scoreRound(s)[0].redThrees shouldBe ScoreLine(0, 0)
    }

    // ---------- JSON antigo ----------

    @Test
    fun `registro antigo sem atTurnStart e lido com valor padrao falso`() {
        // compatibilidade: JSON salvo antes de atTurnStart existir
        val laid = RedThreeLaid(Seat(1), c("3H"), atDeal = true)
        val json = Json.encodeToString(RedThreeLaid.serializer(), laid)
        val old = JsonObject(Json.parseToJsonElement(json).jsonObject - "atTurnStart").toString()
        Json.decodeFromString(RedThreeLaid.serializer(), old) shouldBe laid
        Json.decodeFromString(RedThreeLaid.serializer(), """{"seat":1,"card":"3H","atDeal":true}""") shouldBe laid
        val turnStart = RedThreeLaid(Seat(1), c("3H"), atTurnStart = true)
        Json.decodeFromString(
            RedThreeLaid.serializer(),
            Json.encodeToString(RedThreeLaid.serializer(), turnStart),
        ) shouldBe turnStart
    }

    @Test
    fun `estado salvo sem turnsBegun e unsettledMortoSeats trata todos como ja comecados`() {
        // compatibilidade: partida salva antes dos campos novos (nela a troca era feita na distribuição)
        val state = dealRound(GameMode.DUPLAS, kotlin.random.Random(3))
        val json = Json.parseToJsonElement(Json.encodeToString(RoundState.serializer(), state)).jsonObject
        val old = JsonObject(json - "turnsBegun" - "unsettledMortoSeats").toString()
        val decoded = Json.decodeFromString(RoundState.serializer(), old)
        decoded.turnsBegun shouldBe GameMode.DUPLAS.seatCount
        decoded.unsettledMortoSeats.shouldBeEmpty()
        GameMode.DUPLAS.seats.forEach { decoded.hasBegunFirstTurn(it) shouldBe true }
        decoded.viewFor(Seat(0)).turnsBegun shouldBe GameMode.DUPLAS.seatCount
    }

    @Test
    fun `os assentos que ja comecaram sao os primeiros da ordem de jogada a partir do primeiro jogador`() {
        // §3.5 turnsBegun público: com o assento 2 iniciando em duplas, a ordem é 2, 3, 0, 1
        val s = round(GameMode.DUPLAS) {
            firstSeat = 2
            turnsBegun = 2
        }
        s.hasBegunFirstTurn(Seat(2)) shouldBe true
        s.hasBegunFirstTurn(Seat(3)) shouldBe true
        s.hasBegunFirstTurn(Seat(0)) shouldBe false
        s.hasBegunFirstTurn(Seat(1)) shouldBe false
        s.viewFor(Seat(0)).hasBegunFirstTurn(Seat(3)) shouldBe true
        s.viewFor(Seat(0)).hasBegunFirstTurn(Seat(1)) shouldBe false
    }

    @Test
    fun `a mao propria mostra os 3 vermelhos que o jogador ainda tem`() {
        // §3.5 só o dono vê a própria mão com 3 vermelho; a vista dos outros só tem o tamanho
        val s = round {
            turnsBegun = 1
            hand(0, "KS QD")
            hand(1, "3H 5S 6S")
        }
        s.viewFor(Seat(1)).hand shouldContain c("3H")
        s.viewFor(Seat(0)).hand shouldNotContain c("3H")
        s.viewFor(Seat(0)).handSizes shouldContainExactly listOf(2, 3)
    }
}
