# NEXT_DEFAULTS — why each decision was made

Every entry names what was chosen, what was rejected, and what the rejected option would have
cost. Read this before undoing anything in HANDOFF.md.

---

## 27.8.2026 — tap-anywhere versus three buttons

**Chosen: the buttons are the control. Tap-anywhere is gone entirely.**

The original spec said tap anywhere to start, tap again to reset. Three explicit transport
buttons then arrived, and Baba asked which one survives, recommending the buttons.

Agreed, and the reason is stronger than tidiness. **The two would have disagreed about what a tap
means.** The gesture said start-then-reset; play says start-or-resume. Keeping both would have
put a full-screen reset target directly above a resume button, which is not two ways to do one
thing, it is two contradictory things sharing a surface. The dangerous half is that the gesture's
second state is destructive: a stray touch during a real measurement would have destroyed it,
and the surface area of that mistake is the entire screen.

**Rejected: keeping tap-anywhere but redefining it as play/pause.** That is a third semantics
for the same surface, and it leaves the person working out whether the black area is a control
or a background. Rejected: long-press to reset. Same objection, plus it hides a destructive
action behind a gesture nobody can see.

**What it costs, said plainly.** You lose starting it without aiming, which matters more for low
vision than it does for most people. The mitigation is size rather than gesture: 72dp circles in
portrait and 64dp in landscape, both well above the 48dp minimum touch target, in fixed
positions that never move because no button is ever hidden.

**Enforced, not merely intended.** `verify.py` check 7 goes red if a `clickable` modifier ever
appears on the background again.

## 27.8.2026 — the version is 1, not 2

The repository did not exist. All 83 repositories on the account were listed and none was named
for a stopwatch, a watch, a timer or a clock. The previous session wrote the timing logic and
its test suite on a scratch machine and was cut off before creating anything.

So there was nothing to bump, and calling this v2 would have been a fiction that the next
session would have had to unpick. The transport buttons go in from the start.

## 27.8.2026 — what the digits show

**Chosen: `MM:SS.d` below an hour, `H:MM:SS.d` at and above it. Tenths, truncated.**

Hundredths are a blur while running and the last digit does nothing but flicker; tenths move at
a speed the eye can follow, which is the whole point of a number read across a room.

**Truncated rather than rounded.** A stopwatch reports completed time. Rounding would show 10.0
while 9.96 seconds had passed, and the first frame after start would read 00:00.0 and then jump,
which puts the display ahead of the measurement. That is the wrong direction for a thing whose
only job is to be trusted.

**Rejected: showing `H:MM:SS.d` from zero so the width never changes.** It makes the digits
permanently smaller on every ordinary use in order to defend against an hour that almost never
arrives. One discrete step down in size, an hour into a measurement nobody is staring at, is the
cheaper trade. Past ten hours it steps once more; documented rather than prevented, because a
stopwatch running for ten hours has other problems.

## 27.8.2026 — how the digits are sized

**Chosen: binary search against the real text measurer, on a probe string of the same LENGTH as
the text, not on the text itself.**

This is the whole defence against the commonest way to get a stopwatch wrong. In a monospaced
face every glyph has the same advance, so a string of the same length is exactly the same width.
The computed size therefore depends on the length and nothing else, which means 00:00.0 and
59:59.9 are drawn at the same size and nothing shuffles sideways as it counts. The size changes
at exactly one moment, when the string grows from seven glyphs to nine at one hour.

**Rejected: a formula from the screen width.** It would be a guess about a font that had never
been measured, and it would be wrong by a different amount on every device.

## 27.8.2026 — pause did not change the timing model

The original forbade accumulating deltas. Pause makes subtraction feel awkward, because
`accumulated` sounds like a field that wants to be incremented on a timer. **It is not.** It
changes at exactly one moment, when a run segment ends, and its new value is computed by
subtraction from the segment that just ended. Nothing anywhere adds a tick to a total.

The mutation sweep carries the check: `a paused clock keeps counting` and `pause banks a second
time when already paused` both go red.

## 27.8.2026 — what a reboot costs each phase

**Chosen: RUNNING goes to zeros; PAUSED is kept intact; STOPPED was already zero.**

**Rejected: returning a running stopwatch as PAUSED at the banked figure after a reboot.** It
looks like a preserved measurement and is silently short by however long the device had been up.
A wrong answer delivered confidently is worse than no answer. **Also rejected: throwing away a
paused measurement.** A paused stopwatch holds its whole value in `accumulated` and never
consults the clock, so a reboot costs it nothing and discarding it would be a loss with no cause.

**Two detectors rather than one**, because each has a hole the other covers, and together they
fail towards zeros, which is the safe direction. The boot-marker detector costs one false
positive: moving the phone's clock by more than a minute during a running measurement reads as a
reboot. Rare, deliberate, and it fails to zeros.

## 27.8.2026 — the glyph grey is 55%

Chosen by rendering the play glyph beside the white digits at 40, 45, 50, 55, 60 and 70 percent
and looking at the result, rather than by picking a number in the band that sounded right. Below
50 it starts sinking into the black. Above 60 it begins reading as a second white thing and
competes with the number, which is the one thing the brief forbids.

The ring is an **outline at 18%, not a fill**. A filled disc at any brightness visible enough to
mark a target is a shape with weight, and there are three of them in a row.

Disabled is 22% glyph on a 10% ring: dimmer still, plainly present, and never removed.

## 27.8.2026 — the system bars are left on

**Chosen: edge to edge, black background, bars left where they are.**

The digits are limited by the WIDTH of the screen in both orientations, so hiding the status bar
buys height the digits cannot use. Against that, hiding it takes away the phone's own clock and
battery during a measurement. The background is black, so the bars sit on black and read as part
of the screen.

**Not built, offered instead:** full immersive is one line. It was deliberately not taken,
because "resist adding things" cuts both ways and removing the person's clock is a change to
their phone, not to this app.

## 27.8.2026 — the screen stays awake while PAUSED as well as RUNNING

The original said keep the screen on while running and let it sleep when stopped. Pause did not
exist then. A paused stopwatch is mid-measurement and is precisely the state in which somebody
is reading the number, so it keeps the screen awake. Only STOPPED lets the phone sleep.

## 27.8.2026 — commit rather than apply

`Store.save` uses `commit()`, which writes on the calling thread. `apply()` writes on a
background thread, and the moment it is called from `onStop` the process may be killed before
that thread runs. The write is a handful of primitives and happens on a state change or once
every ten seconds, never per frame. A stopwatch that comes back at the wrong value because a
write lost a race is exactly the bug the brief called the one worth testing hardest.

## 27.8.2026 — one storage mechanism, not three

**Rejected: `rememberSaveable` for rotation, a ViewModel for configuration changes, and a file
for process death.** Rotation, backgrounding and a process kill are three lifecycles with three
survival mechanisms, and carrying the state in all three is three places to disagree. One file,
written on every transition, is one place and survives all three.

The activity also declares `configChanges` for orientation, so rotating does not destroy it and
the digits do not blink at the moment the phone turns, which is the one time a person is
watching the number while rotating.

## 27.8.2026 — no Gradle wrapper was going to be committed, and then one was

**Rejected: `setup-gradle` with a `gradle-version` input and no wrapper**, to avoid committing a
binary. Reversed: the wrapper is the standard mechanism, and pinning the distribution by sha256
in `gradle-wrapper.properties` is strictly better provenance than naming a version in a workflow
file. The wrapper jar is the one at Gradle's own `v8.11.1` tag,
`2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046`.

## 27.8.2026 — lint is blocking from the first build

TTT mini builds with `-x lintVitalRelease` and a comment recording that the noise was never
measured, so the gate was deferred rather than skipped by accident. This app is four source
files. The noise was measured by turning it on: **zero findings**, with
`warningsAsErrors = true` and `allWarningsAsErrors = true` on the Kotlin compiler as well. It
blocks. If it ever cries wolf, the rule is to narrow it in the session that made the noise.

---

## What CI taught, and it cost four runs

**Runs 1 and 2: gate G5 failed because the code was clean.** A grep in a pipeline that matches
nothing exits 1, `pipefail` turns that into a failed assignment, and `bash -e` kills the step
before the echo that would have explained it. Run 1 died with no output at all. Run 2 died one
line further down, on the grep counting unbounded loops, which correctly found none.

TTT mini's workflow carries a comment describing this exact scar on its secret scan and calls its
`|| true` load-bearing. It was walked into anyway. **Every counting pipeline is now guarded, every
count is printed, and only then is anything asserted**, so a red gate always arrives with the
number that made it red. Confirmed both ways: green on the clean tree, red when an unbounded loop
is appended on purpose.

**Run 3: three compile errors, all in the layout.** `maxWidth` and `maxHeight` belong to
`BoxWithConstraintsScope` and that receiver is gone the moment a `Row` lambda opens; they are now
read once into plain values. `2 * EDGE` does not exist, because `Int.times` has no `Dp` overload
while `Dp.times(Int)` does. And `::apply` on a local function named `apply` asks both the reader
and the compiler to disambiguate it from the stdlib extension every object carries.

**Run 4: green.**

## What the mutation sweep found

25 mutations. On the first full sweep, **24 were caught and one survived**:

    SURVIVED   the backwards-clock reboot detector is removed

The test written to isolate that detector was passing for the wrong reason. It started the clock
at `10_000` and restored at `3_000`, which made the saved instant lie in the FUTURE, so the
belt-and-braces guard at the end of `restore()` caught the case and the detector under test was
never exercised. Deleting the detector entirely changed nothing and the suite stayed green.

`startedAt` is now `1_000`, safely behind `now`, so the future guard cannot fire. Confirmed by
deleting the detector again and watching the suite go red.

**This is the finding that justifies the whole sweep.** A passing test that proves nothing is
invisible, and this one had a docstring claiming precisely the thing it was not checking.

## What the structural checks got wrong about themselves

`verify.py` came up 9 of 11 on its first run and **three of its eleven checks were faulty**:

- one counted phases with a regex that matched none of them and printed `3 rules for 0 phases =
  0 cases` while reporting PASS. A check that finds nothing and a check that runs nothing look
  identical from outside;
- one counted persisted fields across the whole file, so the orientation lock's own setter made
  the count 6 of 5 and a genuinely missing timing field could have hidden behind it. It now reads
  only the body of `save()`;
- one asserted `.apply()` appeared nowhere in `Store.kt`, which is wrong: the orientation lock is
  a preference and `apply` is correct for it. It now checks the body of `save()` and prints how
  many `apply` calls exist elsewhere.

All three would have read as passes.

---

# v3 — 27.8.2026, after v2 was on the phone

Six instructions from Baba, all of them corrections to things that were decided on a build server
and were wrong on glass. Every one is recorded here with what it cost.

## The buttons go to the bottom in landscape, and the landscape branch goes with them

**Reversal of a spec instruction, on Baba's word.** The addition spec was explicit: "LANDSCAPE:
down the RIGHT-HAND SIDE, stacked vertically, vertically centred. Landscape is not portrait
rotated." That was built as written. On the phone it was wrong — the screenshot shows the column
sitting directly under the orientation lock and inside the gesture-nav strip, three controls and
a system bar all crowded into the same right-hand edge.

**Chosen: one strip along the bottom, both orientations.** There is now no landscape branch at
all, only a strip whose height (72dp against 108dp) and button size (56dp against 72dp) differ.

The real gain is not the position, it is the deletion. **Two layouts were two places for the same
thing to be different**, and the v2 landscape column had its own collision arithmetic against the
lock button that portrait did not need and nobody would have remembered to update. `verify.py`
now counts each transport glyph and fails if any appears more than once, so a second layout
cannot come back by accident.

## The circles go, the hot zone stays

The ring was an outline at 18% marking where to press. On glass it read as three more shapes on a
screen whose whole design is what is absent, which is what Baba said.

**What was removed is the drawing, not the target.** `IconButton` still occupies the full size
and still takes a press anywhere inside it, so nothing about aim changed — which matters, because
the argument for removing tap-anywhere at v1 rested on the targets being large. A mutation puts a
border back and `verify.py` goes red.

## The glyphs go darker: 55% to 40%

v1 chose 55% by rendering the glyph beside the digits in a mock and looking at it. The mock was
right about the band and wrong about the phone: on a real OLED panel at real brightness, 55% was
still reading as a second bright thing beside the numbers.

**Chosen: 40% enabled, 16% disabled.** Not lower, and this is the part worth arguing with later:
with the circles gone the glyph is the only thing marking the control, so there is no ring to
carry it if the glyph disappears. If 40% is still too bright, the next step down should be taken
one notch at a time and looked at, not halved.

## Whole seconds, and the tenth is gone

**Chosen: `MM:SS` below an hour, `H:MM:SS` above it. Truncated.**

v1 argued for tenths over hundredths on the grounds that tenths move at a speed the eye can
follow. On the phone, Baba's judgement was that the last digit was still the only thing on the
screen moving at a speed the eye cannot rest on, and that is the right call for a display whose
whole purpose is to be readable across a room without effort.

Two side effects, both good and neither the reason: the string drops from seven glyphs to five,
so every digit is about **a third larger for free**, and the redraw goes from ten a second to one.

**What it costs, written into Face.kt so nobody restores it by accident:** the app can no longer
time anything where a fraction of a second matters. It is a clock for minutes, not a photo
finish. If that is ever needed it is a new decision, not a bug report.

## The gear, and where it went

**Chosen: top-left, balancing the orientation lock top-right.** design-language.md 10: a row has
two ends and a middle, and a screen is read as weight before it is read as anything else. Piling
a second control beside the lock would have made the top-right corner a cluster and left the
top-left empty.

Both corner controls say what the next press does, per rule 5: the gear becomes a close when the
panel is open; the lock glyph becomes a rotate glyph when the orientation is locked.

## The swatch grid, and the two things it must not do

**Chosen: six by four, twenty-four swatches, press one and it is applied. No wheel, no hex field,
no sliders.** A wheel offers a million colours in order to find the six anybody wants.

**Rejected: a full-screen settings page.** design-language.md 11 — a thing being adjusted while it
runs must stay visible, because covering it means adjusting blind, and colour and weight are
judged against the digits and nothing else. The panel sits over the black BELOW the digits, so
opening it moves nothing and every press applies live. `verify.py` fails if the panel is ever
aligned anywhere but the bottom.

**Bounded at one end, deliberately.** design-language.md 13 says a control must not offer settings
that defeat it, and the thing this could defeat is legibility. Every swatch clears a contrast
ratio of 4.5 against black and **Test 1 asserts it** rather than trusting the eye that picked
them. The grid is also asserted rectangular and free of duplicates, because a ragged last row
reads as a mistake and a repeated swatch is a cell that does nothing while looking like it should.

A stored colour that is not in the grid — written by a later version, or left by a swatch removed
in a future edit — falls back to white rather than to something invisible.

## Bold or normal, and nothing between

**Rejected: a weight slider.** The question is only whether the strokes are heavy enough to read
across a room, and the answer is one of two. A dial would be a control with no purpose.

The two choices are shown as cells reading `88:88` in the weight they represent, rather than as
the word "Bold" set in bold. You are choosing how the digits look, so the sample is the digits.

**The weight is part of the size measurement, not applied afterwards.** Bold digits are wider,
and sizing a normal face then drawing a bold one is how a layout ends up over the edge.

---

## What Test 1 caught in the new code

**A luminance threshold I guessed.** The tick marking the chosen swatch has to be drawn in black
or white depending on the swatch under it, and the first version used a threshold of 0.35 picked
by eye. On the orange, white gives a contrast of 2.8 and black gives 7.5 — the guess was wrong
and the test said so immediately. It now computes both contrasts and takes the better one. The
real crossover is at a luminance of about 0.179, which is not a number anybody arrives at by
looking at swatches.

## What the mutation sweep caught in itself

**An anchor that stopped being unique.** The `tap-anywhere comes back` mutation anchored on
`.background(BACKGROUND)`, and the settings panel added a second one. The mutation began matching
twice and reported SKIP, **which reads almost exactly like a caught mutation in a list of thirty**.
It now anchors on the whole modifier chain. This is the second time a non-unique or
version-dependent anchor has produced a silent SKIP in this repository.

**Three interruptions, and a guard.** The sweep edits source in place and has now been killed
mid-mutation three times — once by a session ending, twice by a time limit. The first two left a
deliberately broken line in the working tree, and the second cost a confusing red baseline that
looked like a real regression. It now stashes every file it can touch before starting and
restores any stash left by a run that did not finish. **The guard fired on its first outing** and
the recovery cost nothing. A tool that edits source in place has to assume it will be
interrupted, because it will be.

---

# v4 — 27.8.2026, two instructions after v3

## All six numbers from the start

**Chosen: `HH:MM:SS`, always, from zero. This reverses v1 and v3.**

Both earlier versions rejected exactly this, on the grounds that showing an hour field from zero
makes the digits permanently smaller to defend against an hour that almost never arrives. That
argument was correct and it is still the price: eight glyphs instead of five means every digit is
roughly a third smaller than v3's.

**What it buys is worth more than what it costs.** The width now never changes, so there is no
step at the hour, no moment where the digits resize under you, and no branch in the formatter at
all — one format string, one length, one measured size for the life of the app. v3 had exactly
one discontinuity left in it and this removes it.

The width test got stronger rather than weaker as a result: it used to assert one length below
the hour and a different one above, and now asserts a single length across the whole range.

Past 100 hours the hour field takes a third glyph and the digits step once. Documented rather
than prevented; a stopwatch running for four days has other problems.

## Play and pause both toggle

**Chosen: either glyph flips the clock between running and paused. The symbols never morph.**

Pressing play while it runs pauses it. Pressing pause while it is paused starts it again. What
moves is the highlight, which says which of the two the state suggests next. Baba's words: "So
symbol is not changing. So there is a play. I click play one more time. And then what is
highlighted is pause."

**Rejected: one button that swaps its own glyph between play and pause,** which is what most
media players do. A symbol that changes under your thumb has to be read before every press, and
the whole point of a fixed layout is that the hand learns where things are and stops looking.

**Rejected: making pause start the clock from zeros,** which strict toggle logic would require.
Beginning to time something by pressing PAUSE is a surprise, not a convenience. Pause from a
stopwatch showing zeros is the one DEAD cell in the table, along with stop from zeros.

## The change that was not asked for, and had to happen anyway

**v4 made "dim" ambiguous, and a third tone was the honest fix.**

Before v4, dim meant unavailable: a dim button did nothing when pressed. After v4, play is dim
while the clock runs and pressing it still pauses the clock. So the same appearance would have
meant two different things on the same screen — "does nothing" and "not the obvious next move" —
which is precisely the confusion a person discovers by pressing a button and being surprised.

    HIGHLIGHT  40%   what the next press would produce
    SECONDARY  24%   live, pressable, not the suggestion
    DEAD       12%   inert, and looks it

Three tones is one more thing on a screen whose design is what is absent, and it was not asked
for. It is here because the alternative was one appearance carrying two meanings, and that is a
worse kind of clutter than a third grey. **Worth arguing with on the phone:** if 24% and 12% are
not clearly different at arm's length, the answer is to widen the gap, not to collapse them.

## What the sweep found in the checks, again

Three UI mutations survived the first v4 sweep, and all three were failures of `verify.py`
rather than of the app:

- the hidden-button check searched for the word `canPause`, which v4 deleted. **It was checking
  for a shape that could no longer exist**, so it passed on everything. It now looks for any
  conditional wrapping a transport emission, whatever the condition is written in;
- the three-tone check asked whether `GLYPH_SECOND` existed. A val that nothing reads exists
  perfectly well, so collapsing the colour expression back to two tones sailed through. It now
  reads the actual colour expression and the actual `enabled` expression;
- the same weakness let a mutation disable every secondary control without anything noticing.

A fourth mutation survived and that one was the sweep's own fault: it misspelled
`disabledContentColor` to break the tint, which **would not compile**, so catching it would have
proved nothing about anything. A mutation has to be a plausible bug. It now sets the disabled
tint to the highlight colour, which is a regression somebody could actually ship.

`verify.py` also had a latent ordering fault: the new check read `ui` before the line that
defined it, and only ran at all because it happened to sit after that line in the old file. All
three sources are now read once at the top, so check order cannot matter.

---

# v5 — 27.8.2026, two more from the phone

## The corner button sets the orientation instead of locking it

**Chosen: two states, PORTRAIT and LANDSCAPE, and no sensor-following state at all.**

Until v5 the button locked whatever the phone had already decided. Baba's instruction was that
it should CHOOSE: one press portrait, the next landscape. That is a different control wearing
the same corner.

**Say plainly what was lost: the app no longer follows the phone, ever.** There is no third
state, so turning the handset does nothing until the button is pressed. That is a real
capability removed, and it is removed on purpose — a stopwatch propped on a table wants to be
told which way up it is, not to guess from an accelerometer that a system-wide rotation lock may
be overriding anyway. If a "follow the phone" state is ever wanted back it is a third value in
the `Orientation` enum and one more branch, not a rewrite.

**SENSOR_PORTRAIT and SENSOR_LANDSCAPE, not the plain constants.** The plain ones pin a single
way up, so a phone laid flat and turned to face somebody across the table stays upside down. The
sensor variants hold the CLASS of orientation and still allow the 180-degree flip inside it,
which is what a person means by "landscape".

**The glyph shows where the next press GOES, not where you are.** In portrait it shows the
landscape phone. design-language.md 5. You can already see which way up you are by looking at
the screen; what you cannot see is what the button will do.

**The old preference key is deliberately not migrated.** "Was locked" carries no information
about which orientation somebody would now choose, so reading it would be inventing an answer.
Everyone starts in portrait once and a single press settles it.

## Play is white while the clock is idle

**Chosen: a fourth tone at the top of the ladder, PRIMARY, white, on exactly one cell of nine.**

This is a hole in the oldest rule in the brief — that the digits are the only white thing on the
screen and nothing may compete with them. It is worth stating why the hole is acceptable rather
than pretending it is not a hole.

**It closes the instant a measurement starts.** While the clock is stopped or paused there is no
measurement to compete with; the digits are frozen or at zero, and the one thing a person
almost certainly wants is to start. The moment it runs, play drops to SECONDARY and the digits
have the screen to themselves again, which is the situation the original rule was written for.

**Rejected: making the whole HIGHLIGHT tone white,** which would have been the tidier change and
would have put a white pause glyph on screen while the clock runs. That is precisely the moment
the rule exists to protect, and it would have broken it at the only time it matters.

**The edges are asserted, not assumed.** Test 1 walks all nine cells and checks that exactly one
is PRIMARY at a time, that it is always play, and that nothing is PRIMARY while running. Three
mutations attack it: white surviving into RUNNING, white leaking onto stop, and play losing its
white when idle.

**Four rungs is more than a screen like this wants.** The defence is that each means exactly one
thing and they only ever run one direction. If it grows a fifth, that is the point to stop and
redesign rather than to add.

## What the sweep found, again, in a check rather than in the app

One mutation survived: reversing the orientation glyph so the button shows where you ARE instead
of where the next press GOES. Nothing was watching it, because nothing had been written to.
**That fault is invisible in a screenshot and obvious in the hand**, which is exactly the kind a
check has to hold, since screenshots are all this repository gets. `verify.py` now asserts the
mapping and that the spoken label agrees with the glyph.

A second check had to be loosened rather than tightened: the totality check asserted "three
tones", which was a fact about v4 rather than an invariant. What matters is that both tables
cover every control and every phase; how many rungs the prominence ladder has is a design choice
allowed to change. **A check that encodes today's design as a law fails the next time the design
is right to change**, and it fails in the most expensive way, by looking like a regression.

---

# v8 — 28.8.2026, the app listens for itself

## One word, and the app hears it

v6 and v7 leaned on Google's Voice Access, which needs "tap start". Baba's answer was that being
asked to say two things is being asked to do two things, and he is right: a stopwatch command
with a preamble is slower than reaching for the button it replaces.

So the app holds its own recogniser. `RECORD_AUDIO`, asked for from the switch in the settings
panel and never at launch, held only while the stopwatch is on screen AND the switch is on. No
service, no foreground notification, nothing listening when the app is not visible.

**The mapping, said out loud, because it is not obvious.** Baba's words are start, stop and
reset. The controls are start, PAUSE and reset:

    "start"  ->  PLAY     begin, or resume
    "stop"   ->  PAUSE    freeze and KEEP the figure
    "reset"  ->  STOP     back to zeros

So the word "stop" does not drive the control the code calls STOP. That is the reading that makes
three words cover three controls, and if it is the wrong way round the alias table is the only
place it changes.

## The bug the gate exists to stop, and it would have shipped

The listener acts on PARTIAL results, because a command that lands half a second late has missed
the thing being timed. **Partial results repeat.** One spoken "start" arrives as "st", then
"start", then "start" again as the recogniser firms up, and each of those contains the command.

Play is a TOGGLE. Without a gate, one spoken word would press it three or four times: start,
pause, start — and where it landed would depend on how many partials the recogniser happened to
emit, which is to say on nothing the person did. **It would have looked like a possessed
microphone**, and it would have been blamed on Android rather than on this.

Two rules, because one is not enough: once per utterance, and never twice inside 700ms. The
first is reopened only when the recogniser starts a fresh listen; the second covers the tail of
one word landing in the next utterance after a restart.

**The gate is pure and takes the clock as an argument**, for the same reason the stopwatch does.
Every case is walked in Test 1 without a microphone.

## What Test 1 caught in the gate, immediately

`Long.MIN_VALUE` as the "never fired" sentinel. `now - Long.MIN_VALUE` **overflows to a negative
number**, the minimum-gap check reads that as "too soon", and the gate never opens at all. A
voice command that never fires is the exact opposite of the bug the class was written to prevent,
and it would have shipped looking like a microphone that does not work. A boolean marks
never-fired now, and a mutation puts the sentinel back.

## The VU meter is TTT mini's, with a different way in

`Vu` is a port of `AudioLevelSmoother.kt` — the curve, the noise gate and the attack and release
rates are that file's, unchanged, because they are a calibration somebody arrived at by watching
a meter rather than by reasoning. The Apache notice travels with it.

**What differs is the input.** TTT mini owns the microphone through `AudioRecord` and reads
sample peaks. This app hands the microphone to `SpeechRecognizer`, and two readers cannot both
have it — opening `AudioRecord` beside a live recogniser fails or starves one of them. So the
level comes from `onRmsChanged`, in decibels, and `fromRms` converts before the original curve
runs untouched. If a device reports a different range the meter will look wrong and the
recognition will still work, which is the right way round for the two to fail.

## Two more checks answered by prose rather than by code

Both found in this session, in checks written this session:

- the gate's overflow check asked whether `Long.MIN_VALUE` appeared anywhere in `Voice.kt`. It
  appears in the COMMENT explaining why it is not used, so the check failed on a correct file;
- the previous version's tip check asked whether `"tap ` appeared anywhere, and it appeared in a
  comment, so deleting the verb from the code left the check green.

One failed open, one failed closed, from the same mistake. **Checks now strip comments before
looking at Voice.kt**, and the tip check reads the expression rather than the file. That is
eight `verify.py` faults recorded across five sessions, every one of which read as a pass or a
puzzling fail rather than as a broken check.

---

# v19 — 28.8.2026, the display setting

## MULTI or SINGLE, and why this one became a switch

**Chosen: a setting, with MULTI as the default.**

MULTI is HH:MM:SS from zero — the width never changes, nothing ever resizes under you, and there
is no branch in the formatter. SINGLE shows only the fields that have started: two glyphs until
the first minute, then MM:SS, then HH:MM:SS, so the digits are enormous at the start and step
down twice.

**THIS IS THE SAME ARGUMENT v1, v3 AND v4 ALREADY HAD, AND IT HAS TWO ANSWERS.** v1 and v3
rejected showing the hour from zero because it makes the digits permanently smaller in order to
defend against an hour that almost never arrives. v4 reversed them, because a width that never
moves is worth more than size. Both remain true, and which one wins depends on what is being
timed — an eight-second take across a room is not a forty-minute one on a desk.

That is the definition of a setting rather than a decision: a question whose answer legitimately
changes with the use, not one where somebody simply has not worked out the right answer yet. The
brief says to resist adding things, and the test that gets past it is whether the app was making
a choice on the person's behalf that it had no way to make correctly.

**What SINGLE costs, written down so nobody reads it as a bug:** the digits resize twice during a
measurement, at one minute and at one hour. That is the exact fault MULTI exists to avoid. It is
not a defect in SINGLE, it is what SINGLE is.

**Both cells are shown in the thing they describe**, like the weight cells beside them: one reads
88:88:88 at 20sp and the other reads 88 at 30sp. A cell reading "SINGLE" would tell you a word;
these tell you what the screen is about to look like, which is the actual question being asked.

**The check that matters here** is that the digits are measured on the string the setting actually
draws. Sizing on MULTI and drawing SINGLE would size for eight glyphs and draw two, and the
setting would appear to do nothing at all — a failure that looks like a dead control rather than
like a sizing bug.

---

# v45 — 21.9.2026

Four things asked for in one sentence, and the interesting part of each is what it reversed.

## The circles are removed, for the second time, and the reasoning for them was correct

Baba: "remove circles around numbers."

They were removed once before, at v3, because on glass a ring round every glyph read as three
more shapes on a screen whose entire design is what is absent. v39 brought them back
CONDITIONALLY — present exactly when a control could be pressed — and the argument was good
enough to write into `verify.py` as a check with its own paragraph: a mark that carries "will
this do anything" is information, not decoration, and the only mark on the screen carrying it.

**Both the argument and the outcome are worth keeping, because they disagree.** The ring really
was the only thing saying whether a press would land. It was also, at the distance this app is
read from, six thin circles under the digits, and the digits are what the app is. A mark that is
information to somebody holding the rule in their head is a shape to everybody else.

What the ring was saying is not lost. It went back onto the WEIGHT of the glyph, which is how
this app said everything for thirty-eight versions and how the microphone and the play mark never
stopped saying it: white is the one you want, grey is live, dark grey is live but not suggested,
nearly black does nothing. Four steps, no geometry.

**The S/T letter had to lose its ring too, and that is the whole test of whether a language is
one.** v40 gave the letter a ring so it would not be the single pressable thing without one.
Leaving that ring at v45 would have made it the single thing WITH one — the same fault, reversed.
An exception in either direction teaches you not to trust the rule.

The `verify.py` check that guarded the conditional ring now counts `CircleShape` across the whole
screen rather than inside `Glyph` alone, because the last return came back in two places and a
check watching one of them would have passed with a ring on the other.

## Full screen, and the rule it is allowed to break

Baba: "add a button for the full screen when stopwatch is displayed without any buttons, only
numbers."

This app hides the system bars already, so "full screen" here means the app's own controls. One
press and all eight leave; the transport strip and the top band both go to zero height and the
digits grow into the space.

**It is measured against the oldest rule in the brief — "no button is ever hidden, because a
control that disappears moves the layout" — and it does not break it.** That rule is about the
REASON: a control vanishing because it cannot act leaves you guessing where it went and shuffles
the ones beside it. Here everything goes at once, because somebody pressed the button that says
so, and one gesture returns all of it. The condition is written round the group and round the
transport row, never round a single glyph, and `verify.py` now collects every condition wrapped
round a `Transport` or a `Glyph` and refuses any that is not `!fullscreen` — so the exception is
one named thing rather than a hole.

**Reset is not reachable in full screen, and that is the trade, not an oversight.** The way back
has to be a gesture a pocket cannot produce, or the mode undoes itself on a bench. The long press
is already that gesture, which is exactly why reset lives on it — and the two cannot share it,
because then every attempt to get the buttons back would destroy a measurement. Leaving costs one
long press, after which stop is where it has always been.

**There is no matching exit glyph** and the arrow only ever points one way. At the moment the
second state would be needed, nothing is drawn. A control whose other state cannot be shown
should not pretend to have one.

## The presets are his, and there are none in the source

Baba: "inside the settings, remove timer presets. All timer presets are defined by the user, so
he can define multiple presets. Under the first preset, add plus so he can add multiples."

The `TimerLength` enum held six durations and its doc comment defended them: this app has no
keyboard, so presets rather than a field. **That argument was answering the wrong objection.** The
problem was never that the six were hard to reach; it was that they were somebody else's six. The
"save as preset" slot beside them, holding exactly one, is the shape of a design that knew this
and could not admit it.

**The plus is the last cell of the same list, not a control beside it.** Baba asked for it "under
the first preset". As the last cell it is under the first while there is one and after the last
forever after, which is where the next preset will appear — the only place a control that makes
one belongs. A button below the block would be a fixed position that stops being the right one
the moment the list outgrows a row.

**A hold removes; a tap uses.** No confirmation, because the plus that put it there is in the
same row and puts it back — a test in Test 1 says so in as many words. A confirmation would be a
dialogue in an app that has never had one, guarding an action that costs one press to undo.

**Twelve at most, and at the ceiling the list is unchanged rather than rotated.** Dropping the
oldest to make room would silently discard something he saved on purpose, and a preset vanishing
unasked is the one behaviour that would stop the list being trusted. The plus goes dead and says
"full" instead of disappearing — same rule as the transport.

**Stored as one comma-separated string**, parsed by a function that is total and cannot throw. A
list whose length is part of its storage layout needs migrating every time the length changes,
and the first such migration written wrong is the one that loses somebody's list. The first read
inherits v44's single `savedPreset`, so an existing phone does not open to an empty row and
conclude the update ate it.

## Six steps, each with its amount on its face

Baba: "left and right of the entry box for time, add 2 pluses and 2 minuses. Plus is pushing the
stopwatch 30 seconds forward or backwards. Second plus is pushing for 1 minute and add one more.
Third plus and minus is pushing for 10 minutes."

This replaces a single pair whose step GREW WITH THE NUMBER — fifteen seconds under two minutes,
thirty under ten, a minute above — and that rule had its own defence written into the source:
making somebody press eighty-eight times to reach twenty-two minutes is contempt disguised as
precision.

**True, and it bought the wrong thing.** The same button did a different amount depending on a
number you had to be looking at to predict, so the only way to know what a press would do was to
do it. Three fixed steps make the choice of step the choice of BUTTON, which is the same decision
made visible, and the amount is written on the face.

Smallest nearest the digits, growing outwards, so the reach matches the size of the jump. Both
sides are generated from one list and each label is computed from the number the press uses —
typing "10m" beside a step of 600 works until somebody changes one and not the other, and then
the button lies, which is the one thing a control here may never do.

**Every cell is a weight, never a fixed width.** Seven things across a panel as narrow as the
phone: a fixed width fits the phone it was measured on and pushes the ten-minute button off the
edge of a smaller one.

## What the mutation sweep found, again, and this time about itself

The pattern in this file is that the sweep finds the fault in the checker rather than in the app.
This time it found the fault in the sweep.

**`sabotage.py` had not run since SpeechRecognizer was removed.** It named `VoiceListener.kt` —
deleted several versions ago — in the list of files it copies before starting, so it threw
`FileNotFoundError` on its first step, every time. The path was declared TWICE in its header,
which is how a stale one survives a read-through: the eye finds the second and assumes the first
was the typo. Nothing runs it in CI, because it is slow, so nothing reported it; and `README.md`
went on quoting a mutation count it had not produced in months.

Repaired, and it earned its keep on the first run:

- **`verify.py` had a check with no assertion in it.** v37 rewrote the rule about tap-anywhere,
  replaced the `check()` call below it, and left `background_click` computed and unused. For
  eight versions nothing watched whether the black behind everything was pressable — the precise
  fault v1 existed to remove. The sweep printed `SURVIVED: tap-anywhere comes back on the
  background`. This is the fourth time a check in this repository has read as healthy while
  watching nothing, and the second time the sweep is the only thing that noticed.
- **A guard in the brand-new preset code could not fail.** `presetAdd` opened with
  `if (seconds in presets) return presets`; breaking it changed nothing, because a `distinct()`
  further down was already doing the job. Deleted. A guard that cannot fail is worse than no
  guard: it is a second place a reader believes the rule lives, so the next person to change the
  rule changes one of them.

**Seven mutations still point at anchors that have moved**, listed in `HANDOFF.md`. They report
SKIP rather than SURVIVED, and a SKIP in a long list reads almost like a catch — which is the
whole reason they are written down somewhere a person will read instead of left in the output.

---

# v46 — 21.9.2026, the gestures

Baba, in three messages: "pinch is exiting the full screen and pinch is entering the full screen,
so we have pinch in to exit and pinch out to enter"; "one tap is stopping the stopwatch"; "two
taps are resetting the stopwatch. When I mean stopwatch, I mean any mode stopwatch or timer."

## One tap was already what he asked for, and saying so was the right answer

`Control.PLAY` has been a toggle since v4: running → pause, anything else → play. A tap on the
digits has gone through it since v37. "One tap is stopping the stopwatch" describes the app as
built, so nothing was changed for it and nothing was invented to look busy. **The useful thing
was to say so**, because the alternative reading — one tap ONLY stops, and something else starts
— would have left full screen with no way to start a measurement at all, the mode having no
buttons in it.

## Two taps reset, and the first tap is not held back

This is the whole engineering content of the version.

Compose finds a double tap for you with `onDoubleClick`, and it is one parameter. The price is
invisible until you look for it: **every single tap is then delayed by the double-tap window**,
because until that window closes the tap might turn out to be the first of two. Three hundred
milliseconds is imperceptible in a menu. In a stopwatch it is three tenths of a second missing
from the front of every measurement the app will ever take, forever, and it would never be
noticed as a bug — the number would simply always be slightly wrong.

So the tap is never held. It acts the instant it lands, and the second tap, if one comes, resets
**on top of** whatever the first one did. That works because the composition is harmless in every
direction:

    running, tap tap        pauses, then resets       -> zeros
    stopped, tap tap        starts, then resets       -> zeros
    counting in, tap tap    cancels, then resets      -> zeros

All three end at zeros, which is what two taps mean, and each intermediate state is the correct
answer to the first tap in its own right, visible for less than a third of a second.

**A third rapid tap resets again** rather than starting a new measurement, because `lastTapAt` is
not cleared when a double fires. Of the two possible surprises — a panicky burst of taps ending
at zeros, or ending with the clock quietly running — that is the safe one.

## What v1 removed is not being put back, and the check had to be rewritten to say why

This is the one place in this repository where a rule was reversed on purpose, so it is worth
being exact about what was and was not reversed.

v1 deleted tap-anywhere. The objection was never that a large target is bad; it was that **the
second state was destructive on a single isolated tap**. Touch once to start, touch again an hour
later, and the measurement was gone, with the whole screen as the target.

v37 answered that by making the tap a toggle and putting reset behind a long press. v46 adds a
second route to reset — two taps — and the fault v1 removed still cannot occur: **a lone tap can
only pause or start.** Both are recoverable and both are visible. Reset needs two taps inside a
third of a second, which is something a hand does on purpose and a pocket cannot do.

**The check guarding this had to be rewritten, and the rewrite is the interesting part.** It read
"the string `state.stop()` must not appear in the onClick body", which was a PROXY for the rule.
The proxy stopped fitting the moment a guarded reset went into that body, while the rule it stood
for was exactly as true as before. A check that goes red because the code got better is a check
that will be deleted by the next tired person, so it now asserts the thing itself: the reset in
that body is reachable only behind `isDoubleTap`, and there is exactly one of it.

## The pinch, and why it may sit on the background when a tap may not

`verify.py` has refused a `.clickable` on the root modifier chain since v1. The pinch handler
goes there anyway, and the two are not in conflict, because the rule was never "nothing may
listen to the background" — it was "a stray touch must not destroy a measurement".

A pinch cannot be made by a pocket, a sleeve, or one finger. It requires two pointers moving a
quarter of the way apart or together. And the worst it can do is change how much of the screen
the numbers take: **it never reaches the clock at all.**

**On the root rather than on the digits**, because in full screen the digits are everything but
on the ordinary screen they are not, and a way out that only works if you land on the numbers is
a way out you have to aim for. A pinch is already a whole-screen gesture on every phone.

**A ratio, not a distance.** A quarter. Pixels would mean a different gesture on every phone, and
proportion is the entire idea of a pinch. The same quarter in both directions, written as
`1 / PINCH_RATIO` rather than as a second number — a gesture that is harder to undo than to do
would feel wrong and nobody would ever work out why.

Three things stop it acting when nobody asked, and each is asserted:

1. **Two pointers.** `calculateZoom` returns 1 for a single pointer, so reading it unconditionally
   would be harmless — but requiring two says what a pinch IS rather than relying on arithmetic
   elsewhere to come out right.
2. **The accumulator starts fresh for every gesture**, inside `awaitEachGesture`. Hoisted out, an
   afternoon of small spreads would eventually total a quarter and the screen would change with
   nobody having asked.
3. **One firing per gesture.** Without `fired`, carrying on spreading past the threshold re-fires
   on every frame — a control changing state sixty times a second under a finger still moving.

## The quiet win: the long press means one thing again

v45's full screen hung its way out on the long press, which is reset — so **reset had to be
surrendered for as long as full screen was on**, and the v45 delivery record wrote that down as a
real loss rather than hiding it.

The pinch owns that door now. The long press went back to meaning reset in both modes, and reset
is reachable in full screen again. A feature added at Baba's request paid off a debt recorded
against a previous one, which is what happens when the debt is written down instead of argued
away.

## What the sweep found this time, which was mostly the sweep finding me out

Ten mutations were written for the new gestures. **Four survived the first run.**

Three were rules of the brand-new code that nothing was watching: the pinch firing once per
gesture, the guard that keeps the pinch off the settings panel, and arming the double-tap window
at all. The last of those is the nastiest shape a fault can have — `lastTapAt = 0L` instead of
`lastTapAt = now` breaks nothing, throws nothing, and simply means two taps never reset anything,
forever.

The fourth is the one worth remembering. **A check that was `caught` at v45 quietly stopped
working at v46 because the file got longer.** It searched a 200-character window after
`.background(BACKGROUND)` for a `.clickable`; the pinch handler pushed the end of that modifier
chain past 200 characters, and the window stopped reaching it. A tap could have been added to the
background and the check would have said nothing. A character count was never the question — the
question is whether THAT CHAIN carries a clickable — so it now extracts the chain and reads it
whole, and cannot go stale by growing.

**That is twice a check here has been broken by the commit that made a file longer, and both
times only the mutation sweep noticed.**

And a third instance of a pattern this repository keeps producing: **asserting that a thing
EXISTS rather than that it is USED.** The first pinch check asked whether `var fired` was
declared. A flag nobody reads is declared perfectly well, so breaking the condition that reads it
left the check green. The earlier two were a colour constant asserted to exist rather than to be
used, and a check searching for a function that had been deleted.
