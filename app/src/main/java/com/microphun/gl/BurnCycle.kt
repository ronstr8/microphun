package com.microphun.gl

/**
 * The `fire` visual's clock: how far the current image has burned, and when to
 * swap it for the next. No GL in it, so it can be tested on the host.
 *
 * Fire starts at the edges and eats inward. [burn] is how far it has got: 0 is
 * an untouched image, 1 has reached the centre. It only ever grows, and grows
 * faster with the room — the level feeds it and every onset shoves it on — but
 * it also creeps forward in silence, so a quiet room still gets a new image
 * eventually. Once the centre is gone the image poofs: [poof] flashes to 1 and
 * decays, and the next image takes its place.
 */
class BurnCycle {

    /** Where the image in each round comes from. */
    enum class Source { VISUAL, PHOTO }

    var burn = 0f
        private set

    /** 1 at the moment of a swap, decaying: drives the flash. */
    var poof = 0f
        private set

    /** Rounds completed, which is also what alternates the source. */
    var round = 0
        private set

    /** True until something has asked for the first image. */
    private var needsFirst = true

    /**
     * Advances by [dt] seconds of audio at [level] and [onset] (both 0..1).
     * Returns true when a new image is due: at the start, and each time the
     * burn has swallowed the centre.
     */
    fun advance(dt: Float, level: Float, onset: Float): Boolean {
        poof = (poof - dt * POOF_DECAY_PER_SECOND).coerceAtLeast(0f)

        if (needsFirst) {
            needsFirst = false
            return true
        }

        val rate = CREEP_PER_SECOND +
            LEVEL_PER_SECOND * level.coerceIn(0f, 1f) +
            ONSET_PER_SECOND * onset.coerceIn(0f, 1f)
        burn += dt * rate

        if (burn < FINISHED) return false

        burn = 0f
        poof = 1f
        round++
        return true
    }

    /**
     * Where the image for the current round should come from. Rounds alternate
     * between a frame of another visual and a picked photo, starting with a
     * visual; without photos, every round is a visual.
     */
    fun source(hasPhotos: Boolean): Source =
        if (hasPhotos && round % 2 == 1) Source.PHOTO else Source.VISUAL

    /** Starts again from an untouched first image. */
    fun reset() {
        burn = 0f
        poof = 0f
        round = 0
        needsFirst = true
    }

    companion object {
        /**
         * A little past the centre, so the last embers are gone before the
         * swap rather than being cut off by it.
         */
        const val FINISHED = 1.08f

        /** Silence alone finishes a round in 90 seconds. */
        const val CREEP_PER_SECOND = FINISHED / 90f

        /** A steadily loud room adds this much again; beats on top. */
        const val LEVEL_PER_SECOND = 0.05f
        const val ONSET_PER_SECOND = 0.07f

        const val POOF_DECAY_PER_SECOND = 0.9f
    }
}
