# HANDOFF — Minimalist Stopwatch

**Current version: 19.** Repository public at `markoboskoauroville/MINIMALIST_STOPWATCH`.
Latest artefact: `19-stopwatch-v19.apk`, tag `v19`.

This is the briefing. The reasoning behind every decision, including what was tried and rejected,
is in [`NEXT_DEFAULTS.md`](NEXT_DEFAULTS.md) — which is now the more valuable of the two
documents, because this app was corrected about twenty times against a real phone and most of
those corrections reversed something that had looked obviously right on a build server. What was
and was not proven about the shipped artefact is in [`DELIVERY_RECORD.md`](DELIVERY_RECORD.md).

---

## What it is

A stopwatch. Black screen, enormous digits, three transport controls, a microphone, an
orientation button and a gear. Nothing else, ever.

    play    toggle. Starts from zero, resumes from a pause, and PAUSES a running clock
    pause   toggle. Freezes a running clock, resumes a paused one. Does nothing from zeros
    stop    back to zeros

**Play and pause are one toggle wearing two glyphs**, so there is no wrong one to hit. The
symbols never morph — play stays a triangle whether pressing it will start the counting or stop
it. What moves is the highlight.

The three sit **along the bottom in both orientations**. There is no landscape branch.

### The tone ladder — the thing most likely to be misread later

    PRIMARY   100%   WHITE. One cell of the nine: play, while the clock is not running
    HIGHLIGHT  40%   what the next press would produce, given where the clock is
    SECONDARY  24%   live and pressable, but not the thing the state suggests
    DEAD       12%   pressing it does nothing and it looks like nothing will

HIGHLIGHT and SECONDARY are separate because play is dim while the clock runs **and pressing it
still pauses**, so dim alone would have meant two different things on one screen.

PRIMARY is a deliberate hole in "the digits are the only white thing", and it is defensible only
because it **closes the instant a measurement starts**. Test 1 asserts that exactly one control is
white at a time, that it is always play, and that nothing is white while running.

**Nothing is ever hidden.** A control that disappears takes its own location with it.

## The files

    Stopwatch.kt     the timing model, the two button tables, the face. Imports nothing from
                     android.*, and verify.py enforces that
    Palette.kt       48 swatches, the two weights, the flash rule. Imports nothing
    Dsp.kt           FFT, mel features, DTW, the template matcher, sample quality, waveform.
                     Imports nothing
    Voice.kt         the vocabulary, the meter maths, the speech gate, the capture state
                     machine, the one-second light. Imports nothing
    MaMeter.kt       TTT mini's VU meter, ported constant for constant
    MicProbe.kt      AudioRecord, a two-second ring, peaks on demand
    VoiceEngine.kt   the only thing that owns the microphone
    Store.kt         one SharedPreferences file, plus the sample files
    MainActivity.kt  the screen
    StopwatchTest.kt 84 cases, plain JVM, no emulator

**Four of those files import nothing at all.** That is not tidiness. It is why the hard parts of
this app can be attacked in eleven seconds without a phone — and every part that could not be
tested that way is a part that took many versions to get right.

## The timing model

    startedAt        the monotonic instant the CURRENT run segment began
    accumulated      milliseconds banked by every segment BEFORE this one

    running elapsed  = accumulated + (now - startedAt)
    paused  elapsed  = accumulated
    stopped elapsed  = 0

**Never add deltas.** `accumulated` changes at exactly one moment, when a run segment ends, and by
subtraction. A ticker adding 100ms ten times a second looks right for a minute and is visibly
wrong after an hour, which is exactly when the number is relied on.

`now` is always `SystemClock.elapsedRealtime()`: monotonic, unaffected by a time server
correcting the wall clock, and counting through sleep. The wall clock is read in exactly one
place, `Store.bootMarker()`, and verify.py expects exactly one use.

**A reboot** is caught two ways, because each has a hole the other covers: `now < lastSeen` is
proof that a monotonic clock went backwards, and a moved boot marker catches the case where the
device has since been up longer than it was before. RUNNING goes to zeros — a figure that is
silently short is worse than no figure. PAUSED is kept intact, because it holds its whole value
in `accumulated` and never consults the clock.

Everything is written to one file with `commit()` rather than `apply()`, on every transition, on
`onStop`, and every ten seconds while running. `apply()` is a promise to write on a thread that
may never run.

## The face

    MULTI    HH:MM:SS from zero, always. The width NEVER changes
    SINGLE   only the fields that have started: SS, then MM:SS, then HH:MM:SS

**MULTI is the default and the safer one.** SINGLE is bigger — two glyphs instead of eight is
roughly four times the digit height on the same screen — and it steps down twice, at one minute
and at one hour, and never anywhere else. The two options are the same argument with two answers,
which is why it became a switch rather than a decision made once in the source.

Whole seconds either way. **Truncated, not rounded**: a stopwatch reports completed time, and
rounding would put the display ahead of the measurement.

**The digits are sized by binary search against the real text measurer, on a probe of the same
LENGTH as the text.** In a monospaced face a string of the same length is exactly the same width,
so the computed size depends on the length and nothing else, and nothing shuffles sideways as it
counts. The weight is part of the measurement, because bold digits are wider and sizing a normal
face then drawing a bold one is how a layout goes over the edge.

**The flash.** A command registering turns the digits white — or amber, if they are already near
white — for 140ms. A second is a long time to wait to find out whether anything heard you. It
fires only when the state actually changed, so it means "that worked" rather than "I was
touched", and the flash colour is chosen against the current colour so it is always a difference.

## Voice

**SpeechRecognizer is gone from this codebase and a check fails the build if any part of it
returns.** Five versions of evidence: AudioRecord opens the microphone on this phone and delivers
audio every single time, and every time that microphone was handed to the recogniser it churned,
sounded a tone at each session boundary, and recognised nothing. The tone was never something
this app played — it was the recogniser being started and stopped.

What replaced it is template matching. **You record yourself saying each command, three times.**
At run time the last second or two of audio is compared against those recordings and the closest
wins, if it is close enough AND clearly better than the runner-up. Older and dumber than speech
recognition, and better here: no model, no network and no Google service, so nothing can be
missing; no sound, because nothing is started or stopped; language-agnostic, because the template
is whatever you actually said in whatever language; and testable end to end on a plain JVM, which
nothing on the recogniser path ever was.

    AudioRecord owns the microphone permanently, so the meter runs whether commands are armed or
      not, and it keeps a two-second ring
    the ring is read BACKWARDS from the moment speech was noticed, by 250ms, because the level
      only crosses the threshold once a word is already underway and the first consonant is
      quieter than the vowel after it
    a command's score is the MINIMUM across its three samples, never the average: an average is
      dragged down by the one take where a door closed, which is precisely what the other two
      exist to make harmless
    a recording is judged before it is stored — silent, too short and clipped are each refused
      with an instruction rather than a code, because a sampler that keeps room tone as the
      sound of a word fails silently

**The costs, plainly.** It only knows the voice that recorded it, in roughly the conditions it was
recorded in; a different room or a cold will hurt it. It has no idea what a word means, so
something that merely sounds like a template will match. And nothing works until nine samples
exist. The accept threshold (0.55) and margin (0.06) were chosen on the geometry of a cosine
distance, **not measured on a voice** — the tester shows raw scores precisely so they can be set
from evidence.

## The settings panel

Two tabs. **LOOK** is adjustment: 48 swatches, normal or bold, MULTI or SINGLE — each shown in the
thing it describes rather than named in a word. **VOICE** is machinery: nine full-width sample
lines, each carrying the waveform of its own recording, a meter across the top, and the matcher's
raw distance beside each command.

**Press a line to record; it stops when you stop speaking. Long press clears it. Nothing recording
means it is testing.** There is no arm button and no mode switch to understand.

**Every score is a number, not a light.** A light says yes or no; a number is the difference
between "it did not work" and "it was 0.58 and the threshold is 0.55".

The panel never covers the digits, because colour and weight are judged against them, and it is
sized by whichever edge runs out first — v5 sized it by width alone and on a landscape phone it
grew taller than the screen and covered its own way out.

## The icon language

**Hollow is off, solid is on**, everywhere: the microphone, the sample lines. No struck-out marks.
A thin slash is the first thing low vision loses, and it is a third mark to read rather than a
difference you see before you read anything.

## How to check it

    python3 scripts/verify.py                       46 structural checks, one second
    ./gradlew :app:testReleaseUnitTest              Test 1, 84 cases
    python3 scripts/sabotage.py                     the mutation sweep

The sweep edits source in place and **will** be interrupted; it stashes every file it can touch
before starting and restores on the next run. Use `SABOTAGE_SLICE=0:12` to run it in pieces. With
a local kotlinc harness against the four pure files it is minutes rather than an hour.

## The version

**One whole number in one place: `appVersion` in `gradle.properties`.** `versionCode`,
`versionName`, the file name `N-stopwatch-vN.apk`, the tag `vN` and the number shown in the
settings panel are all derived from it. To release, bump it and push. A push that does not bump
publishes nothing and says so loudly, because forgetting to bump is more expensive than a
documents-only run that produced no artefact.

Only the two newest releases are kept. There is no way back to a deleted APK.

## The signing key

A permanent 4096-bit RSA key held as `STOPWATCH_KEYSTORE` and `STOPWATCH_KEYSTORE_PASSWORD`.

    SHA-256  D9:3E:6B:00:8E:99:59:B6:F7:9B:00:CB:F8:2A:A8:9E:
             18:20:B6:7A:9C:3D:AE:B3:DE:4E:E7:BE:93:72:D9:62

Baba holds the only copy outside GitHub, exported at v11. GitHub cannot read those secrets back.
Lose both and the app can never be installed over an existing copy again.

## What every future session should read before touching anything

Five things cost more than everything else in this repository put together:

1. **A grep in a pipeline that matches nothing exits 1.** Under `pipefail` that fails a gate
   BECAUSE THE CODE IS CLEAN. It happened three times, twice after a comment claiming the lesson
   had been learned. Every stage of every pipeline is guarded now.
2. **Never grep source as prose.** Comments have both satisfied checks the code no longer met and
   failed checks the code passed. `verify.py` strips comments in one shared place; use it.
3. **Assert that an edit's anchor matched before replacing it.** Three edits reported success and
   changed nothing, and one of them made a test file look green because the new tests were never
   there to fail. Green looked like proof and was absence.
4. **Every check in verify.py has been wrong at least once**, and nearly all of them read as PASS
   while being wrong — a phase counter printing "0 cases", a check searching for a function that
   had been deleted, a colour constant asserted to exist rather than to be used. The mutation
   sweep found most of them. Run it before believing a check.
5. **The phone is the authority.** Roughly two thirds of the versions here exist because
   something decided on a build server was wrong on glass: the landscape layout, the glyph
   brightness, the tenths, the settings panel that covered its own exit, and the entire speech
   recogniser.

## What has never been run on a phone by the machine that built it

Test 2, Test 4, G6 and G8 are unrun. The digits, the greys, the pads and the matcher have been
proven only as arithmetic. The full list with reasons is in `DELIVERY_RECORD.md`, and its NOT
TESTED block is longer than its gate list on purpose.
