package com.mantra.stopwatch

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * WHO HOLDS THE MICROPHONE, AND WHAT LISTENS TO IT.
 *
 * SPEECHRECOGNIZER IS GONE, AND SO IS THE TONE. Five versions of evidence say the recogniser does
 * not work on this phone: AudioRecord opens the microphone and delivers audio every time, and
 * every time that microphone is handed to the recogniser it churns, plays a tone at each session
 * boundary, and recognises nothing. There was no fifth thing left to try inside that API.
 *
 * The tone was never something this app played. It was the recogniser being started and stopped.
 * With no recogniser there are no sessions, so there is nothing to make a sound.
 *
 * WHAT LISTENS NOW. AudioRecord owns the microphone permanently — which is why the meter runs
 * whether commands are armed or not — and keeps the last two seconds in a ring. When the level
 * says a word was spoken, those two seconds are compared against the three recordings Baba made
 * of himself saying each command. Closest wins, if it is close enough and clearly better than
 * the runner-up.
 *
 * REACHING BACK IS THE POINT OF THE RING. The gate only notices a word once it is already
 * underway, so anything that starts listening at that moment has already missed the beginning.
 * Asking the ring for the last two seconds includes the part that woke it.
 */
class VoiceEngine(
    private val onLevel: (Float) -> Unit,
    private val onHeard: (Control?, List<Pair<Control, Double>>) -> Unit,
    private val onCommand: (Control) -> Unit,
    private val onState: (String) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private val smoother = AudioLevelSmoother()
    private val gate = SpeechGate()
    private val matcher = TemplateMatcher()

    /**
     * The chosen names. Held here because the matcher is here, and read through Vocabulary so a
     * rename cannot become a second list of sayable things sitting beside the enum.
     */
    private var names: Map<Control, String> = emptyMap()

    fun setNames(value: Map<Control, String>) {
        names = value
    }

    private var probe: MicProbe? = null
    private var running = false
    private var armed = false
    private var templates: List<Template> = emptyList()

    /** The recordings, refeatured whenever one is re-recorded. */
    fun setTemplates(value: List<Template>) {
        templates = value
        onState(if (value.size < Control.entries.size) "record all three commands" else "ready")
    }

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
        onLevel(smoother.reset())
    }

    fun setArmed(value: Boolean) {
        armed = value
        onState(if (armed) "listening" else "meter only")
    }


    /** The raw ring, for the recorder in settings. */
    fun recent(ms: Int): ShortArray = probe?.recent(ms) ?: ShortArray(0)

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // CAPTURE. Press a pad and this listens: it waits for the word, records while it lasts, and
    // stops when it ends. There is no arm button and no fixed length, because a fixed length is
    // a worse recording in both directions — it keeps the silence you left if you were quick and
    // it cuts you off if you were not.
    //
    // While a capture is running, matching is suspended. A word lit by the sample being recorded
    // would be a light that means nothing.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    // ─────────────────────────────────────────────────────────────────────────────────────────
    // RECORDING IS MANUAL. Press to start, press again to stop.
    //
    // It used to end itself on silence, which is clever and was wrong. A capture that decides when
    // you have finished speaking will sometimes decide it during the pause in the middle of a word
    // and sometimes not until a lorry has gone past, and either way you are left holding a
    // recording whose boundaries you did not choose. Worse, the failure is invisible: the line
    // fills, the waveform looks plausible, and the template is half a word.
    //
    // Two presses is one more press. It is also the only arrangement where what was recorded is
    // exactly what was intended, and this is the one place in the app where being sure matters
    // more than being quick, because everything downstream is compared against it.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    private var capturingSince = 0L
    private var onCaptured: ((ShortArray?) -> Unit)? = null

    fun startCapture(done: (ShortArray?) -> Unit) {
        capturingSince = SystemClock.elapsedRealtime()
        onCaptured = done
    }

    /** The second press. Hands back everything recorded since the first one. */
    fun finishCapture() {
        val since = capturingSince
        val done = onCaptured
        capturingSince = 0L
        onCaptured = null
        if (done == null || since == 0L) return
        val ms = (SystemClock.elapsedRealtime() - since).toInt()
        done(if (ms < MIN_CAPTURE_MS) null else probe?.recent(ms))
    }

    fun cancelCapture() {
        capturingSince = 0L
        onCaptured = null
    }

    val capturing: Boolean get() = capturingSince != 0L

    /**
     * ONE CLOCK, TTT mini's 50ms, and it never stops while the meter is up. Everything happens
     * here: the level for the meter, and the decision about whether the last two seconds are
     * worth comparing. Nothing runs on the audio thread except filling the ring.
     */
    private fun tick() {
        if (!running) return
        val p = probe
        if (p != null) {
            val level = smoother.update(p.maxAmplitude())
            onLevel(level)
            val now = SystemClock.elapsedRealtime()

            if (capturing) {
                // A ceiling, because a recording nobody stopped must not run on until the ring
                // wraps and starts eating its own beginning. At the ceiling it ends itself and
                // keeps what it has — the one automatic decision left, and the only one that
                // cannot lose anything somebody meant to keep.
                if (now - capturingSince >= MAX_CAPTURE_MS) finishCapture()
                // Nothing else happens while capturing: no matching, no gate. A word lit by the
                // sample being recorded is a light that means nothing.
                main.postDelayed({ tick() }, AUDIO_LEVEL_SAMPLE_MS)
                return
            }

            // NOTHING HEARD COUNTS WHILE THIS APP IS MAKING A NOISE. The meter carries on, so the
            // bar still moves and it is visible that the sound is being heard — it simply cannot
            // trigger anything. A meter that froze here would look like a fault.
            if (MicMute.muted(now)) {
                main.postDelayed({ tick() }, AUDIO_LEVEL_SAMPLE_MS)
                return
            }

            if (templates.isNotEmpty() && gate.shouldOpen(level, now)) {
                // A short wait so the whole word is in the ring before it is read. The gate fires
                // on the first loud frame; the rest of the word has not been spoken yet.
                gate.sessionEnded(now)
                main.postDelayed({ compare() }, TAIL_MS)
            }
        }
        main.postDelayed({ tick() }, AUDIO_LEVEL_SAMPLE_MS)
    }

    private fun compare() {
        val p = probe ?: return
        val heard = Dsp.features(p.recent(WINDOW_MS))
        if (heard.isEmpty()) return
        val scores = matcher.scores(heard, templates)
        val hit = matcher.match(heard, templates)
        // The tester sees every comparison and its scores, whether or not it was accepted. A near
        // miss that shows a number is a threshold that can be changed from evidence; a near miss
        // that shows nothing is indistinguishable from not having heard anything at all.
        onHeard(hit, scores)
        if (hit != null) onCommand(hit)
    }

    private companion object {
        /** Shorter than this and the press was a slip rather than a recording. */
        const val MIN_CAPTURE_MS = 250

        /** The ring holds two seconds; stop before it wraps and eats its own beginning. */
        const val MAX_CAPTURE_MS = 1_900L

        /** Time for the rest of the word to arrive after the gate fires on its first frame. */
        const val TAIL_MS = 500L

        /** How far back to read. Long enough for the word, short enough to be mostly the word. */
        const val WINDOW_MS = 1_400
    }
}
