# Minimalist Stopwatch

A stopwatch. Black screen, white digits, three transport buttons and an orientation lock.
Nothing else, ever.

By **Mantra Productions**, Zagreb. Built for reading across a room.

    play    starts from zero, or resumes from where pause left it. Never a restart
    pause   freezes the digits and keeps the elapsed time
    stop    back to zeros

A button that cannot act is dimmed and inert. No button is ever hidden, because a control that
disappears moves the layout, and a stopwatch whose buttons shuffle is worse than one with a dim
button.

**Portrait:** the buttons run along the bottom, evenly spaced, well clear of the digits.
**Landscape:** they stack down the right-hand edge, and the digits take the width they have.
Landscape is not portrait rotated.

## Install

The latest APK is on the [releases page](../../releases). Two builds are kept at a time.

## The part that is actually hard

A stopwatch is a clock, and clocks are where lazy code shows.

The elapsed figure is **never accumulated by adding ticks**. Two fields are kept, the instant the
current run segment began and the milliseconds banked before it, and the answer is arrived at by
subtraction every time it is asked for. The clock is `SystemClock.elapsedRealtime()`, which is
monotonic and does not move when a time server corrects the wall clock.

Both fields survive rotation, backgrounding, process death and reboot. A reboot mid-run returns
zeros rather than a number that is silently short, because a wrong answer delivered confidently
is worse than no answer.

## Checking it

    python3 scripts/verify.py            11 structural checks, one second
    ./gradlew :app:testReleaseUnitTest   30 cases, plain JVM, no emulator
    python3 scripts/sabotage.py          25 mutations, each rule broken on purpose

The last one is the important one. A test you have never seen fail is a rumour.

See [`HANDOFF.md`](HANDOFF.md) for the briefing, [`NEXT_DEFAULTS.md`](NEXT_DEFAULTS.md) for why
each decision was made, and [`DELIVERY_RECORD.md`](DELIVERY_RECORD.md) for what was proven about
the shipped artefact and, more usefully, what was not.
