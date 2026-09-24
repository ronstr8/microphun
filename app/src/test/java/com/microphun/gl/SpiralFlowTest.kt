package com.microphun.gl

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiralFlowTest {

    private class Placed(
        val text: String,
        val x: Float,
        val y: Float,
        val rotation: Float,
        val height: Float,
        val alpha: Float,
    )

    private fun SpiralFlow.placed(spawn: Float = SPAWN, spin: Float = 0f): List<Placed> {
        val out = ArrayList<Placed>()
        forEachWord(spawn, spin) { text, x, y, rotation, height, alpha ->
            out.add(Placed(text, x, y, rotation, height, alpha))
        }
        return out
    }

    private fun SpiralFlow.offer(vararg words: String) =
        offer(words.toList(), FloatArray(words.size) { 3f })

    /** Runs the flow forward in frame-sized steps. */
    private fun SpiralFlow.run(seconds: Float, exit: Float = EXIT) {
        var left = seconds
        while (left > 0f) {
            advance(minOf(STEP, left), exit)
            left -= STEP
        }
    }

    @Test
    fun `a phrase waits in the centre and spills out a word at a time`() {
        val flow = SpiralFlow()
        flow.offer("down", "the", "rabbit", "hole")
        assertEquals(1, flow.pendingCount)

        flow.advance(0f, EXIT)
        assertEquals(1, flow.phraseCount)
        assertTrue("nothing is out the moment it starts", flow.placed().isEmpty())

        flow.run(0.4f)
        val early = flow.placed().map { it.text }
        assertEquals("the first word leads", "down", early.first())
        assertTrue("not all of it at once: $early", early.size < 4)

        flow.run(2f)
        assertEquals(listOf("down", "the", "rabbit", "hole"), flow.placed().map { it.text })
    }

    @Test
    fun `words sit on the spiral and grow with their radius`() {
        val flow = SpiralFlow()
        flow.offer("down", "the", "rabbit", "hole")
        flow.run(3f)

        val spin = 0.7f
        for (word in flow.placed(spin = spin)) {
            val radius = hypot(word.x, word.y)
            val rho = ln(radius / SPAWN)
            val onCurve = flow.angleAt(rho, spin)

            val miss = onCurve - atan2(word.y, word.x)
            assertTrue("${word.text} off the curve by $miss", cos(miss) > 0.99999f)
            assertEquals("${word.text} height", radius * SpiralFlow.TEXT_HEIGHT, word.height, 1e-2f)
        }
    }

    @Test
    fun `a phrase reads from the outside in`() {
        val flow = SpiralFlow()
        flow.offer("down", "the", "rabbit", "hole")
        flow.run(3f)

        val words = flow.placed()
        for (i in 1 until words.size) {
            val ahead = words[i - 1]
            val behind = words[i]
            assertTrue("${behind.text} must be inside ${ahead.text}", hypot(behind.x, behind.y) < hypot(ahead.x, ahead.y))

            // The direction each word reads should point, roughly, at the next.
            val towardNext = atan2(behind.y - ahead.y, behind.x - ahead.x)
            val readsToward = cos(ahead.rotation - towardNext)
            assertTrue("${ahead.text} reads away from ${behind.text}: $readsToward", readsToward > 0.8f)
        }
    }

    @Test
    fun `phrases leave one after another without overlapping`() {
        val flow = SpiralFlow()
        flow.offer("one", "two", "three")
        flow.offer("four", "five", "six")
        flow.offer("seven", "eight", "nine")

        // Sample the whole journey; at no point may two phrases share a stretch
        // of curve. Words of different phrases must never interleave in radius.
        repeat(400) {
            flow.advance(STEP, EXIT)
            val radii = flow.placed().map { it.text to hypot(it.x, it.y) }
            val order = radii.sortedByDescending { it.second }.map { it.first }
            val expected = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
                .filter { word -> radii.any { it.first == word } }
            assertEquals("phrases interleaved", expected, order)
        }
    }

    @Test
    fun `a phrase falls off once its last word is past the edge`() {
        val flow = SpiralFlow()
        flow.offer("gone", "soon")
        flow.advance(0f, EXIT)

        var seconds = 0f
        while (!flow.isEmpty && seconds < 120f) {
            flow.advance(STEP, EXIT)
            seconds += STEP
            // Retired only once every word is past the edge, never while one is still on screen.
            val onScreen = flow.placed().any { ln(hypot(it.x, it.y) / SPAWN) < EXIT }
            assertTrue("retired with a word still on screen", !flow.isEmpty || !onScreen)
        }
        assertTrue("never left the screen", flow.isEmpty)
        // Centre to exit at the flow's speed, plus the phrase's own length.
        assertEquals(EXIT / SpiralFlow.SPEED, seconds, 1.5f)
    }

    @Test
    fun `a backlog is short and keeps the newest`() {
        val flow = SpiralFlow()
        repeat(10) { flow.offer("phrase$it", "words") }
        assertEquals(SpiralFlow.MAX_PENDING, flow.pendingCount)

        flow.advance(0f, EXIT)
        flow.run(1f)
        assertEquals("phrase7", flow.placed().first().text)
    }

    @Test
    fun `words fade in as they leave the centre`() {
        val flow = SpiralFlow()
        flow.offer("hello", "there")
        flow.advance(0f, EXIT)
        flow.run(0.3f)

        val first = flow.placed().first()
        assertTrue("fully opaque too soon: ${first.alpha}", first.alpha < 1f)
        flow.run(3f)
        assertTrue(flow.placed().all { it.alpha == 1f })
    }

    @Test
    fun `clear empties everything`() {
        val flow = SpiralFlow()
        flow.offer("a", "b")
        flow.offer("c", "d")
        flow.advance(0f, EXIT)
        flow.clear()

        assertTrue(flow.isEmpty)
        assertTrue(flow.placed().isEmpty())
    }

    private companion object {
        const val SPAWN = 12f
        val EXIT = ln(1400f / SPAWN)
        const val STEP = 1f / 60f
    }
}
