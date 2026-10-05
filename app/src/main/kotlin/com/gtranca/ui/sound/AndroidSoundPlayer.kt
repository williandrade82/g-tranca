package com.gtranca.ui.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.EnumMap

/**
 * Toca os efeitos sintetizados ([SoundSynth]) com um `AudioTrack` estático por efeito, criado na primeira vez (ou antes,
 * em [warmUp]). Usa o volume de mídia do sistema. Qualquer falha de áudio é ignorada: o som nunca derruba o jogo.
 */
class AndroidSoundPlayer : SoundPlayer {
    @Volatile
    override var enabled: Boolean = true

    private val tracks = EnumMap<SoundEffect, AudioTrack>(SoundEffect::class.java)
    private val lastPlayed = EnumMap<SoundEffect, Long>(SoundEffect::class.java)

    /** Cria os efeitos antes do primeiro uso (chamar fora da thread principal). */
    fun warmUp() {
        SoundEffect.entries.forEach { runCatching { track(it) } }
    }

    private fun track(effect: SoundEffect): AudioTrack = synchronized(tracks) {
        tracks.getOrPut(effect) {
            val samples = SoundSynth.render(effect)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SoundSynth.RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also {
                    it.write(samples, 0, samples.size)
                    it.setVolume(effect.gain)
                }
        }
    }

    override fun play(effect: SoundEffect, force: Boolean) {
        if (!enabled && !force) return
        // Cartas em sequência rápida (distribuição) não reiniciam o mesmo som a cada quadro.
        val now = SystemClock.elapsedRealtime()
        if (now - (lastPlayed[effect] ?: 0L) < MIN_INTERVAL_MILLIS) return
        lastPlayed[effect] = now
        runCatching {
            val track = track(effect)
            track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    fun release() {
        synchronized(tracks) {
            tracks.values.forEach { runCatching { it.release() } }
            tracks.clear()
        }
    }

    private companion object {
        const val MIN_INTERVAL_MILLIS = 30L
    }
}
