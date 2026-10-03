package com.gtranca.ai

import com.gtranca.engine.Action
import com.gtranca.engine.DiscardPlan
import com.gtranca.engine.RoundEngine
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.viewFor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.common.ExperimentalKotest
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.random.Random

@OptIn(ExperimentalKotest::class)
class MediumBotTest {

    private fun bot(seed: Long = 1) = MediumBot(Random(seed))

    private fun card(text: String) = cards(text).single()

    /** Só os descartes legais: isola a heurística de descarte. */
    private fun MediumBot.decideDiscard(state: RoundState): Action {
        val seat = state.currentSeat
        val discards = RoundEngine.legalActions(state, seat).filterIsInstance<Action.Discard>()
        return chooseAction(state.viewFor(seat), discards)
    }

    // ---------- etapa 1: monte ou lixo ----------

    @Test
    fun `pega o lixo quando o topo forma conjunto com a mao`() {
        // §5.1 7♦ no topo forma grupo com 7♥ e 7♠ da mão; o 9♣ do lixo combina com o 9♦ da mão
        val state = scenario(hand = "7H 7S 9D KD 5H", discard = "9C 7D", opponentHand = "JC JC' TC")
        val action = bot().decide(state)
        action.shouldBeInstanceOf<Action.TakeDiscardPile>()
        (action.plan as DiscardPlan.NewMeld).handCards shouldContainExactlyInAnyOrder cards("7H 7S")
    }

    @Test
    fun `com coringa no topo e lixo pequeno nao suja a canastra limpa`() {
        // §5.4 o único plano é levar o coringa do topo à canastra limpa 4…9♥, que viraria suja (−100, §7.2)
        val state = scenario(
            hand = "KS QD 9C 5D 7S",
            discard = "8C 2D",
            ownMelds = listOf("4H 5H 6H 7H 8H 9H"),
            opponentHand = "JC JC' TC",
        )
        val legal = RoundEngine.legalActions(state, state.currentSeat)
        legal.filterIsInstance<Action.TakeDiscardPile>().isNotEmpty() shouldBe true
        bot().decide(state) shouldBe Action.DrawFromStock
    }

    @Test
    fun `com coringa no topo e lixo valioso pega mesmo sujando a canastra limpa`() {
        // §5.4 pegar ou não, sabendo que o coringa suja o jogo limpo, é estratégia: aqui o lixo compensa
        val state = scenario(
            hand = "KS QD 9C 5D 7S",
            discard = "KD KC QS QH 9D 9S 5C 5C' 7D 7C 2C 2D",
            ownMelds = listOf("4H 5H 6H 7H 8H 9H"),
            opponentHand = "JC JC' TC",
        )
        val action = bot().decide(state)
        action.shouldBeInstanceOf<Action.TakeDiscardPile>()
        action.plan.shouldBeInstanceOf<DiscardPlan.AddToMeld>()
    }

    // ---------- etapa 2: baixar ----------

    @Test
    fun `suja a canastra limpa quando isso permite bater`() {
        // §6.3/§7.3 sujar é escolha de estratégia: com morto pego e canastra, o coringa na canastra
        // deixa só o K♠, que descartado é a batida (§11.1) e encerra a partida antes do adversário
        val state = scenario(
            hand = "2C KS",
            phase = Phase.PLAYING,
            ownMelds = listOf("4H 5H 6H 7H 8H 9H"),
            mortoStatus = listOf(MortoStatus.Taken(Side(0)), MortoStatus.Available),
        )
        val action = bot().decide(state)
        action.shouldBeInstanceOf<Action.AddToMeld>()
        action.cards shouldBe cards("2C")
    }

    @Test
    fun `nao suja a canastra limpa quando isso nao ajuda a fechar a mao`() {
        // §7.2 a canastra suja vale 100 a menos; sem morto, gastar o coringa nela não compensa
        val state = scenario(
            hand = "2C KS QD 9C 5D",
            phase = Phase.PLAYING,
            ownMelds = listOf("4H 5H 6H 7H 8H 9H"),
        )
        val action = bot().decide(state)
        action.shouldBeInstanceOf<Action.Discard>()
        action.card.isWild shouldBe false
    }

    @Test
    fun `usa o coringa para esvaziar a mao e pegar o morto direto`() {
        // §9.2 baixando todas as cartas, pega o morto e continua jogando
        var state = scenario(hand = "5H 6H 7H 2C KS KD", phase = Phase.PLAYING)
        val bot = bot()
        val played = mutableListOf<Action>()
        repeat(4) {
            if (state.mortoStatus[0] == MortoStatus.Available && state.phase == Phase.PLAYING) {
                val action = bot.decide(state)
                (action is Action.Discard) shouldBe false
                played += action
                state = RoundEngine.apply(state, state.currentSeat, action)
            }
        }
        // o 2♣ foi baixado (em conjunto novo ou acrescentado) e está na mesa do lado
        played.any { action ->
            (action is Action.CreateMeld && card("2C") in action.cards) ||
                (action is Action.AddToMeld && card("2C") in action.cards)
        } shouldBe true
        state.tables[0].allCards().contains(card("2C")) shouldBe true
        state.mortoStatus[0] shouldBe MortoStatus.Taken(Side(0))
        state.phase shouldBe Phase.PLAYING
        state.currentSeat shouldBe Seat(0)
    }

    // ---------- etapa 3: descartar ----------

    @Test
    fun `nao descarta o que o adversario acabou de pegar do lixo`() {
        // §5.6 o lixo é aberto: todos viram o adversário pegar o lixo com a Q♥ no topo, baixando-a com
        // Q♠ e Q♣ da mão (§5.1), e levar 7♥ e 7♦ para a mão (§5.2).
        // Descartar o 7♣ deixaria ele pegar o lixo formando grupo de 7 (§5.1).
        val state = scenario(
            hand = "7C KS KD 9H TH",
            phase = Phase.PLAYING,
            discard = "4S 8S",
            opponentHand = "7H 7D JC",
            opponentMelds = listOf("QS QC QH"),
        )
        // Sem memória, o 7♣ isolado é o descarte natural.
        bot().decide(state) shouldBe Action.Discard(card("7C"))

        val withMemory = bot()
        withMemory.observe(
            PublicEvent(
                seat = Seat(1),
                action = Action.TakeDiscardPile(DiscardPlan.NewMeld(cards("QS QC"))),
                takenFromDiscard = cards("7H 7D"),
            ),
        )
        withMemory.decide(state) shouldNotBe Action.Discard(card("7C"))

        // A memória é por partida: zera em onNewRound.
        withMemory.onNewRound()
        withMemory.decide(state) shouldBe Action.Discard(card("7C"))
    }

    @Test
    fun `nao descarta carta que cabe na mesa do adversario`() {
        // §5.1 o adversário pega o lixo acrescentando o topo a um conjunto dele
        val state = scenario(
            hand = "KH 9C 5D 7S",
            phase = Phase.PLAYING,
            discard = "4S",
            opponentMelds = listOf("KS KC KD"),
            opponentHand = "JC JC' TC",
        )
        bot().decide(state) shouldNotBe Action.Discard(card("KH"))
    }

    @Test
    fun `trava o lixo com 3 preto quando ele esta valioso para o adversario`() {
        // §5.3 / §6.6 todas as outras cartas caberiam na mesa do adversário, e o lixo é grande
        val state = scenario(
            hand = "3C KH 9D QD 5D",
            phase = Phase.PLAYING,
            discard = "4S 6C 8H JH TS 9S AC 4C",
            opponentMelds = listOf("KS KC KD", "6D 7D 8D", "QS QC QH"),
            opponentHand = "JC JC' TC",
        )
        bot().decide(state) shouldBe Action.Discard(card("3C"))
    }

    @Test
    fun `guarda o 3 preto quando o lixo vale pouco`() {
        // §5.3 o 3 preto fica de reserva para travar um lixo valioso depois
        val state = scenario(hand = "3C KH 9S 5D 7C", phase = Phase.PLAYING, opponentHand = "JC JC' TC")
        val action = bot().decide(state)
        action.shouldBeInstanceOf<Action.Discard>()
        action.card.isBlackThree shouldBe false
    }

    // ---------- duplas: cooperação com o parceiro ----------

    @Test
    fun `fecha a canastra do parceiro com a carta que completa o conjunto`() {
        // §6.4 os conjuntos são do lado: o 9♥ transforma o 4…8♥ do parceiro em canastra (§7.1)
        val state = duplasScenario(hand = "9H KS 6C QD 8S", ownMelds = listOf("4H 5H 6H 7H 8H"))
        val action = bot().decide(state)
        action.shouldBeInstanceOf<Action.AddToMeld>()
        action.cards shouldBe cards("9H")
    }

    @Test
    fun `nao descarta a carta que completa um conjunto do parceiro`() {
        val state = duplasScenario(hand = "9H KS 6C QD 8S", ownMelds = listOf("4H 5H 6H 7H 8H"))
        repeat(10) { seed ->
            bot(seed.toLong()).decideDiscard(state) shouldNotBe Action.Discard(card("9H"))
        }
    }

    @Test
    fun `em duplas evita alimentar o proximo jogador, e nao o parceiro`() {
        // §4.2 o próximo assento (1) é adversário; o parceiro (2) só joga depois dele e nunca pega o meu
        // descarte. Todos viram (§5.6) o assento 1 levar 7♥ e 7♠ do lixo e o parceiro levar 9♠ e 9♥.
        // O 7♣ alimentaria o assento 1 (grupo de 7, §5.1); o 9♦ serviria ao parceiro, que não o alcança.
        val state = duplasScenario(
            hand = "7C 9D KS KD TH JH",
            discard = "4S",
            ownMelds = listOf("8C 8C' 8D"),
            opponentMelds = listOf("5S 5S' 5C"),
            otherHands = listOf("7H 7S JC", "9S 9H QC", "KC KC' AC"),
        )
        repeat(10) { seed ->
            val bot = bot(seed.toLong())
            bot.observe(
                PublicEvent(Seat(1), Action.TakeDiscardPile(DiscardPlan.NewMeld(cards("5S 5S'"))), takenFromDiscard = cards("7H 7S")),
            )
            bot.observe(
                PublicEvent(Seat(2), Action.TakeDiscardPile(DiscardPlan.NewMeld(cards("8C 8C'"))), takenFromDiscard = cards("9S 9H")),
            )
            bot.decide(state) shouldBe Action.Discard(card("9D"))
        }
    }

    // ---------- §10 monte e mortos esgotados ----------

    /** Sem monte e sem morto disponível (§10.2): só resta pegar o lixo ou recusar a compra. */
    private fun exhaustedScenario(hand: String, discard: String, ownMelds: List<String>, sideHasMorto: Boolean) = scenario(
        hand = hand,
        discard = discard,
        ownMelds = ownMelds,
        opponentHand = "JC JC' TC",
        stock = "",
        mortoStatus = listOf(MortoStatus.Taken(if (sideHasMorto) Side(0) else Side(1)), MortoStatus.BecameStock),
    )

    @Test
    fun `sem monte nem morto recusa a compra quando o lixo nao compensa e o lado nao tem morto`() {
        // §10.2 o único plano suja a canastra limpa com o coringa do topo (§5.4); recusar encerra sem vencedor
        val state = exhaustedScenario("KS QD 9C 5D 7S", "8C 2D", listOf("4H 5H 6H 7H 8H 9H"), sideHasMorto = false)
        val legal = RoundEngine.legalActions(state, state.currentSeat)
        legal.contains(Action.DrawFromStock) shouldBe false
        legal.contains(Action.DeclineDraw) shouldBe true
        legal.any { it is Action.TakeDiscardPile } shouldBe true
        bot().decide(state) shouldBe Action.DeclineDraw
    }

    @Test
    fun `sem monte nem morto pega o lixo quando o lado tem morto, mesmo valendo pouco`() {
        // §10.2 / §11.1 com o morto do lado, manter a partida viva preserva a chance de bater
        val state = exhaustedScenario("KS QD 9C 5D 7S", "8C 2D", listOf("4H 5H 6H 7H 8H 9H"), sideHasMorto = true)
        bot().decide(state).shouldBeInstanceOf<Action.TakeDiscardPile>()
    }

    @Test
    fun `sem monte nem morto pega o lixo quando ele compensa`() {
        // §10.2 / §5.1 o 7♦ do topo forma grupo com 7♥ e 7♠ da mão
        val state = exhaustedScenario("7H 7S 9D KD 5H", "9C 7D", emptyList(), sideHasMorto = false)
        bot().decide(state).shouldBeInstanceOf<Action.TakeDiscardPile>()
    }

    // ---------- ameaça de batida do adversário ----------

    @Test
    fun `com o adversario ameacando bater aceita gastar o coringa para baixar`() {
        // Coringa na mão vale −10 se o adversário bater (§12.2). Sem ameaça, acrescentar só o 2♣ ao 4-5-6♥
        // não compensa o custo do coringa; com o adversário de morto (§9), canastra e mão pequena (§11.1), compensa.
        fun state(threat: Boolean) = scenario(
            hand = "2C KS QD 9C 5D",
            phase = Phase.PLAYING,
            ownMelds = listOf("4H 5H 6H"),
            opponentHand = "JC JC' TC",
            stock = "TD JD QH KH",
            opponentMelds = if (threat) listOf("4S 5S 6S 7S 8S 9S") else emptyList(),
            mortoStatus = listOf(MortoStatus.Available, if (threat) MortoStatus.Taken(Side(1)) else MortoStatus.Available),
        )
        val calm = bot().decide(state(threat = false))
        calm.shouldBeInstanceOf<Action.Discard>()
        calm.card.isWild shouldBe false

        val threatened = bot().decide(state(threat = true))
        threatened.shouldBeInstanceOf<Action.AddToMeld>()
        threatened.cards shouldBe cards("2C")
    }

    // ---------- 3 preto fora da reserva ----------

    @Test
    fun `descarta o 3 preto quando o lado ja pode bater`() {
        // §11.1 com morto e canastra, basta esvaziar a mão: o 3 preto (§6.6) sai da reserva.
        // A mesma mão sem morto guarda o 3 preto (ver `guarda o 3 preto quando o lixo vale pouco`).
        val state = scenario(
            hand = "3C KH 9S 5D 7C",
            phase = Phase.PLAYING,
            ownMelds = listOf("4H 5H 6H 7H 8H 9H"),
            opponentHand = "JC JC' TC",
            mortoStatus = listOf(MortoStatus.Taken(Side(0)), MortoStatus.Available),
        )
        bot().decide(state) shouldBe Action.Discard(card("3C"))
    }

    // ---------- coringa do topo em conjunto novo ----------

    @Test
    fun `custo do coringa do topo em conjunto novo pesa na decisao de pegar o lixo`() {
        // §5.4 o coringa do topo forma conjunto novo com K♠ e K♦ (o conjunto nasce sujo)
        val state = scenario(hand = "KS KD 9C 5H 7S", discard = "8C 2D", opponentHand = "JC JC' TC")
        val take = bot().decide(state)
        take.shouldBeInstanceOf<Action.TakeDiscardPile>()
        (take.plan as DiscardPlan.NewMeld).handCards shouldContainExactlyInAnyOrder cards("KS KD")

        val costly = MediumBot(Random(1), MediumWeights(wildTopNewMeld = 100.0))
        costly.decide(state) shouldBe Action.DrawFromStock
    }

    // ---------- fábrica ----------

    @Test
    fun `createBot cria o Medio e o Dificil continua nao suportado`() {
        createBot(Difficulty.MEDIO, Random(1)).shouldBeInstanceOf<MediumBot>()
        shouldThrow<UnsupportedOperationException> { createBot(Difficulty.DIFICIL, Random(1)) }
    }

    // ---------- propriedades ----------

    @Test
    fun `sempre devolve acao legal em partidas completas`() = runBlocking<Unit> {
        for (mode in GameMode.entries) {
            checkAll(PropTestConfig(iterations = 40, seed = 20_261_002), Arb.long()) { seed ->
                val bots = List(mode.seatCount) { MediumBot(Random(seed + it)) }
                playRound(mode, seed, bots) // confere cada ação contra legalActions
            }
        }
    }

    @Test
    fun `sempre devolve acao legal contra o Facil`() = runBlocking<Unit> {
        for (mode in GameMode.entries) {
            checkAll(PropTestConfig(iterations = 25, seed = 99), Arb.long()) { seed ->
                val bots = List(mode.seatCount) { i ->
                    if (mode.sideOf(Seat(i)).index == 0) MediumBot(Random(seed + i)) else EasyBot(Random(seed + i))
                }
                playRound(mode, seed, bots)
            }
        }
    }

    @Test
    fun `mesma semente produz as mesmas escolhas`() = runBlocking<Unit> {
        checkAll(PropTestConfig(iterations = 8, seed = 7), Arb.long()) { seed ->
            for (mode in GameMode.entries) {
                fun run() = playRound(mode, seed, List(mode.seatCount) { MediumBot(Random(seed * 31 + it)) })
                run() shouldBe run()
            }
        }
    }
}
