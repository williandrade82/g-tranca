package com.gtranca.online.admin

import com.gtranca.online.contracts.AdminAccountDetail
import com.gtranca.online.contracts.AdminAccountsPage
import com.gtranca.online.contracts.AdminStatsDto
import com.gtranca.online.contracts.AdminTablesPage
import com.gtranca.online.contracts.TableView

/**
 * Base de teste para [LobbyAdminGateway] (Sec18.7): cada metodo nao sobrescrito erra se chamado, pra deixar
 * claro, em cada teste, o que ele espera (ou nao espera) usar do gateway - sem repetir os 9 metodos em todo
 * `object : LobbyAdminGateway { ... }`.
 */
open class FakeLobbyAdminGateway : LobbyAdminGateway {
    override suspend fun stats(): AdminStatsDto = error("nao usado neste teste")
    override suspend fun searchAccounts(query: String, limit: Int, offset: Int): AdminAccountsPage = error("nao usado neste teste")
    override suspend fun accountDetail(id: String): AdminAccountDetail? = error("nao usado neste teste")
    // ": Unit" explicito: sem isso o Kotlin infere o tipo de retorno a partir de "error(...)" (Nothing), e
    // qualquer classe que sobrescrever de novo com um corpo de verdade (retorno Unit) deixa de compilar.
    override suspend fun block(id: String, reason: String?): Unit = error("nao usado neste teste")
    override suspend fun unblock(id: String): Unit = error("nao usado neste teste")
    override suspend fun revokeSessions(id: String): Int = error("nao usado neste teste")
    override suspend fun tables(limit: Int, offset: Int): AdminTablesPage = error("nao usado neste teste")
    override suspend fun tableDetail(id: String): TableView? = error("nao usado neste teste")
    override suspend fun closeTable(id: String, reason: String?): TableView? = error("nao usado neste teste")
}
