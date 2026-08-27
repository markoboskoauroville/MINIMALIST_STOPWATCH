# DELIVERY RECORD — Minimalist Stopwatch v2 — 27.8.2026

The shape is fixed so that two releases can be compared. The **NOT TESTED** block is the most
valuable part of this document and it is long, because this app has never been on a phone.

---

    ARTEFACT   2-stopwatch-v2.apk, built by GitHub Actions from the commit tagged v2
    VERSION    new: 2   previous: 1, still downloadable at the releases page
    SIGNED BY  the permanent repository key, SHA-256 D9:3E:6B:00:...:D9:62

## The gates

    G1  PROVENANCE   pass   clean tree (0 uncommitted, 0 untracked), built on the runner and
                            not on a desk, every action pinned by commit SHA, Gradle
                            distribution pinned by sha256. The three numbers agree:
                            gradle.properties, versionCode, versionName and the tag are all 2

    G2  SECRETS      pass   history scanned, commits examined, 0 key-shaped strings
                            artefact scanned as a binary, 0 key-shaped strings
                            staged diff scanned before every push, 0

    G3  ANALYSIS     pass   verify.py: 11 of 11 structural checks, each printing what it
                            examined. Android Lint fatal on release, warningsAsErrors on,
                            Kotlin allWarningsAsErrors on: 0 errors, 0 warnings.
                            Test 1: 30 cases, 0 failures, 0 errors
                            Mutation sweep: 25 mutations, 25 caught, 0 survived

    G4  DEAD CODE    pass   4 source files, 474 dependencies resolved. No -keep rules at all,
                            which is the interesting half of R8's report: nothing is being
                            held alive by a note from us saying spare this. The UNWIRED sweep
                            is check 4 of verify.py: 3 availability rules across 3 phases,
                            all 9 cases reachable from the screen

    G5  DEAD LOOPS   pass   3 loops examined, 0 written as while(true), 1 wait examined.
                            The only wait is the tick, bounded to 1..100ms by
                            Face.untilNextTenth, which Test 1 asserts and the sweep breaks.
                            There is no network, no file wait and no IPC in this app

    G6  STRESS       NOT RUN, see below

    G7  BUDGETS      baseline set, nothing to compare against yet
                            APK 794,915 bytes at v1
                            dependencies resolved: 474
                            cold start, frame time, memory, battery: not measured, no device

    G8  UPGRADE      NOT RUN, see below. v1 and v2 are both downloadable and are signed with
                            the same key, so the upgrade is testable by hand for the first
                            time with this release

    G9  RECORD       this document

## NOT TESTED

**Everything requiring a phone.** This is the honest headline: the app has been compiled,
statically checked and had its timing model attacked from every angle a JVM can reach, and it
has never been looked at.

    TEST 2, the real thing      never run. Nothing has pressed the buttons. The wiring from
                                the three circles to the three model transitions is proven only
                                by reading, and Test 1 cannot see it
    TEST 4 and G8, the upgrade  never run. v1 installed, used, left running, then v2 over the
                                top: not done. Both artefacts exist and share a signing key,
                                so this is the first release where it CAN be done
    G6, stress                  no soak, no monkey, no sabotage list. An hour of running with
                                the heap watched has not happened, and neither has
                                adb shell monkey
    THE APPEARANCE              the 55% grey was chosen from a rendered mock, not from a
                                screen. So were both layouts. The claim that the digits fill
                                the width, that the transport column clears the lock button in
                                landscape, and that 55% reads as a control rather than as a
                                second white thing are all UNVERIFIED ON GLASS
    THE MONOSPACE FACE          FontFamily.Monospace resolves to whatever the device ships. The
                                claim that a 1 cannot be read as a 7 has not been checked on a
                                real device, and it is the one typographic requirement in the
                                brief
    REBOOT                      the reboot logic is exhaustively tested as a pure function with
                                hand-made inputs. It has never met an actual reboot
    PROCESS DEATH               same. commit() rather than apply() is the right mechanism and
                                the reasoning is sound; adb shell am kill has not been run
    ROTATION                    the activity declares configChanges and does not recreate. Not
                                observed
    FONT SCALE                  the digits are measured in sp, so a system font scale multiplies
                                them and the binary search accounts for it. Never tested at a
                                large accessibility font scale
    TABLETS, FOLDABLES          not considered, not tested

## Known and deliberate

    the size steps down once at one hour, when MM:SS.d becomes H:MM:SS.d, and once more past
      ten hours. Documented rather than prevented

    the boot-marker reboot detector has one false positive: moving the phone's clock by more
      than a minute during a running measurement reads as a reboot and returns zeros. Rare,
      deliberate, and it fails in the safe direction

    the system bars are left visible on black rather than hidden. One line to change

    the signing keystore exists ONLY as a repository secret. There is no second copy. If it is
      lost the app can never be upgraded in place again

## Rollout

One user, one phone. Stage 0 is the whole rollout. "Halt" means installing the previous APK,
which is why two are kept.

**Before this is trusted for a real measurement:** install it, start it, put the phone in a
pocket for ten minutes, and check it shows ten more minutes rather than ten fewer. Then pause it,
wait, play, and check the gap was not counted. Those two take a quarter of an hour and they are
worth more than every gate above, because they are Test 2 and nothing here is.
