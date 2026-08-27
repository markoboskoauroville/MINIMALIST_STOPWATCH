package com.mantra.stopwatch

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ─────────────────────────────────────────────────────────────────────────────────────────────
// THE COLOURS. There are six of them and five are greys.
//
// The digits are the only white thing on the screen. Everything else is measured DOWN from
// them, and the numbers below were chosen by rendering the glyph beside the digits at each
// step of the band and looking at it, not by picking a percentage that sounded right.
//
//   55%   the glyph. At 45 it starts to sink into the black at launcher-adjacent sizes; at 65
//         it begins to read as a second white thing and competes with the number. 55 is
//         unmistakably a control and unmistakably not the digits
//   18%   the circle. It marks where to press and does not announce itself. An outline rather
//         than a fill, because a filled disc at any brightness that is visible is a shape with
//         weight, and there are three of them in a row
//   22%   the glyph when the button can do nothing. Dimmer still, as asked. It is still
//         plainly there — never hidden, because a control that disappears moves the layout,
//         and a stopwatch whose buttons shuffle is worse than one with a dim button
//   10%   the circle when the button can do nothing
// ─────────────────────────────────────────────────────────────────────────────────────────────

private val DIGITS = Color.White
private val GLYPH = Color(0xFF8C8C8C)          // 55%
private val GLYPH_OFF = Color(0xFF383838)      // 22%
private val RING = Color(0xFF2E2E2E)           // 18%
private val RING_OFF = Color(0xFF1A1A1A)       // 10%
private val BACKGROUND = Color.Black

private val EDGE = 12.dp                       // the margin at the screen edge, everywhere
private val LOCK_ZONE = 56.dp                  // the height the orientation button reserves

class MainActivity : ComponentActivity() {

    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        // Edge to edge, with the system bars left where they are rather than hidden. Hiding them
        // would buy about 24dp of height that the digits do not need, since they are limited by
        // the WIDTH of the screen in both orientations, and it would take away the phone's own
        // clock and battery while a measurement is running. The background is black, so the bars
        // sit on black and read as part of the screen. Say the word and this becomes one line.
        enableEdgeToEdge()

        setContent { Screen(store, this) }
    }
}

@Composable
private fun Screen(store: Store, activity: ComponentActivity) {

    // THE STATE. Loaded once, from the file, through the reboot detectors.
    var state by remember { mutableStateOf(store.load()) }
    var locked by remember { mutableStateOf(store.locked) }

    // `now` exists only so the digits can be recomputed. It is not the elapsed time and nothing
    // accumulates into it: every frame asks the pure model what has passed, by subtraction.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    fun commit(next: Stopwatch) {
        state = next
        now = SystemClock.elapsedRealtime()
        store.save(next)
    }

    val elapsed = state.elapsed(now)

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // THE TICK. Redraws when the digits actually change, not as fast as the display can go.
    //
    // untilNextTenth is bounded to 1..100ms and can never return zero, so this loop cannot spin.
    // `isActive` rather than `true`: the bound is the coroutine's own life, and writing it that
    // way means the gate that greps for unbounded loops sees an honest answer.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    LaunchedEffect(state) {
        if (state.phase != Phase.RUNNING) return@LaunchedEffect
        var sinceWrite = 0L
        while (isActive) {
            val step = Face.untilNextTenth(state.elapsed(SystemClock.elapsedRealtime()))
            delay(step)
            now = SystemClock.elapsedRealtime()
            // lastSeen has to be refreshed while running or the backwards-clock reboot detector
            // is comparing against an instant from whenever the last button was pressed. Ten
            // seconds is often enough to be useful and rare enough to be free.
            sinceWrite += step
            if (sinceWrite >= 10_000L) {
                sinceWrite = 0L
                store.save(state)
            }
        }
    }

    // A measurement in progress keeps the screen awake, whether it is running or frozen at a
    // figure somebody is about to read. Only a stopped stopwatch lets the phone sleep.
    DisposableEffect(state.phase) {
        val w = activity.window
        if (state.phase == Phase.STOPPED) {
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(locked) {
        activity.requestedOrientation =
            if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { }
    }

    // The last write before the process can be taken away. onStop is the last callback Android
    // promises, and Store.save uses commit rather than apply so the bytes are on disk when this
    // returns rather than queued behind a thread that may never run.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, state) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) store.save(state)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // THE SCREEN. Note what is NOT here: the background carries no click handler of any kind.
    // Tap-anywhere is gone deliberately, and its absence is the point rather than an omission.
    // ─────────────────────────────────────────────────────────────────────────────────────────
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(BACKGROUND)
            .safeDrawingPadding()
    ) {
        // maxWidth and maxHeight belong to BoxWithConstraintsScope, and that receiver is gone
        // the moment a Row or Column lambda is opened. Read them once, here, into plain values.
        val screenW = maxWidth
        val screenH = maxHeight
        val landscape = screenW > screenH
        val text = Face.format(elapsed)

        if (landscape) {
            // The digits take the width they have and the buttons take the edge. The column is
            // centred in the space BELOW the lock button rather than in the whole height, which
            // is what keeps the two apart on a short screen.
            val column = 84.dp
            val available = screenH - LOCK_ZONE - EDGE
            val size = minOf(64.dp, (available - EDGE * 2) / 3)
            Row(Modifier.fillMaxSize()) {
                Digits(
                    text = text,
                    width = screenW - column - EDGE,
                    height = screenH - EDGE * 2,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Column(
                    modifier = Modifier.width(column).fillMaxHeight().padding(top = LOCK_ZONE),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Transport(state, size, EDGE, vertical = true) { commit(it) }
                }
            }
        } else {
            val strip = 108.dp
            Column(Modifier.fillMaxSize()) {
                Digits(
                    text = text,
                    width = screenW - EDGE * 2,
                    height = screenH - strip - LOCK_ZONE,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().height(strip),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Transport(state, 72.dp, EDGE, vertical = false) { commit(it) }
                }
            }
        }

        // Top right in both orientations, over everything, so it never moves.
        Circle(
            icon = if (locked) Icons.Default.ScreenRotation else Icons.Default.ScreenLockRotation,
            label = if (locked) "Follow the phone" else "Lock this orientation",
            enabled = true,
            size = 40.dp,
            ring = false,
            modifier = Modifier.align(Alignment.TopEnd).padding(EDGE),
        ) {
            locked = !locked
            store.locked = locked
        }
    }
}

/**
 * The three, in the audio-player order, and the same three in both orientations. `vertical` only
 * changes which way they are stacked; nothing appears, disappears or changes size between the
 * two layouts.
 */
@Composable
private fun Transport(
    state: Stopwatch,
    size: Dp,
    gap: Dp,
    vertical: Boolean,
    onChange: (Stopwatch) -> Unit,
) {
    val spacer = if (vertical) Modifier.height(gap) else Modifier.width(gap)

    Circle(Icons.Default.PlayArrow, "Play", state.canPlay(), size, true) {
        onChange(state.play(SystemClock.elapsedRealtime()))
    }
    Box(spacer)
    Circle(Icons.Default.Pause, "Pause", state.canPause(), size, true) {
        onChange(state.pause(SystemClock.elapsedRealtime()))
    }
    Box(spacer)
    Circle(Icons.Default.Stop, "Stop", state.canStop(), size, true) {
        onChange(state.stop())
    }
}

@Composable
private fun Circle(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    size: Dp,
    ring: Boolean,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
) {
    // enabled = false on IconButton does two things at once and both are wanted: it takes the
    // colour to the disabled tint, and it stops the press being delivered. Pressing a button
    // that can do nothing does nothing, and it looked like nothing would.
    IconButton(
        onClick = onPress,
        enabled = enabled,
        modifier = modifier
            .size(size)
            .then(
                if (ring) Modifier.border(
                    width = 1.5.dp,
                    color = if (enabled) RING else RING_OFF,
                    shape = CircleShape,
                ) else Modifier
            ),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = GLYPH,
            disabledContentColor = GLYPH_OFF,
        ),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.44f))
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
 * has the same advance, so a string of the same length is exactly the same width — which means
 * the computed size depends on the LENGTH of the string and nothing else. 00:00.0 and 59:59.9
 * are the same width and are drawn at the same size, so nothing shuffles sideways as it counts.
 *
 * The size therefore changes at exactly one moment: when the string grows from seven glyphs to
 * nine at one hour. It is one discrete step, an hour into a measurement, and it was chosen over
 * showing an hour field from zero, which would make the digits permanently smaller on every
 * ordinary use to defend against an hour that almost never arrives.
 */
@Composable
private fun Digits(text: String, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = TextStyle(fontFamily = FontFamily.Monospace, color = DIGITS)

    val fontSize: TextUnit = remember(text.length, width, height, density) {
        val wPx = with(density) { width.toPx() }
        val hPx = with(density) { height.toPx() }
        val probe = AnnotatedString("8".repeat(text.length))
        var lo = 8f
        var hi = 520f
        // 20 halvings of a 512-wide range settles to well under a hundredth of a point, and the
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
