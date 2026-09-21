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
VOICE = ROOT / "app/src/main/java/com/mantra/stopwatch/Voice.kt"
PROPS = ROOT / "gradle.properties"
# VoiceListener.kt WAS IN THIS LIST AND HAS NOT EXISTED FOR SEVERAL VERSIONS. SpeechRecognizer
# was taken out and the file went with it; this script kept naming it, kept it in MUTABLE, and
# therefore crashed on its own first line of work — take_stash() reads every file in MUTABLE.
# So the sweep this repository calls "the important one" had not run at all since that removal,
# and nothing said so, because nothing runs it. It is not in the workflow: a script that only
# fails when a person remembers to type it is a script that fails silently.
#
# Two mistakes worth naming rather than quietly deleting. The path was declared TWICE, which is
# how a stale one survives a read-through — the eye finds the second and assumes the first was
# the mistake. And a crash here looks nothing like a caught mutation, so the one run that would
# have shown it up was the one nobody did.
MUTABLE = [LOGIC, UI, STORE, PALETTE, VOICE, PROPS]

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
    (LOGIC, "a preset the plus and minus could never reach is allowed into the list",
     "    if (seconds !in TIMER_MIN..TIMER_MAX) return presets",
     "    if (false) return presets"),
    (LOGIC, "the preset list drops the oldest to make room, losing one he saved",
     "    if (presets.size >= PRESETS_MAX) return presets",
     "    if (presets.size >= PRESETS_MAX) return presets.drop(1) + seconds"),
    # POINTED AT THE LINE THAT ACTUALLY ENFORCES IT. It was aimed at an early return above this,
    # survived, and that survival was the useful result: the early return was dead code and the
    # deduplication was distinct() all along. The guard is gone and the mutation now breaks the
    # thing that was doing the work.
    (LOGIC, "pressing plus twice puts the same duration in twice",
     "    return (presets + seconds).distinct().sorted()",
     "    return (presets + seconds).sorted()"),
    (LOGIC, "an unreadable preset string throws instead of reading as nothing",
     "        .mapNotNull { it.trim().toIntOrNull() }",
     "        .map { it.trim().toInt() }"),
    (LOGIC, "a step is made variable again, so the button lies about what it does",
     "val TIMER_STEPS = listOf(30, 60, 600)",
     "val TIMER_STEPS = listOf(15, 60, 600)"),
    (LOGIC, "a pinch out and a pinch in need different amounts of travel",
     "    zoom <= 1f / PINCH_RATIO -> Pinch.IN",
     "    zoom <= 0.5f -> Pinch.IN"),
    (LOGIC, "a resting hand is enough to pinch",
     "const val PINCH_RATIO = 1.25f",
     "const val PINCH_RATIO = 1.01f"),
    (LOGIC, "a tap an hour later counts as the second of two and resets the clock",
     "    previousTapAt > 0L && now >= previousTapAt && now - previousTapAt <= DOUBLE_TAP_MS",
     "    previousTapAt > 0L && now >= previousTapAt"),
    (LOGIC, "the first tap of all is treated as the second of two",
     "    previousTapAt > 0L && now >= previousTapAt && now - previousTapAt <= DOUBLE_TAP_MS",
     "    now >= previousTapAt && now - previousTapAt <= DOUBLE_TAP_MS"),
    (LOGIC, "a clock that goes backwards throws a measurement away",
     "    previousTapAt > 0L && now >= previousTapAt && now - previousTapAt <= DOUBLE_TAP_MS",
     "    previousTapAt > 0L && now - previousTapAt <= DOUBLE_TAP_MS"),
    (LOGIC, "stop is offered as the suggested next action",
     "        Control.STOP -> if (phase == Phase.STOPPED) Tone.DEAD else Tone.SECONDARY",
     "        Control.STOP -> if (phase == Phase.STOPPED) Tone.DEAD else Tone.HIGHLIGHT"),
    (LOGIC, "a spoken command is renamed and the button silently stops answering to it",
     'PLAY("Start"),',
     'PLAY("Play"),'),
    (LOGIC, "two controls answer to the same spoken word",
     'PAUSE("Pause"),',
     'PAUSE("Start"),'),
    (LOGIC, "the white play glyph stays white while the clock runs",
     "        Control.PLAY -> if (phase == Phase.RUNNING) Tone.SECONDARY else Tone.PRIMARY",
     "        Control.PLAY -> Tone.PRIMARY"),
    (LOGIC, "white leaks onto a second control",
     "        Control.STOP -> if (phase == Phase.STOPPED) Tone.DEAD else Tone.SECONDARY",
     "        Control.STOP -> if (phase == Phase.STOPPED) Tone.DEAD else Tone.PRIMARY"),
    (LOGIC, "play stops being white when the clock is idle",
     "        Control.PLAY -> if (phase == Phase.RUNNING) Tone.SECONDARY else Tone.PRIMARY",
     "        Control.PLAY -> if (phase == Phase.RUNNING) Tone.SECONDARY else Tone.HIGHLIGHT"),
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
    (VOICE, "the gate opens on every partial, so one word toggles the clock several times",
     "        if (firedThisUtterance) return false",
     "        if (false) return false"),
    (VOICE, "the minimum gap is removed, so the tail of a word acts twice",
     "        if (hasFired && now - firedAt < minGapMs) return false",
     "        if (false) return false"),
    (VOICE, "the never-fired sentinel goes back to an instant arithmetic can wrap",
     "        if (hasFired && now - firedAt < minGapMs) return false",
     "        if (now - Long.MIN_VALUE < minGapMs) return false"),
    (PALETTE, "a swatch dark enough to vanish on black is offered",
     "        0xFFFFFFFF, 0xFFE2E8F0,",
     "        0xFF101010, 0xFFE2E8F0,"),
    (PALETTE, "the same swatch appears twice, so one cell does nothing",
     "        0xFF4ADE80, 0xFF86EFAC,",
     "        0xFFEF4444, 0xFF86EFAC,"),
    (VOICE, "a word is shared by two controls, so it matches neither",
     '            "stop", "stops", "stopped", "hold", "wait",',
     '            "stop", "stops", "stopped", "hold", "reset",'),
    (VOICE, "a sentence naming two controls picks one instead of refusing",
     "        return if (hits.size == 1) hits.single() else null",
     "        return hits.firstOrNull()"),
    (VOICE, "the heard text is judged raw, so punctuation defeats every command",
     "        val words = normalise(raw).toSet()",
     "        val words = raw.split(\" \").toSet()"),
    (VOICE, "the meter is not clamped, so a loud room drives the bar past the end",
     "        val gated = ((normalised.coerceIn(0f, 1f) - NOISE_GATE) / (1f - NOISE_GATE)).coerceIn(0f, 1f)",
     "        val gated = (normalised - NOISE_GATE) / (1f - NOISE_GATE)"),
    (VOICE, "the noise gate is removed and the meter twitches in silence",
     "        val gated = ((normalised.coerceIn(0f, 1f) - NOISE_GATE) / (1f - NOISE_GATE)).coerceIn(0f, 1f)",
     "        val gated = normalised.coerceIn(0f, 1f)"),
    (VOICE, "attack and release are swapped, so the meter lags going up",
     "        val rate = if (curved > level) ATTACK else RELEASE",
     "        val rate = if (curved > level) RELEASE else ATTACK"),
    (VOICE, "the reminder names a word the matcher does not accept",
     '            "reset", "resets", "recept", "reserve",          // "recept" is the common mishearing',
     '            "wipe", "resets", "recept", "reserve",          // "recept" is the common mishearing'),
    (PALETTE, "a swatch is dropped, so the grid is ragged in one orientation",
     "        0xFF22D3EE, 0xFF67E8F9, 0xFF2DD4BF,",
     "        0xFF22D3EE, 0xFF2DD4BF,"),
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
     "            .safeDrawingPadding()\n    ) {\n        val screenW = maxWidth",
     "            .clickable { }\n            .safeDrawingPadding()\n    ) {\n        val screenW = maxWidth"),
    # RE-ANCHORED AT v45. The old anchor still said `::commit`, which the Transport call stopped
    # using several versions ago, so this mutation had been skipping rather than running — and a
    # SKIP in a long list reads almost like a catch. It matters more now than it did: v45 added
    # the one condition allowed to wrap a control, so the check has to still refuse every other.
    (UI, "a button is hidden rather than dimmed when it cannot act",
     "                Transport(Icons.Default.Pause, Control.PAUSE, state, button) { next ->",
     "                if (state.tone(Control.PAUSE) != Tone.DEAD) Transport(Icons.Default.Pause, Control.PAUSE, state, button) { next ->"),
    (UI, "the tone ladder is collapsed, so prominence means nothing",
     "                Tone.PRIMARY -> GLYPH_PRIMARY\n                Tone.HIGHLIGHT -> GLYPH",
     "                Tone.PRIMARY -> GLYPH\n                Tone.HIGHLIGHT -> GLYPH"),
    (UI, "the app goes back to following the phone instead of being told",
     "            Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT",
     "            Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED"),
    # Indented four further at v45, when the top controls went inside the full-screen group.
    (UI, "the orientation button shows where you are instead of where you would go",
     "                icon = if (orientation == Orientation.PORTRAIT) Icons.Default.StayCurrentLandscape\n                       else Icons.Default.StayCurrentPortrait,",
     "                icon = if (orientation == Orientation.PORTRAIT) Icons.Default.StayCurrentPortrait\n                       else Icons.Default.StayCurrentLandscape,"),
    (UI, "the circles come back around the transport glyphs",
     "        modifier = modifier.size(size),",
     "        modifier = modifier.size(size).border(1.5.dp, GLYPH, CircleShape),"),
    (UI, "the panel is sized by width alone again, and can grow past the screen",
     "    val cell = minOf((width - gap * (columns - 1)) / columns, forGrid / rows, 64.dp)",
     "    val cell = (width - gap * (columns - 1)) / columns"),
    (UI, "the panel loses its own way out, leaving only the corner it can cover",
     '            Glyph(Icons.Default.Close, "Close settings", Tone.HIGHLIGHT, 32.dp, onPress = onClose)',
     "            Box(Modifier.size(32.dp))"),
    (UI, "a control label is typed at the call site again, so it can drift from the tip",
     "                Transport(Icons.Default.Stop, Control.STOP, state, button, ::commit)",
     '                Transport(Icons.Default.Stop, "Stop", Control.STOP, state, button, ::commit)'),
    (UI, "the reminder is typed by hand instead of generated, so it can become a believed lie",
     '                    Control.entries.joinToString("  ") { Heard.primary(it) }',
     '                    "start  pause  reset"'),
    (UI, "the meter is fed a raw level, so a loud room runs the bar off the panel",
     "                            .fillMaxWidth(level.coerceIn(0f, 1f))",
     "                            .fillMaxWidth(level)"),
    (UI, "the microphone is left running when the screen goes away",
     "            onDispose { v.stop() }",
     "            onDispose { }"),
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
    # ── v45's own rules, broken on purpose ───────────────────────────────────────────────
    (UI, "the ring comes back round the mode letter, the one place it hid last time",
     "                    .size(40.dp)\n                    .clickable {",
     "                    .size(40.dp)\n                    .border(1.dp, GLYPH_SECOND, CircleShape)\n                    .clickable {"),
    (UI, "full screen hides the transport but leaves the strip's height reserved",
     "        val strip = if (fullscreen) 0.dp else if (landscape) 72.dp else 108.dp",
     "        val strip = if (landscape) 72.dp else 108.dp"),
    # RE-ANCHORED AT v46: the way back moved from the long press to the pinch, and both doors
    # now go through one function instead of being written where they happen.
    (UI, "full screen has no way back, so the controls can be taken away for good",
     "                            } else if (verdict == Pinch.IN && fullscreen) {\n                                fired = true\n                                leaveFullscreen()",
     "                            } else if (false) {\n                                fired = true\n                                leaveFullscreen()"),
    (UI, "the panel is left open behind a screen that draws no way of closing it",
     "        settingsOpen = false\n        fullscreen = true",
     "        fullscreen = true"),
    # ── v46's own rules ──────────────────────────────────────────────────────────────────
    (UI, "one finger is enough to pinch, so a swipe changes the screen",
     "                        if (!fired && event.changes.size >= 2) {",
     "                        if (!fired) {"),
    (UI, "the pinch fires on every frame while the fingers are still moving",
     "                        if (!fired && event.changes.size >= 2) {",
     "                        if (event.changes.size >= 2) {"),
    (UI, "the zoom is accumulated across gestures, so small movements add up to a change",
     "                awaitEachGesture {\n                    awaitFirstDown(requireUnconsumed = false)\n                    var zoom = 1f",
     "                var zoom = 1f\n                awaitEachGesture {\n                    awaitFirstDown(requireUnconsumed = false)"),
    (UI, "a pinch that acted does not consume, so the fingers lifting also read as a tap",
     "                            if (fired) event.changes.forEach { it.consume() }",
     "                            if (false) event.changes.forEach { it.consume() }"),
    (UI, "the pinch runs over the settings panel and swallows it",
     "                if (settingsOpen) return@pointerInput",
     "                if (false) return@pointerInput"),
    (UI, "reset is put back on a single isolated tap, which is exactly what v1 removed",
     "                            if (isDoubleTap(lastTapAt, now)) {",
     "                            if (true) {"),
    (UI, "the double-tap window is never armed, so two taps never reset",
     "                            lastTapAt = now",
     "                            lastTapAt = 0L"),
    (UI, "every tap is held back to look for a second one, off the front of every measurement",
     "                        onClick = {\n                            val now = SystemClock.elapsedRealtime()",
     "                        onDoubleClick = {},\n                        onClick = {\n                            val now = SystemClock.elapsedRealtime()"),
    (UI, "the two sides of the duration row are typed out instead of generated",
     "                TIMER_STEPS.reversed().forEach { step ->",
     "                listOf(600, 60, 30).forEach { step ->"),
    (UI, "the duration is moved by arithmetic in the interface, past its own bound",
     "                        onTimerSeconds(timerShift(timerSeconds, step))",
     "                        onTimerSeconds(timerSeconds + step)"),
    (UI, "the plus becomes a control beside the list instead of the next cell in it",
     "            val cells: List<Int?> = presets + listOf(null)",
     "            val cells: List<Int?> = presets"),
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
