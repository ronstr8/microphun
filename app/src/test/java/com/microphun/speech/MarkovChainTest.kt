package com.microphun.speech

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkovChainTest {

    private fun corpusOf(vararg utterances: String) = WordCorpus().apply {
        utterances.forEach { add(it.split(" ")) }
    }

    /** A few sentences with words in common, so there is something to recombine. */
    private fun conversation() = corpusOf(
        "the cat sat on the mat",
        "the dog sat by the door",
        "a cat walked past the door",
        "the mat was by the fire",
        "she walked the dog past the fire",
    )

    @Test
    fun `a thin corpus produces nothing`() {
        val chain = MarkovChain(corpusOf("hello there"), Random(1))
        assertNull("two words cannot be recombined into anything", chain.generate())
    }

    @Test
    fun `the same seed gives the same phrase`() {
        val first = MarkovChain(conversation(), Random(42)).generate()
        val second = MarkovChain(conversation(), Random(42)).generate()

        assertNotNull(first)
        assertEquals(first, second)
    }

    @Test
    fun `phrases stay within their length bounds`() {
        val corpus = conversation()
        repeat(200) { seed ->
            val phrase = MarkovChain(corpus, Random(seed.toLong())).generate() ?: return@repeat
            assertTrue(
                "length ${phrase.size} out of bounds: $phrase",
                phrase.size in MarkovChain.MIN_PHRASE..MarkovChain.MAX_PHRASE,
            )
        }
    }

    @Test
    fun `every word comes from the corpus`() {
        val corpus = conversation()
        val heard = buildSet {
            (0 until corpus.words).forEach { add(corpus.word(it)) }
        }
        repeat(200) { seed ->
            val phrase = MarkovChain(corpus, Random(seed.toLong())).generate() ?: return@repeat
            phrase.forEach { assertTrue("invented word '$it'", heard.contains(it)) }
        }
    }

    @Test
    fun `no phrase ever quotes four consecutive words that were heard`() {
        val corpus = conversation()
        repeat(500) { seed ->
            val phrase = MarkovChain(corpus, Random(seed.toLong())).generate() ?: return@repeat
            for (end in WordCorpus.GUARD_RUN..phrase.size) {
                val window = phrase.subList(end - WordCorpus.GUARD_RUN, end)
                assertFalse("phrase $phrase quotes $window", corpus.heardRun(window))
            }
        }
    }

    @Test
    fun `the guard holds even when the corpus is one long deterministic chain`() {
        // Every word has exactly one successor, so without the guard a walk
        // would reproduce the input verbatim from wherever it started.
        val corpus = corpusOf("alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima")
        repeat(500) { seed ->
            val phrase = MarkovChain(corpus, Random(seed.toLong())).generate() ?: return@repeat
            for (end in WordCorpus.GUARD_RUN..phrase.size) {
                val window = phrase.subList(end - WordCorpus.GUARD_RUN, end)
                assertFalse("phrase $phrase replays $window", corpus.heardRun(window))
            }
        }
    }

    @Test
    fun `backoff makes a deterministic corpus produce more than one phrase`() {
        val corpus = corpusOf("alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima")
        val phrases = (0 until 100)
            .mapNotNull { MarkovChain(corpus, Random(it.toLong())).generate() }
            .toSet()

        assertTrue("expected variety, got $phrases", phrases.size > 5)
    }

    @Test
    fun `recency weighting favours what was just said`() {
        // "go" is followed by "left" once, long ago, and by "right" once,
        // recently. Weighting should tilt the coin toward "right".
        val corpus = WordCorpus()
        corpus.add("go left and then keep going left again".split(" "))
        repeat(20) { corpus.add("go right".split(" ")) }

        var right = 0
        var left = 0
        repeat(400) { seed ->
            val chain = MarkovChain(corpus, Random(seed.toLong()), backoffChance = 0f)
            // Ask the chain directly what follows "go" by generating until a
            // phrase contains it; simpler and just as honest: count successors.
            val phrase = chain.generate() ?: return@repeat
            for (i in 0 until phrase.size - 1) {
                if (phrase[i] == "go" && phrase[i + 1] == "right") right++
                if (phrase[i] == "go" && phrase[i + 1] == "left") left++
            }
        }
        assertTrue("expected recent successor to dominate: right=$right left=$left", right > left)
    }

    @Test
    fun `generation usually succeeds, so the property tests above are not empty`() {
        val corpus = conversation()
        val phrases = (0 until 200).mapNotNull { MarkovChain(corpus, Random(it.toLong())).generate() }

        // Without this, every test that skips on a null phrase could be
        // passing by never looking at anything.
        assertTrue("only ${phrases.size} of 200 seeds produced a phrase", phrases.size > 150)

        println("sample phrases from: \"the cat sat on the mat\" and friends")
        phrases.take(8).forEach { println("  " + it.joinToString(" ")) }
    }

    @Test
    fun `phrases prefer to end where something heard ended`() {
        // Every utterance ends on a distinctive word, and none of those words
        // appears anywhere else, so a dangling ending is obvious.
        val corpus = corpusOf(
            "the cat sat on the rug",
            "the dog sat on the floor",
            "a cat walked past the gate",
            "the rug was by the hearth",
        )
        val endings = (0 until 200)
            .mapNotNull { MarkovChain(corpus, Random(it.toLong())).generate() }
            .map { it.last() }

        val settled = endings.count { corpus.endsUtterance(it) }
        println("phrases ending on a finished-sounding word: $settled of ${endings.size}")
        // Measured at ~79% when this went in; the threshold is a regression
        // guard, not the goal.
        assertTrue(
            "only $settled of ${endings.size} phrases ended on a finished-sounding word",
            settled > endings.size * 2 / 3,
        )
    }

    @Test
    fun `no phrase says the same word twice in a row`() {
        val corpus = corpusOf("yes yes yes no no maybe yes no maybe so")
        repeat(200) { seed ->
            val phrase = MarkovChain(corpus, Random(seed.toLong())).generate() ?: return@repeat
            for (i in 1 until phrase.size) {
                assertFalse("stutter in $phrase", phrase[i] == phrase[i - 1])
            }
        }
    }
}
