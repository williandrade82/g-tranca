package com.gtranca.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Onde fica o jogo salvo (um só por vez). */
interface SavedGameStore {
    /** O jogo salvo, ou `null` se não houver, se o arquivo estiver corrompido ou for de outra versão do formato. */
    suspend fun load(): SavedGame?

    /** Grava (substitui) o jogo salvo, de forma atômica. */
    suspend fun save(game: SavedGame)

    /** Apaga o jogo salvo (não faz nada se não houver). */
    suspend fun clear()
}

/**
 * Jogo salvo em [file] (JSON). A escrita é atômica: grava num arquivo temporário ao lado e o renomeia por cima do
 * original, então uma interrupção no meio nunca deixa um arquivo pela metade. Leitura com defeito (JSON inválido,
 * campos faltando, versão desconhecida) devolve `null` — o app segue como se não houvesse jogo salvo.
 */
class FileSavedGameStore(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SavedGameStore {

    private val mutex = Mutex()

    override suspend fun load(): SavedGame? = withContext(ioDispatcher) {
        mutex.withLock {
            if (!file.exists()) return@withLock null
            try {
                decode(file.readText())
            } catch (_: IOException) {
                null
            }
        }
    }

    override suspend fun save(game: SavedGame) = withContext(ioDispatcher) {
        mutex.withLock {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(encode(game))
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            Unit
        }
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock {
            file.delete()
            File(file.parentFile, file.name + ".tmp").delete()
            Unit
        }
    }

    companion object {
        val JSON = Json { encodeDefaults = true }

        /** Conteúdo do arquivo para [game] (JSON do formato atual). */
        fun encode(game: SavedGame): String = JSON.encodeToString(SavedGame.serializer(), game)

        /** Decodifica o conteúdo do arquivo; `null` se não for um jogo salvo válido desta versão do formato. */
        fun decode(text: String): SavedGame? = try {
            val version = JSON.parseToJsonElement(text).jsonObject["formatVersion"]?.jsonPrimitive?.intOrNull
            if (version != SavedGame.FORMAT_VERSION) null else JSON.decodeFromString(SavedGame.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            // JSON malformado, tipos errados ou `require` dos modelos do motor (estado inconsistente).
            null
        }
    }
}
