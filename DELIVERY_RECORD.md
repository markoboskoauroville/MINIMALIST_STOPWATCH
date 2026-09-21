# DELIVERY RECORD — Minimalist Stopwatch v46 — 21.9.2026

    ARTEFACT   46-stopwatch-v46.apk, built by GitHub Actions from the commit tagged v46
    VERSION    new: 46   previous: 45, still downloadable at the releases page
    SIGNED BY  the permanent repository key, SHA-256 D9:3E:6B:00:...:D9:62
    SIZE       to be read back from the published release, not estimated here

## What v46 is

Three messages from Baba, all gestures:

    pinch out enters full screen, pinch in leaves it
    one tap stops a running clock  (already true since v4 — said so rather than rebuilt)
    two taps reset, in EITHER mode, stopwatch or timer

And the consequence nobody asked for: the pinch owns the way out of full screen, so the long
press went back to meaning reset in both modes, and **reset is reachable inside full screen
again** — a loss this document recorded against v45.

## The gates

    G1  PROVENANCE   pass   appVersion 45 -> 46, every action pinned by SHA, Gradle pinned by
                            sha256. The runner asserts the three numbers agree

    G2  SECRETS      pass   nothing in this change touches a key, a URL or a permission

    G3  ANALYSIS     pass   verify.py: 93 of 93, up from 89
                            Lint fatal on release: clean, assembleRelease green
                            Test 1: 133 cases, up from 126, 0 failures, 0 errors
                            Mutation sweep: 83 mutations, up from 70. RUN — and it found four
                              things, three of them in code written the same hour

    G4  DEAD CODE    pass   no dead code added; no function left behind by the change

    G5  DEAD LOOPS   pass   ONE NEW LOOP, and it is the reason to read this line rather than
                            skip it. `awaitEachGesture`'s do/while runs until the last pointer
                            lifts, which is a bound set by a finger rather than by a number.
                            That is the normal shape of every pointer loop in Compose and it is
                            the same shape the workflow's G5 counts and permits — it is not
                            `while (true)`, and it cannot spin, because `awaitPointerEvent()`
                            suspends until the hardware has something to say

    G6  STRESS       NOT RUN, no device

    G7  BUDGETS      v45 -> v46: source grew by the gesture code and its comments. APK figure to
                            be read from the release. Cold start, frame time, memory, battery:
                            NEVER MEASURED, no device

    G8  UPGRADE      NOT RUN by hand. v45 and v46 share the signing key, so it is testable

    G9  RECORD       this document

## What the sweep found at v46

Ten mutations written for the new gestures; **four survived the first run.** Three were rules of
brand-new code that nothing was watching: the pinch firing once per gesture, the guard keeping it
off the settings panel, and arming the double-tap window at all. That last one is the worst shape
a fault can take — it breaks nothing, throws nothing, and simply means two taps never reset
anything.

The fourth is the one to remember. **A check that passed at v45 quietly stopped working at v46
because the file got longer.** It searched a 200-character window after `.background(BACKGROUND)`
for a `.clickable`; the pinch handler pushed the end of that modifier chain past the window. A
tap could have been added to the background and nothing would have said so. It now extracts the
root modifier chain and reads it whole. **Twice now a check here has been broken by the commit
that made a file longer, and both times only the sweep noticed.**

Seven mutations still point at anchors that moved in versions before this one. Unchanged, and
still the next job.

## NOT TESTED — v46

**Every gesture. None has been made by a hand.** This is a change made ENTIRELY of gestures, and
gestures are the one kind of thing a JVM cannot judge at all. The arithmetic behind them is
proven — thresholds, windows, clock guards — and the arithmetic was never the risk.

    THE PINCH                never made on glass. Whether a quarter is the right amount of
                             travel, whether it is found without being told, and — the real
                             question — WHETHER IT FIRES AT ALL through the digits' own click
                             handler. The pinch sits on the root and the digits carry a
                             `combinedClickable`; Compose dispatches to the child first. The
                             reasoning says the clickable cancels on slop and releases the
                             pointers, and the reasoning has not met a thumb
    TWO TAPS                 never made. Whether 300ms is comfortable for Baba specifically is
                             not a thing this machine can know, and he is the only user
    THE COMPOSED RESET       the pause-then-reset flicker is asserted to be invisible at under a
                             third of a second. NOBODY HAS LOOKED AT IT
    RESET IN FULL SCREEN     the long press works there again in principle; never tried
    THE PANEL GUARD          pinching over an open settings panel should do nothing. Untested

Everything in the v45 block below still stands except the artefact upload, which ran.

---

# The v45 record — 21.9.2026

The shape is fixed so that two releases can be compared. The **NOT TESTED** block is the most
valuable part of this document and it is longer than the gate list on purpose.

**This document has now been stale twice.** At v22 it claimed to be v7; it then sat at v22 while
the app reached v44, so for twenty-two versions the only honest account of what had and had not
been proven described an app that no longer existed. Nothing between v23 and v44 was recorded at
the time and it is not reconstructed here — those versions have no delivery record and saying so
is more use than inventing one. The v22 record is kept below, unedited, as the last one anybody
actually wrote.

---

    ARTEFACT   45-stopwatch-v45.apk, built by GitHub Actions run 65 from commit 20163b9
    VERSION    new: 45   previous: 44, still downloadable at the releases page
    SIGNED BY  the permanent repository key, SHA-256 D9:3E:6B:00:...:D9:62
    SIZE       1,045,039 bytes, signed, read back from the published release. The local unsigned
               build was 1,036,847; the difference is the signature block and nothing else

## What v45 is

Four changes asked for in one sentence by Baba on 21.9.2026, and a fifth nobody asked for:

    the rings are gone from every control, and the tone ladder is back carrying what they said
    a full-screen button: one press and every control leaves, long press on the digits brings
      them back
    the six built-in timer presets are DELETED. Every preset is now one he saved
    six duration buttons, three each side, 30s / 1m / 10m, each with its amount on its face
    sabotage.py repaired — it had been crashing on a deleted file for months, unnoticed

## The gates

    G1  PROVENANCE   pass   every action pinned by commit SHA, Gradle distribution pinned by
                            sha256, appVersion 44 -> 45. The runner asserts the three numbers
                            agree; the local build confirms it compiles and packages clean

    G2  SECRETS      pass   scanned by the workflow on the push. Nothing was added to this
                            change that touches a key, a URL or a permission

    G3  ANALYSIS     pass   verify.py: 89 of 89, each printing what it examined
                            Lint fatal on release: clean, assembleRelease green
                            Test 1: 126 cases, 0 failures, 0 errors
                            Mutation sweep: RUN, and see below. This is the first version in
                              months where that line is not a lie

    G4  DEAD CODE    pass   15 source files, 5,941 lines. One dead guard found by the sweep and
                            deleted; TimerLength, timerStep and timerNudge removed with the
                            feature they served

    G5  DEAD LOOPS   pass   counted by the workflow. Nothing in this change adds a loop or a
                            wait

    G6  STRESS       NOT RUN, no device

    G7  BUDGETS      v44 -> v45
                            source 5,941 lines across 15 files
                            APK 1,045,039 bytes signed. The v44 figure was not recorded, so
                              there is no comparison to make and inventing one would be worse
                              than this sentence
                            cold start, frame time, memory, battery: NEVER MEASURED, no device

    G8  UPGRADE      NOT RUN by hand. v44 and v45 share the signing key, so it is testable

    G9  RECORD       this document

## THE GAP IN G3 IS NARROWER THAN IT WAS, AND IT IS A DIFFERENT GAP

The v22 record called the mutation sweep "the largest piece of unpaid work in the repository".
It was worse than that entry knew. **The sweep had not run at all since SpeechRecognizer was
removed** — it named a file that no longer existed in the list it copies before starting, so it
threw on its first step, every time, and nothing runs it in CI to report that.

It runs again. All 70 mutations were examined, and every rule v45 introduces is proven to fail
on purpose — the rings, the full-screen group, the strip's reserved height, the way back, the
presets, the bound, the duplicate, the parser and the steps.

**What running it found, both in the checker rather than the app, which is this repository's
pattern:**

    a check in verify.py with no assertion in it. v37 rewrote the tap-anywhere rule, replaced the
      check() below it, and left background_click computed and unused. For eight versions nothing
      watched whether the black behind everything was pressable. Restored
    a guard in the new preset code that could not fail, because a distinct() further down was
      already doing the job. Deleted

**Seven mutations still point at anchors that have moved** and are listed by name in
`HANDOFF.md`. They report SKIP rather than SURVIVED, so seven rules this repository claims to
guard are currently unguarded — not broken, unwatched. That is the next job, and it is smaller
than the one the v22 record described.

## NOT TESTED — v45

**Every one of the four things Baba asked for. None has been touched by a thumb.**

    THE RINGS GONE              the four-step tone ladder has never been LOOKED AT on glass since
                                it came back. The whole argument for removing the rings is about
                                what a screen looks like at arm's length, and that argument has
                                been made twice from a desk. Whether a dark-grey glyph reads as
                                "live but not suggested" or simply as "off" at low brightness is
                                the question, and it is unanswered
    FULL SCREEN                 never entered on a phone. Whether the digits actually look
                                bigger, whether the long press is discoverable without being
                                told, and whether somebody can find their way back without
                                reading this repository, are all unknown. THE DISCOVERABILITY IS
                                THE RISK: the way out is a gesture, and a gesture nobody guesses
                                is a trap however well documented
    THE PRESET ROW              never pressed. The wrap at four presets, the hold-to-remove, and
                                whether "full" reads as a state rather than a broken button, are
                                untested on a real panel
    THE SIX STEP BUTTONS        never pressed, and this is the one with a measurable layout risk:
                                seven cells across a panel on the narrowest phone. They are laid
                                out by WEIGHT precisely so they cannot overflow, but "cannot
                                overflow" and "the 9sp label is legible at that width" are
                                different claims and only the first is proven
    THE v44 MIGRATION           a phone holding a v44 savedPreset has never been updated to v45.
                                The inheritance is written and tested as a pure function; the
                                actual preferences file on an actual phone has not been read
    THE ARTEFACT UPLOAD         RUN AND PROVEN on run 65: `stopwatch-v45-build65`, 909,098 bytes
                                zipped, alongside the release. This one line of the block is
                                answered; every line above it is not

Everything in the v22 NOT TESTED block below that has not been superseded still stands. No device
has been used for any of this.

---

# The v22 record, kept as written — 28.8.2026

**This document was stale for fifteen versions.** It claimed to be v7 while the app was v22, which
made the only honest account of what had and had not been proven describe an app that no longer
existed. Anything below dated between v8 and v21 was reconstructed from the commits rather than
recorded at the time, and is marked where that matters.

---

    ARTEFACT   22-stopwatch-v22.apk, built by GitHub Actions from the commit tagged v22
    VERSION    new: 22   previous: 21, still downloadable at the releases page
    SIGNED BY  the permanent repository key, SHA-256 D9:3E:6B:00:...:D9:62
    SIZE       844,191 bytes

## What the app became between v7 and v22

Roughly two thirds of these exist because something decided on a build server was wrong on a
phone. That ratio is the most useful fact in this file.

    v8-v13   voice commands, four times over. Voice Access labels, then SpeechRecognizer with
             offline preferred, then a restart-storm fix, then a microphone probe, then the
             recogniser DELETED entirely and replaced with template matching against recordings
             Baba makes himself
    v14-v17  three samples per command, a sampler tab with waveforms and per-command scores,
             an arm mode, then the arm mode removed again in favour of press-and-it-stops
    v18      the digits flash when a command registers
    v19      MULTI or SINGLE display
    v20      spectral subtraction, and the DTW band fix it uncovered
    v21      the lap counter, in lengths and in metres
    v22      a ten-second count-in and the Go word that ends it

## The gates

    G1  PROVENANCE   pass   clean tree, built on the runner, every action pinned by commit SHA,
                            Gradle distribution pinned by sha256. gradle.properties, versionCode,
                            versionName and the tag all read 22

    G2  SECRETS      pass   history scanned, 0 key shapes; artefact scanned as a binary, 0;
                            staged diff scanned before every push, 0

    G3  ANALYSIS     pass   verify.py: 51 of 51, each printing what it examined
                            Lint fatal on release, warningsAsErrors, allWarningsAsErrors: 0
                            Test 1: 92 cases, 0 failures
                            Mutation sweep: SEE THE GAP BELOW — not clean, not run

    G4  DEAD CODE    pass   10 source files, 3,902 lines. Still no -keep rules at all, which is
                            the interesting half of R8's report: nothing is held alive by a note
                            saying spare this

    G5  DEAD LOOPS   pass   43 loops examined, 0 written as while(true), 6 waits.
                            Every wait is bounded by construction and Test 1 asserts the two that
                            matter: the redraw delay is 1..1000ms and the capture ends or times
                            out. The recording thread in GoSound also carries a hard ceiling on
                            samples collected, because a bound a reader cannot see is not a bound

    G6  STRESS       NOT RUN, no device

    G7  BUDGETS      v21 -> v22
                            APK 844,191 -> 844,191 bytes, unchanged
                            source 3,902 lines across 10 files
                            four of those files import nothing at all, which is why the hard
                              parts are testable in eleven seconds
                            cold start, frame time, memory, battery: NEVER MEASURED, no device

    G8  UPGRADE      NOT RUN by hand. v21 and v22 share a signing key, so it is testable

    G9  RECORD       this document

## THE GAP IN G3, STATED PLAINLY

**The mutation sweep has not been extended since v17 and has not been run since v20.** It carries
48 mutations. Nothing in it attacks the lap counter, the count-in, the Go word, the display modes
or the noise reduction — five features and roughly a third of the current source.

That matters more here than it would in most projects, because on this repository the sweep has
been the thing that found the faults: a test passing for the wrong reason, four separate checks in
`verify.py` that read as PASS while checking nothing, and an anchor that had silently stopped
matching. **A green verify.py on this codebase means less than it looks like it means until the
sweep has been run against it.**

This is the largest piece of unpaid work in the repository and it should be the next one done.

## NOT TESTED

**Everything requiring a phone.** The app has been compiled, statically checked, and had its pure
logic attacked from every angle a JVM can reach. Since v18 it has not been looked at.

    TEST 2, the real thing      nothing since v18 has been touched by a thumb
    THE LAP COUNTER             never pressed, never spoken. The arithmetic is tested and the
                                readout has never been seen above the digits, in either display
                                mode, in either orientation
    THE COUNT-IN                never watched. Whether ten seconds is the right length, and
                                whether the countdown reads clearly at the size the digits take
                                for a one or two character string, is unknown
    THE GO WORD                 NEVER RECORDED AND NEVER PLAYED. The rate discovery, the four
                                byte header, the alarm stream and the AudioTrack path have no
                                evidence behind them at all. This is the least proven code in
                                the repository
    THE NOISE REDUCTION         proven only against synthetic signals, and twice the synthetic
                                signal was the thing that was wrong rather than the code. Whether
                                it helps in traffic or a club is the entire question and it is
                                unanswered
    THE MATCHER THRESHOLDS      accept 0.55 and margin 0.06 were chosen from the geometry of a
                                cosine distance, NOT MEASURED ON A VOICE. The v20 DTW fix may
                                have moved the real distances substantially. The tester prints
                                them precisely so this can be settled from evidence
    TEST 4 and G8               v21 and v22 share a key; installing one over the other, unrun
    G6, stress                  no soak, no monkey, no sabotage list
    REBOOT, PROCESS DEATH       exhaustively tested as pure functions with hand-made inputs.
                                Neither has met the real thing
    FONT SCALE, TABLETS         not tested

## Known and deliberate

    the app can no longer time anything below a second

    SINGLE resizes the digits twice during a measurement. That is what SINGLE is, not a defect

    the count-in only ever delays a start from zero; resuming a pause is immediate

    the template matcher only knows the voice that recorded it, in roughly the conditions it was
      recorded in, and has no idea what a word means

    nothing listens while the app is off screen. Always-on would need a foreground service and a
      permanent notification, which changes what this app is, and has not been agreed

    the signing keystore exists as two repository secrets and one copy Baba holds, exported at
      v11. GitHub cannot read them back

## Rollout

One user, one phone. Stage 0 is the whole rollout. "Halt" means installing v21.

**The quarter of an hour worth more than every gate above**, still unspent: record the nine
samples, swim or walk a few lengths with the lap counter on, set the count-in and record a Go
word, and read back the three distances the tester prints.
