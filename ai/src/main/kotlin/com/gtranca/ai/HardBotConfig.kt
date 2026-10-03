package com.gtranca.ai

/**
 * Orçamento e parâmetros da busca do bot **Difícil** (ISMCTS, ver `com.gtranca.ai.hard.HardBot`).
 *
 * Orçamento (decisão do usuário): **iterações fixas, calibradas para celular**, com o teto de tempo
 * [timeLimitMillis] (800 ms) só como **rede de segurança**. A busca para ao completar [iterations] iterações
 * ou ao estourar o teto, o que vier primeiro. Enquanto o teto não é atingido, a escolha é determinística
 * (mesma semente e mesmas entradas, mesma ação); se ele for atingido, a jogada deixa de ser reproduzível e
 * o `HardBot` conta isso em `timeLimitHits`. O `:sim` e os testes rodam sem teto.
 *
 * Calibragem (desktop, uma thread, só decisões com mais de uma ação legal): o padrão (20 iterações, 2
 * candidatas por tipo) custa em média ~234 ms por decisão no individual (máximo 449 ms) e ~251 ms em duplas
 * (máximo 1016 ms), para a média ficar abaixo de 800 ms num celular 2–4× mais lento (os picos podem
 * atingir o teto). **A calibragem deve ser refeita num aparelho real quando o
 * `:app` existir** (medir a média e o máximo por decisão e quantas vezes o teto é atingido).
 *
 * Força medida contra o Médio (`:sim`, alvo 1000, `--check-invariants`, IC 95%):
 * - **padrão** (20 iterações, 2 candidatas por tipo, 5 visitas mínimas), 200 jogos por modo com sementes
 *   novas (3000 e 4000): individual 63,0% ± 6,7 p.p. (diferença por partida +79,7 ± 46,4); duplas
 *   66,0% ± 6,6 p.p. (+113,1 ± 48,8); duplas com lados trocados (Difícil no lado 1, semente 5000)
 *   61,0% ± 6,8 p.p. (+86,4 ± 43,4);
 * - referência, 20 iterações com 3 candidatas e 8 visitas mínimas (100 jogos): individual 64% ± 9,4 p.p.;
 *   duplas 53% ± 9,8 p.p. (o IC incluía 50%; hipótese: com 3 candidatas por tipo, 20 iterações espalham
 *   demais as amostras para a escolha conservadora ter evidência);
 * - referência, 40 iterações com 3 candidatas e 8 visitas mínimas (100 jogos): individual 62% ± 9,5 p.p.;
 *   duplas 66% ± 9,3 p.p.
 * Simular até o fim da partida rende mais por iteração, mas custa ~2,4× mais, por isso o padrão corta em
 * [rolloutTurns] passagens de vez.
 *
 * @property iterations iterações da busca por decisão (cada uma: uma determinização, um caminho na árvore e
 *   duas simulações, a do caminho e a da referência pareada, a preferida do Médio). Deve ser positivo.
 * @property timeLimitMillis teto de tempo por decisão, em milissegundos, ou `null` para não ter teto.
 * @property explorationConstant constante de exploração do UCT (sobre vantagens, diferenças de recompensas
 *   em [0, 1]; ver `Ismcts`).
 * @property candidatesPerKind fator de ramificação: em cada nó, só entram as melhores ações de cada tipo
 *   segundo a heurística do Médio (planos de pegar o lixo; baixas; descartes).
 * @property rolloutTurns quantas passagens de vez a simulação joga (com o Médio em todos os assentos)
 *   antes de parar e avaliar a mesa pela heurística; a simulação também para se a partida terminar.
 * @property rewardScale escala, em pontos de placar (§12): a recompensa é `0,5 + diferença / (2 × escala)`,
 *   limitada a [0, 1].
 * @property treeDepth quantas ações do próprio assento, na mesma jogada, entram na árvore; o resto da
 *   jogada é jogado pelo Médio na simulação. 1 = só a decisão atual (menos ruído com poucas iterações).
 * @property overrideMargin vantagem média mínima (em recompensa, sobre a preferida do Médio no mesmo mundo)
 *   para uma candidata trocar a preferida do Médio (escolha final conservadora).
 * @property minVisitsToOverride visitas mínimas de uma candidata para poder trocar a preferida do Médio.
 * @property overrideConfidence quantos erros-padrão a vantagem média precisa ter para trocar a preferida do
 *   Médio (0 desliga o teste).
 */
data class HardBotConfig(
    val iterations: Int = DEFAULT_ITERATIONS,
    val timeLimitMillis: Long? = DEFAULT_TIME_LIMIT_MILLIS,
    val explorationConstant: Double = 0.3,
    val candidatesPerKind: Int = 2,
    val rolloutTurns: Int = 10,
    val rewardScale: Double = 600.0,
    val treeDepth: Int = 1,
    val overrideMargin: Double = 0.01,
    val minVisitsToOverride: Int = 5,
    val overrideConfidence: Double = 1.0,
) {
    init {
        require(iterations > 0) { "iterations deve ser positivo: $iterations" }
        require(timeLimitMillis == null || timeLimitMillis > 0) { "timeLimitMillis deve ser positivo: $timeLimitMillis" }
        require(explorationConstant >= 0.0) { "explorationConstant não pode ser negativa" }
        require(candidatesPerKind > 0) { "candidatesPerKind deve ser positivo" }
        require(rolloutTurns >= 0) { "rolloutTurns não pode ser negativo" }
        require(rewardScale > 0.0) { "rewardScale deve ser positiva" }
        require(treeDepth > 0) { "treeDepth deve ser positivo" }
        require(overrideMargin >= 0.0) { "overrideMargin não pode ser negativa" }
        require(minVisitsToOverride >= 0) { "minVisitsToOverride não pode ser negativo" }
        require(overrideConfidence >= 0.0) { "overrideConfidence não pode ser negativa" }
    }

    /** Mesma configuração, sem teto de tempo: escolhas determinísticas (`:sim`, testes). */
    fun withoutTimeLimit(): HardBotConfig = copy(timeLimitMillis = null)

    companion object {
        const val DEFAULT_ITERATIONS: Int = 20
        const val DEFAULT_TIME_LIMIT_MILLIS: Long = 800

        /** Padrão do app: [DEFAULT_ITERATIONS] iterações, com teto de segurança de [DEFAULT_TIME_LIMIT_MILLIS] ms. */
        val DEFAULT: HardBotConfig = HardBotConfig()
    }
}
