package com.gtranca.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.ai.BotPlayer
import com.gtranca.ai.PlayStyle
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.MeldId
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.RuleError
import com.gtranca.engine.model.Seat
import com.gtranca.game.CustomHand
import com.gtranca.game.PlanRanking
import com.gtranca.game.GameConfig
import com.gtranca.game.GameController
import com.gtranca.game.GameSnapshot
import com.gtranca.game.LocalTableDriver
import com.gtranca.game.TableDriver
import com.gtranca.game.HandOrder
import com.gtranca.game.HandPrefs
import com.gtranca.game.HandSort
import com.gtranca.game.Persona
import com.gtranca.game.PersonaGenerator
import kotlinx.coroutines.cancelAndJoin
import com.gtranca.game.BotLevel
import com.gtranca.game.BotProfile
import com.gtranca.game.BotCharacter
import com.gtranca.game.PlayerProfile
import com.gtranca.game.HumanPlayer
import com.gtranca.game.HumanTurnResolver
import com.gtranca.game.PlayIntent
import com.gtranca.game.LogRedThreeSource
import com.gtranca.game.RedThreeNotice
import com.gtranca.game.RedThreeSwapSource
import com.gtranca.game.allocateReplacements
import com.gtranca.game.Resolution
import com.gtranca.game.Stage
import com.gtranca.game.cardClass
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import com.gtranca.game.WriteQueue
import com.gtranca.game.toSaved
import com.gtranca.game.savedGameOf
import com.gtranca.game.SaveSnapshot
import com.gtranca.game.RestoredGame
import com.gtranca.game.GamePersistence
import com.gtranca.data.GameResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Mensagem para o humano (o texto em pt-BR é escolhido na camada de UI). */
sealed interface UiMessage {
    /** Jogada recusada pelo motor; [error] `null` = motivo desconhecido. */
    data class Rejected(val error: RuleError?) : UiMessage

    /** Para descartar, selecione exatamente uma carta. */
    data object SelectOneCardToDiscard : UiMessage

    /** Mesa multijogador com as cartas escondidas: mostre-as para selecionar. */
    data object ShowCardsToPlay : UiMessage
}

/**
 * Encenação de 3 vermelho(s) que entraram na mesa do lado do humano (§6.5): por ação dele ou na distribuição. O
 * motor já os baixou e repôs; até o humano tocar em "Baixar", a mesa não mostra esses 3 vermelhos e a mão não
 * mostra as cartas [hiddenHandCards] (reposição/morto) recebidas na mesma ação.
 */
data class RedThreeReveal(val notice: RedThreeNotice, val hiddenHandCards: Set<Card>)

/** Telas depois de uma partida: anúncio do resultado, pontos (§12), anúncio do fim do jogo e tela final (§13). */
/** Etapas do fim da partida (docs/telas.md 3.9–3.12): mesa final com "Seguir", pontos da partida e, no fim do jogo, o resultado. */
enum class EndScreen { FINAL_TABLE, ROUND_POINTS, FINAL }

/**
 * Estado da tela de jogo. Derivado do [GameSnapshot] (vista do humano) e do estado local da tela; os
 * controles e o destaque vêm de `legalActions` ([GameSnapshot.humanLegal]).
 *
 * @property hand mão na ordem escolhida, sem as cartas ainda escondidas por uma encenação de 3 vermelho.
 * @property customHand blocos da ordem Personalizada (coluna de 3 pretos e coringas + resto), ou `null`.
 * @property highlighted cartas físicas que participam de jogada (por contagem de cópias).
 * @property newCards cartas que acabaram de entrar na mão (compra, reposição, morto, lixo), até a próxima jogada.
 * @property redThrees 3 vermelhos na mesa de cada lado, sem os que ainda estão sendo encenados.
 * @property reveal troca de 3 vermelho do próprio humano sendo encenada (bloqueia a mesa até "Baixar 3 vermelho").
 * @property banner troca de 3 vermelho de outro assento sendo mostrada (some sozinha após a duração da animação).
 */
data class GameUiState(
    val snapshot: GameSnapshot,
    val hand: List<Card>,
    val customHand: CustomHand?,
    val selected: List<Card>,
    val highlighted: Set<Card>,
    val newCards: Set<Card>,
    val sort: HandSort,
    val redThrees: List<List<Card>>,
    val reveal: RedThreeReveal?,
    /** §6.5 há troca de 3 vermelho do humano ainda não confirmada ("Baixar 3 vermelho"): a mesa fica bloqueada. */
    val ownSwapPending: Boolean,
    val banner: RedThreeNotice?,
    val planChoice: List<Action.TakeDiscardPile>?,
    /** "Baixar" com mais de uma possibilidade: opções (conjunto novo/acréscimos), a que mais pontua primeiro. */
    val meldChoice: List<Action>? = null,
    val confirmDecline: Boolean,
    val confirmResign: Boolean,
    val message: UiMessage?,
    val endScreen: EndScreen?,
    /** Cartas voando agora (animações da mesa); vazio com animações desligadas. */
    val flights: List<CardFlight> = emptyList(),
    /** §7 conjuntos que acabaram de virar canastra (destaque curto). */
    val canastaFlashes: List<CanastaFlash> = emptyList(),
    /**
     * Duração efetiva de cada voo, já multiplicada pela escala de animação do sistema (0 = sem animação: com
     * "remover animações", nenhum voo é gerado).
     */
    val animationMillis: Long = 0,
    /** §14.1 perfil de cada assento (vazio se a tela não tem perfis). */
    val personas: List<Persona> = emptyList(),
    /** §3.5 a distribuição ainda está sendo animada: a tela avisa quando termina ([TableEvents.onDealAnimationFinished]). */
    val dealPending: Boolean = false,
    /** Voos da distribuição (a tela espera todos pousarem para avisar que ela terminou). */
    val dealFlightIds: Set<Long> = emptySet(),
    /** Quanto tempo o aviso de troca de 3 vermelho fica na tela antes de fechar sozinho (0 = sem animações). */
    val swapNoticeMillis: Long = 0,
    /** §16.6 multijogador: a vez já chegou, mas este aparelho ainda anima as jogadas anteriores. */
    val turnHeld: Boolean = false,
    /** §16.6 multijogador: a vez de um humano remoto também aparece (não há robô "pensando" nela). */
    val remoteTurns: Boolean = false,
    /** Mesa multijogador: o botão "Esconder cartas" aparece. */
    val canHideHand: Boolean = false,
    /** §17.3 boosters do jogador na partida em andamento (ids do catálogo; vazio = nenhum). */
    val boosters: List<String> = emptyList(),
    /** §17.3 o seletor de boosters da próxima partida está na tela (a partida só começa depois do Seguir). */
    val boosterSelectorPending: Boolean = false,
    /** As cartas da mão estão viradas (só neste aparelho). */
    val handHidden: Boolean = false,
) {
    val isHumanTurn: Boolean get() = snapshot.isHumanTurn && !ownSwapPending && !turnHeld
    private val legal: List<Action> get() = if (ownSwapPending || turnHeld) emptyList() else snapshot.humanLegal
    val awaitingDraw: Boolean get() = isHumanTurn && snapshot.view.phase == Phase.AWAITING_DRAW
    val playing: Boolean get() = isHumanTurn && snapshot.view.phase == Phase.PLAYING
    val canDraw: Boolean get() = Action.DrawFromStock in legal

    /** §5.3/§5.5 só com algum plano de pegar o lixo em `legalActions`. */
    val canTakeDiscardPile: Boolean get() = legal.any { it is Action.TakeDiscardPile }
    val canDecline: Boolean get() = Action.DeclineDraw in legal
    /** "Baixar" só quando a seleção forma conjunto novo ou completa um conjunto da mesa (em `legalActions`). */
    val canMeld: Boolean get() = playing && HumanTurnResolver.canMeld(legal, selected)

    val canDiscard: Boolean get() = playing && selected.size == 1

    /** A carta selecionada pode ser descartada agora (há um `Discard` dela em `legalActions`). */
    val canDiscardSelected: Boolean
        get() = canDiscard && legal.any { it is Action.Discard && it.card.cardClass == selected.single().cardClass }
}

/**
 * ViewModel da partida: cria o [GameController] (humano no assento 0, bots da dificuldade escolhida),
 * roda o laço no [viewModelScope] e traduz os gestos da tela em ações de `legalActions`
 * ([HumanTurnResolver]). Cada snapshot do controlador é absorvido num único passo ([absorb]), que deriva as
 * cartas novas e as encenações de 3 vermelho de forma consistente com o que a tela mostra.
 */
class GameViewModel(
    config: GameConfig,
    gameSeed: Long = Random.nextLong(),
    computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    botDelayMillis: Long = GameController.DEFAULT_BOT_DELAY_MILLIS,
    /** Troca os bots nos testes; `null` = o bot do nível e do estilo do personagem de cada assento (§14.5). */
    botFactory: ((Seat, Random) -> BotPlayer)? = null,
    private val redThreeSource: RedThreeSwapSource = LogRedThreeSource,
    /** Duração da animação automática de troca de 3 vermelho de outro assento (0 nos testes). */
    private val swapAnimationMillis: Long = DEFAULT_SWAP_ANIMATION_MILLIS,
    /**
     * Duração das animações da mesa (compra, descarte, lixo, baixas, 3 vermelho, canastra); 0 desliga (testes).
     * São só da interface: o laço do controlador não espera por elas.
     */
    private val animationMillis: Long = DEFAULT_ANIMATION_MILLIS,
    /** Velocidade inicial das animações: `true` = [SLOW_FACTOR] vezes mais lenta (Perfil). */
    slowAnimations: Boolean = false,
    /** Jogo salvo a retomar (então [config] e [gameSeed] devem ser os dele), ou `null` para um jogo novo. */
    restored: RestoredGame? = null,
    /** Identificador do jogo (estatísticas contam cada jogo uma vez); o do jogo salvo, ao retomar. */
    private val gameId: String = UUID.randomUUID().toString(),
    /** Onde gravar o jogo, a estatística e as preferências; `null` = não grava (testes). */
    private val persistence: GamePersistence? = null,
    /** Ordem da mão inicial (a preferida do jogador). */
    initialHand: HandPrefs = HandPrefs(),
    /** §14.1 perfil do jogador (nome e avatar); os dos adversários e do parceiro são sorteados pela semente do jogo. */
    playerProfile: PlayerProfile = PlayerProfile(),
    /** §14.1 caminho da foto do jogador no aparelho (vale se o perfil usa foto). */
    playerPhoto: String? = null,
    /** Fila das gravações (fora da thread principal e do laço do jogo; sobrevive ao fim desta tela). */
    private val writes: WriteQueue = WriteQueue.app,
    /** §16 mesa multijogador: quem conduz a mesa no lugar do controlador local; `null` = jogo contra robôs de sempre. */
    externalDriver: TableDriver? = null,
    /** §16 nomes e avatares dos assentos vindos da sala (mudam no jogo: robô substituto, "VAGO"); `null` = sorteio local. */
    private val externalPersonas: StateFlow<List<Persona>>? = null,
    /**
     * §16 mesa multijogador: a vez só é entregue ao jogador depois de terminar as animações deste aparelho (o anfitrião não
     * as espera, §16.6) e a mesa indica a vez também dos humanos remotos.
     */
    private val multiplayer: Boolean = false,
    /**
     * §14.4, §14.5 personagem de cada assento de robô (índice → `BotProfile.code`) como a mesa foi montada (escolhidos e
     * sorteados, na posição escolhida); vazio = sorteio pela semente do jogo (jogos salvos antes da mesa montada).
     */
    private val robots: Map<Int, String> = emptyMap(),
    /** §16.7 o jogo continua de onde estava na tela (virou online no meio): a primeira vista não refaz animação. */
    private val continuing: Boolean = false,
    /** §17.3 boosters da partida em andamento (já debitados); no jogo retomado vale o do jogo salvo. */
    initialBoosters: List<String> = emptyList(),
    /**
     * §17.3 inventário de boosters: com ele, cada partida depois da primeira começa pelo seletor (a 1ª passa por ele antes de
     * o jogo ser criado). `null` = sem boosters (testes e mesa multijogador).
     */
    val boosterInventory: com.gtranca.data.InventoryRepository? = null,
) : ViewModel(), TableEvents {

    private val config = config
    private val gameSeed = gameSeed

    /** O fim do jogo já foi para a fila: nenhuma gravação depois dele (recriaria o salvo apagado). */
    private val finishSent = AtomicBoolean(false)

    /** Laço do controlador local (parado quando o jogo vira online, §16.7). */
    private var runJob: kotlinx.coroutines.Job? = null

    /** Grava a foto do jogo (na fila, em ordem; falha só é registrada). */
    /**
     * §14.6 a aposta foi mesmo debitada ao começar. Se o saldo já não cobria (a tela da mesa estava desatualizada), o jogo
     * segue sem aposta: nada é salvo com ela nem pago na vitória.
     */
    @Volatile
    private var stakeCharged = true

    /** A configuração do jogo como foi jogada: sem aposta se o débito falhou. */
    private val playedConfig: GameConfig get() = if (stakeCharged) config else config.copy(stake = 0)

    private fun enqueueSave(snapshot: SaveSnapshot) {
        val store = persistence ?: return
        if (finishSent.get()) return
        val boostersNow = boosters
        writes.enqueue { store.save(savedGameOf(gameId, playedConfig, gameSeed, snapshot, drawnForName, robots, boostersNow)) }
    }

    /** O jogo é conduzido por este aparelho (contra robôs), não por uma mesa multijogador. */
    private val playsLocally: Boolean = externalDriver == null

    /** §17.3 boosters da partida em andamento; lido pela gravação do jogo (fora da thread principal). */
    @Volatile
    private var boosters: List<String> = restored?.boosters ?: initialBoosters

    private data class Local(
        val snapshot: GameSnapshot,
        val selected: List<Card> = emptyList(),
        val sort: HandSort = HandSort.BY_SUIT,
        val separateSpecial: Boolean = true,
        val newCards: Set<Card> = emptySet(),
        /** Trocas de 3 vermelho a encenar, em ordem; a primeira está na tela. */
        val swaps: List<RedThreeNotice> = emptyList(),
        /** Trocas já encenadas cujos voos ainda não pousaram: o controlador só segue quando chegam a zero. */
        val settling: Int = 0,
        /** Cartas da mão escondidas até a troca do humano ([RedThreeNotice.id]) ser confirmada. */
        val hidden: Map<Long, Set<Card>> = emptyMap(),
        val flights: List<CardFlight> = emptyList(),
        /** Voos da distribuição ainda no ar: as trocas do início da vez só são encenadas depois deles. */
        val dealIds: Set<Long> = emptySet(),
        /** §3.5 a tela avisou que a distribuição terminou (ou nada foi animado): aviso e voos das trocas já podem começar. */
        val dealDone: Boolean = true,
        /** A 1ª troca depois da distribuição vem logo depois dela (pausa curta, sem o aviso longo parado). */
        val afterDeal: Boolean = false,
        /** §3.5 troca própria do início da vez → troca em cadeia que chega à mão quando ela é confirmada. */
        val delivers: Map<Long, List<Long>> = emptyMap(),
        val flashes: List<CanastaFlash> = emptyList(),
        /** Escala de duração das animações do sistema (`ANIMATOR_DURATION_SCALE`; 0 = desligadas). */
        val animationScale: Float = 1f,
        /** Escala do sistema (`ANIMATOR_DURATION_SCALE`) e a velocidade escolhida pelo jogador (1 ou [SLOW_FACTOR]); [animationScale] é o produto. */
        val systemScale: Float = 1f,
        val speed: Float = 1f,
        /** §9.4 trocas de 3 vermelho que vieram no morto (id da troca → morto). */
        val mortoSwaps: Map<Long, Int> = emptyMap(),
        val planChoice: List<Action.TakeDiscardPile>? = null,
        val meldChoice: List<Action>? = null,
        /** Pedido ([GameSnapshot.humanRequestId]) em que os planos de [planChoice] foram resolvidos. */
        val planRequestId: Long = 0,
        val confirmDecline: Boolean = false,
        val confirmResign: Boolean = false,
        /** Mesa multijogador: cartas da mão viradas (só local, até o fim do jogo). */
        val handHidden: Boolean = false,
        val message: UiMessage? = null,
        val endStep: Int = 0,
        /** §17.3 o seletor de boosters da próxima partida está na tela. */
        val selectorPending: Boolean = false,
    )

    private val human = HumanPlayer()
    private val humanSeat = Seat(0)

    /** §14.1, §14.5 perfis por assento: o do jogador no seu e, nos demais, os personagens sorteados com a semente deste jogo. */
    private val drawnForName: String = restored?.playerName ?: playerProfile.name

    val personas: List<Persona> = run {
        val opponents = PersonaGenerator.opponents(gameSeed, config.mode.seatCount - 1, config.difficulty, drawnForName).iterator()
        val level = BotLevel.of(config.difficulty)
        List(config.mode.seatCount) { index ->
            val chosen = robots[index]?.let { code -> BotProfile.entries.firstOrNull { it.code == code } }
            when {
                index == humanSeat.index -> playerProfile.persona(PlayerProfile.DEFAULT_NAME, playerPhoto)
                robots.isNotEmpty() && chosen != null -> Persona(chosen.botName, "", BotCharacter(chosen, level))
                else -> opponents.next()
            }
        }
    }

    /** §14.5 cada bot joga no nível da dificuldade e no estilo do perfil do seu personagem. */
    private val styledBots: (Seat, Random) -> BotPlayer = { seat, random ->
        createBot(config.difficulty, random, style = personas[seat.index].character?.profile?.style ?: PlayStyle.NEUTRO)
    }
    private val controller: TableDriver = externalDriver ?: LocalTableDriver(
        GameController(
            config = config,
            gameSeed = gameSeed,
            players = GameController.playersFor(config, gameSeed, human, humanSeat, botFactory ?: styledBots, restored?.botRandomCalls.orEmpty()),
            viewerSeat = humanSeat,
            computeDispatcher = computeDispatcher,
            botDelayMillis = botDelayMillis,
            dealDelayMillis = if (animationMillis > 0) dealAnimationMillis(config) else 0,
            restored = restored,
            // Só repassa a foto; serializar e gravar fica com o consumidor em IO (não atrasa o laço).
            onSave = if (persistence == null) null else ::enqueueSave,
        ),
        human,
    )
    private val animationIds = AtomicLong(0)

    init {
        controller.dealDelayScale = speedFor(slowAnimations).toDouble()
    }

    private val local = MutableStateFlow(
        controller.state.value.let { initial ->
            // Retomada: o registro de 3 vermelhos já aconteceu (nada se reencena) e, se o jogo parou no fim de uma
            // partida, volta à tela de pontos dela.
            val base = Local(
                initial,
                sort = initialHand.sort,
                separateSpecial = initialHand.separateSpecial,
                speed = speedFor(slowAnimations),
                animationScale = speedFor(slowAnimations),
                endStep = if ((restored != null || continuing) && initial.stage == Stage.ROUND_OVER) 1 else 0,
            )
            absorb(base, initial, previous = if (restored != null || continuing) initial else null)
        },
    )

    val uiState: StateFlow<GameUiState> = (
        if (externalPersonas == null) local.map(::build)
        else kotlinx.coroutines.flow.combine(local, externalPersonas) { state, _ -> build(state) }
        ).stateIn(viewModelScope, SharingStarted.Eagerly, build(local.value))

    /** §16.6 a vez fica segura enquanto este aparelho ainda anima (distribuição, voos, trocas de 3 vermelho). */
    private fun turnHeld(state: Local): Boolean =
        multiplayer && (!state.dealDone || state.flights.isNotEmpty() || state.swaps.isNotEmpty() || state.settling > 0)

    init {
        viewModelScope.launch {
            controller.state.collect { snapshot ->
                local.update { absorb(it, snapshot, it.snapshot) }
                // §13/§13.1 fim do jogo: estatística (uma vez) e o jogo salvo apagado.
                if (snapshot.stage == Stage.GAME_OVER && persistence != null && finishSent.compareAndSet(false, true)) {
                    val result = when {
                        snapshot.resigned -> GameResult.RESIGNATION
                        snapshot.winner == snapshot.viewerSide -> GameResult.WIN
                        else -> GameResult.LOSS
                    }
                    // Na fila durável: sair da tela não interrompe o fim (apagar o salvo e contar o jogo).
                    writes.enqueue { persistence.finish(gameId, playedConfig.toSaved(), result) }
                }
            }
        }
        // Jogo novo: salvo desde o início (se o processo morrer antes da 1ª ação, o jogo continua de onde estava).
        // §14.6 a aposta é debitada ao começar o jogo (antes de salvar o jogo; retomar não debita de novo).
        if (restored == null && config.stake > 0) persistence?.let { store -> writes.enqueue { if (!store.debitStake(config.stake)) stakeCharged = false } }
        if (restored == null) (controller as? LocalTableDriver)?.let { enqueueSave(it.controller.saveSnapshot()) }
        // §16.1 recusa do anfitrião depois do envio: mostra o motivo (o convidado não valida nada sozinho).
        viewModelScope.launch { controller.rejections.collect { error -> local.update { it.copy(message = UiMessage.Rejected(error)) } } }
        // §3.5 rede de segurança: se a tela nunca avisar que a distribuição terminou (ex.: app em segundo plano), segue
        // depois do tempo da animação mais uma folga, para o jogo não travar.
        viewModelScope.launch {
            local.map { state -> state.snapshot.roundNumber to state.dealDone }
                .distinctUntilChanged()
                .collectLatest { (_, done) ->
                    if (!done) {
                        val state = local.value
                        val lastDelay = state.flights.filter { it.id in state.dealIds }.maxOfOrNull { it.delayMillis } ?: 0L
                        delay(lastDelay + effectiveMillis(state) * 2 + EXPIRY_MARGIN_MILLIS + DEAL_SIGNAL_GRACE_MILLIS)
                        onDealAnimationFinished()
                    }
                }
        }
        // Trocas de outros assentos: animação automática, uma de cada vez.
        viewModelScope.launch {
            local.map { state ->
                // §3.5 a troca do jogador seguinte só começa depois que o descarte (e qualquer voo) anterior pousou
                state.swaps.firstOrNull()?.takeIf { !isOwn(it, state.snapshot) && state.dealDone && state.flights.none { f -> f.id !in state.dealIds } && state.settling <= 0 }
            }
                .distinctUntilChanged()
                .collectLatest { head ->
                    if (head != null) {
                        // Logo depois da distribuição: pausa curta; nas demais trocas, o aviso tem o tempo de leitura todo.
                        local.value.let { now ->
                            delay(if (now.afterDeal) minOf(swapAnimationMillis, DEAL_SWAP_PAUSE_MILLIS) else (swapAnimationMillis * now.speed).toLong())
                        }
                        var landing = 0L
                        local.update { state ->
                            if (state.swaps.firstOrNull()?.id == head.id) {
                                val flights = swapFlights(head, state)
                                landing = landingMillis(flights, state)
                                state.copy(
                                    swaps = state.swaps.drop(1),
                                    flights = state.flights + flights,
                                    settling = state.settling + if (landing > 0) 1 else 0,
                                    afterDeal = false,
                                )
                            } else {
                                landing = 0
                                state
                            }
                        }
                        settleAfter(landing)
                    }
                }
        }
        // Cada voo/destaque some sozinho depois da sua duração (o desenho também para; isto só limpa o estado).
        viewModelScope.launch {
            val scheduled = mutableSetOf<Long>()
            // (id, atraso, vezes que o voo dura o normal): a batida dura mais e libera o anúncio logo que pousa.
            local.map { state ->
                state.flights.map { Triple(it.id, it.delayMillis, if (it.finale) it.durationFactor else 0f) } +
                    state.flashes.map { Triple(it.id, 0L, 0f) }
            }
                .distinctUntilChanged()
                .collect { items ->
                    items.filter { (id, _, _) -> scheduled.add(id) }.forEach { (id, delayMillis, finaleFactor) ->
                        launch {
                            // O desenho dura no máximo 2 × a duração efetiva (pulso: sobe e desce) após o atraso; o voo
                            // da batida, o seu tempo (2 × o normal) e nada mais.
                            val draw = if (finaleFactor > 0f) (effectiveMillis(local.value) * finaleFactor).toLong() + 300 else effectiveMillis(local.value) * 2
                            delay(delayMillis + draw + EXPIRY_MARGIN_MILLIS)
                            local.update { state ->
                                state.copy(flights = state.flights.filterNot { it.id == id }, flashes = state.flashes.filterNot { it.id == id })
                            }
                            scheduled.remove(id)
                        }
                    }
                }
        }
        // §3.5/§6.5 encenadas todas as trocas de 3 vermelho já vistas (fila vazia), libera a próxima ação do controlador.
        // A confirmação leva o tamanho do registro visto: só vale para a espera exata (as trocas que acabamos de mostrar).
        viewModelScope.launch {
            local.collect { state ->
                val snapshot = state.snapshot
                if (snapshot.stage == Stage.PLAYING && state.swaps.isEmpty() && state.settling <= 0 && snapshot.view.redThreeLog.isNotEmpty()) {
                    controller.presentationDone(snapshot.roundNumber, snapshot.view.redThreeLog.size)
                }
            }
        }
        runJob = viewModelScope.launch { controller.run() }
    }

    /**
     * §16.7, §16.12 "Wi-Fi ligado no meio de um jogo só com robôs": para o laço local e a gravação, apaga o jogo salvo e
     * devolve o estado exato (mesma mão, vez, placar e aposta) para o jogo seguir hospedado. `null` se não é um jogo local
     * em andamento.
     */
    suspend fun handOffToHost(): HandOff? {
        val driver = controller as? LocalTableDriver ?: return null
        if (driver.controller.state.value.stage == Stage.GAME_OVER || !finishSent.compareAndSet(false, true)) return null
        runJob?.cancelAndJoin()
        val snapshot = driver.controller.saveSnapshot()
        persistence?.let { store -> writes.enqueue { store.discard() } }
        val robots = personas.withIndex().mapNotNull { (i, p) -> p.character?.let { i to it.profile.code } }.toMap()
        return HandOff(playedConfig.copy(rules = snapshot.match.rules), gameSeed, snapshot, robots)
    }

    /** A troca é do próprio humano: encenação com o botão "Baixar 3 vermelho". */
    private fun isOwn(notice: RedThreeNotice, snapshot: GameSnapshot): Boolean = notice.seat == snapshot.viewerSeat

    // ---------- derivação ----------

    /**
     * Incorpora um novo snapshot ([previous] = o último já incorporado, `null` no início): cartas novas na mão,
     * trocas de 3 vermelho a encenar ([redThreeSource]) e a etapa das telas finais.
     */
    private fun absorb(state: Local, snapshot: GameSnapshot, previous: GameSnapshot?): Local {
        val old = state.snapshot
        val sameRound = old.roundNumber == snapshot.roundNumber
        val hand = snapshot.view.hand.toSet()
        // §5.2/§6.5/§9 cartas que entraram na mão desde o último snapshot visto (diferença de multiconjunto: as
        // cartas físicas são únicas).
        val gained = if (sameRound && previous != null) hand - old.view.hand.toSet() else emptySet()
        var newCards = if (sameRound) (state.newCards intersect hand) + gained else emptySet()

        val fresh = redThreeSource.newSwaps(previous, snapshot)
        val swaps = (if (sameRound) state.swaps else emptyList()) + fresh
        var hidden = if (sameRound) state.hidden else emptyMap()
        // A reposição (ou o morto) da troca do próprio humano só aparece depois do "Baixar 3 vermelho" da última
        // troca dele nesta ação.
        // §9.4 3 vermelhos que vieram no morto pego agora: as cartas do morto aparecem como novas (nada oculto),
        // e a troca anima do morto à área de 3 vermelhos.
        val mortoTaken = if (sameRound) {
            snapshot.view.mortoStatus.indices.firstOrNull { i ->
                snapshot.view.mortoStatus[i] is MortoStatus.Taken && old.view.mortoStatus.getOrNull(i) !is MortoStatus.Taken
            }
        } else {
            null
        }
        val takenBy = mortoTaken?.let { (snapshot.view.mortoStatus[it] as MortoStatus.Taken).side }
        val fromMorto = fresh.filter { !it.atTurnStart && takenBy != null && it.side == takenBy }
        val mortoSwaps = (if (sameRound) state.mortoSwaps else emptyMap()) + fromMorto.associate { it.id to mortoTaken!! }
        // §3.5/§6.5 as reposições do humano (informação privada da vista dele) ficam ocultas até a última troca dele ser
        // confirmada, e então aparecem como novas. Não se casa reposição com troca: numa reposição em cadeia a carta
        // intermediária era outro 3 vermelho, que não está mais na mão. Na 1ª vez da partida (sem snapshot anterior da
        // mesma partida) as reposições são as últimas cartas da mão: o motor as acrescenta ao fim.
        val ownSwaps = fresh.filter { it.seat == snapshot.viewerSeat && it !in fromMorto }
        val ownMorto = fresh.filter { it.seat == snapshot.viewerSeat && it in fromMorto }
        val dealing = effectiveMillis(state) > 0 && snapshot.stage == Stage.PLAYING && (previous == null || !sameRound)
        var delivers = if (sameRound) state.delivers else emptyMap()
        if (ownSwaps.isNotEmpty()) {
            val fromDeal = !(sameRound && previous != null)
            val startOnly = ownSwaps.all { it.atTurnStart }
            if (startOnly) {
                // §3.5 uma reposição por 3 vermelho baixado; as "em cadeia" (chained) chegaram como reposição de outra troca,
                // então só as demais reposições ficam na mão.
                val kept = ownSwaps.sumOf { it.cards.size } - ownSwaps.filter { it.chained }.sumOf { it.cards.size }
                val replacements = if (fromDeal) snapshot.view.hand.takeLast(kept).toSet() else gained
                val (givenAway, hiddenNow) = allocateReplacements(ownSwaps, snapshot.view.hand.filter { it in replacements })
                delivers = delivers + givenAway
                hidden = hidden + hiddenNow
                if (fromDeal) newCards = newCards + replacements
            } else {
                val replacements = if (fromDeal) snapshot.view.hand.takeLast(ownSwaps.sumOf { it.cards.size }).toSet() else gained
                hidden = hidden + (ownSwaps.last().id to replacements)
                if (fromDeal) newCards = newCards + replacements
            }
        }
        // §9.4 o morto do próprio humano: os 3 vermelhos vêm no morto (aparecem como novos); as reposições do monte são as
        // últimas cartas da mão (o motor as acrescenta ao fim) e chegam, na troca, depois dos 3 vermelhos.
        if (ownMorto.isNotEmpty()) {
            // §10 com o monte vazio não há reposição: nunca mais reposições do que cartas que havia no monte.
            val kept = minOf(
                ownMorto.sumOf { it.cards.size } - ownMorto.filter { it.chained }.sumOf { it.cards.size },
                old.view.stockSize,
            )
            val moved = snapshot.view.hand.filter { it in gained }
            val (givenAway, hiddenNow) = allocateReplacements(ownMorto, moved.takeLast(kept))
            delivers = delivers + givenAway
            hidden = hidden + hiddenNow
        }
        // Animações da mesa a partir das diferenças com o último snapshot incorporado.
        // O fim da partida (ROUND_OVER/GAME_OVER) chega junto da última jogada: ela também é animada (a batida, em
        // câmera lenta) e o anúncio espera o voo pousar. A desistência não anima nada.
        val keepFlights = sameRound && (snapshot.stage == Stage.PLAYING || !snapshot.resigned)
        val (newFlights, newFlashes) = if (effectiveMillis(state) > 0 && previous != null && keepFlights) {
            TableAnimations.derive(old, snapshot, hidden.values.flatten().toSet(), goOut = snapshot.view.phase == Phase.FINISHED, nextId = animationIds::incrementAndGet)
                .let { (flights, flashes) -> flights.map { it.scaled(state.animationScale) } to flashes }
        } else {
            emptyList<CardFlight>() to emptyList()
        }
        // Início de partida (jogo novo ou partida seguinte): distribuição do monte aos assentos.
        val dealFlights = if (dealing) {
            val hiddenCards = hidden.values.flatten().toSet()
            // Os 3 vermelhos do início da vez (já baixados pelo motor) fazem parte das 11 cartas dadas: chegam à mão e só
            // então vão à área de 3 vermelhos, na confirmação.
            val dealt = snapshot.view.hand.filter { it !in hiddenCards } +
                fresh.filter { it.seat == snapshot.viewerSeat && it.atTurnStart && !it.chained }.flatMap { it.cards }.filter { it !in snapshot.view.hand }
            TableAnimations.deal(snapshot, dealt, animationIds::incrementAndGet)
                .map { it.scaled(state.animationScale) }
        } else {
            emptyList()
        }
        val endStep = if (snapshot.stage == Stage.PLAYING || old.history.size != snapshot.history.size) 0 else state.endStep
        val playing = snapshot.stage == Stage.PLAYING
        if (!playing) newCards = emptySet()
        return state.copy(
            snapshot = snapshot,
            selected = state.selected.filter { it in hand },
            newCards = newCards,
            swaps = if (playing) swaps else emptyList(),
            mortoSwaps = if (playing) mortoSwaps.filterKeys { id -> swaps.any { it.id == id } } else emptyMap(),
            flights = if (keepFlights) state.flights + newFlights + dealFlights else dealFlights,
            dealIds = (if (playing && sameRound) state.dealIds else emptySet()) + dealFlights.map { it.id },
            dealDone = if (previous == null || !sameRound) !dealing else state.dealDone,
            afterDeal = if (dealing) fresh.isNotEmpty() else state.afterDeal,
            flashes = if (keepFlights) state.flashes + newFlashes else emptyList(),
            hidden = if (playing) hidden.filterKeys { id -> swaps.any { it.id == id } } else emptyMap(),
            delivers = if (playing) delivers.filterKeys { id -> swaps.any { it.id == id } } else emptyMap(),
            planChoice = state.planChoice.takeIf { snapshot.isHumanTurn },
            meldChoice = state.meldChoice.takeIf { snapshot.isHumanTurn },
            confirmDecline = state.confirmDecline && snapshot.isHumanTurn,
            confirmResign = state.confirmResign && snapshot.stage == old.stage && sameRound,
            endStep = endStep,
        )
    }

    private fun build(state: Local): GameUiState {
        val snapshot = state.snapshot
        val head = state.swaps.firstOrNull()
        // O diálogo do próprio 3 vermelho espera os voos em andamento (distribuição, compra) terminarem.
        val reveal = head?.takeIf { isOwn(it, snapshot) && state.dealDone && state.flights.isEmpty() }?.let { RedThreeReveal(it, state.hidden[it.id].orEmpty()) }
        val hidden = state.hidden.values.flatten().toSet()
        // Os 3 vermelhos ainda não encenados não aparecem na mesa (o atual entra quando a encenação termina).
        val staged = state.swaps.flatMap { it.cards }.toSet()
        // §6.5 o próprio 3 vermelho ainda não confirmado continua à vista na mão (o motor já o baixou): a compra pousa
        // nele e só depois vem o alerta da troca.
        // Numa reposição em cadeia (§6.5), só o 3 vermelho da compra aparece: o seguinte chega como reposição dele, depois
        // da confirmação. Os do início da vez (§3.5) e os do morto (§9.4) já estavam juntos na mão.
        val ownQueue = state.swaps.filter { isOwn(it, snapshot) }
        val chainHead = ownQueue.firstOrNull { !it.atTurnStart && it.id !in state.mortoSwaps }
        val ownPending = ownQueue
            .filter { ((it.atTurnStart || it.id in state.mortoSwaps) && (!it.chained || delivered(it, state))) || it == chainHead }
            .flatMap { it.cards }
            .filter { it !in snapshot.view.hand }
        val visibleHand = snapshot.view.hand.filter { it !in hidden } + ownPending
        val split = if (state.separateSpecial) HandOrder.split(visibleHand, state.sort) else null
        val hand = split?.all ?: HandOrder.sort(visibleHand, state.sort)
        val ownSwapPending = state.swaps.any { isOwn(it, snapshot) }
        val held = turnHeld(state)
        val legal = if (ownSwapPending || held) emptyList() else snapshot.humanLegal
        return GameUiState(
            snapshot = snapshot,
            hand = hand,
            customHand = split,
            selected = state.selected.filter { it in visibleHand },
            highlighted = HumanTurnResolver.highlightedCards(hand, legal, state.selected),
            newCards = state.newCards - hidden,
            sort = state.sort,
            redThrees = snapshot.view.redThrees.map { side -> side.filter { it !in staged } },
            reveal = reveal,
            ownSwapPending = ownSwapPending,
            banner = head?.takeIf { state.dealDone && reveal == null && !isOwn(it, snapshot) && state.flights.none { f -> f.id !in state.dealIds } && state.settling <= 0 },
            swapNoticeMillis = if (state.afterDeal) minOf(swapAnimationMillis, DEAL_SWAP_PAUSE_MILLIS) else (swapAnimationMillis * state.speed).toLong(),
            planChoice = state.planChoice,
            meldChoice = state.meldChoice,
            confirmDecline = state.confirmDecline,
            confirmResign = state.confirmResign,
            message = state.message,
            // A última jogada da partida (descarte ou baixa de quem bateu) pousa na mesa antes do anúncio aparecer.
            endScreen = endScreen(snapshot, state.endStep).takeUnless { state.endStep == 0 && !snapshot.resigned && state.flights.isNotEmpty() },
            flights = state.flights,
            canastaFlashes = state.flashes,
            animationMillis = effectiveMillis(state),
            personas = externalPersonas?.value ?: personas,
            turnHeld = held,
            remoteTurns = multiplayer,
            canHideHand = multiplayer,
            handHidden = multiplayer && state.handHidden,
            boosters = boosters,
            boosterSelectorPending = state.selectorPending,
            dealPending = !state.dealDone,
            dealFlightIds = state.dealIds,
        )
    }

    /** §3.5 o 3 vermelho em cadeia de [notice] já chegou à mão: a troca que o traz foi confirmada. */
    private fun delivered(notice: RedThreeNotice, state: Local): Boolean =
        state.delivers.any { (from, to) -> notice.id in to && state.swaps.none { it.id == from } }

    private fun endScreen(snapshot: GameSnapshot, step: Int): EndScreen? = when {
        snapshot.stage == Stage.PLAYING -> null
        snapshot.resigned -> EndScreen.FINAL
        snapshot.stage == Stage.ROUND_OVER -> if (step == 0) EndScreen.FINAL_TABLE else EndScreen.ROUND_POINTS
        else -> EndScreen.entries[step.coerceAtMost(EndScreen.entries.lastIndex)]
    }

    // ---------- gestos ----------

    /** Mesa multijogador: esconde ou mostra as cartas da mão (a seleção é limpa ao esconder). */
    override fun onToggleHideHand() {
        if (!multiplayer) return
        local.update { it.copy(handHidden = !it.handHidden, selected = if (!it.handHidden) emptyList() else it.selected) }
    }

    override fun onCardClick(card: Card) = local.update { state ->
        // Cartas escondidas não se selecionam: um aviso curto lembra de mostrá-las.
        if (multiplayer && state.handHidden) return@update state.copy(message = UiMessage.ShowCardsToPlay)
        val selected = state.selected.filter { it in state.snapshot.view.hand }
        state.copy(selected = if (card in selected) selected - card else selected + card, message = null)
    }

    override fun onClearSelection() = local.update { it.copy(selected = emptyList()) }

    override fun onSortChange(sort: HandSort) = updatePrefs { it.copy(sort = sort) }

    override fun onSpecialColumnToggle() = updatePrefs { it.copy(separateSpecial = !it.separateSpecial) }

    private fun updatePrefs(change: (Local) -> Local) {
        val updated = local.updateAndGet(change)
        val id = HandPrefs(updated.sort, updated.separateSpecial).id
        persistence?.let { store -> writes.enqueue { store.saveHandSort(id) } }
    }

    override fun onDraw() = play(PlayIntent.Draw)

    override fun onTakeDiscardPile() = play(PlayIntent.TakeDiscardPile)

    /** Toque no lixo: antes de comprar, pegar o lixo; depois, descartar a carta selecionada (§4.3, §8). */
    override fun onDiscardPileClick() {
        when (controller.state.value.view.phase) {
            Phase.AWAITING_DRAW -> play(PlayIntent.TakeDiscardPile)
            Phase.PLAYING -> play(PlayIntent.Discard)
            Phase.FINISHED -> Unit
        }
    }

    override fun onCreateMeld() = play(PlayIntent.CreateMeld)

    override fun onAddToMeld(meldId: MeldId) = play(PlayIntent.AddToMeld(meldId))

    override fun onDiscard() = play(PlayIntent.Discard)

    /** §10.2 recusar a compra encerra a partida sem vencedor: pede confirmação. */
    override fun onDeclineDraw() {
        if (Action.DeclineDraw in controller.state.value.humanLegal) local.update { it.copy(confirmDecline = true) }
    }

    override fun onConfirmDecline() {
        local.update { it.copy(confirmDecline = false) }
        play(PlayIntent.DeclineDraw)
    }

    override fun onDismissDecline() = local.update { it.copy(confirmDecline = false) }

    /** Escolha entre planos de pegar o lixo (todos vindos de `legal`). */
    override fun onPlanChosen(action: Action.TakeDiscardPile) {
        val requestId = local.value.planRequestId
        local.update { it.copy(planChoice = null) }
        submit(action, requestId)
    }

    override fun onDismissPlanChoice() = local.update { it.copy(planChoice = null) }

    /** Escolha entre as opções de "Baixar" (todas vindas de `legal`). */
    override fun onMeldChosen(action: Action) {
        val requestId = local.value.planRequestId
        local.update { it.copy(meldChoice = null) }
        submit(action, requestId)
    }

    override fun onDismissMeldChoice() = local.update { it.copy(meldChoice = null) }

    override fun onMessageShown() = local.update { it.copy(message = null) }

    /** "Baixar 3 vermelho": encerra a encenação; o 3 vermelho aparece na mesa e a reposição, como nova. */
    override fun onRevealConfirmed() {
        var landing = 0L
        local.update { state ->
            landing = 0
            val head = state.swaps.firstOrNull()?.takeIf { isOwn(it, state.snapshot) } ?: return@update state
            val flights = swapFlights(head, state)
            landing = landingMillis(flights, state)
            state.copy(
                swaps = state.swaps.drop(1),
                hidden = state.hidden - head.id,
                flights = state.flights + flights,
                settling = state.settling + if (landing > 0) 1 else 0,
                afterDeal = false,
            )
        }
        settleAfter(landing)
    }

    /** Tempo até o último voo de [flights] pousar (atraso do voo + duração); 0 sem voos (animações desligadas). */
    private fun landingMillis(flights: List<CardFlight>, state: Local): Long =
        if (flights.isEmpty()) 0 else flights.maxOf { it.delayMillis } + effectiveMillis(state)

    /**
     * §3.5/§6.5 uma troca foi encenada: o controlador só segue [landing] ms depois, quando os voos dela já pousaram.
     * Por tempo (não pela tela), para não travar se a mesa não estiver composta. Sem isso, a vez do humano podia abrir
     * com o 3 vermelho do adversário ainda voando (ex.: morto indireto com 3 vermelho, §9.3/§9.4).
     */
    private fun settleAfter(landing: Long) {
        if (landing <= 0) return
        viewModelScope.launch {
            delay(landing)
            local.update { it.copy(settling = it.settling - 1) }
        }
    }

    /** §13.1 desistir pede confirmação; cancelar volta como se nada tivesse acontecido. */
    override fun onResign() = local.update { it.copy(confirmResign = true) }

    override fun onDismissResign() = local.update { it.copy(confirmResign = false) }

    override fun onConfirmResign() {
        local.update { it.copy(confirmResign = false) }
        controller.resign()
    }

    fun onNextRound() {
        // §17.3 contra robôs, cada partida começa pelo seletor de boosters (antes de ver a distribuição).
        if (boosterInventory != null && playsLocally && !multiplayer) local.update { it.copy(selectorPending = true) }
        else controller.continueToNextRound()
    }

    /** §17.3 Seguir no seletor: os boosters da partida ([chosen], já debitados) valem e a próxima partida começa. */
    fun onBoostersConfirmed(chosen: List<String>) {
        if (!local.value.selectorPending) return
        boosters = chosen
        local.update { it.copy(selectorPending = false) }
        controller.continueToNextRound()
    }

    /** Avança entre as telas de fim de partida/jogo (anúncio → pontos → anúncio do jogo → final). */
    fun onEndNext() = local.update { it.copy(endStep = it.endStep + 1) }

    private fun play(intent: PlayIntent) {
        // Toque atrasado (ex.: toque duplo): se o controlador não espera mais o humano (a jogada anterior já foi
        // enviada, a vez mudou), ignora em vez de mostrar um motivo de outra situação. Durante a encenação de 3
        // vermelho a mesa está bloqueada.
        val snapshot = controller.state.value
        val ownSwapPending = local.value.let { l -> l.swaps.any { isOwn(it, l.snapshot) } }
        if (!snapshot.isHumanTurn || !controller.awaitingHuman() || ownSwapPending || turnHeld(local.value)) return
        val requestId = snapshot.humanRequestId
        val selected = local.value.selected.filter { it in snapshot.view.hand }
        val resolution = HumanTurnResolver.resolve(intent, selected, snapshot.humanLegal) { controller.explain(it, requestId) }
        if (resolution !is Resolution.Play && controller.state.value !== snapshot) return
        when (resolution) {
            is Resolution.Play -> submit(resolution.action, requestId)
            is Resolution.ChoosePlan ->
                local.update { it.copy(planChoice = PlanRanking.rank(resolution.options, snapshot.view), planRequestId = requestId, message = null) }
            is Resolution.Rejected -> local.update { it.copy(message = UiMessage.Rejected(resolution.reason)) }
            Resolution.SelectOneCardToDiscard -> local.update { it.copy(message = UiMessage.SelectOneCardToDiscard) }
            is Resolution.ChooseMeld ->
                local.update { it.copy(meldChoice = PlanRanking.rankMelds(resolution.options, snapshot.view), planRequestId = requestId, message = null) }
        }
    }

    /** A tela avisa que a distribuição terminou de ser animada (o último voo pousou). */
    override fun onDealAnimationFinished() {
        var round = 0
        var changed = false
        local.update { state ->
            round = state.snapshot.roundNumber
            changed = !state.dealDone
            if (changed) state.copy(dealDone = true) else state
        }
        if (changed) controller.dealAnimationDone(round)
    }

    /** Escala de animação do sistema mudou (a tela observa `ANIMATOR_DURATION_SCALE`); 0 desliga os voos. */
    fun onAnimationScaleChanged(scale: Float) = local.update { state -> state.withScales(systemScale = scale, speed = state.speed) }

    /** Velocidade das animações escolhida pelo jogador (Perfil ou botão da mesa): normal ou [SLOW_FACTOR] vezes mais lenta. */
    fun onSpeedChanged(slow: Boolean) {
        val speed = speedFor(slow)
        controller.dealDelayScale = speed.toDouble()
        local.update { state -> state.withScales(systemScale = state.systemScale, speed = speed) }
    }

    private fun Local.withScales(systemScale: Float, speed: Float): Local {
        val scale = systemScale * speed
        if (scale == animationScale) return copy(systemScale = systemScale, speed = speed)
        return copy(
            systemScale = systemScale,
            speed = speed,
            animationScale = scale,
            flights = if (scale <= 0f) emptyList() else flights,
            flashes = if (scale <= 0f) emptyList() else flashes,
        )
    }

    /** Duração efetiva de cada animação (configurada × escala do sistema). */
    private fun effectiveMillis(state: Local): Long = (animationMillis * state.animationScale).toLong().coerceAtLeast(0)

    private fun CardFlight.scaled(scale: Float): CardFlight = copy(delayMillis = (delayMillis * scale).toLong())

    /**
     * §6.5 voos da troca encenada [notice] (que está saindo da fila de [state]): o 3 vermelho vai à área do lado e a
     * reposição chega do monte. Do humano: numa cadeia (mesma compra), a reposição de uma troca é o 3 vermelho da troca
     * seguinte, e a da última é a carta revelada agora; vindo do morto (§9.4), sem reposição do monte. De outro assento,
     * a reposição chega virada.
     */
    private fun swapFlights(notice: RedThreeNotice, state: Local): List<CardFlight> {
        if (effectiveMillis(state) <= 0) return emptyList()
        val viewer = state.snapshot.viewerSeat
        val fromMorto = state.mortoSwaps[notice.id]
        // Do humano: primeiro as cartas das trocas em cadeia que esta troca entrega (são 3 vermelhos que vão à mesa na
        // troca seguinte), depois as reposições que ficam na mão.
        val given = state.delivers[notice.id].orEmpty().flatMap { id -> state.swaps.first { it.id == id }.cards }
        val kept = state.hidden[notice.id]?.toList().orEmpty()
        val replacement = when {
            notice.seat != viewer -> TableAnimations.Replacement.FaceDown(notice.cards.size)
            given.isNotEmpty() || kept.isNotEmpty() -> TableAnimations.Replacement.Revealed(given + kept)
            fromMorto != null -> TableAnimations.Replacement.None
            else -> {
                val next = state.swaps.getOrNull(1)
                if (!notice.atTurnStart && next != null && next.seat == viewer && !next.atTurnStart && next.id !in state.mortoSwaps) {
                    TableAnimations.Replacement.Revealed(next.cards)
                } else {
                    TableAnimations.Replacement.None
                }
            }
        }
        return TableAnimations.redThreeSwap(notice, viewer, replacement, animationIds::incrementAndGet, fromMorto)
            .map { it.scaled(state.animationScale) }
    }

    /** Tempo da animação de distribuição (cartas dos assentos e dos mortos, escalonadas, mais um voo). */
    private fun dealAnimationMillis(config: GameConfig): Long =
        (config.mode.seatCount * 11 + 2) * TableAnimations.DEAL_STAGGER_MILLIS + animationMillis + EXPIRY_MARGIN_MILLIS

    companion object {
        /** Folga para a limpeza de uma animação depois do fim do desenho. */
        private const val EXPIRY_MARGIN_MILLIS: Long = 150

        /** Folga da rede de segurança da distribuição: a tela tem este tempo a mais para avisar que terminou. */
        private const val DEAL_SIGNAL_GRACE_MILLIS: Long = 1_500

        /** Pausa entre o fim da distribuição e a 1ª troca de 3 vermelho de um bot. */
        const val DEAL_SWAP_PAUSE_MILLIS: Long = 400

        /** Duração padrão de cada animação da mesa (300–600 ms). */
        const val DEFAULT_ANIMATION_MILLIS: Long = 450

        /** Duração padrão da animação de troca de 3 vermelho de outro assento. */
        const val DEFAULT_SWAP_ANIMATION_MILLIS: Long = 1_800

        /** Fator de duração da velocidade "lenta" (30% mais lenta). */
        const val SLOW_FACTOR: Float = 1.3f

        fun speedFor(slow: Boolean): Float = if (slow) SLOW_FACTOR else 1f
    }

    private fun submit(action: Action, requestId: Long) {
        if (controller.submit(action, requestId)) {
            local.update { it.copy(selected = emptyList(), newCards = emptySet(), message = null) }
        }
    }
}

/** §16.7 o jogo local que vira online: configuração jogada, semente, estado salvo e o personagem de cada assento de robô. */
data class HandOff(
    val config: GameConfig,
    val gameSeed: Long,
    val snapshot: SaveSnapshot,
    val robots: Map<Int, String>,
)
