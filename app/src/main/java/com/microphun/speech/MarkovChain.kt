package com.microphun.speech

import kotlin.random.Random

/**
 * Invents phrases from what the room has said.
 *
 * A plain order-1 chain over a corpus this small is nearly deterministic: with
 * a few hundred words of speech most pairs are seen exactly once, so "walking"
 * it just replays the input until it reaches a word with two successors. Four
 * things keep it from being an echo:
 *
 *  - **Backoff.** When a word has only one successor, sometimes ignore it and
 *    jump somewhere else in the corpus. This is where recombination comes from.
 *  - **Recency.** Successors heard recently are likelier than ones from the
 *    start of the session, so the chain drifts as the conversation drifts.
 *  - **No looping.** A word appears once per phrase unless someone used it
 *    twice in one utterance. Over a small corpus the walk keeps coming back to
 *    the same few words, and "this time uh while the this time" is the result.
 *  - **A replay guard.** A phrase may never reproduce [WordCorpus.GUARD_RUN]
 *    consecutive words that were actually heard. Three words in a row is a
 *    coincidence; four is quoting someone at a screen other people can see.
 *
 * [random] is injectable so generation is reproducible under test.
 */
class MarkovChain(
    private val corpus: WordCorpus,
    private val random: Random = Random.Default,
    private val backoffChance: Float = BACKOFF_CHANCE,
    private val recencyWeight: Float = RECENCY_WEIGHT,
) {

    /**
     * A phrase of [MIN_PHRASE]..[MAX_PHRASE] words, or null when the corpus is
     * too thin to be worth reading.
     */
    fun generate(): List<String>? {
        if (corpus.words < MIN_CORPUS) return null

        val target = random.nextInt(MIN_PHRASE, MAX_PHRASE + 1)
        val phrase = ArrayList<String>(target)
        phrase.add(start())

        while (phrase.size < target) {
            phrase.add(next(phrase) ?: break)
        }

        // Having reached the target, keep walking a little if the phrase is
        // mid-breath — landing on a natural stop beats trimming back to one.
        while (phrase.size < MAX_PHRASE && !corpus.endsUtterance(phrase.last())) {
            phrase.add(next(phrase) ?: break)
        }

        return if (phrase.size >= MIN_PHRASE) settle(phrase) else null
    }

    /**
     * Trims back to a word that ended something the room said.
     *
     * A walk stops when it runs out of length, which routinely leaves a phrase
     * dangling on "the" or "by". Backing up to the last word that ever finished
     * an utterance reads as a thought rather than a truncation — but only while
     * the phrase stays long enough to be worth showing.
     */
    private fun settle(phrase: List<String>): List<String> {
        if (corpus.endsUtterance(phrase.last())) return phrase

        for (end in phrase.size - 1 downTo MIN_PHRASE) {
            if (corpus.endsUtterance(phrase[end - 1])) return phrase.subList(0, end)
        }
        return phrase
    }

    /** Usually the start of something someone said, so phrases open like one. */
    private fun start(): String =
        if (corpus.openingCount > 0 && random.nextFloat() < OPENING_CHANCE) {
            corpus.opening(random.nextInt(corpus.openingCount))
        } else {
            jump()
        }

    private fun next(phrase: List<String>): String? {
        val last = phrase.last()
        val options = corpus.successorsOf(last)

        // A word with one successor is a rail, not a choice; taking it every
        // time is what makes a small chain regurgitate.
        val onRails = options.isNotEmpty() && options.all { it.word == options[0].word }
        val follow = options.isNotEmpty() && !(onRails && random.nextFloat() < backoffChance)

        var candidate = if (follow) weighted(options) else jump()

        var tries = 0
        while (rejected(phrase, last, candidate) && tries < JUMP_TRIES) {
            candidate = jump()
            tries++
        }
        return if (rejected(phrase, last, candidate)) null else candidate
    }

    private fun rejected(phrase: List<String>, last: String, candidate: String): Boolean =
        candidate == last ||
            (candidate in phrase && !corpus.repeatsWithin(candidate)) ||
            corpus.heardRun(phrase + candidate)

    /** Picks a successor, favouring ones heard recently. */
    private fun weighted(options: List<WordCorpus.Successor>): String {
        val oldest = options.minOf { it.utterance }
        val span = (options.maxOf { it.utterance } - oldest).coerceAtLeast(1)

        var total = 0f
        for (option in options) total += weight(option, oldest, span)

        var pick = random.nextFloat() * total
        for (option in options) {
            pick -= weight(option, oldest, span)
            if (pick <= 0f) return option.word
        }
        return options.last().word
    }

    private fun weight(option: WordCorpus.Successor, oldest: Int, span: Int): Float =
        1f + recencyWeight * (option.utterance - oldest) / span

    private fun jump(): String = corpus.word(random.nextInt(corpus.words))

    companion object {
        const val MIN_PHRASE = 3
        const val MAX_PHRASE = 7

        /** Below this there is nothing to recombine, only to repeat. */
        const val MIN_CORPUS = 12

        const val BACKOFF_CHANCE = 0.35f
        const val RECENCY_WEIGHT = 2f
        const val OPENING_CHANCE = 0.6f

        /** Attempts to find a word the guard allows before giving up. */
        private const val JUMP_TRIES = 8
    }
}
