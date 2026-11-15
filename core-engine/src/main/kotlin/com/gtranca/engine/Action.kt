package com.gtranca.engine

import com.gtranca.engine.model.Card
import com.gtranca.engine.model.MeldId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ação de um jogador na sua vez (§4.3). 3 vermelhos, morto e reposições são automáticos. */
@Serializable
sealed interface Action {
    /** §4.3 etapa 1: comprar do monte (§10.1: morto disponível vira monte se ele estiver vazio). */
    @Serializable
    @SerialName("drawFromStock")
    data object DrawFromStock : Action

    /**
     * §4.3 etapa 1 / §5: pegar o lixo, de forma atômica. O [plan] diz como o topo vai à mesa;
     * as demais cartas do lixo vão para a mão (§5.2). O topo nunca passa pela mão.
     */
    @Serializable
    @SerialName("takeDiscardPile")
    data class TakeDiscardPile(val plan: DiscardPlan) : Action

    /** §4.3 etapa 2: baixar um conjunto novo com cartas da mão. */
    @Serializable
    @SerialName("createMeld")
    data class CreateMeld(val cards: List<Card>) : Action

    /** §4.3 etapa 2 / §6.4: acrescentar cartas da mão ao conjunto [meldId] do lado. */
    @Serializable
    @SerialName("addToMeld")
    data class AddToMeld(val meldId: MeldId, val cards: List<Card>) : Action

    /** §4.3 etapa 3 / §8: descartar uma carta, encerrando a jogada. */
    @Serializable
    @SerialName("discard")
    data class Discard(val card: Card) : Action

    /** §10.2 recusar a compra com monte e mortos esgotados: a partida termina sem vencedor. */
    @Serializable
    @SerialName("declineDraw")
    data object DeclineDraw : Action

    /**
     * §3.6 palpite da largura (fim − início) numa barra de estágios, antes da 1ª vez da partida: só válido nas
     * fases [com.gtranca.engine.model.Phase.DEAL_GUESS_HAND] / [com.gtranca.engine.model.Phase.DEAL_GUESS_MORTO],
     * para o assento indicado em [com.gtranca.engine.model.RoundState.dealGuess]. Desde o ajuste de toque único
     * (15/11/2026): o motor **não normaliza mais** [start]/[end] — exige [start] sempre igual a
     * [com.gtranca.engine.model.DEAL_GUESS_FIXED_START] (sempre montado por [com.gtranca.engine.model.dealGuessAction],
     * nunca à mão) e só aceita [end] tal que `end - start` esteja dentro da faixa fixa do papel/modo
     * (`dealGuessHandRange`/`dealGuessMortoRange`), não mais o baralho inteiro.
     */
    @Serializable
    @SerialName("guessDealRange")
    data class GuessDealRange(val start: Int, val end: Int) : Action
}

/** Como o topo do lixo vai à mesa ao pegar o lixo (§5.1). */
@Serializable
sealed interface DiscardPlan {
    /** Conjunto novo com o topo e pelo menos 2 cartas da mão ([handCards]). */
    @Serializable
    @SerialName("newMeld")
    data class NewMeld(val handCards: List<Card>) : DiscardPlan

    /** Acréscimo do topo, sozinho ou com [handCards], ao conjunto [meldId] do lado. */
    @Serializable
    @SerialName("addToMeld")
    data class AddToMeld(val meldId: MeldId, val handCards: List<Card> = emptyList()) : DiscardPlan
}
