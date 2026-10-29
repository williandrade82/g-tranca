package com.gtranca.ui.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.SystemClock
import com.gtranca.R
import java.util.EnumMap

/**
 * Toca os efeitos sintetizados ([SoundSynth]) com um `AudioTrack` estático por efeito, criado na primeira vez (ou antes,
 * em [warmUp]). Usa o volume de mídia do sistema. Qualquer falha de áudio é ignorada: o som nunca derruba o jogo.
 */
class AndroidSoundPlayer(context: Context) : SoundPlayer {
    private val appContext = context.applicationContext
    @Volatile
    override var enabled: Boolean = true

    private val tracks = EnumMap<SoundEffect, AudioTrack>(SoundEffect::class.java)
    private val lastPlayed = EnumMap<SoundEffect, Long>(SoundEffect::class.java)

    /** §17.8 os sons gravados das reações (MP3 em `res/raw`), carregados num `SoundPool` na primeira vez. */
    private val reactionPool: SoundPool by lazy {
        SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    }
    private val reactionIds = EnumMap<ReactionSound, Int>(ReactionSound::class.java)

    /** Cria os efeitos antes do primeiro uso (chamar fora da thread principal). */
    fun warmUp() {
        SoundEffect.entries.forEach { runCatching { track(it) } }
        ReactionSound.entries.forEach { runCatching { reactionId(it) } }
    }

    private fun reactionId(sound: ReactionSound): Int = synchronized(reactionIds) {
        reactionIds.getOrPut(sound) {
            val raw = when (sound) {
                ReactionSound.CLAPS -> R.raw.booster3_claps
                ReactionSound.KISS -> R.raw.booster3_kiss
                ReactionSound.CHICKEN -> R.raw.booster3_chicken
            }
            reactionPool.load(appContext, raw, 1)
        }
    }

    override fun playReaction(sound: ReactionSound) {
        if (!enabled) return
        // Se o som ainda carrega, o toque é ignorado (play devolve 0): o som nunca derruba nem atrasa o jogo.
        runCatching { reactionPool.play(reactionId(sound), REACTION_VOLUME, REACTION_VOLUME, 1, 0, 1f) }
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
        runCatching { reactionPool.release() }
    }

    private companion object {
        const val MIN_INTERVAL_MILLIS = 30L
        const val REACTION_VOLUME = 0.9f
    }
}
