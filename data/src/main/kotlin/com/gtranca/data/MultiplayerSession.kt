package com.gtranca.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * §16.12 a **sessão** multijogador de um jogador comum (nunca o jogo): a mesa, o assento, o token, o nome do jogador, o
 * nome do anfitrião (só para exibir) e se ele era o anfitrião (que volta como jogador comum, §16.12). Serve ao
 * reingresso pelo token (§16.5) a partir de "Continuar".
 */
@Serializable
data class MultiplayerSession(
    val tableId: String,
    val seat: Int,
    val token: String,
    val playerName: String,
    val hostName: String,
    val wasHost: Boolean = false,
    /** Relógio de parede (ms) em que foi gravada: a sessão vale [MultiplayerSessionStore.MAX_AGE_MILLIS]. */
    val savedAtMillis: Long = 0,
    val formatVersion: Int = FORMAT_VERSION,
) {
    override fun toString(): String = "MultiplayerSession(table=$tableId, seat=$seat)" // nunca registrar o token

    companion object {
        const val FORMAT_VERSION: Int = 1
    }
}

/** Onde fica a sessão multijogador (uma só por vez), separada do jogo salvo contra robôs (§16.12). */
interface MultiplayerSessionStore {
    /** A sessão guardada, ou `null` se não há, se expirou (e então é apagada), se está corrompida ou é de outra versão. */
    suspend fun load(): MultiplayerSession?

    /** Grava (substitui) a sessão, carimbando a hora. */
    suspend fun save(session: MultiplayerSession)

    /** Apaga a sessão (não faz nada se não houver). */
    suspend fun clear()

    companion object {
        /** §16.12 depois disto a mesa já não existe: a sessão é descartada. */
        const val MAX_AGE_MILLIS: Long = 12 * 60 * 60 * 1000L
    }
}

/** A sessão em [file] (JSON), escrita de forma atômica como o jogo salvo. */
class FileMultiplayerSessionStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = MultiplayerSessionStore.MAX_AGE_MILLIS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MultiplayerSessionStore {
    private val mutex = Mutex()

    override suspend fun load(): MultiplayerSession? = withContext(ioDispatcher) {
        mutex.withLock {
            if (!file.exists()) return@withLock null
            val session = try {
                JSON.decodeFromString(MultiplayerSession.serializer(), file.readText())
            } catch (_: IOException) {
                return@withLock null
            } catch (_: Exception) {
                file.delete() // ilegível: como se não houvesse
                return@withLock null
            }
            val age = clock() - session.savedAtMillis
            if (session.formatVersion != MultiplayerSession.FORMAT_VERSION || age > maxAgeMillis || age < -maxAgeMillis) {
                file.delete()
                return@withLock null
            }
            session
        }
    }

    override suspend fun save(session: MultiplayerSession) = withContext(ioDispatcher) {
        mutex.withLock {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            try {
                FileOutputStream(temp).use { out ->
                    out.write(JSON.encodeToString(MultiplayerSession.serializer(), session.copy(savedAtMillis = clock())).toByteArray(Charsets.UTF_8))
                    out.flush()
                    out.fd.sync()
                }
                try {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (error: IOException) {
                temp.delete()
                throw error
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

    private companion object {
        val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
