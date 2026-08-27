package com.mantra.stopwatch

/**
 * THE WHOLE OF THE TIMING LOGIC, AND IT TOUCHES NOTHING ANDROID.
 *
 * Not one import from android.*. That is deliberate and it is the reason Test 1 exists at all:
 * this file compiles and runs on a plain JVM, so the rules below can be attacked with hand-made
 * inputs before a single pixel is drawn. If an android import ever appears here, Test 1 stops
 * being a test of the mechanism and becomes a test of the emulator.
 *
 * ---------------------------------------------------------------------------------------------
 * THE MODEL. Two fields, and subtraction. Never addition of deltas.
 * ---------------------------------------------------------------------------------------------
 *
 *   startedAt     the monotonic instant the CURRENT run segment began
 *   accumulated   milliseconds banked by every segment BEFORE this one
 *
 *   running elapsed  =  accumulated + (now - startedAt)
 *   paused  elapsed  =  accumulated
 *   stopped elapsed  =  0
 *
 * Pause does not break the subtraction rule, it only adds a second field. The temptation when
 * pause arrives is to start adding up ticks instead, because "accumulated" sounds like it wants
 * to be incremented on a timer. It must not be. `accumulated` changes at exactly one moment —
 * when a run segment ENDS — and its new value is computed by subtraction, not by counting. A
 * ticker that adds 100ms ten times a second looks correct for a minute and is visibly wrong
 * after an hour, which is precisely when the number is being relied on.
 *
 * `now` is always SystemClock.elapsedRealtime() at the call site: monotonic, unaffected by a
 * time server correcting the wall clock, and counting while the device sleeps.
 */

enum class Phase { STOPPED, RUNNING, PAUSED }

data class Stopwatch(
    val phase: Phase = Phase.STOPPED,
    val startedAt: Long = 0L,
    val accumulated: Long = 0L,
) {

    /**
     * A monotonic clock is monotonic in principle. In practice a saved instant can outlive the
     * boot it was measured against, and then `now - startedAt` is negative. A stopwatch showing
     * -00:03.2 is worse than one showing zero, so the floor is here rather than in the formatter,
     * where it would have been applied to a value already used for arithmetic elsewhere.
     */
    fun elapsed(now: Long): Long {
        val raw = when (phase) {
            Phase.RUNNING -> accumulated + (now - startedAt)
            Phase.PAUSED -> accumulated
            Phase.STOPPED -> 0L
        }
        return if (raw < 0L) 0L else raw
    }

    /** From zero, or from where pause left it. Play on a running clock is not a restart. */
    fun play(now: Long): Stopwatch = when (phase) {
        Phase.RUNNING -> this
        Phase.STOPPED -> Stopwatch(Phase.RUNNING, now, 0L)
        Phase.PAUSED -> Stopwatch(Phase.RUNNING, now, accumulated)
    }

    /**
     * Banks the segment and freezes. Pausing an already-paused clock returns the same object —
     * if it instead recomputed, the segment would be banked a second time and the elapsed figure
     * would double every press.
     */
    fun pause(now: Long): Stopwatch = when (phase) {
        Phase.RUNNING -> Stopwatch(Phase.PAUSED, 0L, elapsed(now))
        else -> this
    }

    /** Back to zeros. Clears BOTH fields; leaving accumulated behind is the classic half-reset. */
    fun stop(): Stopwatch = Stopwatch()

    // The nine cases of the button table live here rather than in the Activity, so they can be
    // walked in Test 1. A control that cannot act is dimmed and inert — never hidden, because a
    // control that disappears moves the layout and a stopwatch whose buttons shuffle is worse
    // than one with a dim button.
    fun canPlay(): Boolean = phase != Phase.RUNNING
    fun canPause(): Boolean = phase == Phase.RUNNING
    fun canStop(): Boolean = phase != Phase.STOPPED

    companion object {

        /**
         * A boot marker moving by more than this is read as a reboot rather than as the clock
         * being corrected. A time server steps the wall clock by well under a second; a reboot
         * moves the marker by the whole of the previous uptime plus however long the device was
         * off. A minute sits far above the one and far below the other.
         */
        const val BOOT_TOLERANCE_MS: Long = 60_000L

        /**
         * RESTORING ACROSS ROTATION, BACKGROUNDING, PROCESS DEATH AND REBOOT.
         *
         * The first three are free: elapsedRealtime keeps counting through all of them, so a
         * saved startedAt is still measured against the same origin and the subtraction is still
         * valid. Ten minutes in the background is ten more minutes on the clock, not ten fewer.
         *
         * A REBOOT IS DIFFERENT and it is the case that has to be handled rather than assumed
         * away. elapsedRealtime restarts at zero, so a saved startedAt no longer refers to
         * anything. Two independent detectors, because each has a hole the other covers:
         *
         *   now < lastSeen          exact and clock-independent. A monotonic clock cannot go
         *                           backwards, so this is proof, not suspicion. Misses a reboot
         *                           where the device has since been up longer than it was before
         *   boot marker moved       (wall clock - elapsedRealtime) is approximately the instant
         *                           of boot. Catches the case above. Costs one false positive:
         *                           moving the phone's clock by more than a minute during a
         *                           running measurement reads as a reboot. Rare, deliberate, and
         *                           it fails to zeros rather than to a wrong number
         *
         * What each phase deserves when a reboot is detected:
         *
         *   RUNNING   ZEROS. The length of the segment in progress is unknowable. Returning
         *             PAUSED at `accumulated` would look like a preserved measurement and would
         *             silently be short by however long the device had been running. A wrong
         *             answer delivered confidently is worse than no answer
         *   PAUSED    KEPT, intact. A paused stopwatch holds its whole value in `accumulated`
         *             and never consults the clock, so a reboot costs it nothing. Throwing it
         *             away would be a loss with no cause
         *   STOPPED   zeros, which it already was
         */
        fun restore(
            phase: Phase,
            startedAt: Long,
            accumulated: Long,
            savedBootMarker: Long,
            bootMarkerNow: Long,
            lastSeen: Long,
            now: Long,
        ): Stopwatch {
            val markerMoved = bootMarkerNow - savedBootMarker
            val rebooted = now < lastSeen ||
                (if (markerMoved < 0) -markerMoved else markerMoved) > BOOT_TOLERANCE_MS

            if (rebooted) {
                return if (phase == Phase.PAUSED) Stopwatch(Phase.PAUSED, 0L, accumulated)
                else Stopwatch()
            }
            // Belt and braces: a saved instant in the future cannot be honoured whatever the
            // detectors concluded.
            if (phase == Phase.RUNNING && now < startedAt) return Stopwatch()
            return Stopwatch(phase, startedAt, accumulated)
        }
    }
}

/**
 * THE FACE.
 *
 * MM:SS below an hour, H:MM:SS at and above it. WHOLE SECONDS, no tenths.
 *
 * The tenth was removed on Baba's instruction on 27.8.2026 after v2 was on the phone. It is the
 * right call and the reason is not only taste: the last digit was the only part of the display
 * that changed at a speed the eye cannot rest on, and everything else on this screen exists to
 * be readable across a room. Dropping it also takes the string from seven glyphs to five, which
 * makes every remaining digit substantially larger for free, and takes the redraw from ten a
 * second to one.
 *
 * WHAT THE TENTH COST US, said plainly so nobody restores it by accident: the app can no longer
 * be used to time anything where a fraction of a second matters. It is a clock for minutes, not
 * a photo finish.
 *
 * TRUNCATED, NOT ROUNDED. A stopwatch reports completed time. Rounding would show 10 while 9.6
 * seconds had passed, putting the display ahead of the measurement, which is the wrong direction
 * for a thing whose only job is to be trusted. It also means the first second after start reads
 * 00:00, which is correct: no whole second has elapsed.
 *
 * THE WIDTH NEVER CHANGES WHILE COUNTING. Every field is zero-padded to a fixed number of glyphs
 * and the typeface is monospaced, so 11 occupies exactly what 00 does and nothing shuffles.
 *
 * WHAT HAPPENS AT THE HOUR, DECIDED IN ADVANCE. The string grows from 5 glyphs to 7 and the
 * autosizing digits shrink once, at 1:00:00, and do not change again. The alternative was to show
 * H:MM:SS from zero so nothing ever resizes — rejected, because it makes the digits permanently
 * smaller on every ordinary use to defend against an hour that almost never arrives. Past ten
 * hours the hour field takes a second glyph and it steps once more. Documented rather than
 * prevented; a stopwatch running for ten hours has other problems.
 */
object Face {
    fun format(ms: Long): String {
        val t = if (ms < 0L) 0L else ms
        val seconds = t / 1000L
        val s = seconds % 60L
        val minutes = seconds / 60L
        val m = minutes % 60L
        val h = minutes / 60L
        return if (h > 0L) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }

    /**
     * Milliseconds until the next whole second turns over, so the redraw happens when the digits
     * actually change instead of sixty times a second on a display that changes once. Never
     * returns 0 — a zero-delay repost is an unbounded loop wearing a timer's clothes.
     */
    fun untilNextSecond(elapsedMs: Long): Long {
        val r = 1000L - (if (elapsedMs < 0L) 0L else elapsedMs) % 1000L
        return if (r <= 0L) 1000L else r
    }
}
