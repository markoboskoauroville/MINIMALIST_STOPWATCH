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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
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
                // Three controls, one call each, and the tone comes from the model rather than
                // from a condition written here. The Activity does not know what a phase is.
                //
                // THE LABELS ARE THE VOICE COMMANDS. Google's Voice Access, which is an
                // accessibility service already on the phone, matches what is said against the
                // contentDescription of every control on screen. So these three strings are not
                // decoration for a screen reader — they are the vocabulary. "Start", "Pause",
                // "Reset", chosen to match the words Baba said he wanted rather than the words
                // the model happens to use internally (play, pause, stop).
                Transport(Icons.Default.PlayArrow, "Start", Control.PLAY, state, button, ::commit)
                Transport(Icons.Default.Pause, "Pause", Control.PAUSE, state, button, ::commit)
                Transport(Icons.Default.Stop, "Reset", Control.STOP, state, button, ::commit)
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

        Glyph(
            icon = if (settingsOpen) Icons.Default.Close else Icons.Default.Settings,
            label = if (settingsOpen) "Close settings" else "Settings",
            tone = Tone.HIGHLIGHT,
            size = 40.dp,
            modifier = Modifier.align(Alignment.TopEnd).padding(EDGE),
        ) { settingsOpen = !settingsOpen }

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
                onColour = { colour = it; store.colour = it },
                onWeight = { weight = it; store.weight = it },
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
    label: String,
    control: Control,
    state: Stopwatch,
    size: Dp,
    commit: (Stopwatch) -> Unit,
) {
    val tone = state.tone(control)
    Glyph(
        icon = icon,
        label = label,
        tone = tone,
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
            contentColor = when (tone) {
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
    onColour: (Long) -> Unit,
    onWeight: (Weight) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    val forGrid = maxHeight - header - weightRow - gap * (rows + 2)
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
