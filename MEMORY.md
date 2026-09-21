# MEMORY — Minimalist Stopwatch

Facts a future session needs and cannot derive from the source.

## The signing key exists in exactly one place

Repository secrets `STOPWATCH_KEYSTORE` and `STOPWATCH_KEYSTORE_PASSWORD`. Generated 27.8.2026
inside a session container that no longer exists, so there is **no second copy anywhere**.

    SHA-256  D9:3E:6B:00:8E:99:59:B6:F7:9B:00:CB:F8:2A:A8:9E:
             18:20:B6:7A:9C:3D:AE:B3:DE:4E:E7:BE:93:72:D9:62

If those secrets are lost, the app can never again be installed over an existing copy. Recovery
means uninstalling from every device and reinstalling under a new key. Worth exporting the
keystore to Baba's own vault the next time he is in the repository settings.

## The version is one number in one place

`appVersion` in `gradle.properties`. `versionCode`, `versionName`, the APK filename and the
release tag are all derived from it. To release: bump that number, push, done.

## Icons come from the set, never from a hand

`Icons.Default.PlayArrow`, `Pause`, `Stop`, `ScreenLockRotation`, `ScreenRotation`, `Settings`,
`Close` and `Check`, all from
`androidx.compose.material:material-icons-extended`, the same artefact TTT mini uses. R8 strips
the rest; the release APK is 795 KB with the whole library on the classpath.

## Two things about this codebase that will surprise a fresh reader

**`Stopwatch.kt` imports nothing from `android.*` and must not start.** `verify.py` fails if it
does. The whole timing model is testable on a plain JVM in eleven seconds, which is what makes
the 25-mutation sweep affordable.

**Nothing on the background is tappable, and that is enforced.** Tap-anywhere was removed
deliberately on 27.8.2026 and `verify.py` goes red if a `clickable` returns to it.

**Play and pause are ONE TOGGLE wearing two glyphs.** Either flips between running and paused.
The symbols never morph. `Stopwatch.press()` says what each control does and `Stopwatch.tone()`
says how it looks, nine cases each, both in the pure file where Test 1 walks them.

**There are THREE tones, not two, and the third one is load-bearing.** HIGHLIGHT 40%, SECONDARY
24%, DEAD 12%. Before v4, dim meant dead. After v4, play is dim while running and pressing it
still pauses the clock, so dim alone would have carried two meanings. If anybody ever collapses
this back to two tones, `verify.py` goes red — it reads the actual colour expression, not whether
the constant exists.

**The face shows all six numbers from zero and the width never changes.** `HH:MM:SS`, one format
string, no branch, no step at the hour. v1 and v3 both rejected this and v4 reversed them on
Baba's instruction.

**There is no landscape layout.** One strip along the bottom in both orientations, with two sets
of sizes. v2 had a right-edge column and it was wrong on the phone. `verify.py` counts each
transport glyph and fails if one appears twice, which is how a second layout would announce
itself.

**The circles around the glyphs were removed and the touch targets were not.** If a `border`
modifier appears anywhere in the screen, `verify.py` goes red.

## The local test harness

`scripts/sabotage.py` through Gradle takes about an hour. Compiling the two Kotlin files
directly with `kotlinc` against JUnit takes eleven seconds a run, which brings the sweep to about
four minutes:

    SABOTAGE_RUN=/path/to/runtest.sh python3 scripts/sabotage.py

Worth rebuilding that harness at the start of any session that touches the timing model. The
compile takes `Stopwatch.kt`, `Palette.kt` and the test file, in that order.

**The sweep edits source in place and it will be interrupted.** It has been killed mid-mutation
three times. It stashes to `.sabotage-stash` before it starts and restores on the next run. If a
baseline ever comes up red for no reason, look for that directory first. Use `SABOTAGE_SLICE`
to run it in pieces when the thing running it has a time limit.

## A documents-only push does not publish, and says so

Pushing a README fix without bumping `appVersion` used to fail the whole run on the last step,
after every gate had passed. The publish step now skips when the tag already exists and prints a
loud block saying nothing was published and what to do about it. **If you changed the app and see
that block, you forgot to bump the version.**

## Every check in verify.py has been wrong at least once

Six faults so far, across three sessions, and every one of them read as a PASS:

- a phase counter whose regex matched nothing, printing "0 cases" and passing;
- a field counter that counted the whole file, so a preference masked a missing timing field;
- an `apply()` check that forbade it everywhere, including where it is correct;
- a hidden-button check searching for `canPause` after `canPause` was deleted;
- a three-tone check asking whether a colour constant existed rather than whether anything read
  it, which let two separate mutations through;
- a check that read `ui` before the line defining it, and only worked by accident of ordering.

**The mutation sweep found five of the six.** Run it before believing verify.py.

## Two anchors have gone stale and reported SKIP

Both times the SKIP read almost exactly like a caught mutation in a list of thirty. Once because
the anchor contained the version number and the version was bumped; once because the settings
panel added a second `.background(BACKGROUND)` and the anchor stopped being unique. **When
reading a sweep result, count the SKIPs before believing the caught total.**

## sabotage.py is not in CI, and it silently stopped working once

The mutation sweep is the only thing on this repository that has reliably found faults in the
CHECKS rather than in the app, and **nothing runs it automatically** because it is slow. That is
a decision, not an oversight — but it has a cost that was paid in full once already.

Between the removal of SpeechRecognizer and 21.9.2026, `sabotage.py` named `VoiceListener.kt` in
the list of files it copies before it starts. The file was deleted with the recogniser, so the
script threw `FileNotFoundError` on its own first step, every single run. It had not swept
anything for months, `README.md` went on quoting a mutation count it was no longer producing, and
the two things it found within minutes of being repaired were a `verify.py` check that had lost
its assertion eight versions earlier and a guard in brand-new code that could not fail.

**Run it before believing a green `verify.py`.** Its own header is the first place to look if it
does anything other than print mutations: a crash there looks nothing like a failed mutation.

## A SKIP in the sweep is not a pass, and it reads like one

Seven mutations had anchors that no longer matched. The sweep printed `SKIP  anchor found 0
times` for each, which in a list of thirty is almost invisible next to `caught`. **Seven rules
this repository claims to guard were not being guarded at all**, and not one of them had stopped
being true — every one was an anchor that had stopped matching, which is the more dangerous
shape, because the rule looks watched.

Re-pointed on 21.9.2026. If the sweep ever prints SKIP again, fix it in the same session: an
anchor that does not match is a check that does not run.

**`anchor found N times` where N > 1 is the same problem.** `while (isActive)` matched three
loops, so the mutation refused to run rather than picking one. Anchor on something unique to the
loop you mean.

## Every file a mutation touches must be in MUTABLE

`sabotage.py` stashes and restores only the files in that list. A mutation aimed at a file
outside it edits the working tree and leaves the edit behind if the run is interrupted — the one
thing this script must never do. MaMeter.kt was added on 21.9.2026 when the meter mutation was
re-pointed into it.

## Rings have been removed twice and the argument for them is always good

v3 removed them, v39 brought a conditional one back with a sound argument, v45 removed them again
at Baba's word. State is carried by the WEIGHT of the glyph — white, grey, dark grey, nearly
black. If a future session is about to add a ring, the thing to notice is that the case FOR one
is correct both times and has twice been beside the point: at the distance this app is read from,
circles under the digits are circles.

## Never use Compose's `onDoubleClick` on the digits

It delays EVERY single tap by the double-tap window, because until the window closes the tap
might be the first of two. Three tenths of a second off the front of every measurement, on an app
whose whole job is measuring. v46 composes the reset on top of the first tap's effect instead —
pause then reset, or start then reset, both arriving at zeros — so the tap never waits.
`verify.py` goes red if `onDoubleClick` appears anywhere in the screen.

## A check that searches a fixed window of characters will rot when the file grows

Twice now. A check searched 200 characters after `.background(BACKGROUND)` for a `.clickable`; it
passed at v45, and at v46 the pinch handler made that modifier chain longer than the window, so
the check silently stopped watching anything. **Extract the thing you mean and read it whole** —
the root modifier chain, the function body, the enum — rather than guessing at a distance. Both
times only `sabotage.py` noticed.

## Assert that a thing is USED, never that it EXISTS

**This is the most productive fault to look for in this repository.** Six instances so far, and
every one read as a healthy PASS:

    a colour constant asserted to exist rather than to be read
    a check searching for a function that had already been deleted
    `var fired` asserted to be declared, while the condition reading it was broken
    "coerceIn(0f, 1f)" searched for anywhere in MaMeter.kt — it is there three times, and only
      the one inside maNorm keeps the meter inside its track
    "Alignment.TopCenter" searched for anywhere in the screen — the microphone, the power mark
      and the mode letter all use it, so the settings panel could be moved to the bottom freely
    "Vocabulary.display" searched for anywhere — three call sites, so hardcoding the name in the
      field somebody actually reads changed nothing

A declaration nobody reads compiles perfectly well, and a string that appears three times cannot
tell you which one matters. **Extract the specific line and read it** — the function's own
definition, that modifier chain, those call sites by name. Every one of these was found by
`sabotage.py` and by nothing else.
