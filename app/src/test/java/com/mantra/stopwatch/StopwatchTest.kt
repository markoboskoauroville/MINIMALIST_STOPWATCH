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
        assertEquals("00:00.0", Face.format(0))
        assertEquals("00:00.0", Face.format(99))          // not yet a tenth
        assertEquals("00:00.1", Face.format(100))
        assertEquals("00:09.9", Face.format(9_900))
        assertEquals("00:10.0", Face.format(10_000))
        assertEquals("00:59.9", Face.format(59_900))
        assertEquals("01:00.0", Face.format(60_000))      // the minute
        assertEquals("59:59.9", Face.format(3_599_900))
        assertEquals("1:00:00.0", Face.format(3_600_000)) // the hour, where the field appears
        assertEquals("1:00:00.1", Face.format(3_600_100))
        assertEquals("1:59:59.9", Face.format(7_199_900))
        assertEquals("2:00:00.0", Face.format(7_200_000))
    }

    /**
     * Truncation, not rounding. 9.96 seconds have not been 10 seconds yet, and a display that
     * runs ahead of the measurement is the one direction a stopwatch may never fail in.
     */
    @Test
    fun truncatesRatherThanRounds() {
        assertEquals("00:09.9", Face.format(9_960))
        assertEquals("00:00.0", Face.format(50))
        assertEquals("00:59.9", Face.format(59_999))
    }

    /**
     * The commonest way to get a stopwatch wrong: digits that shuffle sideways as they count.
     * Same field, same glyph count, always — checked across a whole minute and a whole hour
     * rather than at two hand-picked instants.
     */
    @Test
    fun widthNeverChangesWithinAField() {
        val belowAnHour = (0 until 3_600_000 step 7_919).map { Face.format(it.toLong()).length }
        assertEquals(setOf(7), belowAnHour.toSet())

        val aboveAnHour = (3_600_000 until 7_200_000 step 7_919).map { Face.format(it.toLong()).length }
        assertEquals(setOf(9), aboveAnHour.toSet())
    }

    @Test
    fun neverPrintsANegative() {
        assertEquals("00:00.0", Face.format(-1))
        assertEquals("00:00.0", Face.format(-100_000))
    }

    /** A repost delay of zero is an unbounded loop wearing a timer's clothes. */
    @Test
    fun theRedrawDelayIsAlwaysBetweenOneAndAHundred() {
        for (ms in 0L..1000L) {
            val d = Face.untilNextTenth(ms)
            assertTrue("delay $d out of range at $ms", d in 1L..100L)
        }
        assertEquals(100L, Face.untilNextTenth(0))
        assertEquals(1L, Face.untilNextTenth(99))
        assertEquals(100L, Face.untilNextTenth(-5))
    }

    // -----------------------------------------------------------------------------------------
    // THE CLOCK. A backwards clock, a background gap, and the difference pause makes to both.
    // -----------------------------------------------------------------------------------------

    @Test
    fun aClockThatMovesBackwardsDoesNotProduceANegative() {
        val running = Stopwatch().play(10_000)
        assertEquals(0L, running.elapsed(9_999))   // one millisecond backwards
        assertEquals(0L, running.elapsed(0))
        assertEquals("00:00.0", Face.format(running.elapsed(9_999)))
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
        assertEquals("00:10.0", Face.format(s.elapsed(104_000)))
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
        assertEquals("00:00.0", Face.format(s.elapsed(999_999)))
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

    @Test
    fun everyButtonInEveryStateHasTheRightAvailability() {
        val stopped = Stopwatch()
        val running = stopped.play(0)
        val paused = running.pause(1_000)

        assertEquals(Phase.STOPPED, stopped.phase)
        assertEquals(Phase.RUNNING, running.phase)
        assertEquals(Phase.PAUSED, paused.phase)

        // stopped: only play can do anything
        assertTrue(stopped.canPlay()); assertFalse(stopped.canPause()); assertFalse(stopped.canStop())
        // running: play is inert, pause and stop are live
        assertFalse(running.canPlay()); assertTrue(running.canPause()); assertTrue(running.canStop())
        // paused: play resumes, pause is inert, stop clears
        assertTrue(paused.canPlay()); assertFalse(paused.canPause()); assertTrue(paused.canStop())
    }

    /**
     * A dim button does nothing when pressed — proven on the state rather than assumed from the
     * flag. The Activity guards the press with canX(); this is the other half of that contract:
     * even if a press slipped through, the model would not move.
     */
    @Test
    fun anUnavailableButtonChangesNothingIfItIsSomehowPressed() {
        val running = Stopwatch().play(0)
        assertEquals(running, running.play(9_999))          // play while running
        val stopped = Stopwatch()
        assertEquals(stopped, stopped.pause(9_999))         // pause while stopped
        assertEquals(stopped, stopped.stop())               // stop while stopped
        val paused = running.pause(1_000)
        assertEquals(paused, paused.pause(9_999))           // pause while paused
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
     * THE CASE ONLY THE BACKWARDS CHECK CAN CATCH, and it was missing until the mutation sweep
     * removed that check and nothing went red.
     *
     * A reboot moves the boot marker by the previous uptime plus the time powered off. When both
     * are small — a device up fifty seconds, restarted, back in five — the marker moves less than
     * the tolerance and the marker detector says nothing. The monotonic clock still went
     * backwards, and that is proof.
     */
    @Test
    fun aQuickRebootIsCaughtByTheBackwardsClockAlone() {
        val s = Stopwatch().play(10_000)
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
        assertEquals("02:03.4", Face.format(back.elapsed(15_000)))
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
        assertEquals("1:00:00.0", Face.format(s.elapsed(3_600_000)))
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
        assertEquals("02:17.0", Face.format(s.elapsed(clock)))
    }
}
