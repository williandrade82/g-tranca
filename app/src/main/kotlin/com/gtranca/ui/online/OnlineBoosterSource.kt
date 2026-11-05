package com.gtranca.ui.online

import com.gtranca.engine.BoosterCatalog
import com.gtranca.engine.BoosterDef
import com.gtranca.online.client.EconomyApi
import com.gtranca.online.client.MatchSession
import com.gtranca.ui.boosters.BoosterConfirmResult
import com.gtranca.ui.boosters.BoosterSelectionSource
import com.gtranca.ui.boosters.BoosterSourceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** O resultado de uma tentativa de Seguir, como chega pelo laço de [OnlineTableDriver.connect] (§17.3/§18.3). */
sealed interface BoosterOutcomeEvent {
    data class Confirmed(val boosters: List<String>) : BoosterOutcomeEvent
    data class Failed(val message: String) : BoosterOutcomeEvent
}

/**
 * [BoosterSelectionSource] do seletor online (docs/telas.md 3.19 "Online", §17.3/§18.3/§18.5): lê o saldo do inventário
 * ONLINE da conta (nunca o do aparelho) pela [economy] e manda a escolha pela [session] (`SelectBoosters`, rascunho a
 * cada troca e Seguir). O resultado de uma confirmação não volta direto de [confirm] — chega de fora, em [outcomes],
 * porque é o MESMO laço de eventos de [OnlineTableDriver.connect] (`BoostersConfirmed`/`BoosterConfirmFailed`) que
 * também constrói o condutor da mesa quando este assento confirma a tempo; aqui só se espera o primeiro valor dele
 * depois de mandar o Seguir.
 */
class OnlineBoosterSource(
    private val session: MatchSession,
    private val economy: EconomyApi,
    private val account: OnlineAccount,
    /** §17.2 a marcação do Inventário DO APARELHO, só para pré-preencher os nichos (a quantidade é sempre a online). */
    private val deviceSelection: suspend () -> List<String>,
    private val outcomes: Flow<BoosterOutcomeEvent>,
    /** Mostrada se [session] não tiver conexão agora ao tentar o Seguir (antes de qualquer resposta do servidor). */
    private val networkErrorMessage: String,
    override val catalog: List<BoosterDef> = BoosterCatalog.DEFAULT,
) : BoosterSelectionSource {

    override suspend fun load(): BoosterSourceState {
        val quantities = try {
            account.callWith(economy) { api, guest -> api.economyState(guest.token, guest.id) }.inventory
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
        val marked = try {
            deviceSelection()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        return BoosterSourceState(quantities, marked.filter { (quantities[it] ?: 0) > 0 })
    }

    override suspend fun draft(boosters: List<String>) {
        session.selectBoosters(boosters, final = false)
    }

    /**
     * A escuta de [outcomes] é registrada ([CoroutineStart.UNDISPATCHED], já inscrita antes de [confirm] continuar) antes
     * do Seguir ser mandado, para nunca perder a resposta que chegaria entre o envio e o início da escuta; sem conexão
     * agora, a falha é injetada direto, sem esperar nada do servidor.
     */
    override suspend fun confirm(boosters: List<String>): BoosterConfirmResult = coroutineScope {
        val result = CompletableDeferred<BoosterOutcomeEvent>()
        val listening = launch(start = CoroutineStart.UNDISPATCHED) { outcomes.collect { result.complete(it) } }
        if (!session.selectBoosters(boosters, final = true)) result.complete(BoosterOutcomeEvent.Failed(networkErrorMessage))
        val event = result.await()
        listening.cancel()
        when (event) {
            is BoosterOutcomeEvent.Confirmed -> BoosterConfirmResult.Ok(event.boosters)
            is BoosterOutcomeEvent.Failed -> BoosterConfirmResult.Failed(event.message)
        }
    }
}
