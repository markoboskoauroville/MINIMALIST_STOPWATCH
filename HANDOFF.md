# HANDOFF — Minimalist Stopwatch

**Current version: 1.** Repository public at `markoboskoauroville/MINIMALIST_STOPWATCH`.
Latest artefact: `1-stopwatch-v1.apk`, tag `v1`.

This is the briefing. The reasoning behind each decision, including what was tried and rejected,
is in [`NEXT_DEFAULTS.md`](NEXT_DEFAULTS.md). What was and was not proven about the shipped
artefact is in [`DELIVERY_RECORD.md`](DELIVERY_RECORD.md).

---

## What it is

A stopwatch. Black screen, white digits, three transport circles and an orientation lock.
Nothing else, ever.

    play    starts from zero, or resumes from where pause left it. Never a restart
    pause   freezes the digits and keeps the elapsed time
    stop    back to zeros

A button that cannot act right now is dimmed and inert. **No button is ever hidden**, because a
control that disappears moves the layout, and a stopwatch whose buttons shuffle is worse than one
with a dim button.

## The four files

    Stopwatch.kt     the whole timing model, and it imports nothing from android.*
                     That is enforced by verify.py, not merely intended
    Store.kt         one SharedPreferences file, written with commit rather than apply
    MainActivity.kt  the screen, the tick, the layouts, the colours
    StopwatchTest.kt 30 cases, on a plain JVM, no emulator

## The timing model, which is the whole app

    startedAt        the monotonic instant the CURRENT run segment began
    accumulated      milliseconds banked by every segment BEFORE this one

    running elapsed  = accumulated + (now - startedAt)
    paused  elapsed  = accumulated
    stopped elapsed  = 0

**Never add deltas.** `accumulated` changes at exactly one moment, when a run segment ends, and
its new value is computed by subtraction. A ticker adding 100ms ten times a second looks correct
for a minute and is visibly wrong after an hour, which is precisely when the number is relied on.

`now` is always `SystemClock.elapsedRealtime()`: monotonic, unaffected by a time server
correcting the wall clock, and counting while the device sleeps. The wall clock is read in
exactly one place, `Store.bootMarker()`, and that is not a measurement. verify.py counts it and
expects exactly one.

## Surviving rotation, backgrounding, process death and reboot

The first three are free, because `elapsedRealtime` keeps counting through all of them and a
saved `startedAt` is still measured against the same origin.

**A reboot is the case that has to be handled.** Two independent detectors, because each has a
hole the other covers:

    now < lastSeen     proof, not suspicion: a monotonic clock cannot go backwards. Misses a
                       reboot where the device has since been up longer than it was before
    boot marker moved  (wall clock - elapsedRealtime) is roughly the instant of boot. Catches
                       the case above. Costs one false positive: moving the phone's clock by
                       more than a minute mid-measurement reads as a reboot

What each phase is worth when a reboot is detected:

    RUNNING   ZEROS. The length of the segment in progress is unknowable, and coming back
              PAUSED at the banked figure would look like a preserved measurement while being
              silently short. A wrong answer delivered confidently is worse than no answer
    PAUSED    KEPT INTACT. A paused stopwatch holds its whole value in accumulated and never
              consults the clock, so a reboot costs it nothing
    STOPPED   zeros, which it already was

## The decisions you are most likely to want to undo

Each of these is argued in NEXT_DEFAULTS.md. Read that before changing one.

    tap-anywhere is GONE          the buttons are the control. verify.py goes red if a
                                  clickable ever returns to the background
    tenths, not hundredths        hundredths are a blur at speed and the last digit only
                                  flickers
    MM:SS.d, growing to H:MM:SS.d the size steps down once at one hour and never again
      at one hour
    the system bars are LEFT ON   black background, so they sit on black. Hiding them is one
                                  line and was deliberately not taken
    screen stays awake while      only STOPPED lets the phone sleep. A paused stopwatch is
      RUNNING **and** PAUSED      mid-measurement and about to be read

## The colours

    digits           white, and the only white thing on the screen
    glyph            55%  #8C8C8C   chosen by rendering it beside the digits at 40, 45, 50,
                                    55, 60 and 70 and looking. Below 50 it sinks into the
                                    black, above 60 it reads as a second white thing
    glyph disabled   22%  #383838
    ring             18%  #2E2E2E   an outline, not a fill
    ring disabled    10%  #1A1A1A

The glyphs are plain filled Material icons, `Icons.Default.PlayArrow`, `Pause`, `Stop`,
`ScreenLockRotation` and `ScreenRotation`, from the same `material-icons-extended` artefact TTT
mini uses. **Nothing is hand drawn.** If a future change tempts you to draw a triangle, stop and
use the icon.

## How to check it

    python3 scripts/verify.py                       11 structural checks, one second
    ./gradlew :app:testReleaseUnitTest              Test 1, 30 cases
    python3 scripts/sabotage.py                     25 mutations, each one broken on purpose

The sabotage sweep is the important one and it is slow through Gradle. With a local kotlinc
harness it is minutes rather than an hour:

    SABOTAGE_RUN=/path/to/runtest.sh python3 scripts/sabotage.py

## The version

**One whole number, written in one place: `appVersion` in `gradle.properties`.** Everything else
is derived from it — `versionCode`, `versionName`, the file name `N-stopwatch-vN.apk` and the tag
`vN`. The workflow reads the same line, so the two ends of the file name cannot disagree. To
release, bump that one number and push.

Only the two newest releases stay. There is no way back to a deleted artefact; the tag and the
source survive, the APK does not.

## The signing key

A permanent 4096-bit RSA key, generated 27.8.2026, held as repository secrets
`STOPWATCH_KEYSTORE` and `STOPWATCH_KEYSTORE_PASSWORD`. Every build is signed with it, so a new
version installs straight over the old one and Android never asks anybody to uninstall anything.

    SHA-256  D9:3E:6B:00:8E:99:59:B6:F7:9B:00:CB:F8:2A:A8:9E:
             18:20:B6:7A:9C:3D:AE:B3:DE:4E:E7:BE:93:72:D9:62

**The keystore does not exist anywhere except in those secrets.** It was generated inside a
session container that does not survive. If it is ever lost, the app can never be upgraded in
place again and would have to be uninstalled and reinstalled under a new key. That is a real
risk and it is written here rather than discovered later.

## What has never been run on a phone

Everything in this repository was proven on a build server. Test 2, Test 4, G6 and G8 are all
unrun, and the full list with reasons is in DELIVERY_RECORD.md. The shortest version: **the
digits, the greys and the two layouts have never been seen on a real screen.**
