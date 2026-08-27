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
PROPS = ROOT / "gradle.properties"

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
    (LOGIC, "the face rounds instead of truncating",
     "        val tenths = t / 100L",
     "        val tenths = (t + 50L) / 100L"),
    (LOGIC, "the hour field is shown from zero (the width changes below an hour)",
     "        return if (h > 0L) {",
     "        return if (h >= 0L) {"),
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
    (LOGIC, "stop is offered while already stopped",
     "    fun canStop(): Boolean = phase != Phase.STOPPED",
     "    fun canStop(): Boolean = true"),
    (LOGIC, "play is offered while already running",
     "    fun canPlay(): Boolean = phase != Phase.RUNNING",
     "    fun canPlay(): Boolean = true"),
    (LOGIC, "pause is offered while stopped",
     "    fun canPause(): Boolean = phase == Phase.RUNNING",
     "    fun canPause(): Boolean = phase != Phase.PAUSED"),
    (LOGIC, "the redraw delay can reach zero (an unbounded loop wearing a timer's clothes)",
     "        return if (r <= 0L) 100L else r",
     "        return r - 1L"),
]

_APP_VERSION_LINE = next(
    line + "\n" for line in PROPS.read_text().splitlines() if line.startswith("appVersion=")
)

SHAPE_MUTATIONS = [
    (UI, "tap-anywhere comes back on the background",
     "            .background(BACKGROUND)",
     "            .background(BACKGROUND)\n            .clickable { }"),
    (UI, "a button is hidden rather than dimmed when it cannot act",
     '    Circle(Icons.Default.Pause, "Pause", state.canPause(), size, true) {',
     '    if (state.canPause()) Circle(Icons.Default.Pause, "Pause", state.canPause(), size, true) {'),
    (UI, "the disabled tint is removed, so a dead button looks live",
     "            disabledContentColor = GLYPH_OFF,",
     "            disabledContentColour = GLYPH_OFF,"),
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


bad = 0
bad += sweep(f"LOGIC, {len(LOGIC_MUTATIONS)} mutations", LOGIC_MUTATIONS, TEST_CMD)
bad += sweep(f"SHAPE, {len(SHAPE_MUTATIONS)} mutations", SHAPE_MUTATIONS, CHECK_CMD)

total = len(LOGIC_MUTATIONS) + len(SHAPE_MUTATIONS)
print(f"\n{total} mutations, {total - bad} caught, {bad} survived or skipped")
sys.exit(1 if bad else 0)
