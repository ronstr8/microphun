package com.microphun.gl

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the spiral's phrases are. Pure layout with no GL in it, so it can be
 * tested on the host; [SpiralLayer] draws what this places.
 *
 * Phrases spill out of the centre one word at a time and ride a logarithmic
 * spiral outward, growing as they go, until they fall off the screen. The
 * logarithmic spiral is the one that makes that work:
 *
 *  - Its arms are spaced in proportion to their radius, so words that grow in
 *    proportion to their radius never collide with the turn inside them.
 *  - Measured in ρ = ln(r / spawn radius), a word's length along the curve is a
 *    constant, whatever radius it is at. A phrase is therefore a rigid run of
 *    intervals in ρ, and moving it is adding to one number.
 *
 * The first word leads, so reading goes from the outside in: each word and the
 * text within it runs back along the curve towards the centre.
 */
class SpiralFlow(
    /** k in r = a·e^(kθ). Each turn multiplies the radius by e^(2πk). */
    private val pitch: Float = PITCH,
    /** Height of a word as a fraction of its radius. */
    private val textHeight: Float = TEXT_HEIGHT,
) {

    private class Phrase(
        val words: List<String>,
        /** Centre of each word behind the head, in ρ. */
        val offsets: FloatArray,
        val length: Float,
    ) {
        /** ρ of the leading edge. Starts at 0, the spawn radius. */
        var head = 0f
        val tail: Float get() = head - length
    }

    private val live = ArrayList<Phrase>()
    private val pending = ArrayDeque<Phrase>()

    /** ρ along the curve per unit of arc length divided by radius. */
    private val rhoPerArc = pitch / sqrt(1f + pitch * pitch)

    /** Angle from a point's radius to the way its text reads: back along the curve. */
    private val readingOffset = atan2(1f, pitch) + PI.toFloat()

    val phraseCount: Int get() = live.size
    val pendingCount: Int get() = pending.size
    val isEmpty: Boolean get() = live.isEmpty() && pending.isEmpty()

    /**
     * Queues a phrase to spill out once there is room. [aspects] is each
     * word's glyph width over its height, which is all the layout needs.
     *
     * Phrases arrive with speech and leave at a fixed pace, so a burst of
     * conversation can outrun the spiral. The queue is short and drops its
     * oldest, same rule as everything downstream of the microphone: what was
     * said a minute ago is not worth waiting for.
     */
    fun offer(words: List<String>, aspects: FloatArray) {
        require(words.size == aspects.size) { "one aspect per word" }
        if (words.isEmpty()) return

        val offsets = FloatArray(words.size)
        var cursor = 0f
        for (i in words.indices) {
            val span = span(aspects[i])
            offsets[i] = cursor + span * 0.5f
            cursor += span + WORD_GAP
        }

        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(Phrase(words, offsets, cursor - WORD_GAP))
    }

    /**
     * Moves everything outward by [dt] seconds, retires phrases whose last word
     * has passed [exitRho], and lets the next phrase out once the one ahead of
     * it has cleared the centre.
     */
    fun advance(dt: Float, exitRho: Float) {
        for (phrase in live) phrase.head += dt * SPEED
        live.removeAll { it.tail > exitRho }

        val newest = live.lastOrNull()
        if (pending.isNotEmpty() && (newest == null || newest.tail >= PHRASE_GAP)) {
            live.add(pending.removeFirst())
        }
    }

    /**
     * Visits every word that has emerged from the centre, placed in pixels
     * relative to the centre of the screen, y up.
     *
     * [rotation] is the direction the text reads, in radians; [height] is the
     * word's height in pixels. [spin] turns the whole spiral.
     */
    fun forEachWord(
        spawnRadius: Float,
        spin: Float,
        visit: (text: String, x: Float, y: Float, rotation: Float, height: Float, alpha: Float) -> Unit,
    ) {
        for (phrase in live) {
            for (i in phrase.words.indices) {
                val rho = phrase.head - phrase.offsets[i]
                // Still inside the centre: not out yet.
                if (rho <= 0f) continue

                val radius = spawnRadius * exp(rho)
                val theta = angleAt(rho, spin)
                visit(
                    phrase.words[i],
                    radius * cos(theta),
                    radius * sin(theta),
                    theta + readingOffset,
                    radius * textHeight,
                    (rho / FADE_IN_RHO).coerceAtMost(1f),
                )
            }
        }
    }

    /** Where the curve is at [rho]; the backdrop draws its arm from the same formula. */
    fun angleAt(rho: Float, spin: Float): Float = rho / pitch + spin

    /** ρ occupied by a word of this aspect ratio. The same at every radius. */
    private fun span(aspect: Float): Float = textHeight * aspect * rhoPerArc

    fun clear() {
        live.clear()
        pending.clear()
    }

    companion object {
        /**
         * 2.2× the radius per turn. Words take up 0.24 of their radius, which
         * leaves a wide lane between one turn and the next. At 1.6× the curve
         * wound so tight that on the device it read as concentric rings — a
         * tunnel, not a spiral. A word's angular length barely depends on this,
         * so opening it up does not change how much of a turn a phrase takes.
         */
        val PITCH = (ln(2.2) / (2.0 * PI)).toFloat()
        const val TEXT_HEIGHT = 0.24f

        /** ρ per second. Centre to corner is about 4.6 on a phone, so a word crosses in ~16 s. */
        const val SPEED = 0.28f

        /** Between words of a phrase, and between one phrase and the next. */
        const val WORD_GAP = 0.012f
        const val PHRASE_GAP = 0.2f

        /** A word fades in over its first stretch out of the centre. */
        const val FADE_IN_RHO = 0.35f

        const val MAX_PENDING = 3

        /**
         * Radians per second the spiral turns on its own. Against the flow, and
         * nearly as fast: words that ride the curve outward would otherwise
         * whirl round at over 2 rad/s. Counter-turning most of that away leaves the
         * words drifting slowly round and the arm appearing to stream outward
         * with them, which is what makes it read as falling in.
         */
        val COUNTER_SPIN = -0.75f * SPEED / PITCH

        /**
         * How much the beat can add to the counter-turn: bass leans on it,
         * onsets kick it. As fractions of it, so it only ever grows.
         */
        const val SPIN_BASS = 0.25f
        const val SPIN_ONSET = 0.35f

        /** Per second: the spin reaches most of a kick in about half a second. */
        const val SPIN_EASE = 2.5f

        /**
         * The spin rate for the next frame, eased from [rate] towards what the
         * beat asks for.
         *
         * The first version added the beat to the counter-turn outright. An
         * onset was strong enough to cancel it and flip it for a moment, so on
         * the device the arm stopped streaming, ran backwards, and snapped
         * forward again with every word: "it pauses and jerks as I talk". The
         * beat now scales the counter-turn instead — it can only surge in the
         * direction it is already going — and the rate eases, so a kick is a
         * swell rather than a step.
         */
        fun spinRate(rate: Float, bass: Float, onset: Float, dt: Float): Float {
            val surge = 1f + SPIN_BASS * bass.coerceIn(0f, 1f) + SPIN_ONSET * onset.coerceIn(0f, 1f)
            val target = COUNTER_SPIN * surge
            return rate + (target - rate) * (dt * SPIN_EASE).coerceIn(0f, 1f)
        }
    }
}
