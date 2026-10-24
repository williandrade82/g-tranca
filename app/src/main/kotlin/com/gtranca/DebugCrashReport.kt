package com.gtranca

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Só no APK debug: grava o erro que fechou o app e o mostra na próxima abertura, com Copiar/Compartilhar, para
 * diagnosticar testes em celulares reais sem cabo USB. Nada é enviado sozinho; o arquivo fica no próprio aparelho.
 */
object DebugCrashReport {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(app).writeText("Thread: ${thread.name}\n${error.stackTraceToString()}") }
            previous?.uncaughtException(thread, error)
        }
    }

    fun showPending(activity: Activity) {
        if (!BuildConfig.DEBUG) return
        val f = file(activity)
        val text = runCatching { f.readText() }.getOrNull() ?: return
        f.delete()
        AlertDialog.Builder(activity)
            .setTitle("O app fechou na última vez")
            .setMessage(text.take(4000))
            .setPositiveButton("Compartilhar") { _, _ ->
                activity.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null),
                )
            }
            .setNeutralButton("Copiar") { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("erro", text))
            }
            .setNegativeButton("Fechar", null)
            .show()
    }
}
