package com.gtranca.engine

import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RedThreeLaid
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.cards
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * §3.5 / §6.5 as trocas de 3 vermelho são públicas: todos sabem, na ordem em que aconteceram, quem baixou cada
 * 3 vermelho (no início da vez ou durante a jogada). A carta de reposição continua oculta.
 */
class RedThreeLogTest {

    // ---------- durante a jogada (§6.5) ----------

    @Test
    fun `3 vermelho comprado entra no registro com o assento de quem comprou`() {
        // §6.5 a troca é pública e feita na hora (durante a jogada: atTurnStart falso)
        val s = round {
            hand(0, "KS QD")
            stock("3H KH 4S")
        }.act(0, Action.DrawFromStock)
        s.redThreeLog shouldContainExactly listOf(laid(0, "3H"))
        s.redThreeLogViolation() shouldBe null
    }

    @Test
    fun `reposicao em cadeia registra cada 3 vermelho na ordem`() {
        // §6.5 reposição em cadeia: um item por 3 vermelho, na ordem em que foram baixados
        val s = round {
            hand(0, "KS QD")
            stock("3H 3D 3H' KH 4S")
        }.act(0, Action.DrawFromStock)
        s.redThreeLog shouldContainExactly listOf(laid(0, "3H"), laid(0, "3D"), laid(0, "3H'"))
    }

    @Test
    fun `registro acumula apos as trocas anteriores`() {
        // §3.5 + §6.5 o registro é cronológico: a troca da jogada vem depois das trocas anteriores
        val s = round(GameMode.DUPLAS) {
            hand(3, "KS QD")
            stock("3H KH")
            redThrees(0, "3D")
            redThreeLog = listOf(laid(2, "3D", atTurnStart = true))
            current = 3
        }.act(3, Action.DrawFromStock)
        s.redThreeLog shouldContainExactly listOf(laid(2, "3D", atTurnStart = true), laid(3, "3H"))
        s.redThreeLogViolation() shouldBe null
    }

    @Test
    fun `3 vermelho sem reposicao tambem e registrado`() {
        // §6.5 / §10.2 baixado sem reposição continua sendo uma troca pública
        val s = round {
            hand(0, "KS QD")
            stock("3H")
            mortoTaken(0, 0)
            mortoTaken(1, 1)
        }.act(0, Action.DrawFromStock)
        s.redThreeLog shouldContainExactly listOf(laid(0, "3H"))
    }

    @Test
    fun `reposicao pelo morto que vira monte registra o 3 vermelho reposto`() {
        // §6.5 + §10.1 monte vazio na reposição: o morto vira monte; se a reposição for 3 vermelho, entra no registro
        val s = round {
            hand(0, "KS QD")
            stock("3H")
            morto(0, "3D 4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC'")
        }.act(0, Action.DrawFromStock)
        s.mortoStatus[0] shouldBe MortoStatus.BecameStock
        s.redThreeLog shouldContainExactly listOf(laid(0, "3H"), laid(0, "3D"))
        s.redThreeLogViolation() shouldBe null
    }

    @Test
    fun `3 vermelho do morto direto e registrado`() {
        // §9.2 + §9.4 3 vermelho que vem no morto é baixado e reposto: troca pública
        val s = round {
            hand(0, "5H 6H 7H")
            hand(1, "9C 9D")
            morto(0, "3H 4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC'")
            stock("AS KS")
            phase = Phase.PLAYING
        }.act(0, create("5H 6H 7H"))
        s.redThreeLog shouldContainExactly listOf(laid(0, "3H"))
    }

    @Test
    fun `3 vermelho do morto indireto so e registrado no inicio da proxima vez de quem o pegou`() {
        // §9.3 + §9.4 o morto vai para a mão no descarte, com o 3 vermelho; a troca (pública) só vem no início da próxima vez
        val s = round {
            hand(0, "KS")
            hand(1, "9C 9D")
            morto(0, "3H 4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC'")
            stock("AS QS JS")
            phase = Phase.PLAYING
        }.act(0, discardCard("KS"))
        s.currentSeat shouldBe Seat(1)
        s.redThreeLog.shouldBeEmpty()
        s.hand(0) shouldContain c("3H")
        // o adversário joga e a vez volta ao assento 0: só então o 3H é baixado, no início da vez
        val back = s.drawAndDiscardFor(1)
        back.currentSeat shouldBe Seat(0)
        back.redThreeLog shouldContainExactly listOf(laid(0, "3H", atTurnStart = true))
    }

    @Test
    fun `em duplas o morto pego pelo parceiro registra o parceiro`() {
        // §9.4 + §3.5 o registro diz QUEM baixou: o assento 2, não o 0, mesmo sendo o mesmo lado
        val s = round(GameMode.DUPLAS) {
            hand(2, "5H 6H 7H")
            morto(0, "3D 4C' 5C' 6C' 7C' 8C' 9C' TC' JC' QC' KC'")
            stock("AS KS")
            current = 2
            phase = Phase.PLAYING
        }.act(2, create("5H 6H 7H"))
        s.redThreeLog shouldContainExactly listOf(laid(2, "3D"))
        s.redThreesOf(Side(0)) shouldContainExactly cards("3D")
    }

    @Test
    fun `acao sem 3 vermelho nao altera o registro`() {
        val before = round {
            hand(0, "KS QD")
            stock("KH 4S")
            redThrees(1, "3H")
            redThreeLog = listOf(laid(1, "3H", atTurnStart = true))
        }
        before.act(0, Action.DrawFromStock).redThreeLog shouldBe before.redThreeLog
    }

    private fun RoundState.drawAndDiscardFor(seat: Int): RoundState {
        val drawn = act(seat, Action.DrawFromStock)
        return drawn.act(seat, Action.Discard(drawn.hand(seat).first { !it.isRedThree }))
    }

    // ---------- vista (§3.5 público, reposição oculta) ----------

    @Test
    fun `todos os assentos veem o mesmo registro do estado sem a carta de reposicao`() {
        // §3.5 / §6.5 público: todos são avisados; a reposição (KH) só a vê quem comprou
        val s = round(GameMode.DUPLAS) {
            hand(0, "KS QD")
            hand(1, "9C 9D")
            hand(2, "5H 6H")
            hand(3, "7D 8D")
            stock("3H KH 4S")
        }.act(0, Action.DrawFromStock)
        s.mode.seats.forEach { seat ->
            val view = s.viewFor(seat)
            view.redThreeLog shouldBe s.redThreeLog
            val json = Json.encodeToString(PlayerView.serializer(), view)
            json shouldContain "\"3H\""
            if (seat != Seat(0)) json shouldNotContain "\"KH\""
        }
    }

    @Test
    fun `registro da vista e copia defensiva`() {
        val state = round {
            redThrees(0, "3H")
            redThreeLog = listOf(laid(0, "3H", atTurnStart = true))
        }
        val view = state.viewFor(Seat(1))
        runCatching { (view.redThreeLog as MutableList<RedThreeLaid>).clear() }
        state.redThreeLog shouldContainExactly listOf(laid(0, "3H", atTurnStart = true))
    }

    // ---------- serialização ----------

    private fun withoutLog(json: String): String =
        JsonObject(Json.parseToJsonElement(json).jsonObject - "redThreeLog").toString()

    @Test
    fun `estado com registro faz ida e volta em JSON`() {
        val state = (0L until 200L).map { dealRound(GameMode.DUPLAS, Random(it)) }.first { it.redThreeLog.size >= 2 }
        val json = Json.encodeToString(RoundState.serializer(), state)
        json shouldContain "redThreeLog"
        Json.decodeFromString(RoundState.serializer(), json) shouldBe state
    }

    @Test
    fun `JSON antigo sem o registro e lido com registro vazio`() {
        // compatibilidade: partidas salvas antes do registro existir
        val state = (0L until 200L).map { dealRound(GameMode.INDIVIDUAL, Random(it)) }.first { it.redThreeLog.isNotEmpty() }
        val old = withoutLog(Json.encodeToString(RoundState.serializer(), state))
        old shouldNotContain "redThreeLog"
        val decoded = Json.decodeFromString(RoundState.serializer(), old)
        decoded.redThreeLog.shouldBeEmpty()
        decoded shouldBe state.copy(redThreeLog = emptyList())

        val view = state.viewFor(Seat(0))
        val oldView = withoutLog(Json.encodeToString(PlayerView.serializer(), view))
        Json.decodeFromString(PlayerView.serializer(), oldView) shouldBe view.copy(redThreeLog = emptyList())
    }

    // ---------- determinização ----------

    @Test
    fun `determinizacao preserva o registro publico`() {
        // §3.5 o registro é público: o mundo sorteado tem o mesmo registro da vista
        for (mode in GameMode.entries) {
            val state = (0L until 200L).map { dealRound(mode, Random(it)) }.first { it.redThreeLog.isNotEmpty() }
            mode.seats.forEach { seat ->
                val view = state.viewFor(seat)
                val sampled = view.determinize(Random(seat.index.toLong()))
                sampled.redThreeLog shouldBe view.redThreeLog
                sampled.viewFor(seat) shouldBe view
            }
        }
    }

    @Test
    fun `vista sem registro de partida salva antiga continua determinizavel`() {
        // compatibilidade: registro vazio (JSON antigo) com 3 vermelhos na mesa
        val state = (0L until 200L).map { dealRound(GameMode.INDIVIDUAL, Random(it)) }.first { it.redThreeLog.isNotEmpty() }
        val view = state.viewFor(Seat(0)).copy(redThreeLog = emptyList())
        view.determinize(Random(1)).viewFor(Seat(0)) shouldBe view
    }

    @Test
    fun `registro que nao bate com os 3 vermelhos da mesa e uma vista incoerente`() {
        // §3.5 / §6.5 cada 3 vermelho do registro está na área do lado de quem o baixou
        val state = (0L until 200L).map { dealRound(GameMode.DUPLAS, Random(it)) }.first { it.redThreeLog.isNotEmpty() }
        val view = state.viewFor(Seat(0))
        val first = view.redThreeLog.first()
        val otherSideSeat = Seat((first.seat.index + 1) % 4)
        val wrongSide = view.copy(redThreeLog = listOf(first.copy(seat = otherSideSeat)) + view.redThreeLog.drop(1))
        shouldThrow<IllegalArgumentException> { wrongSide.determinize(Random(1)) }.message shouldContain "registro"
        val outOfMode = view.copy(redThreeLog = listOf(first.copy(seat = Seat(7))) + view.redThreeLog.drop(1))
        shouldThrow<IllegalArgumentException> { outOfMode.determinize(Random(1)) }
    }

    @Test
    fun `troca atDeal de JSON antigo depois de uma troca da jogada e uma vista incoerente`() {
        // compatibilidade: no JSON antigo as trocas da distribuição (atDeal) vinham antes de todas as da jogada
        val state = (0L until 500L).map { dealRound(GameMode.INDIVIDUAL, Random(it)) }.first { it.redThreeLog.size >= 2 }
        val view = state.viewFor(Seat(0))
        val swapped = view.copy(redThreeLog = view.redThreeLog.dropLast(1) + view.redThreeLog.last().copy(atDeal = true))
        shouldThrow<IllegalArgumentException> { swapped.determinize(Random(1)) }.message shouldContain "registro"
    }
}
