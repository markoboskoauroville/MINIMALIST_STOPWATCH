package com.mantra.stopwatch

import android.content.Context
import android.os.SystemClock

/**
 * THE ONE ENGINE, AND THE ONE PLACE ANYTHING CAN REACH IT.
 *
 * The engine used to be created by the screen, which meant it died with the screen. It now
 * belongs to the process and its lifetime is decided by ListeningService.
 *
 * The screen SUBSCRIBES rather than owns. It sets its callbacks when it appears and clears them
 * when it leaves, and in between the engine carries on without it — which is the whole point,
 * because a hands-free control that only works while you are looking at it is not hands-free.
 *
 * A singleton is the honest shape here rather than a shortcut: there is one microphone, so there
 * is one engine, and anything that pretends otherwise is pretending about hardware.
 */
object VoiceHub {

    private var engine: VoiceEngine? = null

    /** What the screen fills in while it is present, and clears when it is not. */
    var onLevel: ((Float) -> Unit)? = null
    var onHeard: ((Control?, List<Pair<Control, Double>>) -> Unit)? = null
    var onState: ((String) -> Unit)? = null

    /**
     * The action a recognised command performs. Set by the screen when it is showing, and by
     * nothing when it is not — so a command spoken with the app in the background still has to
     * reach the stopwatch through something that knows what the stopwatch is.
     */
    var onCommand: ((Control) -> Unit)? = null

    val running: Boolean get() = engine != null

    fun start(context: Context) {
        if (engine != null) return
        engine = VoiceEngine(
            onLevel = { level -> onLevel?.invoke(level) },
            onHeard = { hit, scores -> onHeard?.invoke(hit, scores) },
            onCommand = { control -> onCommand?.invoke(control) },
            onState = { state -> onState?.invoke(state) },
        ).also { it.startMeter() }
        reloadTemplates(context)
    }

    fun stop() {
        engine?.stopMeter()
        engine = null
    }

    fun setArmed(armed: Boolean) {
        engine?.setArmed(armed)
    }

    fun recent(ms: Int): ShortArray = engine?.recent(ms) ?: ShortArray(0)

    /** The second press. The hub forwards it; the engine decides what was recorded. */
    fun finishCapture() {
        engine?.finishCapture()
    }

    fun startCapture(done: (ShortArray?) -> Unit) {
        val e = engine
        if (e == null) {
            done(null)
            return
        }
        e.startCapture(done)
    }

    /**
     * Featured once, here, rather than in the screen. The screen used to do it and would have
     * lost the templates every time it went away, which would have left the engine listening for
     * nothing at exactly the moment it mattered most.
     */
    fun reloadTemplates(context: Context) {
        val store = Store(context)
        // The names travel with the templates, because both are read from the store and both must
        // survive the screen going away. Loading them anywhere else would mean a renamed command
        // stopped answering the moment the phone went in a pocket.
        engine?.setNames(store.names)
        engine?.setTemplates(
            Control.entries.flatMap { c ->
                (0 until Store.SAMPLES).mapNotNull { slot ->
                    store.loadSample(c, slot)?.let { Template(c, Dsp.features(it)) }
                }
            }
        )
    }

    /** For the tester, which wants to know whether a capture is in progress. */
    fun elapsedNow(): Long = SystemClock.elapsedRealtime()
}
