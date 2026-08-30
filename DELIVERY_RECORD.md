# DELIVERY RECORD — Minimalist Stopwatch v22 — 28.8.2026

The shape is fixed so that two releases can be compared. The **NOT TESTED** block is the most
valuable part of this document and it is longer than the gate list on purpose.

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
