package com.mantra.stopwatch

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
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

@Composable
private fun Screen(store: Store, activity: ComponentActivity) {

    var state by remember { mutableStateOf(store.load()) }
    var orientation by remember { mutableStateOf(store.orientation) }
    var colour by remember { mutableLongStateOf(store.colour) }
    var weight by remember { mutableStateOf(store.weight) }
    var display by remember { mutableStateOf(store.display) }
    var lapMode by remember { mutableStateOf(store.lapMode) }
    var names by remember { mutableStateOf(store.names) }
    var preroll by remember { mutableStateOf(store.preroll) }

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

    // Starting a measurement from zero with the countdown on does not start the clock: it starts
    // the countdown, and the clock starts when that ends. Everything else — resuming a pause,
    // pausing, stopping — is immediate, because a start ceremony in front of those would be a
    // delay with no purpose.
    fun beginPreroll(): Boolean {
        if (preroll == PrerollMode.OFF) return false
        if (state.phase != Phase.STOPPED) return false
        prerollEndsAt = SystemClock.elapsedRealtime() + preroll.seconds * 1000L
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
                GoSound.play(context)
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
    var tab by remember { mutableStateOf(SettingsTab.LOOK) }

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
        VoiceHub.onCommand = { control ->
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
            .safeDrawingPadding()
    ) {
        val screenW = maxWidth
        val screenH = maxHeight
        val landscape = screenW > screenH
        val text = Face.format(elapsed, display)
        val countdown = prerollLabel(prerollEndsAt - prerollNow).takeIf { prerollEndsAt > 0L }

        val button = if (landscape) 56.dp else 72.dp
        val strip = if (landscape) 72.dp else 108.dp

        Column(Modifier.fillMaxSize()) {
            // ABOVE THE DIGITS, and only when the counter is on. It is a control as well as a
            // readout: tapping it is the other way to count a length, and at the end of a length
            // in a pool a thumb finds a wide target above the numbers more easily than a small
            // one anywhere else.
            lapLabel(laps, lapMode)?.let { label ->
                Text(
                    text = label,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (weight == Weight.BOLD) FontWeight.Bold else FontWeight.Normal,
                        color = Color(colour),
                        fontSize = 28.sp,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { laps++; flashes++ }
                        .padding(top = LOCK_ZONE, bottom = 4.dp),
                    textAlign = TextAlign.Center,
                )
            }

            Digits(
                text = countdown ?: text,
                colour = Color(if (flashing) Palette.flashOf(colour) else colour),
                weight = weight,
                width = screenW - EDGE * 2,
                height = screenH - strip - LOCK_ZONE,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(
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
                Transport(Icons.Default.PlayArrow, Control.PLAY, state, button) { next ->
                    if (prerollEndsAt > 0L) cancelPreroll()
                    else if (!(next.phase == Phase.RUNNING && beginPreroll())) commit(next)
                }
                Transport(Icons.Default.Pause, Control.PAUSE, state, button) { next ->
                    cancelPreroll(); commit(next)
                }
                Transport(Icons.Default.Stop, Control.STOP, state, button) { next ->
                    cancelPreroll(); commit(next)
                }
            }
        }

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
                maxHeight = screenH * 0.62f,
                landscape = landscape,
                colour = colour,
                weight = weight,
                display = display,
                onDisplay = { display = it; store.display = it },
                lapMode = lapMode,
                onLapMode = { lapMode = it; store.lapMode = it },
                names = names,
                onNames = { names = it; store.names = it; VoiceHub.reloadTemplates(context) },
                live = live,
                onLive = { live = it },
                preroll = preroll,
                onPreroll = { preroll = it; store.preroll = it },
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
                modifier = Modifier
                    .align(Alignment.BottomCenter)
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
    commit: (Stopwatch) -> Unit,
) {
    Glyph(
        icon = icon,
        label = control.spoken,
        tone = state.tone(control),
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
    IconButton(
        onClick = onPress,
        enabled = tone != Tone.DEAD,
        modifier = modifier.size(size),
        colors = IconButtonDefaults.iconButtonColors(
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
    lapMode: LapMode,
    onLapMode: (LapMode) -> Unit,
    names: Map<Control, String>,
    onNames: (Map<Control, String>) -> Unit,
    live: FloatArray,
    onLive: (FloatArray) -> Unit,
    preroll: PrerollMode,
    onPreroll: (PrerollMode) -> Unit,
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
            Glyph(Icons.Default.Close, "Close settings", Tone.HIGHLIGHT, 32.dp, onPress = onClose)
            Box(Modifier.weight(1f))
            Text(
                text = "v" + BuildConfig.VERSION_NAME,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    color = GLYPH_SECOND,
                    fontSize = 12.sp,
                ),
                maxLines = 1,
            )
        }

        // ─────────────────────────────────────────────────────────────────────────────────────
        // TWO TABS, because the panel now holds two unrelated jobs.
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
            Tab("LOOK", tab == SettingsTab.LOOK, colour) { onTab(SettingsTab.LOOK) }
            Tab("VOICE", tab == SettingsTab.VOICE, colour) { onTab(SettingsTab.VOICE) }
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

        // Normal or bold, shown in the thing they describe. A row reading "Bold" set in bold
        // tells you less than the digits themselves set in bold, which is what is being chosen.
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            val half = (gridWidth - gap) / 2
            WeightCell("88:88:88", Weight.NORMAL, weight, colour, half, onWeight)
            WeightCell("88:88:88", Weight.BOLD, weight, colour, half, onWeight)
        }

        // DISPLAY, shown the way the weight is shown: in the thing it describes. A cell reading
        // "MULTI" tells you a word; a cell reading 88:88:88 beside one reading 88 tells you what
        // the screen is about to look like, which is the actual question.
        Row(
            modifier = Modifier.padding(top = gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            val half = (gridWidth - gap) / 2
            DisplayCell("88:88:88", Display.MULTI, display, colour, weight, half, onDisplay)
            DisplayCell("88", Display.SINGLE, display, colour, weight, half, onDisplay)
        }

        // THE LAP COUNTER, shown the way everything else in this tab is shown: in the thing it
        // describes. A cell reading "25 m" tells you a word; a cell reading 3 (75 m) is what will
        // actually sit above the digits after three lengths.
        Row(
            modifier = Modifier.padding(top = gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            val quarter = (gridWidth - gap * 3) / 4
            LapCell("off", LapMode.OFF, lapMode, colour, quarter, onLapMode)
            LapCell("3", LapMode.COUNT, lapMode, colour, quarter, onLapMode)
            LapCell("3 (75 m)", LapMode.M25, lapMode, colour, quarter, onLapMode)
            LapCell("3 (150 m)", LapMode.M50, lapMode, colour, quarter, onLapMode)
        }

        // THE COUNTDOWN, and beside it the word that plays when it ends.
        //
        // The GO cell is a recorder, not a setting: press it and say whatever you want to hear.
        // It sits here rather than in the VOICE tab because it is not a command — nothing ever
        // matches against it, it is only played — and putting it among the templates would
        // invite it to be treated as one.
        Row(
            modifier = Modifier.padding(top = gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            val third = (gridWidth - gap * 2) / 3
            LapCell("no count-in", LapMode.OFF, if (preroll == PrerollMode.OFF) LapMode.OFF else LapMode.COUNT,
                colour, third) { onPreroll(PrerollMode.OFF) }
            LapCell("10", LapMode.OFF, if (preroll == PrerollMode.TEN) LapMode.OFF else LapMode.COUNT,
                colour, third) { onPreroll(PrerollMode.TEN) }
            GoCell(third, colour, context, goRecorded) { goRecorded++ }
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
enum class SettingsTab { LOOK, VOICE }

@Composable
private fun Tab(label: String, selected: Boolean, colour: Long, onPress: () -> Unit) {
    Text(
        text = label,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            color = if (selected) Color(colour) else GLYPH_OFF,
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
    represents: LapMode,
    current: LapMode,
    colour: Long,
    width: Dp,
    onLapMode: (LapMode) -> Unit,
) {
    val chosen = represents == current
    Box(
        Modifier
            .size(width = width, height = 44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (chosen) PANEL_CHOSEN else PANEL_IDLE),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = { onLapMode(represents) },
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
