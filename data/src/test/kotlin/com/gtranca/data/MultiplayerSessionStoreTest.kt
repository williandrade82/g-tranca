package com.gtranca.data

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** §16.12 a sessão multijogador: gravar, ler, apagar e expirar; nunca o jogo, e separada do jogo salvo. */
class MultiplayerSessionStoreTest {
    @TempDir
    lateinit var dir: File

    private var now = 1_000_000L

    private fun store() = FileMultiplayerSessionStore(File(dir, "multiplayer_session.json"), clock = { now })

    private val session = MultiplayerSession("mesa-1", seat = 2, token = "abc123", playerName = "Bia", hostName = "Ana", wasHost = false)

    @Test
    fun `§16_12 grava e le a sessao de ida e volta, com a hora da gravacao`() = runBlocking<Unit> {
        val s = store()
        s.load().shouldBeNull()
        s.save(session)
        s.load() shouldBe session.copy(savedAtMillis = now)
    }

    @Test
    fun `§16_12 gravar de novo substitui (o token e o assento mudam) e apagar remove`() = runBlocking<Unit> {
        val s = store()
        s.save(session)
        s.save(session.copy(seat = 3, token = "novo", wasHost = true))
        s.load()!!.let { it.seat shouldBe 3; it.token shouldBe "novo"; it.wasHost shouldBe true }
        s.clear()
        s.load().shouldBeNull()
        s.clear() // apagar de novo não falha
    }

    @Test
    fun `§16_12 a sessao expira em 12 horas`() = runBlocking<Unit> {
        val s = store()
        s.save(session)
        now += MultiplayerSessionStore.MAX_AGE_MILLIS
        s.load() shouldBe session.copy(savedAtMillis = 1_000_000L) // no limite ainda vale
        now += 1
        s.load().shouldBeNull()
        File(dir, "multiplayer_session.json").exists() shouldBe false // a expirada é apagada
    }

    @Test
    fun `§16_12 arquivo ilegivel ou de outra versao vale como sem sessao, sem derrubar`() = runBlocking<Unit> {
        val file = File(dir, "multiplayer_session.json")
        file.writeText("lixo")
        store().load().shouldBeNull()
        file.writeText("""{"tableId":"m","seat":1,"token":"t","playerName":"a","hostName":"b","savedAtMillis":1000000,"formatVersion":99}""")
        store().load().shouldBeNull()
    }

    @Test
    fun `§16_12 a sessao nao leva cartas nem jogo e o token nunca aparece no texto do objeto`() {
        session.toString().contains("abc123") shouldBe false
        MultiplayerSession::class.java.declaredFields.map { it.name }.none { it.contains("match", ignoreCase = true) } shouldBe true
    }

    @Test
    fun `§16_12 fica em arquivo proprio, separado do jogo salvo contra robos`() {
        GameDataFiles.session shouldBe "multiplayer_session.json"
        (GameDataFiles.session != GameData.SAVED_GAME_FILE) shouldBe true
    }
}

private object GameDataFiles {
    val session = GameData.MULTIPLAYER_SESSION_FILE
}
