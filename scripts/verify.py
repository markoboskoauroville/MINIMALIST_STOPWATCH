#!/usr/bin/env python3
"""
verify.py — the checks that are cheap enough to run on every push, and that a compiler will not
run for you.

Every check PRINTS WHAT IT EXAMINED, not only what it found. delivery-gate.md 14: a check that
finds nothing and a check that runs nothing look identical from outside, and this account has
already had a check report zero findings for a twelve-entry enum because the indentation
differed. A zero here is a failure of the check until proven otherwise, which is why every one
of these asserts a minimum count of things looked at.

Each check can fail while the others pass. Run scripts/sabotage.py to see them fail on purpose.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/mantra/stopwatch"
LOGIC = MAIN / "Stopwatch.kt"
UI = MAIN / "MainActivity.kt"
STORE = MAIN / "Store.kt"

failures = []
checks_run = []


def check(name, ok, detail):
    checks_run.append(name)
    print(f"{'pass' if ok else 'FAIL'}  {name}: {detail}")
    if not ok:
        failures.append(name)


# ── 1 ────────────────────────────────────────────────────────────────────────────────────────
# The timing logic must not import anything from Android. The moment it does, Test 1 stops being
# a test of the mechanism and becomes a test of an emulator that is not present.
src = LOGIC.read_text()
ui = UI.read_text()
store = STORE.read_text()

imports = re.findall(r"^import\s+(\S+)", src, re.M)
android = [i for i in imports if i.startswith("android")]
check("the timing logic touches nothing Android",
      not android,
      f"{len(imports)} imports in Stopwatch.kt, {len(android)} from android.*")

# ── 2 ────────────────────────────────────────────────────────────────────────────────────────
# The wall clock is never read for a measurement. System.currentTimeMillis moves when a time
# server corrects it and the stopwatch must not jump because of that. It is allowed in Store.kt
# for exactly one purpose, the boot marker, which is not a measurement.
wall = []
for f in (LOGIC, UI):
    for n, line in enumerate(f.read_text().splitlines(), 1):
        if "currentTimeMillis" in line and not line.strip().startswith("*"):
            wall.append(f"{f.name}:{n}")
store_wall = [n for n, line in enumerate(STORE.read_text().splitlines(), 1)
              if "currentTimeMillis" in line and not line.strip().startswith("*")]
check("wall time is never used to measure",
      not wall and len(store_wall) == 1,
      f"0 uses in the logic and the screen, {len(store_wall)} in Store.kt (the boot marker, expected 1)")

# ── 3 ────────────────────────────────────────────────────────────────────────────────────────
# Elapsed time is computed by subtraction, never by adding a delta to a running total. This is
# the drift the whole design is built to avoid, and it looks fine for a minute.
bad_add = re.findall(r"accumulated\s*\+=|elapsed\s*\+=|\+=\s*(?:step|tick|delta|interval)", src)
subtractions = re.findall(r"now\s*-\s*startedAt", src)
check("elapsed is subtraction, never an accumulating delta",
      not bad_add and len(subtractions) >= 1,
      f"{len(subtractions)} subtraction sites, {len(bad_add)} accumulating assignments")

# ── 4 ────────────────────────────────────────────────────────────────────────────────────────
# The nine cases. Three buttons times three phases, and every one of them has to have an answer
# that the screen can ask for without knowing what a phase is.
def enum_members(name):
    m = re.search(rf"enum class {name}\s*\{{([^}}]*)\}}", src)
    return [x.strip() for x in m.group(1).split(",") if x.strip()] if m else []

phases = enum_members("Phase")
controls = enum_members("Control")
tones = enum_members("Tone")
# Both tables must exist and both must be total. `when` over an enum with no else is exhaustive
# in Kotlin, so the compiler enforces the rows; this asserts the tables are the size they claim.
has_press = re.search(r"fun press\(control: Control, now: Long\)", src) is not None
has_tone = re.search(r"fun tone\(control: Control\)", src) is not None
check("both button tables are total for every control and phase",
      has_press and has_tone and len(phases) == 3 and len(controls) == 3 and len(tones) >= 3,
      f"{len(controls)} controls x {len(phases)} phases = {len(controls) * len(phases)} cases, "
      f"two tables (press, tone), {len(tones)} tones: {', '.join(tones)}")

# ── 4b ───────────────────────────────────────────────────────────────────────────────────────
# v4 made dim ambiguous: play is dim while running and pressing it still pauses the clock. If
# the third tone is ever collapsed back into two, dim means two things again and this goes red.
# Declaring the colour is not using it. Three mutations survived the first version of this check
# because it asked whether GLYPH_SECOND EXISTED, and a val that nothing reads exists perfectly
# well. It now reads the actual colour expression and the actual enabled expression.
colours = re.search(r"iconButtonColors\((.*?)\)\s*,\s*\)", ui, re.S)
colour_body = colours.group(1) if colours else ""
tones_used = {t for t in ("GLYPH_PRIMARY", "GLYPH", "GLYPH_SECOND", "GLYPH_OFF") if t in colour_body}
enabled_expr = re.search(r"enabled\s*=\s*(tone[^,\n]*)", ui)
enabled_text = enabled_expr.group(1).strip() if enabled_expr else ""
check("every tone reaches the drawn colour, and only DEAD is inert",
      tones_used == {"GLYPH_PRIMARY", "GLYPH", "GLYPH_SECOND", "GLYPH_OFF"}
      and enabled_text == "tone != Tone.DEAD",
      f"{len(tones_used)} of 4 tones reach the drawn colour: `enabled = {enabled_text}`")

# ── 4c ───────────────────────────────────────────────────────────────────────────────────────
# The white play glyph is a deliberate hole in "the digits are the only white thing". The hole is
# only defensible because it closes the instant a measurement starts. If PRIMARY is ever handed
# to a control other than play, or survives into RUNNING, this goes red.
primary_rows = re.findall(r"Tone\.PRIMARY", src)
play_row = re.search(r"Control\.PLAY -> if \(phase == Phase\.RUNNING\) Tone\.SECONDARY else Tone\.PRIMARY", src)
check("white is only ever play, and only while the clock is idle",
      play_row is not None and len(primary_rows) == 1,
      f"{len(primary_rows)} PRIMARY cell in the table of 9, on play, absent while running")

# ── 4d ───────────────────────────────────────────────────────────────────────────────────────
# v5 replaced the orientation LOCK with an orientation CHOICE. The app no longer follows the
# phone at all, and both branches force a sensor-class orientation so a phone laid on a table
# can still turn 180 degrees within the orientation that was chosen.
forced = re.findall(r"SCREEN_ORIENTATION_SENSOR_(PORTRAIT|LANDSCAPE)", ui)
check("the corner button sets the orientation rather than locking it",
      sorted(forced) == ["LANDSCAPE", "PORTRAIT"] and "SCREEN_ORIENTATION_LOCKED" not in ui,
      f"{len(forced)} forced orientations, no lock, no unspecified: {', '.join(sorted(forced))}")

# ── 4e ───────────────────────────────────────────────────────────────────────────────────────
# design-language.md 5: a control says what the next press DOES, not what is currently true. In
# portrait the button must show the LANDSCAPE glyph. Getting this backwards is invisible in a
# screenshot and obvious in the hand, which is exactly the kind of fault a check should hold.
# The mutation sweep walked through the first version of this file with it reversed.
says_next = re.search(
    r"Orientation\.PORTRAIT\)\s*Icons\.Default\.StayCurrentLandscape\s*\n\s*else Icons\.Default\.StayCurrentPortrait", ui)
label_next = re.search(r'Orientation\.PORTRAIT\)\s*"Turn landscape"\s*else\s*"Turn portrait"', ui)
check("the orientation button shows where the next press goes, not where you are",
      says_next is not None and label_next is not None,
      "in portrait it offers landscape, and the spoken label agrees with the glyph")

# ── 5 ────────────────────────────────────────────────────────────────────────────────────────
# G5. Every loop in the tree, counted, and each one read for what bounds it. The count is the
# point: a grep that matches nothing and a grep pointed at the wrong directory print the same
# thing.
loops = []
for f in sorted(MAIN.glob("*.kt")):
    for n, line in enumerate(f.read_text().splitlines(), 1):
        if re.search(r"\b(while|for|repeat)\s*[\(\{]", line) and not line.strip().startswith("*"):
            loops.append((f.name, n, line.strip()))
unbounded = [l for l in loops if "while (true)" in l[2] or "while(true)" in l[2]]
check("every loop has a bound a reader can see",
      len(loops) >= 2 and not unbounded,
      f"{len(loops)} loops examined, {len(unbounded)} written as while(true)")

# ── 6 ────────────────────────────────────────────────────────────────────────────────────────
# The redraw delay can never be zero. A zero-delay repost is an unbounded loop wearing a timer's
# clothes, and it presents as a hot phone rather than as an error.
check("the redraw delay can never be zero",
      "if (r <= 0L) 1000L else r" in src and "untilNextSecond" in src,
      "untilNextSecond floors at 1ms and is capped at 1000ms by construction")

# ── 7 ────────────────────────────────────────────────────────────────────────────────────────
# Tap-anywhere is gone and its absence is a decision. If a clickable ever reappears on the
# background this goes red, because that is the stray touch that destroys a measurement.
background_click = re.search(r"\.background\(BACKGROUND\)[\s\S]{0,200}?\.clickable", ui)
clickables = len(re.findall(r"\.clickable\(", ui))
check("nothing on the background is tappable",
      background_click is None and clickables == 0,
      f"{clickables} clickable modifiers in the screen, buttons carry their own onClick")

# ── 8 ────────────────────────────────────────────────────────────────────────────────────────
# Never hide a control that is temporarily unavailable. A disabled button is dimmed; a button
# removed from the tree moves everything beside it.
# The mutation sweep walked straight through the old version of this: it looked for the word
# canPause, which v4 deleted, so it was checking for a shape that could no longer exist. It now
# looks for ANY conditional wrapping a transport emission, whatever the condition is written in.
hidden = re.search(r"\bif\s*\(.*?\)\s*\{?\s*(Transport|Glyph)\s*\(", ui)
emitted = len(re.findall(r"^\s*Transport\(", ui, re.M))
check("no button is hidden when it cannot act",
      hidden is None and emitted == 3,
      f"{emitted} transport controls emitted unconditionally, 0 wrapped in a condition")

# ── 8b ───────────────────────────────────────────────────────────────────────────────────────
# The circles were removed on 27.8.2026 because on glass they read as three more shapes on a
# screen whose whole design is what is absent. The HOT ZONE stayed. If a border ever returns to
# the transport, this goes red.
borders = re.findall(r"\.border\(", ui)
check("nothing is drawn around the transport glyphs",
      not borders,
      f"{len(borders)} border modifiers in the screen, and the touch target is IconButton's own size")

# ── 8c ───────────────────────────────────────────────────────────────────────────────────────
# One transport row, not one per orientation. v2 had a landscape branch putting the buttons down
# the right edge and a portrait branch putting them at the bottom, which was two places for the
# same thing to be different. Each glyph is now written exactly once.
counts = {g: len(re.findall(rf"Icons\.Default\.{g}\b", ui)) for g in ("PlayArrow", "Pause", "Stop")}
check("the transport is written once, not once per orientation",
      set(counts.values()) == {1},
      f"play x{counts['PlayArrow']}, pause x{counts['Pause']}, stop x{counts['Stop']}, all at the bottom")

# ── 8d ───────────────────────────────────────────────────────────────────────────────────────
# The colour and the weight are judged against the digits, so the panel may never be drawn over
# them. It is aligned to the bottom of the screen, over the black below the numbers.
check("the settings panel sits at the bottom and not over the digits",
      "Alignment.BottomCenter" in ui and "SettingsGrid" in ui,
      "the grid is aligned BottomCenter, and every press applies live")

# ── 8e ───────────────────────────────────────────────────────────────────────────────────────
# v5's panel was sized by WIDTH ALONE. On a landscape phone that made each cell about 130dp and
# four rows of it taller than the display, so the panel covered the whole screen including its
# own way out. A panel that can grow past the display is a trap, and the fix is measuring
# against both edges, not picking a smaller number.
sized_by_both = re.search(r"minOf\(\(width - gap \* \(columns - 1\)\) / columns,\s*forGrid / rows", ui)
has_budget = "maxHeight" in ui and re.search(r"maxHeight\s*=\s*screenH\s*\*", ui)
check("the settings panel cannot grow taller than the screen",
      sized_by_both is not None and has_budget is not None,
      "the cell is the smaller of what the width allows and what the height allows, capped at 64dp")

# ── 8f ───────────────────────────────────────────────────────────────────────────────────────
# The panel must carry its own way out. In landscape v5 the corner gear was the only exit and
# the panel covered it, which is the specific trap Baba hit.
check("the settings panel carries its own way out",
      'Icons.Default.Close, "Close settings"' in ui and "onClose" in ui,
      "an X inside the panel, independent of the corner control")

# ── 8g ───────────────────────────────────────────────────────────────────────────────────────
# The words on the controls ARE the voice vocabulary: Google's Voice Access matches speech
# against contentDescription. If these drift back to the model's internal names, the spoken
# commands stop working and nothing else breaks, which is the worst way for it to fail.
labels = set(re.findall(r'Transport\(Icons\.Default\.\w+, "(\w+)"', ui))
check("the spoken vocabulary is on the controls",
      labels == {"Start", "Pause", "Reset"},
      f"contentDescriptions: {', '.join(sorted(labels))} — these are what Voice Access listens for")

# ── 9 ────────────────────────────────────────────────────────────────────────────────────────
# Both timing fields have to be written, or the one that is not is the one that comes back wrong.
store = STORE.read_text()
# Only the body of save(), not the whole file: the orientation lock is a preference written by
# its own setter and counting it here would let a genuinely missing timing field hide behind it.
save_body = re.search(r"fun save\(s: Stopwatch\)\s*\{(.*?)\n    \}", store, re.S).group(1)
written = re.findall(r"\.put\w+\(K_(\w+)", save_body)
check("every field needed to restore is written",
      len(set(written)) == 5,
      f"{len(set(written))} of 5 fields written on save: {', '.join(sorted(set(written)))}")

# ── 10 ───────────────────────────────────────────────────────────────────────────────────────
# The save that matters is the one made as the process is taken away, and apply() is a promise
# to write on a thread that may never run.
check("the save that survives process death is synchronous",
      ".commit()" in save_body and ".apply()" not in save_body,
      f"save() ends in commit; {store.count('.apply()')} apply() calls elsewhere, all preferences")

# ── 11 ───────────────────────────────────────────────────────────────────────────────────────
# versioning.md 3: the number lives in one place here and every other form is derived from it.
props = (ROOT / "gradle.properties").read_text()
gradle = (ROOT / "app/build.gradle.kts").read_text()
m = re.search(r"^appVersion=(\d+)$", props, re.M)
check("the version is one whole number in one place",
      m is not None and "versionCode = appVersion" in gradle and 'versionName = appVersion.toString()' in gradle,
      f"appVersion={m.group(1) if m else 'MISSING'}, versionCode and versionName both derived")

print()
print(f"{len(checks_run) - len(failures)} of {len(checks_run)} checks passed")
if failures:
    print("failed: " + ", ".join(failures))
sys.exit(1 if failures else 0)
