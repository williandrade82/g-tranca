package com.gtranca.online.contracts

import com.gtranca.engine.model.Seat
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** §18.4 / §18.8: mensagens novas do protocolo 2 (relogio de jogada, mesa encerrada, motivo do abandono). */
class ClockContractsTest {
    @Test
    fun `18_8 o protocolo subiu para 2 e a versao antiga e recusada com a mensagem em pt-BR`() {
        ONLINE_PROTOCOL_VERSION shouldBe 2
        checkProtocolVersion(1) shouldBe VersionCheck.Incompatible("Atualize o aplicativo")
    }

    @Test
    fun `18_4 mesa encerrada e troca de assento com motivo viajam em JSON`() {
        val closed: ServerMessage = TableClosed("O anfitrião encerrou a mesa.")
        val text = OnlineJson.encodeToString(ServerMessage.serializer(), closed)
        text.contains("\"type\":\"closed\"") shouldBe true
        OnlineJson.decodeFromString(ServerMessage.serializer(), text) shouldBe closed

        val seats: ServerMessage = SeatsChanged(emptyList(), 2, SeatChange.RESIGNED, AbandonReason.TEMPO)
        OnlineJson.decodeFromString(ServerMessage.serializer(), OnlineJson.encodeToString(ServerMessage.serializer(), seats)) shouldBe seats
    }

    @Test
    fun `18_4 o relogio da vez e o aviso de fim de jogo viajam em JSON`() {
        val clock = TurnClock(Seat(2), 60_000, 41_500)
        OnlineJson.decodeFromString(TurnClock.serializer(), OnlineJson.encodeToString(TurnClock.serializer(), clock)) shouldBe clock
        val notice = MatchEndedNotice(GameEndReason.ABANDONO)
        OnlineJson.decodeFromString(MatchEndedNotice.serializer(), OnlineJson.encodeToString(MatchEndedNotice.serializer(), notice)) shouldBe notice
    }
}
