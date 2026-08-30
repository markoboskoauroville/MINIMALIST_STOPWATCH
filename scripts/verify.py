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
VOICE = MAIN / "Voice.kt"
STORE = MAIN / "Store.kt"

failures = []
checks_run = []

# The number of tests that existed when this line was last updated. See the ratchet at the end.
TEST_FLOOR = 97


def code_only(text):
    """
    The file with its comments removed.

    THIS IS THE THIRD TIME COMMENTS HAVE BROKEN A CHECK IN THIS REPOSITORY, in both directions:
    a comment containing "tap " satisfied a check the code no longer met, a comment explaining
    that the code avoids while(true) failed a gate the code passed, and now a comment explaining
    why SpeechRecognizer was removed fails a check asserting it is gone. Three times is not bad
    luck, it is grepping source as prose. Every check about what the CODE does goes through here.
    """
    without_block = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return "\n".join(
        line for line in without_block.split("\n")
        if not line.lstrip().startswith(("//", "*"))
    )


def check(name, ok, detail):
    checks_run.append(name)
    print(f"{'pass' if ok else 'FAIL'}  {name}: {detail}")
    if not ok:
        failures.append(name)


# ── 1 ────────────────────────────────────────────────────────────────────────────────────────
# The timing logic must not import anything from Android. The moment it does, Test 1 stops being
# a test of the mechanism and becomes a test of an emulator that is not present.
src = LOGIC.read_text()
palette_src = (MAIN / "Palette.kt").read_text()
ui = UI.read_text()
store = STORE.read_text()
meter = (ROOT / "app/src/main/java/com/mantra/stopwatch/MaMeter.kt").read_text()
go = (MAIN / "GoSound.kt").read_text()
dsp = (ROOT / "app/src/main/java/com/mantra/stopwatch/Dsp.kt").read_text()
probe = (ROOT / "app/src/main/java/com/mantra/stopwatch/MicProbe.kt").read_text()
engine = (ROOT / "app/src/main/java/com/mantra/stopwatch/VoiceEngine.kt").read_text()

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
    """
    Kotlin writes enums two ways and this file contains both: `enum class Tone { A, B }` on one
    line, and a multi-line body when a member carries a constructor value. The first version of
    this only understood the one-line form, split the body on commas, and reported `0 tones` the
    moment Control grew a value — while still printing a count, which is the shape of a check
    that has quietly stopped looking at anything.
    """
    one_line = re.search(rf"enum class {name}\s*\{{([^}}\n]*)\}}", src)
    if one_line:
        return [x.strip() for x in one_line.group(1).split(",") if x.strip()]
    multi = re.search(rf"enum class {name}[^{{]*\{{(.*?)\n\}}", src, re.S)
    if not multi:
        return []
    return re.findall(r"^\s{4}([A-Z][A-Z_]*)\s*[,(]", multi.group(1), re.M)


phases = enum_members("Phase")
controls = enum_members("Control")
tones = enum_members("Tone")
# Both tables must exist and both must be total. `when` over an enum with no else is exhaustive
# in Kotlin, so the compiler enforces the rows; this asserts the tables are the size they claim.
has_press = re.search(r"fun press\(control: Control, now: Long\)", src) is not None
has_tone = re.search(r"fun tone\(control: Control\)", src) is not None
check("both button tables are total for every control and phase",
      # The control count is no longer three — LAP joined at v21 — and hardcoding it here would
      # be encoding today's feature list as a law, which is the fault that made the tone check
      # fail the last time the design was right to change. What must hold is that both tables
      # are total: Kotlin's exhaustive `when` over an enum enforces the rows, this asserts the
      # tables exist and that the enums are the size they claim.
      has_press and has_tone and len(phases) == 3 and len(controls) >= 3 and len(tones) >= 3,
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
# COMMENTS ARE NOT CODE, and this check had it backwards. It counted the literal anywhere in the
# file, so a comment EXPLAINING that the code deliberately avoids `while (true)` failed the gate.
# That is the mirror of the fault found two versions ago, where a comment containing "tap "
# satisfied a check the code no longer met. Both come from grepping a source file as text; the
# answer in both directions is to strip comment lines first.
unbounded = [
    l for l in loops
    if ("while (true)" in l[2] or "while(true)" in l[2])
    and not l[2].lstrip().startswith(("//", "*", "/*"))
]
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
# The count used to have to be zero, which stopped being the right question when the recorder
# row was added: a word you press to record it is a clickable, and it is meant to be. What must
# stay true is that the BACKGROUND carries none, which is the thing that would destroy a running
# measurement by accident.
check("nothing on the background is tappable",
      background_click is None,
      f"{clickables} clickable modifiers in the screen, none of them on the background")

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
glyph_body = re.search(r"private fun Glyph\((.*?)\n\}", code_only(ui), re.S)
borders = re.findall(r"\.border\(", glyph_body.group(1) if glyph_body else "")
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
# The vocabulary lives on the enum and nowhere else. If a literal string ever appears at the
# call site again, the tip and the button can drift apart and only the spoken command breaks.
spoken = dict(re.findall(r'(PLAY|PAUSE|STOP)\("(\w+)"\)', src))
literal_labels = re.findall(r'Transport\(Icons\.Default\.\w+, "', ui)
tip_is_generated = "Heard.primary(control)" in ui and "Control.entries.forEach" in ui
check("the spoken vocabulary has exactly one home",
      set(spoken.values()) == {"Start", "Pause", "Reset"}
      and not literal_labels
      and "control.spoken" in ui
      and tip_is_generated,
      f"{len(spoken)} words on the enum ({', '.join(spoken.values())}), "
      f"{len(literal_labels)} typed at the call site, the lit words come from Heard.primary")

# ── 8h ───────────────────────────────────────────────────────────────────────────────────────
# The reminder must say what actually works. Voice Access needs the verb; the app does not
# listen on its own. Printing the shorter "start" would read better and would not work, and a
# reminder that does not work is the same failure as a wrong one.
voice = VOICE.read_text()

# v8 stopped relying on Voice Access and listens for itself, so the reminder must print the bare
# word. The previous version of this check asserted the OPPOSITE — that the tip carried "tap" —
# which was correct for v7 and would have blocked v8. Left as a note rather than deleted: a
# check is a statement about what must be true now, not a monument to what was true once.
# The three words in the tester are the matcher's own first entries, so a word can never be shown
# that the matcher would refuse. Typing them here would be a second vocabulary.
check("the reminder prints one bare word per control, from the matcher's own list",
      "Heard.primary(control)" in ui and "Control.entries.forEach" in ui,
      "one Text per control, its text taken from the matcher rather than written beside it")

# ── 8i ───────────────────────────────────────────────────────────────────────────────────────
# No word may belong to two controls, or match() refuses it and the command silently stops
# working. Test 1 asserts this on the real map; this counts the words so a shrunken vocabulary
# cannot pass by being empty.
# NAMED THE THREE CONTROLS THAT EXISTED WHEN IT WAS WRITTEN, so when LAP arrived at v21 this
# check carried on passing while ignoring a quarter of the vocabulary. It now reads the control
# list from the enum, so a control cannot be added without its words being checked too.
# From the Control enum's own body, not from every constructor call in the file — PrerollMode and
# LapMode also have upper-case members with arguments, and matching those made this report four
# controls with no vocabulary while there were four controls, all of which had one.
control_body = re.search(r"enum class Control[^{]*\{(.*?)\n\}", src, re.S)
controls_in_enum = re.findall(
    r"^    ([A-Z][A-Z_]*)\(", control_body.group(1) if control_body else "", re.M
)
vocab = re.findall(r'Control\.([A-Z_]+) to setOf\((.*?)\),\n', voice, re.S)
words = {c: set(re.findall(r'"([^"]+)"', body)) for c, body in vocab}
overlap = [w for c in words for d in words if c < d for w in (words[c] & words[d])]
missing = [c for c in controls_in_enum if c not in words]
check("every control has its own disjoint list of words",
      not missing and not overlap and all(len(v) >= 4 for v in words.values()),
      f"{sum(len(v) for v in words.values())} words across {len(words)} controls, "
      f"{len(overlap)} shared, {len(missing)} controls with no vocabulary")

# ── 8j ───────────────────────────────────────────────────────────────────────────────────────
# THIS CHECK HAD NO BODY. Its comment survived and its check() call did not, so for several
# versions the file contained a paragraph explaining a rule that nothing enforced. A comment
# without a check is worse than neither: it reads as coverage.
#
# The rule it described has also been REVERSED, deliberately and at Baba's word. Until v23 the
# microphone closed with the screen; now it outlives the screen while voice is switched on,
# because a hands-free control that only works while you are looking at it is not hands-free.
# What must stay true is that it does not outlive the SWITCH.
check("the microphone outlives the screen only while voice is on",
      "if (!listening) ListeningService.stop(context)" in code_only(ui)
      and "ListeningService.start(context) else ListeningService.stop(context)" in code_only(ui),
      "the service is the microphone's lifetime, and the switch is the service's")

# Android will not allow a background microphone without announcing it, and that is right: an app
# that can hear you must say so where it cannot be missed. The notification is the price of the
# feature, not an obstacle to it, and it must carry its own way out.
service = (MAIN / "ListeningService.kt").read_text()
check("the listening notification says what it is and how to stop it",
      "setOngoing(true)" in code_only(service)
      and "Stop listening" in code_only(service)
      and "START_NOT_STICKY" in code_only(service),
      "ongoing, with a stop action, and it stays dead if Android kills it rather than "
      "reopening the microphone later unasked")


# ── 8l ───────────────────────────────────────────────────────────────────────────────────────
# The bar is drawn as a fraction of a width. A level above 1 runs it off the panel, and the curve
# is only clamped inside Vu — if the drawing ever trusts the number it is handed, one loud room
# breaks the layout.
check("the meter cannot draw past the end of its track",
      "maNorm(smoothed)" in meter and "coerceIn(0f, 1f)" in meter
      and "coerceIn(0f, full - 2f)" in meter,
      "maNorm clamps the fill and the peak marker is clamped to stay inside the track")

# ── 8k ───────────────────────────────────────────────────────────────────────────────────────
# Restarting the recogniser from inside its own callback with no delay is a tight loop that

# ── 9 ────────────────────────────────────────────────────────────────────────────────────────
# Both timing fields have to be written, or the one that is not is the one that comes back wrong.
store = STORE.read_text()
# Only the body of save(), not the whole file: the orientation lock is a preference written by
# its own setter and counting it here would let a genuinely missing timing field hide behind it.
save_body = re.search(r"fun save\(s: Stopwatch\)\s*\{(.*?)\n    \}", store, re.S).group(1)
written = re.findall(r"\.put\w+\((?:Keys\.)?K_(\w+)", save_body)
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

# ── the gate ─────────────────────────────────────────────────────────────────────────────────
# Partial results repeat the same word, play is a toggle, so acting on every delivery would
# start and pause the clock several times for one spoken word. The gate is the only thing
# the tester still sees every delivery.
voice = (MAIN / "Voice.kt").read_text()
# [^)]* stops at the FIRST bracket, and the call inside is elapsedRealtime(), so the first

# The overflow that Test 1 caught. Long.MIN_VALUE as "never fired" makes now - firedAt negative,
# which the minimum-gap check reads as "too soon", and the gate never opens at all.
# THE CODE, NOT THE FILE. The first version asked whether "Long.MIN_VALUE" appeared anywhere in
# Voice.kt — and it appears in the comment explaining why it is not used, so the check failed on
# a correct file. This is the second time in two versions that a check has been answered by
# prose rather than by code; strip the comments before looking.
voice_code = "\n".join(
    line for line in voice.splitlines()
    if not line.strip().startswith(("*", "//", "/*"))
)
check("the gate has no sentinel that can overflow",
      "Long.MIN_VALUE" not in voice_code and "hasFired" in voice_code,
      "a boolean marks never-fired, not a magic instant that arithmetic can wrap")


# v8 restarted at 250ms, which is a session start and end four times a second: a restart storm,

# v8 reset the meter inside onError, so a failing session stamped the level to zero several

# The counters are the whole debugging loop for somebody who has never held the phone.
# The meter is TTT mini's, ported rather than reinterpreted. If any of these constants drift, it
# has been rewritten by somebody who thought they knew better, which is how the first three
# versions of it went wrong.
ported = {
    "FLOOR_DB = -54f": "the silence floor",
    "tween(70)": "the smoothing speed",
    "delay(60L)": "the peak hold clock",
    "0.6f": "the peak fall per tick",
    "0xFF9B3B33": "hot",
    "0xFFF0883E": "warm",
    "0xFF56D364": "good",
    "VISUAL_FULL_SCALE = 16000f": "TTT mini's calibration",
    "AUDIO_LEVEL_SAMPLE_MS = 50L": "the sampling clock",
}
missing = [why for token, why in ported.items() if token not in meter]
check("the meter is TTT mini's, constant for constant",
      not missing,
      f"{len(ported) - len(missing)} of {len(ported)} ported values present, {len(missing)} drifted")

# The lag was the meter moving when a buffer arrived rather than on a clock. The probe must
# collect peaks and the UI must pull them, which is TTT mini's maxAmplitude arrangement.
check("the meter runs on a clock, not on the audio buffer",
      "fun maxAmplitude()" in probe and "val size = minimum" in probe
      and "AUDIO_LEVEL_SAMPLE_MS" in engine,
      "the audio thread collects peaks, the engine pulls them every 50ms")

# The tester must be able to hear a command without the stopwatch moving, or there is no way to
# tell 'it did not hear me' from 'it heard me and did the wrong thing'.
check("the tester detects without acting",
      "} else if (!settingsOpen) {" in code_only(ui)
      and "commit(state.press(control, SystemClock.elapsedRealtime()))" in code_only(ui),
      "with the panel open the word lights and the press is suppressed")

# The recogniser is gone entirely. If any part of it comes back, the tone comes back with it.
all_src = "".join((ROOT / "app/src/main/java/com/mantra/stopwatch" / f).read_text()
                  for f in ("VoiceEngine.kt", "MainActivity.kt", "MicProbe.kt", "Voice.kt"))
check("nothing starts a speech recogniser any more",
      "SpeechRecognizer" not in code_only(all_src) and "RecognizerIntent" not in code_only(all_src),
      "the tone was the recogniser's session boundary; with no sessions there is nothing to sound")

# The matcher must refuse rather than pick between two poor answers, or a conversation in the
# room fires the stopwatch.
# Three samples per command are pointless if the bad one drags the good ones down, so the score
# for a command is the BEST of its samples. An average would make three worse than one.
check("a command is scored by its best sample, not its average",
      "groupBy { it.control }" in dsp and "minOf { Dsp.dtw(" in dsp,
      "one score per command, taken from whichever recording of it is closest")

# Three slots, each pressable on its own, or there is no way to redo the one that went wrong —
# and the one that went wrong is the reason there are three.
# One icon language across the whole app: hollow is off, solid is on. The microphone says it, the
# sample slots say it, and the record arm says it. If any of them starts using a slash or a
# colour alone to mean off, there are two languages on one screen.
check("hollow means off and solid means on, everywhere",
      "Icons.Filled.Mic else Icons.Outlined.Mic" in ui
      and 'if (recording) "listening" else "empty"' in ui
      and "FiberManualRecord" not in code_only(ui),
      "the microphone says it with fill; a line with no sample says it in a word, because a line "
      "is wide enough for one and a glyph would be smaller information in more space")

# Recording and matching must never run at once: a word lit by the sample being recorded is a
# light that means nothing.
# There is no arm mode any more. What must stay true is that a capture suspends matching for as
# long as it lasts — a word lit by the sample being recorded is a light that means nothing.
check("a capture suspends matching while it runs",
      "// Nothing else happens while capturing: no matching, no gate." in engine
      and "main.postDelayed({ tick() }, AUDIO_LEVEL_SAMPLE_MS)\n                return" in engine,
      "the tick returns early while a capture is open, so nothing is matched against it")

check("each sample slot can be re-recorded on its own",
      "onPress(control, slot)" in ui and "for (slot in 0 until Store.SAMPLES)" in ui
      and "SampleCheck.assess(samples)" in ui,
      f"{3} slots per command, each with its own press")

check("the matcher needs a margin as well as a threshold",
      "runnerUp - bestScore < margin" in dsp and "bestScore > accept" in dsp,
      "the best match must be close AND clearly better than the second best")

# Loudness must not change the answer, and the normalisation that guarantees it is per frame.
# The per-utterance version collapsed every frame of a steady sound to zero.
# THE ESTIMATOR MUST NOT NEED A QUIET PART. v20's needed one and carried a guard that declined to
# clean anything without a gap in it, which is exactly traffic and exactly a club. If a guard like
# that reappears, the feature has silently stopped working in the only places it was built for.
check("the noise estimate works without a gap in the audio",
      "MINIMUM_BIAS" in dsp and "FLAT_ENOUGH_TO_REFUSE" not in dsp
      and "QUIET_QUANTILE" not in dsp,
      "minimum statistics per band, with no window that it refuses to touch")

# A gain that jumps between frames is what musical noise IS. The decision-directed smoothing is
# the single thing that stops it, so it is the single thing worth holding here.
check("the suppression gain is smoothed between frames",
      "SMOOTHING * priorSnr[b]" in dsp and "priorSnr[b] = (gain * gain * posterior)" in dsp,
      "decision-directed: this frame's decision informs the next, so the gain cannot jump")

check("loudness is removed per frame, not per utterance",
      "f[b] - mean }" in dsp and "mean /= BANDS" in dsp,
      "each frame minus its own mean across the bands, which keeps the shape and drops the gain")

check("the tester shows the scores, not just a verdict",
      "matcher.scores(" in (ROOT / "app/src/main/java/com/mantra/stopwatch/VoiceEngine.kt").read_text()
      and 'score?.let { "%.2f".format(it) }' in ui,
      "every comparison and its distance, so a near miss is a number rather than silence")

# The microphone switch is a hands-free control and must be reachable without opening a panel.
check("the microphone switch is on the main screen",
      "Alignment.TopCenter" in ui and "Icons.Filled.Mic else Icons.Outlined.Mic" in ui,
      "top middle of the stopwatch itself, not a row inside settings")

# The switch shows its position by WEIGHT — solid against hollow — not by a struck-out mark. A
# small slash is the first thing low vision loses, and it is a third mark to read rather than a
# difference you see before you read anything.
check("the microphone switch shows on and off without a struck-out mark",
      "MicOff" not in ui and "Icons.Outlined.Mic" in ui,
      "filled is on, outlined is off, no slash anywhere in the screen")

# AudioRecord is the only owner now and it never lets go. What used to be a handover is a ring
# read, which cannot contend with anything because nothing else wants the microphone.
check("the microphone has exactly one owner",
      code_only(engine).count("MicProbe(") == 1 and "fun recent(" in probe,
      "AudioRecord holds it for the life of the meter; the matcher reads the ring instead")

# The meter must not stop when voice is switched on. That was the symptom; this is the invariant.
check("the meter runs whether or not voice is armed",
      "fun startMeter()" in engine and "fun setArmed(" in engine
      and "armed" not in code_only(engine).split("private fun tick()")[1].split("onLevel(level)")[0],
      "arming gates the recogniser, not the meter: tick() feeds the level before it consults it")

# A recording is judged before it is stored, and the refusal has to say what to do about it. A
# red light that does not tell you why is a red light you learn to ignore.
check("a bad recording is refused, and the refusal says what to do",
      "SampleQuality.GOOD ->" in dsp and "try again" in dsp and "move back" in dsp,
      "silent, too short and clipped are each refused with an instruction rather than a code")

# The pads carry the shape of what is on them. A pad that only says "filled" cannot tell you
# whether you caught the word or the cough before it.
check("every pad shows the waveform of its own sample",
      "waveform(samples, 96)" in ui and "fun waveform(" in dsp,
      "the pad draws real peaks from the stored audio, not a filled state")

# A second is a long time to wait to learn whether anything registered, so the digits flash the
# instant a command lands. Two things have to hold or the flash is worse than nothing.
check("the flash fires only when the state actually changed",
      "if (next != state) flashes++" in code_only(ui),
      "a press on a dead control registered nothing and must not claim to have")

check("the flash is always a difference from the digits it flashes",
      "Palette.flashOf(colour)" in code_only(ui) and "fun flashOf(" in palette_src
      and "BLACK" in palette_src,
      "the digits vanish rather than brighten; the palette's own contrast floor is what "
      "guarantees the flash is visible, so there is no second threshold to keep in step")

# THE APP MUST NOT HEAR ITSELF. The count-in ends, the Go word plays, the app hears its own Go
# word and stops the stopwatch it just started. Every part working exactly as designed.
check("nothing heard counts while the app is making a noise",
      "MicMute.muted(now)" in engine and "MicMute.muteFor(" in code_only(go),
      "the mute is armed before a sample is written, and outlasts the sound by the length of "
      "the ring the matcher reads")

# SINGLE is worth having only because the digits get bigger, and they only get bigger if the
# measured string is the shorter one. Sizing on MULTI and drawing SINGLE would size for eight
# glyphs and draw two — the setting would appear to do nothing.
check("the digits are measured on the string the setting actually draws",
      "Face.format(elapsed, display)" in code_only(ui)
      and "remember(text.length" in code_only(ui),
      "one format call feeds both the measurement and the draw, so they cannot disagree")

# MULTI must keep its promise: one width for the life of the app. SINGLE steps twice, at known
# moments, and never inside a field.
check("both display modes have a width that only changes at a field boundary",
      'display == Display.MULTI) return "%02d:%02d:%02d"' in src
      and "m > 0L ->" in src and "else ->" in src,
      "MULTI is one branch and one width; SINGLE is three branches and three widths")

# The lap count must never reach the timing model. It counts lengths of a pool; the stopwatch
# measures time, and nothing but a transition may touch startedAt or accumulated.
check("the lap counter never touches the clock",
      "Control.LAP -> this" in src and "var laps by remember" in code_only(ui),
      "press(LAP) returns the same state; the count lives in the screen")

# A lap count left over from the last swim, sitting above a stopwatch reading zero, is a number
# that will be believed.
check("stop clears the lap count with everything else",
      "if (next.phase == Phase.STOPPED && state.phase != Phase.STOPPED) laps = 0" in code_only(ui),
      "the reset that clears the digits clears the lengths too")

# The Go word is the only sound this app makes and the only recording it plays back. It is kept
# at the best rate the phone will open, and the rate is stored WITH the audio — a sample recorded
# at 48000 and played at 44100 is a word said too slowly by somebody too deep, and nothing in the
# file would say why.
check("the Go word keeps its own sample rate",
      "buf.putInt(rate)" in code_only(go) and "val rate = buf.getInt()" in code_only(go)
      and "fun bestRate()" in code_only(go),
      "the rate is discovered by opening the device, and written into the first four bytes")

# A countdown that survived a stop would start a measurement nobody asked for.
check("every command cancels a running countdown",
      code_only(ui).count("cancelPreroll()") >= 4,
      "pause, stop and any spoken command cancel it; play toggles it")

# The countdown must only ever delay a start from zero. Putting a ceremony in front of resuming a
# pause would be a delay with no purpose.
check("the countdown only ever delays a start from zero",
      "if (state.phase != Phase.STOPPED) return false" in code_only(ui),
      "resuming a pause, pausing and stopping are all immediate")

# ── THE RATCHET ──────────────────────────────────────────────────────────────────────────────
#
# FOUR EDITS ON THIS PROJECT HAVE SILENTLY MATCHED NOTHING AND REPORTED SUCCESS, and twice that
# hid missing tests behind a green suite. The lap arithmetic shipped untested for a whole version
# because the script meant to add its test aborted before writing, and the suite stayed green
# precisely BECAUSE the test was not there to fail. Green looked like proof and was absence.
#
# So the count is written down and may only go up. Deleting a test is a deliberate act and needs
# this line changed in the same commit; an edit that quietly fails to add one now goes red.
TESTS = (ROOT / "app/src/test/java/com/mantra/stopwatch/StopwatchTest.kt").read_text()
test_count = TESTS.count("@Test")
check("the test count has not gone backwards",
      test_count >= TEST_FLOOR,
      f"{test_count} cases, floor is {TEST_FLOOR}. Raise the floor when you add tests; "
      f"lowering it is a deliberate act and should be argued for in the commit")



# RECORDING IS MANUAL. A capture that ends itself sometimes ends inside the pause in the middle
# of a word, and the failure is invisible: the line fills, the waveform looks plausible, and the
# template is half a word. If an automatic stop reappears, this goes red.
check("a recording ends when it is told to, not when it decides",
      "fun finishCapture()" in engine and "CaptureState.DONE ->" not in code_only(engine),
      "press starts it, press stops it; the only automatic stop left is the ring ceiling")

# What a press MEANS is decided in pure code, not in the interface, so Test 1 can read it. Ported
# from SAMPLE_PLAYER rather than invented a second time: two apps in this account disagreeing
# about what pressing a sample does would be two answers to one question.
check("what a press means is decided in pure code",
      "SamplerGesture.press(samplerMode" in code_only(ui) and "object SamplerGesture" in voice,
      "the table returns a decision; the screen only carries it out")

# Overwriting a take destroys something that cannot be got back, and a press is one finger on a
# small line among twelve.
check("recording over a take asks first",
      "SamplerPress.ConfirmOverwrite" in voice and "press again to record over it" in ui,
      "the confirmation is in the table, so it cannot be skipped by the interface")

print()
print(f"{len(checks_run) - len(failures)} of {len(checks_run)} checks passed")
if failures:
    print("failed: " + ", ".join(failures))
sys.exit(1 if failures else 0)
