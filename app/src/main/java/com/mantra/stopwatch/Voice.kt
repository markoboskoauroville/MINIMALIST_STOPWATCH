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
