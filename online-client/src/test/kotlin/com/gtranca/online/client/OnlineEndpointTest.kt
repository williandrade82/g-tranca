package com.gtranca.online.client

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OnlineEndpointTest {
    // §18.1
    @Test
    fun `aceita ip, nome e porta, ignorando esquema, espacos e barra final`() {
        OnlineEndpoint.parse(" 192.168.1.5 ", cleartext = true)!!.httpBase shouldBe "http://192.168.1.5"
        OnlineEndpoint.parse("http://Jogo.Exemplo.com:8080/", cleartext = true)!!.httpBase shouldBe "http://jogo.exemplo.com:8080"
        OnlineEndpoint.parse("https://meu-servidor", cleartext = true)!!.wsBase shouldBe "ws://meu-servidor"
    }

    // §18.1
    @Test
    fun `release usa sempre https e wss`() {
        val e = OnlineEndpoint.parse("192.168.1.5:443", cleartext = false)!!
        e.httpBase shouldBe "https://192.168.1.5:443"
        e.wsBase shouldBe "wss://192.168.1.5:443"
    }

    // §18.1
    @Test
    fun `recusa vazio, caminho, porta invalida e espacos`() {
        for (bad in listOf("", "   ", "a b", "host/caminho", "host:0", "host:99999", "host:abc", "-host", "user@host", "host?x=1")) {
            OnlineEndpoint.parse(bad, cleartext = true) shouldBe null
        }
    }

    // §18.2
    @Test
    fun `o link do convite normaliza o id`() {
        OnlineEndpoint.parse("192.168.1.5", cleartext = true)!!.inviteLink("abc234") shouldBe "http://192.168.1.5/m/ABC234"
    }
}
