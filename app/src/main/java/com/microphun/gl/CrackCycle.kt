package com.microphun.gl

import kotlin.random.Random

/**
 * The `crack` visual's clock: when a noise strikes the glass and how hard,
 * how much damage has built up, and when the glass gives way. No GL in it, so
 * it can be tested on the host.
 *
 * An impact is an onset — the analysis's beat detector — caught on its rising
 * edge, so one bang is one blow however long the onset takes to decay. Onset
 * spikes to 1 for any beat, soft or loud, so it says *when* but not *how
 * hard*; the size comes from the level, taken at its peak over a short window
 * after the onset, since a transient's level is still climbing when the onset
 * fires. Every blow adds damage, big ones far more than small ones, and once
 * the glass has taken enough it shatters, goes dark, and a fresh image takes
 * its place.
 *
 * How hard is partly absolute and partly relative to the blows before it. The
 * first version was absolute only, with a guessed range, and on the device
 * every noise in a real room fell at its bottom: a screen of identical minimum
 * cracks. Ranking a blow against the recent ones guarantees the spread — the
 * loudest thing in *this* room makes the biggest crack — while the absolute
 * part still lets a genuinely loud room break the glass sooner.
 *
 * An onset whose peak is below [SILENT_LEVEL] is not a blow at all. The beat
 * detector reacts to change in the spectrum, not to loudness, so it fires in a
 * quiet room too — a fifth of the blows in the first session on the device
 * peaked at nothing. Left in, they cracked the glass for no sound, and filled
 * the ranking with silence so the next faint noise ranked as a big one.
 */
class CrackCycle(private val random: Random = Random.Default) {

    /** What a call to [advance] asks the layer to do. */
    class Step {
        /** Time for a fresh image: at the start, and after each shatter. */
        var newImage = false
            internal set

        /** Size of a blow that landed this frame, 0..1, or -1 for none. */
        var impact = -1f
            internal set

        /** For a blow, the peak level it was sized from. */
        var peak = 0f
            internal set
    }

    private val step = Step()

    var damage = 0f
        private set

    /** 0 while the glass holds; runs to 1 as it gives way. */
    var shatter = 0f
        private set

    /** 1 on a blow, decaying: drives the flash. */
    var flash = 0f
        private set

    private var needsFirst = true
    private var onsetWasHigh = false
    private var sinceImpact = Float.MAX_VALUE

    /** A blow caught but not yet landed, while its peak is measured; -1 if none. */
    private var pendingFor = -1f
    private var pendingPeak = 0f

    /** Peaks of the most recent blows, oldest first. */
    private val recent = ArrayDeque<Float>()

    /** Advances by [dt] seconds of audio at [level] and [onset], both 0..1. */
    fun advance(dt: Float, level: Float, onset: Float): Step {
        step.newImage = false
        step.impact = -1f
        step.peak = 0f
        flash = (flash - dt * FLASH_DECAY_PER_SECOND).coerceAtLeast(0f)
        sinceImpact += dt

        if (needsFirst) {
            needsFirst = false
            step.newImage = true
            return step
        }

        if (shatter > 0f) {
            shatter += dt / SHATTER_SECONDS
            if (shatter >= 1f) {
                shatter = 0f
                damage = 0f
                step.newImage = true
            }
            // No blows land while the glass is coming down.
            onsetWasHigh = onset >= ONSET_THRESHOLD
            pendingFor = -1f
            return step
        }

        val high = onset >= ONSET_THRESHOLD
        val rising = high && !onsetWasHigh
        onsetWasHigh = high

        if (pendingFor >= 0f) {
            pendingFor += dt
            pendingPeak = maxOf(pendingPeak, level)
            if (pendingFor >= PEAK_WINDOW_SECONDS) land(pendingPeak)
            return step
        }

        if (!rising || sinceImpact < MIN_GAP_SECONDS) return step
        sinceImpact = 0f
        pendingFor = 0f
        pendingPeak = level
        return step
    }

    private fun land(peak: Float) {
        pendingFor = -1f
        if (peak < SILENT_LEVEL) return
        val size = sizeFor(peak)
        step.impact = size
        step.peak = peak
        flash = 1f
        damage += DAMAGE_PER_BLOW * size * size + DAMAGE_FLOOR
        if (damage >= SHATTER_AT) shatter = Float.MIN_VALUE
    }

    /**
     * Size for a blow peaking at [peak]: part absolute loudness, part its rank
     * among the recent blows, and a little jitter so equal noises still differ.
     */
    fun sizeFor(peak: Float): Float {
        val absolute = absoluteSize(peak)
        val relative = if (recent.size < MIN_HISTORY) {
            absolute
        } else {
            val below = recent.count { it < peak }
            val equal = recent.count { it == peak }
            (below + 0.5f * equal) / recent.size
        }
        recent.addLast(peak)
        if (recent.size > HISTORY) recent.removeFirst()

        val blended = ABSOLUTE_WEIGHT * absolute + (1f - ABSOLUTE_WEIGHT) * relative
        val jittered = blended + (random.nextFloat() - 0.5f) * 2f * JITTER
        // Skewed so most blows are modest and the big ones stand out.
        return jittered.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }.coerceAtLeast(MIN_SIZE)
    }

    /** Starts again from a fresh, whole image. */
    fun reset() {
        damage = 0f
        shatter = 0f
        flash = 0f
        needsFirst = true
        onsetWasHigh = false
        sinceImpact = Float.MAX_VALUE
        pendingFor = -1f
        recent.clear()
    }

    companion object {
        /** Onset is 1 at a beat and decays; above this counts as the beat. */
        const val ONSET_THRESHOLD = 0.6f

        /** Beats closer than this are one blow, not a burst of them. */
        const val MIN_GAP_SECONDS = 0.12f

        /**
         * Level at which the absolute part of a blow is smallest and largest.
         * Lower than the first guess (0.3..0.85), under which no noise in a real
         * room made more than the smallest crack. The loudest noises in the
         * first session on the device peaked at 0.33..0.48, so the top sits
         * there rather than at the earlier 0.65, which nothing reached. Debug
         * builds log each blow's peak under `microphun.crack`, which is what to
         * tune these from.
         */
        const val QUIET_LEVEL = 0.2f
        const val LOUD_LEVEL = 0.45f

        /** An onset peaking below this is the detector reacting to a quiet room, not a blow. */
        const val SILENT_LEVEL = 0.02f

        /** How long after the onset a blow's level is watched for its peak. */
        const val PEAK_WINDOW_SECONDS = 0.08f

        /** Blows remembered for ranking, and how many before ranking starts. */
        const val HISTORY = 16
        const val MIN_HISTORY = 4

        /** Share of a blow's size that is absolute rather than ranked. */
        const val ABSOLUTE_WEIGHT = 0.4f
        const val JITTER = 0.08f

        /** The smallest blow still leaves a mark. */
        const val MIN_SIZE = 0.08f

        /** A full-size blow is worth this much damage; a small one far less. */
        const val DAMAGE_PER_BLOW = 1f
        const val DAMAGE_FLOOR = 0.04f

        /** About four big blows, or a few dozen small ones. */
        const val SHATTER_AT = 4f

        const val SHATTER_SECONDS = 1.4f
        const val FLASH_DECAY_PER_SECOND = 4f

        fun absoluteSize(level: Float): Float =
            ((level - QUIET_LEVEL) / (LOUD_LEVEL - QUIET_LEVEL)).coerceIn(0f, 1f)
    }
}
