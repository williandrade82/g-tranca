package com.gtranca.online.admin

import com.gtranca.online.contracts.AdminAdjustRequest
import com.gtranca.online.contracts.AdminLedgerPage
import com.gtranca.online.contracts.AdminMigrationsPage
import com.gtranca.online.contracts.AdminWalletDto

/**
 * Base de teste para [EconomyAdminGateway] (Sec18.7): cada metodo nao sobrescrito erra se chamado, pra deixar
 * claro, em cada teste, o que ele espera (ou nao espera) usar do gateway - mesmo padrao do [FakeLobbyAdminGateway].
 */
open class FakeEconomyAdminGateway : EconomyAdminGateway {
    override suspend fun wallet(id: String): AdminWalletDto? = error("nao usado neste teste")
    override suspend fun ledger(id: String, limit: Int, offset: Int): AdminLedgerPage? = error("nao usado neste teste")
    override suspend fun adjust(id: String, request: AdminAdjustRequest): AdminWalletDto = error("nao usado neste teste")
    override suspend fun migrations(limit: Int, offset: Int): AdminMigrationsPage = error("nao usado neste teste")
}
