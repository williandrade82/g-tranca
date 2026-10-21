package com.gtranca.net.nearby

import com.gtranca.net.TableAdvert
import java.io.ByteArrayOutputStream

/**
 * Codifica o [TableAdvert] no `endpointInfo` do Nearby (os bytes que a busca vê antes de conectar). O espaço é curto (o
 * anúncio vai também por BLE), então o formato é binário e os nomes são cortados por caractere (nunca no meio de um
 * caractere acentuado) para caber em [MAX_BYTES]. O corte só afeta o que a lista mostra: a mesa é identificada por
 * `tableId` + `hostId`, que nunca são cortados. Nada privado vai aqui (sem token, sem regras, sem cartas).
 *
 * Formato 1: `[formato][flags][hostId varint][epoch varint][provisionalRank][tableId][hostName][tableName]`, cada texto
 * com 1 byte de tamanho. Formato desconhecido → `null` (mesa de outra versão do app fica fora da lista; a recusa clara de
 * versão vem no `Hello`, §16.2).
 */
object AdvertCodec {
    const val FORMAT: Int = 1

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
        val tableName = r.text()
        if (tableId.isEmpty()) return null
        TableAdvert(
            tableId = tableId, tableName = tableName, hostName = hostName,
            inProgress = flags and FLAG_IN_PROGRESS != 0, listed = flags and FLAG_LISTED != 0,
            hostId = hostId, epoch = epoch, provisionalRank = rank,
        )
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
