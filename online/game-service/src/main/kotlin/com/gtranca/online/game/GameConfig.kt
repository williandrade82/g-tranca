package com.gtranca.online.game

import com.gtranca.ai.HardBotConfig

/** Tudo configuravel por variavel de ambiente; nada de endereco ou segredo no codigo. */
data class GameConfig(
    /** Segredo servico a servico. Vazio/nulo = rotas `/internal/...` FECHADAS (falha fechada). */
    val internalSecret: String? = null,
    /** Endereco interno do lobby-service (nome do Service do cluster). */
    val lobbyUrl: String = "http://lobby-service",
    /** Endereco interno do economy-service (apostas, §18.2). `null` = sem economia (a aposta so e registrada). */
    val economyUrl: String? = null,
    /** Pausa antes de cada jogada de robo, para o humano acompanhar. */
    val botDelayMs: Long = 1_200,
    /** Pausa entre o fim de uma partida e a distribuicao da proxima (§16.11: o jogo nao espera telas). */
    val roundPauseMs: Long = 6_000,
    val maxMatches: Int = 100,
    /** Depois do fim do jogo a sessao continua aceitando reconexao (para receber o resultado) por este tempo. */
    val retainAfterEndMs: Long = 10 * 60_000L,
    /**
     * §18.4 "Todos desconectados": a mesa e destruida quando NENHUM humano esta conectado por este tempo seguido (90 s:
     * tela bloqueada ou ligacao nao destroem a mesa na hora).
     */
    val disconnectGraceMs: Long = 90_000L,
    /** Chave do aplicativo (§18.2) para o handshake. `null` = sem verificacao. */
    val appKey: String? = null,
    /**
     * §18.4: prazo para o PRIMEIRO humano conectar depois que a partida e criada (60 s).
     * A sessao nasce sem ninguem conectado (o app abre o canal logo apos o /start); a regra de destruicao so vale depois
     * que algum humano conectou ao menos uma vez ou, sem nenhuma conexao, passado este prazo.
     */
    val firstConnectMs: Long = 60_000L,
    /** Abandono dentro deste tempo desde o inicio do jogo devolve a aposta mesmo com `PERDE_APOSTA` (§18.4: 60 s). */
    val abandonGraceMs: Long = 60_000,
    /**
     * §17.3/§18.3: prazo do seletor de boosters do inicio do jogo, a partir de quando ele aparece a cada assento
     * humano (30 s). So vale com a economia configurada (`economyUrl`); sem ela o seletor nem aparece.
     */
    val boosterSelectMs: Long = 30_000L,
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
                economyUrl = env["ECONOMY_URL"]?.takeIf { it.isNotBlank() },
                botDelayMs = long("BOT_DELAY_MS", d.botDelayMs),
                roundPauseMs = long("ROUND_PAUSE_MS", d.roundPauseMs),
                maxMatches = int("MAX_MATCHES", d.maxMatches),
                retainAfterEndMs = long("RETAIN_AFTER_END_MS", d.retainAfterEndMs),
                disconnectGraceMs = long("DISCONNECT_GRACE_MS", d.disconnectGraceMs),
                appKey = System.getenv("APP_SIGNING_KEY")?.takeIf { it.isNotBlank() },
                firstConnectMs = long("FIRST_CONNECT_MS", d.firstConnectMs),
                boosterSelectMs = long("BOOSTER_SELECT_MS", d.boosterSelectMs),
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
