package com.mantra.stopwatch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * WHO HOLDS THE MICROPHONE, AND WHEN.
 *
 * THE EVIDENCE THAT PRODUCED THIS DESIGN. On Baba's phone the meter runs while voice is off and
 * stops the moment it is switched on, and a tone goes on and off without pause the whole time
 * it is on. Those two observations together are conclusive: AudioRecord opens the microphone
 * here and works, SpeechRecognizer takes it away and then churns — started, killed, started,
 * killed — and the tone is the session boundary. The microphone was never the problem, the
 * permission was never the problem, and the meter was never the problem.
 *
 * SO THE OWNERSHIP IS INVERTED. AudioRecord holds the microphone all the time, which is why the
 * meter now runs whether voice is on or off — the thing Baba asked for and the thing that was
 * impossible while the recogniser owned it. The recogniser is a guest: it is woken when the
 * level says somebody actually spoke, it gets one session, and it hands the microphone straight
 * back.
 *
 * WHAT THAT BUYS. A session per utterance instead of four a second. If this phone plays a tone
 * at a session boundary it now plays one when you speak, which is a sound with a meaning,
 * rather than a sound that never stops. And the recogniser is listening at the moment there is
 * something to hear instead of spending its life timing out on a silent room.
 *
 * WHAT IT COSTS, said plainly: the first fraction of the word that opens the gate is spoken
 * while AudioRecord still has the microphone, so the recogniser may not hear the very start of
 * it. That is why the vocabulary has synonyms and why matching is forgiving. If it turns out to
 * clip too much, the answer is a short ring buffer handed to the recogniser, not a lower
 * threshold — a lower threshold brings the churn back.
 */
class VoiceEngine(
    private val context: Context,
    private val onLevel: (Float) -> Unit,
    private val onHeard: (String, Control?) -> Unit,
    private val onCommand: (Control) -> Unit,
    private val onState: (String) -> Unit,
    private val onDiagnostics: (Diagnostics) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private val smoother = AudioLevelSmoother()
    private val gate = SpeechGate()

    private var probe: MicProbe? = null
    private var listener: VoiceListener? = null
    private var running = false
    private var armed = false
    private var sessions = 0
    private var heardCount = 0
    private var lastError = ""

    /** The meter runs from here whether or not voice commands are armed. */
    fun startMeter() {
        if (running) return
        running = true
        probe = MicProbe(onFail = { onState(it) }).also { it.start() }
        tick()
    }

    fun stopMeter() {
        running = false
        main.removeCallbacksAndMessages(null)
        probe?.stop()
        probe = null
        closeSession()
        onLevel(smoother.reset())
    }

    /** Whether a spoken word is allowed to wake the recogniser. The meter is unaffected. */
    fun setArmed(value: Boolean) {
        armed = value
        if (!armed) closeSession()
        onState(if (armed) "listening" else "meter only")
        report()
    }

    private fun report() = onDiagnostics(
        Diagnostics(sessions = sessions, rmsCallbacks = heardCount, lastError = lastError, offline = true)
    )

    /**
     * ONE CLOCK, TTT mini's 50ms, and it never stops while the meter is up. The gate is consulted
     * here rather than inside an audio callback, so the decision to wake the recogniser happens
     * at a known rate instead of at whatever rate the microphone happens to deliver buffers.
     */
    private fun tick() {
        if (!running) return
        val p = probe
        if (p != null) {
            val level = smoother.update(p.maxAmplitude())
            onLevel(level)
            if (armed && listener == null && gate.shouldOpen(level, SystemClock.elapsedRealtime())) {
                openSession()
            }
        }
        main.postDelayed({ tick() }, AUDIO_LEVEL_SAMPLE_MS)
    }

    /**
     * The handover. AudioRecord is released BEFORE the recogniser is created, because two owners
     * of one microphone is the fault this whole design exists to avoid, and the order matters:
     * creating the recogniser first would leave a window where both want it.
     */
    private fun openSession() {
        probe?.stop()
        probe = null
        sessions++
        onState("heard something")
        report()
        listener = VoiceListener(
            context = context,
            onLevel = { /* the meter is fed by AudioRecord, never by the recogniser */ },
            onHeard = { text, control ->
                heardCount++
                onHeard(text, control)
                report()
            },
            onCommand = onCommand,
            onState = { onState(it) },
            onDiagnostics = { lastError = it.lastError },
        ).also { it.start() }

        // A session cannot be allowed to hold the microphone for ever. If the recogniser neither
        // decides nor errors, this takes it back so the meter comes alive again rather than
        // staying frozen with no indication of why.
        main.postDelayed({ closeSession() }, SESSION_MS)
    }

    private fun closeSession() {
        listener?.stop()
        listener = null
        gate.sessionEnded(SystemClock.elapsedRealtime())
        if (running && probe == null) {
            probe = MicProbe(onFail = { onState(it) }).also { it.start() }
            onState(if (armed) "listening" else "meter only")
        }
    }

    private companion object {
        /** Long enough for a word and its silence, short enough that the meter is not gone. */
        const val SESSION_MS = 2_500L
    }
}
