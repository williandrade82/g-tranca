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
 * §16.2 a foto do perfil para a mesa multijogador: reduzida a [SIZE] px e comprimida em JPEG (alvo de até ~15 KB), em
 * Base64. Jogador sem foto (ou foto ilegível) não envia nada.
 */
object SharedPhoto {
    const val SIZE: Int = 96

    fun encode(context: Context, profile: PlayerProfile?): String? {
        if (profile?.hasPhoto != true) return null
        return runCatching {
            val source = BitmapFactory.decodeFile(ProfilePhoto.savedPath(context)) ?: return null
            val scaled = Bitmap.createScaledBitmap(source, SIZE, SIZE, true)
            var quality = 80
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
