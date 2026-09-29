package com.microphun.gl

import kotlin.math.hypot
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrackPatternTest {

    private val scale = 1440f

    private fun crack(size: Float, seed: Long) =
        CrackPattern.generate(500f, 400f, size, scale, Random(seed))

    @Test
    fun `the same seed cracks the same way`() {
        val a = crack(0.6f, 3)
        val b = crack(0.6f, 3)
        assertEquals(a.size, b.size)
        a.zip(b).forEach { (p, q) ->
            assertEquals(p.x0, q.x0)
            assertEquals(p.y1, q.y1)
        }
    }

    @Test
    fun `cracks stay within the blow's extent`() {
        for (size in listOf(0f, 0.3f, 0.7f, 1f)) {
            val extent = CrackPattern.maxExtent(size, scale)
            repeat(80) { seed ->
                for (s in crack(size, seed.toLong())) {
                    assertTrue(hypot(s.x0 - 500f, s.y0 - 400f) <= extent * 1.05f)
                    assertTrue("size $size reaches ${hypot(s.x1 - 500f, s.y1 - 400f)} of $extent",
                        hypot(s.x1 - 500f, s.y1 - 400f) <= extent * 1.05f)
                }
            }
        }
    }

    @Test
    fun `every form starts at the point of impact`() {
        repeat(40) { seed ->
            val fromImpact = crack(seed / 40f, seed.toLong()).count { it.x0 == 500f && it.y0 == 400f }
            assertTrue("seed $seed: only $fromImpact cracks start at the impact", fromImpact >= 1)
        }
    }

    @Test
    fun `blows of the same size do not all look alike`() {
        // The first version gave every small blow four even arms, and a screen
        // of them read as a swarm of spiders. Same size, many seeds: the forms
        // must vary, and so must the number of pieces.
        for (size in listOf(0.1f, 0.6f)) {
            val forms = (0 until 60).map { CrackPattern.form(size, Random(it.toLong())) }.toSet()
            assertTrue("size $size only ever takes $forms", forms.size >= 3)
            val counts = (0 until 60).map { crack(size, it.toLong()).size }.toSet()
            assertTrue("size $size always has the same piece count: $counts", counts.size >= 8)
        }
    }

    @Test
    fun `a star's arms are not all the same length`() {
        repeat(20) { seed ->
            val segments = crack(0.5f, seed.toLong())
            val starts = segments.filter { it.x0 == 500f && it.y0 == 400f }
            if (starts.size < 3) return@repeat
            val lengths = starts.map { it.length }
            // Arms are walked in equal steps, so the first step's length is the arm's length / steps.
            assertTrue("seed $seed: even arms $lengths", lengths.max() > lengths.min() * 1.5f)
        }
    }

    @Test
    fun `a bigger blow leaves more, longer and thicker cracks`() {
        fun average(size: Float, measure: (List<CrackPattern.Segment>) -> Float) =
            (0 until 40).map { measure(crack(size, it.toLong())) }.average()

        val sizes = listOf(0.1f, 0.5f, 1f)
        val lengths = sizes.map { size -> average(size) { segs -> segs.sumOf { it.length.toDouble() }.toFloat() } }
        val counts = sizes.map { size -> average(size) { it.size.toFloat() } }
        val widths = sizes.map { size -> average(size) { segs -> segs.maxOf { it.width } } }

        for (i in 1 until sizes.size) {
            assertTrue("total length not growing: $lengths", lengths[i] > lengths[i - 1] * 1.5)
            assertTrue("count not growing: $counts", counts[i] > counts[i - 1])
            assertTrue("width not growing: $widths", widths[i] > widths[i - 1])
        }
    }

    @Test
    fun `every piece has a positive width and a length`() {
        repeat(30) { seed ->
            for (s in crack(seed / 30f, seed.toLong())) {
                assertTrue(s.width > 0f)
                assertTrue(s.length > 0f)
            }
        }
    }
}

class CrackCycleTest {

    private val step = 1f / 60f

    @Test
    fun `the first frame asks for an image`() {
        val cycle = CrackCycle()
        assertTrue(cycle.advance(step, 0f, 0f).newImage)
        assertFalse(cycle.advance(step, 0f, 0f).newImage)
    }

    @Test
    fun `a held onset is one blow, not one per frame`() {
        val cycle = CrackCycle()
        cycle.advance(step, 0f, 0f)
        var blows = 0
        repeat(60) { if (cycle.advance(step, 0.5f, 1f).impact >= 0f) blows++ }
        assertEquals(1, blows)
    }

    @Test
    fun `beats closer than the gap are one blow`() {
        val cycle = CrackCycle()
        cycle.advance(step, 0f, 0f)
        var blows = 0
        // On, off, on again two frames later: well inside the gap. Then quiet
        // long enough for the blow to land after its peak window.
        for (onset in listOf(1f, 0f, 1f, 0f, 1f) + List(10) { 0f }) {
            if (cycle.advance(step, 0.5f, onset).impact >= 0f) blows++
        }
        assertEquals(1, blows)
    }

    @Test
    fun `an onset in a silent room is not a blow`() {
        val cycle = CrackCycle()
        cycle.advance(step, 0f, 0f)
        var blows = 0
        repeat(600) { frame ->
            val onset = if (frame % 30 == 0) 1f else 0f
            if (cycle.advance(step, 0.01f, onset).impact >= 0f) blows++
        }
        assertEquals(0, blows)
        assertEquals(0f, cycle.damage)
        assertEquals(0f, cycle.flash)
    }

    @Test
    fun `silence does not make the next faint noise a big crack`() {
        // Twenty silent onsets, then a faint real one. Were the silent ones
        // ranked, the faint noise would top them all and crack like a bang.
        val cycle = CrackCycle(Random(5))
        cycle.advance(step, 0f, 0f)
        var size = -1f
        repeat(21 * 30) { frame ->
            val level = if (frame < 20 * 30) 0.005f else 0.05f
            val result = cycle.advance(step, level, if (frame % 30 == 0) 1f else 0f)
            if (result.impact >= 0f) size = result.impact
        }
        assertTrue("faint noise made size $size", size in 0f..0.2f)
    }

    @Test
    fun `louder noises strike harder`() {
        // Averaged over the jitter, and with no history, so absolute only.
        fun size(level: Float) = (0 until 50).map { CrackCycle(Random(it.toLong())).sizeFor(level) }.average()
        val quiet = size(0.25f)
        val medium = size(0.32f)
        val loud = size(0.5f)
        assertTrue("$quiet < $medium < $loud", quiet < medium && medium < loud)
        assertTrue("loud is not big: $loud", loud > 0.9)
    }

    @Test
    fun `the loudest noise in a quiet room still makes a big crack`() {
        // A room where nothing is loud in absolute terms: a run of soft blows,
        // then one that is only somewhat louder. Ranking must make it stand out.
        val cycle = CrackCycle(Random(1))
        val soft = (0 until 12).map { cycle.sizeFor(0.18f + 0.01f * (it % 3)) }
        val standout = cycle.sizeFor(0.35f)
        assertTrue("standout $standout not above every soft blow ${soft.max()}", standout > soft.max())
        assertTrue("standout $standout is still small", standout > 0.4f)
    }

    @Test
    fun `sizes spread out when levels vary`() {
        val cycle = CrackCycle(Random(2))
        val sizes = (0 until 60).map { cycle.sizeFor(0.2f + 0.3f * Random(it.toLong()).nextFloat()) }
        assertTrue("no small blows: ${sizes.min()}", sizes.min() < 0.2f)
        assertTrue("no big blows: ${sizes.max()}", sizes.max() > 0.8f)
    }

    @Test
    fun `a blow is sized from its peak just after the onset`() {
        val cycle = CrackCycle(Random(3))
        cycle.advance(step, 0f, 0f)
        // The onset fires while the level is still climbing.
        val levels = listOf(0.2f, 0.4f, 0.6f, 0.7f, 0.5f, 0.3f, 0.2f, 0.2f)
        var peak = -1f
        levels.forEachIndexed { i, level ->
            val result = cycle.advance(step, level, if (i == 0) 1f else 0f)
            if (result.impact >= 0f) peak = result.peak
        }
        assertEquals(0.7f, peak)
    }

    /** One beat every half second at [level], until the glass shatters. */
    private fun blowsUntilShatter(cycle: CrackCycle, level: Float): Int {
        var blows = 0
        var frame = 0
        while (cycle.shatter == 0f) {
            val onset = if (frame % 30 == 0) 1f else 0f
            if (cycle.advance(step, level, onset).impact >= 0f) blows++
            frame++
            check(frame < 60 * 600) { "never shattered" }
        }
        return blows
    }

    @Test
    fun `a loud room shatters the glass sooner than a quiet one`() {
        val loud = blowsUntilShatter(CrackCycle(Random(4)).apply { advance(step, 0f, 0f) }, level = 0.8f)
        val soft = blowsUntilShatter(CrackCycle(Random(4)).apply { advance(step, 0f, 0f) }, level = 0.2f)
        assertTrue("loud $loud blows vs soft $soft", loud < soft / 2)
    }

    @Test
    fun `a shatter brings one new image and no blows while the glass comes down`() {
        val cycle = CrackCycle()
        cycle.advance(step, 0f, 0f)
        blowsUntilShatter(cycle, level = 0.9f)

        var images = 0
        var blows = 0
        repeat(120) { frame ->
            val result = cycle.advance(step, 0.9f, if (frame % 10 == 0) 1f else 0f)
            if (result.newImage) images++
            if (result.impact >= 0f && images == 0) blows++
        }
        assertEquals(1, images)
        assertEquals("a blow landed while shattering", 0, blows)
        assertEquals(0f, cycle.shatter)
    }

    @Test
    fun `the damage clears with the new image`() {
        val cycle = CrackCycle()
        cycle.advance(step, 0f, 0f)
        blowsUntilShatter(cycle, level = 0.9f)
        while (!cycle.advance(step, 0f, 0f).newImage) Unit
        assertEquals(0f, cycle.damage)
    }
}
