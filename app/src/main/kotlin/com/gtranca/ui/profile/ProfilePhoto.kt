package com.gtranca.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Recorte quadrado central de uma imagem [width] × [height]: lado e canto superior esquerdo (§14.1). */
data class SquareCrop(val left: Int, val top: Int, val side: Int) {
    companion object {
        fun center(width: Int, height: Int): SquareCrop {
            require(width > 0 && height > 0) { "Imagem vazia" }
            val side = minOf(width, height)
            return SquareCrop((width - side) / 2, (height - side) / 2, side)
        }

        /** Maior potência de 2 que, dividindo a imagem, ainda deixa o menor lado com pelo menos [target] pixels. */
        fun sampleSize(width: Int, height: Int, target: Int): Int {
            var sample = 1
            while (minOf(width, height) / (sample * 2) >= target) sample *= 2
            return sample
        }
    }
}

/** Fotos do perfil vistas pela tela (os testes usam uma versão em memória). */
interface PhotoStore {
    /** Caminho da foto gravada no perfil e do rascunho (escolhida, ainda não salva). */
    val savedPath: String
    val draftPath: String

    /** Lê a imagem [uri], recorta o centro, reduz e grava como rascunho. `false` se não deu (formato, leitura). */
    suspend fun importDraft(uri: String): Boolean

    /** O rascunho passa a ser a foto do perfil. */
    suspend fun commitDraft(): Boolean

    /** Apaga a foto do perfil. */
    suspend fun deleteSaved()
}

/**
 * §14.1 foto pessoal do jogador: guardada só no aparelho (pasta interna do app), recortada em quadrado a partir do
 * centro (o avatar a mostra em círculo) e reduzida a [SIZE] pixels.
 */
class ProfilePhoto(private val context: Context) : PhotoStore {
    override val savedPath: String get() = File(context.filesDir, SAVED).path
    override val draftPath: String get() = File(context.filesDir, DRAFT).path

    override suspend fun importDraft(uri: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val source = decode(Uri.parse(uri)) ?: return@runCatching false
            val crop = SquareCrop.center(source.width, source.height)
            val square = Bitmap.createBitmap(source, crop.left, crop.top, crop.side, crop.side)
            val scaled = if (crop.side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
            val temp = File(context.filesDir, "$DRAFT.tmp")
            temp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            val draft = File(draftPath)
            draft.delete()
            temp.renameTo(draft).also { if (it) version++ }
        }.getOrDefault(false)
    }

    override suspend fun commitDraft(): Boolean = withContext(Dispatchers.IO) {
        val draft = File(draftPath)
        if (!draft.exists()) return@withContext false
        val saved = File(savedPath)
        saved.delete()
        draft.renameTo(saved).also { if (it) version++ }
    }

    override suspend fun deleteSaved() = withContext(Dispatchers.IO) {
        if (File(savedPath).delete()) version++
    }

    /** Decodifica já reduzida (amostragem), respeitando a orientação da foto quando o sistema permite (API 28+). */
    private fun decode(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = SquareCrop.sampleSize(info.size.width, info.size.height, SIZE)
                decoder.setTargetSampleSize(sample)
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = SquareCrop.sampleSize(bounds.outWidth, bounds.outHeight, SIZE) }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    companion object {
        const val SIZE = 512
        private const val QUALITY = 88
        private const val SAVED = "profile_photo.jpg"
        private const val DRAFT = "profile_photo_draft.jpg"

        /** Muda a cada foto gravada ou apagada: os avatares relêem o arquivo. */
        var version by mutableIntStateOf(0)
            private set

        /** Caminho da foto do perfil (pode não existir). */
        fun savedPath(context: Context): String = File(context.filesDir, SAVED).path
    }
}
