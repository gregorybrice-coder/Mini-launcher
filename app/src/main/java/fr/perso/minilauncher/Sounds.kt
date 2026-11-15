package fr.perso.minilauncher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool

/** Sons courts via SoundPool (faible latence). Respecte le mode silencieux. */
class Sounds(ctx: Context) {
    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val idTick = pool.load(ctx, R.raw.defilement_cliquetis, 1)
    private val idOpen = pool.load(ctx, R.raw.groupe_pop_ouverture, 1)
    private val idClose = pool.load(ctx, R.raw.groupe_pop_fermeture, 1)
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var enabled: Boolean = true

    private fun play(id: Int, volume: Float, rate: Float) {
        if (!enabled || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        pool.play(id, volume, volume, 1, 0, rate)
    }

    fun tick(rate: Float = 1f) = play(idTick, 0.5f, rate)
    fun open() = play(idOpen, 0.8f, 1f)
    fun close() = play(idClose, 0.8f, 1f)

    fun release() = pool.release()
}
