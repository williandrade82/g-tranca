package com.gtranca.engine

import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Meld
import com.gtranca.engine.model.MeldError
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RoundResult
import com.gtranca.engine.model.RoundState
import com.gtranca.engine.model.RuleResult
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.cards
import com.gtranca.engine.model.shouldBeOk
import com.gtranca.engine.model.shouldFailWith
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Parâmetros de regra (§14.2) e perfis da casa (§14.3). */
class RuleProfilesTest {

    private val paulista = RuleSet.PAULISTA

    private fun RoundState.actP(seat: Int, action: Action) = RoundEngine.apply(this, Seat(seat), action, paulista)
    private fun RoundState.checkP(seat: Int, action: Action) = RoundEngine.validate(this, Seat(seat), action, paulista)
    private fun RoundState.legalP(seat: Int) = RoundEngine.legalActions(this, Seat(seat), paulista)

    @Test
    fun `perfis da casa sao Classica e Tranca Paulista com as diferencas do documento`() {
        // §14.3 Clássica = base; Paulista difere em groupRanks, mortoBecomesStock, goOutRequiresCleanCanasta e 3 preto
        RuleSet.HOUSE.map { it.id } shouldBe listOf("classica", "paulista")
        RuleSet.DEFAULT shouldBe RuleSet.CLASSICA
        with(RuleSet.CLASSICA) {
            groupRanks shouldBe GroupRanks.ANY
            mortoBecomesStock shouldBe true
            goOutRequiresCleanCanasta shouldBe false
            handBlackThreePoints shouldBe -5
            maxWildsPerMeld shouldBe 1
            targetScore shouldBe 3000
        }
        paulista shouldBe RuleSet.CLASSICA.copy(
            id = "paulista", name = "Tranca Paulista", baseId = "classica",
            groupRanks = GroupRanks.FACE_AND_ACE, mortoBecomesStock = false,
            goOutRequiresCleanCanasta = true, handBlackThreePoints = -100,
        )
    }

    @Test
    fun `duplicar um perfil registra a origem e mantem os valores`() {
        // §14.3 perfis personalizados: cópia de outro perfil, com a origem registrada
        val mine = paulista.duplicate("familia", "Regras da família").copy(handBlackThreePoints = -50)
        mine.baseId shouldBe "paulista"
        mine.version shouldBe 1
        mine.groupRanks shouldBe GroupRanks.FACE_AND_ACE
        mine.handBlackThreePoints shouldBe -50
    }

    @Test
    fun `valores fora das faixas sao recusados`() {
        // §14.2 perfil com valor fora das faixas não pode ser usado
        shouldThrow<IllegalArgumentException> { RuleSet(handBlackThreePoints = 10) }
        shouldThrow<IllegalArgumentException> { RuleSet(cleanCanastaPoints = -1) }
        shouldThrow<IllegalArgumentException> { RuleSet(dirtyCanastaPoints = -1) }
        shouldThrow<IllegalArgumentException> { RuleSet(targetScore = 0) }
        shouldThrow<IllegalArgumentException> { RuleSet(maxWildsPerMeld = 2) }
        RuleSet(groupRanks = GroupRanks.NONE, maxWildsPerMeld = 0) // §14.2 combinação dentro das faixas é válida
    }

    @Test
    fun `groupRanks restringe os numeros que formam grupo`() {
        // §6.1 / §14.2 groupRanks: só A, K, Q, J na Paulista (coringa permitido); nenhum grupo com NONE
        Meld.create(cards("7S 7D 7H"), paulista) shouldFailWith MeldError.GROUP_RANK_NOT_ALLOWED
        Meld.create(cards("KS KD KH"), paulista).shouldBeOk()
        Meld.create(cards("AS AD 2C"), paulista).shouldBeOk()
        Meld.create(cards("JS QD 2C"), paulista) shouldFailWith MeldError.NOT_A_SEQUENCE_OR_GROUP
        Meld.create(cards("7S 7D 7H")).shouldBeOk()
        val none = RuleSet(groupRanks = GroupRanks.NONE)
        Meld.create(cards("KS KD KH"), none) shouldFailWith MeldError.GROUP_RANK_NOT_ALLOWED
        Meld.create(cards("5H 6H 7H"), none).shouldBeOk()
    }

    @Test
    fun `groupRanks vale tambem ao pegar o lixo`() {
        // §6.1 a restrição vale para o grupo formado ao pegar o lixo (§5.1)
        val s = round { hand(0, "7S 7D KS KD 9C"); discard("4H 7H") }
        s.checkP(0, takeNew("7S 7D")) shouldFailWith MeldError.GROUP_RANK_NOT_ALLOWED
        s.check(0, takeNew("7S 7D")).shouldBeOk()
        s.legalP(0).takeNewPlans() shouldNotContain cards("7S 7D").toSet()
        val k = round { hand(0, "KS KD 9C 8D"); discard("4H KH") }
        k.checkP(0, takeNew("KS KD")).shouldBeOk()
    }

    @Test
    fun `coringa no buraco da sequencia vale em todos os perfis da casa`() {
        // §6.3 sequência 7-8-(2)-10 com o coringa travado no 9
        RuleSet.HOUSE.forEach { Meld.create(cards("7C 8C 2H TC"), it).shouldBeOk() }
        RuleSet.HOUSE.forEach { Meld.create(cards("KD AD 2H"), it).shouldBeOk() }
    }

    @Test
    fun `sem coringas os 2 saem do baralho`() {
        // §1 / §6.3 / §14.2 maxWildsPerMeld = 0: 96 cartas, nenhum 2; monte 52 (individual) e 30 (duplas)
        val noWilds = RuleSet(maxWildsPerMeld = 0)
        com.gtranca.engine.model.Deck.forRules(noWilds).size shouldBe 96
        com.gtranca.engine.model.Deck.forRules(noWilds).any { it.isWild } shouldBe false
        val solo = dealRound(GameMode.INDIVIDUAL, Random(3), noWilds)
        val all = solo.hands.flatten() + solo.mortos.flatten() + solo.stock + solo.redThrees.flatten()
        all.size shouldBe 96
        all.any { it.isWild } shouldBe false
        dealRound(GameMode.DUPLAS, Random(3), noWilds).let { it.stock.size + it.redThrees.flatten().size } shouldBe 30
        (solo.stock.size + solo.redThrees.flatten().size) shouldBe 52
        // a vista sorteada pelo bot Difícil também não tem 2
        solo.viewFor(Seat(0), noWilds).determinize(Random(1)).let { w ->
            (w.hands.flatten() + w.mortos.flatten() + w.stock).any { it.isWild } shouldBe false
        }
    }

    @Test
    fun `maxWildsPerMeld zero proibe coringa em conjuntos`() {
        // §6.3 / §14.2 com 0, o coringa não entra em nenhum conjunto
        val noWilds = RuleSet(maxWildsPerMeld = 0)
        Meld.create(cards("5H 6H 2C"), noWilds) shouldFailWith MeldError.WILDS_NOT_ALLOWED
        (Meld.create(cards("5H 6H 7H"), noWilds) as com.gtranca.engine.model.RuleResult.Ok).value.add(cards("2C"), noWilds) shouldFailWith MeldError.WILDS_NOT_ALLOWED
        Meld.create(cards("5H 6H 7H"), noWilds).shouldBeOk()
    }

    @Test
    fun `sem mortoBecomesStock o monte vazio nao usa o morto`() {
        // §10 / §14.2 mortoBecomesStock = não: segue-se o item 2; o morto continua disponível
        val s = round {
            hand(0, "KS QD 9C")
            stock("")
            discard("4H")
        }
        s.checkP(0, Action.DrawFromStock) shouldFailWith ActionError.STOCK_EXHAUSTED
        s.legalP(0) shouldContain Action.DeclineDraw
        val ended = s.actP(0, Action.DeclineDraw)
        ended.phase shouldBe Phase.FINISHED
        ended.result shouldBe RoundResult.NoWinner
        ended.mortoStatus[0] shouldBe MortoStatus.Available
        // Clássica: o morto vira monte
        s.act(0, Action.DrawFromStock).mortoStatus[0] shouldBe MortoStatus.BecameStock
    }

    @Test
    fun `goOutRequiresCleanCanasta exige canastra limpa para bater`() {
        // §11.1 / §14.2 com o parâmetro em sim, canastra suja não permite bater (nem ficar sem cartas, §8)
        val dirty = round {
            hand(0, "TH")
            stock("9D 5C 6C")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H 9H 2C")
        }.act(0, Action.DrawFromStock)
        (dirty.checkP(0, addTo(0, "TH")) is RuleResult.Failure) shouldBe true
        dirty.check(0, addTo(0, "TH")).shouldBeOk()

        val clean = round {
            hand(0, "JH")
            stock("9D 5C 6C")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H 9H TH")
        }.act(0, Action.DrawFromStock)
        clean.checkP(0, addTo(0, "JH")).shouldBeOk()
        val wentOut = RoundEngine.apply(RoundEngine.apply(clean, Seat(0), addTo(0, "JH"), paulista), Seat(0), discardCard("9D"), paulista)
        wentOut.phase shouldBe Phase.FINISHED
        wentOut.result shouldBe RoundResult.GoOut(Side(0), Seat(0))
    }

    @Test
    fun `coringa que suja a unica canastra limpa nao permite ficar sem cartas`() {
        // §11.1 / §14.2 / §8 a mesa resultante precisa ter a canastra limpa (legalActions e motor concordam)
        val s = round {
            hand(0, "2C")
            stock("9D 5C 6C")
            mortoTaken(0, 0)
            meld(0, "4H 5H 6H 7H 8H 9H TH")
        }.act(0, Action.DrawFromStock)
        (s.checkP(0, addTo(0, "2C")) is RuleResult.Failure) shouldBe true
        s.legalP(0) shouldNotContain addTo(0, "2C")
        s.check(0, addTo(0, "2C")).shouldBeOk()
    }

    @Test
    fun `3 preto na mao vale o parametro do perfil`() {
        // §12.2 / §14.2 handBlackThreePoints: –5 na Clássica, –100 na Paulista
        val s = round {
            hand(0, "3S 3C 9D")
            hand(1, "KS")
            phase = Phase.FINISHED
            result = RoundResult.NoWinner
        }
        scoreRound(s, RuleSet.CLASSICA)[0].hand.blackThrees.points shouldBe -10
        scoreRound(s, paulista)[0].hand.blackThrees.points shouldBe -200
    }

    @Test
    fun `o jogo guarda a copia das regras e os passos usam essa copia`() {
        // §14.4 regras aceitas na mesa ficam fixas no jogo; JSON antigo sem regras = Clássica
        val match = startMatch(GameMode.INDIVIDUAL, random = Random(1), rules = paulista)
        match.rules shouldBe paulista
        match.targetScore shouldBe 3000
        val custom = startMatch(GameMode.INDIVIDUAL, targetScore = 1500, random = Random(1), rules = paulista)
        custom.rules.targetScore shouldBe 1500
        val json = Json.encodeToString(com.gtranca.engine.model.Match.serializer(), custom)
        Json.decodeFromString(com.gtranca.engine.model.Match.serializer(), json).rules shouldBe custom.rules
        val legacy = json.replace(Regex(""","rules":\{[^}]*\}"""), "")
        Json.decodeFromString(com.gtranca.engine.model.Match.serializer(), legacy).rules shouldBe RuleSet.CLASSICA
    }

    @Test
    fun `a vista do jogador traz o perfil da mesa`() {
        // §14.4 todos conhecem as regras da mesa
        round { hand(0, "KS") }.viewFor(Seat(0), paulista).rules shouldBe paulista
    }
}
