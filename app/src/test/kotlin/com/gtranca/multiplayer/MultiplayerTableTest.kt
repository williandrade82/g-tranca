package com.gtranca.multiplayer

import com.gtranca.ai.Difficulty
import com.gtranca.data.GameResult
import com.gtranca.engine.Action
import com.gtranca.engine.model.ActionError
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.game.GameConfig
import com.gtranca.game.Persona
import com.gtranca.game.Stage
import com.gtranca.net.MemoryNetwork
import com.gtranca.net.TableRules
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.Collections

/** §16 a mesa multijogador que as telas usam (sala, papéis, condutor da mesa e moedas), no transporte em memória. */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiplayerTableTest {

    private class FakeCoins : MpCoins {
        val debits: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val settled: MutableList<Pair<GameResult, Int>> = Collections.synchronizedList(mutableListOf())
        override suspend fun debit(stake: Int): Boolean = debits.add(stake)
        override suspend fun settle(gameId: String, rules: TableRules, result: GameResult, stake: Int, received: Int) {
            settled += result to received
        }
    }

    private class Setup(
        val host: MultiplayerTable,
        val guest: MultiplayerTable,
        val hostCoins: FakeCoins,
        val guestCoins: FakeCoins,
        val scope: CoroutineScope,
        val rules: TableRules,
        /** Nova mesa no mesmo transporte (outro jogo no mesmo celular). */
        val again: (host: Boolean) -> MultiplayerTable,
        /** Derruba a conexão do anfitrião (queda, §16.10). */
        val dropHost: () -> Unit = {},
    )

    private suspend fun TestScope.setup(
        seed: Long,
        targetScore: Int = 500,
        stake: Int = 50,
        robots: List<String> = listOf("jovem", "sobrio", "atleta"),
        hostPhoto: String? = null,
        guestPhoto: String? = null,
    ): Setup {
        val network = MemoryNetwork()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + Job())
        val config = GameConfig(GameMode.DUPLAS, Difficulty.FACIL, targetScore = targetScore, stake = stake)
        val rules = TableRules("Mesa da família", GameMode.DUPLAS, config.rules, targetScore, stake, Difficulty.FACIL.id, robots)
        val hostCoins = FakeCoins()
        val guestCoins = FakeCoins()
        val bots: (GameConfig) -> ((Seat, kotlin.random.Random) -> com.gtranca.ai.BotPlayer) = { { seat, _ -> testBot(seed, seat) } }
        val hostTransport = network.transport()
        val guestTransport = network.transport()
        val host = MultiplayerTable(hostTransport, scope, hostCoins, dispatcher, bots, newSeed = { seed })
        val guest = MultiplayerTable(guestTransport, scope, guestCoins, dispatcher, bots, newSeed = { seed })
        host.myPhoto = hostPhoto
        guest.myPhoto = guestPhoto
        host.host(rules, "Ana", "mesa-1")
        advanceUntilIdle()
        guest.join("mesa-1", "Beto")
        advanceUntilIdle()
        val again = { isHost: Boolean ->
            MultiplayerTable(if (isHost) hostTransport else guestTransport, scope, if (isHost) hostCoins else guestCoins, dispatcher, bots, newSeed = { seed })
        }
        return Setup(host, guest, hostCoins, guestCoins, scope, rules, again) { network.kill(hostTransport.id) }
    }

    @Test
    fun `um leitor por transporte - sair da sala e entrar de novo, e o anfitrião que joga duas vezes`() = runTest {
        val seed = 45L
        val t = setup(seed)
        // Convidado sai da sala e entra de novo pelo mesmo transporte: chega à sala e confirma.
        t.guest.leave()
        advanceUntilIdle()
        t.host.roster.value.size shouldBe 1
        val guest2 = t.again(false)
        guest2.join("mesa-1", "Beto")
        advanceUntilIdle()
        guest2.mySeat shouldBe Seat(1)
        guest2.confirmSeat()
        advanceUntilIdle()
        t.host.roster.value.first { it.id == guest2.myId }.confirmed shouldBe true
        // O anfitrião joga até o fim e publica outra mesa no mesmo transporte; o convidado entra nela.
        t.host.startGame()
        advanceUntilIdle()
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        val guestDriver = MultiplayerDriver.create(guest2, t.scope) { 0 }
        drive(t.scope, hostDriver, seed)
        drive(t.scope, guestDriver, seed)
        advanceUntilIdle()
        hostDriver.state.value.stage shouldBe Stage.GAME_OVER
        t.host.leave()
        guest2.leave()
        advanceUntilIdle()
        val host2 = t.again(true)
        host2.host(t.rules.copy(tableName = "Revanche"), "Ana", "mesa-2")
        advanceUntilIdle()
        val guest3 = t.again(false)
        guest3.join("mesa-2", "Beto")
        advanceUntilIdle()
        guest3.mySeat shouldBe Seat(1)
        host2.roster.value.size shouldBe 2
    }

    @Test
    fun `§16_13 quem sai das telas antes do premio chegar ainda recebe`() = runTest {
        val seed = 46L
        val t = setup(seed)
        t.guest.confirmSeat()
        advanceUntilIdle()
        t.host.startGame()
        advanceUntilIdle()
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        val guestDriver = MultiplayerDriver.create(t.guest, t.scope) { 0 }
        drive(t.scope, hostDriver, seed)
        drive(t.scope, guestDriver, seed)
        // Assim que a tela do convidado chega ao fim do jogo, ele toca "Voltar ao início".
        t.scope.launch { guestDriver.state.first { it.stage == Stage.GAME_OVER }; t.guest.exitScreens() }
        advanceUntilIdle()
        t.guestCoins.settled shouldHaveSize 1
        t.guest.role.value shouldBe null
    }

    @Test
    fun `§16_6 a tela do convidado - a vez chega pelo condutor e a compra vai ao anfitrião`() = runTest {
        val seed = 47L
        val t = setup(seed, targetScore = 3000)
        t.guest.confirmSeat()
        advanceUntilIdle()
        t.host.startGame()
        advanceUntilIdle()
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        val guestDriver = MultiplayerDriver.create(t.guest, t.scope) { 0 }
        kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val personas = t.guest.personas(Persona("Beto"), "VAGO") { it }
            val vm = com.gtranca.ui.game.GameViewModel(
                MultiplayerTable.configOf(t.rules),
                animationMillis = 0,
                swapAnimationMillis = 0,
                externalDriver = guestDriver,
                externalPersonas = personas,
                multiplayer = true,
            )
            drive(t.scope, hostDriver, seed)
            advanceUntilIdle()
            val ui = vm.uiState.value
            ui.snapshot.viewerSeat shouldBe Seat(1)
            ui.isHumanTurn shouldBe true
            ui.turnHeld shouldBe false
            ui.canDraw shouldBe true
            vm.onDraw()
            advanceUntilIdle()
            // O anfitrião aplicou a compra: a vista volta com a fase de jogar.
            vm.uiState.value.snapshot.view.phase shouldBe Phase.PLAYING
            vm.uiState.value.isHumanTurn shouldBe true
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    /** Joga o assento deste aparelho como o robô do assento jogaria, pela vista da tela, e avança as telas de resultado. */
    private fun drive(scope: CoroutineScope, driver: MultiplayerDriver, seed: Long, until: () -> Boolean = { true }) {
        // No escopo das sessões (trabalho em primeiro plano): o advanceUntilIdle não roda o backgroundScope sozinho.
        scope.launch {
            var continued = 0
            driver.state.collect { s ->
                if (!until()) return@collect
                if (s.stage == Stage.ROUND_OVER && continued < s.roundNumber) {
                    continued = s.roundNumber
                    driver.continueToNextRound()
                }
                if (s.humanLegal.isNotEmpty()) {
                    driver.submit(testBot(seed, s.viewerSeat).chooseAction(s.view, s.humanLegal), s.humanRequestId)
                }
            }
        }
    }

    @Test
    fun `§16_2 sala, inicio, jogo completo pela tela e moedas de cada aparelho`() = runTest {
        val seed = 41L
        val t = setup(seed)
        // §16.2 entra no primeiro assento livre e troca para outro vago antes de confirmar; nome em branco = "Jogador 1".
        t.guest.mySeat shouldBe Seat(1)
        t.guest.chooseSeat(Seat(2))
        t.guest.confirmSeat()
        t.host.rename(" ")
        advanceUntilIdle()
        t.host.roster.value.first { it.isHost }.name shouldBe "Jogador 1"
        t.guest.roster.value.first { it.id == t.guest.myId }.let { it.seat shouldBe Seat(2); it.confirmed shouldBe true }

        t.host.startGame()
        advanceUntilIdle()
        // §16.13 cada aparelho debita a aposta ao começar.
        t.hostCoins.debits shouldBe listOf(50)
        t.guestCoins.debits shouldBe listOf(50)

        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        val guestDriver = MultiplayerDriver.create(t.guest, t.scope) { 0 }
        guestDriver.state.value.viewerSeat shouldBe Seat(2)
        // §16.6 nomes da sala: o anfitrião, o próprio jogador com o perfil e os robôs escolhidos.
        val personas = t.guest.personas(Persona("Eu"), "VAGO") { "$it (fora)" }
        advanceUntilIdle()
        personas.value shouldHaveSize 4
        personas.value[0].firstName shouldBe "Jogador 1"
        personas.value[2].firstName shouldBe "Beto"
        personas.value[1].character.shouldNotBeNull()
        drive(t.scope, hostDriver, seed)
        drive(t.scope, guestDriver, seed)
        advanceUntilIdle()
        
        hostDriver.state.value.stage shouldBe Stage.GAME_OVER
        guestDriver.state.value.stage shouldBe Stage.GAME_OVER
        guestDriver.state.value.winner shouldBe hostDriver.state.value.winner
        // "Ver a mesa" no fim (convidado): a mesa final tem a última vez de cada assento, como no anfitrião (antes vinha
        // sem nenhuma lista e a pílula do assento fechava o app ao indexá-la).
        guestDriver.state.value.turnEvents.size shouldBe 4
        guestDriver.state.value.turnRedThrees.size shouldBe 4
        guestDriver.state.value.turnEvents.any { it.isNotEmpty() } shouldBe true
        // §16.13 cada um recebe o resultado uma vez; assentos 0 e 2 são parceiros: o mesmo resultado.
        t.hostCoins.settled shouldHaveSize 1
        t.guestCoins.settled shouldHaveSize 1
        t.hostCoins.settled.single().first shouldBe t.guestCoins.settled.single().first
        t.hostCoins.settled.single().first shouldNotBe GameResult.RESIGNATION
        t.guest.received.value.shouldNotBeNull()
    }

    @Test
    fun `§16_9 desistencia do convidado, assento VAGO, pausa do anfitrião e robo substituto`() = runTest {
        val seed = 42L
        val t = setup(seed)
        t.guest.confirmSeat()
        advanceUntilIdle()
        t.host.startGame()
        advanceUntilIdle()
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        val guestDriver = MultiplayerDriver.create(t.guest, t.scope) { 0 }
        guestDriver.resign()
        advanceUntilIdle()
        // §16.9 para quem desistiu, o jogo acabou (derrota por desistência); para os outros, "VAGO".
        guestDriver.state.value.resigned shouldBe true
        guestDriver.state.value.stage shouldBe Stage.GAME_OVER
        val personas = t.host.personas(Persona("Eu"), "VAGO") { "$it (fora)" }
        advanceUntilIdle()
        personas.value[1].firstName shouldBe "VAGO"
        drive(t.scope, hostDriver, seed)
        advanceUntilIdle()
        // A vez do assento VAGO pausa e o anfitrião decide.
        val host = (t.host.role.value as MpRole.Hosting).host!!
        host.decision.value.shouldNotBeNull().seat shouldBe Seat(1)
        host.replaceWithRobot()
        advanceUntilIdle()
        hostDriver.state.value.stage shouldBe Stage.GAME_OVER
        t.guest.exitScreens()
        t.guestCoins.settled shouldContain (GameResult.RESIGNATION to 0)
    }

    @Test
    fun `§16_10 passar o cargo - o convidado vira anfitrião, o antigo volta como convidado e o jogo segue até o fim`() = runTest {
        val seed = 44L
        val t = setup(seed)
        t.guest.confirmSeat()
        advanceUntilIdle()
        t.host.startGame()
        advanceUntilIdle()
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        val guestDriver = MultiplayerDriver.create(t.guest, t.scope) { 0 }
        t.host.passRole(t.guest.myId) shouldBe true
        advanceUntilIdle()
        // Os papéis trocaram sem trocar de tela (o mesmo condutor segue o papel novo).
        (t.guest.role.value is MpRole.Hosting) shouldBe true
        t.guest.tookOver.value shouldBe true
        // O menu do anfitrião segue o papel: quem recebeu a mesa tem as ações; quem passou, não.
        t.guest.hostControls.value.shouldNotBeNull()
        t.host.hostControls.value shouldBe null
        (t.host.role.value is MpRole.Guesting) shouldBe true
        t.host.mySeat shouldBe Seat(0)
        drive(t.scope, hostDriver, seed)
        drive(t.scope, guestDriver, seed)
        advanceUntilIdle()
        hostDriver.state.value.stage shouldBe Stage.GAME_OVER
        guestDriver.state.value.stage shouldBe Stage.GAME_OVER
        t.hostCoins.settled shouldHaveSize 1
        t.guestCoins.settled shouldHaveSize 1
    }

    @Test
    fun `§16_10 passar o cargo depois de o convidado jogar - a vez do novo anfitrião não fica presa ao pedido antigo`() = runTest {
        val seed = 50L
        val t = setup(seed, targetScore = 1000)
        t.guest.confirmSeat()
        advanceUntilIdle()
        t.host.startGame()
        advanceUntilIdle()
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        val guestDriver = MultiplayerDriver.create(t.guest, t.scope) { 0 }
        // O convidado joga algumas vezes antes da troca (tem pedido respondido com a numeração do anfitrião antigo).
        var turns = 0
        t.scope.launch { guestDriver.state.collect { if (it.humanLegal.isNotEmpty()) turns++ } }
        drive(t.scope, hostDriver, seed) { turns < 6 }
        drive(t.scope, guestDriver, seed) { turns < 6 }
        advanceUntilIdle()
        (turns >= 6) shouldBe true
        t.host.passRole(t.guest.myId) shouldBe true
        advanceUntilIdle()
        (t.guest.role.value is MpRole.Hosting) shouldBe true
        drive(t.scope, hostDriver, seed)
        drive(t.scope, guestDriver, seed)
        advanceUntilIdle()
        hostDriver.state.value.stage shouldBe Stage.GAME_OVER
        guestDriver.state.value.stage shouldBe Stage.GAME_OVER
    }

    @Test
    fun `§16_2 os robos sao os do anfitriao, iguais em todos os aparelhos e no assento certo`() = runTest {
        val seed = 48L
        // Ordem diferente da dos perfis: um sorteio local no convidado daria outros robôs.
        val t = setup(seed, robots = listOf("atleta", "alternativo", "sobrio"))
        t.guest.confirmSeat()
        advanceUntilIdle()
        t.host.startGame()
        advanceUntilIdle()
        val hostPersonas = t.host.personas(Persona("Ana"), "VAGO") { it }
        val guestPersonas = t.guest.personas(Persona("Beto"), "VAGO") { it }
        advanceUntilIdle()
        // Convidado no assento 1: os robôs ficam nos assentos 2 e 3, na ordem escolhida pelo anfitrião.
        listOf(2, 3).forEach { seat ->
            guestPersonas.value[seat] shouldBe hostPersonas.value[seat]
            guestPersonas.value[seat].character.shouldNotBeNull()
        }
        hostPersonas.value[2].character!!.profile shouldBe com.gtranca.game.BotProfile.ATLETA
        hostPersonas.value[3].character!!.profile shouldBe com.gtranca.game.BotProfile.ALTERNATIVO
        // Quem volta à mesa (reingresso com o jogo em andamento) recebe os mesmos robôs.
        val guestSession = (t.guest.role.value as MpRole.Guesting).session
        guestSession.robots.value shouldBe mapOf(2 to "atleta", 3 to "alternativo")
        (t.host.role.value as MpRole.Hosting).session.robots shouldBe mapOf(2 to "atleta", 3 to "alternativo")
    }

    @Test
    fun `§16_6 cada aparelho ve uma jogada por vez, em ordem, no seu ritmo`() = runTest {
        val seed = 49L
        val t = setup(seed, targetScore = 3000)
        t.guest.confirmSeat()
        advanceUntilIdle()
        t.host.startGame()
        advanceUntilIdle()
        val pace = 700L
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { pace }
        val guestDriver = MultiplayerDriver.create(t.guest, t.scope) { pace }
        val hostSeen = Collections.synchronizedList(mutableListOf<com.gtranca.game.GameSnapshot>())
        val guestSeen = Collections.synchronizedList(mutableListOf<com.gtranca.game.GameSnapshot>())
        val guestTimes = Collections.synchronizedList(mutableListOf<Long>())
        t.scope.launch { hostDriver.state.collect { hostSeen += it } }
        t.scope.launch { guestDriver.state.collect { guestSeen += it; guestTimes += testScheduler.currentTime } }
        // Só a 1ª partida: os dois jogam como o robô do assento até ela acabar.
        val firstRound = { hostDriver.state.value.roundNumber == 1 && guestDriver.state.value.roundNumber == 1 }
        drive(t.scope, hostDriver, seed, firstRound)
        drive(t.scope, guestDriver, seed, firstRound)
        advanceUntilIdle()
        guestSeen.any { it.stage == Stage.ROUND_OVER } shouldBe true
        listOf(hostSeen, guestSeen).forEach { seen ->
            seen.zipWithNext().filter { (a, b) -> a.roundNumber == b.roundNumber && a.stage == Stage.PLAYING }.forEach { (a, b) ->
                val events = com.gtranca.ui.game.TableAnimations.newEvents(a, b)
                (events.size <= 1) shouldBe true
            }
        }
        // As jogadas dos outros chegam com o intervalo do ritmo deste aparelho.
        guestSeen.indices.drop(1).filter { i ->
            val a = guestSeen[i - 1]
            val b = guestSeen[i]
            a.roundNumber == b.roundNumber && a.stage == Stage.PLAYING && b.stage == Stage.PLAYING &&
                com.gtranca.ui.game.TableAnimations.newEvents(a, b).any { it.seat != b.viewerSeat }
        }.forEach { i -> (guestTimes[i] - guestTimes[i - 1] >= pace) shouldBe true }
    }

    @Test
    fun `§16_2 iniciar com assento vago ou convidado sem confirmar pede confirmacao`() {
        fun p(id: Int, seat: Int, confirmed: Boolean, host: Boolean = false) =
            com.gtranca.net.PlayerInfo(id, "J$id", id, Seat(seat), isHost = host, confirmed = confirmed)
        val full = listOf(p(0, 0, true, host = true), p(1, 1, true), p(2, 2, true), p(3, 3, true))
        com.gtranca.ui.multiplayer.startWarning(GameMode.DUPLAS, full) shouldBe null
        com.gtranca.ui.multiplayer.startWarning(GameMode.DUPLAS, full.take(3)) shouldBe com.gtranca.ui.multiplayer.StartWarning(1, emptyList())
        com.gtranca.ui.multiplayer.startWarning(GameMode.DUPLAS, listOf(p(0, 0, true, host = true), p(1, 1, false))) shouldBe
            com.gtranca.ui.multiplayer.StartWarning(2, listOf("J1"))
    }

    @Test
    fun `§16_2 a foto do perfil chega uma vez aos outros aparelhos e a opcao desligada nao mostra`() = runTest {
        val hostJpeg = byteArrayOf(1, 2, 3)
        val guestJpeg = byteArrayOf(9, 8, 7, 6)
        val b64 = { b: ByteArray -> java.util.Base64.getEncoder().encodeToString(b) }
        val t = setup(51L, hostPhoto = b64(hostJpeg), guestPhoto = b64(guestJpeg))
        t.guest.confirmSeat()
        advanceUntilIdle()
        // Na sala: cada um recebe a foto do outro (só em memória).
        t.guest.photos.value[0]!!.toList() shouldBe hostJpeg.toList()
        t.host.photos.value[t.guest.myId]!!.toList() shouldBe guestJpeg.toList()
        t.host.startGame()
        advanceUntilIdle()
        val showing = t.guest.personas(Persona("Beto"), "VAGO") { it }
        val hiding = t.guest.personas(Persona("Beto"), "VAGO", kotlinx.coroutines.flow.flowOf(false)) { it }
        val hostSees = t.host.personas(Persona("Ana"), "VAGO") { it }
        advanceUntilIdle()
        showing.value[0].photoBytes!!.toList() shouldBe hostJpeg.toList()
        hiding.value[0].photoBytes shouldBe null
        hostSees.value[1].photoBytes!!.toList() shouldBe guestJpeg.toList()
        // Robôs continuam com os personagens (sem foto).
        showing.value[2].character.shouldNotBeNull()
        showing.value[2].photoBytes shouldBe null
    }

    @Test
    fun `§16_10 queda do anfitriao - o sucessor ganha as acoes de anfitriao`() = runTest {
        val t = setup(52L, targetScore = 3000)
        t.guest.confirmSeat()
        advanceUntilIdle()
        t.host.startGame()
        advanceUntilIdle()
        t.host.hostControls.value.shouldNotBeNull()
        t.guest.hostControls.value shouldBe null
        t.dropHost()
        advanceUntilIdle()
        (t.guest.role.value is MpRole.Hosting) shouldBe true
        t.guest.hostControls.value.shouldNotBeNull()
    }

    @Test
    fun `§16_2 sala - avatar de cada assento com a foto, a propria do perfil local`() {
        val me = com.gtranca.net.PlayerInfo(1, "Beto", 1, Seat(1))
        val other = com.gtranca.net.PlayerInfo(0, "Ana", 0, Seat(0), isHost = true)
        val jpeg = byteArrayOf(1, 2)
        val self = Persona("Você", photo = "/perfil.jpg")
        com.gtranca.ui.multiplayer.seatAvatar(me, 1, self, mapOf(0 to jpeg)).let { it.photo shouldBe "/perfil.jpg"; it.firstName shouldBe "Beto" }
        com.gtranca.ui.multiplayer.seatAvatar(other, 1, self, mapOf(0 to jpeg)).photoBytes!!.toList() shouldBe jpeg.toList()
        // Opção desligada (sem fotos recebidas): a inicial.
        com.gtranca.ui.multiplayer.seatAvatar(other, 1, self, emptyMap()).photoBytes shouldBe null
    }

    @Test
    fun `§16_2 girar a mesa cai sempre num assento vago`() {
        val vacant = listOf(Seat(1), Seat(3))
        com.gtranca.ui.multiplayer.nextVacant(GameMode.DUPLAS, Seat(2), vacant, +1) shouldBe Seat(3)
        com.gtranca.ui.multiplayer.nextVacant(GameMode.DUPLAS, Seat(2), vacant, -1) shouldBe Seat(1)
        com.gtranca.ui.multiplayer.nextVacant(GameMode.DUPLAS, Seat(3), listOf(Seat(1)), +1) shouldBe Seat(1)
        com.gtranca.ui.multiplayer.nextVacant(GameMode.DUPLAS, Seat(3), emptyList(), +1) shouldBe null
    }

    @Test
    fun `§16_1 recusa libera o mesmo pedido para jogar de novo quando a vista volta`() = runTest {
        val seed = 43L
        val t = setup(seed, targetScore = 3000)
        t.guest.confirmSeat()
        advanceUntilIdle()
        t.host.startGame()
        advanceUntilIdle()
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        val guestDriver = MultiplayerDriver.create(t.guest, t.scope) { 0 }
        drive(t.scope, hostDriver, seed)
        advanceUntilIdle()
        val turn = guestDriver.state.value
        turn.humanLegal.isNotEmpty() shouldBe true
        val errors = mutableListOf<com.gtranca.engine.model.RuleError?>()
        t.scope.launch { guestDriver.rejections.collect { errors += it } }
        // Jogada fora das legais (descartar antes de comprar): o anfitrião recusa com o motivo e o mesmo pedido volta a valer.
        val illegal = Action.Discard(turn.view.hand.first())
        turn.view.phase shouldBe Phase.AWAITING_DRAW
        guestDriver.submit(illegal, turn.humanRequestId) shouldBe true
        guestDriver.submit(illegal, turn.humanRequestId) shouldBe false // toque duplo: o mesmo pedido não sai de novo
        advanceUntilIdle()
        errors shouldHaveSize 1
        errors.single() shouldBe ActionError.MUST_DRAW_FIRST
        guestDriver.state.value.humanRequestId shouldBe turn.humanRequestId
        guestDriver.state.value.humanLegal shouldBe turn.humanLegal
        guestDriver.awaitingHuman() shouldBe true
    }
}
