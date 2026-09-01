package com.mantra.stopwatch

import kotlin.math.PI
import kotlin.math.sin

/**
 * THE SOUND THE TIMER MAKES WHEN IT REACHES ZERO, GENERATED RATHER THAN SHIPPED.
 *
 * No asset, no download, no licence to read, nothing to go missing from a build, and about two
 * hundred bytes of code instead of a file. It is also the only way this can be TUNED: a recording
 * is a thing you either keep or replace, while this is four numbers that can be moved until it
 * sounds right on the phone it will actually be heard on.
 *
 * WHY A CHIRP AND NOT A TONE. Real birdsong is frequency-swept — the pitch slides across the note
 * rather than sitting still — and that slide is most of what makes a sound read as a bird rather
 * than as an alarm. A steady sine at 3kHz is a smoke detector. The same energy swept from 2 to 4
 * and back is a chaffinch.
 *
 * THREE NOTES, NOT ONE. A single chirp is a blip and is easy to miss and easier to mistake for a
 * notification. Three, with silence between them, is a CALL: it has a shape, it repeats, and
 * nothing else on a phone sounds like it.
 *
 * IT IS ALSO KIND, WHICH IS THE POINT. A timer that ends is telling somebody their plank is over
 * or their tea is ready. Every phone in the world does that with a buzz. This app has spent
 * thirty-three versions taking things away to make one number readable in the dark; the sound it
 * makes at the end of a measurement should belong to the same object.
 */
/**
 * TWO SPECIES, BECAUSE TWO MOMENTS.
 *
 * The count-in ending and the timer ending are different events and must not sound alike. Both
 * are heard from across a room, usually while doing something else, and a person who has to work
 * out WHICH sound that was has been given a puzzle instead of an answer.
 *
 * They are told apart by shape rather than by pitch, which is what actually survives a noisy room
 * and a phone on a bench: three rising-falling chirps against two falling whistles. Even at the
 * wrong volume, through a wall, the rhythm is different.
 */
enum class Bird {
    /** Three quick rising-falling chirps. The timer, ending. */
    CHAFFINCH,

    /** Two slower falling whistles, the second lower. The count-in, ending: go. */
    CHICKADEE,
}

object Birdsong {

    private const val NOTE_MS = 110
    private const val GAP_MS = 70
    private const val NOTES = 3

    /** The sweep, in hertz. Up and back down within each note, which is what a chirp is. */
    private const val LOW_HZ = 2_100.0
    private const val HIGH_HZ = 4_300.0

    // The second voice. Longer notes, a downward glide, and the second note lower than the first
    // — which is the two-note falling call anybody would recognise as a different bird.
    private const val C_NOTE_MS = 230
    private const val C_GAP_MS = 90
    private const val C_NOTES = 2
    private const val C_TOP_HZ = 3_900.0
    private const val C_BOTTOM_HZ = 2_500.0
    private const val C_SECOND_NOTE = 0.78

    /** Well below full scale. Nothing about this needs to be loud to be heard across a room. */
    private const val AMPLITUDE = 0.32

    fun samples(bird: Bird = Bird.CHAFFINCH, rate: Int = Dsp.SAMPLE_RATE): ShortArray =
        if (bird == Bird.CHICKADEE) chickadee(rate) else chaffinch(rate)

    fun lengthMs(bird: Bird): Int =
        if (bird == Bird.CHICKADEE) C_NOTES * C_NOTE_MS + (C_NOTES - 1) * C_GAP_MS
        else NOTES * NOTE_MS + (NOTES - 1) * GAP_MS

    /**
     * Two falling whistles, the second lower. Where the chaffinch bends up and back within each
     * note, this one only descends — and a fall is heard as an ending, which is what it marks.
     */
    private fun chickadee(rate: Int): ShortArray {
        val noteLen = rate * C_NOTE_MS / 1000
        val gapLen = rate * C_GAP_MS / 1000
        val out = ShortArray(C_NOTES * noteLen + (C_NOTES - 1) * gapLen)
        var at = 0
        for (note in 0 until C_NOTES) {
            val drop = if (note == 0) 1.0 else C_SECOND_NOTE
            var phase = 0.0
            for (i in 0 until noteLen) {
                val t = i.toDouble() / noteLen
                val hz = (C_TOP_HZ - (C_TOP_HZ - C_BOTTOM_HZ) * t) * drop
                phase += 2.0 * PI * hz / rate
                val envelope = sin(PI * t)
                val v = sin(phase) * 0.86 + sin(phase * 2.0) * 0.14
                out[at + i] = (v * envelope * AMPLITUDE * 32767).toInt().toShort()
            }
            at += noteLen
            if (note < C_NOTES - 1) at += gapLen
        }
        return out
    }

    private fun chaffinch(rate: Int): ShortArray {
        val noteLen = rate * NOTE_MS / 1000
        val gapLen = rate * GAP_MS / 1000
        val out = ShortArray(NOTES * noteLen + (NOTES - 1) * gapLen)

        var at = 0
        for (note in 0 until NOTES) {
            // Each note starts a little higher than the last, the way a call rises.
            val lift = 1.0 + note * 0.06
            var phase = 0.0
            for (i in 0 until noteLen) {
                val t = i.toDouble() / noteLen

                // The sweep: up over the first half, back down over the second. A note that only
                // rises sounds like a question; one that rises and falls sounds like a bird.
                val bend = if (t < 0.5) t * 2.0 else (1.0 - t) * 2.0
                val hz = (LOW_HZ + (HIGH_HZ - LOW_HZ) * bend) * lift

                // Phase is accumulated rather than computed from t, because a sweep computed as
                // sin(2·pi·f(t)·t) is not the frequency it claims to be — the changing f smears
                // across the whole elapsed time and the note bends the wrong way.
                phase += 2.0 * PI * hz / rate

                // A soft start and end, so there is no click at either edge. A click is the one
                // thing that would make this sound synthetic.
                val envelope = sin(PI * t)

                // A touch of the octave above, which is what stops it sounding like a whistle.
                val v = sin(phase) * 0.82 + sin(phase * 2.0) * 0.18
                out[at + i] = (v * envelope * AMPLITUDE * 32767).toInt().toShort()
            }
            at += noteLen
            if (note < NOTES - 1) at += gapLen
        }
        return out
    }

    /** Length in milliseconds, so anything that has to wait for one can. */

}
