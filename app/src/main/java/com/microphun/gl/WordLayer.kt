package com.microphun.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import com.microphun.audio.AudioFrame
import com.microphun.speech.WordStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * The word cloud drawn over the `words` visual.
 *
 * A recognised word lands somewhere at random, holds still, and fades out if
 * the room stops saying it. Hearing it again does not move it — the word gains
 * a hit, which enlarges it permanently and kicks a pulse — so over a
 * conversation the screen becomes a map of what is being repeated.
 *
 * This owns the layout and nothing else: [GlyphCache] owns the textures, and is
 * shared, so a word costs one rasterisation however many times it appears or
 * however many visuals show it. Note the cache's rule — a glyph is asked for on
 * every frame it is drawn, since the lookup is what marks it as still in use.
 *
 * Everything here runs on the GL thread, once per frame, with [WordStream] as
 * the only state shared with the recogniser.
 */
class WordLayer(
    private val assets: AssetManager,
    private val stream: WordStream,
    private val glyphs: GlyphCache,
) {

    private class Word(val text: String) {
        var centerX = 0f
        var centerY = 0f
        var hits = 1

        /** Eased toward the size [hits] asks for, so growth is visible. */
        var scale = 0f

        /** 1 on a hit, decaying: drives the flash and the size overshoot. */
        var pulse = 1f

        /** Seconds since the word appeared, and since it was last heard. */
        var life = 0f
        var idle = 0f

        val expired: Boolean get() = idle > LIFETIME_SECONDS + FADE_SECONDS

        val alpha: Float
            get() {
                val fadeIn = (life / FADE_IN_SECONDS).coerceAtMost(1f)
                val fadeOut = 1f - ((idle - LIFETIME_SECONDS) / FADE_SECONDS).coerceIn(0f, 1f)
                return fadeIn * fadeOut
            }
    }

    private val words = ArrayList<Word>(MAX_WORDS)
    private val random = Random.Default

    private var program: ShaderProgram? = null
    private lateinit var quad: FloatBuffer

    /** Links the text program. Call on the GL thread with a current context. */
    fun create() {
        val data = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )
        quad = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }

        program = ShaderProgram.link(readAsset("shaders/text.vert"), readAsset("shaders/text.frag"))
    }

    /**
     * Drops every live word and discards pending ones. The textures stay in the
     * cache, which is shared and cleared by whoever owns it.
     */
    fun clear() {
        words.clear()
        stream.clear()
    }

    /**
     * Takes in whatever the recogniser has heard, advances the animation by
     * [dt] seconds and draws the cloud over the current framebuffer.
     */
    fun draw(frame: AudioFrame, dt: Float, width: Float, height: Float) {
        val shader = program ?: return

        while (true) {
            val word = stream.poll() ?: break
            ingest(word, width, height)
        }
        if (words.isEmpty()) return

        // Premultiplied: the glyph bitmaps come out of Canvas that way, and the
        // text shader keeps its output premultiplied to match.
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        shader.use()
        shader.set("uResolution", width, height)
        shader.setInt("uGlyph", 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        val position = shader.attribute("aPosition")
        GLES30.glEnableVertexAttribArray(position)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, quad)

        val iterator = words.iterator()
        while (iterator.hasNext()) {
            val word = iterator.next()
            // Also the cache's "still in use" mark for this frame.
            val glyph = glyphs.get(word.text)
            if (glyph == null) {
                iterator.remove()
                continue
            }

            advance(word, glyph, dt, height)
            if (word.expired) {
                iterator.remove()
                continue
            }

            drawWord(shader, word, glyph, frame, width, height)
        }

        GLES30.glDisableVertexAttribArray(position)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawWord(
        shader: ShaderProgram,
        word: Word,
        glyph: GlyphCache.Glyph,
        frame: AudioFrame,
        width: Float,
        height: Float,
    ) {
        val bump = word.scale * (1f + PULSE_OVERSHOOT * word.pulse + 0.10f * frame.level)
        var halfWidth = glyph.width * bump * 0.5f
        var halfHeight = glyph.height * bump * 0.5f

        // A long word at full size can be wider than the screen; shrink it
        // rather than letting it run off both edges.
        val overflow = (halfWidth * 2f) / (width * MAX_WIDTH_FRACTION)
        if (overflow > 1f) {
            halfWidth /= overflow
            halfHeight /= overflow
        }

        val x = word.centerX.coerceIn(halfWidth, (width - halfWidth).coerceAtLeast(halfWidth))
        val y = word.centerY.coerceIn(halfHeight, (height - halfHeight).coerceAtLeast(halfHeight))

        shader.set("uCenter", x, y)
        shader.set("uHalfSize", halfWidth, halfHeight)
        shader.set("uHue", glyph.hue)
        shader.set("uAlpha", word.alpha)
        shader.set("uGlow", word.pulse * 0.8f + frame.onset * 0.35f)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, glyph.texture)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** Adds [text] to the cloud, or scores a hit against the word already up. */
    private fun ingest(text: String, width: Float, height: Float) {
        val existing = words.firstOrNull { it.text == text }
        if (existing != null) {
            existing.hits = min(existing.hits + 1, MAX_HITS)
            existing.pulse = 1f
            existing.idle = 0f
            return
        }

        // Rasterise now rather than mid-draw, and skip anything that has no
        // drawable extent instead of adding a word that can never be shown.
        if (glyphs.get(text) == null) return

        if (words.size >= MAX_WORDS) retireStalest()

        val word = Word(text)
        place(word, width, height)
        words.add(word)
    }

    private fun advance(word: Word, glyph: GlyphCache.Glyph, dt: Float, height: Float) {
        word.life += dt
        word.idle += dt
        word.pulse = (word.pulse - dt * PULSE_DECAY_PER_SECOND).coerceAtLeast(0f)

        // Height on screen the word has earned, as a fraction of the viewport.
        val fraction = (BASE_HEIGHT_FRACTION + HIT_HEIGHT_FRACTION * (word.hits - 1))
            .coerceAtMost(MAX_HEIGHT_FRACTION)
        val target = height * fraction / glyph.height

        // Frame-rate independent ease, clamped so a long frame cannot overshoot
        // past the target.
        word.scale += (target - word.scale) * min(1f, dt * SCALE_EASE)
    }

    /**
     * Picks a home for a new word: several random candidates, keeping whichever
     * is furthest from the words already up. Cheap, and enough to stop the
     * cloud piling into one corner without the cost of real layout.
     */
    private fun place(word: Word, width: Float, height: Float) {
        val marginX = width * EDGE_MARGIN_FRACTION
        val marginY = height * EDGE_MARGIN_FRACTION

        var bestX = width * 0.5f
        var bestY = height * 0.5f
        var bestDistance = -1f

        repeat(PLACEMENT_TRIES) {
            val x = marginX + random.nextFloat() * (width - marginX * 2f)
            val y = marginY + random.nextFloat() * (height - marginY * 2f)
            val nearest = words.minOfOrNull { hypot(it.centerX - x, it.centerY - y) } ?: Float.MAX_VALUE
            if (nearest > bestDistance) {
                bestDistance = nearest
                bestX = x
                bestY = y
            }
        }

        word.centerX = bestX
        word.centerY = bestY
    }

    private fun retireStalest() {
        val stalest = words.maxByOrNull { it.idle } ?: return
        words.remove(stalest)
    }

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val MAX_WORDS = 18

        /** Past this, more repetition stops making a word any bigger. */
        const val MAX_HITS = 12

        const val BASE_HEIGHT_FRACTION = 0.055f
        const val HIT_HEIGHT_FRACTION = 0.022f
        const val MAX_HEIGHT_FRACTION = 0.24f
        const val MAX_WIDTH_FRACTION = 0.9f

        const val SCALE_EASE = 7f
        const val PULSE_DECAY_PER_SECOND = 2.2f
        const val PULSE_OVERSHOOT = 0.45f

        const val FADE_IN_SECONDS = 0.28f

        /** Silence a word survives before it starts to go. */
        const val LIFETIME_SECONDS = 9f
        const val FADE_SECONDS = 1.6f

        const val EDGE_MARGIN_FRACTION = 0.12f
        const val PLACEMENT_TRIES = 10
    }
}
