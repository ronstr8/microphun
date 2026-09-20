package com.microphun.gl

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.microphun.audio.AudioFrame
import com.microphun.speech.WordStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * The word cloud drawn over the `words` visual.
 *
 * Each recognised word is rasterised once by [Canvas] into its own texture and
 * from then on lives as a textured quad: it lands somewhere at random, holds
 * still, and fades out if the room stops saying it. Hearing it again does not
 * move it — the word gains a hit, which enlarges it permanently and kicks a
 * pulse — so over a conversation the screen becomes a map of what is being
 * repeated.
 *
 * Everything here runs on the GL thread, once per frame, with [WordStream] as
 * the only state shared with the recogniser. Rasterising allocates a bitmap,
 * but only on the frame a genuinely new word arrives.
 */
class WordLayer(
    private val assets: AssetManager,
    private val stream: WordStream,
) {

    private class Word(
        val text: String,
        val texture: Int,
        /** Rasterised size in texels; the quad keeps this aspect ratio. */
        val rasterWidth: Float,
        val rasterHeight: Float,
        val hue: Float,
    ) {
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
    }

    private val words = ArrayList<Word>(MAX_WORDS)
    private val random = Random.Default

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = RASTER_TEXT_PX
        color = Color.WHITE
        // A black halo, baked into the glyph texture, is what lets a word stay
        // readable over a moving visual without dimming the visual itself.
        setShadowLayer(GLOW_RADIUS, 0f, 0f, Color.BLACK)
    }
    private val bounds = Rect()

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

    /** Drops every live word and its texture, and discards pending words. */
    fun clear() {
        if (words.isNotEmpty()) {
            val textures = IntArray(words.size) { words[it].texture }
            GLES30.glDeleteTextures(textures.size, textures, 0)
            words.clear()
        }
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

        advance(dt, height)
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

        for (word in words) {
            val bump = word.scale * (1f + PULSE_OVERSHOOT * word.pulse + 0.10f * frame.level)
            var halfWidth = word.rasterWidth * bump * 0.5f
            var halfHeight = word.rasterHeight * bump * 0.5f

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
            shader.set("uHue", word.hue)
            shader.set("uAlpha", alphaOf(word))
            shader.set("uGlow", word.pulse * 0.8f + frame.onset * 0.35f)

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, word.texture)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES30.glDisableVertexAttribArray(position)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
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

        if (words.size >= MAX_WORDS) retireStalest()

        val word = rasterise(text) ?: return
        place(word, width, height)
        words.add(word)
    }

    private fun advance(dt: Float, height: Float) {
        val iterator = words.iterator()
        while (iterator.hasNext()) {
            val word = iterator.next()
            word.life += dt
            word.idle += dt
            word.pulse = (word.pulse - dt * PULSE_DECAY_PER_SECOND).coerceAtLeast(0f)

            val target = targetScale(word, height)
            // Frame-rate independent ease, clamped so a long frame cannot
            // overshoot past the target.
            word.scale += (target - word.scale) * min(1f, dt * SCALE_EASE)

            if (word.idle > LIFETIME_SECONDS + FADE_SECONDS) {
                GLES30.glDeleteTextures(1, intArrayOf(word.texture), 0)
                iterator.remove()
            }
        }
    }

    /** Height on screen the word has earned, as a fraction of the viewport. */
    private fun targetScale(word: Word, height: Float): Float {
        val fraction = (BASE_HEIGHT_FRACTION + HIT_HEIGHT_FRACTION * (word.hits - 1))
            .coerceAtMost(MAX_HEIGHT_FRACTION)
        return height * fraction / word.rasterHeight
    }

    private fun alphaOf(word: Word): Float {
        val fadeIn = (word.life / FADE_IN_SECONDS).coerceAtMost(1f)
        val fadeOut = 1f - ((word.idle - LIFETIME_SECONDS) / FADE_SECONDS).coerceIn(0f, 1f)
        return fadeIn * fadeOut
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
        GLES30.glDeleteTextures(1, intArrayOf(stalest.texture), 0)
        words.remove(stalest)
    }

    /**
     * Draws [text] into a texture once, at a size generous enough that scaling
     * it up for a repeated word stays crisp.
     */
    private fun rasterise(text: String): Word? {
        paint.getTextBounds(text, 0, text.length, bounds)
        val pad = (GLOW_RADIUS * 1.6f).toInt()
        val width = bounds.width() + pad * 2
        val height = bounds.height() + pad * 2
        if (width <= 0 || height <= 0) return null

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawText(
            text,
            (pad - bounds.left).toFloat(),
            (pad - bounds.top).toFloat(),
            paint,
        )

        val texture = upload(bitmap)
        bitmap.recycle()

        // Hue from the text itself, so a word keeps its colour across sessions
        // and two different words rarely collide.
        val hue = (abs(text.hashCode()) % 997) / 997f
        return Word(text, texture, width.toFloat(), height.toFloat(), hue)
    }

    private fun upload(bitmap: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_LINEAR,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE,
        )
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        /** Rasterised once at this size; the GPU scales from here. */
        const val RASTER_TEXT_PX = 132f
        const val GLOW_RADIUS = 18f

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
