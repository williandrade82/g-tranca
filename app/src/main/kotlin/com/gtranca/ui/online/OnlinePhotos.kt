package com.gtranca.ui.online

import com.gtranca.online.contracts.SeatKind
import com.gtranca.online.contracts.TableView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Base64

/**
 * Fotos dos jogadores das mesas online (§18.2), por id do convidado. Cada lugar traz o caminho da foto com a versão; a
 * foto só é baixada quando o caminho muda (uma vez por jogador, de novo se ele trocar a foto). Falha de rede não apaga a
 * foto já baixada; tenta de novo na próxima atualização da mesa.
 */
class OnlinePhotos(private val account: OnlineAccount) {
    private val _photos = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val photos: StateFlow<Map<String, ByteArray>> = _photos.asStateFlow()

    /** Caminho já resolvido por convidado. */
    private val loaded = mutableMapOf<String, String>()

    suspend fun refresh(tables: List<TableView>) {
        val wanted = tables.flatMap { it.seats }.filter { it.kind == SeatKind.HUMANO && it.guestId != null }
            .associate { it.guestId!! to it.photoUrl }
        for ((guestId, url) in wanted) {
            if (loaded[guestId] == url) continue
            if (url == null) {
                loaded[guestId] = ""
                _photos.update { it - guestId }
                continue
            }
            val bytes = try {
                account.call { api, guest -> api.photo(guest.token, url) }?.let { Base64.getDecoder().decode(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            loaded[guestId] = url
            _photos.update { if (bytes != null) it + (guestId to bytes) else it - guestId }
        }
    }
}
