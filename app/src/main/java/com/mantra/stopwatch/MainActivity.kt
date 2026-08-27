package com.mantra.stopwatch

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

private val GLYPH = Color(0xFF666666)          // 40%
private val GLYPH_OFF = Color(0xFF292929)      // 16%
private val BACKGROUND = Color.Black
private val PANEL_CHOSEN = Color(0xFF1F1F1F)
private val PANEL_IDLE = Color(0xFF0D0D0D)

private val EDGE = 12.dp
private val LOCK_ZONE = 56.dp

class MainActivity : ComponentActivity() {

    private lateinit var store: Store

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        enableEdgeToEdge()
        setContent { Screen(store, this) }
    }
}

@Composable
private fun Screen(store: Store, activity: ComponentActivity) {

    var state by remember { mutableStateOf(store.load()) }
    var locked by remember { mutableStateOf(store.locked) }
    var colour by remember { mutableLongStateOf(store.colour) }
    var weight by remember { mutableStateOf(store.weight) }
    var settingsOpen by remember { mutableStateOf(false) }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    fun commit(next: Stopwatch) {
        state = next
        now = SystemClock.elapsedRealtime()
        store.save(next)
    }

    val elapsed = state.elapsed(now)

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

    DisposableEffect(locked) {
        activity.requestedOrientation =
            if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
        val text = Face.format(elapsed)

        val button = if (landscape) 56.dp else 72.dp
        val strip = if (landscape) 72.dp else 108.dp

        Column(Modifier.fillMaxSize()) {
            Digits(
                text = text,
                colour = Color(colour),
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
                Glyph(Icons.Default.PlayArrow, "Play", state.canPlay(), button) {
                    commit(state.play(SystemClock.elapsedRealtime()))
                }
                Glyph(Icons.Default.Pause, "Pause", state.canPause(), button) {
                    commit(state.pause(SystemClock.elapsedRealtime()))
                }
                Glyph(Icons.Default.Stop, "Stop", state.canStop(), button) {
                    commit(state.stop())
                }
            }
        }

        // The two corner controls. design-language.md 10: a row has two ends and a middle, and a
        // screen is read as weight before it is read as anything else. The gear balances the
        // orientation lock rather than piling up beside it. Both say what the next press does.
        Glyph(
            icon = if (settingsOpen) Icons.Default.Close else Icons.Default.Settings,
            label = if (settingsOpen) "Close settings" else "Settings",
            enabled = true,
            size = 40.dp,
            modifier = Modifier.align(Alignment.TopStart).padding(EDGE),
        ) { settingsOpen = !settingsOpen }

        Glyph(
            icon = if (locked) Icons.Default.ScreenRotation else Icons.Default.ScreenLockRotation,
            label = if (locked) "Follow the phone" else "Lock this orientation",
            enabled = true,
            size = 40.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(EDGE),
        ) {
            locked = !locked
            store.locked = locked
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
                colour = colour,
                weight = weight,
                onColour = { colour = it; store.colour = it },
                onWeight = { weight = it; store.weight = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(BACKGROUND)
                    .padding(EDGE),
            )
        }
    }
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
    enabled: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    onPress: () -> Unit,
) {
    IconButton(
        onClick = onPress,
        enabled = enabled,
        modifier = modifier.size(size),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = GLYPH,
            disabledContentColor = GLYPH_OFF,
        ),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(size * 0.48f))
    }
}

/**
 * SIX BY FOUR, in the manner of a swatch grid in an Adobe application. You look at it, you press
 * one, it is applied. No wheel, no hex field, no sliders: a wheel offers a million colours in
 * order to find the six anybody wants.
 *
 * The chosen swatch carries a tick, following the Avid model in design-language.md 15 where a
 * category holds several instances and one carries the checkmark. The tick is drawn in black or
 * white depending on which can be seen on that swatch, and Palette.markOn computes which rather
 * than guessing — the guess was wrong on the orange and Test 1 said so.
 */
@Composable
private fun SettingsGrid(
    width: Dp,
    colour: Long,
    weight: Weight,
    onColour: (Long) -> Unit,
    onWeight: (Weight) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = 8.dp
    val cell = (width - gap * (Palette.COLUMNS - 1)) / Palette.COLUMNS

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Palette.SWATCHES.chunked(Palette.COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.padding(bottom = gap),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                row.forEach { swatch ->
                    Box(
                        Modifier
                            .size(cell)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(swatch)),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconButton(onClick = { onColour(swatch) }, modifier = Modifier.size(cell)) {
                            if (swatch == colour) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Chosen",
                                    tint = Color(Palette.markOn(swatch)),
                                    modifier = Modifier.size(cell * 0.55f),
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
            val half = cell * 3 + gap * 2
            WeightCell("88:88", Weight.NORMAL, weight, colour, half, onWeight)
            WeightCell("88:88", Weight.BOLD, weight, colour, half, onWeight)
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
