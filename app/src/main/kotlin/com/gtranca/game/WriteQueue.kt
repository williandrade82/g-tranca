package com.gtranca.game

import android.content.Context
import android.util.Log
import com.gtranca.ai.Difficulty
import com.gtranca.data.GameData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fila única de gravações do processo, executadas uma de cada vez, na ordem em que entraram, num escopo que não
 * morre com as telas: sair do jogo não descarta gravações pendentes e o fim do jogo (§13.1) não é cortado no meio.
 * Uma gravação que falha (disco cheio, E/S) é só registrada em [onError]: a fila segue viva e o jogo continua.
 */
class WriteQueue(scope: CoroutineScope, private val onError: (Throwable) -> Unit = {}) {

    private class Job(val block: suspend () -> Unit, val done: CompletableDeferred<Boolean>?)

    private val jobs = Channel<Job>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in jobs) {
                val ok = try {
                    withContext(NonCancellable) { job.block() }
                    true
                } catch (error: Exception) {
                    // Falha da gravação (inclusive um cancelamento de dentro dela): registra e segue para a próxima.
                    onError(error)
                    false
                }
                job.done?.complete(ok)
            }
        }
    }

    /** Põe [block] na fila, sem esperar. */
    fun enqueue(block: suspend () -> Unit) {
        jobs.trySend(Job(block, null))
    }

    /** Põe [block] na fila e espera ele (e tudo o que estava antes) terminar; `false` se ele falhou. */
    suspend fun run(block: suspend () -> Unit): Boolean {
        val done = CompletableDeferred<Boolean>()
        jobs.send(Job(block, done))
        return done.await()
    }

    /** Lê depois das gravações pendentes (ex.: o jogo salvo ao voltar ao início); `null` se a leitura falhar. */
    suspend fun <T> read(block: suspend () -> T): T? {
        var result: T? = null
        run { result = block() }
        return result
    }

    companion object {
        private const val TAG = "GTranca"

        /** A fila do processo (escopo da aplicação, em IO). */
        val app: WriteQueue by lazy {
            WriteQueue(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { error ->
                if (error !is CancellationException) Log.w(TAG, "Falha ao gravar", error)
            }
        }
    }
}

/** Persistência do app, que só aceita jogos salvos com uma dificuldade conhecida. */
fun appData(context: Context): GameData =
    GameData.get(context) { id -> Difficulty.entries.any { it.id == id } }
