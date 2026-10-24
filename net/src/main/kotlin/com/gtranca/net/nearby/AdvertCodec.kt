package com.gtranca.net.nearby

import com.gtranca.net.PresenceAdvert
import com.gtranca.net.TableAdvert
import java.io.ByteArrayOutputStream

/**
 * Codifica o [TableAdvert] no `endpointInfo` do Nearby (os bytes que a busca vê antes de conectar). O espaço é curto (o
 * anúncio vai também por BLE), então o formato é binário e os nomes são cortados por caractere (nunca no meio de um
 * caractere acentuado) para caber em [MAX_BYTES]. O corte só afeta o que a lista mostra: a mesa é identificada por
 * `tableId` + `hostId`, que nunca são cortados. Nada privado vai aqui (sem token, sem regras, sem cartas).
 *
 * Mesa (formato 2): `[2][flags][hostId varint][epoch varint][provisionalRank][tableId][hostName][n][n chaves][tableName]`,
 * cada texto com 1 byte de tamanho; as chaves são as presenças convidadas ([TableAdvert.invited], §16.2).
 * Presença (formato 3, §16.2): `[3][key][name]`. O primeiro byte distingue os dois anúncios. Formato desconhecido →
 * `null` (aparelho de outra versão do app fica fora da lista; a recusa clara de versão vem no `Hello`, §16.1).
 */
object AdvertCodec {
    const val FORMAT: Int = 2

    /** §16.2 formato do anúncio de presença de um jogador próximo. */
    const val PRESENCE_FORMAT: Int = 5

    /** §16.2 formato do aviso de "saindo" de um jogador próximo: `[4][key]`. */
    const val PRESENCE_LEAVING_FORMAT: Int = 4

    /** §16.2 no máximo 3 convidados (4 participantes com o anfitrião). */
    const val MAX_INVITED: Int = 3

    /** Teto de cada chave de presença ([com.gtranca.net.newPresenceKey] gera 6 caracteres). */
    const val MAX_PRESENCE_KEY_BYTES: Int = 8

    /** Teto conservador do `endpointInfo` (a documentação não fixa um número estável; o BLE é o limitante). */
    const val MAX_BYTES: Int = 100

    /** Teto do `tableId` (gerado por [newTableId] com 8 caracteres). */
    const val MAX_TABLE_ID_BYTES: Int = 16

    /** Teto do nome do anfitrião; o nome da mesa fica com o resto. */
    const val MAX_HOST_NAME_BYTES: Int = 32

    private const val FLAG_IN_PROGRESS = 1
    private const val FLAG_LISTED = 2

    fun encode(advert: TableAdvert): ByteArray {
        val id = advert.tableId.toByteArray(Charsets.UTF_8)
        require(id.size in 1..MAX_TABLE_ID_BYTES) { "tableId com tamanho inválido" }
        require(advert.hostId >= 0 && advert.epoch >= 0 && advert.provisionalRank in 0..255)
        val out = ByteArrayOutputStream()
        out.write(FORMAT)
        out.write((if (advert.inProgress) FLAG_IN_PROGRESS else 0) or (if (advert.listed) FLAG_LISTED else 0))
        out.writeVarint(advert.hostId)
        out.writeVarint(advert.epoch)
        out.write(advert.provisionalRank)
        out.writeText(id)
        out.writeText(truncateUtf8(advert.hostName, MAX_HOST_NAME_BYTES))
        require(advert.invited.size <= MAX_INVITED) { "Convidados demais" }
        out.write(advert.invited.size)
        advert.invited.forEach { key ->
            val bytes = key.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..MAX_PRESENCE_KEY_BYTES) { "Chave de presença com tamanho inválido" }
            out.writeText(bytes)
        }
        val left = MAX_BYTES - out.size() - 1
        out.writeText(truncateUtf8(advert.tableName, left.coerceAtLeast(0)))
        return out.toByteArray().also { check(it.size <= MAX_BYTES) }
    }

    /** `null` se os bytes não são um anúncio desta versão do formato (ou vieram corrompidos). */
    fun decode(bytes: ByteArray): TableAdvert? = runCatching {
        val r = Reader(bytes)
        if (r.byte() != FORMAT) return null
        val flags = r.byte()
        val hostId = r.varint()
        val epoch = r.varint()
        val rank = r.byte()
        val tableId = r.text()
        val hostName = r.text()
        val count = r.byte()
        if (count > MAX_INVITED) return null
        val invited = List(count) { r.text() }
        val tableName = r.text()
        if (tableId.isEmpty() || !r.atEnd) return null
        TableAdvert(
            tableId = tableId, tableName = tableName, hostName = hostName,
            inProgress = flags and FLAG_IN_PROGRESS != 0, listed = flags and FLAG_LISTED != 0,
            hostId = hostId, epoch = epoch, provisionalRank = rank, invited = invited,
        )
    }.getOrNull()

    /** §16.2 anúncio de presença (chave e nome, o nome cortado por caractere para caber em [MAX_BYTES]). */
    fun encodePresence(presence: PresenceAdvert): ByteArray {
        val key = presence.key.toByteArray(Charsets.UTF_8)
        require(key.size in 1..MAX_PRESENCE_KEY_BYTES) { "Chave de presença com tamanho inválido" }
        val out = ByteArrayOutputStream()
        out.write(PRESENCE_FORMAT)
        out.writeText(key)
        // §16.2 recrutado por (vazio = livre) e os convites imediatos deste jogador como anfitrião.
        out.writeText(presence.recruitedBy?.let(::keyBytes) ?: ByteArray(0))
        require(presence.inviting.size <= MAX_INVITED) { "Convites demais" }
        out.write(presence.inviting.size)
        presence.inviting.forEach { out.writeText(keyBytes(it)) }
        out.writeText(truncateUtf8(presence.name, MAX_BYTES - out.size() - 1))
        return out.toByteArray().also { check(it.size <= MAX_BYTES) }
    }

    private fun keyBytes(key: String): ByteArray = key.toByteArray(Charsets.UTF_8).also {
        require(it.size in 1..MAX_PRESENCE_KEY_BYTES) { "Chave de presença com tamanho inválido" }
    }

    /** §16.2 aviso de "saindo" (só a chave): o mesmo jogador, que está desligando o Wi-Fi do jogo. */
    fun encodePresenceLeaving(key: String): ByteArray {
        val bytes = key.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_PRESENCE_KEY_BYTES) { "Chave de presença com tamanho inválido" }
        return ByteArrayOutputStream().apply { write(PRESENCE_LEAVING_FORMAT); writeText(bytes) }.toByteArray()
    }

    /** O primeiro byte é de um anúncio de presença (comum ou "saindo"). */
    fun isPresence(bytes: ByteArray): Boolean =
        bytes.firstOrNull()?.toInt().let { it == PRESENCE_FORMAT || it == PRESENCE_LEAVING_FORMAT }

    /** `null` se os bytes não são um anúncio de presença desta versão (ex.: é o de uma mesa). */
    fun decodePresence(bytes: ByteArray): PresenceAdvert? = runCatching {
        val r = Reader(bytes)
        val format = r.byte()
        if (format == PRESENCE_LEAVING_FORMAT) {
            val key = r.text()
            if (key.isEmpty() || !r.atEnd) return null
            return PresenceAdvert(key, "", leaving = true)
        }
        if (format != PRESENCE_FORMAT) return null
        val key = r.text()
        val recruitedBy = r.text().ifEmpty { null }
        val count = r.byte()
        if (count > MAX_INVITED) return null
        val inviting = List(count) { r.text() }
        val name = r.text()
        if (key.isEmpty() || !r.atEnd) return null
        PresenceAdvert(key, name, recruitedBy = recruitedBy, inviting = inviting)
    }.getOrNull()

    /** Maior prefixo de [text] (por caractere, sem partir pares substitutos) que cabe em [maxBytes] em UTF-8. */
    fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val full = text.toByteArray(Charsets.UTF_8)
        if (full.size <= maxBytes) return full
        var end = 0
        var size = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (size + n > maxBytes) break
            size += n
            end += Character.charCount(cp)
        }
        return text.substring(0, end).toByteArray(Charsets.UTF_8)
    }

    private fun ByteArrayOutputStream.writeText(bytes: ByteArray) {
        write(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeVarint(value: Int) {
        var v = value
        while (v >= 0x80) {
            write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        write(v)
    }

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0
        val atEnd: Boolean get() = pos == bytes.size
        fun byte(): Int = bytes[pos++].toInt() and 0xFF
        fun varint(): Int {
            var shift = 0
            var result = 0
            while (true) {
                val b = byte()
                result = result or ((b and 0x7F) shl shift)
                if (b < 0x80) return result
                shift += 7
                require(shift < 32)
            }
        }
        fun text(): String {
            val n = byte()
            require(pos + n <= bytes.size)
            return String(bytes, pos, n, Charsets.UTF_8).also { pos += n }
        }
    }
}

/** Identificador novo de mesa (8 caracteres, cabe no anúncio). */
fun newTableId(random: java.util.Random = java.security.SecureRandom()): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
    return String(CharArray(8) { alphabet[random.nextInt(alphabet.length)] })
}
