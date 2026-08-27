# DELIVERY RECORD — Minimalist Stopwatch v3 — 27.8.2026

The shape is fixed so that two releases can be compared. The **NOT TESTED** block is the most
valuable part of this document.

---

    ARTEFACT   3-stopwatch-v3.apk, built by GitHub Actions from the commit tagged v3
    VERSION    new: 3   previous: 2, still downloadable at the releases page
    SIGNED BY  the permanent repository key, SHA-256 D9:3E:6B:00:...:D9:62

## What changed since v2, and why

All six changes came from Baba after v2 was on the phone. Every one was a correction to something
decided on a build server. The reasoning is in NEXT_DEFAULTS.md; the short list:

    the transport moved to the bottom in BOTH orientations, and the landscape branch was deleted
    the circles around the glyphs were removed; the touch targets were not
    the glyphs went from 55% to 40%, disabled from 22% to 16%
    the tenth was dropped: MM:SS, growing to H:MM:SS at the hour
    a gear top-left opens a 6x4 swatch grid that sets the digit colour, live
    normal or bold, chosen in the same panel

## The gates

    G1  PROVENANCE   pass   clean tree, built on the runner, every action pinned by commit SHA,
                            Gradle distribution pinned by sha256. The three numbers agree

    G2  SECRETS      pass   history scanned, 0 key shapes; artefact scanned as a binary, 0;
                            staged diff scanned before the push, 0

    G3  ANALYSIS     pass   verify.py: 14 of 14, each printing what it examined
                            Lint fatal on release, warningsAsErrors, allWarningsAsErrors: 0
                            Test 1: 36 cases, 0 failures
                            Mutation sweep: 31 mutations, 31 caught, 0 survived

    G4  DEAD CODE    pass   5 source files. Still no -keep rules at all, which is the
                            interesting half of R8's report: nothing is held alive by a note
                            saying spare this. The UNWIRED sweep is check 4 of verify.py:
                            3 availability rules across 3 phases, all 9 cases reachable

    G5  DEAD LOOPS   pass   loops counted and printed, 0 written as while(true). The only wait
                            is the tick, now bounded to 1..1000ms by Face.untilNextSecond,
                            which Test 1 asserts across ten thousand values and the sweep breaks

    G6  STRESS       NOT RUN, no device

    G7  BUDGETS      v2 -> v3
                            APK 794,911 bytes -> measured on this build, see the CI log
                            dependencies resolved: 474 -> unchanged, no new libraries
                            REDRAWS PER SECOND WHILE RUNNING: 10 -> 1, a tenfold reduction in
                              wakeups on the one screen that is meant to stay lit
                            cold start, frame time, memory, battery: not measured, no device

    G8  UPGRADE      NOT RUN by hand. v1, v2 and v3 share a signing key, so it is testable

    G9  RECORD       this document

## NOT TESTED

**Everything requiring a phone**, still. v3 was written in response to screenshots, which is one
step better than v2 was written from, and is not the same as having been used.

    TEST 2, the real thing      never run. Nothing has pressed the buttons, nothing has opened
                                the settings panel, and no swatch has ever been tapped
    THE SETTINGS PANEL          entirely unexercised on a device. Its height is computed from
                                the screen width and it is aligned to the bottom; on a short
                                landscape screen it may reach further up than intended and
                                nobody has looked. THIS IS THE MOST LIKELY PLACE FOR A LAYOUT
                                FAULT IN THIS RELEASE
    THE COLOUR AND THE WEIGHT   the contrast floor is arithmetic, not eyesight. Whether an amber
                                or a violet is actually pleasant to read across a room at that
                                size is unknown, and it is the kind of thing arithmetic gets
                                wrong
    40% GLYPHS                  chosen as one step down from a value that was measured in a
                                mock and found too bright on glass. Whether 40% is right, or
                                whether it should have been 45 or 32, has not been seen
    THE BOTTOM STRIP IN         the new landscape layout has never been rendered. The strip is
      LANDSCAPE                 72dp on a screen that may only be 360dp tall, and safe-drawing
                                padding is doing the work of keeping it clear of the gesture bar
    TEST 4 and G8, the upgrade  three artefacts exist and share a key. Not done
    G6, stress                  no soak, no monkey
    REBOOT, PROCESS DEATH       exhaustively tested as pure functions with hand-made inputs.
                                Neither has met the real thing
    FONT SCALE, TABLETS         not tested

## Known and deliberate

    the app can no longer time anything below a second. That is the point of the change, and it
      is written into Face.kt so it is not later read as a regression

    the size steps down once at one hour and once more past ten hours

    the boot-marker reboot detector has one false positive: moving the phone's clock by more
      than a minute during a running measurement reads as a reboot and returns zeros

    the system bars are left visible on black rather than hidden. One line to change

    THE SIGNING KEYSTORE EXISTS ONLY AS A REPOSITORY SECRET. There is no second copy anywhere.
      It was generated in a session container that does not survive. If it is lost the app can
      never be upgraded in place again

## Rollout

One user, one phone. Stage 0 is the whole rollout. "Halt" means installing v2, which is why two
releases are kept.

**The quarter of an hour that is worth more than every gate above**, and which has still not been
spent: install it, start it, put the phone in a pocket for ten minutes, and check it shows ten
more minutes rather than ten fewer. Then pause, wait, play, and check the gap was not counted.
Then turn the phone sideways and look at the bottom strip.
