package com.gtranca.ai

/**
 * Pesos das heurísticas do [MediumBot], reunidos num só lugar para ajustes futuros.
 *
 * Unidade aproximada: **pontos de placar** (§12). Ex.: sujar uma canastra limpa custa
 * [dirtyCleanCanasta] = 100, a diferença entre a limpa (+200) e a suja (+100), §7.2.
 * Os valores de cartas são estimativas do quanto uma carta "vale" em potencial de jogo.
 *
 * Ajustados por simulação contra o [EasyBot] (diferença média de pontos por partida, §12): cartas
 * do lixo sem afinidade com a mão valem pouco (atrasam o morto, §9), coringa da mão pode ser gasto
 * com mais liberdade e o risco de alimentar o adversário pesa bastante.
 */
data class MediumWeights(
    // ---------- baixar (§4.3 etapa 2) ----------
    /** Cada carta que sai da mão para a mesa (ritmo para o morto; sai da penalidade da mão, §12.2, e soma o seu valor na mesa, §12.1). */
    val cardToTable: Double = 10.0,
    /** Bônus por formar canastra limpa (§7.2: +200). */
    val cleanCanastaBonus: Double = 60.0,
    /** Bônus por formar canastra suja (§7.2: +100). */
    val dirtyCanastaBonus: Double = 30.0,
    /** Bônus extra pela primeira canastra do lado, condição para bater (§11.1). */
    val firstCanastaBonus: Double = 40.0,
    /** Custo de gastar um coringa da mão (perde flexibilidade e o conjunto não fecha limpo). */
    val wildUse: Double = 12.0,
    /** Custo de sujar uma canastra limpa (§6.3/§7.3; a suja vale 100 a menos, §7.2; o coringa soma +10 na mesa, §12.1). */
    val dirtyCleanCanasta: Double = 100.0,
    /** Esvaziar a mão: pega o morto direto (§9.2) ou bate (§11.1). */
    val emptyHand: Double = 500.0,
    /** Ficar com 1 carta para, no descarte, pegar o morto indireto (§9.3) ou bater (§11.1). */
    val oneCardLeft: Double = 300.0,
    /** Fator sobre [wildUse] quando o lado já pode bater (morto e canastra, §11.1): esvaziar a mão é o que importa. */
    val wildUseGoingOutFactor: Double = 0.2,
    /** Pontuação mínima para baixar; abaixo disso, passa ao descarte. */
    val meldThreshold: Double = 5.0,

    // ---------- pegar o lixo (§5) ----------
    /** Valor esperado de comprar uma carta do monte, comparado ao de pegar o lixo. */
    val drawValue: Double = 12.0,
    /** Valor de cada carta natural que vem do lixo para a mão (§5.2). */
    val gainNatural: Double = 3.0,
    /** Valor extra de uma carta do lixo que combina com a mão ou com a mesa do lado. */
    val gainSynergy: Double = 10.0,
    /** Valor de um coringa que vem do lixo. */
    val gainWild: Double = 30.0,
    /** Valor de um 3 preto que vem do lixo (só serve para travar o lixo, §5.3). */
    val gainBlackThree: Double = 2.0,
    /** §5.4 coringa do topo num conjunto limpo (não canastra): ele vai fechar sujo. */
    val wildTopDirtiesMeld: Double = 25.0,
    /** §5.4 coringa do topo num conjunto novo. */
    val wildTopNewMeld: Double = 10.0,
    /** Risco de ficar com muitas cartas na mão se o adversário estiver para bater (§12.2), por carta. */
    val handPenaltyRisk: Double = 8.0,

    // ---------- descarte (§8) ----------
    /** Manter um coringa na mão. */
    val wildKeep: Double = 60.0,
    /** Guardar o 3 preto para travar o lixo depois (§5.3, §6.6), quando o lixo vale pouco agora. */
    val blackThreeReserve: Double = 12.0,
    /** Só guarda o 3 preto com pelo menos esta quantidade de cartas na mão e se o lado ainda não pode bater. */
    val blackThreeReserveMinHand: Int = 5,
    /** Utilidade do 3 preto fora da reserva: ele atrasa o esvaziamento da mão e vale −5 nela (§12.2). */
    val blackThreeDump: Double = -5.0,
    /** Cada outra carta do mesmo número na mão. */
    val pair: Double = 10.0,
    /** Cada vizinha imediata de naipe na mão. */
    val neighbor: Double = 7.0,
    /** Cada vizinha a dois passos (cabe com coringa) na mão. */
    val nearNeighbor: Double = 3.0,
    /** A carta cabe num conjunto do lado (inclusive do parceiro, §6.4). */
    val fitsOwnMeld: Double = 20.0,
    /** A carta faria um conjunto do lado virar canastra (ajuda a fechar canastras do lado). */
    val completesOwnCanasta: Double = 15.0,
    /** Desempate: J, Q, K e A penalizam mais na mão (§12.2: −10 contra −8). */
    val highCardPenalty: Double = 1.0,
    /** Base do valor do lixo para o adversário (o conjunto que ele baixa ao pegar). */
    val feedBase: Double = 30.0,
    /** Valor, para o adversário, de cada carta natural do lixo. */
    val oppNatural: Double = 20.0,
    /** Valor extra, para o adversário, de cada carta do lixo que cabe nos conjuntos dele. */
    val oppFitsMeld: Double = 6.0,
    /** Valor, para o adversário, de cada coringa do lixo. */
    val oppWild: Double = 30.0,
    /** Multiplicador extra do risco de alimentar quando o adversário está perto de bater. */
    val threatFeedMultiplier: Double = 1.5,
    /** Probabilidade estimada de o adversário conseguir levar um coringa do topo à mesa (§5.4). */
    val wildTakeProbability: Double = 0.9,
    /** Fator sobre a chance de grupo se o próximo adversário já descartou esse número. */
    val discardedRankDiscount: Double = 0.5,
)
