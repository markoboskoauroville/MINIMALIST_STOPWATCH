package com.mantra.stopwatch

import kotlin.math.sqrt

/**
 * WHAT COUNTS AS A COMMAND, and it imports nothing.
 *
 * A recogniser hands back a string. Turning that string into one of three controls, or into
 * nothing, is a decision with edges, and every edge here is one Test 1 can walk without a
 * microphone, a permission or a phone.
 *
 * ONE WORD, NO VERB. v7 relied on Google's Voice Access, which needs "tap start". Baba's answer
 * was that being asked to say two things is being asked to do two things. So the app listens for
 * itself and a bare "start" is the whole command.
 *
 * WHY THERE ARE SYNONYMS. Three reasons, and none of them is indecision:
 *
 *   1. A recogniser mishears. "start" comes back as "star" and "stark" often enough that
 *      refusing them means the app looks broken when the person said the right thing.
 *   2. Baba speaks Croatian and English and does not think about which one he is in.
 *   3. His own list was start, stop and reset. The buttons say Start, Pause and Reset because
 *      pause is what the middle one does. Rather than make him remember which word this app
 *      wants, BOTH are accepted and the button keeps the honest label.
 *
 * WHAT IS DELIBERATELY NOT ACCEPTED: anything that could mean two of them. "stop" belongs to
 * pause and nothing else; reset does not answer to it. A command that guesses between freezing a
 * measurement and destroying it is worse than one that does nothing.
 */
object Heard {

    /**
     * The words each control answers to. Lower case, no punctuation — [match] normalises before
     * it looks anything up, so nothing in these lists needs to worry about how it was spoken.
     */
    val VOCABULARY: Map<Control, Set<String>> = mapOf(
        Control.PLAY to setOf(
            "start", "star", "stark", "started", "starts",   // and the usual mishearings
            "go", "run", "begin",
            "kreni", "krenimo", "pocni", "počni", "start!",
        ),
        Control.PAUSE to setOf(
            "pause", "paws", "pose", "pauses", "paused",
            "stop", "stops", "stopped", "hold", "wait",
            "pauza", "stani", "cekaj", "čekaj",
        ),
        Control.STOP to setOf(
            "reset", "resets", "recept", "reserve",          // "recept" is the common mishearing
            "clear", "zero", "zeros", "cancel",
            "resetiraj", "nula", "obrisi", "obriši", "ponisti", "poništi",
        ),
        // Lap is the same word in both languages, which is luck rather than design. "krug" is
        // the Croatian, and it is what somebody out of breath at the end of a length is as
        // likely to say as the English.
        Control.LAP to setOf(
            "lap", "laps", "lapse", "lab",                   // the usual mishearings of one syllable
            "next", "round",
            "krug", "krugovi", "duzina", "dužina",
        ),
    )

    /**
     * The single word each control shows in the reminder. It is the first entry of its list by
     * construction rather than a second copy of the string, so a rename cannot leave the tip
     * printing a word the matcher no longer accepts.
     */
    fun primary(control: Control): String = VOCABULARY.getValue(control).first()

    /**
     * Strip everything that is not a letter or a space, fold to lower case, collapse runs of
     * space. Recognisers return "Start." and "start the clock" and "  START  " for the same
     * breath, and none of that should be the difference between working and not.
     *
     * Croatian diacritics are LEFT ALONE rather than folded to ASCII, because the vocabulary
     * carries both spellings and folding would quietly merge words that are not the same.
     */
    fun normalise(raw: String): List<String> =
        raw.lowercase()
            .map { if (it.isLetter()) it else ' ' }
            .joinToString("")
            .split(" ")
            .filter { it.isNotBlank() }

    /**
     * The heard text to a control, or null.
     *
     * REFUSES RATHER THAN GUESSES when the words point at more than one control. "start and
     * stop" is not a command, it is a sentence, and picking one of the two would be inventing an
     * intention. Null means the app does nothing and the tester shows nothing matched, which is
     * an honest answer a person can act on.
     */
    fun match(raw: String): Control? {
        val words = normalise(raw).toSet()
        val hits = VOCABULARY.entries
            .filter { (_, accepted) -> words.any { it in accepted } }
            .map { it.key }
        return if (hits.size == 1) hits.single() else null
    }
}

/**
 * THE VU CURVE.
 *
 * Ported from TTT mini, app/src/main/kotlin/dev/patrickgold/florisboard/dictate/audio/
 * AudioLevelSmoother.kt, which carries this notice:
 *
 *     Copyright (C) 2026 DevEmperor (Dictate)
 *     Licensed under the Apache License, Version 2.0
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * The curve, the gate and the attack and release rates are that file's, unchanged, because they
 * are a calibration somebody arrived at by watching a meter rather than by reasoning. What is
 * new is the entry point.
 *
 * WHY IT TAKES DECIBELS RATHER THAN PCM AMPLITUDE. TTT mini owns the microphone through
 * AudioRecord and can read sample peaks directly. This app hands the microphone to
 * SpeechRecognizer, and a second reader cannot have it at the same time — opening AudioRecord
 * beside a live recogniser fails or starves one of the two. SpeechRecognizer offers the level
 * back through onRmsChanged, in decibels, so that is the input and [fromRms] does the conversion
 * before the original curve runs untouched.
 */
class Vu {

    private var level = 0f

    /** SpeechRecognizer's rmsdB to the 0..1 the original curve expects. */
    fun fromRms(rmsDb: Float): Float {
        val span = RMS_MAX - RMS_MIN
        // No clamp here: update() clamps on the way in, and a second one was two places to be
        // right about the same thing. The mutation sweep found it by breaking this line and
        // watching nothing happen, which is the honest way to discover a redundant guard.
        return update((rmsDb - RMS_MIN) / span)
    }

    /**
     * A PCM16 peak, which is how TTT mini feeds this curve and the only honest source of level
     * there is. Used by MicProbe, which owns the microphone directly while voice commands are
     * off, so the tester can answer "does audio reach this app" without asking the recogniser.
     *
     * VISUAL_FULL_SCALE is TTT mini's number and not full scale. Speech rarely reaches PCM16
     * maximum, so dividing by 32767 gives a bar that barely moves for a normal voice. 16000 is
     * the calibration that has been looked at on a real screen for years in the other app.
     */
    fun fromPeak(peak: Int): Float = update(peak.coerceAtLeast(0) / VISUAL_FULL_SCALE)

    /** The curve from TTT mini, taking an already normalised 0..1 rather than a PCM16 peak. */
    fun update(normalised: Float): Float {
        val gated = ((normalised.coerceIn(0f, 1f) - NOISE_GATE) / (1f - NOISE_GATE)).coerceIn(0f, 1f)
        val curved = sqrt(gated)
        val rate = if (curved > level) ATTACK else RELEASE
        level += (curved - level) * rate
        if (level < REST_EPSILON && curved == 0f) level = 0f
        return level
    }

    fun reset(): Float {
        level = 0f
        return level
    }

    private companion object {
        // SpeechRecognizer's documented range is loose and device-dependent. These two are what
        // silence and a normal speaking voice actually read as; anything outside is clamped.
        const val RMS_MIN = -2f
        const val RMS_MAX = 10f

        /** TTT mini's calibration: speech rarely reaches PCM16 full scale, so this is not it. */
        const val VISUAL_FULL_SCALE = 16_000f

        // Unchanged from TTT mini.
        const val NOISE_GATE = 0.025f
        const val ATTACK = 0.55f
        const val RELEASE = 0.20f
        const val REST_EPSILON = 0.005f
    }
}

/**
 * FIRE ONCE PER UTTERANCE, AND THIS IS THE BUG THE WHOLE FILE EXISTS TO STOP.
 *
 * The listener acts on PARTIAL results, because a stopwatch command that lands half a second
 * late has already missed the thing being timed. But partial results arrive several times for
 * one spoken word: "st", "start", "start" again as the recogniser firms up. Each of those
 * contains the command, so without a gate a single spoken "start" presses play three or four
 * times — and play is a TOGGLE, so the clock would start, pause, start, pause and end up
 * wherever the count of partials left it. It would look like the microphone was possessed.
 *
 * TWO RULES, BECAUSE ONE IS NOT ENOUGH:
 *
 *   once per utterance   the gate closes on the first match and only [newUtterance] reopens it,
 *                        which the listener calls when it starts listening again
 *   and a minimum gap    because the recogniser restarts every couple of seconds and the tail
 *                        of the same word can land in the next utterance as well
 *
 * It is pure, and it takes the clock as an argument, for the same reason the stopwatch does:
 * every case here can be walked in Test 1 without a microphone.
 */
class CommandGate(private val minGapMs: Long = MIN_GAP_MS) {

    // NOT Long.MIN_VALUE for "never fired". That was the first version and Test 1 killed it on
    // the first run: `now - Long.MIN_VALUE` OVERFLOWS to a negative number, the minimum-gap
    // check reads that as "too soon", and the gate never opened at all. A voice command that
    // never fires is the exact opposite of the bug this class was written to prevent, and it
    // would have shipped looking like a microphone that does not work.
    private var firedAt = 0L
    private var hasFired = false
    private var firedThisUtterance = false

    /** Called when the recogniser begins a fresh listen. Reopens the gate. */
    fun newUtterance() {
        firedThisUtterance = false
    }

    /**
     * True at most once per utterance, and never twice inside [minGapMs] however many utterances
     * that spans. Asking has a side effect on purpose: a gate that reports whether it would open
     * and then has to be told separately that it did is two calls to keep in step.
     */
    fun allow(now: Long): Boolean {
        if (firedThisUtterance) return false
        if (hasFired && now - firedAt < minGapMs) return false
        firedThisUtterance = true
        hasFired = true
        firedAt = now
        return true
    }

    private companion object {
        /**
         * Long enough that the tail of one word cannot arrive twice, short enough that "stop"
         * followed deliberately by "reset" both land. Two commands a second is faster than
         * anybody speaks them.
         */
        const val MIN_GAP_MS = 700L
    }
}


/**
 * WHAT THE TESTER PRINTS, and it exists because this code cannot see the room.
 *
 * Every field separates two faults that look identical from outside:
 *
 *   sessions climbing fast        the recogniser is being started and killed in a loop
 *   rmsCallbacks stuck at zero    it never got as far as opening the microphone, so a still
 *                                 meter is not a broken meter
 *   lastError                     the name, not the number, so it can be read out loud
 *   offline                       whether the offline preference is still being asked for or
 *                                 has been given up on
 *
 * A person holding the phone can read these four out in a sentence. That is the fastest debugging
 * loop available to somebody who has never held the device.
 */
data class Diagnostics(
    val sessions: Int = 0,
    val rmsCallbacks: Int = 0,
    val lastError: String = "",
    val offline: Boolean = true,
) {
    /**
     * The one line the panel shows. Written here rather than in the screen so Test 1 can walk it,
     * because a diagnostics line that is wrong is worse than none: it is believed.
     */
    fun line(): String = buildString {
        append("s").append(sessions)
        append("  rms").append(rmsCallbacks)
        append(if (offline) "  offline" else "  online")
        if (lastError.isNotEmpty()) append("  ").append(lastError)
    }

    /**
     * The reading that says the fault is a restart storm rather than a quiet room: many sessions
     * and no level callbacks at all. Named so the panel can say it in words instead of leaving
     * two numbers to be interpreted.
     */
    fun looksLikeRestartStorm(): Boolean = sessions >= 5 && rmsCallbacks == 0
}


/**
 * WHEN TO WAKE THE RECOGNISER, and this is the whole answer to the beeping.
 *
 * WHAT THE PHONE TOLD US. The meter runs when the app is not listening and stops when it is, and
 * a tone goes on and off without pause the whole time. Those two facts together say something
 * precise: AudioRecord opens the microphone on this device and works, and SpeechRecognizer takes
 * it away and then churns — started, killed, started, killed. The tone is the session boundary.
 * Nothing was wrong with the microphone, the permission, or the meter.
 *
 * SO THE RECOGNISER STOPS BEING THE THING THAT HOLDS THE MICROPHONE. AudioRecord holds it, all
 * the time, which is why the meter now runs whether voice is on or off. The recogniser is woken
 * only when the level says somebody actually spoke, gets one session, and hands the microphone
 * straight back.
 *
 * A session per utterance instead of four a second is the difference between a tone when you
 * speak and a tone that never stops. It also means the recogniser is listening at the moment
 * there is something to hear, rather than spending its life timing out on a silent room.
 *
 * THE COOLDOWN IS NOT POLITENESS. Without it the tail of the word that opened the gate opens it
 * again the moment the microphone comes back, and the churn returns wearing a different hat.
 */
class SpeechGate(
    private val openAt: Float = OPEN_AT,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    private var endedAt = 0L
    private var everEnded = false

    fun shouldOpen(level: Float, now: Long): Boolean {
        if (level < openAt) return false
        if (!everEnded) return true
        return now - endedAt >= cooldownMs
    }

    fun sessionEnded(now: Long) {
        endedAt = now
        everEnded = true
    }

    private companion object {
        /**
         * On the smoother's 0..1, a room tone sits near zero and a spoken word crosses this
         * comfortably. Low enough that a quiet voice opens it, high enough that a fan does not.
         */
        const val OPEN_AT = 0.30f
        const val COOLDOWN_MS = 1_200L
    }
}

/**
 * WHICH WORD IS LIT, AND FOR HOW LONG.
 *
 * The tester lights the word that was just heard and then lets it go dark again. A light that
 * stays on cannot show a second "start" a moment later — it is already on, so nothing happens,
 * and the one thing being tested is invisible. Going dark is what makes the next one visible.
 *
 * A second is long enough to catch out of the corner of an eye and short enough that two words
 * spoken in a row read as two events.
 */
data class Lit(val control: Control? = null, val untilMs: Long = 0L) {

    fun isLit(candidate: Control, now: Long): Boolean =
        control == candidate && now < untilMs

    companion object {
        const val HOLD_MS = 1_000L

        fun of(control: Control, now: Long) = Lit(control, now + HOLD_MS)
    }
}


/** Where a capture has got to. */
enum class CaptureState { WAITING, SPEAKING, DONE, TIMED_OUT }

/**
 * LISTENING FOR ONE WORD AND STOPPING WHEN IT ENDS.
 *
 * The arm button is gone, and so is the fixed second and a half. Pressing a pad starts a capture:
 * it waits for you to begin, records while you speak, and stops when you stop. A fixed length is
 * a worse recording in both directions — it keeps the silence you left at the end if you were
 * quick, and it cuts you off if you were not.
 *
 * FOUR NUMBERS, and each one is a decision rather than a default:
 *
 *   ONSET       the level that counts as speech starting. The same threshold the command gate
 *               uses, because they are answering the same question about the same signal
 *   HANGOVER    how long the level must stay down before the word is over. Too short and it
 *               stops inside the gap in the middle of a word like "re-set"; too long and it
 *               keeps a second of room at the end
 *   MAX_SPEECH  a ceiling, so a noisy room cannot hold a capture open for ever
 *   WAIT        how long to wait for you to start before giving up and saying so
 *
 * It is a pure state machine fed a level and a clock, so all of that is testable without a
 * microphone — which matters here, because every part of this app that needed a microphone to
 * test is the part that took ten versions to get right.
 */
class Capture(
    private val onset: Float = ONSET,
    private val hangoverMs: Long = HANGOVER_MS,
    private val maxSpeechMs: Long = MAX_SPEECH_MS,
    private val waitMs: Long = WAIT_MS,
) {
    private var startedAt = 0L
    private var onsetAt = 0L
    private var quietSince = 0L
    private var state = CaptureState.WAITING

    fun begin(now: Long) {
        startedAt = now
        onsetAt = 0L
        quietSince = 0L
        state = CaptureState.WAITING
    }

    fun update(level: Float, now: Long): CaptureState {
        when (state) {
            CaptureState.WAITING -> {
                if (level >= onset) {
                    onsetAt = now
                    quietSince = 0L
                    state = CaptureState.SPEAKING
                } else if (now - startedAt >= waitMs) {
                    state = CaptureState.TIMED_OUT
                }
            }
            CaptureState.SPEAKING -> {
                if (level >= onset) {
                    quietSince = 0L
                } else {
                    if (quietSince == 0L) quietSince = now
                    if (now - quietSince >= hangoverMs) state = CaptureState.DONE
                }
                if (now - onsetAt >= maxSpeechMs) state = CaptureState.DONE
            }
            else -> Unit
        }
        return state
    }

    /**
     * How far back to read from the ring, in milliseconds, once the capture is done.
     *
     * From a little BEFORE the onset, because the level only crosses the threshold once the word
     * is already underway — the first consonant is always quieter than the vowel that follows it,
     * and reading from the crossing point would clip every recording at the front.
     */
    fun windowMs(now: Long): Int =
        ((now - onsetAt) + LEAD_MS).toInt().coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)

    companion object {
        const val ONSET = 0.30f
        const val HANGOVER_MS = 550L
        const val MAX_SPEECH_MS = 2_000L
        const val WAIT_MS = 4_000L

        /** Reach back past the crossing point to catch the start of the word. */
        const val LEAD_MS = 250L
        const val MIN_WINDOW_MS = 400
        const val MAX_WINDOW_MS = 2_000
    }
}

/**
 * WHAT A PRESS ON A SAMPLE LINE MEANS, ported from SAMPLE_PLAYER's Gesture rather than invented a
 * second time.
 *
 * That app faced the same problem with thirty tiles and solved it with a mode plus a pure press
 * table: the decision is RETURNED rather than performed, so Test 1 can read what a press was
 * decided to mean without a phone, a microphone or a screen. Two apps in the same account
 * disagreeing about what pressing a sample does would be two answers to one question.
 *
 * TWO MODES, because a press on a line has to mean two different things and one of them destroys
 * a recording. The stopwatch deleted tap-anywhere at v1 for exactly that reason. It is accepted
 * here on the same terms SAMPLE_PLAYER accepts it: the mode is never hidden, it is a visible
 * control, and it is the only thing separating listening to a take from recording over it.
 */
enum class SamplerMode {
    /** A press PLAYS the line, so you can hear what you recorded. */
    LISTEN,

    /** A press starts recording into the line, and the next press stops it. */
    RECORD,
}

/** The decision, returned rather than done. */
sealed interface SamplerPress {
    data class Play(val control: Control, val slot: Int) : SamplerPress
    data class StartRecording(val control: Control, val slot: Int) : SamplerPress

    /**
     * The line already holds a take and the person pressed it in record mode.
     *
     * NOT StartRecording. Recording over a take destroys something that cannot be got back, and a
     * press is one finger on a small line among twelve. The confirmation is the whole difference
     * between a mistake that costs a tap and a mistake that costs a take, and it is decided here
     * rather than in the interface so that Test 1 can prove it is never skipped.
     */
    data class ConfirmOverwrite(val control: Control, val slot: Int) : SamplerPress
    data class StopRecording(val control: Control, val slot: Int) : SamplerPress
    data class Refused(val why: String) : SamplerPress
}

object SamplerGesture {

    fun press(
        mode: SamplerMode,
        control: Control,
        slot: Int,
        filled: Boolean,
        recording: Pair<Control, Int>?,
    ): SamplerPress {
        // A recording in progress overrides the mode entirely. There is one microphone, so there
        // is one answer, and it does not depend on which button was showing when it started.
        if (recording != null) {
            return if (recording == control to slot) {
                SamplerPress.StopRecording(control, slot)
            } else {
                // Not a second recording, and not a silent no-op either: say which line is busy.
                SamplerPress.Refused("${Heard.primary(recording.first)} ${recording.second + 1} is recording")
            }
        }
        return when (mode) {
            SamplerMode.LISTEN ->
                if (filled) SamplerPress.Play(control, slot)
                // An empty line in listen mode does NOT fall through to recording. That
                // fall-through is the thing this whole table exists to prevent.
                else SamplerPress.Refused("nothing recorded there")

            SamplerMode.RECORD ->
                if (filled) SamplerPress.ConfirmOverwrite(control, slot)
                else SamplerPress.StartRecording(control, slot)
        }
    }
}

/**
 * THE MICROPHONE IS DEAF WHILE THIS APP IS MAKING A NOISE.
 *
 * Baba found this the hard way: the count-in ends, the Go word plays, the app hears its own Go
 * word, and the stopwatch it just started is stopped again by a sound it made itself. The feature
 * defeated itself, which is the worst shape a bug can have — every part worked exactly as
 * designed.
 *
 * There is no clever fix and no clever fix is wanted. While this app is playing audio, nothing
 * heard counts.
 *
 * THE TAIL IS THE PART THAT IS EASY TO GET WRONG. The matcher does not compare what is arriving
 * now, it compares THE LAST SECOND AND A HALF from a ring — so unmuting the instant playback ends
 * leaves the played sound sitting in the ring, where the very next comparison will find it. The
 * mute has to outlast the sound by at least the length of the window the matcher reads, or the
 * feedback comes back a moment later wearing a different hat.
 */
object MicMute {

    @Volatile private var until = 0L

    /** How long after a sound ends before what is heard can count again. */
    const val TAIL_MS = 1_600L

    fun muteFor(soundMs: Long, now: Long) {
        val ends = now + soundMs + TAIL_MS
        // Never shorten an existing mute. Two sounds overlapping must leave the microphone deaf
        // until the LATER of them has cleared the ring.
        if (ends > until) until = ends
    }

    fun muted(now: Long): Boolean = now < until

    /** Only for tests, and named so it reads as such at the call site. */
    fun clearForTest() {
        until = 0L
    }
}
