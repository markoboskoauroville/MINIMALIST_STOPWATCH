package com.mantra.stopwatch

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * THE WORD THAT PLAYS WHEN THE COUNTDOWN ENDS.
 *
 * Everything else this app records is a template: it is never played back, only compared, and
 * 16kHz is generous for comparing the shape of a word. This one is the opposite — it is never
 * compared, only played, and it is the only sound this app ever makes. So it is recorded at the
 * best rate the phone will give.
 *
 * THE RATE IS ASKED FOR, NOT ASSUMED. Every phone claims 44100 and most manage 48000, but a
 * device that supports neither exists and would fail at the moment of recording rather than
 * politely fall back. The list is tried in order and the first one that actually initialises is
 * used, which is the only way to find out.
 *
 * THE RATE IS STORED WITH THE AUDIO. A sample recorded at 48000 and played back at 44100 is a
 * word said slightly too slowly by somebody slightly too deep, and nothing about the file would
 * say why. The rate goes in the first four bytes.
 */
object GoSound {

    private val RATES = intArrayOf(48_000, 44_100, 32_000, 22_050, 16_000)

    /** However the capture behaves, this thread stops collecting after this many seconds. */
    private const val MAX_SECONDS = 6
    private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    /** The highest rate this device will actually open, discovered rather than assumed. */
    fun bestRate(): Int {
        for (rate in RATES) {
            val min = AudioRecord.getMinBufferSize(rate, CHANNEL_IN, ENCODING)
            if (min > 0) return rate
        }
        return RATES.last()
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "go.pcm")

    fun exists(context: Context): Boolean = file(context).let { it.exists() && it.length() > 8 }

    fun clear(context: Context) {
        file(context).delete()
    }

    fun save(context: Context, rate: Int, samples: ShortArray) {
        val buf = ByteBuffer.allocate(4 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(rate)
        for (s in samples) buf.putShort(s)
        file(context).writeBytes(buf.array())
    }

    /**
     * Records until the speaking stops, using the same rule as every other capture in this app so
     * that "press and say it" means one thing everywhere. The level is computed here rather than
     * borrowed from the meter, because the meter's microphone is closed while this one is open.
     */
    fun record(context: Context, onDone: (Boolean) -> Unit) {
        thread(name = "go-record", isDaemon = true) {
            val rate = bestRate()
            val min = AudioRecord.getMinBufferSize(rate, CHANNEL_IN, ENCODING)
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, CHANNEL_IN, ENCODING, min * 2)
            } catch (e: SecurityException) {
                onDone(false); return@thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release(); onDone(false); return@thread
            }

            val captured = ArrayList<Short>()
            val capture = Capture()
            val smoother = AudioLevelSmoother()
            val buffer = ShortArray(min)
            var started = 0L
            try {
                record.startRecording()
                started = System.currentTimeMillis()
                capture.begin(0)
                // Bounded by the capture, which ends on silence and gives up if nobody speaks —
                // and, because a bound a reader cannot see is not a bound, also by a hard ceiling
                // on the samples collected. A microphone thread that cannot end is a microphone
                // that stays open.
                val ceiling = rate * MAX_SECONDS
                while (captured.size < ceiling) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read <= 0) continue
                    var peak = 0
                    for (i in 0 until read) {
                        val v = abs(buffer[i].toInt())
                        if (v > peak) peak = v
                        captured.add(buffer[i])
                    }
                    val now = System.currentTimeMillis() - started
                    val state = capture.update(smoother.update(peak), now)
                    if (state == CaptureState.DONE) break
                    if (state == CaptureState.TIMED_OUT) {
                        record.stop(); record.release(); onDone(false); return@thread
                    }
                }
            } catch (e: IllegalStateException) {
                record.release(); onDone(false); return@thread
            }
            runCatching { record.stop() }
            record.release()

            val samples = ShortArray(captured.size) { captured[it] }
            if (SampleCheck.assess(downsampleForCheck(samples, rate)) != SampleQuality.GOOD) {
                onDone(false); return@thread
            }
            save(context, rate, samples)
            onDone(true)
        }
    }

    /**
     * SampleCheck works at the matcher's rate, so the quality judgement is made on a decimated
     * copy. It is judging whether a word was said, not how it sounds, and that question does not
     * need the extra bandwidth — while the sample that is KEPT is the full-rate one.
     */
    private fun downsampleForCheck(samples: ShortArray, rate: Int): ShortArray {
        if (rate <= Dsp.SAMPLE_RATE) return samples
        val step = rate.toDouble() / Dsp.SAMPLE_RATE
        val out = ShortArray((samples.size / step).toInt())
        for (i in out.indices) out[i] = samples[(i * step).toInt()]
        return out
    }

    /**
     * Plays it once, at the rate it was recorded at. Uses the ALARM stream deliberately: this
     * fires when a measurement starts and the phone is on a bench across a room, and the media
     * stream is the one people turn down.
     */
    fun play(context: Context) {
        val f = file(context)
        if (!f.exists() || f.length() <= 8) return
        val bytes = f.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rate = buf.getInt()
        if (rate !in 8_000..96_000) return
        playSamples(ShortArray((bytes.size - 4) / 2) { buf.getShort() }, rate)
    }

    /**
     * LISTENING BACK TO A TAKE, which is half of what a sampler is for.
     *
     * Until now a recording could only be compared, never heard, so the only way to find out
     * whether a take was any good was to say the word and see whether it matched — which conflates
     * a bad recording with a bad threshold, and those need completely different fixes. Hearing it
     * separates them in one press.
     *
     * The templates are at the matcher's rate rather than the Go word's, which is why the rate is
     * a parameter: playing 16kHz audio at 48kHz is a word said three times too fast, and nothing
     * about the samples would say so.
     */
    fun playSamples(samples: ShortArray, rate: Int) {
        if (samples.isEmpty() || rate !in 8_000..96_000) return
        thread(name = "go-play", isDaemon = true) {
            val min = AudioTrack.getMinBufferSize(rate, CHANNEL_OUT, ENCODING)
            if (min <= 0) return@thread
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(rate)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(min, samples.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            runCatching {
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep((samples.size * 1000L / rate) + 200L)
            }
            runCatching { track.stop() }
            track.release()
        }
    }
}
