package com.gtranca.multiplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.gtranca.game.PlayerProfile
import com.gtranca.net.MAX_PHOTO_BASE64
import com.gtranca.ui.profile.ProfilePhoto
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * §16.2 a foto do perfil para a mesa multijogador: o lado maior reduzido a até [MAX_DIMENSION] px (sem esticar: mantém
 * a proporção original, nunca aumenta uma foto menor) e comprimida em JPEG, em Base64. Mostrada **inteira** na mesa de
 * lugares (docs/telas.md 3.3; antes só uma versão pequena e quadrada, cortada em círculo), por isso a qualidade e a
 * proporção da foto original importam agora, não só o tamanho do arquivo. Jogador sem foto (ou foto ilegível) não
 * envia nada.
 */
object SharedPhoto {
    const val MAX_DIMENSION: Int = 240

    fun encode(context: Context, profile: PlayerProfile?): String? {
        if (profile?.hasPhoto != true) return null
        return runCatching {
            val source = BitmapFactory.decodeFile(ProfilePhoto.savedPath(context)) ?: return null
            val scale = (MAX_DIMENSION.toFloat() / maxOf(source.width, source.height)).coerceAtMost(1f)
            val w = (source.width * scale).toInt().coerceAtLeast(1)
            val h = (source.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(source, w, h, true)
            var quality = 85
            var data: String
            do {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                data = Base64.getEncoder().encodeToString(out.toByteArray())
                quality -= 15
            } while (data.length > MAX_PHOTO_BASE64 && quality > 10)
            data.takeIf { it.length <= MAX_PHOTO_BASE64 }
        }.getOrNull()
    }
}
