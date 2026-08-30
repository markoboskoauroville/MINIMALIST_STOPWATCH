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

/**
 * The three transport controls, named so the button table can be walked case by case.
 *
 * `spoken` IS THE VOICE VOCABULARY, AND IT IS THE ONLY PLACE IT IS WRITTEN.
 *
 * Google's Voice Access is an accessibility service already on the phone, and it matches speech
 * against the contentDescription of every control on screen. So this string is not decoration
 * for a screen reader: it is the word that can be said out loud. It goes on the button AND into
 * the reminder in the settings panel, from here, so the tip cannot come to disagree with what
 * the app actually answers to. A tip that lists a command the app no longer knows is worse than
 * no tip, because it is believed.
 *
 * They deliberately do not match the enum's own names. The model thinks in play, pause and stop;
 * a person timing something says start, pause and reset.
 */
enum class Control(val spoken: String) {
    PLAY("Start"),
    PAUSE("Pause"),
    STOP("Reset"),

    /**
     * The lap counter, which is a command but NOT a transport control.
     *
     * It is in this enum because everything a spoken word can do lives here: the vocabulary, the
     * sample rows in the recorder, and the matcher's per-command scoring all walk Control.entries.
     * Leaving lap out would have meant a second parallel list of things that can be said, and two
     * lists of the same kind of thing is how one of them quietly stops being updated.
     *
     * It is not drawn in the transport row, and it never changes the clock — see press().
     */
    LAP("Lap"),
}

/**
 * How a control is drawn — a ladder of prominence, four rungs, each with exactly one meaning.
 * See Stopwatch.tone for which cell gets which.
 *
 * PRIMARY is white, and it is the ONLY thing on this screen other than the digits that ever is.
 * It appears on one cell of the nine: play, while the clock is not running. That is a screen
 * with no measurement in progress and nothing to compete with, and the white says start here.
 * THE MOMENT IT RUNS, THE WHITE IS GONE and the digits are alone again, which is the whole
 * point of the original rule and the reason the exception stops where it does.
 */
enum class Tone { PRIMARY, HIGHLIGHT, SECONDARY, DEAD }

/**
 * Which way up the app sits. There is no "follow the phone" any more: from v5 the corner button
 * SETS the orientation rather than locking whatever the phone happened to be doing.
 */
enum class Orientation { PORTRAIT, LANDSCAPE }

/**
 * How much of the clock is on the screen.
 *
 * MULTI is what this app has always done: HH:MM:SS from zero, so the width never changes and
 * nothing ever resizes under you. It is the right default and it is what v4 was asked for.
 *
 * SINGLE shows only the fields that have anything in them — two digits until the first minute,
 * then MM:SS, then HH:MM:SS. Each of those is a shorter string, so the digits are enormous at
 * the start and step down as the measurement grows.
 *
 * THE TWO OPTIONS ARE THE SAME ARGUMENT WITH TWO ANSWERS. v1 and v3 rejected showing the hour
 * from zero because it makes the digits permanently smaller to defend against an hour that
 * rarely arrives; v4 reversed that because a width that never changes is worth more than size.
 * Both are true and which one wins depends on what is being timed, so it stops being a decision
 * made once in the source and becomes a switch.
 */
enum class Display { MULTI, SINGLE }

/**
 * The lap counter, off or counting, and if counting then optionally in metres.
 *
 * The metres exist for swimming. A pool has a known length, so a lap count IS a distance, and
 * the arithmetic is one multiplication that a person should not have to do in their head while
 * out of breath at the end of a length.
 *
 * The lengths offered are the two that almost every pool is. A free-text field would be a
 * keyboard on a screen whose whole design is that it has no keyboard, for a number that changes
 * about once a year.
 */
/**
 * The countdown before a measurement starts, so you can put the phone down and get to the wall.
 *
 * OFF is the default and always was. TEN is ten seconds, which is what was asked for and is about
 * the time it takes to set a phone on a bench and reach the end of a lane.
 *
 * IT APPLIES ONLY FROM ZERO. Resuming a paused measurement must not sit there counting to itself
 * — you already started, you are standing in the water, and a second start ceremony would be a
 * delay with no purpose.
 */
/**
 * Which way the clock runs. S counts up, T counts down.
 *
 * THE TIMING MODEL DOES NOT CHANGE, and that is the whole design. A timer is not a second clock
 * with its own start instant and its own drift; it is the same elapsed figure subtracted from a
 * length. Everything that was proven about startedAt and accumulated over twenty-nine versions —
 * the reboot rules, the process death, the never-adding-deltas — holds for both modes because
 * both modes are the same measurement read two ways.
 */
enum class AppMode { STOPWATCH, TIMER }

/**
 * How long the timer runs. Presets rather than a field, for the same reason the pool lengths are
 * presets: this app has no keyboard by design, and these are the durations somebody standing in a
 * kitchen or on a mat actually asks for.
 */
enum class TimerLength(val seconds: Int) {
    ONE(60),
    THREE(180),
    FIVE(300),
    TEN(600),
    TWENTY_FIVE(1_500),
}

/**
 * What the timer shows: the length less what has elapsed, and never below zero.
 *
 * CLAMPED AT ZERO RATHER THAN GOING NEGATIVE. A timer that runs past its end and shows a minus
 * sign has stopped being a timer and become a stopwatch nobody asked for, and the figure it shows
 * is one somebody could read as time remaining.
 */
fun timerRemaining(lengthMs: Long, elapsedMs: Long): Long {
    val left = lengthMs - elapsedMs
    return if (left < 0L) 0L else left
}

/** Whether the timer has run out. Separate from the figure, because zero is a state as well. */
fun timerFinished(lengthMs: Long, elapsedMs: Long): Boolean = elapsedMs >= lengthMs

enum class PrerollMode(val seconds: Int) {
    OFF(0),
    TEN(10),
}

/**
 * What the digits show while the countdown runs, or null when it is over.
 *
 * COUNTS DOWN IN WHOLE SECONDS AND SHOWS THE ONE YOU ARE IN. At 9.4 seconds remaining this reads
 * 10, not 9: the number on the screen is the second currently being lived through, which is how
 * every countdown a person has ever watched behaves. Truncating instead would show 10 for a
 * fraction of a second and 0 for a whole one, and the last second would be the wrong length.
 */
fun prerollLabel(remainingMs: Long): String? {
    if (remainingMs <= 0L) return null
    val seconds = (remainingMs + 999L) / 1000L
    return seconds.toString()
}

enum class LapMode(val metres: Int?) {
    OFF(null),
    COUNT(null),
    M25(25),
    M50(50),
}

/**
 * What sits above the digits, or null when there is nothing to show.
 *
 * Pure and separate from the screen because it is the one piece of arithmetic in this feature and
 * arithmetic is the thing worth testing: a lap counter that shows the wrong distance at length
 * forty is worse than one that shows nothing, because it will be believed.
 */
fun lapLabel(laps: Int, mode: LapMode): String? {
    if (mode == LapMode.OFF) return null
    val n = if (laps < 0) 0 else laps
    val metres = mode.metres ?: return "$n"
    return "$n  (${n * metres} m)"
}

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

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // THE BUTTON TABLE. Nine cases for what each control DOES, nine for how each control LOOKS,
    // and both live here rather than in the Activity so Test 1 can walk all eighteen.
    //
    // v4 CHANGED WHAT A PRESS MEANS. Play and pause are now the same toggle: pressing play while
    // it runs pauses it, pressing pause while it is paused starts it again. The two glyphs stay
    // as two glyphs — the symbol does not morph — and the highlight moves between them to say
    // which one the next press would produce.
    //
    // A control that disappears moves the layout, so nothing is ever hidden. What changed is
    // that dim no longer means dead. See tone() for how the two are told apart.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * What pressing a control does. Returning `this` means the press was received and changed
     * nothing, which is not the same as the press being refused: it is the honest answer for
     * pause on a stopwatch that was never started.
     */
    fun press(control: Control, now: Long): Stopwatch = when (control) {
        // Both halves of the toggle. Either glyph, same behaviour, so there is no wrong one to
        // hit — which matters more here than the usual objection to two controls for one thing,
        // because the alternative was a dead button sitting where a live one used to be.
        Control.PLAY -> if (phase == Phase.RUNNING) pause(now) else play(now)
        Control.PAUSE -> when (phase) {
            Phase.RUNNING -> pause(now)
            Phase.PAUSED -> play(now)
            // NOT a toggle from zero, deliberately. Under a strict toggle, pressing pause on a
            // stopwatch showing zeros would START a measurement, and beginning to time something
            // by pressing PAUSE is a surprise rather than a convenience.
            Phase.STOPPED -> this
        }
        Control.STOP -> stop()
        // THE LAP COUNTER IS NOT PART OF THE TIMING MODEL AND MUST NOT BE. It counts lengths of
        // a pool; the stopwatch measures time. Putting it in here would mean a lap press could
        // touch startedAt or accumulated, and the one thing this model has never done in twenty
        // versions is let anything but a transition touch those two fields.
        Control.LAP -> this
    }

    /**
     * How a control looks.
     *
     *   PRIMARY     white, and only ever play while the clock is not running
     *   HIGHLIGHT   what the next press would produce, given where the clock is
     *   SECONDARY   live, pressable, but not the thing the state suggests
     *   DEAD        pressing it does nothing and it looks like nothing will
     *
     * HIGHLIGHT and SECONDARY exist because v4 made dim ambiguous: play is dim while running and
     * pressing it still pauses the clock, so dim alone would have meant two different things on
     * one screen. PRIMARY was added at v5. Four rungs is more than a screen like this wants, and
     * the defence is that each one means exactly one thing and they only ever go one direction.
     */
    fun tone(control: Control): Tone = when (control) {
        // WHITE WHEN THE CLOCK IS NOT RUNNING, and only then. Baba asked for this after v4, and
        // it is a deliberate hole in the rule that the digits are the only white thing: on an
        // idle screen there is no measurement to compete with, and the one thing you almost
        // certainly want is enormous and unmissable. While it runs, play drops to SECONDARY and
        // the digits have the screen to themselves again.
        Control.PLAY -> if (phase == Phase.RUNNING) Tone.SECONDARY else Tone.PRIMARY
        Control.PAUSE -> when (phase) {
            Phase.RUNNING -> Tone.HIGHLIGHT
            Phase.PAUSED -> Tone.SECONDARY
            Phase.STOPPED -> Tone.DEAD
        }
        // Stop is never the suggested next action. There is no state of a stopwatch in which
        // throwing the measurement away is what you probably meant to do next.
        Control.STOP -> if (phase == Phase.STOPPED) Tone.DEAD else Tone.SECONDARY
        // Never drawn in the transport, so its tone is only ever asked for by the tester, where
        // every row is drawn the same way.
        Control.LAP -> Tone.SECONDARY
    }

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
 * HH:MM:SS, ALWAYS, FROM ZERO. All six numbers are on the screen the moment the app opens.
 *
 * This reverses the decision taken at v1 and repeated at v3, and Baba asked for it after using
 * the thing. The old argument was that showing an hour field from zero makes the digits
 * permanently smaller in order to defend against an hour that almost never arrives. That is
 * still true and it is still the cost: eight glyphs instead of five means each digit is roughly
 * a third smaller than v3's.
 *
 * WHAT IT BUYS IS WORTH MORE. The width now NEVER changes. There is no step at the hour, no
 * moment where the digits resize under you, and no branch in the formatter at all — one format
 * string, one length, one measured size for the life of the app. The v3 arrangement had exactly
 * one discontinuity in it and this removes the last one.
 *
 * TRUNCATED, NOT ROUNDED. A stopwatch reports completed time. Rounding would show 10 while 9.6
 * seconds had passed, putting the display ahead of the measurement, which is the wrong direction
 * for a thing whose only job is to be trusted. The first second after start reads 00:00:00,
 * which is correct: no whole second has elapsed.
 *
 * PAST 100 HOURS the hour field takes a third glyph and the digits step down once. Documented
 * rather than prevented; a stopwatch running for four days has other problems.
 */
object Face {
    fun format(ms: Long, display: Display = Display.MULTI): String {
        val t = if (ms < 0L) 0L else ms
        val seconds = t / 1000L
        val s = seconds % 60L
        val minutes = seconds / 60L
        val m = minutes % 60L
        val h = minutes / 60L
        if (display == Display.MULTI) return "%02d:%02d:%02d".format(h, m, s)
        // SINGLE drops the fields that are still empty. Two glyphs for the first minute, five for
        // the first hour, eight after that — and NOTHING between those three, so the size steps
        // twice in a whole hour rather than drifting.
        return when {
            h > 0L -> "%02d:%02d:%02d".format(h, m, s)
            m > 0L -> "%02d:%02d".format(m, s)
            else -> "%02d".format(s)
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
