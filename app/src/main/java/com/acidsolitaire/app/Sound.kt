package com.acidsolitaire.app

import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Synthesizes a soft glockenspiel-style bell, so no audio asset is needed. Each chime picks a
 * note from a pentatonic scale, so a run of moves tinkles pleasantly. The volume comes from the
 * Settings slider.
 */
class SoundPlayer(private val prefs: SharedPreferences, private val scope: CoroutineScope) {
    companion object {
        const val VOLUME_KEY = "soundVolume"
        private const val RATE = 44_100
        private const val TAG = "SoundPlayer"

        /** E6, G6, A6, C7, D7, E7. */
        private val NOTES = doubleArrayOf(1318.51, 1567.98, 1760.00, 2093.00, 2349.32, 2637.02)
    }

    /** The bell sounds, generated in the background as soon as the player is created. */
    private val buffers: Deferred<List<ShortArray>> = scope.async(Dispatchers.Default) { NOTES.map { makeBell(it) } }

    /**
     * One reusable track per note, created on first use. This caps the app at NOTES.size tracks no
     * matter how fast moves come in, instead of opening a new track for every chime.
     */
    private val tracks = arrayOfNulls<AudioTrack>(NOTES.size)
    private val lock = Any()
    private var released = false
    private var lastNote = -1

    private fun volume(): Float = prefs.getFloat(VOLUME_KEY, 1f).coerceIn(0f, 1f)

    fun chime() {
        var index = Random.nextInt(NOTES.size)
        if (index == lastNote) index = (index + 1) % NOTES.size
        play(index)
    }

    /** A short rising run of bells for the win screen. */
    fun fanfare() {
        scope.launch {
            for (index in listOf(0, 2, 3, 4, 5, 4, 5)) {
                play(index)
                delay(140)
            }
        }
    }

    private fun play(index: Int) {
        val level = volume()
        if (level <= 0f) return
        lastNote = index
        scope.launch(Dispatchers.Default) {
            val data = buffers.await()[index]
            synchronized(lock) {
                if (released) return@launch
                try {
                    val track = tracks[index] ?: createTrack(data).also { tracks[index] = it }
                    // Rewind a static track to the start so the note can ring again.
                    if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
                    track.reloadStaticData()
                    track.setVolume(level)
                    track.play()
                } catch (e: Exception) {
                    // Drop the broken track (e.g. after an audio device change); it is rebuilt on the next chime.
                    Log.w(TAG, "Couldn't play note $index", e)
                    tracks[index]?.release()
                    tracks[index] = null
                }
            }
        }
    }

    private fun createTrack(data: ShortArray): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        try {
            val written = track.write(data, 0, data.size)
            check(written == data.size) { "AudioTrack.write returned $written" }
        } catch (e: Exception) {
            track.release()
            throw e
        }
        return track
    }

    /** Frees every track. Call when the owner goes away; later chimes are ignored. */
    fun release() {
        synchronized(lock) {
            released = true
            tracks.forEachIndexed { i, track ->
                track?.release()
                tracks[i] = null
            }
        }
    }

    private fun makeBell(frequency: Double): ShortArray {
        val frames = (RATE * 1.6).toInt()
        // Inharmonic partials with faster-decaying overtones give a glassy bell timbre.
        val partials = listOf(
            Triple(1.0, 1.0, 3.2), Triple(2.76, 0.45, 5.5), Triple(5.40, 0.22, 8.5), Triple(8.93, 0.10, 12.0),
        ).filter { frequency * it.first < 18_000 }
        return ShortArray(frames) { frame ->
            val t = frame.toDouble() / RATE
            var value = 0.0
            for ((ratio, gain, decay) in partials) {
                value += gain * sin(2 * PI * frequency * ratio * t) * exp(-decay * t)
            }
            val attack = min(1.0, t / 0.002)
            (value * attack * 0.2 * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
