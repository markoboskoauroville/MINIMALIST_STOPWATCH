# HANDOFF — Minimalist Stopwatch

**Current version: 47.** Repository public at `markoboskoauroville/MINIMALIST_STOPWATCH`.
Latest artefact: `47-stopwatch-v47.apk`, tag `v47`.

**v47, 26.9.2026 — R, the real-time clock.** Baba: "Third mode, R. R means real time ... the real
time clock without seconds, minutes and hours in 24 hours format." The mode letter now goes
S → T → R → S. In R the same digits show the wall clock as `H:MM`, twenty-four hours (`9:05`,
`14:30`, `0:00`): the minutes are always two digits, because "9:5" is not a time anybody reads;
the hour keeps the no-leading-zero rule. R has nothing to start. The digits' tap and long press,
the three transport glyphs (drawn DEAD, left in place so nothing moves) and the spoken commands
all do nothing, and the lap count is hidden. The time is read once a second on the second, so a
clock correction or a time-zone change shows within a second. Stored as a second key `clockMode`
beside `timerMode`, so a phone updated from v46 opens in the mode it was left in. New: Face.clock,
AppMode.next/letter, 2 tests (135 now), verify check 94. Tried on the Pixel 7 emulator (API 35) from the published APK: S → T → R → S, R read 1:59 then 2:00 on the minute against `adb shell date`, a tap on the digits and on play in R changed nothing, S came back at 0, and R survived a force-stop.

*This header said 19 until 21.9.2026 and the app was at 44. Nothing reads it, which is exactly
why it rotted — and why the counts further down this page were wrong by a factor of two and the
mutation sweep had been dead for several versions without anybody noticing. Every number written
on this page is now one somebody ran.*

This is the briefing. The reasoning behind every decision, including what was tried and rejected,
is in [`NEXT_DEFAULTS.md`](NEXT_DEFAULTS.md) — which is now the more valuable of the two
documents, because this app was corrected about twenty times against a real phone and most of
those corrections reversed something that had looked obviously right on a build server. What was
and was not proven about the shipped artefact is in [`DELIVERY_RECORD.md`](DELIVERY_RECORD.md).

---

## What it is

A stopwatch and a timer. Black screen, enormous digits, three transport controls, a microphone,
an orientation button, a full-screen button, an S/T mode letter, a power mark and a gear.
Nothing else, ever — and with one press, not even those.

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

Five tabs: **TIMER**, **WATCH**, **LAP**, **VOICE**, **LOOK**. **LOOK** is adjustment: 48 swatches, normal or bold, MULTI or SINGLE — each shown in the
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

### The timer tab, and whose presets they are

**There are no presets in the source.** v45 deleted the `TimerLength` enum — half a minute, one,
three, five, ten, twenty-five — at Baba's word: "all timer presets are defined by the user." Six
durations chosen in advance are six guesses, and the single "save as preset" slot that sat beside
them was a consolation for the person none of the six suited.

The duration is set by **six buttons, three each side**, each with its amount on its face:

    −10m  −1m  −30s   [ 05:00 ]   +30s  +1m  +10m

Both sides are generated from `TIMER_STEPS`, and the label on each face is computed from the same
number the press uses, so a face cannot lie about what it does. **The amount is fixed**, which
reverses the old single pair whose step grew with the number — clever, and unpredictable, because
the same button did a different thing depending on a value you had to be reading to foresee.

Under them is his list. **+ keeps the current duration, a press uses a preset, a hold removes
one.** Twelve at most. The plus is the LAST CELL OF THE SAME LIST rather than a control beside
it, so it wraps with the presets and is always where the next one will appear — under the first
while there is one, after the last forever after.

Stored as one comma-separated string under `timerPresets`, read by `presetsDecode`, which is
total and cannot throw: the worst an unreadable value can do is read back as no presets. The
first read inherits the old single `savedPreset` if one was there, so a phone updating from v44
does not open to an empty row.

## The icon language

**Hollow is off, solid is on**, everywhere: the microphone, the sample lines. No struck-out marks.
A thin slash is the first thing low vision loses, and it is a third mark to read rather than a
difference you see before you read anything.

**Nothing wears a ring.** State is carried by the WEIGHT of the glyph, on a four-step ladder:
white is the one you want next, grey is live, dark grey is live but not the suggestion, nearly
black does nothing and cannot be pressed.

This has now been decided three times and the third should hold, so the whole argument is here
rather than a third of it. v3 removed a ring drawn round every glyph all the time — decoration.
v39 brought a CONDITIONAL ring back, present exactly when a control could be pressed, reasoning
that a mark carrying "will this do anything" is information. **The reasoning was sound and the
screen was still worse for it**, which is the part worth remembering: a mark that is information
to somebody who knows the rule is a shape to everybody else, and at the distance this app is read
from, six thin circles under the digits are six circles. v45 removed them at Baba's word and put
the ladder back. If a fourth session is about to add a ring, the thing to notice is that the
argument FOR one is always correct and has twice been beside the point.

## The gestures

    tap          stops a running clock, starts a stopped one
    tap tap      back to zeros, in either mode
    long press   back to zeros
    pinch out    full screen — the numbers and nothing else
    pinch in     the controls come back

Baba, 21.9.2026: "two taps are resetting the stopwatch — when I mean stopwatch, I mean any mode,
stopwatch or timer." That clause is why nothing in `Stopwatch.kt`'s gesture code knows which mode
is showing. A gesture meaning one thing on the stopwatch and another on the timer would be two
gestures wearing one shape.

**A single tap can only ever pause or start, and that is the whole of v1's rule kept intact.**
Tap-anywhere was removed in v1 because its second state was destructive on ONE ISOLATED TAP:
touch to start, touch again an hour later, measurement gone, with the whole screen as the target.
Reset now needs two taps inside 300ms, or a long press. Neither is a thing a pocket does.

**THE FIRST TAP IS NEVER HELD BACK, and this is the design decision in the change.** Compose will
find a double tap for you through `onDoubleClick`, and the price is that every single tap waits
out the double-tap window first, because until it closes the tap might be the first of two. Three
hundred milliseconds is nothing in a menu and it is three tenths of a second off the front of
every measurement in a stopwatch. So the tap acts immediately and the second one resets ON TOP of
whatever the first did:

    running, tap tap      pauses, then resets       -> zeros
    stopped, tap tap      starts, then resets       -> zeros
    counting in, tap tap  cancels, then resets      -> zeros

Every path ends at zeros, which is what two taps mean, and the intermediate state is the correct
answer to the first tap in its own right. A third rapid tap resets again rather than starting a
new measurement, because `lastTapAt` is not cleared when a double fires — so a burst of panicky
taps ends at zeros instead of quietly starting the clock.

## Full screen

**Press the button beside the orientation control, or pinch out, and every control leaves the
screen.** Only the numbers remain, sized to the whole of the glass. **Pinch in to bring them
back.**

- **The pinch is on the ROOT, not on the digits**, so it is found anywhere on the glass. A way
  out that only works if you land on the numbers is a way out you have to aim for.
- **It is the one thing the black background responds to, and `verify.py` still refuses a
  `.clickable` there.** That is not an inconsistency: v1 removed tap-anywhere because a stray
  touch destroyed a measurement, and a pinch cannot be made by a pocket, a sleeve or one finger.
  The worst it can do is change how much of the screen the numbers take.
- **v45 hung the way out on the long press and it cost too much.** The long press is reset, so
  reset had to be surrendered for as long as full screen was on — written down at the time as a
  real loss. The pinch owns that door now and **the long press means one thing again in both
  modes**, with reset reachable in full screen.
- **It hides controls, which this app otherwise never does.** The rule it appears to break is
  about a control vanishing BECAUSE IT CANNOT ACT, which leaves you guessing and moves everything
  beside it. Here every control leaves at once, because somebody asked. The condition is written
  round the group and round the transport row, never round one glyph, and `verify.py` refuses any
  other condition wrapped round a `Transport` or a `Glyph`.

Three things keep the pinch from acting when nobody asked: two pointers are required, the zoom
accumulator starts fresh for every gesture (hoisted out, an afternoon of small spreads would
eventually add up to a quarter), and it fires once per gesture (without that, carrying on past
the threshold re-fires on every frame). All three are asserted, and all three were proven to fail
on purpose — two of them only after the sweep found nothing was watching them.

The setting survives the app being closed. Going full screen also closes the settings panel,
because a panel open behind a screen that draws no controls would be the only thing on it; both
doors go through one `enterFullscreen()` so neither can forget.

## How to check it

    python3 scripts/verify.py                       93 structural checks, one second
    ./gradlew :app:testReleaseUnitTest              Test 1, 133 cases
    python3 scripts/sabotage.py                     83 mutations, 49 logic and 34 shape
                                                    all 83 caught as of 21.9.2026

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

## The mutation sweep, and what running it found

**It had not run since SpeechRecognizer was removed.** `sabotage.py` still named
`VoiceListener.kt`, a file deleted several versions ago, in the list it copies before it starts —
so it threw `FileNotFoundError` on its own first step, every time, and had done for months. The
path was declared TWICE in the header, which is how a stale one survives being read: the eye
finds the second and assumes the first was the mistake. Nothing runs it in CI, so nothing said so.
Meanwhile `README.md` went on quoting a mutation count it produced.

Repaired at v45, and running it immediately earned its keep twice:

1. **A `verify.py` check had lost its assertion.** v37 rewrote the rule about tap-anywhere,
   replaced the `check()` call, and left `background_click` computed and never used. For eight
   versions nothing watched whether the black behind everything was pressable — the exact fault
   v1 existed to remove. The sweep printed `SURVIVED: tap-anywhere comes back on the background`,
   which is precisely what it is for. Restored as its own check.
2. **A guard in the new preset code could not fail.** `presetAdd` opened with
   `if (seconds in presets) return presets`, and breaking it changed nothing, because a
   `distinct()` further down had been doing the whole job. A guard that cannot fail is worse than
   none: it is a second place a reader believes the rule lives. Deleted.

**It went on earning its keep at v46.** Ten mutations were written for the new gestures and
**four survived the first sweep**: three rules of the brand-new code that nothing was watching
(the pinch firing once per gesture, the panel guard, and arming the double-tap window), and one
check that had been `caught` at v45 and quietly stopped working — it searched a 200-character
window after `.background(BACKGROUND)` for a `.clickable`, and the pinch handler made the chain
longer than the window. A character count was never the right question; it now extracts the root
modifier chain and reads it whole. **Twice now, a check in this repository has been broken by the
commit that made the file longer**, and both times only the sweep said so.

A pattern worth naming, because it has now happened three times: **asserting that a thing EXISTS
rather than that it is USED.** The first version of the pinch check asked whether `var fired` was
declared. A flag nobody reads is declared perfectly well, so breaking the condition that reads it
left the check green.

### The seven stale anchors, re-pointed — and what re-pointing them found

They were SKIPs rather than survivors: the anchor was not found, so the mutation never ran, and
a SKIP in a long list reads almost like a catch. Seven rules this repository claimed to guard
were unguarded. All seven now run:

    a control label is typed at the call site        Transport() had lost its ::commit tail
    the name shown for a control is typed by hand    the reminder row became the tip row
    the meter is fed a raw level                     moved into MaMeter.kt when it was extracted
    the microphone is left running                   v.stop() belonged to the deleted recogniser
    the settings panel is moved off the top          the panel went full-screen at v38
    the tick loop becomes unbounded                  three loops wear it now; anchored on the clock's
    accumulated is not persisted                     the constants moved into a Keys object

**None of them was a rule that had stopped being true.** Every one was an anchor that had stopped
matching — which is the more dangerous shape, because the rule looks guarded.

**Re-pointing them exposed three checks that were passing while watching nothing**, and all three
are the same mistake:

    the meter's clamp        asked whether "coerceIn(0f, 1f)" appeared ANYWHERE in MaMeter.kt.
                             It appears three times. Deleting the one in maNorm — the clamp that
                             actually keeps the bar inside its track — left the check green.
    the panel's position     asked whether "Alignment.TopCenter" appeared anywhere in the screen.
                             The microphone, the power mark and the mode letter all use it.
                             Moving the panel to the bottom left the check green.
    the control's name       asked whether Vocabulary.display appeared at all. It appears three
                             times. Hardcoding the name in the field somebody reads left it green.

**ASSERTING THAT A THING EXISTS RATHER THAN THAT IT IS USED.** That is now four in this
repository — those three plus `var fired` at v46 — and before them a colour constant asserted to
exist rather than be read, and a check searching for a function that had been deleted. It is the
single most productive fault to look for here. All three now read the specific line.

**MaMeter.kt was also added to `MUTABLE`.** A file that is mutated but not in that list is never
stashed and never restored, so a crash mid-sweep would leave the edit behind — the one thing this
script must never do.

Two more stale anchors were in the LOGIC half and are fixed the same way. One of them could not
simply be re-pointed, and what it turned up is the open question below.

**The sweep is clean for the first time: 83 of 83 — 49 logic and 34 shape — 0 survived, 0
skipped.** It has never been in that state before.

### OPEN, FOR BABA: MULTI and SINGLE now do exactly the same thing

The mutation *"the hour field is dropped below an hour, so the width moves again"* guarded the
original bargain of the FIELDS setting: **MULTI** showed `HH:MM:SS` from zero so the width never
changed, **SINGLE** showed only the fields with something in them so the digits were larger. Two
answers to one real question, which is what made it a setting rather than a decision.

At v43/v44 the padding was removed at Baba's word — *"the colon already carries the position"* —
and that is a good change. But it was applied to **both** branches, and the two are now textually
identical:

    if (display == Display.MULTI) return when {
        h > 0L -> "$h:$m:$s"
        m > 0L -> "$m:$s"
        else   -> "$s"
    }
    return when {            // SINGLE — the same three lines
        h > 0L -> "$h:$m:$s"
        m > 0L -> "$m:$s"
        else   -> "$s"
    }

So **the FIELDS row in the LOOK tab is a dead control**: two cells, a tick that moves between
them, and no difference on the screen either way. That is precisely the fault this app has
refused everywhere else — a control that cannot do anything, looking like it can.

Nothing was changed here, because which way it should go is a design decision and not mine:

  1. **Remove the FIELDS setting.** Honest, and one less thing in the panel. MULTI was the
     default and the no-padding face is now the only face.
  2. **Give MULTI its fixed width back**, padded, so the setting means something again —
     reversing part of v43 for the MULTI branch only.

Until one is chosen, `verify.py` has no check on this: a check asserting they differ would go red
today, and a check asserting they are the same would enshrine a dead control.

## What has never been run on a phone by the machine that built it

Test 2, Test 4, G6 and G8 are unrun. The digits, the greys, the pads and the matcher have been
proven only as arithmetic. The full list with reasons is in `DELIVERY_RECORD.md`, and its NOT
TESTED block is longer than its gate list on purpose.
