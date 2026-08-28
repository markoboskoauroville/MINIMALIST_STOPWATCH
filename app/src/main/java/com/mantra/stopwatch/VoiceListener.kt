package com.mantra.stopwatch

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * THE MICROPHONE, AND EVERYTHING THAT CAN GO WRONG WITH IT.
 *
 * The decision about what a heard string MEANS is not here — that is Heard.match, which is pure
 * and has thirteen cases in Test 1. This file does the part no test can reach: holding a
 * recogniser open, restarting it when it gives up, and reporting a level.
 *
 * WHY IT RESTARTS ITSELF. Android's SpeechRecognizer is built for one utterance. It listens,
 * decides, calls back and stops. There is no "keep listening" mode, so continuous recognition
 * means starting it again every time it ends, which it does constantly — ERROR_NO_MATCH and
 * ERROR_SPEECH_TIMEOUT arrive every few seconds in a quiet room. THIS IS THE PART THAT GOES
 * WRONG: restarting from inside the callback with no delay is a tight loop that heats the phone
 * and never hears anything. Every restart goes through the handler with a gap.
 *
 * WHY THE VU COMES FROM HERE AND NOT FROM AudioRecord. TTT mini owns the microphone and reads
 * sample peaks. This app hands the microphone to the recogniser, and two readers cannot both
 * have it — opening AudioRecord beside a live recogniser fails or starves one of them.
 * onRmsChanged is the level the recogniser is willing to give back, so that is the source.
 *
 * WHAT IT DOES NOT DO: run in the background. There is no service and no foreground
 * notification. The microphone is open only while the stopwatch is on screen and the switch is
 * on, and it is closed in onStop. An always-listening stopwatch is a different application with
 * a different set of promises.
 */
class VoiceListener(
    private val context: Context,
    private val onLevel: (Float) -> Unit,
    /** Every delivery, for the tester. Says what was heard, whether or not it acts. */
    private val onHeard: (String, Control?) -> Unit,
    /** At most once per utterance. This is the one that moves the stopwatch. */
    private val onCommand: (Control) -> Unit,
    private val onState: (String) -> Unit,
    /** The counters, so a person with the phone can say what this code cannot see. */
    private val onDiagnostics: (Diagnostics) -> Unit = {},
) {

    private val main = Handler(Looper.getMainLooper())
    private val vu = Vu()
    private val gate = CommandGate()
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false

    // THE NUMBERS THE TESTER PRINTS. Every one of them exists because a person holding the phone
    // can see something this code cannot, and without them the only report possible is "it does
    // not work". A session count that climbs by four a second says restart storm. Rms callbacks
    // stuck at zero while sessions climb says the recogniser is dying before it ever opens the
    // microphone. Those two numbers separate the two faults without anybody guessing.
    private var sessions = 0
    private var rmsCallbacks = 0
    private var lastError = ""
    private var offlineFailures = 0
    private var offline = true

    private fun report() = onDiagnostics(
        Diagnostics(
            sessions = sessions,
            rmsCallbacks = rmsCallbacks,
            lastError = lastError,
            offline = offline,
        )
    )

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // BUG 1, AND IT IS ALMOST CERTAINLY WHY NOTHING WORKED.
    //
    // v8 set EXTRA_PREFER_OFFLINE unconditionally. On a phone with no downloaded offline model
    // for the locale, that does not fall back — the recogniser fails the session immediately,
    // every time. Paired with a 250ms restart that is roughly four dead sessions a second,
    // forever: nothing is ever recognised, and on the OEM builds that play a tone at the start
    // and end of a session, THAT is the on-off sound Baba is hearing. It is not the app doing
    // something clever, it is the recogniser being started and killed four times a second.
    //
    // Offline is still tried first, because a one-word command should not need the network. But
    // it is now a PREFERENCE THAT GIVES UP: after OFFLINE_ATTEMPTS failures the flag comes off
    // and the recogniser is allowed to use whatever it has. Falling back late is a slower app;
    // never falling back is a broken one.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    private fun intent(offline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Ask the session to stay open through ordinary pauses in speech. Not every
            // implementation honours these; the ones that do restart far less often, which means
            // fewer tones and a meter that is actually being fed.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6_000L)
        }

    fun start() {
        if (wanted) return
        wanted = true
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            // Said plainly rather than failing quietly. A microphone switch that turns on and
            // then does nothing is worse than one that says it cannot.
            onState("no recogniser on this phone")
            wanted = false
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
        onState("listening")
        listen()
    }

    fun stop() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        recognizer?.apply {
            stopListening()
            destroy()
        }
        recognizer = null
        onLevel(vu.reset())
        onState("off")
    }

    private fun listen() {
        if (!wanted) return
        // A fresh listen is a fresh utterance, so the gate reopens here and nowhere else.
        gate.newUtterance()
        sessions++
        report()
        runCatching { recognizer?.startListening(intent(offline)) }
            .onFailure { onState("could not start") }
    }

    /** Never immediate. See the note above about the tight loop. */
    private fun restart(delayMs: Long) {
        if (!wanted) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listen() }, delayMs)
    }

    private val listener = object : RecognitionListener {

        override fun onRmsChanged(rmsdB: Float) {
            // BUG 2. This callback only arrives between onReadyForSpeech and onEndOfSpeech. If
            // every session dies on arrival — see BUG 1 — it is never called at all and the bar
            // never moves, which is exactly what Baba saw. The counter below is how anybody can
            // tell "the meter is broken" from "the meter is never being given anything".
            rmsCallbacks++
            onLevel(vu.fromRms(rmsdB))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Acted on as soon as a word is recognisable rather than waiting for the recogniser
            // to decide the sentence is finished. A stopwatch command that lands half a second
            // late has already missed the thing being timed.
            deliver(partialResults, final = false)
        }

        override fun onResults(results: Bundle?) {
            deliver(results, final = true)
            restart(RESTART_MS)
        }

        override fun onError(error: Int) {
            // BUG 3. v8 reset the meter here, and with a session failing every 250ms that stamped
            // the level back to zero four times a second. Even a working rms feed could not have
            // shown through it. The meter now falls on its own release curve and is only reset
            // when the microphone is actually let go.
            lastError = name(error)
            report()
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onState("microphone refused")
                    wanted = false
                    onLevel(vu.reset())
                    return
                }
                // The two that mean "this configuration cannot work". Enough of them and the
                // offline preference is dropped rather than retried for ever.
                SpeechRecognizer.ERROR_SERVER,
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (offline && ++offlineFailures >= OFFLINE_ATTEMPTS) {
                        offline = false
                        onState("offline model missing, using the online recogniser")
                    }
                    restart(BACKOFF_MS)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(BUSY_MS)
                // A quiet room produces these for ever and neither is a fault.
                else -> restart(RESTART_MS)
            }
        }

        /**
         * The code as a word. An integer in a diagnostics panel is a thing to be looked up; a
         * name is a thing that can be read out over a message, which is the whole point of the
         * panel existing.
         */
        private fun name(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NETWORK -> "no network"
            SpeechRecognizer.ERROR_AUDIO -> "audio"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "silence"
            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no permission"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language unavailable"
            else -> "error $error"
        }

        private fun deliver(bundle: Bundle?, final: Boolean) {
            val texts = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (texts.isEmpty()) return
            // The recogniser offers several candidates in confidence order. The first one that
            // is a command wins, because "start" arriving as the second guess is still the
            // person having said start.
            val hit = texts.firstNotNullOfOrNull { t -> Heard.match(t)?.let { t to it } }
            if (hit != null) {
                // The tester sees every delivery. The stopwatch sees at most one per utterance:
                // partial results repeat the same word as the recogniser firms it up, and play
                // is a toggle, so acting on each of them would start and pause the clock several
                // times for one spoken word.
                onHeard(hit.first, hit.second)
                if (gate.allow(android.os.SystemClock.elapsedRealtime())) onCommand(hit.second)
            } else if (final) {
                onHeard(texts.first(), null)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = onState("listening")
        override fun onBeginningOfSpeech() = Unit
        override fun onEndOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        // BUG 4. 250ms was a restart storm. Every session start and end is a tone on the OEM
        // builds that play one, and many implementations rate-limit and answer BUSY, so a short
        // gap made the app both noisier and less likely to hear anything. Slower is better here:
        // a spoken word still lands within one gap, and the microphone stays open across it.
        const val RESTART_MS = 900L
        const val BUSY_MS = 2_000L
        const val BACKOFF_MS = 3_000L
        const val OFFLINE_ATTEMPTS = 3
    }
}
