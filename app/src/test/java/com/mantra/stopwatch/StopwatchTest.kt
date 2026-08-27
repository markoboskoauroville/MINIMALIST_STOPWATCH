package com.mantra.stopwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST 1 — THE MECHANISM, ALONE.
 *
 * No Activity, no emulator, no clock. `now` is a number this file chooses, which is the only way
 * to test an hour boundary without waiting an hour, and the only way to simulate a reboot at all.
 *
 * This is the single copy of these checks. It runs on a plain JVM here during development and
 * again in CI as `gradle :app:testDebugUnitTest`. Two copies with a rule about keeping them in
 * step are still two copies, and the rule is eventually not followed.
 *
 * Every check below was sabotaged once before it was trusted — the code it watches was broken on
 * purpose and the check confirmed red, then repaired and confirmed green. The record of which
 * mutation was used for which check is in HANDOFF.md, so a later session can repeat it rather
 * than take this sentence on faith.
 */
class StopwatchTest {

    // -----------------------------------------------------------------------------------------
    // THE FACE. Boundaries are where format bugs live, so every one of them is named.
    // -----------------------------------------------------------------------------------------

    @Test
    fun formatsAtTheBoundaries() {
        assertEquals("00:00:00", Face.format(0))
        assertEquals("00:00:00", Face.format(999))         // not yet a whole second
        assertEquals("00:00:01", Face.format(1_000))
        assertEquals("00:00:09", Face.format(9_900))
        assertEquals("00:00:10", Face.format(10_000))
        assertEquals("00:00:59", Face.format(59_999))
        assertEquals("00:01:00", Face.format(60_000))      // the minute
        assertEquals("00:59:59", Face.format(3_599_999))
        assertEquals("01:00:00", Face.format(3_600_000))   // the hour, and the width does not move
        assertEquals("01:00:01", Face.format(3_601_000))
        assertEquals("01:59:59", Face.format(7_199_999))
        assertEquals("02:00:00", Face.format(7_200_000))
        assertEquals("10:00:00", Face.format(36_000_000))
        assertEquals("99:59:59", Face.format(359_999_999)) // the last figure that fits in six
    }

    /**
     * Truncation, not rounding. 9.96 seconds have not been 10 seconds yet, and a display that
     * runs ahead of the measurement is the one direction a stopwatch may never fail in.
     */
    @Test
    fun truncatesRatherThanRounds() {
        assertEquals("00:00:09", Face.format(9_600))
        assertEquals("00:00:00", Face.format(500))
        assertEquals("00:00:59", Face.format(59_999))
        assertEquals("00:00:00", Face.format(999))
    }

    /**
     * The commonest way to get a stopwatch wrong: digits that shuffle sideways as they count.
     * Same field, same glyph count, always — checked across a whole minute and a whole hour
     * rather than at two hand-picked instants.
     */
    @Test
    fun widthNeverChangesWithinAField() {
        // v4 shows all six numbers from zero, so there is no longer a step at the hour and this
        // check is stronger than it was: ONE length, across the whole range, for ever.
        val everything = (0 until 7_200_000 step 7_919).map { Face.format(it.toLong()).length }
        assertEquals(setOf(8), everything.toSet())
        assertEquals(8, Face.format(0).length)
        assertEquals(8, Face.format(359_999_999).length)
    }

    @Test
    fun neverPrintsANegative() {
        assertEquals("00:00:00", Face.format(-1))
        assertEquals("00:00:00", Face.format(-100_000))
    }

    /** A repost delay of zero is an unbounded loop wearing a timer's clothes. */
    @Test
    fun theRedrawDelayIsAlwaysBetweenOneAndASecond() {
        for (ms in 0L..10_000L) {
            val d = Face.untilNextSecond(ms)
            assertTrue("delay $d out of range at $ms", d in 1L..1000L)
        }
        assertEquals(1000L, Face.untilNextSecond(0))
        assertEquals(1L, Face.untilNextSecond(999))
        assertEquals(1000L, Face.untilNextSecond(-5))
    }

    // -----------------------------------------------------------------------------------------
    // THE PALETTE. A swatch that cannot be read is a setting that turns the app off.
    // -----------------------------------------------------------------------------------------

    /**
     * design-language.md 13: a control should not offer settings that defeat it. The thing this
     * one could defeat is legibility, so every swatch has to clear a real contrast ratio against
     * the only background this app has. 4.5 is the WCAG threshold for ordinary text and these
     * digits are enormous, so it is a floor with room under it rather than a target.
     */
    @Test
    fun everySwatchIsLegibleOnBlack() {
        val worst = Palette.SWATCHES.minOf { Palette.contrastOnBlack(it) }
        for (c in Palette.SWATCHES) {
            val ratio = Palette.contrastOnBlack(c)
            assertTrue("swatch %08X has contrast %.2f".format(c, ratio), ratio >= 4.5)
        }
        assertTrue("worst swatch is $worst", worst >= 4.5)
    }

    /** The grid has to fill its rows exactly, or the last row is ragged and reads as a mistake. */
    @Test
    fun theGridIsRectangular() {
        assertEquals(24, Palette.SWATCHES.size)
        assertEquals(0, Palette.SWATCHES.size % Palette.COLUMNS)
        assertEquals(4, Palette.SWATCHES.size / Palette.COLUMNS)
    }

    /** Two identical swatches in a grid is a cell that does nothing and looks like it should. */
    @Test
    fun noSwatchAppearsTwice() {
        assertEquals(Palette.SWATCHES.size, Palette.SWATCHES.toSet().size)
    }

    /** White is the default and it must be reachable, or there is no way back to the original. */
    @Test
    fun theDefaultIsInTheGrid() {
        assertTrue(Palette.DEFAULT in Palette.SWATCHES)
        assertEquals(0xFFFFFFFF, Palette.DEFAULT)
    }

    /**
     * A stored value from a future version, a corrupted preference, or a colour removed from the
     * grid by a later edit must all land on white rather than on something invisible.
     */
    @Test
    fun aColourThatIsNotInTheGridFallsBackToWhite() {
        assertEquals(Palette.DEFAULT, Palette.sanitise(0xFF000000))
        assertEquals(Palette.DEFAULT, Palette.sanitise(0))
        assertEquals(Palette.DEFAULT, Palette.sanitise(-1))
        assertEquals(0xFFE8A64B, Palette.sanitise(0xFFE8A64B))
    }

    /** The tick on the chosen swatch has to be visible on the swatch it is sitting on. */
    @Test
    fun theMarkOnASwatchIsAlwaysVisibleAgainstIt() {
        for (c in Palette.SWATCHES) {
            val mark = Palette.markOn(c)
            val lum = Palette.luminance(c)
            val markLum = Palette.luminance(mark)
            val lighter = maxOf(lum, markLum)
            val darker = minOf(lum, markLum)
            val ratio = (lighter + 0.05) / (darker + 0.05)
            assertTrue("mark on %08X has contrast %.2f".format(c, ratio), ratio >= 3.0)
        }
    }

    // -----------------------------------------------------------------------------------------
    // THE CLOCK. A backwards clock, a background gap, and the difference pause makes to both.
    // -----------------------------------------------------------------------------------------

    @Test
    fun aClockThatMovesBackwardsDoesNotProduceANegative() {
        val running = Stopwatch().play(10_000)
        assertEquals(0L, running.elapsed(9_999))   // one millisecond backwards
        assertEquals(0L, running.elapsed(0))
        assertEquals("00:00:00", Face.format(running.elapsed(9_999)))
    }

    /** Ten minutes in the background is ten more minutes, not ten fewer. */
    @Test
    fun aBackgroundGapWhileRunningAddsAllOfIt() {
        val s = Stopwatch().play(1_000)
        assertEquals(600_000L, s.elapsed(601_000))
    }

    @Test
    fun aBackgroundGapWhilePausedAddsNothing() {
        val paused = Stopwatch().play(1_000).pause(6_000)
        assertEquals(5_000L, paused.elapsed(6_000))
        assertEquals(5_000L, paused.elapsed(606_000))   // ten minutes later, unchanged
    }

    /**
     * The same gap, the two phases, side by side. Written as one test on purpose: the two
     * numbers being different is the assertion, and separated they could both drift together.
     */
    @Test
    fun theSameGapCountsWhenRunningAndNotWhenPaused() {
        val gap = 300_000L
        val running = Stopwatch().play(1_000)
        val paused = Stopwatch().play(1_000).pause(1_000)
        assertEquals(gap, running.elapsed(1_000 + gap))
        assertEquals(0L, paused.elapsed(1_000 + gap))
    }

    // -----------------------------------------------------------------------------------------
    // PAUSE AND RESUME. The arithmetic that pause introduced, attacked directly.
    // -----------------------------------------------------------------------------------------

    @Test
    fun theInterval_between_pause_and_play_isNotCounted() {
        var s = Stopwatch().play(0)
        s = s.pause(5_000)              // five seconds measured
        assertEquals(5_000L, s.elapsed(5_000))
        s = s.play(65_000)              // a minute of standing still
        assertEquals(5_000L, s.elapsed(65_000))
        assertEquals(8_000L, s.elapsed(68_000))   // three more seconds, not sixty-three
    }

    @Test
    fun severalPausesAddUpAndNothingIsCountedTwice() {
        var s = Stopwatch().play(0)
        s = s.pause(1_000)              // 1s
        s = s.play(10_000)
        s = s.pause(12_000)             // +2s = 3s
        s = s.play(50_000)
        s = s.pause(53_000)             // +3s = 6s
        s = s.play(100_000)
        assertEquals(6_000L, s.elapsed(100_000))
        assertEquals(10_000L, s.elapsed(104_000))   // +4s = 10s
        assertEquals("00:00:10", Face.format(s.elapsed(104_000)))
    }

    @Test
    fun playTwiceInARowDoesNotRestartOrJump() {
        val first = Stopwatch().play(1_000)
        val second = first.play(50_000)
        assertEquals(first, second)                        // the same object's worth of state
        assertEquals(1_000L, second.startedAt)             // NOT moved to 50_000
        assertEquals(9_000L, second.elapsed(10_000))       // nine seconds, not restarted
    }

    @Test
    fun pauseTwiceDoesNotSubtractTwice() {
        val once = Stopwatch().play(0).pause(4_000)
        val twice = once.pause(9_000)
        assertEquals(once, twice)
        assertEquals(4_000L, twice.elapsed(9_000))
        assertEquals(4_000L, twice.elapsed(1_000_000))
    }

    @Test
    fun pausingAStoppedClockDoesNothing() {
        val s = Stopwatch().pause(5_000)
        assertEquals(Phase.STOPPED, s.phase)
        assertEquals(0L, s.elapsed(5_000))
    }

    // -----------------------------------------------------------------------------------------
    // STOP. From both live phases, and it must reach EXACTLY zero, not nearly.
    // -----------------------------------------------------------------------------------------

    @Test
    fun stopFromRunningReachesExactlyZero() {
        val s = Stopwatch().play(1_000).stop()
        assertEquals(Phase.STOPPED, s.phase)
        assertEquals(0L, s.accumulated)
        assertEquals(0L, s.startedAt)
        assertEquals(0L, s.elapsed(999_999))
        assertEquals("00:00:00", Face.format(s.elapsed(999_999)))
    }

    @Test
    fun stopFromPausedReachesExactlyZero() {
        val s = Stopwatch().play(1_000).pause(45_000).stop()
        assertEquals(Phase.STOPPED, s.phase)
        assertEquals(0L, s.accumulated)
        assertEquals(0L, s.elapsed(999_999))
    }

    /** Stop, then play: from zero, resuming nothing. The half-reset that leaves accumulated. */
    @Test
    fun playingAfterStopStartsFromZeroRatherThanResuming() {
        val s = Stopwatch().play(0).pause(30_000).stop().play(100_000)
        assertEquals(0L, s.elapsed(100_000))
        assertEquals(2_000L, s.elapsed(102_000))
    }

    // -----------------------------------------------------------------------------------------
    // THE NINE CASES. Three states, three buttons, walked exhaustively rather than sampled.
    // -----------------------------------------------------------------------------------------

    /**
     * THE NINE CASES OF HOW EACH CONTROL LOOKS. Three controls, three phases, walked exhaustively
     * rather than sampled, and written as a table so a change to one cell is visible as a change
     * to one cell.
     *
     * HIGHLIGHT is what the next press would produce. SECONDARY is live but not suggested. DEAD
     * is the only one that does nothing, and after v4 there are just two of those.
     */
    @Test
    fun everyControlInEveryPhaseHasTheRightTone() {
        val stopped = Stopwatch()
        val running = stopped.play(0)
        val paused = running.pause(1_000)

        assertEquals(Phase.STOPPED, stopped.phase)
        assertEquals(Phase.RUNNING, running.phase)
        assertEquals(Phase.PAUSED, paused.phase)

        //                        PLAY                PAUSE               STOP
        // stopped:               start it            nothing to freeze   already zero
        assertEquals(Tone.HIGHLIGHT, stopped.tone(Control.PLAY))
        assertEquals(Tone.DEAD, stopped.tone(Control.PAUSE))
        assertEquals(Tone.DEAD, stopped.tone(Control.STOP))
        // running:               also pauses         freeze it           throw it away
        assertEquals(Tone.SECONDARY, running.tone(Control.PLAY))
        assertEquals(Tone.HIGHLIGHT, running.tone(Control.PAUSE))
        assertEquals(Tone.SECONDARY, running.tone(Control.STOP))
        // paused:                resume              also resumes        throw it away
        assertEquals(Tone.HIGHLIGHT, paused.tone(Control.PLAY))
        assertEquals(Tone.SECONDARY, paused.tone(Control.PAUSE))
        assertEquals(Tone.SECONDARY, paused.tone(Control.STOP))

        // Every one of the nine was named above. If a phase or a control is ever added, this
        // count fails before anybody notices the table is short.
        val cases = Phase.entries.size * Control.entries.size
        assertEquals(9, cases)
    }

    /**
     * THE NINE CASES OF WHAT EACH CONTROL DOES, which after v4 is not the same table.
     *
     * Play and pause are one toggle wearing two glyphs: pressing play while it runs pauses it,
     * pressing pause while it is paused starts it again. This is the behaviour Baba asked for
     * after using v3, and it is the reason tone() had to grow a third value — play is dim while
     * running AND still does something, so dim could no longer mean dead.
     */
    @Test
    fun pressingEachControlInEachPhaseDoesTheRightThing() {
        val stopped = Stopwatch()
        val running = stopped.play(0)
        val paused = running.pause(1_000)

        // PLAY is a full toggle
        assertEquals(Phase.RUNNING, stopped.press(Control.PLAY, 0).phase)
        assertEquals(Phase.PAUSED, running.press(Control.PLAY, 1_000).phase)
        assertEquals(Phase.RUNNING, paused.press(Control.PLAY, 2_000).phase)

        // PAUSE toggles between running and paused, and does NOT start from zeros: beginning to
        // time something by pressing PAUSE would be a surprise rather than a convenience.
        assertEquals(stopped, stopped.press(Control.PAUSE, 5_000))
        assertEquals(Phase.PAUSED, running.press(Control.PAUSE, 1_000).phase)
        assertEquals(Phase.RUNNING, paused.press(Control.PAUSE, 2_000).phase)

        // STOP always means zeros
        assertEquals(Phase.STOPPED, stopped.press(Control.STOP, 0).phase)
        assertEquals(Phase.STOPPED, running.press(Control.STOP, 1_000).phase)
        assertEquals(Phase.STOPPED, paused.press(Control.STOP, 2_000).phase)
    }

    /**
     * The toggle must not restart or double-count. Pressing play to pause banks the segment
     * exactly once, and pressing pause to resume carries the banked figure forward.
     */
    @Test
    fun theToggleBanksTimeExactlyOnceHoweverItIsPressed() {
        val a = Stopwatch().play(0)
        val pausedByPlay = a.press(Control.PLAY, 30_000)          // play used as pause
        assertEquals(30_000L, pausedByPlay.elapsed(90_000))       // the gap after it is not counted

        val resumedByPause = pausedByPlay.press(Control.PAUSE, 100_000)  // pause used as play
        assertEquals(30_000L, resumedByPause.elapsed(100_000))
        assertEquals(35_000L, resumedByPause.elapsed(105_000))

        val pausedAgain = resumedByPause.press(Control.PLAY, 110_000)
        assertEquals(40_000L, pausedAgain.elapsed(999_999))       // 30 + 10, banked twice, never doubled
    }

    /**
     * A DEAD control changes nothing when pressed — proven on the state rather than assumed from
     * the flag. The Activity guards the press with tone(); this is the other half of that
     * contract: even if a press slipped through, the model would not move.
     */
    @Test
    fun aDeadControlChangesNothingIfItIsSomehowPressed() {
        val stopped = Stopwatch()
        assertEquals(Tone.DEAD, stopped.tone(Control.PAUSE))
        assertEquals(stopped, stopped.press(Control.PAUSE, 9_999))
        assertEquals(Tone.DEAD, stopped.tone(Control.STOP))
        assertEquals(stopped, stopped.press(Control.STOP, 9_999))

        // And the underlying transitions stay idempotent whatever route reaches them.
        val running = Stopwatch().play(0)
        assertEquals(running, running.play(9_999))
        val paused = running.pause(1_000)
        assertEquals(paused, paused.pause(9_999))
    }

    // -----------------------------------------------------------------------------------------
    // SURVIVING ROTATION, BACKGROUNDING, PROCESS DEATH AND REBOOT.
    // The bug worth testing hardest: coming back in the wrong phase.
    // -----------------------------------------------------------------------------------------

    private fun reload(s: Stopwatch, savedAt: Long, now: Long, marker: Long = 1_000_000, markerNow: Long = 1_000_000) =
        Stopwatch.restore(s.phase, s.startedAt, s.accumulated, marker, markerNow, savedAt, now)

    @Test
    fun aRunningStopwatchComesBackRunningAndKeepsCounting() {
        val s = Stopwatch().play(1_000)
        val back = reload(s, savedAt = 5_000, now = 605_000)     // ten minutes away
        assertEquals(Phase.RUNNING, back.phase)
        assertEquals(604_000L, back.elapsed(605_000))
    }

    @Test
    fun aPausedStopwatchComesBackPausedAndUnchanged() {
        val s = Stopwatch().play(1_000).pause(31_000)
        val back = reload(s, savedAt = 31_000, now = 631_000)
        assertEquals(Phase.PAUSED, back.phase)
        assertEquals(30_000L, back.elapsed(631_000))
    }

    @Test
    fun aStoppedStopwatchComesBackAtZero() {
        val back = reload(Stopwatch(), savedAt = 10_000, now = 900_000)
        assertEquals(Phase.STOPPED, back.phase)
        assertEquals(0L, back.elapsed(900_000))
    }

    /**
     * REBOOT, detector one: the monotonic clock has gone backwards, which it cannot do within a
     * boot. Proof rather than suspicion.
     */
    @Test
    fun aRunningStopwatchAcrossARebootGoesToZeroRatherThanLying() {
        val s = Stopwatch().play(3_000_000)               // fifty minutes of uptime
        val back = Stopwatch.restore(
            s.phase, s.startedAt, s.accumulated,
            savedBootMarker = 1_000_000, bootMarkerNow = 4_100_000,   // marker moved by the uptime
            lastSeen = 3_600_000, now = 20_000,                        // twenty seconds since boot
        )
        assertEquals(Phase.STOPPED, back.phase)
        assertEquals(0L, back.elapsed(20_000))
    }

    /**
     * REBOOT, detector two: uptime already exceeds what was last seen, so the backwards check
     * misses it and only the boot marker catches it.
     */
    @Test
    fun theBootMarkerCatchesARebootTheBackwardsCheckMisses() {
        val s = Stopwatch().play(1_000)
        val back = Stopwatch.restore(
            s.phase, s.startedAt, s.accumulated,
            savedBootMarker = 1_000_000, bootMarkerNow = 1_200_000,   // 200s later: a reboot
            lastSeen = 2_000, now = 500_000,                           // now > lastSeen: no proof here
        )
        assertEquals(Phase.STOPPED, back.phase)
    }

    /**
     * THE CASE ONLY THE BACKWARDS CHECK CAN CATCH.
     *
     * A reboot moves the boot marker by the previous uptime plus the time powered off. When both
     * are small — a device up fifty seconds, restarted, back in five — the marker moves less than
     * the tolerance and the marker detector says nothing. The monotonic clock still went
     * backwards, and that is proof.
     *
     * THIS TEST WAS PASSING FOR THE WRONG REASON UNTIL THE MUTATION SWEEP SAID SO. It used to
     * start the clock at 10_000 and restore at 3_000, which meant the saved instant was in the
     * FUTURE, and the belt-and-braces guard at the end of restore() caught it. Deleting the
     * backwards detector entirely changed nothing and this test stayed green. `startedAt` is now
     * 1_000, safely behind `now`, so the future guard cannot fire and the only thing standing
     * between this and a wrong answer is the detector the test claims to be about.
     */
    @Test
    fun aQuickRebootIsCaughtByTheBackwardsClockAlone() {
        val s = Stopwatch().play(1_000)
        val back = Stopwatch.restore(
            s.phase, s.startedAt, s.accumulated,
            savedBootMarker = 1_000_000, bootMarkerNow = 1_050_000,   // 50s: inside the tolerance
            lastSeen = 45_000, now = 3_000,                            // but uptime went backwards
        )
        assertEquals(Phase.STOPPED, back.phase)
        assertEquals(0L, back.elapsed(3_000))
    }

    /** A paused measurement owes the clock nothing, so a reboot costs it nothing. */
    @Test
    fun aPausedStopwatchSurvivesARebootIntact() {
        val s = Stopwatch().play(0).pause(123_400)
        val back = Stopwatch.restore(
            s.phase, s.startedAt, s.accumulated,
            savedBootMarker = 1_000_000, bootMarkerNow = 9_000_000,
            lastSeen = 200_000, now = 15_000,
        )
        assertEquals(Phase.PAUSED, back.phase)
        assertEquals(123_400L, back.elapsed(15_000))
        assertEquals("00:02:03", Face.format(back.elapsed(15_000)))
    }

    /**
     * A time server correcting the clock by a few seconds is NOT a reboot, and must not be read
     * as one. This is the false positive the tolerance exists to prevent.
     */
    @Test
    fun aSmallClockCorrectionIsNotMistakenForAReboot() {
        val s = Stopwatch().play(1_000)
        val back = Stopwatch.restore(
            s.phase, s.startedAt, s.accumulated,
            savedBootMarker = 1_000_000, bootMarkerNow = 1_002_500,   // 2.5s of NTP step
            lastSeen = 5_000, now = 61_000,
        )
        assertEquals(Phase.RUNNING, back.phase)
        assertEquals(60_000L, back.elapsed(61_000))
    }

    /** The last guard: a saved instant in the future cannot be honoured whatever else concluded. */
    @Test
    fun aSavedInstantInTheFutureFallsBackToZero() {
        val back = Stopwatch.restore(
            Phase.RUNNING, startedAt = 900_000, accumulated = 0,
            savedBootMarker = 1_000_000, bootMarkerNow = 1_000_000,
            lastSeen = 0, now = 100_000,
        )
        assertEquals(Phase.STOPPED, back.phase)
        assertEquals(0L, back.elapsed(100_000))
    }

    // -----------------------------------------------------------------------------------------
    // THE LONG RUN. Where adding deltas would have looked fine for a minute and been wrong here.
    // -----------------------------------------------------------------------------------------

    @Test
    fun anHourOfTicksDoesNotDrift() {
        val s = Stopwatch().play(0)
        // Every tenth of an hour's worth of redraws, each asking the same question independently.
        var t = 0L
        while (t <= 3_600_000L) {
            assertEquals(t, s.elapsed(t))
            t += 100L
        }
        assertEquals("01:00:00", Face.format(s.elapsed(3_600_000)))
    }

    @Test
    fun aThousandPausesStillTotalExactly() {
        var s = Stopwatch()
        var clock = 0L
        repeat(1_000) {
            s = s.play(clock)
            clock += 137L               // measured
            s = s.pause(clock)
            clock += 911L               // not measured
        }
        assertEquals(137_000L, s.elapsed(clock))
        assertEquals("00:02:17", Face.format(s.elapsed(clock)))
    }
}
