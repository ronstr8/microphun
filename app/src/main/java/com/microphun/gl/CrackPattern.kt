package com.microphun.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * The cracks one impact leaves in glass: straight lines, so no GL here and the
 * shapes can be tested on the host. [CrackLayer] draws them.
 *
 * Struck glass fails in a few recognisable ways, and a screen of identical
 * stars reads as a swarm of little spiders rather than as damage — the first
 * version on the device did exactly that. So each blow picks a form:
 *
 *  - **Star**: radials out from the point of impact, few or many, very unequal
 *    in length, often thrown mostly one way.
 *  - **Run**: one or two long wandering cracks straight through the impact —
 *    the stress crack that runs across a phone screen.
 *  - **Chip**: a tight cluster of short fractures, like a knock on a corner.
 *  - **Web**: a star whose radials are joined by rings, for hard blows.
 *
 * The size of the blow scales whichever form it is: reach, count, width. Soft
 * blows lean towards chips, runs and small stars; hard ones towards webs.
 */
object CrackPattern {

    /** One straight piece of crack, in pixels, [width] pixels wide. */
    class Segment(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val width: Float,
    ) {
        val length: Float get() = hypot(x1 - x0, y1 - y0)
    }

    enum class Form { STAR, RUN, CHIP, WEB }

    /** How far the cracks of a blow of [size] reach, as a fraction of [scale]. */
    fun reach(size: Float, scale: Float): Float =
        scale * (MIN_REACH + (MAX_REACH - MIN_REACH) * size.coerceIn(0f, 1f).pow(1.3f))

    /** The form a blow of [size] takes, drawn at random but weighted by size. */
    fun form(size: Float, random: Random): Form {
        val roll = random.nextFloat()
        return if (size < 0.4f) {
            when {
                roll < 0.35f -> Form.RUN
                roll < 0.6f -> Form.CHIP
                else -> Form.STAR
            }
        } else {
            when {
                roll < 0.2f -> Form.RUN
                roll < 0.45f -> Form.STAR
                else -> Form.WEB
            }
        }
    }

    /**
     * The cracks from a blow at ([x], [y]) of [size] (0..1). [scale] is the
     * screen's short side in pixels, so a blow looks the same on any screen.
     */
    fun generate(x: Float, y: Float, size: Float, scale: Float, random: Random): List<Segment> {
        val strength = size.coerceIn(0f, 1f)
        val reach = reach(strength, scale)
        val rootWidth = MIN_WIDTH + (MAX_WIDTH - MIN_WIDTH) * strength
        val segments = ArrayList<Segment>()

        when (form(strength, random)) {
            Form.RUN -> run(segments, x, y, reach, rootWidth, strength, random)
            Form.CHIP -> chip(segments, x, y, reach, rootWidth, random)
            Form.STAR -> star(segments, x, y, reach, rootWidth, strength, random, rings = false)
            Form.WEB -> {
                star(segments, x, y, reach, rootWidth, strength, random, rings = true)
                // Hard blows often send one crack running on past the web.
                if (random.nextFloat() < 0.5f) run(segments, x, y, reach, rootWidth * 0.8f, strength, random, both = false)
            }
        }
        return segments
    }

    private fun star(
        out: MutableList<Segment>,
        x: Float,
        y: Float,
        reach: Float,
        rootWidth: Float,
        strength: Float,
        random: Random,
        rings: Boolean,
    ) {
        val fewest = MIN_RAYS + (strength * 4).roundToInt()
        val most = fewest + 3 + (strength * (MAX_RAYS - MIN_RAYS - 4)).roundToInt()
        val count = random.nextInt(fewest, most + 1)
        val step = (2.0 * PI / count).toFloat()
        val start = random.nextFloat() * (2f * PI.toFloat())

        // A glancing blow throws its cracks mostly one way: radials near this
        // direction run long, the rest stay short.
        val thrown = random.nextFloat() * (2f * PI.toFloat())
        val bias = random.nextFloat() * 0.8f

        val radials = ArrayList<List<Pair<Float, Float>>>(count)
        for (i in 0 until count) {
            val angle = start + i * step + (random.nextFloat() - 0.5f) * step * ANGLE_JITTER
            val facing = (1f + cos(angle - thrown)) * 0.5f
            // Most radials short, a few long: squaring skews towards short.
            val spread = random.nextFloat().let { it * it }
            val length = reach * (0.2f + 0.8f * spread) * (1f - bias + bias * facing).coerceAtLeast(0.2f)
            radials.add(walk(out, x, y, angle, length, rootWidth, strength, random, branches = true))
        }

        if (!rings) return
        val ringCount = 1 + (strength * (MAX_RINGS - 1)).roundToInt()
        for (ring in 1..ringCount) {
            val radius = reach * ring / (ringCount + 1f) * (0.8f + 0.4f * random.nextFloat())
            for (i in 0 until count) {
                if (random.nextFloat() > RING_CHANCE) continue
                val a = pointAt(radials[i], x, y, radius) ?: continue
                val b = pointAt(radials[(i + 1) % count], x, y, radius) ?: continue
                // Bowed outward a little, the way ring cracks curve.
                val mx = (a.first + b.first) * 0.5f
                val my = (a.second + b.second) * 0.5f
                val bow = 1f + RING_BOW * (random.nextFloat() - 0.3f)
                val cx = x + (mx - x) * bow
                val cy = y + (my - y) * bow
                val width = rootWidth * RING_WIDTH
                out.add(Segment(a.first, a.second, cx, cy, width))
                out.add(Segment(cx, cy, b.first, b.second, width))
            }
        }
    }

    /** One long crack through the impact, both ways unless [both] is false. */
    private fun run(
        out: MutableList<Segment>,
        x: Float,
        y: Float,
        reach: Float,
        rootWidth: Float,
        strength: Float,
        random: Random,
        both: Boolean = true,
    ) {
        // A run travels much further for its size than a star's radials do.
        val length = reach * RUN_STRETCH
        val angle = random.nextFloat() * (2f * PI.toFloat())
        walk(out, x, y, angle, length * (0.6f + 0.4f * random.nextFloat()), rootWidth, strength, random, branches = true, wander = RUN_WANDER)
        if (both) {
            walk(out, x, y, angle + PI.toFloat(), length * (0.3f + 0.6f * random.nextFloat()), rootWidth, strength, random, branches = true, wander = RUN_WANDER)
        }
        // A second, fainter run crossing the first, sometimes.
        if (random.nextFloat() < 0.35f) {
            val cross = angle + PI.toFloat() * (0.3f + 0.4f * random.nextFloat())
            walk(out, x, y, cross, length * 0.5f, rootWidth * 0.7f, strength, random, branches = false, wander = RUN_WANDER)
        }
    }

    /** A cluster of short fractures, tight around the impact. */
    private fun chip(out: MutableList<Segment>, x: Float, y: Float, reach: Float, rootWidth: Float, random: Random) {
        val pieces = random.nextInt(5, 12)
        val radius = reach * CHIP_RADIUS
        repeat(pieces) {
            val angle = random.nextFloat() * (2f * PI.toFloat())
            val length = radius * (0.3f + 0.7f * random.nextFloat())
            walk(out, x, y, angle, length, rootWidth * 0.8f, 0f, random, branches = false, wander = CHIP_WANDER, steps = 3)
        }
    }

    /** Walks one crack outward, appending its pieces; returns the points it passed. */
    private fun walk(
        out: MutableList<Segment>,
        x: Float,
        y: Float,
        angle: Float,
        length: Float,
        rootWidth: Float,
        strength: Float,
        random: Random,
        branches: Boolean,
        wander: Float = WANDER,
        steps: Int = STEPS,
    ): List<Pair<Float, Float>> {
        val points = ArrayList<Pair<Float, Float>>()
        points.add(x to y)
        val stepLength = length / steps
        var px = x
        var py = y
        var heading = angle
        for (s in 0 until steps) {
            heading += (random.nextFloat() - 0.5f) * wander
            val nx = px + cos(heading) * stepLength
            val ny = py + sin(heading) * stepLength
            // Thinner towards the tip.
            val width = rootWidth * (1f - TAPER * s / steps)
            out.add(Segment(px, py, nx, ny, width))
            points.add(nx to ny)

            if (branches && s in 1 until steps - 1 && random.nextFloat() < BRANCH_CHANCE * (0.4f + strength)) {
                val side = if (random.nextBoolean()) 1f else -1f
                walk(
                    out, nx, ny, heading + side * (0.35f + 0.5f * random.nextFloat()),
                    length * BRANCH_LENGTH * (steps - s) / steps, width * 0.7f, strength, random,
                    branches = false, wander = wander,
                )
            }
            px = nx
            py = ny
        }
        return points
    }

    /** The first point along [radial] at least [radius] from the impact. */
    private fun pointAt(radial: List<Pair<Float, Float>>, x: Float, y: Float, radius: Float): Pair<Float, Float>? =
        radial.firstOrNull { hypot(it.first - x, it.second - y) >= radius }

    /** A tap still leaves a visible mark; a bang reaches most of the way across. */
    const val MIN_REACH = 0.06f
    const val MAX_REACH = 0.8f

    /** A run travels this many times a star's reach; see [maxExtent]. */
    const val RUN_STRETCH = 2.2f

    const val MIN_RAYS = 3
    const val MAX_RAYS = 16
    const val MAX_RINGS = 3

    /** Pixels, at the root of a crack. */
    const val MIN_WIDTH = 1.2f
    const val MAX_WIDTH = 5f

    /** Nothing a blow of [size] makes reaches further than this from it. */
    fun maxExtent(size: Float, scale: Float): Float = reach(size, scale) * RUN_STRETCH

    private const val STEPS = 9
    private const val ANGLE_JITTER = 0.9f
    private const val WANDER = 0.4f
    private const val RUN_WANDER = 0.3f
    private const val CHIP_WANDER = 0.9f
    private const val CHIP_RADIUS = 0.35f
    private const val TAPER = 0.7f
    private const val BRANCH_CHANCE = 0.2f
    private const val BRANCH_LENGTH = 0.45f
    private const val RING_CHANCE = 0.75f
    private const val RING_BOW = 0.25f
    private const val RING_WIDTH = 0.6f
}
