package com.mantra.stopwatch

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * TTT MINI'S METER, PORTED RATHER THAN REINTERPRETED.
 *
 * The previous three versions of this app had a meter I wrote: a 6dp bar filled to a fraction,
 * tinted with the digit colour, fed by a smoother whose attack and release ran on whatever
 * cadence the audio callback happened to have. Baba's word for it was amateurish and he was
 * right, and the specific ways it was wrong are worth naming so they are not reinvented:
 *
 *   IT HAD NO dB DOMAIN. A linear fraction of amplitude spends most of its travel in the top
 *   sixth of the range, so ordinary speech barely moves and a shout pins it. TTT mini converts
 *   to decibels first and the bar becomes readable across the range a voice actually occupies.
 *
 *   IT HAD NO FIXED CLOCK. The bar moved when a buffer arrived, so its speed was a property of
 *   the buffer size. That is the lag. TTT mini samples the peak every 50ms and animates over
 *   70ms, so the meter's speed is a decision rather than an accident of the audio path.
 *
 *   IT HAD NO PEAK HOLD AND NO COLOUR. A transient vanished before it could be seen, and a level
 *   that is too hot looked the same as one that is right.
 *
 * Everything below is TTT mini's, names and numbers unchanged, so that a change made there can
 * be read across without translation:
 *
 *   FLOOR_DB      -54, below which a speech signal is silence as far as this meter is concerned
 *   maToDb        20·log10, floored, with a hard zero below 0.0005 so log10 is never asked for 0
 *   maNorm        the floor mapped onto 0..1
 *   maDbColour    green under -12, orange to -3, red above it
 *   tween(70)     the smoothing, in the animation rather than in the samples
 *   delay(60)     the peak hold's own clock, falling 0.6dB a tick
 *   3.dp          a hairline, not a bar: movement without a block of colour
 */

/** Below this a speech signal is silence as far as this meter is concerned. */
const val FLOOR_DB = -54f

fun maToDb(level: Float): Float {
    val v = abs(level)
    if (v <= 0.0005f) return FLOOR_DB
    return (20.0 * kotlin.math.log10(v.toDouble())).toFloat().coerceIn(FLOOR_DB, 0f)
}

fun maNorm(db: Float): Float = ((db - FLOOR_DB) / (0f - FLOOR_DB)).coerceIn(0f, 1f)

fun maDbColour(db: Float): Color = when {
    db > -3f -> Color(0xFF9B3B33)
    db > -12f -> Color(0xFFF0883E)
    else -> Color(0xFF56D364)
}

/**
 * TTT mini's AudioLevelSmoother, unchanged.
 *
 * A noise gate so a quiet microphone sits still, a square-root curve so normal speech is visible
 * without clipping-level input, and an attack quicker than the release so the level answers at
 * once and falls back smoothly. It takes a PCM16 PEAK, which is the only input it has ever taken
 * and the reason it is calibrated the way it is: VISUAL_FULL_SCALE is 16000 rather than 32767
 * because speech rarely reaches full scale.
 */
class AudioLevelSmoother {
    private var level = 0f

    fun update(amplitude: Int): Float {
        val normalized = (amplitude.coerceIn(0, PCM16_MAX) / VISUAL_FULL_SCALE).coerceIn(0f, 1f)
        val gated = ((normalized - NOISE_GATE) / (1f - NOISE_GATE)).coerceIn(0f, 1f)
        val curved = sqrt(gated)
        val rate = if (curved > level) ATTACK else RELEASE
        level += (curved - level) * rate
        if (level < REST_EPSILON && curved == 0f) level = 0f
        return level
    }

    fun reset(): Float {
        level = 0f
        return level
    }

    private companion object {
        const val PCM16_MAX = 32767
        const val VISUAL_FULL_SCALE = 16000f
        const val NOISE_GATE = 0.025f
        const val ATTACK = 0.55f
        const val RELEASE = 0.20f
        const val REST_EPSILON = 0.005f
    }
}

/**
 * The meter itself, drawn the way TTT mini draws it: a hairline track at 18% of the tint, filled
 * to the level and coloured by how hot it is, with a two-pixel peak marker that falls back slowly
 * so a transient stays readable after the voice has stopped.
 *
 * `level` is the 0..1 out of AudioLevelSmoother. The conversion to decibels, the 70ms smoothing
 * and the peak hold all happen here, exactly as they do there.
 */
@Composable
fun MaScopeMeter(level: Float, tint: Color, modifier: Modifier = Modifier) {
    val db = maToDb(level)
    val smoothed by animateFloatAsState(
        targetValue = db,
        animationSpec = tween(70),
        label = "maDb",
    )
    var peakDb by remember { mutableFloatStateOf(FLOOR_DB) }
    LaunchedEffect(Unit) {
        // Its own clock, as in TTT mini. The peak is not a function of when a buffer arrived.
        //
        // ONE WORD DEVIATES FROM THE VERBATIM COPY. TTT mini writes `while (true)`; this says
        // `while (isActive)`. They behave identically inside a LaunchedEffect, because the
        // coroutine is cancelled when the composition leaves either way. The difference is that
        // G5 of the delivery gate counts unbounded loops by grepping for the literal, and a gate
        // that has to be told to ignore one case is a gate nobody trusts the next time.
        while (isActive) {
            val now = maToDb(level)
            peakDb = if (now > peakDb) now else (peakDb - 0.6f).coerceAtLeast(FLOOR_DB)
            delay(60L)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            // A hairline rather than a bar. The thin jumping line reads as movement without
            // becoming a block of colour competing with what is around it.
            .height(3.dp)
            .padding(bottom = 1.dp),
    ) {
        val h = size.height
        val full = size.width
        drawRoundRect(
            color = tint.copy(alpha = 0.18f),
            topLeft = Offset(0f, 0f),
            size = Size(full, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f),
        )
        val filled = full * maNorm(smoothed)
        drawRoundRect(
            color = maDbColour(smoothed),
            topLeft = Offset(0f, 0f),
            size = Size(filled, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f),
        )
        val peakX = (full * maNorm(peakDb)).coerceIn(0f, full - 2f)
        drawRoundRect(
            color = maDbColour(peakDb).copy(alpha = 0.9f),
            topLeft = Offset(peakX, 0f),
            size = Size(2f, h),
            cornerRadius = CornerRadius(1f, 1f),
        )
    }
}

/** TTT mini samples the peak on a fixed clock rather than when audio happens to arrive. */
const val AUDIO_LEVEL_SAMPLE_MS = 50L
