package com.mantra.stopwatch

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * DOES ANY AUDIO REACH THIS APP AT ALL.
 *
 * This is not part of the voice commands. It exists because "audio is still not entering the
 * application" is a sentence that covers four completely different faults, and from outside the
 * phone they are indistinguishable:
 *
 *   1. the RECORD_AUDIO permission was never actually granted
 *   2. the microphone is held by something else, or the OEM is refusing it
 *   3. audio arrives fine and SpeechRecognizer is the broken part
 *   4. audio arrives, recognition works, and the words do not match
 *
 * SpeechRecognizer cannot tell them apart, because its rms callback only fires once it has
 * already got as far as opening the microphone. If it never gets there, a dead meter looks
 * exactly like a dead microphone. THIS CLASS ANSWERS 1 AND 2 ON THEIR OWN, by opening the
 * microphone directly and reading sample peaks — which is what TTT mini does, and the reason its
 * meter has never had this problem.
 *
 * IT CANNOT RUN AT THE SAME TIME AS THE RECOGNISER. One microphone, one owner. So the tester
 * runs this only while voice commands are switched OFF, and that constraint is the whole design:
 * turn listening off, speak, and if the bar moves then audio reaches this app and the fault is
 * further down the chain. If the bar does not move, nothing after it can possibly work and there
 * is no point looking at the recogniser at all.
 *
 * The level maths is AudioLevelSmoother, unchanged, fed the way TTT mini feeds it: a PCM16 peak
 * rather than the recogniser's undocumented rmsdB.
 */
class MicProbe(private val onFail: (String) -> Unit) {

    @Volatile private var running = false

    /**
     * THE RING OF RECENT AUDIO, and it is what makes template matching possible at all.
     *
     * TTT mini's RecordingController streams every frame straight to a file, because it is
     * recording something a person asked it to record. Here nothing is being recorded: the
     * question is "what were the last two seconds", asked after a word has already been spoken.
     * So the same capture loop writes into a ring instead of a file, and the last two seconds
     * are always available without anything having been started first.
     *
     * Two seconds because a command is under one, and the gate only notices a word once it is
     * already underway — so the ring has to reach back far enough to include the beginning of a
     * word whose middle is what woke us up. That reaching back is the fix for the clipped
     * first syllable that the previous design could not avoid.
     */
    private val ring = ShortArray(RING)
    @Volatile private var written = 0L

    /** The last [ms] milliseconds, oldest first. Safe to call from any thread. */
    fun recent(ms: Int): ShortArray {
        val want = minOf((SAMPLE_RATE * ms / 1000), RING, written.toInt())
        if (want <= 0) return ShortArray(0)
        val out = ShortArray(want)
        val end = (written % RING).toInt()
        for (i in 0 until want) {
            var idx = end - want + i
            if (idx < 0) idx += RING
            out[i] = ring[idx]
        }
        return out
    }

    fun clearRing() {
        written = 0
    }

    /**
     * The peak seen since the last read, exactly as TTT mini's RecordingController.maxAmplitude
     * works. THE UI PULLS ON ITS OWN CLOCK; the audio thread only records the highest sample it
     * has seen. That is the difference between a meter whose speed is a decision and one whose
     * speed is whatever the buffer size happened to be, which is the lag Baba is seeing.
     */
    @Volatile private var peak = 0

    fun maxAmplitude(): Int {
        val p = peak
        peak = 0
        return p
    }

    fun start() {
        if (running) return
        running = true
        thread(name = "mic-probe", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        peak = 0
    }

    private fun loop() {
        val minimum = AudioRecord.getMinBufferSize(RATE, CHANNEL, ENCODING)
        if (minimum <= 0) {
            onFail("no buffer")
            running = false
            return
        }
        // The MINIMUM, not four times it. A larger buffer means each read covers more time, and
        // the meter then cannot answer faster than one buffer however often the UI asks. At 16kHz
        // the minimum is a few tens of milliseconds, which is under the 50ms sampling clock, so
        // the clock is what decides the speed rather than the audio path.
        val size = minimum
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, CHANNEL, ENCODING, size)
        } catch (e: SecurityException) {
            // The permission is the commonest cause and it is worth naming rather than reporting
            // a generic failure, because it is the one a person can fix in ten seconds.
            onFail("permission refused")
            running = false
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onFail("microphone busy")
            record.release()
            running = false
            return
        }

        val buffer = ShortArray(size / 2)
        try {
            record.startRecording()
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    // A negative read is an error code, not a quiet room. Saying so stops this
                    // looking like silence, which is the failure being investigated.
                    if (read < 0) onFail("read failed $read")
                    continue
                }
                var high = 0
                for (i in 0 until read) {
                    val v = abs(buffer[i].toInt())
                    if (v > high) high = v
                }
                // Highest since the UI last looked, not since this buffer started.
                if (high > peak) peak = high

                // And into the ring, so the last two seconds can always be asked for.
                for (i in 0 until read) {
                    ring[((written + i) % RING).toInt()] = buffer[i]
                }
                written += read
            }
        } catch (e: IllegalStateException) {
            onFail("could not start")
        } finally {
            runCatching { record.stop() }
            record.release()
            running = false
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Two seconds. Long enough to contain a word whose middle woke the gate. */
        const val RING = SAMPLE_RATE * 2

        const val RATE = SAMPLE_RATE
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
