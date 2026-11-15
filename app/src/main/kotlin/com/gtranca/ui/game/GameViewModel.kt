package com.gtranca.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gtranca.ai.BotPlayer
import com.gtranca.ai.PlayStyle
import com.gtranca.ai.createBot
import com.gtranca.engine.Action
import com.gtranca.engine.model.Card
import com.gtranca.engine.model.Guess
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

/** §3.6 qual palpite do minijogo "distribuição certeira" (§3.8.2 do telas-app.md) acabou de resolver. */
enum class DealGuessStage { HAND, MORTO }

/**
 * §3.6 é a vez do humano palpitar: a tela mostra o modal "Cartas para a distribuição"/"Cartas para o Morto".
 * [target] é o número certo (44 ou 22 na mão, sempre 22 no morto) — número **público** das regras
 * (`definition.md` §3.6), mostrado na tela (telas-app.md 3.8.2); o que continua secreto, só do motor, é a
 * **posição** desse número certo dentro de [range] (a faixa fixa que a barra oferece, ajuste de 15/11/2026:
 * 35..55 na mão em duplas, 12..32 na mão em individual, 12..32 no morto em qualquer modo — `dealGuessHandRange`/
 * `dealGuessMortoRange`). O toque único escolhe um número **dentro** de [range]; [target] está sempre nela.
 */
data class DealGuessPrompt(val stage: DealGuessStage, val range: IntRange, val target: Int)

/**
 * §3.6 reação em tela cheia a um palpite resolvido: fica até o toque ([TableEvents.onDealGuessRevealDismissed]),
 * que libera a próxima jogada do motor (o palpite seguinte, ou a 1ª jogada de verdade). Exclusiva de quem palpitou
 * de verdade (`role == YOU`); o parceiro (duplas, `role == PARTNER`) usa a reação pequena e não bloqueante
 * [PartnerGuessReaction] (abaixo), não esta — por isso [partner] é sempre `false` aqui (mantido só para não mudar
 * o formato já testado; este estado é só da UI, nunca é serializado/salvo). [roundNumber] e [stage] identificam,
 * para o motor, qual espera reconhecer.
 */
data class DealGuessReveal(
    val stage: DealGuessStage,
    val partner: Boolean,
    val correct: Boolean,
    /**
     * Número que o jogador tocou (§3.6, toque único: o estágio do indicador naquele instante é o próprio palpite).
     * Desde o ajuste de 15/11/2026, o motor codifica esse número como `Guess.width` (`start` fixo em 1,
     * `dealGuessAction`), então não é mais preciso guardar o toque bruto à parte: `width` já é o valor de verdade.
     */
    val width: Int,
    val roundNumber: Int,
    val id: Long,
)

/** §3.6 reação reduzida (pílula) de quem palpitou: confete/borda dourada no acerto, tremor no erro; some sozinha. */
data class DealGuessPillReaction(val correct: Boolean, val id: Long)

/**
 * §3.6 reação pequena, não bloqueante, do palpite do PARCEIRO (duplas): ao contrário de [DealGuessReveal] (tela
 * cheia, só do próprio jogador), esta some sozinha depois de alguns instantes e nunca espera toque — o motor já foi
 * liberado na hora ([GameController.guessRevealDone]), igual aos demais assentos que não são o próprio nem o
 * parceiro. Nasce encostada na borda esquerda da área de jogos do próprio lado (`AnimAnchor.SideArea`) e desliza
 * para a direita (telas-app.md 3.8.2, redesenhada 14/11/2026); a UI conduz as três fases (entrada/repouso/saída)
 * sozinha, a partir deste estado.
 *
 * @property target alvo (largura certa) do papel palpitado — número **público** das regras (`definition.md` §3.6:
 *   `dealGuessHandTarget`/`dealGuessMortoTarget`), nunca segredo; mostrado no cartão junto de [guessedWidth].
 * @property guessedWidth largura que o parceiro realmente escolheu (`Guess.width`, do motor): `correct` é
 *   exatamente `guessedWidth == target`.
 */
data class PartnerGuessReaction(val correct: Boolean, val stage: DealGuessStage, val id: Long, val target: Int, val guessedWidth: Int)

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
/** §17.8 uma reação enviada pelo [seat], com o [id] do envio: cada id novo reinicia a animação no centro da tela. */
data class ReactionEvent(val seat: Seat, val reaction: com.gtranca.engine.Reaction, val id: Long)

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
    /** Jogo do próprio lado indicado para receber o topo do lixo (borda dourada). */
    val targetMeld: MeldId? = null,
    /** A indicação para pegar o lixo não casou com nenhum plano: modal *Não dá para pegar assim*. */
    val takeMistake: Resolution.TakeMistake? = null,
    /** "Baixar" com mais de uma possibilidade: opções (conjunto novo/acréscimos), a que mais pontua primeiro. */
    val meldChoice: List<Action>? = null,
    val confirmDecline: Boolean,
    val confirmResign: Boolean,
    val message: UiMessage?,
    val endScreen: EndScreen?,
    /** Cartas voando agora (animações da mesa); vazio com animações desligadas. */
    val flights: List<CardFlight> = emptyList(),
    /** Voos de jogadas que esperam na fila o pouso dos anteriores (telas.md 3.8.1 R1, R6): ainda não começaram. */
    val queuedFlights: List<CardFlight> = emptyList(),
    /** R6: cartas de cada assento (índice) ainda a caminho do morto: a pílula não as conta antes de pousarem. Preenchido pela tela. */
    val heldBack: Map<Int, Int> = emptyMap(),
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
    /** Camada "Prepare-se!" sobre a mesa, antes da distribuição da 1ª partida. */
    val opening: Boolean = false,
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
    /** §17.7 b2 Yellow-LED: as cartas da mão que se encaixam nos jogos do adversário (vazio sem o b2 ou fora da vez). */
    val opponentFits: Map<Card, com.gtranca.engine.OpponentFit> = emptyMap(),
    /** §17.8 b3 Reações: o botão aparece (o booster está na partida). */
    val reactionsEnabled: Boolean = false,
    /** §17.8 quantas reações ainda podem ser enviadas nesta partida (0 a 3). */
    val reactionsLeft: Int = 0,
    /** §17.8 a reação que o jogador acabou de enviar, por alguns segundos ([ownReactionId] muda a cada envio). */
    val ownReaction: com.gtranca.engine.Reaction? = null,
    val ownReactionId: Long = 0,
    /** As cartas da mão estão viradas (só neste aparelho). */
    val handHidden: Boolean = false,
    /** §17.8 reações dos outros assentos à vista agora (assento → reação), junto da pílula de cada um. */
    val seatReactions: Map<Int, com.gtranca.engine.Reaction> = emptyMap(),
    /** §17.8 a reação mais recente à vista (de qualquer assento), animada no centro da tela. */
    val lastReaction: ReactionEvent? = null,
    /** §17.3 mesa multijogador: a partida a que o seletor na tela se refere (0 = contra robôs: a da partida atual). */
    val boosterSelectorRound: Int = 0,
    /**
     * §17.3/§18.3 a vez é de um assento remoto que ainda não confirmou o próprio seletor de boosters: a mesa mostra
     * "Aguardando <nome> escolher os boosters" no lugar do texto de vez de sempre (telas.md 3.19 "Online"/"Multijogador").
     */
    val boostersAwaitingSeat: Seat? = null,
    /** §3.6 reação em tela cheia do palpite resolvido, só do próprio jogador (`role == YOU`), até o toque fechar. */
    val dealGuessReveal: DealGuessReveal? = null,
    /** §3.6 reação reduzida (pílula) por assento (índice), enquanto estiver à vista. */
    val dealGuessPills: Map<Int, DealGuessPillReaction> = emptyMap(),
    /** §3.6 reação pequena do palpite do parceiro (duplas), ancorada no canto da mesa; some sozinha. */
    val partnerGuessReaction: PartnerGuessReaction? = null,
) {
    val isHumanTurn: Boolean get() = snapshot.isHumanTurn && !ownSwapPending && !turnHeld
    private val legal: List<Action> get() = if (ownSwapPending || turnHeld) emptyList() else snapshot.humanLegal
    /** §3.6 fases de palpite da distribuição certeira (não são "jogar", "comprar" etc.: nenhum outro controle vale). */
    private val dealGuessPhase: Boolean get() = snapshot.view.phase == Phase.DEAL_GUESS_HAND || snapshot.view.phase == Phase.DEAL_GUESS_MORTO
    val awaitingDraw: Boolean get() = isHumanTurn && snapshot.view.phase == Phase.AWAITING_DRAW
    val playing: Boolean get() = isHumanTurn && snapshot.view.phase == Phase.PLAYING
    val canDraw: Boolean get() = Action.DrawFromStock in legal

    /**
     * §3.6 é a vez do humano palpitar e o modal deve aparecer: `null` fora dessa vez, enquanto a reação em tela
     * cheia do palpite anterior (deste próprio humano) ainda não fechou, ou durante o "Prepare-se!" (que cobriria o
     * modal). `dealerSeat`/`mortoDealerSeat` são sempre assentos diferentes (inclusive em individual, §3.6): o
     * humano só palpita uma das duas etapas por partida, nunca as duas. O monte some da mesa no mesmo intervalo
     * (fora daqui: ver [isDealGuessing]).
     */
    val dealGuessPrompt: DealGuessPrompt?
        get() {
            if (!isHumanTurn || dealGuessReveal != null || opening) return null
            val view = snapshot.view
            return when (view.phase) {
                Phase.DEAL_GUESS_HAND -> DealGuessPrompt(
                    DealGuessStage.HAND,
                    com.gtranca.engine.model.dealGuessHandRange(view.mode),
                    com.gtranca.engine.model.dealGuessHandTarget(view.mode, view.rules),
                )
                Phase.DEAL_GUESS_MORTO -> {
                    // Guarda igual à do motor (`RoundEngine.guessDealRange`: `guess.hand ?: fail(NOT_GUESS_PHASE)`):
                    // só mostra o modal do morto depois do palpite da mão resolvido de verdade.
                    view.dealGuess?.hand ?: return null
                    DealGuessPrompt(
                        DealGuessStage.MORTO,
                        com.gtranca.engine.model.dealGuessMortoRange(),
                        com.gtranca.engine.model.dealGuessMortoTarget(view.rules),
                    )
                }
                else -> null
            }
        }

    /**
     * §3.6 o monte fica escondido (em qualquer lugar da mesa que o desenha) enquanto o palpite não termina. O
     * palpite do morto já resolve o motor para [Phase.AWAITING_DRAW] (nenhuma carta troca de lugar com o palpite em
     * si, §3.6): o monte continua escondido até o toque fechar a reação (`dealGuessReveal`), para a distribuição
     * (escondida atrás da reação em tela cheia) nunca aparecer antes dela.
     */
    val isDealGuessing: Boolean get() = dealGuessPhase || dealGuessReveal != null

    /** §5.3/§5.5 só com algum plano de pegar o lixo em `legalActions`. */
    val canTakeDiscardPile: Boolean get() = legal.any { it is Action.TakeDiscardPile }
    val canDecline: Boolean get() = Action.DeclineDraw in legal
    /** "Baixar" só quando a seleção forma conjunto novo ou completa um conjunto da mesa (em `legalActions`). */
    val canMeld: Boolean get() = playing && HumanTurnResolver.canMeld(legal, selected)

    /** Jogos do lado que recebem todas as cartas selecionadas de uma vez (destacados na mesa). */
    val addableMelds: Set<MeldId> get() = if (playing) HumanTurnResolver.addTargets(legal, selected) else emptySet()

    /** As cartas selecionadas formam um conjunto novo: a mesa mostra o conjunto vazio. */
    val canCreateNew: Boolean get() = playing && HumanTurnResolver.canCreateNew(legal, selected)

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
    /** §17.3 boosters do jogo (escolhidos e debitados no início, valem até o fim); no jogo retomado vale o do jogo salvo. */
    initialBoosters: List<String> = emptyList(),
    /**
     * §17.3 inventário de boosters: o seletor é só no início do jogo (antes de o jogo ser criado); as partidas seguintes
     * seguem com os mesmos. `null` = sem boosters (testes e mesa multijogador).
     */
    val boosterInventory: com.gtranca.data.InventoryRepository? = null,
    /** TEMPORÁRIO (pedido do usuário, 2026-11-14, ajustado 14/11/2026): ver `GameController.forcePartnerDeals` (§3.6, testes). */
    private val forcePartnerDeals: Boolean = false,
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
        val tableNow = table
        writes.enqueue { store.save(savedGameOf(gameId, playedConfig, gameSeed, snapshot, drawnForName, robots, tableNow)) }
    }

    /** O jogo é conduzido por este aparelho (contra robôs), não por uma mesa multijogador. */
    private val playsLocally: Boolean = externalDriver == null

    /** §17.3 os boosters do jogador deste aparelho: os do assento dele em [table]. */
    private val boosters: List<String> get() = seatState.boosters.filter { config.skillBoosters || !com.gtranca.engine.BoosterCatalog.isSkill(it) } // §17.9

    /**
     * §17.4 o estado dos boosters do assento deste aparelho: na mesa multijogador o que o anfitrião guarda (e só vale na partida em
     * que o seletor foi confirmado); contra robôs, o de [table].
     */
    private val seatState: com.gtranca.engine.SeatBoosters
        get() = externalSeat?.let { flow -> flow.value.takeIf { it.confirmedFor(local.value.snapshot.roundNumber) } ?: com.gtranca.engine.SeatBoosters() }
            ?: table.of(humanSeat.index)

    private data class FlightBatch(val flights: List<CardFlight>, val flashes: List<CanastaFlash>)

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
        /**
         * telas.md 3.8.1 R1/R2: voos de jogadas que chegaram enquanto os da anterior ainda não pousaram; entram em [flights]
         * um trecho por vez, quando o anterior pousa. Enquanto houver fila, [flights] não está vazio.
         */
        val queued: List<FlightBatch> = emptyList(),
        /** Um trecho de voos está no ar (a fila espera o pouso dele). */
        val batchBusy: Boolean = false,
        /** Contador dos trechos liberados e o tempo até o último voo do atual pousar (para o cronômetro da fila). */
        val batchSeq: Long = 0,
        val batchLanding: Long = 0,
        /** Voos da distribuição ainda no ar: as trocas do início da vez só são encenadas depois deles. */
        val dealIds: Set<Long> = emptySet(),
        /** §3.5 a tela avisou que a distribuição terminou (ou nada foi animado): aviso e voos das trocas já podem começar. */
        val dealDone: Boolean = true,
        /** A 1ª troca depois da distribuição vem logo depois dela (pausa curta, sem o aviso longo parado). */
        val afterDeal: Boolean = false,
        /** Camada "Prepare-se!" sobre a mesa: 1,5 s antes da distribuição da 1ª partida (os voos dela esperam). */
        val opening: Boolean = false,
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
        /** Jogo do próprio lado indicado (antes de comprar) para receber o topo do lixo. */
        val targetMeld: MeldId? = null,
        val takeMistake: Resolution.TakeMistake? = null,
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
        /** Mesa multijogador: a partida que espera o seletor deste aparelho. */
        val pendingBoosterRound: Int = 0,
        /** §17.8 reações dos outros assentos à vista: assento → (reação, id do envio). */
        val seatReactions: Map<Int, Pair<com.gtranca.engine.Reaction, Long>> = emptyMap(),
        /** Muda quando o anfitrião atualiza os boosters do assento (a tela refaz a conta). */
        val boostersTick: Int = 0,
        /** §17.3/§18.3 assentos (índice) que ainda não confirmaram o próprio seletor de boosters do jogo. */
        val choosingBoosters: Set<Int> = emptySet(),
        val ownReaction: com.gtranca.engine.Reaction? = null,
        val ownReactionId: Long = 0,
        val lastReaction: ReactionEvent? = null,
        /** §3.6 reação em tela cheia do palpite resolvido, até o toque ([onDealGuessRevealDismissed]) reconhecer o motor. */
        val dealGuessReveal: DealGuessReveal? = null,
        /** §3.6 reação reduzida (pílula) por assento (índice), enquanto estiver à vista. */
        val dealGuessPills: Map<Int, DealGuessPillReaction> = emptyMap(),
        /** §3.6 reação pequena do palpite do parceiro (duplas), ancorada no canto da mesa; some sozinha. */
        val partnerGuessReaction: PartnerGuessReaction? = null,
    )

    private val human = HumanPlayer()
    private val humanSeat = Seat(0)

    /**
     * §17.4 os boosters de cada assento da mesa na partida em andamento e as reações já usadas. Ficam **no assento** (é o que o
     * jogo salvo guarda); lido pela gravação do jogo, fora da thread principal.
     */
    @Volatile
    private var table: com.gtranca.engine.TableBoosters = restored?.tableBoosters?.takeIf { it.bySeat.isNotEmpty() }
        ?: com.gtranca.engine.TableBoosters.single(humanSeat.index, initialBoosters)

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
            forcePartnerDeals = forcePartnerDeals,
        ),
        human,
    )
    private val animationIds = AtomicLong(0)

    /** §17.4 mesa multijogador: o estado dos boosters do assento deste aparelho, vindo do anfitrião. */
    private val externalSeat: StateFlow<com.gtranca.engine.SeatBoosters>? = controller.seatBoosters

    /** Mesa multijogador: a partida cujo seletor já foi confirmado aqui (o anfitrião ainda pode demorar a ecoar). */
    @Volatile
    private var confirmedBoosterRound = 0

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
                opening = restored == null && !continuing && initial.stage == Stage.PLAYING && initial.roundNumber == 1,
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
        if (local.value.opening) {
            viewModelScope.launch {
                delay(com.gtranca.net.PREPARE_MILLIS)
                local.update { it.copy(opening = false) }
            }
        }
        viewModelScope.launch {
            controller.state.collect { snapshot ->
                val beforeGuess = local.value.snapshot
                local.update { absorb(it, snapshot, it.snapshot) }
                handleDealGuessTransition(beforeGuess, snapshot)
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
        // §17.3 mesa multijogador: o seletor aparece quando o condutor da mesa espera os boosters deste aparelho.
        if (!playsLocally) {
            viewModelScope.launch {
                controller.boosterRound.collect { round ->
                    local.update { state ->
                        when {
                            round == null -> state.copy(selectorPending = false)
                            round != confirmedBoosterRound -> state.copy(selectorPending = true, pendingBoosterRound = round)
                            else -> state
                        }
                    }
                }
            }
            externalSeat?.let { flow -> viewModelScope.launch { flow.collect { local.update { it.copy(boostersTick = it.boostersTick + 1) } } } }
            viewModelScope.launch { controller.seatReactions.collect(::showSeatReaction) }
            // §17.3/§18.3 "Aguardando <nome> escolher os boosters" quando a vez chega a um assento que ainda não confirmou.
            viewModelScope.launch { controller.choosingBoosters.collect { set -> local.update { it.copy(choosingBoosters = set) } } }
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
        // telas.md 3.8.1 R1/R2: o trecho de voos seguinte só entra no ar quando o anterior pousou (por tempo, como settleAfter).
        viewModelScope.launch {
            local.map { it.batchSeq }.distinctUntilChanged().collectLatest { seq ->
                if (seq > 0) {
                    delay(local.value.batchLanding.coerceAtLeast(0))
                    local.update(::releaseNext)
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
        return HandOff(playedConfig.copy(rules = snapshot.match.rules), gameSeed, snapshot, robots, table)
    }

    /** A troca é do próprio humano: encenação com o botão "Baixar 3 vermelho". */
    private fun isOwn(notice: RedThreeNotice, snapshot: GameSnapshot): Boolean = notice.seat == snapshot.viewerSeat

    /**
     * §3.6 [before] e [after] são snapshots consecutivos: se algum palpite (mão e/ou morto) resolveu entre eles
     * (`dealGuess.hand`/`.morto` passou de `null` a não-`null`), aciona a reação dele. Comparar contra [before] (e não
     * contra algum "já visto" guardado em [Local]) garante que um jogo retomado no meio da partida, já com o palpite
     * resolvido antes de existir esta tela, nunca reencena a reação (ver `absorb`, mesma ideia das trocas de 3 vermelho).
     */
    private fun handleDealGuessTransition(before: GameSnapshot, after: GameSnapshot) {
        if (before.roundNumber != after.roundNumber) return
        val oldGuess = before.view.dealGuess
        val newGuess = after.view.dealGuess ?: return
        val hand = newGuess.hand
        if (oldGuess?.hand == null && hand != null) reactToGuess(DealGuessStage.HAND, newGuess.dealerSeat, hand, after)
        val morto = newGuess.morto
        if (oldGuess?.morto == null && morto != null) reactToGuess(DealGuessStage.MORTO, newGuess.mortoDealerSeat, morto, after)
    }

    /**
     * §3.6 reage ao palpite de [seat] resolvido ([guess] vem só do motor, nunca calculado aqui): a pílula (telas.md
     * 3.8.2 `deal-guess-pill-burst`/`-shake`) sempre aparece, em qualquer aparelho. A reação em tela cheia
     * ([DealGuessReveal], bloqueante, só fecha no toque) é exclusiva do próprio jogador (`role == YOU`). O parceiro
     * (duplas, `role == PARTNER`) ganha uma reação pequena, não bloqueante ([PartnerGuessReaction]): o motor já é
     * liberado na hora, como para qualquer outro assento — ela é só cosmética, some sozinha.
     */
    private fun reactToGuess(stage: DealGuessStage, seat: Seat, guess: Guess, snapshot: GameSnapshot) {
        val id = animationIds.incrementAndGet()
        local.update { it.copy(dealGuessPills = it.dealGuessPills + (seat.index to DealGuessPillReaction(guess.correct, id))) }
        viewModelScope.launch {
            delay(DEAL_GUESS_PILL_MILLIS)
            local.update { state -> if (state.dealGuessPills[seat.index]?.id == id) state.copy(dealGuessPills = state.dealGuessPills - seat.index) else state }
        }
        val role = com.gtranca.game.SeatRole.of(snapshot.view.mode, seat, snapshot.viewerSeat)
        when (role) {
            com.gtranca.game.SeatRole.YOU -> {
                // §3.6 (ajuste de 15/11/2026, toque único): `guess.width` já é o número que o jogador tocou de
                // verdade (o motor fixa `start` em 1, `dealGuessAction`); não precisa mais de um cache do toque bruto.
                local.update {
                    it.copy(
                        dealGuessReveal = DealGuessReveal(
                            stage, partner = false, correct = guess.correct, width = guess.width,
                            roundNumber = snapshot.roundNumber, id = id,
                        ),
                    )
                }
            }
            com.gtranca.game.SeatRole.PARTNER -> {
                // §3.6 pedido do usuário (14/11/2026): o cartão mostra o alvo público da regra junto da largura que
                // o parceiro realmente escolheu — mesmo cálculo de [DealGuessPrompt.target] (`dealGuessPrompt`, acima).
                val target = when (stage) {
                    DealGuessStage.HAND -> com.gtranca.engine.model.dealGuessHandTarget(snapshot.view.mode, snapshot.view.rules)
                    DealGuessStage.MORTO -> com.gtranca.engine.model.dealGuessMortoTarget(snapshot.view.rules)
                }
                local.update { it.copy(partnerGuessReaction = PartnerGuessReaction(guess.correct, stage, id, target, guess.width)) }
                controller.guessRevealDone(snapshot.roundNumber, stage == DealGuessStage.HAND)
                viewModelScope.launch {
                    delay(PARTNER_GUESS_REACTION_MILLIS)
                    local.update { state -> if (state.partnerGuessReaction?.id == id) state.copy(partnerGuessReaction = null) else state }
                }
            }
            else -> controller.guessRevealDone(snapshot.roundNumber, stage == DealGuessStage.HAND)
        }
    }

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
        // §3.6 com a distribuição certeira ligada, a partida nova começa numa fase de palpite (a mão do motor já está
        // completa desde o início: o palpite não muda cartas, §3.6) — a animação da distribuição só deve começar depois
        // dos dois palpites resolvidos, não no 1º snapshot da partida. `old.view.phase` nas fases de palpite identifica
        // exatamente essa transição (sai da última fase de palpite para `AWAITING_DRAW`); sem a regra, o 1º snapshot já
        // chega em `AWAITING_DRAW`, e o comportamento é o de sempre.
        val dealingGuessPhases = snapshot.view.phase == Phase.DEAL_GUESS_HAND || snapshot.view.phase == Phase.DEAL_GUESS_MORTO
        val leavingGuessPhases = old.view.phase == Phase.DEAL_GUESS_HAND || old.view.phase == Phase.DEAL_GUESS_MORTO
        val dealing = effectiveMillis(state) > 0 && snapshot.stage == Stage.PLAYING && !dealingGuessPhases &&
            (previous == null || !sameRound || leavingGuessPhases)
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
            TableAnimations.derive(old, snapshot, hidden.values.flatten().toSet(), goOut = snapshot.view.phase == Phase.FINISHED, durationMillis = animationMillis, nextId = animationIds::incrementAndGet)
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
            // Sorteado a partir da semente do jogo e do número da partida (não do Random global): reproduzível, e as
            // duas instâncias de um mesmo teste com a mesma semente comparam o mesmo estilo.
            val dealStyleOptions = TableAnimations.DealStyle.entries
            val dealStyle = dealStyleOptions[Random(gameSeed xor snapshot.roundNumber.toLong()).nextInt(dealStyleOptions.size)]
            TableAnimations.deal(snapshot, dealt, animationIds::incrementAndGet, durationMillis = animationMillis, style = dealStyle)
                .map { it.scaled(state.animationScale) }
                .map { if (state.opening) it.copy(delayMillis = it.delayMillis + com.gtranca.net.PREPARE_MILLIS) else it }
        } else {
            emptyList()
        }
        // R1/R2: com um trecho no ar, as jogadas novas esperam na fila; sem voos novos não há o que ordenar.
        val queueIt = keepFlights && newFlights.isNotEmpty() && state.batchBusy
        val startNow = keepFlights && newFlights.isNotEmpty() && !state.batchBusy
        val endStep = if (snapshot.stage == Stage.PLAYING || old.history.size != snapshot.history.size) 0 else state.endStep
        val playing = snapshot.stage == Stage.PLAYING
        if (!playing) newCards = emptySet()
        return state.copy(
            snapshot = snapshot,
            selected = state.selected.filter { it in hand },
            newCards = newCards,
            swaps = if (playing) swaps else emptyList(),
            mortoSwaps = if (playing) mortoSwaps.filterKeys { id -> swaps.any { it.id == id } } else emptyMap(),
            flights = if (keepFlights) state.flights + (if (queueIt) emptyList() else newFlights) + dealFlights else dealFlights,
            queued = if (keepFlights && queueIt) state.queued + FlightBatch(newFlights, newFlashes) else if (keepFlights) state.queued else emptyList(),
            batchBusy = keepFlights && (state.batchBusy || startNow),
            batchSeq = if (startNow) state.batchSeq + 1 else state.batchSeq,
            batchLanding = if (startNow) landingMillis(newFlights, state) else state.batchLanding,
            dealIds = (if (playing && sameRound) state.dealIds else emptySet()) + dealFlights.map { it.id },
            // §3.6 BUG (relatado 2026-11-14): sem `leavingGuessPhases` aqui, o 1º snapshot da partida (ainda em fase
            // de palpite, `dealing=false` porque não há nada a animar enquanto se palpita) fixava `dealDone=true` e
            // nunca mais era recalculado dentro da mesma partida (só `previous==null || !sameRound` recalculava) —
            // mesmo na transição real de saída dos palpites para a 1ª jogada, quando [dealing] already fica `true` e
            // a distribuição de verdade merecia ser esperada como em qualquer outra partida.
            dealDone = if (previous == null || !sameRound || leavingGuessPhases) !dealing else state.dealDone,
            afterDeal = if (dealing) fresh.isNotEmpty() else state.afterDeal,
            flashes = if (keepFlights) state.flashes + (if (queueIt) emptyList() else newFlashes) else emptyList(),
            hidden = if (playing) hidden.filterKeys { id -> swaps.any { it.id == id } } else emptyMap(),
            delivers = if (playing) delivers.filterKeys { id -> swaps.any { it.id == id } } else emptyMap(),
            planChoice = state.planChoice.takeIf { snapshot.isHumanTurn },
            targetMeld = state.targetMeld?.takeIf {
                snapshot.isHumanTurn && snapshot.view.phase == Phase.AWAITING_DRAW && snapshot.view.tables[snapshot.view.side.index].meld(it) != null
            },
            takeMistake = state.takeMistake.takeIf { snapshot.isHumanTurn && snapshot.view.phase == Phase.AWAITING_DRAW },
            meldChoice = state.meldChoice.takeIf { snapshot.isHumanTurn },
            confirmDecline = state.confirmDecline && snapshot.isHumanTurn,
            confirmResign = state.confirmResign && snapshot.stage == old.stage && sameRound,
            endStep = endStep,
        )
    }

    /** Tira o próximo trecho da fila para o ar (ou marca que nada está no ar). */
    private fun releaseNext(state: Local): Local {
        val next = state.queued.firstOrNull() ?: return state.copy(batchBusy = false)
        return state.copy(
            queued = state.queued.drop(1),
            flights = state.flights + next.flights,
            flashes = state.flashes + next.flashes,
            batchSeq = state.batchSeq + 1,
            batchLanding = landingMillis(next.flights, state),
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
            // §17.6 o realce das cartas jogáveis é do b1 (Green-LED): sem ele nada é calculado nem mostrado.
            highlighted = if (com.gtranca.engine.BoosterCatalog.GREEN_LED in boosters) HumanTurnResolver.highlightedCards(hand, legal, state.selected) else emptySet(),
            newCards = state.newCards - hidden,
            sort = state.sort,
            redThrees = snapshot.view.redThrees.map { side -> side.filter { it !in staged } },
            reveal = reveal,
            ownSwapPending = ownSwapPending,
            banner = head?.takeIf { state.dealDone && reveal == null && !isOwn(it, snapshot) && state.flights.none { f -> f.id !in state.dealIds } && state.settling <= 0 },
            swapNoticeMillis = if (state.afterDeal) minOf(swapAnimationMillis, DEAL_SWAP_PAUSE_MILLIS) else (swapAnimationMillis * state.speed).toLong(),
            planChoice = state.planChoice,
            targetMeld = state.targetMeld,
            takeMistake = state.takeMistake,
            meldChoice = state.meldChoice,
            confirmDecline = state.confirmDecline,
            confirmResign = state.confirmResign,
            message = state.message,
            // A última jogada da partida (descarte ou baixa de quem bateu) pousa na mesa antes do anúncio aparecer.
            endScreen = endScreen(snapshot, state.endStep).takeUnless { state.endStep == 0 && !snapshot.resigned && (state.flights.isNotEmpty() || state.queued.isNotEmpty()) },
            flights = state.flights,
            queuedFlights = state.queued.flatMap { it.flights },
            canastaFlashes = state.flashes,
            animationMillis = effectiveMillis(state),
            personas = externalPersonas?.value ?: personas,
            turnHeld = held,
            remoteTurns = multiplayer,
            canHideHand = multiplayer,
            handHidden = multiplayer && state.handHidden,
            boosters = boosters,
            boosterSelectorPending = state.selectorPending,
            reactionsEnabled = com.gtranca.engine.BoosterCatalog.REACTIONS in boosters,
            reactionsLeft = seatState.reactionsLeft,
            seatReactions = state.seatReactions.mapValues { it.value.first },
            lastReaction = state.lastReaction,
            boosterSelectorRound = state.pendingBoosterRound,
            boostersAwaitingSeat = snapshot.view.currentSeat.takeIf { it.index in state.choosingBoosters && it != snapshot.viewerSeat },
            ownReaction = state.ownReaction,
            ownReactionId = state.ownReactionId,
            opponentFits = if (com.gtranca.engine.BoosterCatalog.RED_LED in boosters && !ownSwapPending && !held) com.gtranca.engine.RedLed.marks(snapshot.view) else emptyMap(),
            dealGuessReveal = state.dealGuessReveal,
            dealGuessPills = state.dealGuessPills,
            partnerGuessReaction = state.partnerGuessReaction,
            dealPending = !state.dealDone,
            dealFlightIds = state.dealIds,
            opening = state.opening,
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

    /**
     * §3.6 (ajuste de 15/11/2026, toque único) o jogador tocou a tela com o indicador no número [number] (o
     * estágio do indicador naquele instante, já dentro da faixa fixa do papel/modo, §3.8.2 do telas-app.md). Como
     * em qualquer outra jogada, a tela nunca decide sozinha se acertou nem monta a ação por conta própria: escolhe,
     * em `legalActions`, a ação que [com.gtranca.engine.model.dealGuessAction] constrói para esse número — se não
     * estiver lá (fora da faixa, toque atrasado etc.), a jogada é ignorada, como qualquer `onX` desta interface.
     */
    override fun onGuessDealNumber(number: Int) {
        val snapshot = controller.state.value
        if (!snapshot.isHumanTurn || !controller.awaitingHuman() || turnHeld(local.value)) return
        val action = snapshot.humanLegal.firstOrNull { it == com.gtranca.engine.model.dealGuessAction(number) } ?: return
        submit(action, snapshot.humanRequestId)
    }

    /** §3.6 toque que fecha a reação em tela cheia do palpite: libera o próximo pedido do motor (§3.6, `GameController`). */
    override fun onDealGuessRevealDismissed() {
        val reveal = local.value.dealGuessReveal ?: return
        local.update { it.copy(dealGuessReveal = null) }
        controller.guessRevealDone(reveal.roundNumber, reveal.stage == DealGuessStage.HAND)
    }

    /** Toque no lixo: antes de comprar, pegar o lixo; depois, descartar a carta selecionada (§4.3, §8). */
    override fun onDiscardPileClick() {
        when (controller.state.value.view.phase) {
            Phase.AWAITING_DRAW -> play(PlayIntent.TakeDiscardPile)
            Phase.PLAYING -> play(PlayIntent.Discard)
            Phase.FINISHED -> Unit
            // §3.6 o lixo nem aparece durante o palpite (o monte é que fica escondido, mas o toque no lixo não faz nada aqui).
            Phase.DEAL_GUESS_HAND, Phase.DEAL_GUESS_MORTO -> Unit
        }
    }

    override fun onCreateMeld() = play(PlayIntent.CreateMeld)
    override fun onNewMeld() = play(PlayIntent.NewMeld)

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

    /** Antes de comprar, tocar num jogo do próprio lado o indica (ou desmarca) como destino do topo do lixo (§5.1). */
    override fun onMeldTarget(meldId: MeldId) = local.update { state ->
        state.copy(targetMeld = if (state.targetMeld == meldId) null else meldId, message = null)
    }

    /** "Ver opções" no modal do engano: todos os planos legais, resolvidos no mesmo pedido. */
    override fun onTakeMistakeOptions() = local.update { state ->
        val mistake = state.takeMistake ?: return@update state
        state.copy(takeMistake = null, planChoice = PlanRanking.rank(mistake.options, state.snapshot.view))
    }

    override fun onDismissTakeMistake() = local.update { it.copy(takeMistake = null) }

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
        if (flights.isEmpty()) 0 else flights.maxOf { it.delayMillis + (effectiveMillis(state) * it.durationFactor).toLong() }

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
        // §17.3 (30/10/2026) os boosters valem o jogo todo: a partida nova não tem seletor; só as reações recomeçam (§17.8).
        table = table.newRound()
        local.update { it.copy(ownReaction = null) }
        controller.continueToNextRound()
    }

    private val reactionIds = AtomicLong(0)

    /**
     * §17.8 b3: envia uma reação (gasta 1 dos 3 usos da partida). Sem o booster ou sem usos, nada acontece. A reação fica à
     * vista por [REACTION_SHOW_MILLIS] (*tempo provisório, revisar com produto*).
     */
    override fun onReaction(reaction: com.gtranca.engine.Reaction) {
        if (!playsLocally) {
            // §17.8 mesa multijogador: o anfitrião valida e repassa a todos (a reação volta por [showSeatReaction]).
            if (seatState.reactionsLeft > 0) controller.sendReaction(local.value.snapshot.roundNumber, reaction)
            return
        }
        val used = table.useReaction(humanSeat.index) ?: return
        table = used
        val id = reactionIds.incrementAndGet()
        local.update { it.copy(ownReaction = reaction, ownReactionId = id, lastReaction = ReactionEvent(humanSeat, reaction, id)) }
        // §17.8 os usos que sobraram ficam no jogo salvo (o "Continuar" os retoma como estavam).
        (controller as? LocalTableDriver)?.let { enqueueSave(it.controller.saveSnapshot()) }
        viewModelScope.launch {
            delay(REACTION_SHOW_MILLIS)
            local.update { if (it.ownReactionId == id) it.copy(ownReaction = null, lastReaction = it.lastReaction?.takeIf { e -> e.id != id }) else it }
        }
    }

    /** §17.8 uma reação chegou da mesa: a do próprio assento aparece junto da mão, a dos outros junto da pílula. */
    private fun showSeatReaction(shown: com.gtranca.game.SeatReaction) {
        val id = reactionIds.incrementAndGet()
        if (shown.seat == local.value.snapshot.viewerSeat.index) {
            local.update { it.copy(ownReaction = shown.reaction, ownReactionId = id, lastReaction = ReactionEvent(Seat(shown.seat), shown.reaction, id)) }
        } else {
            local.update { it.copy(seatReactions = it.seatReactions + (shown.seat to (shown.reaction to id)), lastReaction = ReactionEvent(Seat(shown.seat), shown.reaction, id)) }
        }
        viewModelScope.launch {
            delay(REACTION_SHOW_MILLIS)
            local.update { state ->
                if (shown.seat == state.snapshot.viewerSeat.index) {
                    if (state.ownReactionId == id) state.copy(ownReaction = null, lastReaction = state.lastReaction?.takeIf { e -> e.id != id }) else state
                } else if (state.seatReactions[shown.seat]?.second == id) state.copy(seatReactions = state.seatReactions - shown.seat, lastReaction = state.lastReaction?.takeIf { e -> e.id != id }) else state
            }
        }
    }

    /** §17.3 Seguir no seletor: os boosters da partida ([chosen], já debitados) valem e a próxima partida começa. */
    fun onBoostersConfirmed(chosen: List<String>) {
        if (!local.value.selectorPending) return
        if (!playsLocally) {
            // §17.3 mesa multijogador: registra no assento, no anfitrião; a distribuição aparece depois (o condutor libera).
            val round = local.value.pendingBoosterRound
            confirmedBoosterRound = round
            controller.confirmBoosters(round, chosen)
            local.update { it.copy(selectorPending = false) }
            return
        }
        table = table.confirm(humanSeat.index, chosen, local.value.snapshot.roundNumber + 1)
        local.update { it.copy(selectorPending = false, ownReaction = null) }
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
        val resolution = HumanTurnResolver.resolve(intent, selected, snapshot.humanLegal, local.value.targetMeld) { controller.explain(it, requestId) }
        if (resolution !is Resolution.Play && controller.state.value !== snapshot) return
        when (resolution) {
            is Resolution.Play -> submit(resolution.action, requestId)
            is Resolution.ChoosePlan ->
                local.update { it.copy(planChoice = PlanRanking.rank(resolution.options, snapshot.view), planRequestId = requestId, message = null) }
            is Resolution.TakeMistake -> local.update { it.copy(takeMistake = resolution, planRequestId = requestId, message = null) }
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
            queued = if (scale <= 0f) emptyList() else queued,
            batchBusy = batchBusy && scale > 0f,
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

    /** Tempo da animação de distribuição (pior caso entre os dois estilos em avaliação, [TableAnimations.worstCaseDealMillis]). */
    private fun dealAnimationMillis(config: GameConfig): Long = TableAnimations.worstCaseDealMillis(
        config.mode.seatCount, config.rules.cardsPerHand, config.rules.mortoCount, config.rules.cardsPerMorto, animationMillis,
    ) + EXPIRY_MARGIN_MILLIS

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

        /** §17.8 quanto tempo a reação enviada fica à vista (*provisório, revisar com produto*). */
        const val REACTION_SHOW_MILLIS: Long = 3_000

        /** §3.6 quanto tempo a pílula de quem palpitou mostra a reação reduzida (confete/tremor). */
        const val DEAL_GUESS_PILL_MILLIS: Long = 1_600

        /**
         * §3.6 quanto tempo este estado fica preenchido (`GameUiState.partnerGuessReaction` não-nulo), 14/11/2026:
         * 3 s, o tempo "parado" pedido pelo usuário (telas-app.md 3.8.2). A UI (`PartnerGuessReactionOverlay`)
         * conduz a própria animação (entrada, repouso e saída deslizando para fora da mesa) a partir da última
         * reação não-nula recebida, então ela continua visível mesmo depois deste `delay` zerar o estado aqui —
         * nunca bloqueia nada (o motor já foi liberado na hora, antes deste `delay` sequer começar).
         */
        const val PARTNER_GUESS_REACTION_MILLIS: Long = 3_000

        /** Fator de duração da velocidade "lenta" (30% mais lenta). */
        const val SLOW_FACTOR: Float = 1.3f

        fun speedFor(slow: Boolean): Float = if (slow) SLOW_FACTOR else 1f
    }

    private fun submit(action: Action, requestId: Long) {
        if (controller.submit(action, requestId)) {
            local.update { it.copy(selected = emptyList(), targetMeld = null, newCards = emptySet(), message = null) }
        }
    }
}

/** §16.7 o jogo local que vira online: configuração jogada, semente, estado salvo e o personagem de cada assento de robô. */
data class HandOff(
    val config: GameConfig,
    val gameSeed: Long,
    val snapshot: SaveSnapshot,
    val robots: Map<Int, String>,
    /** §17.3 os boosters de cada assento (a partida em andamento já tem os escolhidos). */
    val boosters: com.gtranca.engine.TableBoosters = com.gtranca.engine.TableBoosters(),
)
