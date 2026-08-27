#!/usr/bin/env python3
"""
sabotage.py — break one rule at a time and confirm the thing that watches it goes red.

    A TEST YOU HAVE NEVER SEEN FAIL IS A RUMOUR.

four-tests.md meta-rule 1 and delivery-gate.md 14 say the same thing in different words: after a
check passes, make it fail on purpose. This walks every rule the app claims to follow, breaks
exactly one, and reports whether anything noticed. A mutation that SURVIVES is a rule nobody is
actually checking, and it is reported as a failure of this script.

Two groups, because two different things do the watching:

    LOGIC   mutations to Stopwatch.kt, watched by the thirty cases in StopwatchTest.kt
    SHAPE   mutations to the screen, the store and the build, watched by scripts/verify.py

Run it with a fast local harness if you have one:

    SABOTAGE_RUN=/path/to/runtest.sh python3 scripts/sabotage.py

otherwise it uses Gradle, which is correct and slow:

    python3 scripts/sabotage.py
"""
import os
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LOGIC = ROOT / "app/src/main/java/com/mantra/stopwatch/Stopwatch.kt"
UI = ROOT / "app/src/main/java/com/mantra/stopwatch/MainActivity.kt"
STORE = ROOT / "app/src/main/java/com/mantra/stopwatch/Store.kt"
PALETTE = ROOT / "app/src/main/java/com/mantra/stopwatch/Palette.kt"
PROPS = ROOT / "gradle.properties"
MUTABLE = [LOGIC, UI, STORE, PALETTE, PROPS]

TEST_CMD = os.environ.get("SABOTAGE_RUN", "./gradlew :app:testReleaseUnitTest -q --no-daemon")
CHECK_CMD = "python3 scripts/verify.py"

LOGIC_MUTATIONS = [
    (LOGIC, "pause banks a second time when already paused",
     "        Phase.RUNNING -> Stopwatch(Phase.PAUSED, 0L, elapsed(now))\n        else -> this",
     "        Phase.RUNNING -> Stopwatch(Phase.PAUSED, 0L, elapsed(now))\n        else -> Stopwatch(Phase.PAUSED, 0L, accumulated + accumulated)"),
    (LOGIC, "play from paused throws the banked time away (restart, not resume)",
     "Phase.PAUSED -> Stopwatch(Phase.RUNNING, now, accumulated)",
     "Phase.PAUSED -> Stopwatch(Phase.RUNNING, now, 0L)"),
    (LOGIC, "play while running moves startedAt (a restart)",
     "        Phase.RUNNING -> this\n        Phase.STOPPED ->",
     "        Phase.RUNNING -> Stopwatch(Phase.RUNNING, now, accumulated)\n        Phase.STOPPED ->"),
    (LOGIC, "the negative clamp is removed",
     "        return if (raw < 0L) 0L else raw",
     "        return raw"),
    (LOGIC, "stop leaves accumulated behind (the half-reset)",
     "    fun stop(): Stopwatch = Stopwatch()",
     "    fun stop(): Stopwatch = Stopwatch(Phase.STOPPED, 0L, accumulated)"),
    (LOGIC, "a paused clock keeps counting",
     "            Phase.PAUSED -> accumulated\n",
     "            Phase.PAUSED -> accumulated + (now - startedAt)\n"),
    (LOGIC, "a reboot mid-run comes back paused at the banked figure (silently short)",
     "                return if (phase == Phase.PAUSED) Stopwatch(Phase.PAUSED, 0L, accumulated)\n                else Stopwatch()",
     "                return Stopwatch(Phase.PAUSED, 0L, accumulated)"),
    (LOGIC, "a reboot throws away a paused measurement that owed the clock nothing",
     "                return if (phase == Phase.PAUSED) Stopwatch(Phase.PAUSED, 0L, accumulated)\n                else Stopwatch()",
     "                return Stopwatch()"),
    (LOGIC, "the boot tolerance is zero, so a clock correction reads as a reboot",
     "const val BOOT_TOLERANCE_MS: Long = 60_000L",
     "const val BOOT_TOLERANCE_MS: Long = 0L"),
    (LOGIC, "the backwards-clock reboot detector is removed",
     "            val rebooted = now < lastSeen ||\n",
     "            val rebooted = false ||\n"),
    (LOGIC, "the boot-marker reboot detector is removed",
     "                (if (markerMoved < 0) -markerMoved else markerMoved) > BOOT_TOLERANCE_MS",
     "                false"),
    (LOGIC, "the future-instant guard is removed",
     "            if (phase == Phase.RUNNING && now < startedAt) return Stopwatch()",
     "            if (false) return Stopwatch()"),
    (LOGIC, "stop is offered as the suggested next action",
     "        Control.STOP -> if (phase == Phase.STOPPED) Tone.DEAD else Tone.SECONDARY",
     "        Control.STOP -> if (phase == Phase.STOPPED) Tone.DEAD else Tone.HIGHLIGHT"),
    (LOGIC, "the highlight does not move to pause when the clock runs",
     "        Control.PLAY -> if (phase == Phase.RUNNING) Tone.SECONDARY else Tone.HIGHLIGHT",
     "        Control.PLAY -> Tone.HIGHLIGHT"),
    (LOGIC, "pause is dead while paused, so the toggle only works one way",
     "            Phase.PAUSED -> Tone.SECONDARY\n            Phase.STOPPED -> Tone.DEAD",
     "            Phase.PAUSED -> Tone.DEAD\n            Phase.STOPPED -> Tone.DEAD"),
    (LOGIC, "play stops being a toggle and only ever starts",
     "        Control.PLAY -> if (phase == Phase.RUNNING) pause(now) else play(now)",
     "        Control.PLAY -> play(now)"),
    (LOGIC, "pause on a stopwatch showing zeros starts a measurement",
     "            Phase.STOPPED -> this\n        }\n        Control.STOP -> stop()",
     "            Phase.STOPPED -> play(now)\n        }\n        Control.STOP -> stop()"),
    (LOGIC, "the hour field is dropped below an hour, so the width moves again",
     '        return "%02d:%02d:%02d".format(h, m, s)',
     '        return if (h > 0L) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)'),
    (LOGIC, "the redraw delay can reach zero (an unbounded loop wearing a timer's clothes)",
     "        return if (r <= 0L) 1000L else r",
     "        return r - 1L"),
    (LOGIC, "the face rounds the seconds instead of truncating",
     "        val seconds = t / 1000L",
     "        val seconds = (t + 500L) / 1000L"),
    (PALETTE, "a swatch dark enough to vanish on black is offered",
     "        0xFFFFFFFF, 0xFFCBD5E1,",
     "        0xFF101010, 0xFFCBD5E1,"),
    (PALETTE, "the same swatch appears twice, so one cell does nothing",
     "        0xFFA3E635, 0xFF4ADE80,",
     "        0xFFEF4444, 0xFF4ADE80,"),
    (PALETTE, "a stored colour outside the grid is trusted rather than sanitised",
     "    fun sanitise(stored: Long): Long = if (stored in SWATCHES) stored else DEFAULT",
     "    fun sanitise(stored: Long): Long = stored"),
    (PALETTE, "the tick on a swatch is chosen by a guessed threshold again",
     "        return if (onBlack >= onWhite) 0xFF000000 else 0xFFFFFFFF",
     "        return if (l > 0.35) 0xFF000000 else 0xFFFFFFFF"),
]

_APP_VERSION_LINE = next(
    line + "\n" for line in PROPS.read_text().splitlines() if line.startswith("appVersion=")
)

SHAPE_MUTATIONS = [
    # The anchor is the whole BoxWithConstraints modifier chain, not just the background call.
    # The settings panel added a second .background(BACKGROUND) and this mutation started
    # matching twice and reporting SKIP, which reads almost like a caught mutation in a long
    # list. An anchor has to be unique to the thing it means.
    (UI, "tap-anywhere comes back on the background",
     "            .fillMaxSize()\n            .background(BACKGROUND)\n            .safeDrawingPadding()",
     "            .fillMaxSize()\n            .background(BACKGROUND)\n            .clickable { }\n            .safeDrawingPadding()"),
    (UI, "a button is hidden rather than dimmed when it cannot act",
     '                Transport(Icons.Default.Pause, "Pause", Control.PAUSE, state, button, ::commit)',
     '                if (state.tone(Control.PAUSE) != Tone.DEAD) Transport(Icons.Default.Pause, "Pause", Control.PAUSE, state, button, ::commit)'),
    (UI, "the third tone is collapsed, so dim means two things again",
     "            contentColor = if (tone == Tone.HIGHLIGHT) GLYPH else GLYPH_SECOND,",
     "            contentColor = GLYPH,"),
    (UI, "the circles come back around the transport glyphs",
     "        modifier = modifier.size(size),",
     "        modifier = modifier.size(size).border(1.5.dp, GLYPH, CircleShape),"),
    (UI, "the settings panel is moved over the digits, so colour is judged blind",
     "                    .align(Alignment.BottomCenter)",
     "                    .align(Alignment.Center)"),
    (UI, "the disabled tint is removed, so a dead button looks live",
     "            disabledContentColor = GLYPH_OFF,",
     "            disabledContentColor = GLYPH,"),
    (UI, "a secondary control is made inert, so the toggle only works one way on screen",
     "        enabled = tone != Tone.DEAD,",
     "        enabled = tone == Tone.HIGHLIGHT,"),
    (UI, "the tick loop becomes unbounded",
     "        while (isActive) {",
     "        while (true) {"),
    (STORE, "the save is queued rather than written, and loses the race with process death",
     "            .commit()",
     "            .apply()"),
    (STORE, "accumulated is not persisted, so a paused stopwatch comes back at zero",
     "            .putLong(K_ACCUMULATED, s.accumulated)\n",
     ""),
    # Read the current number rather than hardcoding it. An anchor with a version in it stops
    # matching the first time the version is bumped, and a SKIPPED mutation reads almost like a
    # caught one in a long list. This bit this sweep once already.
    (PROPS, "the version acquires a dot",
     _APP_VERSION_LINE,
     _APP_VERSION_LINE.rstrip("\n") + ".0\n"),
]


# ── THE SCAR TISSUE ──────────────────────────────────────────────────────────────────────────
# This script has now been killed mid-mutation twice — once by a session ending and once by a
# tool timeout — and both times it left a deliberately broken line in the working tree. The
# second time cost a confusing red baseline that looked like a real regression.
#
# So before touching anything, every file it can mutate is copied to a stash, and the first act
# of every run is to restore any stash left behind by a run that did not finish. A tool that
# edits source in place has to assume it will be interrupted, because it will be.
STASH = ROOT / ".sabotage-stash"


def restore_any_stash():
    if not STASH.exists():
        return
    restored = []
    for f in STASH.iterdir():
        target = next((t for t in MUTABLE if t.name == f.name), None)
        if target and target.read_text() != f.read_text():
            target.write_text(f.read_text())
            restored.append(f.name)
    if restored:
        print(f"restored from an interrupted run: {', '.join(restored)}")
    for f in STASH.iterdir():
        f.unlink()
    STASH.rmdir()


def take_stash():
    STASH.mkdir(exist_ok=True)
    for f in MUTABLE:
        (STASH / f.name).write_text(f.read_text())


def drop_stash():
    if STASH.exists():
        for f in STASH.iterdir():
            f.unlink()
        STASH.rmdir()


def run(cmd):
    p = subprocess.run(cmd, shell=True, cwd=ROOT, capture_output=True, text=True)
    return p.returncode, p.stdout + p.stderr


def sweep(title, mutations, cmd):
    print(f"\n{'=' * 76}\n{title}\n  watched by: {cmd}\n{'=' * 76}")
    code, out = run(cmd)
    if code != 0:
        print("BASELINE IS ALREADY RED. Nothing below would mean anything.")
        print(out[-1200:])
        return len(mutations)

    print("baseline green\n")
    bad = 0
    for path, name, old, new in mutations:
        original = path.read_text()
        if original.count(old) != 1:
            print(f"  SKIP      anchor found {original.count(old)} times: {name}")
            bad += 1
            continue
        try:
            path.write_text(original.replace(old, new))
            code, out = run(cmd)
        finally:
            path.write_text(original)
        if code == 0:
            print(f"  SURVIVED  nothing watches this: {name}")
            bad += 1
        else:
            print(f"  caught    {name}")
    return bad


restore_any_stash()
take_stash()

# A slice, so a long sweep can be run in pieces that each fit inside whatever is running it.
# SABOTAGE_SLICE="0:10" does the first ten logic mutations and nothing else. Without it the
# whole thing runs, which is what a release should do.
slice_spec = os.environ.get("SABOTAGE_SLICE")
logic = LOGIC_MUTATIONS
shape = SHAPE_MUTATIONS
if slice_spec:
    a, b = (int(x) for x in slice_spec.split(":"))
    everything = [("logic", m) for m in LOGIC_MUTATIONS] + [("shape", m) for m in SHAPE_MUTATIONS]
    chosen = everything[a:b]
    logic = [m for kind, m in chosen if kind == "logic"]
    shape = [m for kind, m in chosen if kind == "shape"]
    print(f"slice {a}:{b} of {len(everything)}")

bad = 0
if logic:
    bad += sweep(f"LOGIC, {len(logic)} mutations", logic, TEST_CMD)
if shape:
    bad += sweep(f"SHAPE, {len(shape)} mutations", shape, CHECK_CMD)

drop_stash()

total = len(logic) + len(shape)
print(f"\n{total} mutations, {total - bad} caught, {bad} survived or skipped")
sys.exit(1 if bad else 0)
