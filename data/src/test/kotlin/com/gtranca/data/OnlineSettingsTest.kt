package com.gtranca.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Test

/** Servidor online e convidado provisório nas configurações (§18.1): só no aparelho, sem mexer nos demais campos. */
class OnlineSettingsTest {
    private class MemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            mutex.withLock { transform(state.value).also { state.value = it } }
    }

    // §18.1 sem servidor informado o online fica desligado
    @Test
    fun `por padrao o servidor e vazio e nao ha convidado`() = runBlocking<Unit> {
        val s = DataStoreSettingsRepository(MemoryDataStore()).settings.first()
        s.onlineServer shouldBe ""
        s.onlineGuest shouldBe null
        s shouldBe Settings()
    }

    // §18.1 servidor gravado so no aparelho, sem espacos nas pontas
    @Test
    fun `grava o servidor sem espacos e vazio desliga`() = runBlocking<Unit> {
        val repo = DataStoreSettingsRepository(MemoryDataStore())
        repo.setOnlineServer("  meu-servidor:8080 ")
        repo.settings.first().onlineServer shouldBe "meu-servidor:8080"
        repo.setOnlineServer("   ")
        repo.settings.first().onlineServer shouldBe ""
    }

    @Test
    fun `o convidado faz ida e volta e trocar de servidor o esquece`() = runBlocking<Unit> {
        val repo = DataStoreSettingsRepository(MemoryDataStore())
        repo.setOnlineServer("a")
        repo.setOnlineGuest(OnlineGuest("g1", "Ana", "segredo"))
        repo.settings.first().onlineGuest shouldBe OnlineGuest("g1", "Ana", "segredo")
        repo.setOnlineServer("a") // mesmo servidor: mantém
        repo.settings.first().onlineGuest shouldBe OnlineGuest("g1", "Ana", "segredo")
        repo.setOnlineServer("b")
        repo.settings.first().onlineGuest shouldBe null
        repo.setOnlineGuest(OnlineGuest("g2", "Ana", "outro"))
        repo.setOnlineGuest(null)
        repo.settings.first().onlineGuest shouldBe null
    }

    @Test
    fun `o token nao aparece no toString`() {
        OnlineGuest("g1", "Ana", "segredo").toString().contains("segredo") shouldBe false
    }

    @Test
    fun `nao mexe nos outros campos`() = runBlocking<Unit> {
        val repo = DataStoreSettingsRepository(MemoryDataStore())
        repo.setSoundOn(false)
        repo.setOnlineServer("a")
        repo.settings.first() shouldBe Settings(soundOn = false, onlineServer = "a")
    }
}
