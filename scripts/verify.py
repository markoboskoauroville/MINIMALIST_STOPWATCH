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


def check(name, ok, detail):
    print(f"{'pass' if ok else 'FAIL'}  {name}: {detail}")
    if not ok:
        failures.append(name)


# ── 1 ────────────────────────────────────────────────────────────────────────────────────────
# The timing logic must not import anything from Android. The moment it does, Test 1 stops being
# a test of the mechanism and becomes a test of an emulator that is not present.
src = LOGIC.read_text()
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
enum_body = re.search(r"enum class Phase\s*\{([^}]*)\}", src)
phases = [p.strip() for p in enum_body.group(1).split(",") if p.strip()] if enum_body else []
cans = re.findall(r"fun (can\w+)\(", src)
check("every button has an availability rule for every phase",
      sorted(cans) == ["canPause", "canPlay", "canStop"] and len(phases) == 3,
      f"{len(set(cans))} rules x {len(phases)} phases = {len(set(cans)) * len(phases)} cases: {', '.join(phases)}")

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
      "if (r <= 0L) 100L else r" in src,
      "untilNextTenth floors at 1ms and is capped at 100ms by construction")

# ── 7 ────────────────────────────────────────────────────────────────────────────────────────
# Tap-anywhere is gone and its absence is a decision. If a clickable ever reappears on the
# background this goes red, because that is the stray touch that destroys a measurement.
ui = UI.read_text()
background_click = re.search(r"\.background\(BACKGROUND\)[\s\S]{0,200}?\.clickable", ui)
clickables = len(re.findall(r"\.clickable\(", ui))
check("nothing on the background is tappable",
      background_click is None and clickables == 0,
      f"{clickables} clickable modifiers in the screen, buttons carry their own onClick")

# ── 8 ────────────────────────────────────────────────────────────────────────────────────────
# Never hide a control that is temporarily unavailable. A disabled button is dimmed; a button
# removed from the tree moves everything beside it.
hidden = re.search(r"if\s*\([^)]*can(Play|Pause|Stop)\(\)\s*\)\s*\{?\s*Circle", ui)
check("no button is hidden when it cannot act",
      hidden is None and "disabledContentColor" in ui,
      "availability reaches the button as enabled=, and a disabled tint exists")

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
print(f"{11 - len(failures)} of 11 checks passed")
if failures:
    print("failed: " + ", ".join(failures))
sys.exit(1 if failures else 0)
