### [Download the latest build](https://github.com/markoboskoauroville/MINIMALIST_STOPWATCH/releases/latest)

# Minimalist Stopwatch

A stopwatch and a timer. Black screen, enormous digits, three transport buttons, an orientation
lock, a full-screen button and a gear. Nothing else, ever — and with one press or one pinch, not
even those.

By **Mantra Productions**, Zagreb. Built for reading across a room.

    play    toggle. Starts, resumes, and pauses a running clock
    pause   toggle. Freezes a running clock, resumes a paused one
    stop    back to zeros

Play and pause are one toggle wearing two glyphs, so there is no wrong one to hit. The symbols
never change; the highlight moves between them to say what the next press would do. While the
clock is not running the play glyph is white, and it is the only thing on the screen besides the
digits that ever is.

The corner button sets which way up the app sits: one press portrait, the next landscape. It does
not follow the phone. Beside it is the full-screen button: press it and every control leaves the
screen, so there are only the numbers, as large as the glass allows.

It is the one thing this app hides on purpose, and it is the opposite of a button that vanishes
because it cannot act: everything goes at once, because you pressed the thing that says so, and
one gesture returns all of it. The setting survives the app being closed, so a display left on a
bench comes back the way it was left.

## The gestures

    tap          stops a running clock, starts a stopped one
    tap tap      back to zeros, in either mode
    long press   back to zeros
    pinch out    full screen — the numbers and nothing else
    pinch in     the controls come back

They work in both modes, on the stopwatch and on the timer alike, because "reset the thing on the
screen" is one idea and a gesture that meant two would be two gestures wearing one shape.

**A single tap can only ever pause or start**, and that is deliberate. The first version of this
app had tap-anywhere and it was removed, because its second state was destructive: touch once to
start, touch again whenever, measurement gone, with the whole screen as the target. Reset now
needs two taps inside a third of a second — a thing a hand does on purpose and a pocket does not
do at all — or a long press, which a sleeve cannot make either.

**The first tap is never held back.** Most apps that watch for a double tap delay every single
tap by the double-tap window, because until it closes the tap might turn out to be the first of
two. That is three tenths of a second off the front of every measurement, and this app exists to
measure things. So the tap acts immediately and the second one resets on top of whatever the
first did — pause then reset, or start then reset. Every path arrives at zeros, which is what two
taps mean.

**The pinch is a whole-screen gesture**, found anywhere on the glass rather than only on the
numbers, and it is the only thing the black background responds to. A quarter of the way, in
either direction, measured as a ratio so it is the same movement on every phone.

A button that cannot act is dimmed and inert. No button is ever hidden, because a control that
disappears moves the layout, and a stopwatch whose buttons shuffle is worse than one with a dim
button.

The three sit along the bottom in **both** orientations, evenly spaced, well clear of the digits.
There is no ring drawn around anything, and the touch target is the full circle's worth of space
all the same. A ring came back for a few versions, drawn only around controls that could be
pressed, on the argument that a conditional mark is information rather than decoration. The
argument was sound and the screen was still worse for it: at the distance this app is read from,
six thin circles under the digits are six circles. What they were saying is back on the weight of
the glyph — white is the one you want, grey is live, dark grey is live but not suggested, nearly
black does nothing.

Hours, minutes and seconds, all six numbers, from the moment it opens. Nothing finer: the last
digit of a tenths display is the only thing on a screen moving at a speed the eye cannot rest on.
The width never changes, ever.

## The timer

The letter at the top says which clock you are looking at: **S** counts up, **T** counts down.
Press it to change.

The timer's duration is set under the gear, and **there are no built-in presets**. There were six
once — half a minute, one, three, five, ten, twenty-five — and they were six guesses. Now every
preset on the row is one you put there.

Six buttons set the duration, three either side of it, and each says on its face what it does:

    −10m  −1m  −30s   [ 05:00 ]   +30s  +1m  +10m

The amount is fixed. The old single pair grew its own step with the number — fifteen seconds
under two minutes, thirty under ten, a minute above — which saved presses and cost the one thing
that matters more: the same button did a different thing depending on a number you had to be
looking at to predict.

Under them is your own list. Set a duration, press **+** to keep it, press a preset to use it,
**hold** a preset to remove it. Twelve at most, because the row wraps and an unbounded list would
eventually push the count-in off the bottom of the panel. The plus is the last cell of the same
list rather than a button beside it, so it is always where the next preset will appear.

## Colour

The gear opens a six by four swatch grid for the digit colour, and a choice of normal or bold.
Every press applies live, over the black below the digits, so you are looking at the thing you
are choosing.

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

    python3 scripts/verify.py            93 structural checks, one second
    ./gradlew :app:testReleaseUnitTest   133 cases, plain JVM, no emulator
    python3 scripts/sabotage.py          83 mutations, each rule broken on purpose

The last one is the important one. A test you have never seen fail is a rumour.

**And it had become one.** `sabotage.py` named a source file that was deleted several versions
ago, so it crashed on its own first step and had not run at all since — while this page went on
quoting a number it produced. It is in nobody's CI, which is exactly how that survives. It runs
again as of v45, and running it found two real things: a `verify.py` check whose assertion had
been dropped in an edit eight versions earlier and never reinstated, leaving the rule about what
may be pressed on the background unwatched; and a guard in the new preset code that could not
fail, because a `distinct()` further down was already doing its job. Seven of its mutations still
point at anchors that have moved — they are named in `HANDOFF.md`, and they are the next job.

It went on earning its keep at v46. Of the ten mutations written for the new gestures, **four
survived the first sweep** — three rules of the new code that nothing was watching, and one check
that had been *caught* the version before and quietly stopped working because the pinch made the
modifier chain longer than the 200-character window it was searching. A character count was never
the right question; it now reads the whole chain.

Every build is made by GitHub Actions on push, never on a desk. The workflow keeps the APK on the
run itself as well as publishing a release, so a push that forgets to bump the version still
leaves something installable behind.

See [`HANDOFF.md`](HANDOFF.md) for the briefing, [`NEXT_DEFAULTS.md`](NEXT_DEFAULTS.md) for why
each decision was made, and [`DELIVERY_RECORD.md`](DELIVERY_RECORD.md) for what was proven about
the shipped artefact and, more usefully, what was not.
