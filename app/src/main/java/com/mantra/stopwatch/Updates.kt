package com.mantra.stopwatch

/**
 * WHETHER THERE IS A NEWER BUILD, decided in pure code so it can be got wrong on a desk rather
 * than on a phone.
 *
 * The comparison looks trivial and is the part that goes wrong. A tag is a string; a version is a
 * number; "v9" is newer than "v35" if anybody compares them as text, and that fault appears
 * exactly once — at version 10 — and then never announces itself again.
 */
sealed interface UpdateState {
    /** Nothing asked yet. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** Asked, answered, and this is the newest there is. */
    data class UpToDate(val version: Int) : UpdateState

    data class Available(val version: Int, val url: String) : UpdateState

    /** The check failed. The reason is shown rather than a shrug. */
    data class Failed(val why: String) : UpdateState
}

object Updates {

    const val LATEST_RELEASE =
        "https://api.github.com/repos/markoboskoauroville/MINIMALIST_STOPWATCH/releases/latest"

    /**
     * A tag into a number, or null.
     *
     * Tags in this repository are `v35`. Anything else is not a version this app understands, and
     * returning null rather than guessing is the difference between "I could not tell" and a
     * confident wrong answer about whether somebody is out of date.
     */
    fun versionOf(tag: String?): Int? {
        val t = tag?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (t.isEmpty() || t.any { !it.isDigit() }) return null
        return t.toIntOrNull()
    }

    /**
     * Compared as NUMBERS. This is the whole reason this file exists: as text, "9" sorts after
     * "35", so a string comparison would have told everybody they were up to date from version
     * ten onwards and never mentioned it again.
     */
    fun compare(current: Int, tag: String?, url: String?): UpdateState {
        val latest = versionOf(tag) ?: return UpdateState.Failed("no version in the release")
        return when {
            latest > current && url != null -> UpdateState.Available(latest, url)
            // Newer than the newest release happens on a build made from an unreleased commit.
            // Saying "up to date" is the honest answer; offering a downgrade is not.
            else -> UpdateState.UpToDate(maxOf(current, latest))
        }
    }

    /** What the settings header shows, in one short line. */
    fun describe(state: UpdateState, current: Int): String = when (state) {
        UpdateState.Idle -> "v$current"
        UpdateState.Checking -> "checking"
        is UpdateState.UpToDate -> "v$current  newest"
        is UpdateState.Available -> "v${state.version} ready \u2193"
        is UpdateState.Failed -> "v$current  " + state.why
    }
}
