package com.paintbymoonlight.ui.paint

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The sky the painting plays in: the Sun from 6 a.m. to 7 p.m. by the phone's clock, the Moon
 * the rest (iOS's `Sky`, its Music setting at Auto).
 */
enum class Sky {
    Sun, Moon;

    companion object {
        fun now(): Sky = if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 6 until 19) Sun else Moon
    }
}

/**
 * The tune painting plays, as iOS's `PaintingMelody`: each fill the next note whatever paint
 * it was, six eight-note phrases that rise, answer and settle home, then start over; after a
 * five-second pause it picks up at its next phrase, and the sky turns only where a phrase begins.
 */
class Melody {
    class Note(val sky: Sky, val index: Int, val endsPhrase: Boolean)

    private var position = 0
    /** The most recently played note (the tune's first before any). */
    var latest = TUNE[0]
        private set
    var sky: Sky? = null
        private set
    private var lastPlayed: Long? = null

    /** The sky a fill's note at [now] (ms) plays in, with [requested] the sky of the moment. */
    fun skyOfNote(now: Long, requested: Sky): Sky = if (place(now) % PHRASE == 0) requested else sky ?: requested

    fun next(now: Long, requested: Sky): Note {
        val current = skyOfNote(now, requested)
        position = place(now)
        sky = current
        lastPlayed = now
        latest = TUNE[position]
        val note = Note(current, latest, (position + 1) % PHRASE == 0)
        position = (position + 1) % TUNE.size
        return note
    }

    private fun place(now: Long): Int {
        val last = lastPlayed ?: return position
        if (now - last < BREATH_MS || position % PHRASE == 0) return position
        return (position / PHRASE + 1) * PHRASE % TUNE.size
    }

    companion object {
        val TUNE = intArrayOf(
            2, 3, 4, 3, 2, 1, 0, 1,
            2, 3, 4, 5, 4, 3, 2, 3,
            4, 5, 6, 7, 6, 5, 4, 5,
            6, 7, 8, 7, 6, 5, 4, 3,
            2, 3, 4, 3, 2, 1, 2, 3,
            5, 4, 3, 2, 1, 2, 1, 0,
        )
        const val PHRASE = 8
        const val BREATH_MS = 5000L
    }
}

/**
 * The painting's sounds, synthesized as iOS's `ToneSynth` makes them (no audio files ship): by
 * Sun a kalimba, by Moon a celesta-like bell lower and minor that twinkles where a phrase rests;
 * a wooden thock for a wrong paint; three rising notes for a finished color; a run and a chord
 * for the finished painting. Written once to the cache as WAV and played through a SoundPool as
 * sonification, quiet while the phone's ringer is silenced (iOS's ambient session keeps to the
 * silent switch).
 */
class Sounds(context: Context) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val pool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val loaded = HashSet<Int>()
    private var released = false
    private val ids = HashMap<String, Int>()
    val melody = Melody()
    private var lastNote = 0L

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
    }

    /** Synthesizes what is missing from the cache and loads it; call off the main thread. */
    fun prepare() {
        val dir = File(app.cacheDir, "sounds-v1").apply { mkdirs() }
        val voices = buildMap {
            SUN.forEachIndexed { i, f -> put("sun$i") { kalimba(f) } }
            MOON.forEachIndexed { i, f -> put("moon$i") { celesta(f) } }
            MOON.forEachIndexed { i, f -> put("twinkle$i") { twinkle(2 * f) } }
            put("thud") { thud() }
        }
        val files = voices.map { (name, make) ->
            val file = File(dir, "$name.wav")
            if (!file.exists()) {
                val temp = File(dir, "$name.tmp")
                temp.writeBytes(wav(make()))
                temp.renameTo(file)
            }
            name to file
        }
        main.post {
            if (!released) files.forEach { (name, file) -> if (name !in ids) ids[name] = pool.load(file.path, 1) }
        }
    }

    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        pool.release()
    }

    private fun play(name: String, volume: Float, delayMs: Long = 0) {
        if (released || audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val id = ids[name]?.takeIf { it in loaded } ?: return
        // iOS's mixer plays everything at 0.8.
        val v = (volume * 0.8f).coerceIn(0f, 1f)
        if (delayMs <= 0) pool.play(id, v, v, 1, 0, 1f) else main.postDelayed({ pool.play(id, v, v, 1, 0, 1f) }, delayMs)
    }

    private fun note(sky: Sky, index: Int, volume: Float, delayMs: Long = 0) =
        play(if (sky == Sky.Sun) "sun$index" else "moon$index", volume, delayMs)

    private fun level(sky: Sky) = if (sky == Sky.Sun) 1f else 0.85f

    /** The sky the next fill sounds in: the fill's sparkles turn to stars with it. */
    fun skyOfComingFill(): Sky = melody.skyOfNote(SystemClock.uptimeMillis(), Sky.now())

    /** A fill, [size] 0…1 of the painting: the tune's next note, at most one every 40 ms. */
    fun paint(size: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastNote < 40) return
        lastNote = now
        val n = melody.next(now, Sky.now())
        val volume = (0.18f + 0.22f * (0.35f + 0.65f * size.coerceIn(0f, 1f))) * level(n.sky)
        note(n.sky, n.index, volume)
        if (n.sky == Sky.Moon && n.endsPhrase) play("twinkle${n.index}", volume * 0.35f, 120)
    }

    fun reject() = play("thud", 0.35f)

    fun colorComplete() {
        val sky = melody.sky ?: Sky.now()
        val root = min(melody.latest, SUN.size - 5)
        val (start, step) = if (sky == Sky.Sun) 120L to 85L else 140L to 120L
        listOf(0, 2, 4).forEachIndexed { k, interval -> note(sky, root + interval, 0.3f * level(sky), start + step * k) }
    }

    fun celebrate() {
        val sky = melody.sky ?: Sky.now()
        val count = SUN.size
        if (sky == Sky.Sun) {
            for (i in 0 until count) note(Sky.Sun, i, 0.22f + 0.01f * i, 70L * i)
            for (i in listOf(5, 7, 9, 11)) note(Sky.Sun, i, 0.28f, 70L * count + 120)
        } else {
            val soft = level(Sky.Moon)
            (count - 1 downTo 0).forEachIndexed { k, i -> note(Sky.Moon, i, (0.24f - 0.005f * k) * soft, 100L * k) }
            val chord = 100L * count + 150
            for (i in listOf(0, 1, 3, 4)) note(Sky.Moon, i, 0.26f * soft, chord)
            play("twinkle5", 0.2f, chord + 350)
        }
    }

    private companion object {
        const val RATE = 44100
        /** D major pentatonic over two octaves from D4; the Moon's the same notes as their minor, from the B below. */
        val SUN = doubleArrayOf(293.66, 329.63, 369.99, 440.00, 493.88, 587.33, 659.26, 739.99, 880.00, 987.77, 1174.66, 1318.51)
        val MOON = doubleArrayOf(246.94) + SUN.copyOf(SUN.size - 1)

        /** SplitMix64, as PaintCore draws: the same noise as iOS's for the same seed. */
        class Noise(private var state: Long) {
            fun next(): Long {
                state += -0x61c8864680b583ebL
                var z = state
                z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
                z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
                return z xor (z ushr 31)
            }
            /** −1…1. */
            fun unit(): Double = (next() ushr 11).toDouble() / (1L shl 53).toDouble() * 2 - 1
        }

        fun samples(length: Double, voice: (t: Double) -> Double): FloatArray {
            val n = (RATE * length).toInt()
            return FloatArray(n) { voice(it.toDouble() / RATE).toFloat() }
        }

        /** The Sun's plucked tine: fundamental, octave, a bright fast tine and a soft brush onset. */
        fun kalimba(f: Double): FloatArray {
            val length = 0.75
            val noise = Noise((f * 1000).toLong())
            var lowpassed = 0.0
            return samples(length) { t ->
                val attack = min(1.0, t / 0.003)
                val body = sin(2 * PI * f * t) * exp(-t * 6.5)
                val octave = 0.24 * sin(2 * PI * 2 * f * t) * exp(-t * 10)
                val tine = 0.26 * sin(2 * PI * 5.4 * f * t) * exp(-t * 30)
                lowpassed += 0.25 * (noise.unit() - lowpassed)
                val brush = 0.08 * lowpassed * exp(-t * 45)
                val tail = min(1.0, (length - t) / 0.05)
                (attack * (body + octave + tine) + brush) * tail * 0.55
            }
        }

        /** The Moon's bell: a warm fundamental and octave under a struck bar's ring, a touch uneven. */
        fun celesta(f0: Double): FloatArray {
            val length = 1.6
            val noise = Noise((f0 * 1000).toLong() xor 0x6D6F6F6E)
            val f = f0 * (1 + 0.0018 * noise.unit())
            val strike = 1 + 0.12 * noise.unit()
            var lowpassed = 0.0
            return samples(length) { t ->
                val attack = min(1.0, t / 0.004)
                val body = sin(2 * PI * f * t) * exp(-t * 2.4)
                val octave = 0.26 * sin(2 * PI * 2 * f * t) * exp(-t * 4.5)
                val ring = strike * (0.14 * sin(2 * PI * 2.756 * f * t) * exp(-t * 11) + 0.05 * sin(2 * PI * 5.404 * f * t) * exp(-t * 24))
                lowpassed += 0.2 * (noise.unit() - lowpassed)
                val felt = 0.06 * lowpassed * exp(-t * 70)
                val tail = min(1.0, (length - t) / 0.25)
                (attack * (body + octave + ring) + felt) * tail * 0.5
            }
        }

        /** A star's twinkle over the bell: a quiet, pure ping. */
        fun twinkle(f: Double): FloatArray {
            val length = 0.8
            return samples(length) { t ->
                val attack = min(1.0, t / 0.006)
                val ping = sin(2 * PI * f * t) * exp(-t * 5) + 0.1 * sin(2 * PI * 2.756 * f * t) * exp(-t * 18)
                attack * ping * min(1.0, (length - t) / 0.15) * 0.5
            }
        }

        /** A muted wooden thock. */
        fun thud(): FloatArray = samples(0.18) { t ->
            val pitch = 180 * (1 - 0.25 * min(1.0, t / 0.08))
            min(1.0, t / 0.002) * sin(2 * PI * pitch * t) * exp(-t * 32) * 0.6
        }

        /** 16-bit mono PCM in a WAV file. */
        fun wav(samples: FloatArray): ByteArray {
            val data = samples.size * 2
            val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
            b.put("data".toByteArray()).putInt(data)
            for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
            return b.array()
        }
    }
}
