package com.gtranca.online.client

import com.gtranca.engine.RuleSet
import com.gtranca.engine.model.GameMode
import com.gtranca.engine.model.MortoStatus
import com.gtranca.engine.model.Phase
import com.gtranca.engine.model.Seat
import com.gtranca.engine.model.Side
import com.gtranca.engine.model.SideTable
import com.gtranca.online.contracts.OnlineSeatView
import com.gtranca.online.contracts.SeatInfo
import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.SeatUpdate
import com.gtranca.online.contracts.TableMode
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableStage
import com.gtranca.online.contracts.Welcome
import com.gtranca.online.contracts.ONLINE_PROTOCOL_VERSION

/** Mensagens mínimas, mas válidas, do servidor para os testes do cliente. */
object Fixtures {
    val rules = TableRules(TableMode.INDIVIDUAL, "{}")

    fun welcome(lastSeq: Long = 0) = Welcome(
        protocolVersion = ONLINE_PROTOCOL_VERSION,
        tableId = "ABC234",
        you = Seat(0),
        table = rules,
        ruleSet = RuleSet.DEFAULT,
        seats = listOf(
            SeatInfo(0, SeatKind.HUMANO, "Ana"),
            SeatInfo(1, SeatKind.ROBO, "Bolt"),
        ),
        lastSeq = lastSeq,
    )

    fun view(version: Long) = OnlineSeatView(
        version = version, roundNumber = 1, stage = TableStage.PLAYING, totals = listOf(0, 0),
        mode = GameMode.INDIVIDUAL, seat = Seat(0), side = Side(0), currentSeat = Seat(0), firstSeat = Seat(0),
        phase = Phase.AWAITING_DRAW, hand = emptyList(), handSizes = listOf(0, 0), discardPile = emptyList(),
        stockSize = 100, mortoStatus = listOf(MortoStatus.Available, MortoStatus.Available), mortoSizes = listOf(11, 11),
        redThrees = listOf(emptyList(), emptyList()), tables = listOf(SideTable(), SideTable()), turnsBegun = 0,
    )

    fun update(version: Long) = SeatUpdate(null, view(version))
}
