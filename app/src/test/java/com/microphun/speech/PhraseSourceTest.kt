package com.microphun.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class PhraseSourceTest {

    @Test
    fun `collect drains every waiting utterance`() {
        val stream = WordStream()
        stream.offerUtterance(listOf("turn", "it", "up"))
        stream.offerUtterance(listOf("that", "is", "loud"))

        val phrases = PhraseSource()
        assertEquals(2, phrases.collect(stream))
        assertEquals(6, phrases.words)
        assertNull("the stream must be empty afterwards", stream.pollUtterance())
        assertEquals(0, phrases.collect(stream))
    }

    @Test
    fun `utterances and words travel separately`() {
        val stream = WordStream()
        stream.offer("turn")
        stream.offerUtterance(listOf("turn", "it", "up"))

        PhraseSource().collect(stream)

        // The word cloud's queue is untouched by the corpus draining its own.
        assertEquals("turn", stream.poll())
        assertNull(stream.poll())
    }

    @Test
    fun `empty utterances are not queued`() {
        val stream = WordStream()
        stream.offerUtterance(emptyList())
        assertNull(stream.pollUtterance())
    }

    @Test
    fun `a full utterance queue drops rather than blocks`() {
        val stream = WordStream()
        repeat(100) { stream.offerUtterance(listOf("again", "and", "again")) }

        val phrases = PhraseSource()
        val arrived = phrases.collect(stream)
        assert(arrived in 1 until 100) { "expected a bounded queue, got $arrived" }
    }

    @Test
    fun `phrases come once the corpus is thick enough, and go with clear`() {
        val stream = WordStream()
        val phrases = PhraseSource(Random(7))

        stream.offerUtterance(listOf("the", "bass", "is", "too", "quiet"))
        phrases.collect(stream)
        assertNull("five words is too thin to invent from", phrases.generate())

        stream.offerUtterance(listOf("turn", "the", "bass", "up", "a", "bit"))
        stream.offerUtterance(listOf("the", "lights", "are", "too", "bright", "now"))
        phrases.collect(stream)
        assertNotNull(phrases.generate())

        phrases.clear()
        assertEquals(0, phrases.words)
        assertNull(phrases.generate())
    }
}
