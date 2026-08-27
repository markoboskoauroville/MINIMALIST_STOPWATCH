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

`Icons.Default.PlayArrow`, `Pause`, `Stop`, `ScreenLockRotation`, `ScreenRotation`, all from
`androidx.compose.material:material-icons-extended`, the same artefact TTT mini uses. R8 strips
the rest; the release APK is 795 KB with the whole library on the classpath.

## Two things about this codebase that will surprise a fresh reader

**`Stopwatch.kt` imports nothing from `android.*` and must not start.** `verify.py` fails if it
does. The whole timing model is testable on a plain JVM in eleven seconds, which is what makes
the 25-mutation sweep affordable.

**Nothing on the background is tappable, and that is enforced.** Tap-anywhere was removed
deliberately on 27.8.2026 and `verify.py` check 7 goes red if a `clickable` returns to it.

## The local test harness

`scripts/sabotage.py` through Gradle takes about an hour. Compiling the two Kotlin files
directly with `kotlinc` against JUnit takes eleven seconds a run, which brings the sweep to about
four minutes:

    SABOTAGE_RUN=/path/to/runtest.sh python3 scripts/sabotage.py

Worth rebuilding that harness at the start of any session that touches the timing model.
