package com.microphun.speech

import kotlin.random.Random

/**
 * The GL thread's end of phrase invention: a [WordCorpus] fed from
 * [WordStream], and the [MarkovChain] that walks it.
 *
 * The corpus is not thread safe, and the recogniser reports on the main thread
 * while the visual that shows phrases draws on the GL thread. Rather than lock
 * it, utterances cross over through the stream like words do, and the corpus
 * lives entirely on the side that reads it.
 */
class PhraseSource(random: Random = Random.Default) {

    private val corpus = WordCorpus()
    private val chain = MarkovChain(corpus, random)

    /** Words heard this session, for deciding whether there is anything to say. */
    val words: Int get() = corpus.words

    /** Moves every waiting utterance into the corpus. Returns how many arrived. */
    fun collect(stream: WordStream): Int {
        var added = 0
        while (true) {
            corpus.add(stream.pollUtterance() ?: return added)
            added++
        }
    }

    /** A new phrase, or null while the corpus is too thin. See [MarkovChain.generate]. */
    fun generate(): List<String>? = chain.generate()

    /** Forgets the session. Same posture as the microphone. */
    fun clear() = corpus.clear()
}
