package com.gtranca.online.contracts

import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class ProtocolTest {
    @Test
    fun `18_8 versao igual e compativel`() {
        checkProtocolVersion(ONLINE_PROTOCOL_VERSION) shouldBe VersionCheck.Compatible
    }

    @Test
    fun `18_8 app antigo recebe Atualize o aplicativo e servidor antigo recebe manutencao`() {
        checkProtocolVersion(ONLINE_PROTOCOL_VERSION - 1) shouldBe VersionCheck.Incompatible("Atualize o aplicativo")
        checkProtocolVersion(ONLINE_PROTOCOL_VERSION + 1) shouldBe VersionCheck.Incompatible("Servidor em manutenção")
    }

    @Test
    fun `18_8 ServerInfo vai e volta em JSON com a versao`() {
        val json = Json.encodeToString(ServerInfo("game-service"))
        Json.decodeFromString<ServerInfo>(json) shouldBe ServerInfo("game-service", ONLINE_PROTOCOL_VERSION)
    }
}
