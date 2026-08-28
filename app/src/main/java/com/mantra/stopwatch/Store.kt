package com.mantra.stopwatch

import android.content.Context
import android.os.SystemClock

/**
 * EVERYTHING THAT HAS TO SURVIVE ROTATION, BACKGROUNDING, PROCESS DEATH AND REBOOT.
 *
 * There is no ViewModel and no saved instance state, on purpose. Rotation, backgrounding and a
 * process kill are three different lifecycles with three different survival mechanisms, and
 * carrying the state in all three is three places to disagree. One file, written on every
 * transition, is one place, and it is the only one that survives all three.
 *
 * WHAT IS STORED, AND WHY EACH FIELD IS HERE:
 *
 *   phase, startedAt, accumulated   the state machine itself
 *   lastSeen                        the highest elapsedRealtime this app has ever observed.
 *                                   A monotonic clock cannot go backwards, so now < lastSeen
 *                                   is PROOF of a reboot rather than a suspicion
 *   bootMarker                      wall clock minus elapsedRealtime, which is roughly the
 *                                   instant the device booted. Catches the reboot that the
 *                                   backwards check misses, where the device has since been up
 *                                   longer than it was before
 *   locked                          the orientation lock, which is a preference and not part
 *                                   of the measurement
 *
 * WHY BOTH DETECTORS. Each has a hole and the other covers it. Neither is free of false
 * positives on its own and together they fail towards zeros, which is the safe direction: a
 * stopwatch that lost its measurement is annoying, one that shows a number short by three hours
 * is a lie.
 */
class Store(context: Context) {

    private val p = context.applicationContext
        .getSharedPreferences("stopwatch", Context.MODE_PRIVATE)

    /** Wall clock minus monotonic clock: approximately the instant this device booted. */
    fun bootMarker(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * Which way up the app sits. v5 replaced the old lock/unlock flag with this, and the old key
     * is deliberately NOT read: "was locked" carries no information about which orientation the
     * person would now choose, so guessing from it would be inventing an answer. Everybody
     * starts in portrait once, and one press fixes it for good.
     */
    var orientation: Orientation
        get() = if (p.getBoolean(K_LANDSCAPE, false)) Orientation.LANDSCAPE else Orientation.PORTRAIT
        set(v) = p.edit().putBoolean(K_LANDSCAPE, v == Orientation.LANDSCAPE).apply()

    /**
     * The digit colour, always run through Palette.sanitise on the way out. A value that is no
     * longer in the grid — written by a later version, or left behind by a swatch that was
     * removed — comes back as white rather than as something invisible on black.
     */
    var colour: Long
        get() = Palette.sanitise(p.getLong(K_COLOUR, Palette.DEFAULT))
        set(v) = p.edit().putLong(K_COLOUR, v).apply()

    /**
     * Whether the microphone is wanted. Default OFF, and it stays off until it is switched on
     * from the settings panel. A stopwatch that starts listening because it was installed is not
     * a stopwatch anybody asked for.
     */
    var listening: Boolean
        get() = p.getBoolean(K_LISTENING, false)
        set(v) = p.edit().putBoolean(K_LISTENING, v).apply()

    var weight: Weight
        get() = if (p.getBoolean(K_BOLD, false)) Weight.BOLD else Weight.NORMAL
        set(v) = p.edit().putBoolean(K_BOLD, v == Weight.BOLD).apply()

    /**
     * commit() rather than apply() is deliberate and it is the whole point of this class.
     * apply() writes on a background thread, and the moment this is called from onStop the
     * process may be killed before that thread runs. A stopwatch that comes back at the wrong
     * value because the write lost a race is exactly the bug the spec calls the one worth
     * testing hardest. The write is a handful of primitives and happens on a state change or
     * once every ten seconds, never per frame.
     */
    fun save(s: Stopwatch) {
        p.edit()
            .putString(K_PHASE, s.phase.name)
            .putLong(K_STARTED_AT, s.startedAt)
            .putLong(K_ACCUMULATED, s.accumulated)
            .putLong(K_LAST_SEEN, SystemClock.elapsedRealtime())
            .putLong(K_BOOT_MARKER, bootMarker())
            .commit()
    }

    /**
     * Reads the saved fields and hands them to Stopwatch.restore, which owns the decision about
     * what a reboot costs each phase. The parsing lives here; the rule lives in the pure file
     * where Test 1 can attack it.
     */
    fun load(): Stopwatch {
        val phase = try {
            Phase.valueOf(p.getString(K_PHASE, Phase.STOPPED.name) ?: Phase.STOPPED.name)
        } catch (e: IllegalArgumentException) {
            // A phase name this version does not know is not a crash. It is a fresh stopwatch.
            Phase.STOPPED
        }
        return Stopwatch.restore(
            phase = phase,
            startedAt = p.getLong(K_STARTED_AT, 0L),
            accumulated = p.getLong(K_ACCUMULATED, 0L),
            savedBootMarker = p.getLong(K_BOOT_MARKER, bootMarker()),
            bootMarkerNow = bootMarker(),
            lastSeen = p.getLong(K_LAST_SEEN, 0L),
            now = SystemClock.elapsedRealtime(),
        )
    }

    private companion object {
        const val K_PHASE = "phase"
        const val K_STARTED_AT = "startedAt"
        const val K_ACCUMULATED = "accumulated"
        const val K_LAST_SEEN = "lastSeen"
        const val K_BOOT_MARKER = "bootMarker"
        const val K_LANDSCAPE = "landscape"
        const val K_COLOUR = "colour"
        const val K_BOLD = "bold"
        const val K_LISTENING = "listening"
    }
}
