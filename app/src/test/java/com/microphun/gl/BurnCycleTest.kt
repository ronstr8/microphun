package com.microphun.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnCycleTest {

    /** Runs until the next swap, returning the seconds it took. */
    private fun BurnCycle.untilSwap(level: Float, onset: (Int) -> Float = { 0f }): Float {
        var frames = 0
        while (!advance(STEP, level, onset(frames))) {
            frames++
            check(frames < 60 * 600) { "never swapped" }
        }
        return (frames + 1) * STEP
    }

    @Test
    fun `the first frame asks for an image`() {
        val cycle = BurnCycle()
        assertTrue(cycle.advance(STEP, 0f, 0f))
        assertFalse(cycle.advance(STEP, 0f, 0f))
        assertEquals(0, cycle.round)
    }

    @Test
    fun `burn only grows, and swaps once at the centre`() {
        val cycle = BurnCycle()
        cycle.advance(STEP, 0f, 0f)

        var last = cycle.burn
        var swaps = 0
        repeat(60 * 200) { frame ->
            val loud = frame % 30 == 0
            if (cycle.advance(STEP, 0.5f, if (loud) 1f else 0f)) {
                swaps++
                assertEquals("a swap restarts the burn", 0f, cycle.burn)
                assertEquals("and flashes", 1f, cycle.poof)
                last = 0f
            } else {
                assertTrue("went backwards", cycle.burn >= last)
                last = cycle.burn
            }
        }
        assertTrue("expected several rounds, got $swaps", swaps >= 3)
        assertEquals(swaps, cycle.round)
    }

    @Test
    fun `a silent room still gets a new image`() {
        val cycle = BurnCycle()
        cycle.advance(STEP, 0f, 0f)
        assertEquals(90f, cycle.untilSwap(level = 0f), 1f)
    }

    @Test
    fun `the beat burns faster than silence`() {
        val silent = BurnCycle().apply { advance(STEP, 0f, 0f) }.untilSwap(level = 0f)
        val loud = BurnCycle().apply { advance(STEP, 0f, 0f) }
            .untilSwap(level = 0.5f) { frame -> if (frame % 30 < 8) 1f else 0f }

        assertTrue("loud $loud s is not faster than silent $silent s", loud < silent / 2)
    }

    @Test
    fun `rounds alternate between visuals and photos`() {
        val cycle = BurnCycle()
        val sources = ArrayList<BurnCycle.Source>()
        cycle.advance(STEP, 0f, 0f)
        repeat(4) {
            sources.add(cycle.source(hasPhotos = true))
            cycle.untilSwap(level = 1f) { 1f }
        }
        assertEquals(
            listOf(BurnCycle.Source.VISUAL, BurnCycle.Source.PHOTO, BurnCycle.Source.VISUAL, BurnCycle.Source.PHOTO),
            sources,
        )
    }

    @Test
    fun `without photos every round is a visual`() {
        val cycle = BurnCycle()
        cycle.advance(STEP, 0f, 0f)
        repeat(4) {
            assertEquals(BurnCycle.Source.VISUAL, cycle.source(hasPhotos = false))
            cycle.untilSwap(level = 1f) { 1f }
        }
    }

    @Test
    fun `the flash decays after a swap`() {
        val cycle = BurnCycle()
        cycle.advance(STEP, 0f, 0f)
        cycle.untilSwap(level = 1f) { 1f }
        assertEquals(1f, cycle.poof)

        repeat(90) { cycle.advance(STEP, 0f, 0f) }
        assertTrue("still flashing: ${cycle.poof}", cycle.poof < 0.2f)
    }

    @Test
    fun `reset starts over with a new first image`() {
        val cycle = BurnCycle()
        cycle.advance(STEP, 0f, 0f)
        cycle.untilSwap(level = 1f) { 1f }
        cycle.reset()

        assertEquals(0, cycle.round)
        assertEquals(0f, cycle.burn)
        assertTrue(cycle.advance(STEP, 0f, 0f))
    }

    private companion object {
        const val STEP = 1f / 60f
    }
}
