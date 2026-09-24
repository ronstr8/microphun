package com.microphun.speech

/**
 * What the room has said this session, in the order it was said.
 *
 * Deliberately session-only: it lives in memory, is never written anywhere, and
 * is cleared when the visual collecting it goes away. It is also bounded, so a
 * long evening does not grow without limit — the oldest utterances fall off.
 *
 * Utterances are kept apart rather than poured into one stream. A chain built
 * from a flat list of words will happily follow the last word of one sentence
 * with the first word of something said four minutes later; keeping the
 * boundaries means generated phrases have sentence-shaped joins.
 *
 * Not thread safe: feed it and read it from one thread.
 */
class WordCorpus(private val maxWords: Int = MAX_WORDS) {

    /** A word that followed another, and when it was heard. */
    class Successor(val word: String, val utterance: Int)

    private val utterances = ArrayList<List<String>>()
    private val successors = HashMap<String, MutableList<Successor>>()

    /** Words that began an utterance, so generated phrases can start like one. */
    private val openings = ArrayList<String>()

    /** Words that ended one, so phrases can stop somewhere that sounds finished. */
    private val endings = HashSet<String>()
    private val flat = ArrayList<String>()

    /** Every run of [GUARD_RUN] words actually heard, for the replay guard. */
    private val runs = HashSet<String>()

    /**
     * Words someone used twice in one utterance with something in between, so
     * a phrase may too. A stutter or a chant does not count.
     */
    private val repeated = HashSet<String>()

    private var heard = 0

    val words: Int get() = flat.size
    val utteranceCount: Int get() = utterances.size

    fun add(said: List<String>) {
        // Hesitations are transcribed faithfully, and a chain replays them
        // mid-phrase as if they were words.
        val utterance = said.filterNot { it in FILLERS }
        if (utterance.isEmpty()) return

        utterances.add(utterance)
        index(utterance, heard++)

        if (flat.size > maxWords) {
            // Drop well below the cap and reindex once, rather than paying for
            // a rebuild on every single word past the limit.
            while (flat.size > maxWords * KEEP_FRACTION && utterances.size > 1) {
                val oldest = utterances.removeAt(0)
                flat.subList(0, oldest.size).clear()
            }
            reindex()
        }
    }

    fun clear() {
        utterances.clear()
        successors.clear()
        openings.clear()
        endings.clear()
        flat.clear()
        runs.clear()
        repeated.clear()
        heard = 0
    }

    fun successorsOf(word: String): List<Successor> = successors[word] ?: emptyList()

    /** True if [word] ever ended an utterance. */
    fun endsUtterance(word: String): Boolean = endings.contains(word)

    fun opening(index: Int): String = openings[index]

    val openingCount: Int get() = openings.size

    fun word(index: Int): String = flat[index]

    /**
     * True if someone used [word] twice in one breath, apart. "The cat sat on
     * the mat" earns "the" a second use in a phrase; nothing earns "time" one,
     * which is what stops a small chain looping back round to where it started.
     *
     * The two uses must not be adjacent. "Words words words" is a stutter or a
     * chant, not a sentence that needs the word twice, and counting it let one
     * repetitive utterance unlock repeats for every phrase after it.
     */
    fun repeatsWithin(word: String): Boolean = repeated.contains(word)

    /** True if these words were heard in this order, consecutively. */
    fun heardRun(run: List<String>): Boolean =
        run.size >= GUARD_RUN && runs.contains(run.takeLast(GUARD_RUN).joinToString(" "))

    private fun index(utterance: List<String>, sequence: Int) {
        openings.add(utterance.first())
        endings.add(utterance.last())
        flat.addAll(utterance)

        // A run of the same word is one use, so only a return after something
        // else counts: "hour to hour" does, "words words words" does not.
        val seen = HashSet<String>()
        for (i in utterance.indices) {
            if (i > 0 && utterance[i] == utterance[i - 1]) continue
            if (!seen.add(utterance[i])) repeated.add(utterance[i])
        }

        for (i in utterance.indices) {
            if (i + 1 < utterance.size) {
                successors.getOrPut(utterance[i]) { ArrayList() }
                    .add(Successor(utterance[i + 1], sequence))
            }
            if (i + GUARD_RUN <= utterance.size) {
                runs.add(utterance.subList(i, i + GUARD_RUN).joinToString(" "))
            }
        }
    }

    private fun reindex() {
        successors.clear()
        openings.clear()
        endings.clear()
        runs.clear()
        repeated.clear()
        flat.clear()
        utterances.forEachIndexed { sequence, utterance -> index(utterance, sequence) }
        heard = utterances.size
    }

    companion object {
        /** Roughly an hour of chat, and a few hundred kilobytes at worst. */
        const val MAX_WORDS = 2000

        /**
         * A generated phrase may not reproduce a heard run this long. Three
         * words of someone's sentence is a coincidence; four is a quotation,
         * and this visual points at a screen other people can see.
         */
        const val GUARD_RUN = 4

        private const val KEEP_FRACTION = 0.8

        /**
         * Hesitations, not words. Only the sounds themselves: "like" and "you
         * know" fill gaps too, but they are also real words.
         */
        val FILLERS = setOf("uh", "um", "umm", "uhm", "er", "erm", "ah", "hmm", "hm", "mm", "mhm")
    }
}
