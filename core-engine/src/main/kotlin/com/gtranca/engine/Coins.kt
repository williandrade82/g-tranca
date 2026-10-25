package com.gtranca.engine

/**
 * Moedas e apostas (§14.6): economia local contra os jogadores virtuais. Tudo puro (sem Android, sem relógio): a data
 * do dia chega como parâmetro.
 */
object Coins {
    /**
     * §14.6 os três valores de aposta de cada nível dos robôs (0 = Iniciante/Fácil, 1 = Maduro/Médio, 2 = Sênior/Difícil).
     * Ao trocar de nível a aposta mantém o **mesmo índice** (1º, 2º ou 3º valor).
     */
    val STAKES_BY_LEVEL: List<List<Int>> = listOf(listOf(50, 80, 100), listOf(100, 150, 200), listOf(200, 300, 500))

    /** Aposta padrão (e a menor): o 1º valor do 1º nível. */
    val DEFAULT_STAKE: Int = STAKES_BY_LEVEL.first().first()

    /** §14.6 os três valores do [level] (0..2). */
    fun stakesFor(level: Int): List<Int> = STAKES_BY_LEVEL[level.coerceIn(0, STAKES_BY_LEVEL.lastIndex)]

    /**
     * §14.6 o valor de [stake] na tabela do [level]: o próprio, ou o **mais próximo** (empate: o menor). Cobre apostas
     * gravadas por versões anteriores (50, 100, 250 e 500).
     */
    fun nearestStake(level: Int, stake: Int): Int =
        stakesFor(level).minWith(compareBy<Int>({ kotlin.math.abs(it - stake) }, { it }))

    /** §14.6 o índice (0..2) de [stake] na tabela do [level] (o do valor mais próximo). */
    fun stakeIndexOf(level: Int, stake: Int): Int = stakesFor(level).indexOf(nearestStake(level, stake))

    /**
     * §14.6 a aposta de índice [index] no [level] que o [balance] cobre: o próprio valor, ou o **maior valor do nível que
     * o saldo cobre**; se nem o menor é coberto, o menor (a mesa fica bloqueada por saldo insuficiente).
     */
    fun fitStake(level: Int, index: Int, balance: Int): Int {
        val values = stakesFor(level)
        val wanted = values[index.coerceIn(0, values.lastIndex)]
        if (wanted <= balance) return wanted
        return values.filter { it <= balance }.maxOrNull() ?: values.first()
    }

    /** §14.6 saldo inicial da carteira. */
    const val INITIAL_BALANCE: Int = 1_000

    /** §14.6 moedas do ganho diário. */
    const val DAILY_GAIN: Int = 200

    /** §14.6 o jogador só entra na mesa com saldo maior ou igual à aposta. */
    fun canJoin(balance: Int, stake: Int): Boolean = balance >= stake

    /**
     * §14.6 quanto cada apostador recebe ao fim do jogo, na ordem de [stakes]. [winners] diz quem está no lado vencedor.
     *
     * Os perdedores recebem 0 (a aposta já foi debitada); cada vencedor recebe a própria aposta mais
     * `prêmio × aposta ÷ soma das apostas vencedoras`, em que o prêmio é a soma das apostas perdedoras; a sobra do
     * arredondamento vai pelo maior resto (empate: o primeiro). A soma recebida é igual à soma das apostas. Sem
     * vencedor (não ocorre: §13 não tem empate), todos recebem a própria aposta de volta.
     */
    fun payouts(stakes: List<Int>, winners: List<Boolean>): List<Int> {
        require(stakes.size == winners.size) { "Uma indicação de vencedor por aposta" }
        require(stakes.all { it >= 0 }) { "Aposta não pode ser negativa" }
        val winnerIndexes = winners.indices.filter { winners[it] }
        val winningTotal = winnerIndexes.sumOf { stakes[it].toLong() }
        if (winnerIndexes.isEmpty() || winningTotal == 0L) return stakes.toList()
        val prize = stakes.indices.filter { !winners[it] }.sumOf { stakes[it].toLong() }
        val result = MutableList(stakes.size) { 0 }
        val remainders = mutableListOf<Pair<Int, Long>>()
        var distributed = 0L
        for (i in winnerIndexes) {
            val exact = prize * stakes[i]
            val share = exact / winningTotal
            result[i] = stakes[i] + share.toInt()
            distributed += share
            remainders += i to exact % winningTotal
        }
        var left = prize - distributed
        for ((i, _) in remainders.sortedWith(compareByDescending<Pair<Int, Long>> { it.second }.thenBy { it.first })) {
            if (left == 0L) break
            result[i] += 1
            left--
        }
        return result
    }

    /**
     * O que o jogador (assento 0) recebe ao fim de um jogo em que todos apostam [stake] ([playerCount] 2 no individual,
     * 4 em duplas; os assentos pares são o lado do jogador): quem vence recebe 2 × [stake]; quem perde, 0.
     */
    fun humanPayout(playerCount: Int, stake: Int, won: Boolean): Int {
        val winners = List(playerCount) { (it % 2 == 0) == won }
        return payouts(List(playerCount) { stake }, winners)[0]
    }
}

/**
 * Carteira local (§14.6): [balance] inteiro e nunca negativo e o último dia ([lastClaimDay], dia do calendário local
 * em dias desde 1970) em que o ganho diário foi resgatado.
 */
data class Wallet(val balance: Int = Coins.INITIAL_BALANCE, val lastClaimDay: Long? = null) {
    init {
        require(balance >= 0) { "Saldo não pode ser negativo: $balance" }
    }

    /** O ganho diário está disponível em [today] (um por dia; relógio atrasado não libera de novo). */
    fun canClaimDaily(today: Long): Boolean = lastClaimDay == null || today > lastClaimDay

    /** Resgata o ganho diário em [today], ou `null` se já foi resgatado hoje. */
    fun claimDaily(today: Long): Wallet? =
        if (canClaimDaily(today)) Wallet(balance + Coins.DAILY_GAIN, today) else null

    /** Debita a aposta [stake], ou `null` se o saldo não cobre. */
    fun debit(stake: Int): Wallet? = if (Coins.canJoin(balance, stake)) copy(balance = balance - stake) else null

    /** Credita [amount] (prêmio). */
    fun credit(amount: Int): Wallet {
        require(amount >= 0) { "Crédito não pode ser negativo" }
        return copy(balance = balance + amount)
    }
}
