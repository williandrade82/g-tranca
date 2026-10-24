package com.gtranca.net

/**
 * §16.15 qualidade da conexão do convidado com o anfitrião, de 1 a 3 barras, pelo tempo de ida e volta das mensagens
 * (do envio à confirmação, média móvel) e pelos reenvios recentes. Só informação na tela; não muda o jogo.
 */
object LinkQuality {
    const val GOOD = 3
    const val FAIR = 2
    const val POOR = 1

    /** Até este tempo médio (ms), sem reenvio recente: 3 barras. */
    const val GOOD_RTT_MILLIS = 500.0

    /** Até este tempo médio (ms), com até [FAIR_RESENDS] reenvios recentes: 2 barras. */
    const val FAIR_RTT_MILLIS = 1_500.0
    const val FAIR_RESENDS = 2

    /** Sem enviar nada por este tempo, o convidado manda uma mensagem curta de teste ([Ping]). */
    const val PING_IDLE_MILLIS = 5_000L

    /** Barras para a média [avgRttMillis] (`null` = sem medida ainda) e os [recentResends]. */
    fun classify(avgRttMillis: Double?, recentResends: Int): Int {
        val rtt = avgRttMillis ?: 0.0
        return when {
            rtt <= GOOD_RTT_MILLIS && recentResends == 0 -> GOOD
            rtt <= FAIR_RTT_MILLIS && recentResends <= FAIR_RESENDS -> FAIR
            else -> POOR
        }
    }
}

/** Média móvel do tempo de ida e volta e contagem de reenvios recentes (cada medida limpa um reenvio). */
class LinkQualityMeter(private val alpha: Double = 0.3) {
    var avgRttMillis: Double? = null
        private set
    var recentResends: Int = 0
        private set

    fun onRtt(millis: Long) {
        avgRttMillis = avgRttMillis?.let { it * (1 - alpha) + millis * alpha } ?: millis.toDouble()
        if (recentResends > 0) recentResends--
    }

    fun onResend() {
        recentResends = minOf(recentResends + 1, 5)
    }

    fun level(): Int = LinkQuality.classify(avgRttMillis, recentResends)
}
