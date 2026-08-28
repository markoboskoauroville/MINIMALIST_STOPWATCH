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
class MicProbe(private val onLevel: (Float) -> Unit, private val onFail: (String) -> Unit) {

    @Volatile private var running = false
    private val vu = Vu()

    fun start() {
        if (running) return
        running = true
        thread(name = "mic-probe", isDaemon = true) { loop() }
    }

    fun stop() {
        running = false
        onLevel(vu.reset())
    }

    private fun loop() {
        val minimum = AudioRecord.getMinBufferSize(RATE, CHANNEL, ENCODING)
        if (minimum <= 0) {
            onFail("no buffer")
            running = false
            return
        }
        // Four times the minimum: enough that a slow frame does not drop samples, small enough
        // that the meter is not showing a level from a quarter of a second ago.
        val size = minimum * 4
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
                var peak = 0
                for (i in 0 until read) {
                    val v = abs(buffer[i].toInt())
                    if (v > peak) peak = v
                }
                onLevel(vu.fromPeak(peak))
            }
        } catch (e: IllegalStateException) {
            onFail("could not start")
        } finally {
            runCatching { record.stop() }
            record.release()
            running = false
        }
    }

    private companion object {
        const val RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
