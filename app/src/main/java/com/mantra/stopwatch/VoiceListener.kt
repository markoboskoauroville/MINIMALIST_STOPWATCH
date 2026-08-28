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
) {

    private val main = Handler(Looper.getMainLooper())
    private val vu = Vu()
    private val gate = CommandGate()
    private var recognizer: SpeechRecognizer? = null
    private var wanted = false

    private val intent: Intent
        get() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // On-device where it exists: no network round trip, no audio leaving the phone, and
            // it answers fast enough that a one-word command feels like a button.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
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
        runCatching { recognizer?.startListening(intent) }
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
            onLevel(vu.reset())
            // A quiet room produces NO_MATCH and SPEECH_TIMEOUT forever, and neither is a fault.
            // The two that are worth saying out loud are a refused microphone and a busy one.
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onState("microphone refused")
                    wanted = false
                    return
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restart(BUSY_MS)
                else -> restart(RESTART_MS)
            }
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
        const val RESTART_MS = 250L
        const val BUSY_MS = 1000L
    }
}
