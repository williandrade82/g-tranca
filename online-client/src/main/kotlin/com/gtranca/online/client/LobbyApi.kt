package com.gtranca.online.client

import com.gtranca.online.contracts.GuestIdentity
import com.gtranca.online.contracts.TableRules
import com.gtranca.online.contracts.TableView

/**
 * Operações do lobby (mesas, §18.2). Todas lançam [OnlineException]. Existe como interface para as telas e os
 * ViewModels serem testados com um servidor falso em memória.
 */
interface LobbyApi {
    /** Identidade provisória de convidado (a fase 2 troca por login Google). O token só aparece nesta resposta. */
    suspend fun createGuest(name: String): GuestIdentity

    suspend fun createTable(token: String, rules: TableRules): TableView

    suspend fun table(token: String, tableId: String): TableView

    /** [seat] `null` = primeiro lugar vago. Idempotente para quem já está na mesa. */
    suspend fun join(token: String, tableId: String, seat: Int? = null): TableView

    suspend fun leave(token: String, tableId: String): TableView

    /** Anfitrião: lugar vago vira robô. */
    suspend fun seatRobot(token: String, tableId: String, seat: Int): TableView

    /** Anfitrião: remove um humano (o lugar fica vago). */
    suspend fun removeHuman(token: String, tableId: String, seat: Int): TableView

    /** Anfitrião: inicia (todos os lugares preenchidos). */
    suspend fun start(token: String, tableId: String): TableView

    /** Anfitrião: encerra a mesa (com jogo em andamento conta como abandono dele, §18.4). */
    suspend fun close(token: String, tableId: String): TableView
}
