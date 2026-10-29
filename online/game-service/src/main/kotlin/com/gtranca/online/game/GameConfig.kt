package com.gtranca.online.game

import com.gtranca.ai.HardBotConfig

/** Tudo configuravel por variavel de ambiente; nada de endereco ou segredo no codigo. */
data class GameConfig(
    /** Segredo servico a servico. Vazio/nulo = rotas `/internal/...` FECHADAS (falha fechada). */
    val internalSecret: String? = null,
    /** Endereco interno do lobby-service (nome do Service do cluster). */
    val lobbyUrl: String = "http://lobby-service",
    /** Pausa antes de cada jogada de robo, para o humano acompanhar. */
    val botDelayMs: Long = 1_200,
    /** Pausa entre o fim de uma partida e a distribuicao da proxima (§16.11: o jogo nao espera telas). */
    val roundPauseMs: Long = 6_000,
    val maxMatches: Int = 100,
    /** Depois do fim do jogo a sessao continua aceitando reconexao (para receber o resultado) por este tempo. */
    val retainAfterEndMs: Long = 10 * 60_000L,
    /** Sessao sem nenhuma atividade por este tempo e descartada (o abandono por tempo e a etapa 1.3c). */
    val idleTtlMs: Long = 2 * 3_600_000L,
    val hardConfig: HardBotConfig = HardBotConfig.DEFAULT,
    /** Limite de mensagens do cliente por conexao: reposicao por segundo e rajada maxima. */
    val wsMessagesPerSecond: Int = 10,
    val wsBurst: Int = 20,
    val wsPingMs: Long = 15_000,
    val wsTimeoutMs: Long = 45_000,
    /** Tentativas de conexao (handshake) por IP por minuto. */
    val handshakePerMinute: Int = 60,
    val trustForwardedFor: Boolean = false,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): GameConfig {
            val d = GameConfig()
            fun long(name: String, default: Long) = env[name]?.toLongOrNull() ?: default
            fun int(name: String, default: Int) = env[name]?.toIntOrNull() ?: default
            return GameConfig(
                internalSecret = env["INTERNAL_SECRET"]?.takeIf { it.isNotBlank() },
                lobbyUrl = env["LOBBY_URL"]?.takeIf { it.isNotBlank() } ?: d.lobbyUrl,
                botDelayMs = long("BOT_DELAY_MS", d.botDelayMs),
                roundPauseMs = long("ROUND_PAUSE_MS", d.roundPauseMs),
                maxMatches = int("MAX_MATCHES", d.maxMatches),
                retainAfterEndMs = long("RETAIN_AFTER_END_MS", d.retainAfterEndMs),
                idleTtlMs = long("IDLE_TTL_MS", d.idleTtlMs),
                wsMessagesPerSecond = int("WS_MESSAGES_PER_SECOND", d.wsMessagesPerSecond),
                wsBurst = int("WS_BURST", d.wsBurst),
                wsPingMs = long("WS_PING_MS", d.wsPingMs),
                wsTimeoutMs = long("WS_TIMEOUT_MS", d.wsTimeoutMs),
                handshakePerMinute = int("HANDSHAKE_PER_MINUTE", d.handshakePerMinute),
                trustForwardedFor = env["TRUST_FORWARDED_FOR"] == "true",
            )
        }
    }
}
