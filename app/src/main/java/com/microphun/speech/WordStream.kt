package com.microphun.speech

import java.util.concurrent.ArrayBlockingQueue

/**
 * Hand-off of recognised speech from the recogniser to the GL thread.
 *
 * The same rule as the audio path applies: the producer never blocks and never
 * allocates unboundedly. Words arrive on the main thread as the recogniser
 * revises its hypothesis and are drained once per rendered frame. If the
 * consumer is not running — any visual that does not listen — the queue fills
 * and new words are dropped rather than queued up for a burst later.
 *
 * Whole utterances travel separately, for [WordCorpus]. They are not the same
 * words as the settled stream: an utterance is the recogniser's final
 * hypothesis, so it carries the revisions the word-by-word stream could not
 * take back.
 */
class WordStream {

    private val queue = ArrayBlockingQueue<String>(CAPACITY)
    private val utterances = ArrayBlockingQueue<List<String>>(UTTERANCE_CAPACITY)

    /** Offers a word, dropping it if the queue is full. Never blocks. */
    fun offer(word: String) {
        queue.offer(word)
    }

    /** Removes and returns the oldest word, or null when empty. */
    fun poll(): String? = queue.poll()

    /** Offers a finished utterance, dropping it if the queue is full. Never blocks. */
    fun offerUtterance(words: List<String>) {
        if (words.isNotEmpty()) utterances.offer(words)
    }

    /** Removes and returns the oldest utterance, or null when empty. */
    fun pollUtterance(): List<String>? = utterances.poll()

    fun clear() {
        queue.clear()
        utterances.clear()
    }

    private companion object {
        /**
         * A couple of seconds of fast speech. Deeper than this only delays
         * words that are already too stale to be worth showing.
         */
        const val CAPACITY = 32

        /**
         * Utterances are drained every frame, so this only fills while nothing
         * is collecting them. A handful is plenty; the corpus is a record of
         * the visual's own session, not a backlog.
         */
        const val UTTERANCE_CAPACITY = 8
    }
}
