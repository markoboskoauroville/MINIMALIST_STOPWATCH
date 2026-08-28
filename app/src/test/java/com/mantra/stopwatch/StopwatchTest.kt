package com.mantra.stopwatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    // THE GATE. One spoken word must press one button, however many times the recogniser
    // says it heard it.
    // -----------------------------------------------------------------------------------------

    /**
     * THE BUG THIS EXISTS TO STOP, written down so nobody removes the gate as clutter.
     *
     * Partial results repeat: one spoken "start" arrives as "st", then "start", then "start"
     * again. Play is a TOGGLE. Acting on each delivery would start, pause and start the clock
     * for one word, and where it landed would depend on how many partials the recogniser
     * happened to emit — which is to say, on nothing the person did.
     */
    @Test
    fun oneUtteranceFiresOnceHoweverManyPartialsCarryTheWord() {
        val gate = CommandGate()
        gate.newUtterance()
        assertTrue("the first partial acts", gate.allow(1_000))
        assertFalse("the second does not", gate.allow(1_050))
        assertFalse("nor the third", gate.allow(1_120))
        assertFalse("nor the final result carrying the same word", gate.allow(1_400))
    }

    /** A new utterance reopens the gate, but only once the minimum gap has passed. */
    @Test
    fun aNewUtteranceReopensTheGateOnlyAfterTheMinimumGap() {
        val gate = CommandGate()
        gate.newUtterance()
        assertTrue(gate.allow(1_000))

        // The recogniser restarts every couple of seconds. The tail of the SAME word landing in
        // the next utterance must not act again.
        gate.newUtterance()
        assertFalse("too soon after the last command", gate.allow(1_300))

        gate.newUtterance()
        assertTrue("far enough after it to be a second command", gate.allow(1_800))
    }

    /** Without a fresh utterance the gate stays shut no matter how much time passes. */
    @Test
    fun timeAloneDoesNotReopenTheGate() {
        val gate = CommandGate()
        gate.newUtterance()
        assertTrue(gate.allow(1_000))
        assertFalse(gate.allow(60_000))
        assertFalse(gate.allow(3_600_000))
        gate.newUtterance()
        assertTrue(gate.allow(3_600_000))
    }

    /** Two deliberate commands, spoken a second apart, both land. */
    @Test
    fun twoDeliberateCommandsBothLand() {
        val gate = CommandGate()
        gate.newUtterance()
        assertTrue("stop", gate.allow(10_000))
        gate.newUtterance()
        assertTrue("reset, a second later", gate.allow(11_000))
    }

    // -----------------------------------------------------------------------------------------
    // THE DIAGNOSTICS LINE. It is read out loud by somebody holding a phone, so it has to be
    // right: a diagnostics readout that is wrong is worse than none, because it is believed.
    // -----------------------------------------------------------------------------------------

    /**
     * The peak path is what proves audio reaches the app at all, so its ends are asserted. A
     * meter that cannot reach the top on a loud sound, or cannot sit at zero in a quiet room,
     * cannot answer the question it was added to answer.
     */
    @Test
    fun theMeterMovesForRealSamplesAndRestsOnSilence() {
        val vu = Vu()
        assertEquals(0f, vu.fromPeak(0), 0.001f)
        assertEquals("a silent room stays still", 0f, vu.fromPeak(0), 0.001f)

        val loud = Vu()
        var level = 0f
        repeat(20) { level = loud.fromPeak(16_000) }
        assertTrue("a full-scale peak must reach the top of the bar, was $level", level > 0.95f)

        val speech = Vu()
        var mid = 0f
        repeat(20) { mid = speech.fromPeak(4_000) }
        assertTrue("ordinary speech must be clearly visible, was $mid", mid > 0.35f)

        // Attack faster than release, which is the part of TTT mini's curve worth keeping: the
        // bar answers at once and falls back smoothly instead of flickering.
        val decay = Vu()
        repeat(20) { decay.fromPeak(16_000) }
        val afterOne = decay.fromPeak(0)
        assertTrue("it must not drop to nothing in one frame, was $afterOne", afterOne > 0.5f)
    }

    /**
     * The gate is what turns a tone every quarter second into a tone when you speak. Its two
     * edges are the two ways it can fail: never opening, and opening again on the tail of the
     * word that just opened it.
     */
    @Test
    fun theGateOpensOnSpeechAndNotOnItsOwnEcho() {
        val gate = SpeechGate()
        assertFalse("a silent room must not wake the recogniser", gate.shouldOpen(0.02f, 0))
        assertFalse("a fan must not wake it", gate.shouldOpen(0.2f, 0))
        assertTrue("a spoken word must wake it", gate.shouldOpen(0.5f, 0))

        gate.sessionEnded(1_000)
        assertFalse("the tail of that word must not reopen it", gate.shouldOpen(0.9f, 1_100))
        assertFalse("not at the boundary either", gate.shouldOpen(0.9f, 2_100))
        assertTrue("but the next thing said must", gate.shouldOpen(0.9f, 2_300))
    }

    /**
     * A light that never goes out cannot show the second command. This is the whole test Baba
     * described: say start, watch start light, watch it go dark, say it again.
     */
    @Test
    fun theLitWordGoesDarkSoTheNextOneCanBeSeen() {
        val lit = Lit.of(Control.PLAY, 5_000)
        assertTrue(lit.isLit(Control.PLAY, 5_000))
        assertTrue(lit.isLit(Control.PLAY, 5_900))
        assertFalse("a second later it is dark again", lit.isLit(Control.PLAY, 6_000))
        assertFalse("and it never lights a word that was not said", lit.isLit(Control.STOP, 5_100))
        assertFalse("nothing is lit before anything is heard", Lit().isLit(Control.PLAY, 0))
    }

    // -----------------------------------------------------------------------------------------
    // THE MATCHER. This replaced SpeechRecognizer entirely, so it is the part that has to be
    // right, and unlike everything the recogniser ever did it can be walked without a phone.
    // -----------------------------------------------------------------------------------------

    /** A transform is the kind of thing whose correctness should be visible rather than trusted. */
    @Test
    fun theTransformPutsAToneInTheRightBin() {
        val n = 512
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        // Eight whole cycles across the window lands exactly in bin 8, with no leakage to argue
        // about, which makes this a fact rather than an approximation.
        for (i in 0 until n) re[i] = Math.sin(2.0 * Math.PI * 8.0 * i / n)
        Dsp.fft(re, im)

        val mag = DoubleArray(n / 2) { Math.sqrt(re[it] * re[it] + im[it] * im[it]) }
        val peak = mag.indices.maxByOrNull { mag[it] }
        assertEquals("a pure tone must land in its own bin", 8, peak)

        val others = mag.indices.filter { it != 8 }.maxOf { mag[it] }
        assertTrue("and nowhere else: peak $peak, next $others", mag[8] > others * 50)
    }

    private fun tone(hz: Double, ms: Int, amplitude: Double = 0.3): ShortArray {
        val n = Dsp.SAMPLE_RATE * ms / 1000
        return ShortArray(n) {
            val t = it.toDouble() / Dsp.SAMPLE_RATE
            // Two partials, so the mel bands have something to tell apart rather than one line.
            val v = Math.sin(2 * Math.PI * hz * t) * 0.7 + Math.sin(2 * Math.PI * hz * 2.5 * t) * 0.3
            (v * amplitude * 32767).toInt().toShort()
        }
    }

    /**
     * THE PROPERTY THE WHOLE THING RESTS ON: loudness must not change the answer. Without the
     * per-utterance mean subtraction this matcher would mostly be measuring how close the phone
     * was to the mouth.
     */
    @Test
    fun theSameWordLouderIsStillTheSameWord() {
        val quiet = Dsp.features(tone(300.0, 400, amplitude = 0.05))
        val loud = Dsp.features(tone(300.0, 400, amplitude = 0.60))
        assertTrue("neither may be empty", quiet.isNotEmpty() && loud.isNotEmpty())
        val d = Dsp.dtw(quiet, loud)
        assertTrue("a twelvefold change in gain must barely move it, was $d", d < 0.05)
    }

    /** Silence is a case, not an error, and it must not be mistaken for a word. */
    @Test
    fun silenceProducesNothingToMatch() {
        assertTrue(Dsp.features(ShortArray(8000)).isEmpty())
        assertTrue(Dsp.features(ShortArray(10)).isEmpty())
    }

    /** Aligning something with itself is the one distance that is knowable in advance. */
    @Test
    fun aThingMatchesItselfExactly() {
        val f = Dsp.features(tone(400.0, 300))
        assertEquals(0.0, Dsp.dtw(f, f), 1e-9)
    }

    /**
     * Different words must be further apart than the same word said twice. This is the whole
     * claim the matcher makes, expressed as an ordering rather than as a threshold, so it stays
     * true even when the thresholds are retuned on a real voice.
     */
    @Test
    fun differentSoundsAreFurtherApartThanTheSameOneTwice() {
        val a1 = Dsp.features(tone(250.0, 350))
        val a2 = Dsp.features(tone(250.0, 300, amplitude = 0.2))   // same word, again, different
        val b = Dsp.features(tone(900.0, 350))

        val same = Dsp.dtw(a1, a2)
        val different = Dsp.dtw(a1, b)
        assertTrue("same $same must be nearer than different $different", same < different)
    }

    @Test
    fun theMatcherPicksTheRightTemplateAndRefusesTheRest() {
        val templates = listOf(
            Template(Control.PLAY, Dsp.features(tone(250.0, 350))),
            Template(Control.PAUSE, Dsp.features(tone(600.0, 350))),
            Template(Control.STOP, Dsp.features(tone(1400.0, 350))),
        )
        val matcher = TemplateMatcher()

        assertEquals(Control.PLAY, matcher.match(Dsp.features(tone(250.0, 320, 0.5)), templates))
        assertEquals(Control.PAUSE, matcher.match(Dsp.features(tone(600.0, 380, 0.1)), templates))
        assertEquals(Control.STOP, matcher.match(Dsp.features(tone(1400.0, 350, 0.4)), templates))

        assertNull("silence is not a command", matcher.match(emptyList(), templates))
        assertNull("nothing recorded means nothing matches", matcher.match(Dsp.features(tone(250.0, 350)), emptyList()))
    }

    /**
     * A margin, not just a threshold. In a room where somebody is talking everything is a poor
     * match for all three templates and one of them is inevitably the least bad; without the
     * margin the stopwatch would fire on conversation.
     */
    /**
     * THREE SAMPLES PER COMMAND, AND THE BEST ONE COUNTS.
     *
     * The point of recording a command three times is that one of the three will be the bad one
     * — further from the phone, a door closing, a cough. Taking the minimum makes that recording
     * harmless. Taking an average would let it drag the good ones down, which would make three
     * samples WORSE than one and the whole feature pointless.
     */
    @Test
    fun aBadSampleAmongGoodOnesDoesNotSpoilTheCommand() {
        val good = Dsp.features(tone(250.0, 350))
        val alsoGood = Dsp.features(tone(250.0, 320, 0.5))
        val ruined = Dsp.features(tone(1900.0, 350))   // the one where something went wrong

        val templates = listOf(
            Template(Control.PLAY, good),
            Template(Control.PLAY, alsoGood),
            Template(Control.PLAY, ruined),
            Template(Control.PAUSE, Dsp.features(tone(600.0, 350))),
            Template(Control.STOP, Dsp.features(tone(1400.0, 350))),
        )
        val matcher = TemplateMatcher()
        assertEquals(
            "the two good samples must carry it",
            Control.PLAY,
            matcher.match(Dsp.features(tone(250.0, 340, 0.4)), templates),
        )

        // One score per command, not one per sample, so the margin still compares like with like.
        val scores = matcher.scores(Dsp.features(tone(250.0, 340)), templates)
        assertEquals("one score per control", 3, scores.size)
        assertEquals(Control.PLAY, scores.first().first)
    }

    @Test
    fun twoTemplatesThatAreTooAlikeAreRefusedRatherThanGuessedBetween() {
        val templates = listOf(
            Template(Control.PLAY, Dsp.features(tone(500.0, 350))),
            Template(Control.PAUSE, Dsp.features(tone(500.0, 350))),
        )
        assertNull(
            "identical templates must refuse rather than pick one",
            TemplateMatcher().match(Dsp.features(tone(500.0, 350)), templates),
        )
    }

    @Test
    fun theDiagnosticsLineSaysWhatItMeans() {
        assertEquals("s0  rms0  offline", Diagnostics().line())
        assertEquals("s12  rms40  offline", Diagnostics(12, 40).line())
        assertEquals("s3  rms0  online  no match", Diagnostics(3, 0, "no match", offline = false).line())
    }

    /**
     * The reading that separates the two faults that look identical from outside. Many sessions
     * with no level callbacks at all is a recogniser being started and killed in a loop; a few
     * sessions with no callbacks is just a quiet room.
     */
    @Test
    fun aRestartStormIsManySessionsAndNoLevelAtAll() {
        assertTrue(Diagnostics(sessions = 40, rmsCallbacks = 0).looksLikeRestartStorm())
        assertFalse("a quiet room is not a storm", Diagnostics(2, 0).looksLikeRestartStorm())
        assertFalse("levels arriving means the microphone opened",
            Diagnostics(40, 12).looksLikeRestartStorm())
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

    /**
     * The grid has to fill its rows exactly IN BOTH SHAPES, or the last row is ragged in one
     * orientation and reads as a mistake. Forty-eight is the number because it divides by six
     * and by twelve; any future change to the swatch list has to keep that true.
     */
    @Test
    fun theGridIsRectangularInBothOrientations() {
        assertEquals(48, Palette.SWATCHES.size)
        assertEquals(0, Palette.SWATCHES.size % Palette.COLUMNS_PORTRAIT)
        assertEquals(0, Palette.SWATCHES.size % Palette.COLUMNS_LANDSCAPE)
        assertEquals(8, Palette.SWATCHES.size / Palette.COLUMNS_PORTRAIT)
        assertEquals(4, Palette.SWATCHES.size / Palette.COLUMNS_LANDSCAPE)
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

    // -----------------------------------------------------------------------------------------
    // VOICE. The recogniser is not here; the decision about what a heard string MEANS is, and
    // that is the part with edges.
    // -----------------------------------------------------------------------------------------

    @Test
    fun eachControlAnswersToItsOwnWord() {
        assertEquals(Control.PLAY, Heard.match("start"))
        assertEquals(Control.PAUSE, Heard.match("pause"))
        assertEquals(Control.STOP, Heard.match("reset"))
    }

    /** Baba's own list was start, stop and reset. Stop is the middle one, and it is accepted. */
    @Test
    fun stopMeansPauseAndNeverReset() {
        assertEquals(Control.PAUSE, Heard.match("stop"))
        assertEquals(Control.PAUSE, Heard.match("stopped"))
    }

    /** He does not think about which language he is in, and the app should not make him. */
    @Test
    fun theCroatianWordsWork() {
        assertEquals(Control.PLAY, Heard.match("kreni"))
        assertEquals(Control.PAUSE, Heard.match("pauza"))
        assertEquals(Control.PAUSE, Heard.match("stani"))
        assertEquals(Control.STOP, Heard.match("resetiraj"))
        assertEquals(Control.STOP, Heard.match("poništi"))
    }

    /** A recogniser mishears, and refusing the mishearing makes the app look broken. */
    @Test
    fun theCommonMishearingsAreAccepted() {
        assertEquals(Control.PLAY, Heard.match("star"))
        assertEquals(Control.PAUSE, Heard.match("paws"))
        assertEquals(Control.STOP, Heard.match("recept"))
    }

    /** Punctuation, case and surrounding words are what a recogniser actually returns. */
    @Test
    fun theHeardStringIsNormalisedBeforeItIsJudged() {
        assertEquals(Control.PLAY, Heard.match("Start."))
        assertEquals(Control.PLAY, Heard.match("  START  "))
        assertEquals(Control.PLAY, Heard.match("start the clock"))
        assertEquals(Control.STOP, Heard.match("ok, reset!"))
    }

    /**
     * THE REFUSAL, which matters more than any of the matches.
     *
     * A string containing words for two different controls is a sentence, not a command, and
     * choosing one of the two would be inventing an intention. Between freezing a measurement
     * and destroying it, a guess is much worse than doing nothing.
     */
    @Test
    fun aStringMeaningTwoThingsMeansNothing() {
        assertNull(Heard.match("start and stop"))
        assertNull(Heard.match("stop reset"))
        assertNull(Heard.match("start reset"))
    }

    @Test
    fun anythingElseIsNotACommand() {
        assertNull(Heard.match(""))
        assertNull(Heard.match("   "))
        assertNull(Heard.match("hello"))
        assertNull(Heard.match("what time is it"))
        assertNull(Heard.match("!!!"))
    }

    /**
     * No word may belong to two controls. If one ever did, [Heard.match] would refuse it and the
     * command would silently stop working with nothing to show why.
     */
    @Test
    fun noWordBelongsToTwoControls() {
        val seen = mutableMapOf<String, Control>()
        for ((control, words) in Heard.VOCABULARY) {
            for (w in words) {
                val other = seen.put(w, control)
                assertNull("'" + w + "' belongs to both " + other + " and " + control, other)
            }
        }
        assertEquals(3, Heard.VOCABULARY.size)
    }

    /** Every accepted word must survive normalisation, or it can never be matched. */
    @Test
    fun everyWordInTheVocabularyIsReachable() {
        for ((control, words) in Heard.VOCABULARY) {
            for (w in words) {
                assertEquals("'" + w + "' cannot be matched as written", control, Heard.match(w))
            }
        }
    }

    /** The reminder prints the first word of each list, so it cannot name an unaccepted word. */
    @Test
    fun theReminderNamesWordsTheMatcherAccepts() {
        for (control in Control.entries) {
            assertEquals(control, Heard.match(Heard.primary(control)))
        }
        assertEquals("start", Heard.primary(Control.PLAY))
        assertEquals("pause", Heard.primary(Control.PAUSE))
        assertEquals("reset", Heard.primary(Control.STOP))
    }

    // -----------------------------------------------------------------------------------------
    // THE VU CURVE, ported from TTT mini. Bounded, monotone and quiet at rest.
    // -----------------------------------------------------------------------------------------

    @Test
    fun theMeterStaysBetweenZeroAndOneWhateverItIsFed() {
        val vu = Vu()
        for (db in -50..50) {
            val v = vu.fromRms(db.toFloat())
            assertTrue("level " + v + " out of range at " + db + "dB", v in 0f..1f)
        }
        // Straight into the curve as well, past both ends. The bar is drawn as a fraction of a
        // width, so a level above 1 runs off the panel and a level below 0 inverts it.
        val raw = Vu()
        for (n in -30..30) {
            val v = raw.update(n / 10f)
            assertTrue("level " + v + " out of range at " + (n / 10f), v in 0f..1f)
        }
    }

    /**
     * A NOISE GATE, PROVED AT THE LEVEL IT EXISTS FOR.
     *
     * The first version of this only fed silence, which the clamp handles on its own, so
     * deleting the gate entirely changed nothing and the test stayed green. The gate is not
     * about silence — it is about a room that is never quite silent. This feeds the level a
     * quiet room actually reads at and asserts the meter still rests.
     */
    @Test
    fun aQuietRoomDoesNotMoveTheMeter() {
        val vu = Vu()
        var v = 0f
        repeat(200) { v = vu.fromRms(-1.8f) }   // just above the floor: room tone, not speech
        assertEquals("room tone must not register", 0f, v, 0.0001f)

        val speaking = Vu()
        var s = 0f
        repeat(200) { s = speaking.fromRms(3f) }
        assertTrue("ordinary speech must register", s > 0.4f)
    }

    /** Silence must read as still. A meter that twitches at rest is a meter nobody believes. */
    @Test
    fun silenceSettlesToZero() {
        val vu = Vu()
        repeat(20) { vu.fromRms(10f) }
        repeat(200) { vu.fromRms(-10f) }
        assertEquals(0f, vu.fromRms(-10f), 0.0001f)
    }

    /** Louder must never read lower once settled, or the meter is lying about direction. */
    @Test
    fun louderReadsHigher() {
        fun settled(db: Float): Float {
            val vu = Vu()
            var v = 0f
            repeat(200) { v = vu.fromRms(db) }
            return v
        }
        val quiet = settled(0f)
        val normal = settled(5f)
        val loud = settled(10f)
        assertTrue(quiet <= normal)
        assertTrue(normal <= loud)
        assertEquals(1f, loud, 0.01f)
    }

    /** Attack quicker than release: it must rise faster than it falls, as the original does. */
    @Test
    fun itRisesFasterThanItFalls() {
        val up = Vu()
        val rise = up.fromRms(10f)
        val down = Vu()
        repeat(200) { down.fromRms(10f) }
        val fall = 1f - down.fromRms(-10f)
        assertTrue("rise " + rise + " should exceed fall " + fall, rise > fall)
    }

    /**
     * THE SPOKEN VOCABULARY, which is the one thing on this screen a person says out loud.
     *
     * Voice Access matches speech against contentDescription, so these three strings are the
     * commands. They are asserted here rather than trusted because the failure mode is silent:
     * rename one and the button still works, the app still builds, every other test still
     * passes, and the only thing that breaks is a spoken word that stops being heard.
     */
    @Test
    fun theVocabularyIsThreeDistinctWordsAndTheTipCanOnlyComeFromIt() {
        assertEquals("Start", Control.PLAY.spoken)
        assertEquals("Pause", Control.PAUSE.spoken)
        assertEquals("Reset", Control.STOP.spoken)

        val words = Control.entries.map { it.spoken }
        assertEquals("no two controls may answer to the same word", words.size, words.toSet().size)
        assertEquals("one word per control, no more and no fewer", 3, words.size)
        for (w in words) {
            assertTrue("a spoken command cannot be blank", w.isNotBlank())
            assertTrue("a spoken command must be one word", !w.contains(" "))
        }

        // The reminder in the settings panel is built by joining exactly this list. Composing it
        // here as well proves the tip has no vocabulary of its own to drift with.
        assertEquals(
            "\"tap start\"  \"tap pause\"  \"tap reset\"",
            Control.entries.joinToString("  ") { "\"tap " + it.spoken.lowercase() + "\"" },
        )
    }

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
        // stopped:               start it, WHITE     nothing to freeze   already zero
        assertEquals(Tone.PRIMARY, stopped.tone(Control.PLAY))
        assertEquals(Tone.DEAD, stopped.tone(Control.PAUSE))
        assertEquals(Tone.DEAD, stopped.tone(Control.STOP))
        // running:               also pauses         freeze it           throw it away
        assertEquals(Tone.SECONDARY, running.tone(Control.PLAY))
        assertEquals(Tone.HIGHLIGHT, running.tone(Control.PAUSE))
        assertEquals(Tone.SECONDARY, running.tone(Control.STOP))
        // paused:                resume, WHITE       also resumes        throw it away
        assertEquals(Tone.PRIMARY, paused.tone(Control.PLAY))
        assertEquals(Tone.SECONDARY, paused.tone(Control.PAUSE))
        assertEquals(Tone.SECONDARY, paused.tone(Control.STOP))

        // Every one of the nine was named above. If a phase or a control is ever added, this
        // count fails before anybody notices the table is short.
        val cases = Phase.entries.size * Control.entries.size
        assertEquals(9, cases)
    }

    /**
     * WHITE IS THE ONE HOLE IN THE ORIGINAL RULE, so its edges are asserted rather than assumed.
     *
     * The rule was that the digits are the only white thing on the screen. v5 makes play white
     * while the clock is idle, and the defence is that an idle screen has no measurement to
     * compete with. That defence only holds if the white DISAPPEARS the moment it runs, so:
     * exactly one cell of the nine is PRIMARY at a time, it is always play, and it is never
     * PRIMARY while running.
     */
    @Test
    fun exactlyOneControlIsWhiteAndOnlyWhileTheClockIsIdle() {
        val stopped = Stopwatch()
        val running = stopped.play(0)
        val paused = running.pause(1_000)

        for (state in listOf(stopped, running, paused)) {
            val white = Control.entries.filter { state.tone(it) == Tone.PRIMARY }
            if (state.phase == Phase.RUNNING) {
                assertEquals("nothing may be white while it runs", emptyList<Control>(), white)
            } else {
                assertEquals("only play is white, and only when idle", listOf(Control.PLAY), white)
            }
        }
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
