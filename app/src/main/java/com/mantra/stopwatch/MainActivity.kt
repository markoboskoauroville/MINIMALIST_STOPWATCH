package com.mantra.stopwatch

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import java.time.LocalTime
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ─────────────────────────────────────────────────────────────────────────────────────────────
// THE COLOURS.
//
// The digits are the only bright thing on the screen and everything else is measured DOWN from
// them. v3 took the glyphs down again on Baba's instruction after v2 was on the phone: 55% was
// still reading as a second bright thing beside the numbers. 40%, and not further, because with
// the circles gone the glyph carries the whole control on its own.
//
//   40%   the glyph. Plainly a control, plainly not the digits
//   16%   the glyph when the button can do nothing. Dimmer still, as asked, and still there:
//         never hidden, because a control that disappears moves the layout
//
// THE CIRCLES ARE GONE. They were an outline at 18% marking where to press, and on glass they
// read as three more shapes on a screen whose whole design is what is absent. THE TOUCH TARGET
// IS UNCHANGED — IconButton still occupies the full size and still takes a press anywhere
// inside it. What was removed is the drawing, not the hot zone.
// ─────────────────────────────────────────────────────────────────────────────────────────────

// v4 needed a THIRD tone. Play is dim while the clock runs AND pressing it still pauses, so dim
// on its own would have meant two different things on the same screen: "not the obvious next
// move" and "does nothing". v5 added a FOURTH at the top, white, on the single cell where the
// clock is idle and play is what you want. The mapping from phase to tone lives in Stopwatch.kt
// where Test 1 walks all nine cases of it; nothing here decides which cell is which.
private val GLYPH_PRIMARY = Color.White        // 100% PRIMARY: play, while the clock is idle
private val GLYPH = Color(0xFF666666)          // 40%  HIGHLIGHT: what the next press would do
private val GLYPH_SECOND = Color(0xFF3D3D3D)   // 24%  SECONDARY: live, but not the suggestion
private val GLYPH_OFF = Color(0xFF1F1F1F)      // 12%  DEAD: pressing it does nothing
private val BACKGROUND = Color.Black
private val PANEL_CHOSEN = Color(0xFF1F1F1F)
private val PANEL_IDLE = Color(0xFF0D0D0D)

/** Red means recording, everywhere. It is the one colour in this app that is not a grey ramp. */
private val RECORD_RED = Color(0xFF9B3B33)

/**
 * A seventh of a second. Long enough to see out of the corner of an eye, short enough that it
 * reads as an acknowledgement rather than as the digits having changed colour.
 */
private const val FLASH_MS = 140L

/** Forty values is two seconds at the level tick; this is room for four times that. */
private const val LIVE_MAX = 160

private val EDGE = 12.dp
private val LOCK_ZONE = 56.dp

class MainActivity : ComponentActivity() {

    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        enableEdgeToEdge()

        // FULL SCREEN. The status bar and the navigation bar are both hidden, so the app has the
        // whole panel and nothing above the digits but black.
        //
        // v1 decided the opposite and said so in NEXT_DEFAULTS: leaving the bars visible kept
        // the phone's own clock and battery on screen during a measurement, and the digits are
        // limited by WIDTH rather than height so hiding them buys nothing the digits can use.
        // That reasoning was about the digits. It was wrong about the screen: what the bars
        // actually cost is that a black screen with six enormous numbers on it stops being a
        // black screen with six enormous numbers on it, which is the entire design.
        //
        // SHOW_TRANSIENT_BARS_BY_SWIPE rather than sticky-hidden: a swipe from the edge brings
        // the bars back for a few seconds and then they go again. The phone is never taken away
        // from the person, it is just not on top of the stopwatch.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        setContent { Screen(store, this) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Screen(store: Store, activity: ComponentActivity) {

    var state by remember { mutableStateOf(store.load()) }
    var orientation by remember { mutableStateOf(store.orientation) }
    var colour by remember { mutableLongStateOf(store.colour) }
    var weight by remember { mutableStateOf(store.weight) }
    var display by remember { mutableStateOf(store.display) }
    var lapOn by remember { mutableStateOf(store.lapOn) }
    var lapMetres by remember { mutableIntStateOf(store.lapMetres) }
    var appMode by remember { mutableStateOf(store.appMode) }
    var timerSeconds by remember { mutableIntStateOf(store.timerSeconds) }
    var presets by remember { mutableStateOf(store.timerPresets) }
    var useRecorded by remember { mutableStateOf(store.useRecorded) }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // THE NUMBERS AND NOTHING ELSE. Baba, 21.9.2026: "add a button for the full screen when
    // stopwatch is displayed without any buttons, only numbers."
    //
    // This app already hides the system bars, so "full screen" here does not mean the window —
    // it means the APP'S OWN controls. Pressed, the eight glyphs and the transport strip leave
    // the screen and the digits take the whole of it, which on a phone propped on a bench across
    // a room is a measurably larger number rather than a tidier one.
    //
    // It is the one place this app deliberately breaks its own oldest rule — "no button is ever
    // hidden, because a control that disappears moves the layout". The rule is about a control
    // vanishing BECAUSE IT CANNOT ACT, which leaves you guessing where it went. Here every
    // control leaves at once, because somebody pressed the button that says so, and one gesture
    // brings all of them back. Nothing is guessed and nothing shuffles.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    var fullscreen by remember { mutableStateOf(store.fullscreen) }

    // WHEN THE LAST TAP LANDED, so the one after it knows whether it is the second of two.
    // Zero means there has not been one, which is a real state rather than a sentinel.
    var lastTapAt by remember { mutableLongStateOf(0L) }

    var names by remember { mutableStateOf(store.names) }
    var prerollStopwatch by remember { mutableIntStateOf(store.preroll(AppMode.STOPWATCH)) }
    var prerollTimer by remember { mutableIntStateOf(store.preroll(AppMode.TIMER)) }
    val preroll = if (appMode == AppMode.TIMER) prerollTimer else prerollStopwatch

    // Read once, up here, because the countdown effect below needs it and effects belong
    // beside the state they drive rather than below the values they happen to use.
    val context = LocalContext.current

    // THE COUNTDOWN. Zero means no countdown is running; otherwise it is the instant it ends,
    // on the same monotonic clock as everything else in this app, because a countdown that used
    // the wall clock would jump when a time server corrected it.
    var prerollEndsAt by remember { mutableLongStateOf(0L) }
    var prerollNow by remember { mutableLongStateOf(0L) }

    // THE LAP COUNT LIVES HERE, NOT IN THE STOPWATCH. It counts lengths of a pool; the stopwatch
    // measures time, and the one thing that model has never done in twenty versions is let
    // anything but a transition touch startedAt and accumulated.
    var laps by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }

    // VOICE. The switch is a preference; the microphone follows it and the app's own lifecycle.
    var listening by remember { mutableStateOf(store.listening) }
    var level by remember { mutableFloatStateOf(0f) }
    var voiceState by remember { mutableStateOf("off") }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // THE FLASH. A second is a long time to wait to find out whether anything heard you.
    //
    // The digits take the flash colour for about a seventh of a second the instant a command
    // registers, whether it came from a thumb or from a voice. It is not a state and it carries
    // no information beyond "that arrived" — which is the whole point, because the number itself
    // will not change for up to a second and the silence in between is what feels broken.
    //
    // ONLY WHEN THE STATE ACTUALLY CHANGED. A press on a dead control registered nothing, so it
    // must not claim to have. Flashing on every touch would make the flash mean "I was touched"
    // rather than "that worked", and the second is the only one worth having.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    var flashes by remember { mutableIntStateOf(0) }
    var flashing by remember { mutableStateOf(false) }

    LaunchedEffect(flashes) {
        if (flashes == 0) return@LaunchedEffect
        flashing = true
        delay(FLASH_MS)
        flashing = false
    }


    fun beginPreroll(): Boolean {
        if (preroll <= 0) return false
        if (state.phase != Phase.STOPPED) return false
        prerollEndsAt = SystemClock.elapsedRealtime() + preroll * 1000L
        prerollNow = SystemClock.elapsedRealtime()
        return true
    }

    fun cancelPreroll() {
        prerollEndsAt = 0L
    }

    fun commit(next: Stopwatch) {
        // Stop clears the laps with everything else. A lap count left over from the last swim,
        // sitting above a stopwatch reading zero, is a number that will be believed.
        if (next.phase == Phase.STOPPED && state.phase != Phase.STOPPED) laps = 0
        if (next != state) flashes++
        state = next
        now = SystemClock.elapsedRealtime()
        store.save(next)
    }

    val elapsed = state.elapsed(now)

    /**
     * The sound for a moment, honouring the switch and falling back when it cannot be honoured.
     *
     * FALLING BACK RATHER THAN GOING SILENT. If the recorded word is chosen and there is no
     * recording, a silent count-in would look exactly like a broken one — and the person would
     * be standing at the end of a lane waiting for a sound that was never coming.
     */
    fun sound(bird: Bird) {
        if (useRecorded && GoSound.exists(context)) GoSound.play(context)
        else GoSound.playSamples(Birdsong.samples(bird), Dsp.SAMPLE_RATE)
    }

    // Starting a measurement from zero with the countdown on does not start the clock: it starts
    // the countdown, and the clock starts when that ends. Everything else — resuming a pause,
    // pausing, stopping — is immediate, because a start ceremony in front of those would be a
    // delay with no purpose.
    /**
     * THE ONE ROUTE INTO STARTING, whether it came from a thumb on the digits, the play glyph or
     * a spoken word.
     *
     * A TIMER AT ZERO USED TO BE A DEAD END. It finishes PAUSED with the elapsed figure past the
     * length, so a press resumed it — the clock ran on, the remaining figure stayed clamped at
     * zero, and nothing appeared to happen however many times you pressed. The fault was not the
     * clamp, it was that "finished" is a state the timer has and the transport did not know
     * about.
     *
     * So the first press at zero RESETS to the full duration, and the next one starts it, with
     * the count-in if there is one. Two presses to go again, and the first of them visibly
     * changes the number, which is what was missing.
     */
    // ─────────────────────────────────────────────────────────────────────────────────────────
    // TWO WAYS IN AND TWO WAYS OUT OF FULL SCREEN, AND ONE PIECE OF CODE FOR EACH DIRECTION.
    //
    // v45 had one button in and one long press out, written at the two places they happened. v46
    // adds the pinch at Baba's word — "pinch in to exit and pinch out to enter" — and that is the
    // moment two call sites become four. Four copies of "also close the panel, also write the
    // store" is four chances for one of them to forget, and the one that forgets is found weeks
    // later as "sometimes it comes back with the buttons on".
    // ─────────────────────────────────────────────────────────────────────────────────────────
    fun enterFullscreen() {
        // The panel cannot be left open behind a screen that draws no way of closing it: its own
        // X would be the only control on screen, over digits that were supposed to be alone.
        settingsOpen = false
        fullscreen = true
        store.fullscreen = true
    }

    fun leaveFullscreen() {
        fullscreen = false
        store.fullscreen = false
    }

    fun onPlay() {
        // THE CLOCK HAS NOTHING TO START. A tap on the time must not quietly run a stopwatch
        // behind it that nobody can see.
        if (appMode == AppMode.CLOCK) return
        if (prerollEndsAt > 0L) {
            cancelPreroll()
            return
        }
        if (appMode == AppMode.TIMER && timerFinished(timerSeconds * 1000L, elapsed)) {
            commit(state.stop())
            return
        }
        val next = state.press(Control.PLAY, SystemClock.elapsedRealtime())
        if (!(next.phase == Phase.RUNNING && beginPreroll())) commit(next)
    }

    // THE TIMER ENDS ITSELF, and says so with the same word that starts a measurement. A timer
    // that reaches zero and carries on running is a timer that has to be watched, which is the
    // one thing a timer exists to avoid.
    LaunchedEffect(appMode, timerSeconds, state.phase, elapsed / 1000L) {
        if (appMode != AppMode.TIMER || state.phase != Phase.RUNNING) return@LaunchedEffect
        if (timerFinished(timerSeconds * 1000L, elapsed)) {
            commit(state.pause(SystemClock.elapsedRealtime()))
            // THE BIRD MARKS THE END; THE RECORDED WORD MARKS THE START. Using the same sound for
            // both would make the two moments indistinguishable from across a room, which is the
            // only place either of them is heard from.
            //
            // It goes through the same path as everything else this app plays, so the microphone
            // is deaf for its length plus the ring — the app cannot hear its own bird and stop
            // the clock it just finished.
            sound(Bird.CHAFFINCH)
        }
    }

    // THE COUNTDOWN'S OWN CLOCK. It ticks at 100ms rather than the stopwatch's one second,
    // because a number counting down needs to change on the second it names rather than up to a
    // second late. It runs only while a countdown is open, so it costs nothing the rest of the
    // time.
    LaunchedEffect(prerollEndsAt) {
        if (prerollEndsAt == 0L) return@LaunchedEffect
        while (isActive) {
            prerollNow = SystemClock.elapsedRealtime()
            if (prerollNow >= prerollEndsAt) {
                prerollEndsAt = 0L
                // The word plays as the clock starts, not before it: the sound marks the start
                // rather than announcing that one is coming.
                commit(state.play(SystemClock.elapsedRealtime()))
                // A DIFFERENT BIRD FROM THE TIMER'S. Both are heard from across a room while
                // doing something else, and a person who has to work out WHICH sound that was
                // has been handed a puzzle instead of an answer.
                sound(Bird.CHICKADEE)
                return@LaunchedEffect
            }
            delay(100L)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // THE MICROPHONE.
    //
    // Created when the switch is on and destroyed when it is off, when the app leaves the
    // screen, or when this composition goes away. There is no service: nothing listens while the
    // stopwatch is not visible, and the microphone indicator in the status bar is the truth.
    //
    // A recognised command goes through exactly the same press() the button does, so a spoken
    // "start" and a tapped play cannot behave differently.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    val granted = remember(listening) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    var lit by remember { mutableStateOf(Lit()) }

    /**
     * THE WAVEFORM AS IT FORMS, ported in spirit from SAMPLE_PLAYER's Recorder.live.
     *
     * A recorder that shows nothing until it stops asks you to talk into a hole and find out
     * afterwards. The meter says audio is arriving; only the SHAPE says what arrived — whether the
     * word is centred, whether you started too late, whether the take is worth keeping. On a
     * professional recorder that display is not decoration, it is the thing you watch.
     *
     * One value per level tick, which is 50ms, so a two-second take is forty values. Capped, and
     * halved rather than trimmed when the cap is reached, which is the same thing a waveform does
     * when it is drawn narrower.
     */
    var live by remember { mutableStateOf(FloatArray(0)) }

    // The permission ask. Deleted by accident in the v12 rewrite of this block and caught by the
    // compiler, which is the cheapest place for it to be caught and the reason the build runs
    // before anything is published.
    val askForMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        listening = allowed
        store.listening = allowed
        if (!allowed) voiceState = "microphone refused"
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // ONE OWNER OF THE MICROPHONE, AND THE METER NEVER STOPS.
    //
    // Until v11 there were two: MicProbe when voice was off and SpeechRecognizer when it was on.
    // That is why the meter died the moment listening was switched on, and it is why a tone went
    // on and off without pause — the recogniser was being started and killed several times a
    // second while it found nothing in a silent room.
    //
    // VoiceEngine keeps AudioRecord on the microphone the whole time and wakes the recogniser
    // only when the level says a word was actually spoken. The meter therefore runs whether
    // voice is armed or not, which is what Baba asked for and was impossible before.
    //
    // The engine lives as long as the PROCESS now, not this screen. Arming is a separate call, so
    // switching voice on and off does not tear the microphone down and build it again.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    var scores by remember { mutableStateOf<List<Pair<Control, Double>>>(emptyList()) }
    var templatesReady by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(SettingsTab.TIMER) }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // THE SCREEN SUBSCRIBES; IT DOES NOT OWN.
    //
    // The engine used to be created here, which meant it died with the screen — and a hands-free
    // control that only works while you are looking at it is not hands-free. It now belongs to
    // the process, held by VoiceHub, and its lifetime is decided by ListeningService.
    //
    // These callbacks are set while this screen exists and cleared when it leaves. In between the
    // engine carries on without it, which is the entire point.
    DisposableEffect(Unit) {
        VoiceHub.onCommand = cmd@{ control ->
            // The clock has nothing to start, pause or stop, spoken or pressed.
            if (appMode == AppMode.CLOCK) return@cmd
            // Lap is a command but not a transport control, so it is routed here rather than
            // pressed into the model.
            if (control == Control.LAP) {
                laps++
                flashes++
            } else if (control == Control.PLAY && prerollEndsAt == 0L &&
                state.phase != Phase.RUNNING && !settingsOpen && beginPreroll()
            ) {
                flashes++
            } else if (!settingsOpen) {
                // DETECTION ONLY WHILE THE PANEL IS OPEN: the word lights, the clock does not
                // move. And a spoken command cancels a countdown for the same reason a pressed
                // one does — it is no longer wanted.
                cancelPreroll()
                commit(state.press(control, SystemClock.elapsedRealtime()))
            }
        }
        VoiceHub.onLevel = { value ->
            level = value
            // Read at call time, so this sees the current recording rather than the one that was
            // in progress when these callbacks were installed.
            if (VoiceHub.capturing) {
                val next = live + value
                live = if (next.size <= LIVE_MAX) next
                else FloatArray(next.size / 2) { next[it * 2] }
            }
        }
        VoiceHub.onHeard = { hit, s ->
            scores = s
            if (hit != null) lit = Lit.of(hit, SystemClock.elapsedRealtime())
        }
        VoiceHub.onState = { voiceState = it }
        onDispose {
            VoiceHub.onCommand = null
            VoiceHub.onLevel = null
            VoiceHub.onHeard = null
            VoiceHub.onState = null
        }
    }

    // Refeatured when a sample changes, in VoiceHub rather than here, so the templates survive
    // this screen going away. Doing it here would have lost them at exactly the moment they
    // matter most: when the phone is in a pocket.
    LaunchedEffect(templatesReady) { VoiceHub.reloadTemplates(context) }

    // THE SERVICE IS THE MICROPHONE'S LIFETIME NOW.
    //
    // It runs while voice is switched on, and while the settings panel is open so the meter and
    // the tester have something to show. Switching voice off stops it, and the notification goes
    // with it — the price is paid only while the feature is being used.
    val wantService = granted && (listening || settingsOpen)
    DisposableEffect(wantService) {
        if (wantService) ListeningService.start(context) else ListeningService.stop(context)
        onDispose { }
    }

    // Leaving the screen with voice OFF must close the microphone. Leaving it with voice ON must
    // not — that is the feature. So the panel being open is not enough on its own to keep it
    // alive once this composition is gone.
    DisposableEffect(listening) {
        onDispose { if (!listening) ListeningService.stop(context) }
    }

    // NOT CAPTURING MEANS TESTING. There is no third mode and no switch between them: pressing a
    // pad suspends matching for as long as that one capture lasts, and matching resumes the
    // moment it ends. Everything the person has to know is which pad they pressed.
    DisposableEffect(listening, granted, settingsOpen) {
        VoiceHub.setArmed(granted && (listening || settingsOpen))
        onDispose { }
    }

    // THE TICK, now once a second rather than ten times. untilNextSecond is bounded to 1..1000ms
    // and can never return zero, so this loop cannot spin. `isActive` rather than `true`: the
    // bound is the coroutine's own life, and writing it that way means the gate that greps for
    // unbounded loops sees an honest answer.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    // R, THE WALL CLOCK. Read on the second rather than on the minute, because a minute-long
    // sleep would miss the phone's own clock being corrected, a time zone being crossed, or the
    // screen coming back from a long pause with the old time still on it. Once a second is
    // nothing, and it runs only while R is showing.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    var wall by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(appMode) {
        if (appMode != AppMode.CLOCK) return@LaunchedEffect
        while (isActive) {
            // THE ONE PLACE THIS SCREEN READS THE WALL CLOCK, and it reads it to SHOW it, never
            // to measure with it. verify.py check 2 still holds: no millisecond wall read here.
            val t = LocalTime.now()
            wall = t
            delay((1000L - t.nano / 1_000_000L).coerceIn(1L, 1000L))
        }
    }

    LaunchedEffect(state) {
        if (state.phase != Phase.RUNNING) return@LaunchedEffect
        var sinceWrite = 0L
        while (isActive) {
            val step = Face.untilNextSecond(state.elapsed(SystemClock.elapsedRealtime()))
            delay(step)
            now = SystemClock.elapsedRealtime()
            sinceWrite += step
            if (sinceWrite >= 10_000L) {
                sinceWrite = 0L
                store.save(state)
            }
        }
    }

    DisposableEffect(state.phase) {
        val w = activity.window
        if (state.phase == Phase.STOPPED) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // SENSOR_ rather than plain PORTRAIT and LANDSCAPE. The plain constants pin one specific
    // way up, so a phone laid on a table and turned to face somebody across it stays upside
    // down. The sensor variants hold the CLASS of orientation and still let it flip 180
    // degrees within it, which is what somebody means when they say "landscape".
    //
    // Both are forced, so the app ignores the system auto-rotate setting entirely. That is the
    // point: the button chooses, not the phone.
    DisposableEffect(orientation) {
        activity.requestedOrientation = when (orientation) {
            Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose { }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, state) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) store.save(state)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // THE SCREEN.
    //
    // ONE LAYOUT, TWO SETS OF SIZES. v2 put the transport down the right-hand edge in landscape
    // because the spec said so, and on the phone that was wrong. The buttons live at the bottom,
    // always, in both orientations. So there is no longer a landscape branch and a portrait
    // branch, only a strip whose height and button size differ. Two layouts were two places for
    // the thing to be different; now there is one.
    //
    // Note what is NOT here: the background carries no click handler of any kind. Tap-anywhere
    // is gone deliberately, and its absence is enforced by verify.py rather than merely intended.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(BACKGROUND)
            // ─────────────────────────────────────────────────────────────────────────────────
            // THE PINCH, ON THE WHOLE SCREEN. Baba: "pinch in to exit and pinch out to enter."
            //
            // ON THE ROOT RATHER THAN ON THE DIGITS, because in full screen the digits are all
            // there is but on the ordinary screen they are not, and a way out that only works if
            // you happen to land on the numbers is a way out you have to aim for. A pinch is
            // already a whole-screen gesture everywhere else on the phone.
            //
            // IT IS NOT A CLICKABLE AND THAT DISTINCTION IS THE WHOLE REASON THIS IS ALLOWED
            // HERE. v1 removed tap-anywhere because a stray touch on the background destroyed a
            // measurement, and `verify.py` still refuses a `.clickable` on this modifier. A pinch
            // cannot be made by a pocket, a sleeve or one finger, and the worst it can do is
            // change how much of the screen the numbers take — it never touches the clock.
            //
            // THE ACCUMULATOR LIVES INSIDE awaitEachGesture, so it starts at one for every new
            // gesture. Hoisted out, a long afternoon of small spreads would eventually add up to
            // a quarter and the screen would change with nobody having asked it to.
            //
            // `fired` means the gesture has already had its answer. Without it, carrying on
            // spreading past the threshold would re-fire on every frame — and in full screen that
            // is a control changing state sixty times a second under a finger that is still
            // moving.
            // ─────────────────────────────────────────────────────────────────────────────────
            .pointerInput(fullscreen, settingsOpen) {
                // NOT WHILE THE PANEL IS OPEN. The panel is drawn over this, and a pinch meant
                // for a swatch grid that instead swallowed the whole panel would be the app
                // taking a decision nobody made.
                if (settingsOpen) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var zoom = 1f
                    var fired = false
                    do {
                        val event = awaitPointerEvent()
                        // ONE FINGER IS NOT A PINCH. calculateZoom returns 1 for a single
                        // pointer, so this would be harmless either way — but reading it only
                        // when there are two says what a pinch IS, rather than relying on
                        // arithmetic elsewhere to come out right.
                        if (!fired && event.changes.size >= 2) {
                            zoom *= event.calculateZoom()
                            val verdict = pinchVerdict(zoom)
                            if (verdict == Pinch.OUT && !fullscreen) {
                                fired = true
                                enterFullscreen()
                            } else if (verdict == Pinch.IN && fullscreen) {
                                fired = true
                                leaveFullscreen()
                            }
                            // CONSUMED ONLY ONCE IT HAS ACTED, so that a pinch which decides
                            // nothing leaves every other gesture on the screen untouched. Once it
                            // has acted, the fingers lifting must not also read as a tap on the
                            // digits underneath.
                            if (fired) event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .safeDrawingPadding()
    ) {
        val screenW = maxWidth
        val screenH = maxHeight
        val landscape = screenW > screenH
        // ONE MEASUREMENT, READ TWO WAYS. The timer does not have its own clock: it is the same
        // elapsed figure subtracted from a length, so everything proven about startedAt and
        // accumulated over twenty-nine versions holds for both modes without being proven twice.
        val lengthMs = timerSeconds * 1000L
        val shown = if (appMode == AppMode.TIMER) timerRemaining(lengthMs, elapsed) else elapsed
        val text = if (appMode == AppMode.CLOCK) Face.clock(wall.hour, wall.minute)
                   else Face.format(shown, display)
        val countdown = prerollLabel(prerollEndsAt - prerollNow).takeIf { prerollEndsAt > 0L }

        val button = if (landscape) 56.dp else 72.dp

        // THE TWO RESERVED BANDS, AND IN FULL SCREEN THEY ARE NOT RESERVED.
        //
        // These two numbers are the whole of the full-screen change to the layout, and that is
        // deliberate: the digits are already sized by measuring what is left over, so handing
        // them two zeros gives them the screen without a second code path drawing them.
        // A branch that laid the digits out differently in full screen would be a second layout
        // to keep in step with the first, and the two would disagree within a version.
        val strip = if (fullscreen) 0.dp else if (landscape) 72.dp else 108.dp
        val topZone = if (fullscreen) 0.dp else LOCK_ZONE

        // THE TOP ROW'S CLEARANCE IS RESERVED HERE, and until now it was not. The digit sizing
        // subtracted LOCK_ZONE from the height it had to fill, so it BELIEVED the row above was
        // set aside — but nothing had actually set it aside, so the column began at the very top
        // of the screen and the lap count was drawn straight through the orientation, mode,
        // microphone, power and settings controls.
        //
        // The arithmetic and the layout disagreed, and the arithmetic was right.
        Column(Modifier.fillMaxSize().padding(top = topZone)) {
            // ABOVE THE DIGITS, and only when the counter is on. It is a control as well as a
            // readout: tapping it is the other way to count a length, and at the end of a length
            // in a pool a thumb finds a wide target above the numbers more easily than a small
            // one anywhere else.
            // THE LAP COUNT FILLS THE SPACE ABOVE THE DIGITS.
            //
            // It was 28sp pinned under the top controls, which put a small number in a large
            // empty band — and this is a display read across a room, where a small number is a
            // number nobody reads. It now takes its own share of the height and is sized to fill
            // it by the same binary search the digits use, so it is as large as the space allows
            // rather than a figure somebody typed.
            //
            // A THIRD OF THE HEIGHT, NOT HALF. The lap count is context; the clock is the
            // measurement. Giving them equal weight would make you look twice to find out which
            // number is which, and the one you came for is the one below.
            // Not over the clock: a lap count above the time of day is a number with no meaning.
            lapLabel(laps, lapOn && appMode != AppMode.CLOCK, lapMetres)?.let { label ->
                Digits(
                    text = label,
                    colour = Color(if (flashing) Palette.flashOf(colour) else colour),
                    weight = weight,
                    width = screenW - EDGE * 4,
                    height = (screenH - strip - topZone) * 0.30f,
                    modifier = Modifier
                        .weight(0.30f)
                        .fillMaxWidth()
                        .clickable { laps++; flashes++ },
                )
            }

            // ─────────────────────────────────────────────────────────────────────────────────
            // THE NUMBERS ARE A CONTROL AGAIN, AND THIS REVERSES v1 — BUT NOT ITS REASONING.
            //
            // v1 deleted tap-anywhere, and the argument was never that a large target is bad. It
            // was that the gesture's SECOND STATE WAS DESTRUCTIVE: tap to start, tap again to
            // reset, so one stray touch during a real measurement destroyed it with the whole
            // screen as the target.
            //
            // That objection is answered rather than ignored. A tap now toggles between running
            // and paused, which is recoverable and visible — the worst a stray touch can do is
            // pause a clock you can see is paused, and press again. RESET IS BEHIND A LONG PRESS,
            // which is the one gesture a pocket, a sleeve or a wet hand does not produce.
            //
            // What it buys back is what v1 knowingly gave up: starting without aiming, with the
            // whole screen as the target. That matters more for Baba than for most people.
            // ─────────────────────────────────────────────────────────────────────────────────
            Digits(
                text = countdown ?: text,
                colour = Color(if (flashing) Palette.flashOf(colour) else colour),
                weight = weight,
                width = screenW - EDGE * 2,
                height = screenH - strip - topZone,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .combinedClickable(
                        // ─────────────────────────────────────────────────────────────────
                        // ONE TAP STOPS IT, TWO TAPS RESET IT — AND THE FIRST TAP STILL FIRES
                        // THE INSTANT IT LANDS. That last clause is the whole design of this
                        // block and it is worth the paragraph.
                        //
                        // Compose will detect a double tap for you, through `onDoubleClick`. The
                        // price is that EVERY SINGLE TAP IS THEN HELD BACK for the length of the
                        // double-tap window, because until that window closes the tap might turn
                        // out to be the first of two. Three hundred milliseconds is nothing in a
                        // menu. In a stopwatch it is three tenths of a second of a measurement
                        // that never happened, on every single start, forever — and this app
                        // exists to measure things.
                        //
                        // SO THE TAP IS NEVER HELD. It acts immediately, and the second tap,
                        // if one comes, resets ON TOP of whatever the first one did. That works
                        // because the composition is harmless in both directions:
                        //
                        //     running, tap tap   pauses, then resets      -> zeros
                        //     stopped, tap tap   starts, then resets      -> zeros
                        //     counting in, tap tap  cancels, then resets  -> zeros
                        //
                        // Every path ends at zeros, which is what two taps mean. The
                        // intermediate state exists for less than a third of a second and is the
                        // correct answer to the first tap in its own right.
                        //
                        // A THIRD RAPID TAP RESETS AGAIN rather than starting a new measurement,
                        // because `lastTapAt` is not cleared when a double fires. A burst of
                        // panicky taps therefore ends at zeros instead of quietly starting the
                        // clock, and of the two possible surprises that is the safe one.
                        //
                        // WHAT v1 REMOVED IS NOT BEING PUT BACK. Tap-anywhere was deleted
                        // because its second state was destructive on a SINGLE STRAY TAP: touch
                        // it once to start, touch it again an hour later and the measurement was
                        // gone. Here a lone tap can only ever pause or start — recoverable, and
                        // visible. Reset needs two taps inside a third of a second, which is a
                        // thing a hand does on purpose and a pocket does not do at all.
                        // ─────────────────────────────────────────────────────────────────
                        onClick = {
                            if (appMode == AppMode.CLOCK) return@combinedClickable
                            val now = SystemClock.elapsedRealtime()
                            onPlay()
                            if (isDoubleTap(lastTapAt, now)) {
                                cancelPreroll()
                                // `stop()` builds a fresh zeroed clock and ignores its receiver,
                                // so it does not matter that `state` here was captured before
                                // onPlay() committed over it.
                                commit(state.stop())
                            }
                            lastTapAt = now
                        },
                        // THE LONG PRESS MEANS ONE THING AGAIN, IN BOTH MODES, and getting back
                        // to that is the quiet win in v46. It had to carry the way out of full
                        // screen because nothing else could, which cost reset entirely while
                        // full screen was on — written down at the time as a real loss. The
                        // pinch now owns that door, so this goes back to reset everywhere, and
                        // reset is reachable in full screen again.
                        onLongClick = {
                            if (appMode == AppMode.CLOCK) return@combinedClickable
                            cancelPreroll()
                            commit(state.stop())
                        },
                    ),
            )
            // EVERY CONTROL LEAVES AT ONCE, OR NONE OF THEM DOES. The condition is on the row
            // and not on the three glyphs inside it, and that is the difference between this and
            // the thing the rule forbids: a transport that hid whichever button could not act
            // would move the other two under your thumb. This hides the row, the strip's height
            // goes to zero with it, and the digits grow into the space — one layout, one press,
            // nothing shuffling.
            if (!fullscreen) Row(
                modifier = Modifier.fillMaxWidth().height(strip),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Three controls, one call each, and the tone comes from the model rather than
                // from a condition written here. The Activity does not know what a phase is.
                //
                // The spoken word comes from Control.spoken and is not written here. It is what
                // Voice Access listens for, it is what the tip in the settings panel prints, and
                // there is one copy of it so the two can never disagree.
                // PLAY goes through the countdown; the other two cancel it. A countdown running
                // while somebody presses stop is a countdown that is no longer wanted, and
                // leaving it to finish would start a measurement nobody asked for.
                // IN R THE THREE STAY WHERE THEY ARE AND GO DARK. The row does not leave, so
                // nothing moves under the thumb when the letter changes; the glyphs simply have
                // nothing to do, and look it.
                val transportLive = appMode != AppMode.CLOCK
                Transport(Icons.Default.PlayArrow, Control.PLAY, state, button, transportLive) { _ -> onPlay() }
                Transport(Icons.Default.Pause, Control.PAUSE, state, button, transportLive) { next ->
                    cancelPreroll(); commit(next)
                }
                Transport(Icons.Default.Stop, Control.STOP, state, button, transportLive) { next ->
                    cancelPreroll(); commit(next)
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────────────────────
        // THE EIGHT CONTROLS, AND THE ONE CONDITION THAT TAKES THEM ALL AWAY.
        //
        // Wrapped as a group rather than one by one, deliberately. A condition per control would
        // read as eight independent decisions about eight controls, which is exactly the shape
        // of the fault this app has always refused — a button that goes missing on its own and
        // takes its position with it. There is one decision here and it is Baba's: numbers only,
        // or the app. A pinch in brings them all back.
        // ─────────────────────────────────────────────────────────────────────────────────────
        if (!fullscreen) {
            // The two corner controls, swapped at v6 on Baba's instruction: orientation left,
            // settings right. design-language.md 10 — a row has two ends and a middle, and a screen
            // is read as weight before it is read as anything else. Both say what the next press
            // does rather than what is currently true.

            // design-language.md 5: a control says what the next press DOES. In portrait it shows
            // the landscape glyph, because pressing it gives you landscape. It is not a readout of
            // where you are — you can see where you are by looking at the screen.
            Glyph(
                icon = if (orientation == Orientation.PORTRAIT) Icons.Default.StayCurrentLandscape
                       else Icons.Default.StayCurrentPortrait,
                label = if (orientation == Orientation.PORTRAIT) "Turn landscape" else "Turn portrait",
                tone = Tone.HIGHLIGHT,
                size = 40.dp,
                modifier = Modifier.align(Alignment.TopStart).padding(EDGE),
            ) {
                orientation = if (orientation == Orientation.PORTRAIT) Orientation.LANDSCAPE
                              else Orientation.PORTRAIT
                store.orientation = orientation
            }

            // ─────────────────────────────────────────────────────────────────────────────────
            // THE FULL-SCREEN BUTTON, NEXT TO THE ORIENTATION AND NOT BY ACCIDENT.
            //
            // Both of these controls answer the same question — what shape is the screen — and
            // the two are used in the same breath: prop the phone up, turn it the way you want
            // it, take the buttons away. Putting them side by side means one place to look
            // instead of a hunt along a row of eight.
            //
            // THE WAY BACK IS THE PINCH, and v46 made that true. This button is the only
            // control that is not on the screen when you want it again, so the return has to be
            // something a person finds without being told twice — and a pinch is the one gesture
            // every phone already teaches: it is how a photo, a map and a web page have been
            // made smaller for fifteen years. Pinch out to fill the screen, pinch in to give it
            // back.
            //
            // v45 hung the return on the long press, which worked and cost too much: the long
            // press is reset, so reset had to be surrendered for as long as full screen was on.
            // The pinch owns the door now and the long press went back to meaning one thing.
            //
            // THERE IS NO MATCHING EXIT GLYPH, and the arrow only ever points one way, because
            // the moment it would be needed it is not drawn. A control whose second state cannot
            // be shown should not pretend to have one.
            // ─────────────────────────────────────────────────────────────────────────────────
            Glyph(
                icon = Icons.Default.Fullscreen,
                label = "Numbers only",
                tone = Tone.SECONDARY,
                size = 40.dp,
                modifier = Modifier.align(Alignment.TopStart).padding(EDGE).offset(x = 44.dp),
            ) { enterFullscreen() }

            // ─────────────────────────────────────────────────────────────────────────────────────
            // THE WAY OUT.
            //
            // The app is full screen, which took the system bars away and the back gesture with them.
            // An app with no exit is a trap however good it is, and this one is meant to be left
            // running on a bench.
            //
            // THE POWER MARK, NOT A CROSS. A cross is a thing being cancelled — it says the screen
            // was a mistake. The power mark is the oldest and best-drawn symbol in the whole of
            // consumer electronics: a broken circle with a stroke through the gap, one continuous
            // idea, no corners, and it means the thing is being switched off rather than dismissed.
            // Outlined, so it obeys the same rule as everything else on this screen: hollow is off,
            // and this control is the one that turns everything off.
            //
            // Dim, because it is used once a day and the digits are used constantly.
            //
            // BETWEEN THE MICROPHONE AND THE SETTINGS, not in the middle. v28 took the centre for
            // this and pushed the microphone aside, which had the priority backwards: the microphone
            // is a state you check constantly and the exit is a control you use once. The middle
            // belongs to the thing that is looked at, not to the thing that is looked for.
            //
            // The offset is a quarter of the width rather than a fixed distance, so it stays halfway
            // between the two on a phone held either way up. A fixed number would sit beside the
            // microphone in portrait and be lost in the middle of nowhere in landscape.
            // ─────────────────────────────────────────────────────────────────────────────────────
            // ─────────────────────────────────────────────────────────────────────────────────────
            // S OR T, mirroring the power mark on the other side of the microphone.
            //
            // A LETTER, NOT A GLYPH, and that is deliberate. There is no icon in the Material set
            // that says "stopwatch rather than timer" without being read twice — both are clocks and
            // both are drawn as circles with hands. S and T are read instantly, they are the words
            // themselves, and they are set in the same monospaced face as the digits so they belong
            // to the screen rather than sitting on top of it.
            //
            // The letter shown is the mode you are IN, not the one the press would give. This is the
            // one control on the screen that breaks that rule, and it breaks it for the same reason
            // the microphone does: it is a STATE. You need to know which clock you are looking at
            // before you look at the number, and a control that only says what it would become
            // leaves that question unanswered.
            // ─────────────────────────────────────────────────────────────────────────────────────
            // THE RING IS GONE FROM HERE TOO, and it had to go from here for the rule to hold. v40
            // gave this letter a ring so that it would not be the one pressable thing on the screen
            // without one. That was right while the rings existed; with them removed, a ring left
            // here would make it the one thing on the screen WITH one, which is the same fault
            // wearing the opposite coat.
            //
            // It says what it is the way the glyphs do now: coloured in the timer, grey in the
            // stopwatch, and nothing drawn around it.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(EDGE)
                    .offset(x = -(screenW / 4 - 16.dp))
                    .size(40.dp)
                    .clickable {
                        // Changing which way the clock runs mid-measurement would leave a figure on
                        // screen that means something different from the one that was there a moment
                        // before, so the measurement is cleared with the mode.
                        // S, T, R and round again. One letter, one press, one step.
                        appMode = appMode.next()
                        store.appMode = appMode
                        commit(state.stop())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = appMode.letter,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (appMode != AppMode.STOPWATCH) Color(colour) else GLYPH,
                        fontSize = 18.sp,
                    ),
                    maxLines = 1,
                )
            }

            Glyph(
                icon = Icons.Outlined.PowerSettingsNew,
                label = "Close the stopwatch",
                tone = Tone.SECONDARY,
                size = 40.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(EDGE)
                    .offset(x = screenW / 4 - 16.dp),
            ) { activity.finish() }

            Glyph(
                icon = if (settingsOpen) Icons.Default.Close else Icons.Default.Settings,
                label = if (settingsOpen) "Close settings" else "Settings",
                tone = Tone.HIGHLIGHT,
                size = 40.dp,
                modifier = Modifier.align(Alignment.TopEnd).padding(EDGE),
            ) { settingsOpen = !settingsOpen }

            // ─────────────────────────────────────────────────────────────────────────────────────
            // THE MICROPHONE, TOP MIDDLE.
            //
            // v9 put it bottom right, where it sat beside the transport strip and Baba could not
            // find it. Top middle is the third position on a screen whose two corners are already
            // taken, and design-language.md 10 is explicit that a row has two ends AND A MIDDLE.
            //
            // FILLED IS ON, OUTLINED IS OFF. No slash. A struck-out microphone is a third mark to
            // read — you have to notice the line, and a small line at arm's length is exactly what
            // low vision loses first. Solid against hollow is a difference in weight, which is what
            // an interface is read as before it is read as anything else, and it survives being
            // glanced at from across a room.
            //
            // It is the only control here that shows a STATE rather than what the next press does. A
            // switch that does not show its position can only be read by pressing it, and pressing
            // this one to find out whether the microphone is open is precisely what must not be
            // necessary.
            // ─────────────────────────────────────────────────────────────────────────────────────
            Glyph(
                icon = if (listening) Icons.Filled.Mic else Icons.Outlined.Mic,
                label = if (listening) "Voice on" else "Voice off",
                tone = if (listening) Tone.PRIMARY else Tone.SECONDARY,
                size = 40.dp,
                // THE MIDDLE, AND IT KEEPS IT. v28 moved this aside for the exit and that was the
                // wrong way round: this is a STATE, checked at a glance and often, and the exit is a
                // control used once at the end. The centre belongs to what is looked at.
                modifier = Modifier.align(Alignment.TopCenter).padding(EDGE),
            ) {
                if (!listening && !granted) {
                    askForMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    listening = !listening
                    store.listening = listening
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────────────────────
        // THE SETTINGS GRID, over the bottom of the screen and never over the digits.
        //
        // design-language.md 11: a thing being adjusted while it runs must stay visible, because
        // covering it means adjusting blind, which is the problem the panel was built to solve.
        // The colour and the weight are judged against the digits, so the digits stay on screen
        // and every press applies LIVE. Half the screen, not all of it.
        //
        // It is drawn over the black below the digits, so opening it moves nothing.
        // ─────────────────────────────────────────────────────────────────────────────────────
        if (settingsOpen) {
            SettingsGrid(
                width = screenW - EDGE * 2,
                maxHeight = screenH - EDGE * 2,
                landscape = landscape,
                colour = colour,
                weight = weight,
                display = display,
                onDisplay = { display = it; store.display = it },
                lapOn = lapOn,
                lapMetres = lapMetres,
                onLapOn = { lapOn = it; store.lapOn = it },
                onLapMetres = { lapMetres = it; store.lapMetres = it },
                appMode = appMode,
                timerSeconds = timerSeconds,
                presets = presets,
                onTimerSeconds = { timerSeconds = it; store.timerSeconds = it; commit(state.stop()) },
                // THE LIST GOES THROUGH THE PURE FUNCTIONS AND NOWHERE ELSE. Adding is not
                // `presets + timerSeconds`: that would put a duplicate in on the second press,
                // and it would not know about the ceiling. Both rules live in presetAdd, where
                // Test 1 reads them, and this line only decides WHEN.
                onAddPreset = { presets = presetAdd(presets, timerSeconds); store.timerPresets = presets },
                onRemovePreset = { presets = presetRemove(presets, it); store.timerPresets = presets },
                names = names,
                onNames = { names = it; store.names = it; VoiceHub.reloadTemplates(context) },
                live = live,
                onLive = { live = it },
                preroll = preroll,
                onPreroll = { seconds ->
                    if (appMode == AppMode.TIMER) prerollTimer = seconds else prerollStopwatch = seconds
                    store.setPreroll(appMode, seconds)
                },
                context = context,
                listening = listening,
                level = level,
                scores = scores,
                store = store,
                onRecorded = { templatesReady++ },
                tab = tab,
                onTab = { tab = it },
                lit = lit,
                voiceState = voiceState,
                onColour = { colour = it; store.colour = it },
                onWeight = { weight = it; store.weight = it },
                onListening = { want ->
                    if (want && !granted) {
                        askForMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        listening = want
                        store.listening = want
                    }
                },
                onClose = { settingsOpen = false },
                // ─────────────────────────────────────────────────────────────────────────────
                // TOP ALIGNED, FULL SCREEN, AND THE VOID BELOW IS THE POINT.
                //
                // It used to sit at the bottom and be exactly as tall as its content, so every
                // tab put the tab row at a different height. Switching tabs moved the very
                // control you had just pressed — up, down, up — and you had to chase it. That is
                // a worse fault than it sounds: a row that moves cannot be learned, and muscle
                // memory is the whole reason a fixed layout is worth having.
                //
                // WHAT THIS COSTS, and it was a real rule rather than an oversight:
                // design-language.md 11 says a thing being adjusted while it runs must stay
                // visible, and the panel sat low so the digits showed above it while a colour was
                // being chosen. Full screen takes that away — you now close the panel to see the
                // colour you picked. That is one tap, and it buys a tab row that never moves.
                // ─────────────────────────────────────────────────────────────────────────────
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxSize()
                    .background(BACKGROUND)
                    .padding(EDGE),
            )
        }
    }
}

/**
 * One transport control. The glyph never changes: play stays a triangle whether pressing it will
 * start the clock or pause it, because a symbol that morphs under your thumb is a symbol you have
 * to read before every press. What moves is the HIGHLIGHT, which says which of the two the state
 * suggests you want next.
 */
@Composable
private fun Transport(
    icon: ImageVector,
    control: Control,
    state: Stopwatch,
    size: Dp,
    live: Boolean,
    commit: (Stopwatch) -> Unit,
) {
    Glyph(
        icon = icon,
        label = control.spoken,
        tone = if (live) state.tone(control) else Tone.DEAD,
        size = size,
    ) { commit(state.press(control, SystemClock.elapsedRealtime())) }
}

/**
 * A glyph with a hot zone and nothing drawn around it.
 *
 * IconButton still does the work, so the touch target is the full `size` and the press is taken
 * anywhere inside it. enabled = false does two things at once and both are wanted: it takes the
 * colour to the disabled tint and it stops the press being delivered. Pressing a button that can
 * do nothing does nothing, and it looked like nothing would.
 */
@Composable
private fun Glyph(
    icon: ImageVector,
    label: String,
    tone: Tone,
    size: Dp,
    modifier: Modifier = Modifier,
    /**
     * An override for the one control whose colour means something rather than ranking it. The
     * record arm is red while armed because red is what recording is everywhere, and that is a
     * different axis from the prominence ladder the tones express.
     */
    tint: Color? = null,
    onPress: () -> Unit,
) {
    // enabled = false on a DEAD control does two things at once and both are wanted: it takes
    // the colour to the disabled tint and it stops the press being delivered. A SECONDARY
    // control stays enabled — that is the whole point of the third tone.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    // NO RINGS. Baba, 21.9.2026: "remove circles around numbers." They are gone, and the
    // language they were carrying has gone back where it came from.
    //
    // This is the second time the circles have been removed and it should be the last, so the
    // whole argument is written down rather than half of it. v3 removed a ring drawn round every
    // glyph all the time, correctly: on glass it read as three more shapes on a screen whose
    // entire design is what is absent. v39 brought a CONDITIONAL ring back, present exactly when
    // a control could be pressed, on the reasoning that it was then information rather than
    // decoration.
    //
    // THE REASONING WAS SOUND AND THE RESULT WAS STILL WRONG, which is the useful part. A mark
    // that is information to the person who knows the rule is a shape to everybody else, and at
    // arm's length across a room — which is the only distance this app is ever read from — six
    // thin circles around six small glyphs are six circles. They sat directly under the digits
    // and competed with them.
    //
    // WHAT THE RING WAS SAYING IS NOT LOST; it goes back onto the WEIGHT of the glyph, which is
    // how this app said everything before v39 and how the microphone and the play mark still say
    // it. White is the one you want, grey is live, dark grey is live but not suggested, nearly
    // black is inert. That is a four-step ladder read at a glance, with nothing drawn around it.
    IconButton(
        onClick = onPress,
        enabled = tone != Tone.DEAD,
        modifier = modifier.size(size),
        colors = IconButtonDefaults.iconButtonColors(
            // THE TONE DECIDES THE COLOUR AGAIN. Between v39 and v45 it did not: every live glyph
            // was one grey and the outline carried the state, which was one saying in one place.
            // With the outline gone there would be no saying at all, so the ladder comes back —
            // and it is the same ladder the constants at the top of this file were named for.
            contentColor = tint ?: when (tone) {
                Tone.PRIMARY -> GLYPH_PRIMARY
                Tone.HIGHLIGHT -> GLYPH
                else -> GLYPH_SECOND
            },
            disabledContentColor = GLYPH_OFF,
        ),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.48f))
    }
}

/**
 * FORTY-EIGHT SWATCHES, in the manner of a swatch grid in an Adobe application. You look at it,
 * you press one, it is applied. No wheel, no hex field, no sliders: a wheel offers a million
 * colours in order to find the six anybody wants.
 *
 * Six columns standing up, twelve on its side. Forty-eight divides by both, so the same list is
 * rectangular in either orientation and no row is ever ragged.
 *
 * The chosen swatch carries a tick, following the Avid model in design-language.md 15 where a
 * category holds several instances and one carries the checkmark. The tick is drawn in black or
 * white depending on which can be seen on that swatch, and Palette.markOn computes which rather
 * than guessing — the guess was wrong on the orange and Test 1 said so.
 */
@Composable
private fun SettingsGrid(
    width: Dp,
    maxHeight: Dp,
    landscape: Boolean,
    colour: Long,
    weight: Weight,
    display: Display,
    onDisplay: (Display) -> Unit,
    lapOn: Boolean,
    lapMetres: Int,
    onLapOn: (Boolean) -> Unit,
    onLapMetres: (Int) -> Unit,
    appMode: AppMode,
    timerSeconds: Int,
    presets: List<Int>,
    onTimerSeconds: (Int) -> Unit,
    onAddPreset: () -> Unit,
    onRemovePreset: (Int) -> Unit,
    names: Map<Control, String>,
    onNames: (Map<Control, String>) -> Unit,
    live: FloatArray,
    onLive: (FloatArray) -> Unit,
    preroll: Int,
    onPreroll: (Int) -> Unit,
    context: android.content.Context,
    listening: Boolean,
    level: Float,
    scores: List<Pair<Control, Double>>,
    store: Store,
    onRecorded: () -> Unit,
    tab: SettingsTab,
    onTab: (SettingsTab) -> Unit,
    lit: Lit,
    voiceState: String,
    onColour: (Long) -> Unit,
    onWeight: (Weight) -> Unit,
    onListening: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A light that expires by time cannot go out on its own: nothing recomposes when a clock
    // passes a number. This is the panel's own tick, running only while the panel is open, fast
    // enough that a one-second light looks like it goes out at one second.
    var tickNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            tickNow = SystemClock.elapsedRealtime()
            delay(100L)
        }
    }

    // THE RECORDING STATE, and it belongs to the panel rather than to the screen: nothing
    // outside settings can start a recording, so nothing outside settings needs to know.
    var goRecorded by remember { mutableIntStateOf(0) }
    var update by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var recordingFor by remember { mutableStateOf<Pair<Control, Int>?>(null) }
    var note by remember { mutableStateOf("") }
    val recording = recordingFor != null

    // The microphone is already open, so recording is not opening anything: it is deciding which
    // slice of the ring to keep.
    //
    // PRESS, SPEAK, AND IT STOPS WHEN YOU STOP. No arm button and no fixed length: the capture
    // waits for the word, records while it lasts, and ends on the silence after it. When nothing
    // is capturing the app is testing, which is the only other thing it could be doing.
    var samplerMode by remember { mutableStateOf(SamplerMode.RECORD) }
    var editing by remember { mutableStateOf<Control?>(null) }
    var confirming by remember { mutableStateOf<Pair<Control, Int>?>(null) }

    fun beginRecording(control: Control, slot: Int) {
        onLive(FloatArray(0))
        recordingFor = control to slot
        confirming = null
        note = "recording — press again to stop"
        VoiceHub.startCapture { samples ->
            if (samples == null) {
                note = "too short, hold it longer"
            } else {
                // JUDGED BEFORE IT IS STORED. A sampler that keeps room tone as the sound of a
                // word is a trap: the pad looks filled, the count says three of three, and the
                // only symptom is that matching quietly stops being reliable.
                val quality = SampleCheck.assess(samples)
                note = SampleCheck.describe(quality)
                if (quality == SampleQuality.GOOD) {
                    store.saveSample(control, slot, samples)
                    onRecorded()
                }
            }
            recordingFor = null
        }
    }

    /**
     * EVERY PRESS GOES THROUGH THE TABLE, ported from SAMPLE_PLAYER. What a press MEANS is decided
     * in pure code that Test 1 can read; this only carries the decision out.
     */
    fun onPress(control: Control, slot: Int) {
        val filled = store.hasSample(control, slot)
        when (val d = SamplerGesture.press(samplerMode, control, slot, filled, recordingFor)) {
            is SamplerPress.StopRecording -> VoiceHub.finishCapture()
            is SamplerPress.StartRecording -> beginRecording(d.control, d.slot)
            is SamplerPress.Play -> store.loadSample(control, slot)?.let {
                GoSound.playSamples(it, Dsp.SAMPLE_RATE)
                note = "playing " + Vocabulary.display(control, names) + " " + (slot + 1)
            }
            is SamplerPress.ConfirmOverwrite ->
                // A second press on the same line confirms. Recording over a take destroys
                // something that cannot be got back, and a press is one finger on a small line.
                if (confirming == control to slot) {
                    beginRecording(control, slot)
                } else {
                    confirming = control to slot
                    note = "press again to record over it"
                }
            is SamplerPress.Refused -> note = d.why
        }
    }

    val gap = 6.dp
    val columns = if (landscape) Palette.COLUMNS_LANDSCAPE else Palette.COLUMNS_PORTRAIT
    val rows = Palette.SWATCHES.size / columns

    // THE CELL IS SIZED BY WHICHEVER RUNS OUT FIRST, WIDTH OR HEIGHT.
    //
    // v5 sized it by width alone. On a landscape phone that made a cell about 130dp across, four
    // rows of which are taller than the screen — so the panel covered everything including its
    // own way out, which is exactly what Baba hit. A panel that can grow past the display is a
    // trap, and the fix is not a smaller number, it is measuring against both edges.
    val header = 36.dp
    val weightRow = 52.dp
    val tipRow = 76.dp
    val forGrid = maxHeight - header - weightRow - tipRow - gap * (rows + 3)
    val cell = minOf((width - gap * (columns - 1)) / columns, forGrid / rows, 64.dp)
    val gridWidth = cell * columns + gap * (columns - 1)

    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {

        // The panel's own way out, and the version, which is the first time the number this app
        // was built from has been visible anywhere on the phone. versioning.md 3 asks for it in
        // three places and it has only ever been in two.
        Row(
            modifier = Modifier.width(gridWidth).height(header),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // CLOSE ON THE RIGHT, ALWAYS. Written into MANTRA_MANIFEST as a standing rule rather
            // than decided again per screen: a way out that moves between screens is a way out
            // that has to be looked for, and looking for the exit is the moment an interface
            // stops being trusted.
            // THE VERSION IS THE UPDATE CONTROL. It is already the one place in the app that
            // says which build this is, and "is there a newer one" is the only other question
            // anybody asks about a version number. A separate button would be a second control
            // for one idea.
            //
            // Press once to ask. Press again, when it says one is ready, to fetch it.
            val current = BuildConfig.VERSION_NAME.toIntOrNull() ?: 0
            Text(
                text = Updates.describe(update, current),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = when (update) {
                        is UpdateState.Available -> Color(colour)
                        is UpdateState.Failed -> RECORD_RED
                        else -> GLYPH_SECOND
                    },
                    fontSize = 12.sp,
                ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clickable {
                        val state = update
                        if (state is UpdateState.Available) {
                            UpdateCheck.download(context, state.url)
                        } else if (state !is UpdateState.Checking) {
                            UpdateCheck.check(current) { update = it }
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Box(Modifier.weight(1f))
            Glyph(Icons.Default.Close, "Close settings", Tone.HIGHLIGHT, 32.dp, onPress = onClose)
        }

        // ─────────────────────────────────────────────────────────────────────────────────────
        // FIVE TABS, because five unrelated jobs had been sharing one panel.
        //
        // LOOK is adjustment: colour and weight, things you change once and rarely return to.
        // VOICE is machinery: recording the commands and proving they are heard. Mixing them put
        // a swatch grid between a person and the pad they were trying to press, and made the
        // panel taller than a landscape phone in the process.
        //
        // The tab that is not selected is dim, not hidden — same rule as the transport, because
        // a control that vanishes takes its own location with it.
        // ─────────────────────────────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = gap),
            horizontalArrangement = Arrangement.Center,
        ) {
            SettingsTab.entries.forEach { t ->
                // From the enum, so the row can never disagree with the order.
                Tab(t.name, tab == t, colour) { onTab(t) }
            }
        }

        if (tab == SettingsTab.LAP) {
            RowLabel("LAP COUNTER", colour)
            Help(
                "A number above the digits, counting lengths. Tap it or say lap to add one. " +
                    "Stop clears it. Choose a pool length and it counts metres as well.",
                colour,
            )
            Row(
                modifier = Modifier.padding(top = gap, bottom = gap),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                val half = (gridWidth - gap) / 2
                LapCell("off", chosen = !lapOn, colour = colour, width = half) { onLapOn(false) }
                LapCell("on", chosen = lapOn, colour = colour, width = half) { onLapOn(true) }
            }

            // THE POOL LENGTH, SET RATHER THAN CHOSEN. No presets: pools are 25 and 50 in most of
            // the world, 20 in older municipal baths, 33 and a third in some and 25 yards in
            // others, and a list of two guesses is a list that is wrong for the person who needed
            // the setting. It is typed once and never again.
            RowLabel("POOL LENGTH", colour)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = gap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Nudge("\u2212", colour) { onLapMetres(lapNudge(lapMetres, up = false)) }
                Text(
                    // ZERO IS COUNT ONLY, which is why one control does both. Nudging below one
                    // metre gives a counter that counts lengths and says nothing about distance.
                    text = if (lapMetres <= 0) "count only" else "$lapMetres m",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(colour),
                        fontSize = if (lapMetres <= 0) 18.sp else 34.sp,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
                Nudge("+", colour) { onLapMetres(lapNudge(lapMetres, up = true)) }
            }
            Help(
                "A metre at a time, because a pool is whatever length it is. " +
                    "Take it below one and it counts lengths only.",
                colour,
            )
            return@Column
        }

        // THE STOPWATCH'S OWN COUNT-IN, and it is the only thing a stopwatch has to configure.
        // That is as it should be: a stopwatch counts up from nothing and there is nothing else
        // to say about it.
        if (tab == SettingsTab.WATCH) {
            RowLabel("COUNT-IN", colour)
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = gap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Nudge("\u2212", colour) { onPreroll(prerollNudge(preroll, up = false)) }
                Text(
                    text = if (preroll <= 0) "off" else "$preroll s",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(colour),
                        fontSize = if (preroll <= 0) 20.sp else 34.sp,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
                Nudge("+", colour) { onPreroll(prerollNudge(preroll, up = true)) }
            }
            Help(
                "This clock only. The stopwatch and the timer keep their own. " +
                    "It ends with the word recorded under VOICE.",
                colour,
            )
            return@Column
        }

        if (tab == SettingsTab.TIMER) {
            // ─────────────────────────────────────────────────────────────────────────────────
            // THE DURATION, AND SIX BUTTONS THAT EACH SAY WHAT THEY DO.
            //
            // Baba, 21.9.2026: "left and right of the entry box for time, add 2 pluses and 2
            // minuses ... plus is pushing the stopwatch 30 seconds forward or backwards, second
            // plus is pushing for 1 minute, and add one more ... third plus and minus is pushing
            // for 10 minutes." Three each side, thirty seconds, one minute, ten minutes.
            //
            // WHAT THIS REPLACES IS A SINGLE PAIR WHOSE STEP CHANGED UNDER YOUR THUMB — fifteen
            // seconds under two minutes, thirty under ten, a minute above. That was written to
            // save presses and it cost something worse: the same button did a different thing
            // depending on a number you had to be looking at to predict. Now the step is on the
            // face of the button, and nothing about the number you are on changes what a press
            // will do.
            //
            // SMALLEST NEAREST THE DIGITS, growing outwards on both sides, so the row is
            // symmetrical and the reach matches the size of the jump.
            //
            // EVERY CELL IS A WEIGHT, NOT A FIXED WIDTH. Seven things across a panel that is as
            // narrow as the phone is: a fixed width fits the phone it was measured on and pushes
            // the ten-minute button off the edge of a smaller one.
            // ─────────────────────────────────────────────────────────────────────────────────
            RowLabel("DURATION", colour)
            Row(
                modifier = Modifier.width(gridWidth).padding(bottom = gap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                // Reversed for the minus side so the biggest jump is furthest out, and generated
                // from TIMER_STEPS rather than typed, so the two sides can never disagree about
                // what the second button is worth.
                TIMER_STEPS.reversed().forEach { step ->
                    Step("\u2212", stepLabel(step), colour, Modifier.weight(1f)) {
                        onTimerSeconds(timerShift(timerSeconds, -step))
                    }
                }
                Text(
                    text = Face.format(timerSeconds * 1000L, Display.SINGLE),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(colour),
                        fontSize = 26.sp,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1.8f),
                )
                TIMER_STEPS.forEach { step ->
                    Step("+", stepLabel(step), colour, Modifier.weight(1f)) {
                        onTimerSeconds(timerShift(timerSeconds, step))
                    }
                }
            }
            Help(
                "Half a minute, a minute, ten minutes — the amount is written on the button, " +
                    "so a press does the same thing wherever the number already is.",
                colour,
            )

            // ─────────────────────────────────────────────────────────────────────────────────
            // HIS PRESETS. THERE ARE NO OTHERS. Baba, 21.9.2026: "inside the settings, remove
            // timer presets — all timer presets are defined by the user, so he can define
            // multiple presets. Under the first preset, add plus so he can add multiples."
            //
            // What stood here was six durations chosen in advance and one "save as preset" slot
            // beside them that could hold exactly one more. The six were guesses and the one was
            // a consolation. Both are gone.
            //
            // THE PLUS IS THE LAST CELL RATHER THAN A SEPARATE BUTTON, so it sits immediately
            // after the presets — under the first one while there is only one, and after the
            // last one forever after. It is where the next preset will appear, which is the only
            // place a control that makes one should be.
            //
            // A LONG PRESS REMOVES. It is not on a tap, because a tap is how a preset is USED
            // and the two would be one finger apart; and it needs no confirmation, because the
            // plus that put it there is in the same row and puts it back.
            // ─────────────────────────────────────────────────────────────────────────────────
            RowLabel(if (presets.isEmpty()) "PRESETS — NONE YET" else "PRESETS", colour)
            val perRow = 3
            val presetW = (gridWidth - gap * (perRow - 1)) / perRow
            // The plus rides at the end of the same list, so it wraps with the presets and can
            // never be stranded on a row of its own by a count nobody thought about.
            val cells: List<Int?> = presets + listOf(null)
            cells.chunked(perRow).forEach { row ->
                Row(
                    modifier = Modifier.width(gridWidth).padding(bottom = gap),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    row.forEach { seconds ->
                        if (seconds == null) {
                            // AT THE CEILING IT GOES DEAD RATHER THAN DISAPPEARING, the same rule
                            // the transport has always followed: a control that vanishes takes
                            // its position with it and you are left wondering what you did.
                            PresetCell(
                                sample = if (presets.size >= PRESETS_MAX) "full" else "+",
                                chosen = false,
                                live = presets.size < PRESETS_MAX,
                                colour = colour,
                                width = presetW,
                                onPress = onAddPreset,
                                onLongPress = {},
                            )
                        } else {
                            PresetCell(
                                // Shown as the clock will read it. "05:00" is what you will be
                                // looking at; "five minutes" is a description of it.
                                sample = Face.format(seconds * 1000L, Display.SINGLE),
                                chosen = seconds == timerSeconds,
                                live = true,
                                colour = colour,
                                width = presetW,
                                onPress = { onTimerSeconds(seconds) },
                                onLongPress = { onRemovePreset(seconds) },
                            )
                        }
                    }
                }
            }
            Help(
                "Set a duration above, then press + to keep it. Press a preset to use it, " +
                    "hold it to remove it. Up to " + PRESETS_MAX + ".",
                colour,
            )

            // COUNT-IN LIVES HERE NOW. It belongs to whichever clock is about to start, and the
            // timer is a clock — it already worked in both modes, it was simply filed under the
            // one place nobody would look for it.
            RowLabel("COUNT-IN", colour)
            Help("A pause before the clock starts, ending with the word you record.", colour)
        // THE COUNTDOWN, and beside it the word that plays when it ends.
        //
        // The GO cell is a recorder, not a setting: press it and say whatever you want to hear.
        // It sits here rather than in the VOICE tab because it is not a command — nothing ever
        // matches against it, it is only played — and putting it among the templates would
        // invite it to be treated as one.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = gap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Nudge("\u2212", colour) { onPreroll(prerollNudge(preroll, up = false)) }
                Text(
                    text = if (preroll <= 0) "off" else "$preroll s",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(colour),
                        fontSize = if (preroll <= 0) 20.sp else 34.sp,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
                Nudge("+", colour) { onPreroll(prerollNudge(preroll, up = true)) }
            }
            Help(
                "This clock only. The stopwatch and the timer keep their own. " +
                    "It ends with the word recorded under VOICE.",
                colour,
            )
            return@Column
        }

        if (tab == SettingsTab.VOICE) {
            VoicePads(
                samplerMode = samplerMode,
                onSamplerMode = { samplerMode = it; confirming = null },
                names = names,
                onNames = onNames,
                live = live,
                editing = editing,
                onEditing = { editing = it },
                onNote = { note = it },
                width = gridWidth,
                height = maxHeight - header - gap * 3,
                colour = colour,
                level = level,
                lit = lit,
                now = tickNow,
                scores = scores,
                recordingFor = recordingFor,
                note = note,
                store = store,
                onPress = ::onPress,
                onClear = { c, s -> store.clearSample(c, s); onRecorded() },
            )
            return@Column
        }

        Palette.SWATCHES.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.padding(bottom = gap),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                row.forEach { swatch ->
                    Box(
                        Modifier
                            .size(cell)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(swatch)),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(onClick = { onColour(swatch) }, modifier = Modifier.size(cell)) {
                            if (swatch == colour) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Chosen",
                                    tint = Color(Palette.markOn(swatch)),
                                    modifier = Modifier.size(cell * 0.6f),
                                )
                            }
                        }
                    }
                }
            }
        }

        // A CAPTION ON EACH ROW. Two rows of near-identical cells with nothing on screen saying
        // which is which is not a minimal interface, it is an unlabelled one. Baba read the four
        // boxes as "normal, fat, and a third I do not understand" and there was no way he could
        // have read them otherwise.
        //
        // Normal or bold, shown in the thing they describe. A row reading "Bold" set in bold
        // tells you less than the digits themselves set in bold, which is what is being chosen.
        RowLabel("WEIGHT", colour)
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            val half = (gridWidth - gap) / 2
            WeightCell("88:88:88", Weight.NORMAL, weight, colour, half, onWeight)
            WeightCell("88:88:88", Weight.BOLD, weight, colour, half, onWeight)
        }

        // DISPLAY, shown the way the weight is shown: in the thing it describes. A cell reading
        // "MULTI" tells you a word; a cell reading 88:88:88 beside one reading 88 tells you what
        // the screen is about to look like, which is the actual question.
        RowLabel("FIELDS", colour)
        Row(
            modifier = Modifier.padding(top = gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            val half = (gridWidth - gap) / 2
            DisplayCell("88:88:88", Display.MULTI, display, colour, weight, half, onDisplay)
            DisplayCell("88", Display.SINGLE, display, colour, weight, half, onDisplay)
        }

        // ─────────────────────────────────────────────────────────────────────────────────────
        // THE VOICE TESTER.
        //
        // Baba asked to SEE whether it is triggering, and that is the right thing to ask for:
        // voice control that silently does nothing is indistinguishable from a broken
        // microphone, a missing recogniser, a refused permission and a word the matcher does not
        // know. All four fail the same way and only one of them is a bug.
        //
        // So this row shows four separate things rather than one green light: whether the
        // microphone is on, whether it can hear anything (the meter), what it heard (the words),
        // and whether that became a command (the name, or a dash). Any one of them being wrong
        // points at a different cause.
        //
        // The words come from Heard.primary, which is the first entry of the matcher's own list,
        // so the reminder can never name a word the matcher would refuse.
        // ─────────────────────────────────────────────────────────────────────────────────────
        Column(
            modifier = Modifier.width(gridWidth).height(tipRow).padding(top = gap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(
                    icon = if (listening) Icons.Filled.Mic else Icons.Outlined.Mic,
                    label = if (listening) "Stop listening" else "Listen for commands",
                    tone = if (listening) Tone.PRIMARY else Tone.SECONDARY,
                    size = 28.dp,
                ) { onListening(!listening) }

                // TTT MINI'S METER, ported in MaMeter.kt. dB domain, 70ms tween, peak hold on
                // its own 60ms clock, coloured green under -12, orange to -3, red above.
                Box(Modifier.padding(horizontal = gap).weight(1f)) {
                    MaScopeMeter(level = level, tint = Color(colour))
                }

                Text(
                    text = voiceState,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        color = GLYPH_SECOND,
                        fontSize = 11.sp,
                    ),
                    maxLines = 1,
                    softWrap = false,
                )
            }

            // THE COUNTERS. This line is here so that a report can be a fact rather than "it
            // does not work", which is all anybody can say about a microphone from the outside.
            //
            // Sessions climbing several a second is a restart storm, and it is also the sound
            // of the recogniser being started and killed — the on-off noise. Rms stuck at zero
            // while sessions climb means it never got as far as opening the microphone, so a
            // still meter is not a broken meter. Those two numbers tell the difference without
            // anybody having to guess, and the error name can be read out loud.
        }
    }
}

/** Which half of the settings panel is showing. */
/**
 * FOUR TABS, because three unrelated jobs had been sharing one panel.
 *
 * The colour grid, the sampler, the timer and the lap counter answer different questions and are
 * reached at different moments. Stacked together they made a panel taller than a landscape phone
 * — and, the thing Baba actually hit, they put two rows of identical-looking cells next to each
 * other with nothing on screen to say which was which.
 */
/**
 * ORDERED BY HOW OFTEN THEY ARE OPENED, not by when they were built.
 *
 * TIMER is set most days and often several times in one session. WATCH holds the other clock's
 * count-in. LAP is per swim. VOICE is nine recordings made once and rarely touched again. LOOK is
 * a colour chosen once and then lived with for months.
 *
 * The order was LOOK first purely because the colour grid was the first thing this panel ever
 * held, which is a fact about the repository's history and not about anybody's day.
 */
enum class SettingsTab { TIMER, WATCH, LAP, VOICE, LOOK }

/**
 * The caption above a row of cells.
 *
 * Small, dim, and set in the same face as everything else. It is not a heading competing for
 * attention — it is the one word that turns four identical boxes into two questions.
 */
@Composable
private fun RowLabel(text: String, colour: Long) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            color = Color(colour).copy(alpha = 0.55f),
            fontSize = 9.sp,
        ),
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
    )
}

/**
 * A sentence explaining what a setting does.
 *
 * This app has resisted prose for thirty versions and it was right to. But a lap counter is not
 * self-evident from a row of four boxes, and a control nobody understands is a control nobody
 * uses — which is a worse outcome than a line of small grey text in a panel you opened on
 * purpose. Two lines at most, and only where the cells cannot speak for themselves.
 */
@Composable
private fun Help(text: String, colour: Long) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            color = Color(colour).copy(alpha = 0.38f),
            fontSize = 9.sp,
            lineHeight = 12.sp,
        ),
        maxLines = 3,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
}

/**
 * How a step is written on the face of a button: "30s", "1m", "10m".
 *
 * IT IS COMPUTED, NOT TYPED, so TIMER_STEPS is the only place the three amounts exist. Typing
 * "10m" beside a step of 600 works until somebody changes the step and not the label, and then
 * the button lies about what it does — which is the one thing a control on this screen may never
 * do.
 */
private fun stepLabel(seconds: Int): String =
    if (seconds % 60 == 0) "${seconds / 60}m" else "${seconds}s"

/**
 * One of the six steps: the sign, and under it the amount it moves.
 *
 * TWO LINES RATHER THAN ONE. "−30s" on a single line is four characters at a size that fits six
 * of them across a phone, which is a size nobody reads. The sign is the thing the hand aims at
 * and it gets the space; the amount is the thing the eye checks once and then remembers where it
 * is, and it can be small.
 *
 * The width comes in as a weight from the row, never as a number, so seven cells always fit.
 */
@Composable
private fun Step(
    mark: String,
    amount: String,
    colour: Long,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
) {
    Column(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(PANEL_IDLE)
            .clickable { onPress() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = mark,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                color = Color(colour),
                fontSize = 20.sp,
            ),
            maxLines = 1,
        )
        Text(
            text = amount,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                color = Color(colour).copy(alpha = 0.55f),
                fontSize = 9.sp,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * One saved duration, or the plus that makes another.
 *
 * A COUSIN OF LapCell RATHER THAN THE SAME THING, and the difference is the long press. LapCell
 * is built on IconButton, which has no second gesture, and bolting one on would give every cell
 * in the panel a hold action nobody asked for — including the ones where holding should do
 * nothing at all.
 *
 * `live` dims the cell without removing it, which is how the plus says "no room" at the ceiling.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetCell(
    sample: String,
    chosen: Boolean,
    live: Boolean,
    colour: Long,
    width: Dp,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        Modifier
            .size(width = width, height = 44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (chosen) PANEL_CHOSEN else PANEL_IDLE)
            .combinedClickable(
                enabled = live,
                onClick = onPress,
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = sample,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                color = when {
                    !live -> GLYPH_OFF
                    chosen -> Color(colour)
                    else -> GLYPH
                },
                fontSize = 13.sp,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The minus and the plus. Big enough to hit without looking, which is the whole job. */
@Composable
private fun Nudge(mark: String, colour: Long, onPress: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(PANEL_IDLE)
            .clickable { onPress() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = mark,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                color = Color(colour),
                fontSize = 24.sp,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun Tab(label: String, selected: Boolean, colour: Long, onPress: () -> Unit) {
    Text(
        text = label,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            // WHITE WHEN NOT CHOSEN, COLOURED WHEN CHOSEN — and that is the opposite of the rest
            // of this app, deliberately.
            //
            // Everywhere else, dim means "not the thing you want" and the eye is steered towards
            // one control. A tab row is not that: every tab is somewhere you might be going, and
            // a name you cannot read is not a choice you can make. At 12% these were invisible on
            // a screen at low brightness, which is most of the time on a display designed to be
            // black.
            //
            // So the tabs are all legible and the CHOSEN one is marked by colour rather than by
            // the others being hidden.
            color = if (selected) Color(colour) else Color.White,
            fontSize = 12.sp,
        ),
        maxLines = 1,
        modifier = Modifier
            .clickable { onPress() }
            .padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

/**
 * THE SAMPLER, laid out the way a sampler is laid out.
 *
 * A three by three grid of pads. A row is a command, a column is one of its three takes. Every
 * pad carries the WAVEFORM of what is on it, because a pad that says only "filled" tells you a
 * recording exists while a pad with a shape on it tells you which recording, whether the word is
 * centred, and whether what you caught was a word at all rather than a cough at one end. On an
 * Akai the waveform is not decoration; it is how you know what is under your finger.
 *
 * ARM AT THE TOP, WITH THE METER BESIDE IT, because those are the two things you look at while
 * recording: is it armed, and is the signal arriving. Armed, a pad press records into that pad.
 * Disarmed, the pads are a display and the row that matches lights when you speak.
 *
 * THE ROW SCORE IS A NUMBER, not a light. A light says yes or no; a number says how close, which
 * is the difference between "it did not work" and "it was 0.58 and the threshold is 0.55".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoicePads(
    samplerMode: SamplerMode,
    onSamplerMode: (SamplerMode) -> Unit,
    names: Map<Control, String>,
    onNames: (Map<Control, String>) -> Unit,
    live: FloatArray,
    editing: Control?,
    onEditing: (Control?) -> Unit,
    onNote: (String) -> Unit,
    width: Dp,
    height: Dp,
    colour: Long,
    level: Float,
    lit: Lit,
    now: Long,
    scores: List<Pair<Control, Double>>,
    recordingFor: Pair<Control, Int>?,
    note: String,
    store: Store,
    onPress: (Control, Int) -> Unit,
    onClear: (Control, Int) -> Unit,
) {
    val gap = 4.dp
    val rows = Control.entries.size * Store.SAMPLES
    val header = 26.dp
    val footer = 18.dp
    // ONE SAMPLE, ONE LINE. Nine of them, each as tall as the space allows. A three by three grid
    // of small squares is a keyboard; nine full-width lines is a sample list, which is what this
    // is — and it is the shape that leaves room for a waveform you can actually read.
    val rowH = ((height - header - footer - gap * (rows + 1)) / rows).coerceIn(18.dp, 46.dp)

    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {

        Row(
            modifier = Modifier.fillMaxWidth().height(header),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    recordingFor != null -> "SPEAK"
                    else -> "TAP A LINE TO RECORD"
                },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = if (recordingFor != null) RECORD_RED else GLYPH_SECOND,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(end = gap * 2),
            )
            Box(Modifier.weight(1f)) { MaScopeMeter(level = level, tint = Color(colour)) }

            // THE MODE, AND IT IS NEVER HIDDEN. A press on a line means two different things
            // depending on this, and one of them destroys a recording. SAMPLE_PLAYER accepts the
            // same risk on the same terms: the mode is a visible control, and it is the only thing
            // between listening to a take and recording over it.
            Text(
                text = if (samplerMode == SamplerMode.RECORD) "REC" else "LISTEN",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = if (samplerMode == SamplerMode.RECORD) RECORD_RED else Color(colour),
                    fontSize = 11.sp,
                ),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clickable {
                        onSamplerMode(
                            if (samplerMode == SamplerMode.RECORD) SamplerMode.LISTEN
                            else SamplerMode.RECORD
                        )
                    }
                    .padding(start = gap, end = 2.dp),
            )
        }

        Control.entries.forEach { control ->
            val on = lit.isLit(control, now)
            val score = scores.firstOrNull { it.first == control }?.second
            for (slot in 0 until Store.SAMPLES) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = gap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // THE NAME IS THE RENAME CONTROL. Tap it and type the word you would rather
                    // say. It sits on the first of the control's three lines only — repeating it
                    // three times is three times the reading for the same fact.
                    //
                    // The whole tab then reads as a cheat sheet of the words that actually work,
                    // which is the point: a list of commands you cannot edit is a list you have to
                    // remember, and a list you edit somewhere else is a list that goes out of date.
                    if (slot == 0 && editing == control) {
                        NameField(
                            initial = Vocabulary.display(control, names),
                            colour = colour,
                            onDone = { word ->
                                val why = Vocabulary.validate(control, word, names - control)
                                if (why != null) {
                                    onNote(why)
                                } else {
                                    onNames(names + (control to word.trim().lowercase()))
                                    onNote("say \"" + word.trim().lowercase() + "\"")
                                }
                                onEditing(null)
                            },
                            onCancel = { onEditing(null) },
                        )
                    } else Text(
                        text = if (slot == 0) Vocabulary.display(control, names).uppercase() else "",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            color = if (on) Color(colour) else GLYPH_SECOND,
                            fontSize = 12.sp,
                        ),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .width(58.dp)
                            .clickable(enabled = slot == 0) { onEditing(control) },
                    )
                    Pad(
                        samples = store.loadSample(control, slot),
                        live = if (recordingFor == Pair(control, slot)) live else FloatArray(0),
                        recording = recordingFor == Pair(control, slot),
                        lit = on,
                        colour = colour,
                        height = rowH,
                        modifier = Modifier.weight(1f),
                        onPress = { onPress(control, slot) },
                        onLongPress = { onClear(control, slot) },
                    )
                    Text(
                        // The score belongs to the command, so it sits on the command's own line.
                        text = if (slot == 0) score?.let { "%.2f".format(it) } ?: "--" else "",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                slot != 0 -> GLYPH_OFF
                                on -> Color(colour)
                                score == null -> GLYPH_OFF
                                else -> GLYPH_SECOND
                            },
                            fontSize = 11.sp,
                        ),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.width(42.dp).padding(start = gap),
                    )
                }
            }
        }

        Text(
            text = note,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                color = if (note.contains("again") || note.contains("loud") || note.contains("short"))
                    RECORD_RED else GLYPH_OFF,
                fontSize = 10.sp,
            ),
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.height(footer),
        )
    }
}

/**
 * The one place this app has a keyboard, and it is here reluctantly.
 *
 * Everything else is chosen from what is on the screen, because a keyboard on a phone is the
 * slowest control there is and this app is read across a room. A command name cannot be chosen
 * from a list, though — the whole point is that it is YOUR word — so text entry is the only
 * honest answer.
 *
 * It commits on Done and abandons on anything else, and the field disappears either way. A field
 * that stays open after you have finished with it is a field you have to dismiss.
 */
@Composable
private fun NameField(
    initial: String,
    colour: Long,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            color = Color(colour),
            fontSize = 12.sp,
        ),
        cursorBrush = SolidColor(Color(colour)),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(text) }),
        modifier = Modifier
            .width(58.dp)
            .focusRequester(focus)
            .onFocusChanged { if (!it.isFocused) onCancel() },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pad(
    samples: ShortArray?,
    live: FloatArray,
    recording: Boolean,
    lit: Boolean,
    colour: Long,
    height: Dp,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
) {
    // While recording, the shape IS the live one — drawn as it arrives rather than after it stops.
    val shape = if (recording) live else remember(samples?.size, samples?.firstOrNull()) {
        if (samples == null) FloatArray(0) else waveform(samples, 96)
    }
    val edge = when {
        recording -> RECORD_RED
        lit && shape.isNotEmpty() -> Color(colour)
        shape.isNotEmpty() -> GLYPH_SECOND
        else -> GLYPH_OFF
    }

    // ALWAYS PRESSABLE. There is no arm mode any more: a press starts a capture, and a long press
    // clears the line. Nothing is ever inert, so nothing has to be explained.
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(3.dp))
            .background(if (recording) RECORD_RED.copy(alpha = 0.15f) else PANEL_IDLE)
            .border(1.dp, edge, RoundedCornerShape(3.dp))
            .combinedClickable(onClick = onPress, onLongClick = onLongPress),
        contentAlignment = Alignment.Center,
    ) {
        if (shape.isEmpty()) {
            Text(
                text = if (recording) "\u2026" else "empty",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = if (recording) RECORD_RED else GLYPH_OFF,
                    fontSize = 10.sp,
                ),
                maxLines = 1,
            )
        } else {
            Canvas(Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 4.dp)) {
                val mid = this.size.height / 2f
                val step = this.size.width / shape.size
                shape.forEachIndexed { i, v ->
                    val h = (mid * v).coerceAtLeast(0.5f)
                    drawRect(
                        color = edge,
                        topLeft = Offset(i * step, mid - h),
                        size = Size(step * 0.8f, h * 2f),
                    )
                }
            }
        }
    }
}

/**
 * The GO recorder. Press it, say the word, it stops when you stop — the same rule as every other
 * capture in this app, so "press and say it" means one thing everywhere.
 *
 * Hollow when there is nothing recorded, solid when there is, red while recording. Same language
 * as the microphone and the sample lines.
 */
@Composable
private fun GoCell(
    width: Dp,
    colour: Long,
    context: android.content.Context,
    recordedTick: Int,
    onRecorded: () -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    val exists = remember(recordedTick, busy) { GoSound.exists(context) }
    Box(
        Modifier
            .size(width = width, height = 44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (busy) RECORD_RED.copy(alpha = 0.15f) else PANEL_IDLE),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = {
                if (busy) return@IconButton
                busy = true
                GoSound.record(context) { ok ->
                    busy = false
                    if (ok) onRecorded()
                }
            },
            modifier = Modifier.size(width = width, height = 44.dp),
        ) {
            Text(
                text = when {
                    busy -> "say it"
                    exists -> "GO \u25CF"
                    else -> "GO \u25CB"
                },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        busy -> RECORD_RED
                        exists -> Color(colour)
                        else -> GLYPH
                    },
                    fontSize = 11.sp,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun LapCell(
    sample: String,
    chosen: Boolean,
    colour: Long,
    width: Dp,
    onPress: () -> Unit,
) {
    Box(
        Modifier
            .size(width = width, height = 44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (chosen) PANEL_CHOSEN else PANEL_IDLE),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onPress,
            modifier = Modifier.size(width = width, height = 44.dp),
        ) {
            Text(
                text = sample,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = if (chosen) Color(colour) else GLYPH,
                    fontSize = 11.sp,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun DisplayCell(
    sample: String,
    represents: Display,
    current: Display,
    colour: Long,
    weight: Weight,
    width: Dp,
    onDisplay: (Display) -> Unit,
) {
    val chosen = represents == current
    Box(
        Modifier
            .size(width = width, height = 52.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (chosen) PANEL_CHOSEN else PANEL_IDLE),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = { onDisplay(represents) },
            modifier = Modifier.size(width = width, height = 52.dp),
        ) {
            Text(
                text = sample,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (weight == Weight.BOLD) FontWeight.Bold else FontWeight.Normal,
                    color = if (chosen) Color(colour) else GLYPH,
                    // The SINGLE cell is set larger, because being larger IS what it does.
                    fontSize = if (represents == Display.SINGLE) 30.sp else 20.sp,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun WeightCell(
    sample: String,
    represents: Weight,
    current: Weight,
    colour: Long,
    width: Dp,
    onWeight: (Weight) -> Unit,
) {
    val chosen = represents == current
    Box(
        Modifier
            .size(width = width, height = 52.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (chosen) PANEL_CHOSEN else PANEL_IDLE),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = { onWeight(represents) },
            modifier = Modifier.size(width = width, height = 52.dp),
        ) {
            Text(
                text = sample,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (represents == Weight.BOLD) FontWeight.Bold else FontWeight.Normal,
                    color = if (chosen) Color(colour) else GLYPH,
                    fontSize = 22.sp,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * THE DIGITS, AS LARGE AS THE SPACE ALLOWS.
 *
 * The size is found by binary search against the real text measurer rather than by a formula,
 * because the formula would be a guess about a font it has never measured.
 *
 * IT IS MEASURED ON A PROBE OF THE SAME LENGTH, NOT ON THE TEXT ITSELF, and that is the whole
 * defence against the commonest way to get a stopwatch wrong. In a monospaced face every glyph
 * has the same advance, so a string of the same length is exactly the same width, which means
 * the computed size depends on the LENGTH of the string and nothing else. 00:00 and 59:59 are
 * drawn at the same size and nothing shuffles sideways as it counts.
 *
 * With the tenth gone the string is five glyphs rather than seven, so every digit is about a
 * third larger for free. The size changes at exactly one moment, when it grows to seven at one
 * hour.
 *
 * THE WEIGHT IS PART OF THE MEASUREMENT, not applied afterwards. Bold digits are wider, and
 * sizing a normal face then drawing a bold one is how a layout ends up over the edge.
 */
@Composable
private fun Digits(
    text: String,
    colour: Color,
    weight: Weight,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = if (weight == Weight.BOLD) FontWeight.Bold else FontWeight.Normal,
        color = colour,
    )

    val fontSize: TextUnit = remember(text.length, width, height, density, weight) {
        val wPx = with(density) { width.toPx() }
        val hPx = with(density) { height.toPx() }
        val probe = AnnotatedString("8".repeat(text.length))
        var lo = 8f
        var hi = 900f
        // 20 halvings of a fixed range settles to well under a hundredth of a point, and the
        // count is fixed so this loop is bounded by construction.
        repeat(20) {
            val mid = (lo + hi) / 2f
            val r = measurer.measure(probe, style.copy(fontSize = mid.sp), maxLines = 1, softWrap = false)
            if (r.size.width <= wPx && r.size.height <= hPx) lo = mid else hi = mid
        }
        lo.sp
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text = text, style = style.copy(fontSize = fontSize), maxLines = 1, softWrap = false)
    }
}
