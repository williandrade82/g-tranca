package com.gtranca.ai

/**
 * Estilo de jogo de um personagem (§14.5): **preferências** entre jogadas legais, sobre qualquer nível. Não muda
 * regra nenhuma, e o bot continua escolhendo só entre as ações legais com a informação visível a um jogador.
 *
 * Todos os fatores valem 1,0 no [NEUTRO] (comportamento de antes dos estilos). Médio e Difícil aplicam o estilo
 * aos pesos da heurística ([apply]); o Fácil usa só os três `easy*` (ele não tem pesos).
 *
 * @property takePile fator sobre a vontade de pegar o lixo (> 1 pega mais; < 1 prefere o monte).
 * @property denyPile bônus por carta do lixo que o adversário deixa de ter (pegar para tirar material dele).
 * @property wildCaution fator sobre o cuidado com o coringa: gastá-lo, mantê-lo e sujar canastra limpa.
 * @property tableRush fator sobre a pressa de baixar (cartas à mesa e canastras).
 * @property goOutDrive fator sobre a vontade de esvaziar a mão (morto e batida).
 * @property feedCaution fator sobre o medo de alimentar o próximo adversário no descarte.
 * @property surprise margem de pontos dentro da qual descartes parecidos são sorteados (escolhas menos óbvias).
 * @property easyTakeRate Fácil: chance de pegar o lixo quando há plano legal (1 = sempre, como antes).
 * @property easyMeldSkip Fácil: chance extra de não baixar e passar ao descarte (0 = nunca, como antes).
 * @property easyScatter Fácil: chance extra de descartar uma carta qualquer (0 = nunca, como antes).
 */
data class PlayStyle(
    val id: String,
    val takePile: Double = 1.0,
    val denyPile: Double = 0.0,
    val wildCaution: Double = 1.0,
    val tableRush: Double = 1.0,
    val goOutDrive: Double = 1.0,
    val feedCaution: Double = 1.0,
    val surprise: Double = 0.0,
    val easyTakeRate: Double = 1.0,
    val easyMeldSkip: Double = 0.0,
    val easyScatter: Double = 0.0,
) {
    init {
        require(takePile > 0 && wildCaution > 0 && tableRush > 0 && goOutDrive > 0 && feedCaution > 0) { "Fatores devem ser positivos" }
        require(denyPile >= 0 && surprise >= 0) { "denyPile e surprise não podem ser negativos" }
        require(easyTakeRate in 0.0..1.0 && easyMeldSkip in 0.0..1.0 && easyScatter in 0.0..1.0) { "Taxas do Fácil devem estar entre 0 e 1" }
    }

    /** [base] com este estilo aplicado. */
    fun apply(base: MediumWeights): MediumWeights = base.copy(
        drawValue = base.drawValue / takePile,
        denyPile = base.denyPile + denyPile,
        wildUse = base.wildUse * wildCaution,
        wildKeep = base.wildKeep * wildCaution,
        dirtyCleanCanasta = base.dirtyCleanCanasta * wildCaution,
        cardToTable = base.cardToTable * tableRush,
        cleanCanastaBonus = base.cleanCanastaBonus * tableRush,
        dirtyCanastaBonus = base.dirtyCanastaBonus * tableRush,
        emptyHand = base.emptyHand * goOutDrive,
        oneCardLeft = base.oneCardLeft * goOutDrive,
        feedBase = base.feedBase * feedCaution,
        oppNatural = base.oppNatural * feedCaution,
        oppFitsMeld = base.oppFitsMeld * feedCaution,
        oppWild = base.oppWild * feedCaution,
        discardTieMargin = base.discardTieMargin + surprise,
    )

    companion object {
        /** Sem preferências: o jogo de sempre. */
        val NEUTRO = PlayStyle("neutro")

        /** Habilidoso e sagaz (JOVEM): aproveita o lixo, descarta com segurança, bate cedo. */
        val SAGAZ = PlayStyle("sagaz", takePile = 1.5, goOutDrive = 1.3)

        /** Sóbrio e conservador (SOBRIO): guarda o coringa, pega pouco o lixo, só bate com a mão resolvida. */
        val SOBRIO = PlayStyle(
            "sobrio", takePile = 0.6, wildCaution = 1.6, tableRush = 0.9, goOutDrive = 0.6, feedCaution = 1.4,
            easyTakeRate = 0.5, easyMeldSkip = 0.15,
        )

        /** Ousado (ALTERNATIVO): pega o lixo com frequência, gasta coringa, escolhas menos óbvias. */
        val OUSADO = PlayStyle(
            "ousado", takePile = 2.2, wildCaution = 0.6, feedCaution = 0.8, surprise = 6.0,
            easyScatter = 0.1,
        )

        /** Agressivo (ATLETA): pega o lixo para tirar material do adversário, baixa e bate cedo. */
        val AGRESSIVO = PlayStyle("agressivo", takePile = 1.4, denyPile = 8.0, wildCaution = 0.9, tableRush = 1.3, goOutDrive = 1.5, feedCaution = 0.9)

        val ALL: List<PlayStyle> = listOf(NEUTRO, SAGAZ, SOBRIO, OUSADO, AGRESSIVO)

        fun fromId(id: String): PlayStyle =
            ALL.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Estilo inválido: '$id'")
    }
}
