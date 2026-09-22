package com.microphun.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordCorpusTest {

    @Test
    fun `successors do not cross utterance boundaries`() {
        val corpus = WordCorpus()
        corpus.add(listOf("the", "cat"))
        corpus.add(listOf("sat", "down"))

        // "cat" ended an utterance, so nothing follows it. Without boundaries a
        // chain would happily say "cat sat" from two separate sentences.
        assertTrue(corpus.successorsOf("cat").isEmpty())
        assertEquals(listOf("down"), corpus.successorsOf("sat").map { it.word })
    }

    @Test
    fun `successors accumulate across utterances`() {
        val corpus = WordCorpus()
        corpus.add(listOf("go", "left"))
        corpus.add(listOf("go", "right"))

        assertEquals(setOf("left", "right"), corpus.successorsOf("go").map { it.word }.toSet())
    }

    @Test
    fun `recency is recorded so the chain can favour it`() {
        val corpus = WordCorpus()
        corpus.add(listOf("go", "left"))
        corpus.add(listOf("go", "right"))

        val byWord = corpus.successorsOf("go").associate { it.word to it.utterance }
        assertTrue("later utterance must carry a higher number", byWord.getValue("right") > byWord.getValue("left"))
    }

    @Test
    fun `a heard run of the guard length is recognised`() {
        val corpus = WordCorpus()
        corpus.add(listOf("one", "two", "three", "four", "five"))

        assertTrue(corpus.heardRun(listOf("one", "two", "three", "four")))
        assertTrue(corpus.heardRun(listOf("two", "three", "four", "five")))
        // Only the tail matters: this is asked mid-phrase, about what would be.
        assertTrue(corpus.heardRun(listOf("zero", "two", "three", "four", "five")))
    }

    @Test
    fun `shorter and unheard runs are not flagged`() {
        val corpus = WordCorpus()
        corpus.add(listOf("one", "two", "three", "four", "five"))

        assertFalse("three words is a coincidence, not a quotation",
            corpus.heardRun(listOf("two", "three", "four")))
        assertFalse(corpus.heardRun(listOf("one", "two", "three", "nine")))
    }

    @Test
    fun `a run spanning two utterances was never heard`() {
        val corpus = WordCorpus()
        corpus.add(listOf("one", "two"))
        corpus.add(listOf("three", "four"))

        assertFalse(corpus.heardRun(listOf("one", "two", "three", "four")))
    }

    @Test
    fun `the oldest words fall off once the cap is passed`() {
        val corpus = WordCorpus(maxWords = 20)
        repeat(30) { corpus.add(listOf("utterance$it", "filler", "words", "here")) }

        assertTrue("stays bounded", corpus.words <= 20)
        // The index is rebuilt on eviction, so evicted words leave no trace.
        assertTrue(corpus.successorsOf("utterance0").isEmpty())
        assertFalse(corpus.heardRun(listOf("utterance0", "filler", "words", "here")))
    }

    @Test
    fun `clear empties everything`() {
        val corpus = WordCorpus()
        corpus.add(listOf("one", "two", "three", "four"))
        corpus.clear()

        assertEquals(0, corpus.words)
        assertEquals(0, corpus.openingCount)
        assertTrue(corpus.successorsOf("one").isEmpty())
        assertFalse(corpus.heardRun(listOf("one", "two", "three", "four")))
    }
}
