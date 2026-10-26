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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

    /** §16.12 a sessão guardada, em memória. */
    private class FakeSessions : MpSessions {
        val saved = Collections.synchronizedList(mutableListOf<com.gtranca.data.MultiplayerSession>())
        var clears = 0
        override suspend fun save(session: com.gtranca.data.MultiplayerSession) { saved += session }
        override suspend fun clear() { clears++ }
        val last: com.gtranca.data.MultiplayerSession? get() = saved.lastOrNull()
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
        /** Derruba a conexão do convidado (queda curta: ele volta sozinho pelo token). */
        val dropGuest: () -> Unit = {},
        /** Põe o convidado fora do alcance ([on]) ou o traz de volta. */
        val guestOutOfRange: (Boolean) -> Unit = {},
        /** Uma mesa num aparelho novo (terceiro jogador). */
        val newDevice: () -> MultiplayerTable = { error("sem rede") },
    )

    private suspend fun TestScope.setup(
        seed: Long,
        targetScore: Int = 500,
        stake: Int = 50,
        robots: List<String> = listOf("jovem", "sobrio", "atleta"),
        hostPhoto: String? = null,
        guestPhoto: String? = null,
        guestSeat: Int = 1,
        guestSessions: MpSessions = MpSessions.None,
        hostSessions: MpSessions = MpSessions.None,
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
        val host = MultiplayerTable(hostTransport, scope, hostCoins, dispatcher, bots, newSeed = { seed }, sessions = hostSessions)
        val guest = MultiplayerTable(guestTransport, scope, guestCoins, dispatcher, bots, newSeed = { seed }, sessions = guestSessions)
        host.myPhoto = hostPhoto
        guest.myPhoto = guestPhoto
        host.host(rules, "Ana", "mesa-1", lobby(robots, seed, guestSeat))
        runCurrent() // o recrutado entra antes de o "Prepare-se!" acabar
        enter(host, guest, "mesa-1", "Beto", guestSeat)
        val again = { isHost: Boolean ->
            MultiplayerTable(if (isHost) hostTransport else guestTransport, scope, if (isHost) hostCoins else guestCoins, dispatcher, bots, newSeed = { seed }, sessions = if (isHost) hostSessions else guestSessions)
        }
        return Setup(host, guest, hostCoins, guestCoins, scope, rules, again, { network.kill(hostTransport.id) }, { network.drop(guestTransport.id) }, { on -> if (on) { network.block(guestTransport.id); network.drop(guestTransport.id) } else network.unblock(guestTransport.id) }) {
            MultiplayerTable(network.transport(), scope, FakeCoins(), dispatcher, bots, newSeed = { seed })
        }
    }

    /** §16.2 plano de lugares: o convidado ("Beto") recrutado no lugar [guestSeat] e os robôs escolhidos nos outros. */
    private fun lobby(robots: List<String>, seed: Long, guestSeat: Int = 1): com.gtranca.net.TablePlan {
        var layout = com.gtranca.net.TableLayout.initial(GameMode.DUPLAS).call(guestSeat, "k$guestSeat", "Beto")!!.accept("k$guestSeat")!!
        // O robô da vez do lugar do convidado cede o lugar a ele; os outros ficam nos lugares 1 a 3 na ordem escolhida.
        robots.forEachIndexed { i, persona ->
            val seat = i + 1
            if (seat in 1..3 && seat != guestSeat) layout = layout.placeRobot(seat, persona) ?: layout
        }
        layout = layout.fillWithRobots(com.gtranca.game.Tables.personaPool, kotlin.random.Random(seed))
        return com.gtranca.net.TablePlan(layout, com.gtranca.game.Tables.personaPool, seed)
    }

    /** §16.2 o recrutado entra na mesa do jogo com a própria chave; o "Prepare-se!" passa no relógio virtual e o jogo começa. */
    private suspend fun TestScope.enter(host: MultiplayerTable, guest: MultiplayerTable, tableId: String, name: String, seat: Int = 1) {
        guest.join(tableId, name, inviteKey = "k$seat")
        advanceUntilIdle()
    }

    /** §16.2 o jogo já começou sozinho depois do "Prepare-se!". */
    private suspend fun TestScope.start(t: Setup) {
        advanceUntilIdle()
    }

    @Test
    fun `um leitor por transporte - sair da sala e entrar de novo, e o anfitrião que joga duas vezes`() = runTest {
        val seed = 45L
        val t = setup(seed)
        // §16.2 o recrutado entrou no lugar planejado e o jogo começou sozinho depois do "Prepare-se!".
        val guest2 = t.guest
        guest2.mySeat shouldBe Seat(1)
        // O anfitrião joga até o fim e publica outra mesa no mesmo transporte; o convidado entra nela.
        start(t)
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
        host2.host(t.rules.copy(tableName = "Revanche"), "Ana", "mesa-2", lobby(t.rules.robots, seed))
        runCurrent()
        val guest3 = t.again(false)
        enter(host2, guest3, "mesa-2", "Beto")
        guest3.mySeat shouldBe Seat(1)
        host2.roster.value.size shouldBe 2
    }

    @Test
    fun `§16_13 quem sai das telas antes do premio chegar ainda recebe`() = runTest {
        val seed = 46L
        val t = setup(seed)
        advanceUntilIdle()
        start(t)
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
        advanceUntilIdle()
        start(t)
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
            // Esconder cartas (só no multijogador): viradas, sem seleção, aviso ao tocar; Comprar continua valendo.
            ui.canHideHand shouldBe true
            vm.onToggleHideHand()
            advanceUntilIdle()
            vm.uiState.value.handHidden shouldBe true
            vm.onCardClick(vm.uiState.value.hand.first())
            advanceUntilIdle()
            vm.uiState.value.selected shouldBe emptyList()
            vm.uiState.value.message shouldBe com.gtranca.ui.game.UiMessage.ShowCardsToPlay
            vm.uiState.value.canDraw shouldBe true
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
        val t = setup(seed, guestSeat = 2)
        // §16.2 o recrutado ocupa o lugar que o recrutador deu a ele (aqui, o 2); nome em branco = "Jogador 1".
        t.guest.mySeat shouldBe Seat(2)
        t.host.rename(" ")
        advanceUntilIdle()
        t.host.roster.value.first { it.isHost }.name shouldBe "Jogador 1"
        t.guest.roster.value.first { it.id == t.guest.myId }.let { it.seat shouldBe Seat(2); it.confirmed shouldBe true }

        start(t)
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
        // §16.15 o convidado mede a conexão com o anfitrião (1 a 3); no anfitrião não há medida.
        (t.guest.hostLinkQuality.value in 1..3) shouldBe true
        t.host.hostLinkQuality.value shouldBe null
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
        advanceUntilIdle()
        start(t)
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
        advanceUntilIdle()
        start(t)
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
        advanceUntilIdle()
        start(t)
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
        advanceUntilIdle()
        start(t)
        val hostPersonas = t.host.personas(Persona("Ana"), "VAGO") { it }
        val guestPersonas = t.guest.personas(Persona("Beto"), "VAGO") { it }
        advanceUntilIdle()
        // Convidado no lugar do primeiro robô (assento 1): os outros robôs ficam nos assentos 2 e 3, na ordem escolhida.
        listOf(2, 3).forEach { seat ->
            guestPersonas.value[seat] shouldBe hostPersonas.value[seat]
            guestPersonas.value[seat].character.shouldNotBeNull()
        }
        hostPersonas.value[2].character!!.profile shouldBe com.gtranca.game.BotProfile.ALTERNATIVO
        hostPersonas.value[3].character!!.profile shouldBe com.gtranca.game.BotProfile.SOBRIO
        // Quem volta à mesa (reingresso com o jogo em andamento) recebe os mesmos robôs.
        val guestSession = (t.guest.role.value as MpRole.Guesting).session
        guestSession.robots.value shouldBe mapOf(2 to "alternativo", 3 to "sobrio")
        (t.host.role.value as MpRole.Hosting).session.robots shouldBe mapOf(2 to "alternativo", 3 to "sobrio")
    }

    @Test
    fun `§16_6 cada aparelho ve uma jogada por vez, em ordem, no seu ritmo`() = runTest {
        val seed = 49L
        val t = setup(seed, targetScore = 3000)
        advanceUntilIdle()
        start(t)
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
    fun `§16_2 a foto do perfil chega uma vez aos outros aparelhos e a opcao desligada nao mostra`() = runTest {
        val hostJpeg = byteArrayOf(1, 2, 3)
        val guestJpeg = byteArrayOf(9, 8, 7, 6)
        val b64 = { b: ByteArray -> java.util.Base64.getEncoder().encodeToString(b) }
        val t = setup(51L, hostPhoto = b64(hostJpeg), guestPhoto = b64(guestJpeg))
        advanceUntilIdle()
        // Na sala: cada um recebe a foto do outro (só em memória).
        t.guest.photos.value[0]!!.toList() shouldBe hostJpeg.toList()
        t.host.photos.value[t.guest.myId]!!.toList() shouldBe guestJpeg.toList()
        start(t)
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
        advanceUntilIdle()
        start(t)
        t.host.hostControls.value.shouldNotBeNull()
        t.guest.hostControls.value shouldBe null
        t.dropHost()
        advanceUntilIdle()
        (t.guest.role.value is MpRole.Hosting) shouldBe true
        t.guest.hostControls.value.shouldNotBeNull()
    }

    @Test
    fun `§16_6 robos que jogam antes de a tela abrir - a tela comeca na distribuicao e anima cada jogada`() = runTest {
        // Anfitrião sozinho com robôs (o convidado saiu da escolha de posições e um robô assumiu, §16.2).
        var found = false
        for (seed in 60L..80L) {
            val t = setup(seed, targetScore = 3000)
            t.guest.leave()
            advanceUntilIdle()
            start(t)
            val host = (t.host.role.value as MpRole.Hosting).host!!
            // Os robôs já jogaram até a vez do anfitrião, antes de a tela existir.
            if (host.controller.state.value.turnEvents.all { it.isEmpty() }) continue
            found = true
            val driver = MultiplayerDriver.create(t.host, t.scope) { 700 }
            val seen = Collections.synchronizedList(mutableListOf<com.gtranca.game.GameSnapshot>())
            t.scope.launch { driver.state.collect { seen += it } }
            advanceUntilIdle()
            // A primeira vista da tela é a da distribuição (nenhuma jogada ainda).
            seen.first().turnEvents.all { it.isEmpty() } shouldBe true
            // Depois, uma jogada por vista, em ordem, até a vez do anfitrião.
            seen.zipWithNext().forEach { (a, b) -> (com.gtranca.ui.game.TableAnimations.newEvents(a, b).size <= 1) shouldBe true }
            seen.last().humanLegal.isNotEmpty() shouldBe true
            seen.last().turnEvents shouldBe host.controller.state.value.turnEvents
            break
        }
        found shouldBe true
    }

    @Test
    fun `§16_7 §16_9 vaga de humano que desistiu - outro aparelho entra, e aprovado e joga ate o fim`() = runTest {
        val seed = 95L
        val t = setup(seed, targetScore = 500)
        advanceUntilIdle()
        start(t)
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        drive(t.scope, hostDriver, seed)
        t.guest.resign()
        advanceUntilIdle()
        val host = (t.host.role.value as MpRole.Hosting).host!!
        host.decision.value.shouldNotBeNull().seat shouldBe Seat(1)
        // Um terceiro aparelho pede para entrar no assento VAGO (o anfitrião abre a vaga ao aprovar).
        val third = t.newDevice()
        third.join("mesa-1", "Caio")
        advanceUntilIdle()
        val request = host.hostSession.joinRequests.value.single()
        host.approveJoin(request.requestId) shouldBe true
        advanceUntilIdle()
        third.mySeat shouldBe Seat(1)
        // Como no app: ritmo de 700 ms e a tela (ViewModel) que avisa o fim das encenações.
        val thirdDriver = MultiplayerDriver.create(third, t.scope) { 700 }
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val vm = com.gtranca.ui.game.GameViewModel(
                MultiplayerTable.configOf(t.rules), animationMillis = 0, swapAnimationMillis = 0,
                externalDriver = thirdDriver, externalPersonas = third.personas(Persona("Caio"), "VAGO") { it }, multiplayer = true,
            )
            drive(t.scope, thirdDriver, seed)
            advanceUntilIdle()
            vm.uiState.value.snapshot.stage shouldBe Stage.GAME_OVER
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
        hostDriver.state.value.stage shouldBe Stage.GAME_OVER
        thirdDriver.state.value.stage shouldBe Stage.GAME_OVER
    }

    @Test
    fun `§16_8 §16_10 convidado fora do alcance na vez dele - ao voltar fica um so anfitriao e o jogo segue`() = runTest {
        val seed = 97L
        val t = setup(seed, targetScore = 500)
        advanceUntilIdle()
        start(t)
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        val guestDriver = MultiplayerDriver.create(t.guest, t.scope) { 0 }
        drive(t.scope, hostDriver, seed)
        t.guestOutOfRange(true)
        advanceTimeBy(20_000)
        val host = (t.host.role.value as MpRole.Hosting).host!!
        host.decision.value.shouldNotBeNull().seat shouldBe Seat(1)
        host.waitForPlayer()
        t.guestOutOfRange(false)
        advanceTimeBy(120_000)
        // §16.10 fora do alcance, o convidado (1º sucessor) não via a mesa e assumiu; de volta, só um anfitrião fica e o
        // outro reingressa pelo token. O jogo segue até o fim nos dois aparelhos.
        (listOf(t.host.role.value, t.guest.role.value).count { it is MpRole.Hosting }) shouldBe 1
        drive(t.scope, guestDriver, seed)
        advanceUntilIdle()
        hostDriver.state.value.stage shouldBe Stage.GAME_OVER
        guestDriver.state.value.stage shouldBe Stage.GAME_OVER
    }

    @Test
    fun `§16_9 quem desistiu volta a mesa pelo token - a tela sai do fim de jogo e ele joga ate o fim`() = runTest {
        val seed = 98L
        val t = setup(seed, targetScore = 500)
        advanceUntilIdle()
        start(t)
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        val guestDriver = MultiplayerDriver.create(t.guest, t.scope) { 700 }
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        try {
            val vm = com.gtranca.ui.game.GameViewModel(
                MultiplayerTable.configOf(t.rules), animationMillis = 0, swapAnimationMillis = 0,
                externalDriver = guestDriver, externalPersonas = t.guest.personas(Persona("Beto"), "VAGO") { it }, multiplayer = true,
            )
            drive(t.scope, hostDriver, seed)
            t.guest.resign()
            advanceUntilIdle()
            vm.uiState.value.snapshot.stage shouldBe Stage.GAME_OVER
            val host = (t.host.role.value as MpRole.Hosting).host!!
            host.decision.value.shouldNotBeNull()
            host.waitForPlayer()
            advanceUntilIdle()
            t.guest.returnToTable() shouldBe true
            advanceUntilIdle()
            t.guest.resigned.value shouldBe false
            drive(t.scope, guestDriver, seed)
            advanceUntilIdle()
            hostDriver.state.value.stage shouldBe Stage.GAME_OVER
            vm.uiState.value.snapshot.resigned shouldBe false
            vm.uiState.value.snapshot.stage shouldBe Stage.GAME_OVER
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun `§16_5 convidado cai no meio do jogo e volta pelo token - a tela segue recebendo e a vez anda`() = runTest {
        val seed = 96L
        val t = setup(seed, targetScore = 500)
        advanceUntilIdle()
        start(t)
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        val guestDriver = MultiplayerDriver.create(t.guest, t.scope) { 0 }
        drive(t.scope, hostDriver, seed)
        advanceUntilIdle()
        t.dropGuest()
        advanceTimeBy(3_000)
        advanceUntilIdle()
        drive(t.scope, guestDriver, seed)
        advanceUntilIdle()
        hostDriver.state.value.stage shouldBe Stage.GAME_OVER
        guestDriver.state.value.stage shouldBe Stage.GAME_OVER
    }

    @Test
    fun `§16_1 recusa libera o mesmo pedido para jogar de novo quando a vista volta`() = runTest {
        val seed = 43L
        val t = setup(seed, targetScore = 3000)
        advanceUntilIdle()
        start(t)
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

    @Test
    fun `§16_2 §16_7 anfitriao desliga o Wi-Fi no meio do jogo - o convidado vira robo e o jogo segue so no aparelho dele`() = runTest {
        val seed = 99L
        val t = setup(seed, targetScore = 500)
        advanceUntilIdle()
        start(t)
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        t.host.goOffline()
        advanceUntilIdle()
        t.host.offline.value shouldBe true
        // O convidado soube que a mesa foi encerrada (não tenta voltar nem assumir).
        t.guest.end.value shouldBe MpEnd.Ended(com.gtranca.net.CloseReason.HOST_OFFLINE)
        val host = (t.host.role.value as MpRole.Hosting).host!!
        host.robotPersonas.value.keys shouldContain 1
        drive(t.scope, hostDriver, seed)
        advanceUntilIdle()
        hostDriver.state.value.stage shouldBe Stage.GAME_OVER
    }

    @Test
    fun `§16_7 §16_12 Wi-Fi ligado num jogo so com robos - o jogo segue igual hospedado, o salvo e apagado e um robo pode dar lugar a quem entra`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        try {
            val saves = Collections.synchronizedList(mutableListOf<com.gtranca.data.SavedGame>())
            var discarded = 0
            val persistence = object : com.gtranca.game.GamePersistence {
                override suspend fun save(game: com.gtranca.data.SavedGame) { saves += game }
                override suspend fun finish(gameId: String, config: com.gtranca.data.SavedConfig, result: GameResult) = Unit
                override suspend fun saveHandSort(id: String) = Unit
                override suspend fun discard() { discarded++ }
            }
            val config = GameConfig(GameMode.DUPLAS, Difficulty.FACIL, 3000, stake = 50)
            val robots = mapOf(1 to "alternativo", 2 to "jovem", 3 to "atleta")
            val vm = com.gtranca.ui.game.GameViewModel(
                config, gameSeed = 7, computeDispatcher = dispatcher, botDelayMillis = 0, swapAnimationMillis = 0, animationMillis = 0,
                persistence = persistence, writes = com.gtranca.game.WriteQueue(CoroutineScope(dispatcher)), robots = robots,
            )
            advanceUntilIdle()
            val before = vm.uiState.value.snapshot
            val handOff = vm.handOffToHost().shouldNotBeNull()
            advanceUntilIdle()
            // §16.12 o jogo salvo é apagado e nada mais é gravado.
            discarded shouldBe 1
            val savesAtHandOff = saves.size
            handOff.robots shouldBe robots
            val network = MemoryNetwork()
            val scope = CoroutineScope(dispatcher + Job())
            val table = MultiplayerTable(network.transport(), scope, FakeCoins(), dispatcher, newSeed = { 7 })
            val rules = TableRules("Mesa de Ana", GameMode.DUPLAS, handOff.config.rules, 3000, 50, Difficulty.FACIL.id)
            table.hostRobotGame(rules, "Ana", "mesa-r", handOff.gameSeed, handOff.snapshot, handOff.robots)
            advanceTimeBy(1)
            val host = (table.role.value as MpRole.Hosting).host.shouldNotBeNull()
            // O jogo não muda: mesma partida, mesma mão, mesmo placar, os mesmos robôs.
            host.controller.currentMatch.currentRound shouldBe handOff.snapshot.match.currentRound
            host.controller.state.value.view.hand shouldBe before.view.hand
            host.controller.state.value.totals shouldBe before.totals
            host.robotPersonas.value shouldBe robots
            table.hostControls.value.shouldNotBeNull()
            // §16.7 o anfitrião libera um assento de robô: a mesa aparece na lista com "jogo em andamento".
            table.openRobotSeat(Seat(2))
            advanceTimeBy(1)
            network.adverts().single().let { it.listed shouldBe true; it.inProgress shouldBe true }
            saves.size shouldBe savesAtHandOff
            // §16.7 sem replay: a tela começa na vista atual (sem distribuição nem voos), como no reingresso.
            table.continuing shouldBe true
            val driver = MultiplayerDriver.create(table, scope) { 0 }
            driver.state.value.view.hand shouldBe before.view.hand
            driver.state.value.turnEvents shouldBe before.turnEvents
            val screen = com.gtranca.ui.game.GameViewModel(
                MultiplayerTable.configOf(rules), animationMillis = 400, swapAnimationMillis = 0,
                externalDriver = driver, multiplayer = true, continuing = true,
            )
            screen.uiState.value.flights shouldBe emptyList()
            // §16.7 ligar → desligar → ligar quantas vezes quiser: o jogo segue o mesmo, sem novo débito nem gravação.
            repeat(2) {
                table.goOffline()
                advanceTimeBy(10_000)
                table.offline.value shouldBe true
                val match = (table.role.value as MpRole.Hosting).host!!.controller.currentMatch
                table.goOnline()
                advanceTimeBy(1)
                table.offline.value shouldBe false
                val again = (table.role.value as MpRole.Hosting).host.shouldNotBeNull()
                again.controller.currentMatch.currentRound shouldBe match.currentRound
                again.robotPersonas.value shouldBe robots
                table.hostControls.value.shouldNotBeNull()
            }
            saves.size shouldBe savesAtHandOff
            table.leave()
            advanceTimeBy(1)
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun `§16_12 o jogador comum grava so a sessao ao entrar - mesa, assento, token e nomes - e o anfitriao tambem`() = runTest {
        val guestSessions = FakeSessions()
        val hostSessions = FakeSessions()
        val t = setup(110L, guestSessions = guestSessions, hostSessions = hostSessions)
        advanceUntilIdle()
        guestSessions.last.shouldNotBeNull().let {
            it.tableId shouldBe "mesa-1"
            it.seat shouldBe 1
            it.playerName shouldBe "Beto"
            it.hostName shouldBe "Ana"
            it.wasHost shouldBe false
            it.token.isNotBlank() shouldBe true
        }
        // §16.12 o anfitrião volta como jogador comum: grava a sessão com o token dele, marcada como anfitrião.
        hostSessions.last.shouldNotBeNull().let { it.wasHost shouldBe true; it.seat shouldBe 0; it.token.isNotBlank() shouldBe true }
        // nada mais que a sessão: o jogo não vai a lugar nenhum (o tipo guarda só mesa, assento, token e nomes)
        guestSessions.saved.map { it.copy(savedAtMillis = 0) }.distinct().size shouldBe 1
        guestSessions.clears shouldBe 0
        t.host.role.value.shouldNotBeNull()
    }

    @Test
    fun `§16_12 §16_11 a sessao e apagada quando o resultado do jogo e entregue`() = runTest {
        val seed = 111L
        val guestSessions = FakeSessions()
        val hostSessions = FakeSessions()
        val t = setup(seed, guestSessions = guestSessions, hostSessions = hostSessions)
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        val guestDriver = MultiplayerDriver.create(t.guest, t.scope) { 0 }
        drive(t.scope, hostDriver, seed)
        drive(t.scope, guestDriver, seed)
        advanceUntilIdle()
        guestDriver.state.value.stage shouldBe Stage.GAME_OVER
        guestSessions.clears shouldBe 1
        hostSessions.clears shouldBe 1
    }

    @Test
    fun `§16_12 §16_9 desistir e sair das telas sem voltar apaga a sessao`() = runTest {
        val guestSessions = FakeSessions()
        val t = setup(112L, guestSessions = guestSessions)
        advanceUntilIdle()
        t.guest.resign()
        advanceUntilIdle()
        guestSessions.clears shouldBe 0 // ainda pode voltar à mesa pelo token
        t.guest.exitScreens()
        advanceUntilIdle()
        guestSessions.clears shouldBe 1
    }

    @Test
    fun `§16_5 §16_12 Continuar - com o aplicativo fechado a sessao guardada reingressa no mesmo assento e recebe a vista atual`() = runTest {
        val seed = 113L
        val guestSessions = FakeSessions()
        val t = setup(seed, guestSessions = guestSessions)
        val hostDriver = MultiplayerDriver.create(t.host, t.scope) { 0 }
        drive(t.scope, hostDriver, seed)
        advanceUntilIdle()
        val saved = guestSessions.last.shouldNotBeNull()
        // O aplicativo do convidado fechou: a mesa some do aparelho, a sessão fica guardada.
        t.guest.leave()
        t.dropGuest() // o aplicativo fechou: a conexão cai
        advanceUntilIdle()
        val again = t.again(false)
        again.rejoin(saved.tableId, saved.playerName, saved.token, Seat(saved.seat), saved.wasHost) shouldBe true
        advanceUntilIdle()
        again.guestStatus.value shouldBe com.gtranca.net.GuestStatus.Playing
        again.mySeat shouldBe Seat(1)
        again.rules.value.shouldNotBeNull().stake shouldBe 50
        guestSessions.clears shouldBe 0
    }

    @Test
    fun `§16_5 §16_12 token recusado - A vaga foi preenchida - apaga a sessao`() = runTest {
        val guestSessions = FakeSessions()
        val t = setup(114L, guestSessions = guestSessions)
        advanceUntilIdle()
        val saved = guestSessions.last.shouldNotBeNull()
        t.guest.leave()
        t.dropGuest() // o aplicativo fechou: a conexão cai
        advanceUntilIdle()
        val again = t.again(false)
        again.rejoin(saved.tableId, saved.playerName, "token-errado", Seat(saved.seat), false) shouldBe true
        advanceUntilIdle()
        (again.end.value as MpEnd.Refused).message.contains("ocupado") shouldBe true
        guestSessions.clears shouldBe 1
    }

    @Test
    fun `§16_5 §16_12 mesa nao encontrada em 30 s - fim Mesa nao encontrada e a sessao fica`() = runTest {
        val guestSessions = FakeSessions()
        val t = setup(115L, guestSessions = guestSessions)
        advanceUntilIdle()
        val saved = guestSessions.last.shouldNotBeNull()
        t.guest.leave()
        t.dropGuest() // o aplicativo fechou: a conexão cai
        advanceUntilIdle()
        val again = t.again(false)
        again.rejoin("mesa-que-nao-existe", saved.playerName, saved.token, Seat(saved.seat), false) shouldBe false
        again.end.value shouldBe MpEnd.NotFound
        guestSessions.clears shouldBe 0
    }

    @Test
    fun `§16_2 §16_15 recrutado sem achar a mesa em 30 s termina em Mesa nao encontrada (e nao antes)`() = runTest {
        val t = setup(116L)
        advanceUntilIdle()
        t.guest.leave()
        t.dropGuest()
        advanceUntilIdle()
        val again = t.again(false)
        again.joinAsync("mesa-que-nao-existe", "Bia", inviteKey = "kz")
        advanceTimeBy(29_000)
        again.end.value shouldBe null
        advanceTimeBy(2_000)
        again.end.value shouldBe MpEnd.NotFound
    }
}
