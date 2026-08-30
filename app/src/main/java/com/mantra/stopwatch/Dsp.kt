package com.mantra.stopwatch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MATCHING A SPOKEN WORD AGAINST A RECORDING OF IT, AND IT IMPORTS NOTHING.
 *
 * WHY THIS EXISTS AT ALL. SpeechRecognizer does not work on this phone. Four versions of evidence
 * say so: the meter proves AudioRecord opens the microphone and delivers audio, and every time
 * the recogniser is handed that microphone it churns and plays a tone and recognises nothing.
 * There is no fifth thing to try inside that API. So it is gone, and with it the tone, because
 * the tone was the recogniser's session boundary and there are no longer any sessions.
 *
 * WHAT REPLACES IT. Baba records himself saying each command once. At run time the incoming audio
 * is compared against those three recordings and the closest one wins, if it is close enough.
 * This is older and dumber than speech recognition and it is better here for four reasons:
 *
 *   it needs no model, no network and no Google service, so nothing can be missing
 *   it makes no sound, because nothing is being started and stopped
 *   it is language-agnostic: the template is whatever he actually said, in whatever language,
 *     with whatever accent, so "kreni" and "start" are the same problem
 *   it is testable end to end on a plain JVM, which none of the recogniser path ever was
 *
 * WHAT IT COSTS, plainly. It only knows the voice that recorded it, in roughly the conditions it
 * was recorded in. A different room or a cold will hurt it. It has no idea what a word means, so
 * a word that merely SOUNDS like the template will match. And the templates have to be recorded
 * before anything works at all, which is a step that did not exist before.
 *
 * HOW IT WORKS, in the order the audio moves:
 *
 *   frames        25ms of samples every 10ms, Hamming windowed
 *   spectrum      a radix-2 FFT, magnitude only, phase discarded
 *   mel bands     20 triangular bands on a mel scale, so the resolution follows the ear
 *   log           because loudness is multiplicative and distance should not be
 *   normalise     each frame minus the mean of the whole utterance, which is what makes this
 *                 survive a change of volume or a change of microphone gain
 *   endpoint      leading and trailing quiet frames trimmed, so "start" and "  start  " are
 *                 the same utterance
 *   DTW           the two sequences aligned in time, because nobody says a word at the same
 *                 speed twice, and a straight frame-by-frame comparison would fail on that alone
 */
object Dsp {

    const val SAMPLE_RATE = 16_000
    const val FRAME = 400          // 25ms
    const val HOP = 160            // 10ms
    const val FFT_SIZE = 512
    const val BANDS = 20

    // ── the transform ────────────────────────────────────────────────────────────────────────

    /**
     * An in-place iterative radix-2 FFT. Real input, complex output in the two arrays given.
     *
     * Written out rather than pulled in because the whole point of this file is that it can be
     * walked by Test 1 with no Android and no dependencies, and because a transform is the kind
     * of thing whose correctness should be visible rather than trusted.
     */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n and (n - 1) == 0) { "FFT size must be a power of two, was $n" }

        // Bit reversal.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun melOf(hz: Double) = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
    private fun hzOf(mel: Double) = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)

    /** Triangular filter edges, in FFT bin numbers, evenly spaced on the mel scale. */
    private val edges: IntArray by lazy {
        val low = melOf(80.0)
        val high = melOf(SAMPLE_RATE / 2.0)
        IntArray(BANDS + 2) { i ->
            val mel = low + (high - low) * i / (BANDS + 1)
            (hzOf(mel) * FFT_SIZE / SAMPLE_RATE).toInt().coerceIn(0, FFT_SIZE / 2)
        }
    }

    private val window: DoubleArray by lazy {
        DoubleArray(FRAME) { 0.54 - 0.46 * cos(2.0 * PI * it / (FRAME - 1)) }
    }

    // ── features ─────────────────────────────────────────────────────────────────────────────

    /**
     * The utterance as a sequence of 20-band log-mel frames, endpointed and normalised.
     *
     * Returns an empty list for silence, which is the honest answer and lets everything above
     * treat "nothing was said" as a case rather than as an error.
     */
    /**
     * MINIMUM-STATISTICS NOISE TRACKING AND A DECISION-DIRECTED WIENER GAIN.
     *
     * This is what a professional denoiser does, and the two halves fix the two things wrong with
     * what was here before.
     *
     * THE ESTIMATOR. v20 took the quiet frames of the window and averaged them, which needs the
     * window to CONTAIN quiet frames — so it carried a guard that refused to clean anything with
     * no gap in it, and traffic and a club are exactly the places with no gap. Minimum statistics
     * asks a different question: for each band, what is the smallest this band has been recently?
     * Speech is intermittent in every band, even during continuous talking, so a band's minimum
     * over a second is the noise floor in that band whether or not anybody stopped speaking. The
     * minimum of a noisy quantity is biased low, so it is multiplied back up by a correction
     * factor, which is what Martin's method does and where the number comes from.
     *
     * THE SUPPRESSION. v20 subtracted the estimate from the magnitude. Subtraction is crude: it
     * takes the same amount off a band that is mostly speech and a band that is mostly noise, and
     * what is left in the quiet bands is the ragged residue that sounds like bubbling — musical
     * noise, the thing that makes cheap noise reduction recognisable. A Wiener gain instead asks,
     * per band, HOW MUCH OF THIS IS SIGNAL, and scales by that: bands that are mostly speech pass
     * almost untouched, bands that are mostly noise are turned down smoothly.
     *
     * DECISION-DIRECTED means the signal-to-noise estimate driving the gain is smoothed against
     * the previous frame's answer rather than computed fresh each time. That is Ephraim and
     * Malah's contribution and it is the single thing that removes musical noise: an estimate
     * that jumps frame to frame produces a gain that jumps frame to frame, and a gain that jumps
     * is what you hear.
     *
     * WHY NOT RNNoise. It is better than this and its model is only 60 to 90 KB. It is also C:
     * the NDK, CMake, a native build in CI and a shared object per ABI, none of which this
     * project has. This is a hundred lines of Kotlin, costs a multiplication per band per frame,
     * needs no toolchain, and — the part that matters most here — is testable on a plain JVM,
     * which is how every hard thing in this app finally got right. If this is not enough in the
     * club, RNNoise is next and it is a version of its own.
     */
    private fun denoise(bands: MutableList<DoubleArray>) {
        if (bands.size < MIN_FRAMES) return

        val noise = DoubleArray(BANDS)
        for (b in 0 until BANDS) {
            // The minimum this band reached anywhere in the window, corrected for the bias that
            // taking a minimum introduces. No guard and no quantile: a band that never went quiet
            // still has a smallest value, and that value is the floor it never got below.
            var lowest = Double.MAX_VALUE
            for (f in bands) if (f[b] < lowest) lowest = f[b]
            noise[b] = lowest * MINIMUM_BIAS
        }

        // The a priori signal-to-noise ratio, carried between frames. Starting it at the a
        // posteriori value of the first frame rather than at zero avoids a first frame that is
        // gated to nothing, which would clip the start of every word.
        val priorSnr = DoubleArray(BANDS) { 1.0 }

        for (f in bands) {
            for (b in 0 until BANDS) {
                val power = f[b] * f[b]
                val noisePower = (noise[b] * noise[b]).coerceAtLeast(1e-12)

                // A posteriori: how much louder is this frame than the noise floor.
                val posterior = (power / noisePower).coerceAtLeast(1e-6)

                // Decision directed: mostly last frame's answer, a little of this one. This is
                // the smoothing that stops the gain jumping, and the jumping is what is audible.
                val prior = SMOOTHING * priorSnr[b] +
                    (1.0 - SMOOTHING) * (posterior - 1.0).coerceAtLeast(0.0)

                val gain = (prior / (1.0 + prior)).coerceIn(GAIN_FLOOR, 1.0)
                f[b] = f[b] * gain

                // Carried forward as the estimate of what the signal was, which is what makes it
                // "decision directed": the decision made about this frame informs the next.
                priorSnr[b] = (gain * gain * posterior).coerceAtLeast(1e-6)
            }
        }
    }

    /** The minimum of a fluctuating quantity sits below its mean; this puts it back. */
    private const val MINIMUM_BIAS = 1.5

    /** Ephraim and Malah's alpha. High, because the whole point is that it changes slowly. */
    private const val SMOOTHING = 0.96

    /**
     * Never to zero. A band closed completely is a hole, the log turns a hole into a cliff, and
     * the matcher then compares cliffs instead of voices. It is also what over-aggressive noise
     * reduction sounds like: words with the air removed from between them.
     */
    private const val GAIN_FLOOR = 0.08

    /** Below this there are not enough frames for a minimum to mean anything. */
    private const val MIN_FRAMES = 12

    /** Mean square per frame, straight from the samples, with no normalisation of any kind. */
    fun frameEnergies(samples: ShortArray): DoubleArray {
        if (samples.size < FRAME) return DoubleArray(0)
        val out = ArrayList<Double>()
        var start = 0
        while (start + FRAME <= samples.size) {
            var power = 0.0
            for (i in 0 until FRAME) {
                val v = samples[start + i] / 32768.0
                power += v * v
            }
            out.add(power / FRAME)
            start += HOP
        }
        return out.toDoubleArray()
    }

    fun features(samples: ShortArray, clean: Boolean = true): List<DoubleArray> {
        if (samples.size < FRAME) return emptyList()
        val frames = ArrayList<DoubleArray>()
        val energies = ArrayList<Double>()

        var start = 0
        while (start + FRAME <= samples.size) {
            val re = DoubleArray(FFT_SIZE)
            val im = DoubleArray(FFT_SIZE)
            var power = 0.0
            for (i in 0 until FRAME) {
                val v = samples[start + i] / 32768.0
                power += v * v
                re[i] = v * window[i]
            }
            fft(re, im)

            val band = DoubleArray(BANDS)
            for (b in 0 until BANDS) {
                val lo = edges[b]
                val mid = edges[b + 1]
                val hi = edges[b + 2]
                var sum = 0.0
                for (k in lo until hi) {
                    val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                    // Triangular weighting: full at the middle edge, zero at the two outer ones.
                    val w = if (k < mid) {
                        if (mid == lo) 1.0 else (k - lo).toDouble() / (mid - lo)
                    } else {
                        if (hi == mid) 1.0 else (hi - k).toDouble() / (hi - mid)
                    }
                    sum += mag * w
                }
                // The floor stops log going to negative infinity on a silent band, which would
                // poison every distance computed against it.
                // Magnitude, not log yet: the log happens after the noise is subtracted, because
                // subtraction is linear in magnitude and means nothing in log.
                band[b] = sum
            }
            frames.add(band)
            energies.add(power / FRAME)
            start += HOP
        }

        // BEFORE the log, because subtraction is linear in magnitude and meaningless in log.
        // And before endpointing, because a cleaner signal is a better endpointer: the whole
        // reason the endpointer used to keep half a second of traffic is that the traffic was
        // still in the band energies it was measuring.
        if (clean) denoise(frames)

        // ENDPOINT ON THE CLEANED ENERGY, NOT THE RAW ONE. Test 1 caught this too: a window of
        // pure room noise was being kept as a short utterance, because the endpointer was still
        // measuring the energy the noise had before it was removed. Traffic that has been
        // subtracted out has to look like silence to the thing deciding where the word starts,
        // or the cleaning helps the matcher and lies to the endpointer.
        val cleanedEnergies = if (clean) frames.map { it.sum() } else energies

        for (f in frames) for (b in 0 until BANDS) f[b] = ln(f[b] + 1e-9)

        val kept = endpoint(frames, cleanedEnergies)
        return normalise(kept)
    }

    /**
     * Trims leading and trailing quiet frames. The threshold is relative to the loudest frame of
     * this utterance rather than absolute, because an absolute one would be a guess about a
     * microphone gain nobody has measured.
     */
    private fun endpoint(frames: List<DoubleArray>, energies: List<Double>): List<DoubleArray> {
        if (frames.isEmpty()) return frames
        val peak = energies.max()
        if (peak <= 0.0) return emptyList()
        val floor = peak * SILENCE_FRACTION
        var first = energies.indexOfFirst { it >= floor }
        var last = energies.indexOfLast { it >= floor }
        if (first < 0 || last < first) return emptyList()
        // A little air either side, so a soft consonant at the edge is not cut off.
        first = (first - 3).coerceAtLeast(0)
        last = (last + 3).coerceAtMost(frames.size - 1)
        return frames.subList(first, last + 1)
    }

    /**
     * EACH FRAME MINUS ITS OWN MEAN ACROSS THE BANDS. This is what makes the matcher survive
     * loudness: a quiet "start" and a loud one differ by a constant in the log domain, and
     * subtracting that constant removes exactly the gain while leaving the SHAPE of the spectrum,
     * which is the part that says which word it was.
     *
     * THE FIRST VERSION SUBTRACTED THE MEAN ACROSS THE UTTERANCE INSTEAD, and Test 1 found it by
     * the back door. A steady tone has the same spectrum in every frame, so the per-utterance
     * mean IS each frame, and subtracting it left every frame a vector of zeros. Cosine distance
     * between two zero vectors is numerical noise, and the matcher scored a tone as further from
     * a recording of itself than from a different tone entirely.
     *
     * Real speech is not stationary, so on a voice the fault would have been milder and much
     * harder to see — a matcher that works badly rather than one that visibly cannot work. The
     * synthetic signals in the test are pathological on purpose and that is what made it obvious.
     */
    private fun normalise(frames: List<DoubleArray>): List<DoubleArray> =
        frames.map { f ->
            var mean = 0.0
            for (b in 0 until BANDS) mean += f[b]
            mean /= BANDS
            DoubleArray(BANDS) { b -> f[b] - mean }
        }

    // ── comparison ───────────────────────────────────────────────────────────────────────────

    /** Cosine distance, 0 for identical direction and 2 for opposite. */
    fun distance(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 1.0
        return 1.0 - dot / (sqrt(na) * sqrt(nb))
    }

    /**
     * Dynamic time warping, returning the average distance along the best alignment.
     *
     * AVERAGE, NOT TOTAL. A total grows with the length of the utterance, so a long word would
     * always look worse than a short one and the threshold would have to be different for each
     * command. Dividing by the path length makes one threshold mean the same thing everywhere.
     *
     * The band is Sakoe-Chiba: an alignment is not allowed to wander more than a tenth of the
     * length away from the diagonal. It makes this quick enough to run on every window of live
     * audio, and it also refuses the degenerate alignment where one frame is stretched across a
     * whole utterance, which is how DTW flatters a bad match.
     */
    fun dtw(a: List<DoubleArray>, b: List<DoubleArray>): Double {
        if (a.isEmpty() || b.isEmpty()) return Double.MAX_VALUE
        val n = a.size
        val m = b.size
        // THE BAND MUST BE AT LEAST THE LENGTH DIFFERENCE, and the first version was not.
        //
        // A Sakoe-Chiba band of a tenth of the length stops an alignment wandering off the
        // diagonal. But if one utterance has 108 frames and the other 58, the path has to travel
        // 50 frames off the diagonal just to REACH the corner — and with a band of 10 it cannot,
        // so the cost matrix never reaches its end, the path length is zero, and this returned
        // Double.MAX_VALUE.
        //
        // The matcher reads MAX_VALUE as "nothing like it". So a take noticeably longer or
        // shorter than its template did not score badly — IT FAILED TO SCORE AT ALL, silently,
        // and looked exactly like not having been heard. Test 1 found it only because noise made
        // one window endpoint longer than the other.
        val band = maxOf(BAND_MIN, abs(n - m) + (maxOf(n, m) * BAND_FRACTION).toInt())

        val cost = Array(n + 1) { DoubleArray(m + 1) { Double.MAX_VALUE / 4 } }
        val steps = Array(n + 1) { IntArray(m + 1) }
        cost[0][0] = 0.0

        for (i in 1..n) {
            val from = maxOf(1, i - band)
            val to = min(m, i + band)
            for (j in from..to) {
                val d = distance(a[i - 1], b[j - 1])
                val diag = cost[i - 1][j - 1]
                val up = cost[i - 1][j]
                val left = cost[i][j - 1]
                val best = minOf(diag, up, left)
                cost[i][j] = d + best
                steps[i][j] = 1 + when (best) {
                    diag -> steps[i - 1][j - 1]
                    up -> steps[i - 1][j]
                    else -> steps[i][j - 1]
                }
            }
        }
        val path = steps[n][m]
        if (path == 0) return Double.MAX_VALUE
        return cost[n][m] / path
    }

    private const val SILENCE_FRACTION = 0.02
    private const val BAND_FRACTION = 0.1
    private const val BAND_MIN = 8
}

/** One recorded command: what it triggers and the features of the recording. */
data class Template(val control: Control, val frames: List<DoubleArray>)

/**
 * WHICH TEMPLATE THE AUDIO IS, OR NONE.
 *
 * Two conditions, and both have to hold. The best match must be CLOSE, and it must be clearly
 * better than the second best. The second condition is the one that matters in a room where
 * somebody is talking: everything is a bad match for all three templates, one of them is
 * inevitably the least bad, and without a margin the stopwatch would fire on conversation.
 *
 * Refusing ambiguity rather than picking a winner is the same rule the word matcher already
 * followed, and for the same reason: choosing between two poor answers is guessing while
 * sounding certain.
 */
class TemplateMatcher(
    private val accept: Double = ACCEPT,
    private val margin: Double = MARGIN,
) {
    fun match(heard: List<DoubleArray>, templates: List<Template>): Control? {
        if (heard.isEmpty() || templates.isEmpty()) return null
        val scored = scores(heard, templates)
        val (best, bestScore) = scored.first()
        if (bestScore > accept) return null
        val runnerUp = scored.getOrNull(1)?.second ?: Double.MAX_VALUE
        if (runnerUp - bestScore < margin) return null
        return best
    }

    /**
     * The best distance PER CONTROL, for the tester and for match().
     *
     * THE MINIMUM ACROSS A CONTROL'S SAMPLES, NOT THE AVERAGE. Three recordings of "start" are
     * three attempts at the same thing, and the honest question is whether what was just said
     * resembles ANY of them — not whether it resembles all of them equally. An average is
     * dragged down by the one recording where the phone was further away or a door closed, which
     * is exactly the recording the other two exist to make harmless.
     *
     * This is also what makes the margin still mean something with three samples each: the two
     * numbers being compared are one per command, as they were when there was one sample each.
     */
    fun scores(heard: List<DoubleArray>, templates: List<Template>): List<Pair<Control, Double>> =
        templates
            .groupBy { it.control }
            .map { (control, group) -> control to group.minOf { Dsp.dtw(heard, it.frames) } }
            .sortedBy { it.second }

    private companion object {
        /**
         * Both numbers are starting points chosen on the geometry of a cosine distance, not
         * measured on a voice, because there is no voice here to measure. They are the first
         * thing to change if it misses or if it fires on conversation, and the tester shows the
         * raw scores precisely so that change can be made from evidence.
         */
        const val ACCEPT = 0.55
        const val MARGIN = 0.06
    }
}


/**
 * WHETHER A RECORDING IS ANY GOOD, decided before it is ever stored.
 *
 * A sampler that will happily store two seconds of room tone as the sound of the word "start" is
 * not a sampler, it is a trap. Everything downstream keeps working — the pad looks filled, the
 * count says three of three — and the only symptom is that matching quietly stops being reliable.
 * That is the worst failure shape there is, so a sample is judged at the moment it is taken and
 * refused out loud if it is no good.
 */
enum class SampleQuality {
    /** Usable. */
    GOOD,

    /** Nothing crossed the endpointer: the microphone heard a room, not a word. */
    SILENT,

    /** Something was said, but too little of it to align against anything. */
    TOO_SHORT,

    /** Loud enough to be clipping, which destroys the spectrum the matcher compares. */
    CLIPPED,
}

object SampleCheck {

    /** Below a quarter of a second of speech there is not enough to warp against. */
    const val MIN_FRAMES = 25

    /** A tenth of the samples at the rail is not a loud voice, it is a broken recording. */
    const val CLIP_FRACTION = 0.10

    /**
     * A word is loud against its own surroundings. Below this ratio nothing stands out, which is
     * what a window of steady traffic looks like: plenty of level, no shape.
     */
    const val MIN_DYNAMIC_RANGE = 3.0

    fun assess(samples: ShortArray): SampleQuality {
        if (samples.isEmpty()) return SampleQuality.SILENT

        var clipped = 0
        for (v in samples) if (v >= 32000 || v <= -32000) clipped++
        if (clipped > samples.size * CLIP_FRACTION) return SampleQuality.CLIPPED

        // NOTHING STANDS OUT MEANS NO WORD IN IT. A window of steady traffic has plenty of
        // energy and plenty of frames, and the denoiser correctly leaves it alone because there
        // is no quiet part to learn the room from. Level cannot tell it from speech; DYNAMIC
        // RANGE can, because a word is loud against its own surroundings by definition.
        //
        // MEASURED ON THE RAW SAMPLES, NOT ON THE FEATURES. The first version measured it on the
        // feature frames, which are normalised per frame — and normalising is precisely the step
        // that removes level. It was computing the dynamic range of something that by
        // construction has none, and rejecting good recordings for it.
        val energies = Dsp.frameEnergies(samples)
        if (energies.size >= 3) {
            val sorted = energies.clone().also { it.sort() }
            // A LOW QUANTILE, NOT THE MEDIAN. A good recording can easily be half word and half
            // room, and then the median frame IS a word frame — the check rejected exactly the
            // well-centred recordings it was meant to protect. The quiet fifth of the frames is
            // room in anything that contains a word at all, and is the noise floor itself in
            // anything that does not.
            val floor = sorted[(sorted.size * 0.2).toInt().coerceIn(0, sorted.size - 1)]
            val peak = sorted.last()
            if (peak <= 0.0) return SampleQuality.SILENT
            if (floor > 0.0 && peak / floor < MIN_DYNAMIC_RANGE) return SampleQuality.SILENT
        }

        val frames = Dsp.features(samples)
        if (frames.isEmpty()) return SampleQuality.SILENT
        if (frames.size < MIN_FRAMES) return SampleQuality.TOO_SHORT
        return SampleQuality.GOOD
    }

    fun describe(q: SampleQuality): String = when (q) {
        SampleQuality.GOOD -> "saved"
        SampleQuality.SILENT -> "nothing heard, try again"
        SampleQuality.TOO_SHORT -> "too short, say the whole word"
        SampleQuality.CLIPPED -> "too loud, move back a little"
    }
}

/**
 * THE WAVEFORM ON THE PAD.
 *
 * A pad that says only "filled" tells you a recording exists. A pad with the shape of the
 * recording on it tells you WHICH recording, whether the word is centred in it, and whether what
 * you captured was a word at all or a cough at one end and silence at the other. On an Akai the
 * waveform is not decoration, it is how you know what is under your finger.
 *
 * Returns [buckets] peak amplitudes, 0..1, oldest first.
 */
fun waveform(samples: ShortArray, buckets: Int): FloatArray {
    if (samples.isEmpty() || buckets <= 0) return FloatArray(0)
    val out = FloatArray(buckets)
    val per = samples.size.toDouble() / buckets
    for (b in 0 until buckets) {
        val from = (b * per).toInt()
        val to = minOf(samples.size, ((b + 1) * per).toInt().coerceAtLeast(from + 1))
        var peak = 0
        for (i in from until to) {
            val v = abs(samples[i].toInt())
            if (v > peak) peak = v
        }
        out[b] = (peak / 32767f).coerceIn(0f, 1f)
    }
    return out
}
