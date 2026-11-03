package com.gtranca.ui.online

import androidx.lifecycle.viewModelScope
import com.gtranca.data.InventoryRepository
import com.gtranca.data.OnlineGuest
import com.gtranca.data.Settings
import com.gtranca.data.WalletRepository
import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.BoosterDef
import com.gtranca.engine.Gift
import com.gtranca.engine.Inventory
import com.gtranca.engine.Loadout
import com.gtranca.engine.RuleSet
import com.gtranca.engine.Wallet
import com.gtranca.engine.model.GameMode
import com.gtranca.online.client.OnlineErrorKind
import com.gtranca.online.client.OnlineException
import com.gtranca.online.contracts.AccountLimits
import com.gtranca.online.contracts.BotLevel
import com.gtranca.online.contracts.ErrorCode
import com.gtranca.online.contracts.Punicao
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableState
import com.gtranca.online.contracts.TempoDeJogada
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Telas online (docs/telas.md 3.21 e 3.23): conta de convidado, lista de mesas do servidor e a sala (§18.2). */
@OptIn(ExperimentalCoroutinesApi::class)
class OnlineViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private val lobby = FakeLobby()
    private val settings = FakeSettings(Settings(onlineServer = "servidor:8080"))
    private fun account(name: String = "Ana") = OnlineAccount(lobby, settings) { name }

    // ---------- conta de convidado ----------

    // §18.1 convidado provisório com o nome do Perfil, guardado só no aparelho
    @Test
    fun `cria o convidado com o nome do Perfil e guarda`() = runTest(dispatcher) {
        val g = account("Ana").guest()
        g.name shouldBe "Ana"
        settings.state.value.onlineGuest shouldBe g
        account("Outro").guest() shouldBe g // reaproveita o guardado
        lobby.calls.count { it == "createGuest" } shouldBe 1
    }

    // §18.1 o nome do convidado respeita o limite do servidor (24)
    @Test
    fun `corta o nome em 24 caracteres`() = runTest(dispatcher) {
        account("A".repeat(40)).guest().name.length shouldBe 24
    }

    // §18.1 lobby reiniciado (convidados em memória): recria o convidado e repete a chamada uma vez
    @Test
    fun `token esquecido pelo servidor recria o convidado e repete a chamada`() = runTest(dispatcher) {
        val acc = account()
        val first = acc.guest()
        lobby.forgotten += first.token
        val id = lobby.serverTable()
        val table = acc.call { api, g -> api.join(g.token, id) }
        table.hostId shouldBe settings.state.value.onlineGuest!!.id
        (settings.state.value.onlineGuest!!.token == first.token) shouldBe false
    }

    // ---------- 3.21 Jogar online ----------

    // §18.1 sem servidor informado o online mostra o que falta
    @Test
    fun `sem servidor no Perfil mostra NO_SERVER`() = runTest(dispatcher) {
        val vm = OnlineHomeViewModel(FakeSettings()) { null }
        advanceUntilIdle()
        vm.uiState.value.status shouldBe OnlineHomeStatus.NO_SERVER
    }

    // §18.1 com servidor, o convidado e criado e a tela libera criar e entrar
    @Test
    fun `com servidor entra como convidado e fica READY`() = runTest(dispatcher) {
        val vm = OnlineHomeViewModel(settings) { account("Ana") }
        runCurrent()
        vm.uiState.value.status shouldBe OnlineHomeStatus.READY
        vm.uiState.value.name shouldBe "Ana"
        vm.viewModelScope.cancel() // a lista de mesas consulta sem parar
    }

    // §18.8 versão incompatível e rede viram mensagem com Tentar de novo
    @Test
    fun `falha de versao ou rede vira ERROR e o retry recupera`() = runTest(dispatcher) {
        lobby.failNext = OnlineException(OnlineErrorKind.UPDATE_APP, ErrorCode.VERSAO_INCOMPATIVEL, 426)
        val vm = OnlineHomeViewModel(settings) { account() }
        runCurrent()
        vm.uiState.value.status shouldBe OnlineHomeStatus.ERROR
        vm.uiState.value.problem shouldBe OnlineProblem.UPDATE_APP
        vm.retry()
        runCurrent()
        vm.uiState.value.status shouldBe OnlineHomeStatus.READY
        vm.viewModelScope.cancel()
    }

    // §18.8 servidor em manutenção
    @Test
    fun `503 vira MAINTENANCE`() = runTest(dispatcher) {
        lobby.failNext = OnlineException(OnlineErrorKind.MAINTENANCE, ErrorCode.VERSAO_INCOMPATIVEL, 503)
        val vm = OnlineHomeViewModel(settings) { account() }
        runCurrent()
        vm.uiState.value.problem shouldBe OnlineProblem.MAINTENANCE
        vm.viewModelScope.cancel()
    }

    // ---------- 3.21 saldo online e ganho diário (§14.6/§18.5) ----------

    private fun homeWithEconomy(economy: FakeEconomy, today: Long = 100) =
        OnlineHomeViewModel(settings, pollMs = 3_000, economyFor = { economy }, today = { today }) { account("Ana") }

    // §18.5 a tela mostra o saldo online e o botão de ganho diário quando ele ainda não foi resgatado
    @Test
    fun `mostra o saldo online e o botao de ganho diario`() = runTest(dispatcher) {
        val vm = homeWithEconomy(FakeEconomy(initialBalance = 300))
        runCurrent()
        vm.uiState.value.balance shouldBe 300
        vm.uiState.value.canClaimDaily shouldBe true
        vm.viewModelScope.cancel()
    }

    // §14.6 resgatar credita o saldo e o botão some até o dia seguinte
    @Test
    fun `resgatar o ganho diario credita o saldo e esconde o botao`() = runTest(dispatcher) {
        val vm = homeWithEconomy(FakeEconomy(initialBalance = 300))
        runCurrent()
        vm.onClaimDaily()
        runCurrent()
        vm.uiState.value.balance shouldBe 300 + com.gtranca.engine.Coins.DAILY_GAIN
        vm.uiState.value.canClaimDaily shouldBe false
        vm.uiState.value.claimingDaily shouldBe false
        vm.viewModelScope.cancel()
    }

    // §14.6 outro aparelho já resgatou hoje: a tela concilia e esconde o botão sem mostrar erro
    @Test
    fun `ja resgatado em outro aparelho esconde o botao ao reler`() = runTest(dispatcher) {
        val economy = FakeEconomy(initialBalance = 300)
        val vm = homeWithEconomy(economy)
        runCurrent()
        // Outro aparelho resgata primeiro, diretamente no servidor.
        economy.claimDaily("tok", vm.uiState.value.myId, 100)
        vm.onClaimDaily()
        runCurrent()
        vm.uiState.value.canClaimDaily shouldBe false
        vm.viewModelScope.cancel()
    }

    // §18.5 saldo não impede a lista de mesas de funcionar quando a economia falha
    @Test
    fun `falha da economia nao derruba a lista de mesas`() = runTest(dispatcher) {
        lobby.serverTable()
        val economy = FakeEconomy()
        economy.failNext = OnlineException(OnlineErrorKind.NETWORK)
        val vm = homeWithEconomy(economy)
        runCurrent()
        vm.uiState.value.status shouldBe OnlineHomeStatus.READY
        vm.uiState.value.balance shouldBe null
        vm.uiState.value.tables?.size shouldBe 1
        vm.viewModelScope.cancel()
    }

    // ---------- 3.21 "Trazer minha carteira" (§18.6) ----------

    private class FakeWallet(balance: Int) : WalletRepository {
        val state = MutableStateFlow(Wallet(balance))
        override val wallet: Flow<Wallet> = state
        override val lifetimeNet: Flow<Int> = emptyFlow()
        override suspend fun debit(stake: Int): Boolean = false
        override suspend fun claimDaily(today: Long): Boolean = false
    }

    private class FakeInventoryRepo(quantities: Map<String, Int> = emptyMap(), override val catalog: List<BoosterDef> = BoosterCatalog.DEFAULT) : InventoryRepository {
        val state = MutableStateFlow(Inventory(quantities))
        override val inventory: Flow<Inventory> = state
        override val selection: Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun setSelection(ids: List<String>) = Unit
        override suspend fun confirmLoadout(selection: List<String>) = Loadout.confirm(selection, state.value, catalog)
        override suspend fun claimGift(today: Long, random: Random): Gift? = null
    }

    /** Conta por e-mail já logada (id com o prefixo E_), para o botão de migrar poder aparecer. */
    private suspend fun signedInAccount(name: String = "Ana", email: String = "ana@example.com"): OnlineAccount {
        val acc = account(name)
        acc.signIn(lobby.registerEmail(email, "segredo1", name, null))
        return acc
    }

    private fun homeWithWallet(acc: OnlineAccount, economy: FakeEconomy, walletBalance: Int, boosters: Map<String, Int> = emptyMap()) =
        OnlineHomeViewModel(settings, pollMs = 3_000, economyFor = { economy }, wallet = FakeWallet(walletBalance), inventory = FakeInventoryRepo(boosters)) { acc }

    // §18.6 só a conta (nunca o convidado) e só antes da 1ª migração vê o botão
    @Test
    fun `so a conta que ainda nao migrou pode trazer a carteira`() = runTest(dispatcher) {
        val vmGuest = homeWithWallet(account("Beto"), FakeEconomy(), 100)
        runCurrent()
        vmGuest.uiState.value.canMigrateWallet shouldBe false // convidado
        vmGuest.viewModelScope.cancel()

        val vmAccount = homeWithWallet(signedInAccount(), FakeEconomy(), 100)
        runCurrent()
        vmAccount.uiState.value.canMigrateWallet shouldBe true
        vmAccount.viewModelScope.cancel()
    }

    // §18.6 conta que já migrou (servidor informa migratedLocal) não vê mais o botão
    @Test
    fun `conta que ja migrou nao mostra o botao`() = runTest(dispatcher) {
        val acc = signedInAccount()
        val economy = FakeEconomy()
        val vm = homeWithWallet(acc, economy, 100)
        runCurrent()
        economy.migrateLocalWallet("tok", vm.uiState.value.myId, 10, emptyMap()) // outro aparelho já migrou
        vm.refresh()
        runCurrent()
        vm.uiState.value.canMigrateWallet shouldBe false
        vm.viewModelScope.cancel()
    }

    // §18.6 o toque lê o saldo e só os boosters locais com quantidade > 0
    @Test
    fun `o toque abre o modal com o saldo e os boosters locais`() = runTest(dispatcher) {
        val booster = BoosterCatalog.DEFAULT.first()
        val vm = homeWithWallet(signedInAccount(), FakeEconomy(), 777, mapOf(booster.id to 3, "zerado" to 0))
        runCurrent()
        vm.onMigrateTap()
        runCurrent()
        val dialog = vm.uiState.value.migrateDialog!!
        dialog.coins shouldBe 777
        dialog.items shouldBe listOf(MigrateWalletItem(booster.id, booster.name, 3))
        vm.viewModelScope.cancel()
    }

    // §18.6 um booster que o catálogo LOCAL conhece mas que o servidor ainda não (descompasso de versão) VAI no
    // pedido — nunca é filtrado no aparelho, pois o servidor (EconomyStore.kt, commit d67ce11) recusa o pedido
    // INTEIRO de propósito nesse caso, para nunca perder um booster silenciosamente numa operação de uma vez só; a
    // conta continua sem migrar e o saldo online não muda, para o jogador poder tentar de novo depois de atualizar.
    @Test
    fun `booster fora do catalogo do servidor vai no pedido e o servidor recusa tudo`() = runTest(dispatcher) {
        val catalogDivergente = BoosterCatalog.DEFAULT + BoosterDef("novo-booster-so-local", "Novo", "x", 1, 5, 1, 3)
        val acc = signedInAccount()
        val wallet = FakeWallet(100)
        val inventory = FakeInventoryRepo(mapOf("novo-booster-so-local" to 2), catalog = catalogDivergente)
        val economy = FakeEconomy(initialBalance = 50)
        val vm = OnlineHomeViewModel(settings, pollMs = 3_000, economyFor = { economy }, wallet = wallet, inventory = inventory) { acc }
        runCurrent()
        vm.onMigrateTap()
        runCurrent()
        vm.uiState.value.migrateDialog!!.items shouldBe listOf(MigrateWalletItem("novo-booster-so-local", "Novo", 2))
        vm.onMigrateConfirm()
        runCurrent()
        vm.uiState.value.migrateDialog?.problem shouldBe OnlineProblem.UNEXPECTED
        vm.uiState.value.migratedLocal shouldBe false
        vm.uiState.value.balance shouldBe 50L // nada creditado: o pedido inteiro foi recusado
        vm.viewModelScope.cancel()
    }

    // §18.6 confirmar credita o saldo online, esconde o botão e NUNCA grava no saldo/inventário local
    @Test
    fun `confirmar migra e nao toca na carteira local`() = runTest(dispatcher) {
        val booster = BoosterCatalog.DEFAULT.first()
        val wallet = FakeWallet(500)
        val inventory = FakeInventoryRepo(mapOf(booster.id to 2))
        val economy = FakeEconomy(initialBalance = 1_000)
        val acc = signedInAccount()
        val vm = OnlineHomeViewModel(settings, pollMs = 3_000, economyFor = { economy }, wallet = wallet, inventory = inventory) { acc }
        runCurrent()
        vm.onMigrateTap()
        runCurrent()
        vm.onMigrateConfirm()
        runCurrent()
        vm.uiState.value.balance shouldBe 1_000L + 500
        vm.uiState.value.migrateDialog shouldBe null
        vm.uiState.value.canMigrateWallet shouldBe false
        wallet.state.value.balance shouldBe 500 // save local intocado
        inventory.state.value.quantities[booster.id] shouldBe 2 // save local intocado
        vm.viewModelScope.cancel()
    }

    // §18.6 falha de rede mantém o modal aberto com o motivo; repetir usa os MESMOS valores lidos (não relê)
    @Test
    fun `falha de rede mantem o modal e repetir usa os mesmos valores`() = runTest(dispatcher) {
        val booster = BoosterCatalog.DEFAULT.first()
        val wallet = FakeWallet(500)
        val inventory = FakeInventoryRepo(mapOf(booster.id to 2))
        val economy = FakeEconomy()
        val acc = signedInAccount()
        val vm = OnlineHomeViewModel(settings, pollMs = 3_000, economyFor = { economy }, wallet = wallet, inventory = inventory) { acc }
        runCurrent()
        vm.onMigrateTap()
        runCurrent()
        economy.failNext = OnlineException(OnlineErrorKind.NETWORK)
        vm.onMigrateConfirm()
        runCurrent()
        vm.uiState.value.migrateDialog?.problem shouldBe OnlineProblem.NETWORK
        vm.uiState.value.migrateDialog?.busy shouldBe false
        // Enquanto isso, o aparelho ganhou mais boosters — a repetição não deve ler isso de novo.
        inventory.state.value = Inventory(mapOf(booster.id to 99))
        vm.onMigrateConfirm()
        runCurrent()
        vm.uiState.value.migrateDialog shouldBe null
        vm.uiState.value.balance shouldBe 1_000L + 500
        vm.viewModelScope.cancel()
    }

    // §18.6 cancelar fecha o modal sem chamar o servidor
    @Test
    fun `cancelar fecha o modal sem migrar`() = runTest(dispatcher) {
        val economy = FakeEconomy()
        val vm = homeWithWallet(signedInAccount(), economy, 200)
        runCurrent()
        vm.onMigrateTap()
        runCurrent()
        vm.onMigrateCancel()
        vm.uiState.value.migrateDialog shouldBe null
        vm.uiState.value.canMigrateWallet shouldBe true // ainda não migrou
        vm.viewModelScope.cancel()
    }

    // ---------- 3.21 lista de mesas do servidor (§18.2) ----------

    private fun home() = OnlineHomeViewModel(settings, pollMs = 3_000) { account("Ana") }

    // §18.2 a tela mostra os jogadores online e as mesas do servidor, abertas primeiro
    @Test
    fun `a lista mostra os jogadores online e as mesas abertas antes das em jogo`() = runTest(dispatcher) {
        val playing = lobby.serverTable()
        lobby.otherJoins(playing, "Beto")
        lobby.setState(playing, TableState.EM_JOGO)
        val open = lobby.serverTable()
        lobby.onlinePlayers = 5
        val vm = home()
        runCurrent()
        vm.uiState.value.onlinePlayers shouldBe 5
        vm.uiState.value.tables!!.map { it.id } shouldBe listOf(open, playing)
        vm.viewModelScope.cancel()
    }

    // §18.2 a lista se atualiza sozinha
    @Test
    fun `a lista atualiza sozinha`() = runTest(dispatcher) {
        val id = lobby.serverTable()
        val vm = home()
        runCurrent()
        vm.uiState.value.tables!!.single().seats.count { it.kind == SeatKind.HUMANO } shouldBe 0
        lobby.otherJoins(id, "Beto")
        advanceTimeBy(3_001)
        runCurrent()
        vm.uiState.value.tables!!.single().seats.count { it.kind == SeatKind.HUMANO } shouldBe 1
        vm.viewModelScope.cancel()
    }

    // §18.2 tocar num lugar VAGO senta ali; o 1o a entrar e o anfitriao
    @Test
    fun `tocar num lugar vago senta naquele lugar e o primeiro vira anfitriao`() = runTest(dispatcher) {
        val id = lobby.serverTable()
        val vm = home()
        runCurrent()
        vm.onSeatTap(vm.uiState.value.tables!!.single(), 3)
        runCurrent()
        vm.uiState.value.joinedId shouldBe id
        val me = settings.state.value.onlineGuest!!.id
        lobby.tables.getValue(id).seats[3].guestId shouldBe me
        lobby.tables.getValue(id).hostId shouldBe me
        vm.viewModelScope.cancel()
    }

    // §18.2 dois pedem o mesmo lugar: o segundo e recusado e a mesa se atualiza
    @Test
    fun `lugar pedido por outro antes e recusado e a mesa atualiza`() = runTest(dispatcher) {
        val id = lobby.serverTable()
        val vm = home()
        runCurrent()
        val stale = vm.uiState.value.tables!!.single()
        lobby.otherJoins(id, "Beto") // Beto pega o lugar 0 antes
        vm.onSeatTap(stale, 0)
        runCurrent()
        vm.uiState.value.problem shouldBe OnlineProblem.SEAT_TAKEN
        vm.uiState.value.joinedId shouldBe null
        vm.uiState.value.tables!!.single().seats[0].name shouldBe "Beto"
        vm.viewModelScope.cancel()
    }

    // §18.2 lugar ocupado e mesa em jogo nao respondem ao toque
    @Test
    fun `lugar ocupado e mesa em jogo nao pedem nada`() = runTest(dispatcher) {
        val id = lobby.serverTable()
        lobby.otherJoins(id, "Beto")
        val vm = home()
        runCurrent()
        vm.onSeatTap(vm.uiState.value.tables!!.single(), 0)
        lobby.setState(id, TableState.EM_JOGO)
        vm.onSeatTap(vm.uiState.value.tables!!.single().copy(state = TableState.EM_JOGO), 1)
        runCurrent()
        lobby.calls.count { it == "join" } shouldBe 0
        vm.viewModelScope.cancel()
    }

    // §18.2 quem ja esta sentado volta direto a sala
    @Test
    fun `a minha mesa volta direto a sala`() = runTest(dispatcher) {
        val id = lobby.serverTable()
        account("Ana").call { api, g -> api.join(g.token, id) }
        val vm = home()
        runCurrent()
        vm.uiState.value.myTable!!.id shouldBe id
        vm.onBackToRoom(vm.uiState.value.tables!!.single())
        vm.uiState.value.joinedId shouldBe id
        vm.viewModelScope.cancel()
    }

    // §18.2 sentado, tocar noutro lugar livre troca de lugar (sem sair da lista)
    @Test
    fun `sentado tocar noutro lugar livre troca de lugar`() = runTest(dispatcher) {
        val id = lobby.serverTable()
        account("Ana").call { api, g -> api.join(g.token, id, 0) }
        val vm = home()
        runCurrent()
        vm.onSeatTap(vm.uiState.value.tables!!.single(), 2)
        runCurrent()
        vm.uiState.value.joinedId shouldBe null
        val me = settings.state.value.onlineGuest!!.id
        lobby.tables.getValue(id).seats[2].guestId shouldBe me
        lobby.tables.getValue(id).seats[0].kind shouldBe SeatKind.VAGO
        vm.viewModelScope.cancel()
    }

    // §18.2 a foto do Perfil vai ao servidor e a dos outros aparece na mesa
    @Test
    fun `envia a minha foto e baixa a dos outros jogadores`() = runTest(dispatcher) {
        val id = lobby.serverTable()
        val beto = lobby.otherJoins(id, "Beto")
        lobby.photos[beto.id] = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        lobby.tables[id] = lobby.tables.getValue(id).let { t ->
            t.copy(seats = t.seats.map { if (it.guestId == beto.id) it.copy(photoUrl = "/api/lobby/guests/${beto.id}/photo?v=1") else it })
        }
        val vm = OnlineHomeViewModel(settings, pollMs = 3_000, myPhoto = { "bWluaGE=" }) { account("Ana") }
        runCurrent()
        lobby.photos[settings.state.value.onlineGuest!!.id] shouldBe "bWluaGE="
        vm.photos.value[beto.id]!!.toList() shouldBe listOf<Byte>(1, 2, 3)
        vm.viewModelScope.cancel()
    }

    // §18.2 na sala, tocar num lugar livre troca de lugar
    @Test
    fun `na sala tocar num lugar livre troca de lugar`() = runTest(dispatcher) {
        val id = newTable()
        val vm = room(id)
        runCurrent()
        vm.onMove(3)
        runCurrent()
        vm.uiState.value.mySeat shouldBe 3
        vm.viewModelScope.cancel()
    }

    // ---------- 3.22 Conta online (§18.1) ----------

    // §18.1 cadastro: cria a conta, migra a carteira do convidado deste aparelho (§18.5) e volta "done"
    @Test
    fun `cria a conta por e-mail e senha`() = runTest(dispatcher) {
        val acc = account("Ana")
        acc.guest() // convidado deste aparelho, para a migração da carteira (§18.5)
        val vm = OnlineAccountViewModel(acc, lobby)
        vm.onEmail("ana@example.com")
        vm.onPassword("segredo1")
        vm.onSignup()
        advanceUntilIdle()
        vm.uiState.value.done shouldBe true
        AccountLimits.isAccountId(settings.state.value.onlineGuest!!.id) shouldBe true
        lobby.calls.count { it == "registerEmail" } shouldBe 1
    }

    // §18.1 trap: senha errada chama loginEmail direto (nunca call/callWith), não cria convidado novo nem troca
    // o guardado, e mostra "e-mail ou senha incorretos" (não "acesso expirou").
    @Test
    fun `senha errada nao cria convidado novo nem troca o guardado`() = runTest(dispatcher) {
        lobby.registerEmail("ana@example.com", "correta1", "Ana", null)
        val acc = account("Bob")
        val guestBefore = acc.guest()
        lobby.calls.clear()
        val vm = OnlineAccountViewModel(acc, lobby)
        vm.onEmail("ana@example.com")
        vm.onPassword("errada12")
        vm.onLogin()
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineAccountProblem.INVALID_CREDENTIALS
        vm.uiState.value.busy shouldBe false
        vm.uiState.value.done shouldBe false
        settings.state.value.onlineGuest shouldBe guestBefore
        lobby.calls shouldBe listOf("loginEmail")
    }

    // §18.1 entra com e-mail e senha certos: guarda a conta (troca o convidado) e some o problema anterior
    @Test
    fun `login certo entra na conta`() = runTest(dispatcher) {
        lobby.registerEmail("ana@example.com", "correta1", "Ana", null)
        val vm = OnlineAccountViewModel(account("Bob"), lobby)
        vm.onEmail("ana@example.com")
        vm.onPassword("correta1")
        vm.onLogin()
        advanceUntilIdle()
        vm.uiState.value.done shouldBe true
        AccountLimits.isAccountId(settings.state.value.onlineGuest!!.id) shouldBe true
    }

    // §18.1 cadastro com e-mail já existente: orienta a entrar, em vez de tentar criar de novo
    @Test
    fun `cadastro com e-mail existente orienta a entrar`() = runTest(dispatcher) {
        lobby.registerEmail("ana@example.com", "correta1", "Ana", null)
        val vm = OnlineAccountViewModel(account("Bob"), lobby)
        vm.onEmail("ana@example.com")
        vm.onPassword("outraSenha1")
        vm.onSignup()
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineAccountProblem.EMAIL_ALREADY_REGISTERED
    }

    // §18.5 cadastro com a carteira indisponível: a conta já foi criada no lobby, então orienta Entrar (não repetir
    // Criar conta, que daria "e-mail já cadastrado").
    @Test
    fun `cadastro com carteira indisponivel orienta Entrar`() = runTest(dispatcher) {
        val acc = account("Ana")
        acc.guest() // materializa o convidado antes de falhar a próxima chamada (registerEmail)
        lobby.failNext = OnlineException(OnlineErrorKind.API, ErrorCode.LOGIN_INDISPONIVEL, 503)
        val vm = OnlineAccountViewModel(acc, lobby)
        vm.onEmail("ana@example.com")
        vm.onPassword("segredo1")
        vm.onSignup()
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineAccountProblem.WALLET_PENDING
    }

    // §18.5 o mesmo código (LOGIN_INDISPONIVEL) ao entrar não é "conta já criada": é só "tente de novo"
    @Test
    fun `login com carteira indisponivel mostra manutencao`() = runTest(dispatcher) {
        val acc = account("Ana")
        acc.guest() // materializa o convidado antes de falhar a próxima chamada (loginEmail)
        lobby.failNext = OnlineException(OnlineErrorKind.API, ErrorCode.LOGIN_INDISPONIVEL, 503)
        val vm = OnlineAccountViewModel(acc, lobby)
        vm.onEmail("ana@example.com")
        vm.onPassword("segredo1")
        vm.onLogin()
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineAccountProblem.MAINTENANCE
    }

    // §18.1 muitas tentativas erradas: o texto avisa para tentar depois
    @Test
    fun `login bloqueado avisa para tentar depois`() = runTest(dispatcher) {
        val acc = account("Ana")
        acc.guest() // materializa o convidado antes de falhar a próxima chamada (loginEmail)
        lobby.failNext = OnlineException(OnlineErrorKind.API, ErrorCode.LOGIN_BLOQUEADO, 429)
        val vm = OnlineAccountViewModel(acc, lobby)
        vm.onEmail("ana@example.com")
        vm.onPassword("segredo1")
        vm.onLogin()
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineAccountProblem.LOGIN_LOCKED
    }

    // §18.7 conta bloqueada pelo operador do console: texto diferente de senha errada
    @Test
    fun `conta bloqueada avisa para falar com o suporte`() = runTest(dispatcher) {
        val acc = account("Ana")
        acc.guest() // materializa o convidado antes de falhar a próxima chamada (loginEmail)
        lobby.failNext = OnlineException(OnlineErrorKind.API, ErrorCode.CONTA_BLOQUEADA, 403)
        val vm = OnlineAccountViewModel(acc, lobby)
        vm.onEmail("ana@example.com")
        vm.onPassword("segredo1")
        vm.onLogin()
        advanceUntilIdle()
        vm.uiState.value.problem shouldBe OnlineAccountProblem.ACCOUNT_BLOCKED
    }

    // §18.1 senha curta nem chama o servidor (evita gastar uma chamada de rede à toa, como no Perfil)
    @Test
    fun `senha curta nao chama o servidor`() = runTest(dispatcher) {
        val vm = OnlineAccountViewModel(account("Ana"), lobby)
        vm.onEmail("ana@example.com")
        vm.onPassword("123")
        vm.onLogin()
        advanceUntilIdle()
        lobby.calls shouldBe emptyList()
        vm.uiState.value.busy shouldBe false
    }

    // §18.1 e-mail em branco nem chama o servidor
    @Test
    fun `e-mail em branco nao chama o servidor`() = runTest(dispatcher) {
        val vm = OnlineAccountViewModel(account("Ana"), lobby)
        vm.onPassword("segredo1")
        vm.onSignup()
        advanceUntilIdle()
        lobby.calls shouldBe emptyList()
    }

    // ---------- 3.23 sala ----------

    private fun room(id: String, acc: OnlineAccount = account("Ana")) = OnlineRoomViewModel(acc, id)

    /** Mesa do servidor com Ana sentada (anfitria, a 1a a entrar). */
    private suspend fun newTable(): String = lobby.serverTable().also { id -> account("Ana").call { api, g -> api.join(g.token, id) } }

    // §18.2 quem entra aparece sozinho (consulta a cada ~2 s)
    @Test
    fun `a lista de lugares atualiza ao vivo`() = runTest(dispatcher) {
        val id = newTable()
        val vm = room(id)
        runCurrent()
        vm.uiState.value.isHost shouldBe true
        vm.uiState.value.table!!.seats[1].kind shouldBe SeatKind.VAGO
        lobby.otherJoins(id, "Beto")
        advanceTimeBy(2_001)
        runCurrent()
        vm.uiState.value.table!!.seats[1].name shouldBe "Beto"
        vm.viewModelScope.cancel()
    }

    // §18.2 o anfitriao inicia quando quiser; os lugares livres viram robos
    @Test
    fun `anfitriao inicia com lugares livres e eles viram robos`() = runTest(dispatcher) {
        val id = newTable()
        val vm = room(id)
        runCurrent()
        vm.uiState.value.canStart shouldBe true
        vm.onStart()
        runCurrent()
        vm.uiState.value.started shouldBe true
        vm.uiState.value.table!!.seats.count { it.kind == SeatKind.ROBO } shouldBe 3
        vm.viewModelScope.cancel()
    }

    // §18.2 Entregar a mesa: escolhe outro humano da sala
    @Test
    fun `anfitriao entrega a mesa a outro jogador`() = runTest(dispatcher) {
        val id = newTable()
        val beto = lobby.otherJoins(id, "Beto")
        val vm = room(id)
        runCurrent()
        vm.uiState.value.passCandidates shouldBe listOf(1)
        vm.onAskPass()
        vm.uiState.value.choosingHost shouldBe true
        vm.onPassTo(1)
        runCurrent()
        vm.uiState.value.choosingHost shouldBe false
        vm.uiState.value.isHost shouldBe false
        lobby.tables.getValue(id).hostId shouldBe beto.id
        vm.viewModelScope.cancel()
    }

    // §18.2 o anfitriao tambem sai; o cargo passa ao proximo
    @Test
    fun `anfitriao sai e o cargo passa ao proximo`() = runTest(dispatcher) {
        val id = newTable()
        val beto = lobby.otherJoins(id, "Beto")
        val vm = room(id)
        runCurrent()
        vm.onLeave()
        runCurrent()
        vm.uiState.value.ended shouldBe OnlineRoomEnd.LEFT
        lobby.tables.getValue(id).hostId shouldBe beto.id
        vm.viewModelScope.cancel()
    }

    // §18.2 o anfitrião remove um humano
    @Test
    fun `anfitriao remove um jogador`() = runTest(dispatcher) {
        val id = newTable()
        lobby.otherJoins(id, "Beto")
        val vm = room(id)
        runCurrent()
        vm.onAskRemove(1)
        vm.uiState.value.askRemove shouldBe 1
        vm.onRemove(1)
        runCurrent()
        vm.uiState.value.table!!.seats[1].kind shouldBe SeatKind.VAGO
        vm.viewModelScope.cancel()
    }

    // §18.2 quem não é anfitrião vê a sala só para leitura e pode sair
    @Test
    fun `convidado entra, ve a sala sem poderes de anfitriao e sai`() = runTest(dispatcher) {
        val id = newTable()
        val betoSettings = FakeSettings(Settings(onlineServer = "x"))
        val beto = OnlineAccount(lobby, betoSettings) { "Beto" }
        beto.call { api, g -> api.join(g.token, id) }
        val vm = room(id, beto)
        runCurrent()
        vm.uiState.value.isHost shouldBe false
        vm.uiState.value.canStart shouldBe false
        vm.uiState.value.passCandidates shouldBe listOf(0)
        vm.uiState.value.mySeat shouldBe 1
        vm.onLeave()
        runCurrent()
        lobby.tables.getValue(id).seats[1].kind shouldBe SeatKind.VAGO
        vm.uiState.value.ended shouldBe OnlineRoomEnd.LEFT
        vm.viewModelScope.cancel()
    }

    // §18.2 o anfitrião encerra: quem está na sala é avisado
    @Test
    fun `mesa encerrada pelo anfitriao avisa quem esta na sala`() = runTest(dispatcher) {
        val id = newTable()
        val beto = OnlineAccount(lobby, FakeSettings(Settings(onlineServer = "x"))) { "Beto" }
        beto.call { api, g -> api.join(g.token, id) }
        val vm = room(id, beto)
        runCurrent()
        lobby.setState(id, TableState.ENCERRADA)
        advanceTimeBy(2_001)
        runCurrent()
        vm.uiState.value.ended shouldBe OnlineRoomEnd.CLOSED
        vm.viewModelScope.cancel()
    }

    // §18.2 removido pelo anfitrião
    @Test
    fun `jogador removido sai da sala`() = runTest(dispatcher) {
        val id = newTable()
        val beto = OnlineAccount(lobby, FakeSettings(Settings(onlineServer = "x"))) { "Beto" }
        beto.call { api, g -> api.join(g.token, id) }
        val vm = room(id, beto)
        runCurrent()
        account("Ana").call { api, g -> api.removeHuman(g.token, id, 1) }
        advanceTimeBy(2_001)
        runCurrent()
        vm.uiState.value.ended shouldBe OnlineRoomEnd.REMOVED
        vm.viewModelScope.cancel()
    }

    @Test
    fun `mesa que sumiu do servidor termina a sala`() = runTest(dispatcher) {
        val id = newTable()
        val vm = room(id)
        runCurrent()
        lobby.tables.remove(id)
        advanceTimeBy(2_001)
        runCurrent()
        vm.uiState.value.ended shouldBe OnlineRoomEnd.NOT_FOUND
        vm.viewModelScope.cancel()
    }

    // §18.4 queda de rede: a sala segue tentando e mostra o aviso
    @Test
    fun `queda de rede mantem a sala e tenta de novo`() = runTest(dispatcher) {
        val id = newTable()
        val vm = room(id)
        runCurrent()
        lobby.failAll = OnlineException(OnlineErrorKind.NETWORK)
        advanceTimeBy(2_001)
        runCurrent()
        vm.uiState.value.connectionLost shouldBe true
        vm.uiState.value.table!!.id shouldBe id
        lobby.failAll = null
        advanceTimeBy(2_001)
        runCurrent()
        vm.uiState.value.connectionLost shouldBe false
        vm.viewModelScope.cancel()
    }

    // ---------- erros em pt-BR ----------

    // §18.8 cada falha do cliente tem um problema com texto próprio
    @Test
    fun `traduz as falhas do cliente`() {
        OnlineException(OnlineErrorKind.UPDATE_APP, status = 426).toProblem() shouldBe OnlineProblem.UPDATE_APP
        OnlineException(OnlineErrorKind.MAINTENANCE, status = 503).toProblem() shouldBe OnlineProblem.MAINTENANCE
        OnlineException(OnlineErrorKind.NETWORK).toProblem() shouldBe OnlineProblem.NETWORK
        OnlineException(OnlineErrorKind.API, ErrorCode.MESA_CHEIA).toProblem() shouldBe OnlineProblem.TABLE_FULL
        OnlineException(OnlineErrorKind.API, ErrorCode.MESA_JA_INICIADA).toProblem() shouldBe OnlineProblem.ALREADY_STARTED
        OnlineException(OnlineErrorKind.API, ErrorCode.SEM_PERMISSAO).toProblem() shouldBe OnlineProblem.NOT_HOST
        OnlineException(OnlineErrorKind.API, ErrorCode.LUGARES_VAGOS).toProblem() shouldBe OnlineProblem.EMPTY_SEATS
        OnlineException(OnlineErrorKind.API, ErrorCode.PARTIDA_INDISPONIVEL).toProblem() shouldBe OnlineProblem.GAME_UNAVAILABLE
        OnlineException(OnlineErrorKind.API, ErrorCode.SALDO_INSUFICIENTE).toProblem() shouldBe OnlineProblem.INSUFFICIENT_BALANCE
        OnlineException(OnlineErrorKind.API, ErrorCode.APP_NAO_RECONHECIDO).toProblem() shouldBe OnlineProblem.APP_NOT_RECOGNIZED
    }

    @Test
    fun `o token do convidado nao aparece no toString`() {
        OnlineGuest("g1", "Ana", "segredo").toString().contains("segredo") shouldBe false
    }
}
