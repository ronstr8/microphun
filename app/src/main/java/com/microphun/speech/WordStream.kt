package com.microphun.speech

import java.util.concurrent.ArrayBlockingQueue

/**
 * Hand-off of recognised words from the recogniser to the GL thread.
 *
 * The same rule as the audio path applies: the producer never blocks and never
 * allocates unboundedly. Words arrive on the main thread as the recogniser
 * revises its hypothesis and are drained once per rendered frame. If the
 * consumer is not running — any visual other than `words` — the queue fills
 * and new words are dropped rather than queued up for a burst later.
 */
class WordStream {

    private val queue = ArrayBlockingQueue<String>(CAPACITY)

    /** Offers a word, dropping it if the queue is full. Never blocks. */
    fun offer(word: String) {
        queue.offer(word)
    }

    /** Removes and returns the oldest word, or null when empty. */
    fun poll(): String? = queue.poll()

    fun clear() = queue.clear()

    private companion object {
        /**
         * A couple of seconds of fast speech. Deeper than this only delays
         * words that are already too stale to be worth showing.
         */
        const val CAPACITY = 32
    }
}
