package com.microphun.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log
import com.microphun.BuildConfig
import com.microphun.audio.AudioFrame
import com.microphun.speech.MarkovChain
import com.microphun.speech.PhraseSource
import com.microphun.speech.WordStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min

/**
 * The `spiral` visual's moving parts: invented phrases spilling out of the
 * centre along a spiral, and the state its backdrop shader draws from.
 *
 * Until the corpus holds enough to invent from, there is nothing to say, so the
 * backdrop throbs to the words as they arrive and swells as the corpus fills.
 * After that, every utterance the room finishes sends one invented phrase out
 * of the centre; [SpiralFlow] places it, and the beat turns and colours the
 * spiral.
 *
 * Same shape as [WordLayer]: GL thread only, [WordStream] is the only state
 * shared with the recogniser, and glyphs are asked for on every frame they are
 * drawn, never held — see [GlyphCache].
 */
class SpiralLayer(
    private val assets: AssetManager,
    private val stream: WordStream,
    private val glyphs: GlyphCache,
    private val phrases: PhraseSource,
) {

    private val flow = SpiralFlow()

    private var program: ShaderProgram? = null
    private lateinit var quad: FloatBuffer

    private var throb = 0f
    private var wordAge = QUIET_AGE
    private var flowing = 0f
    private var spin = 0f
    private var travel = 0f
    private var hue = 0f

    private var spawnRadius = 1f

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

    /** Drops every phrase, live or waiting, and starts the throb from rest. */
    fun clear() {
        flow.clear()
        stream.clear()
        throb = 0f
        wordAge = QUIET_AGE
        flowing = 0f
    }

    /**
     * Advances everything by [dt] seconds. [arrived] is how many utterances
     * reached the corpus this frame; each one sends a phrase out. Call before
     * the backdrop is drawn, since [bindBackdrop] reads what this computes.
     */
    fun update(frame: AudioFrame, dt: Float, arrived: Int, width: Float, height: Float) {
        // Each settled word is a beat of the conversation.
        wordAge = (wordAge + dt).coerceAtMost(QUIET_AGE)
        while (stream.poll() != null) {
            throb = 1f
            wordAge = 0f
        }
        throb = (throb - dt * THROB_DECAY_PER_SECOND).coerceAtLeast(0f)

        repeat(arrived) { invent() }

        spawnRadius = min(width, height) * SPAWN_FRACTION
        val exitRadius = hypot(width, height) * 0.5f * EXIT_BEYOND_CORNER
        flow.advance(dt, ln(exitRadius / spawnRadius))
        travel += dt * SpiralFlow.SPEED

        val ready = phrases.words >= MarkovChain.MIN_CORPUS
        flowing += ((if (ready) 1f else 0f) - flowing) * min(1f, dt * FLOW_EASE)

        spin += dt * (SpiralFlow.COUNTER_SPIN + SPIN_BASS * frame.bass + SPIN_ONSET * frame.onset)
        hue += dt * (HUE_DRIFT + HUE_ONSET * frame.onset)
    }

    /** Hands the backdrop the state it draws from. Call with its program in use. */
    fun bindBackdrop(shader: ShaderProgram) {
        shader.set("uThrob", throb)
        shader.set("uWordAge", wordAge)
        shader.set("uFill", (phrases.words / MarkovChain.MIN_CORPUS.toFloat()).coerceAtMost(1f))
        shader.set("uFlow", flowing)
        shader.set("uSpin", spin)
        shader.set("uPitch", SpiralFlow.PITCH)
        shader.set("uSpawnRadius", spawnRadius)
        shader.set("uTravel", travel)
        shader.set("uHue", hue)
    }

    /** Draws the live phrases over the current framebuffer. */
    fun draw(frame: AudioFrame, width: Float, height: Float) {
        val shader = program ?: return
        if (flow.phraseCount == 0) return

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        shader.use()
        shader.set("uResolution", width, height)
        shader.setInt("uGlyph", 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        val position = shader.attribute("aPosition")
        GLES30.glEnableVertexAttribArray(position)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, quad)

        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val glow = frame.onset * 0.35f + throb * 0.25f

        flow.forEachWord(spawnRadius, spin) { text, x, y, rotation, wordHeight, alpha ->
            // Also the cache's "still in use" mark for this frame.
            val glyph = glyphs.get(text) ?: return@forEachWord
            val scale = wordHeight / glyph.height

            shader.set("uCenter", centerX + x, centerY + y)
            shader.set("uHalfSize", glyph.width * scale * 0.5f, wordHeight * 0.5f)
            shader.set("uRotation", rotation)
            shader.set("uHue", glyph.hue + hue)
            shader.set("uAlpha", alpha)
            shader.set("uGlow", glow)

            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, glyph.texture)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES30.glDisableVertexAttribArray(position)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Invents one phrase and queues it to spill out. */
    private fun invent() {
        val phrase = phrases.generate() ?: return

        // Rasterise now, before the words are due on screen, and drop anything
        // with no drawable extent rather than leave a hole in the phrase.
        val words = ArrayList<String>(phrase.size)
        val aspects = ArrayList<Float>(phrase.size)
        for (word in phrase) {
            val glyph = glyphs.get(word) ?: continue
            words.add(word)
            aspects.add(glyph.width / glyph.height)
        }
        if (words.isEmpty()) return

        if (BuildConfig.DEBUG) Log.d(TAG, "spilling: ${words.joinToString(" ")}")
        flow.offer(words, aspects.toFloatArray())
    }

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val TAG = "microphun.phrases"

        /** Words come out this far from the centre, as a fraction of the short side. */
        const val SPAWN_FRACTION = 0.015f

        /**
         * A phrase is retired once its last word is this far past the corner,
         * which is where its inner edge has gone too.
         */
        const val EXIT_BEYOND_CORNER = 1.3f

        /**
         * Slow enough to see. At 3/s a word's throb was gone before the eye
         * found it, and on the device the filling state read as nothing
         * happening at all.
         */
        const val THROB_DECAY_PER_SECOND = 1.1f

        /** Seconds since a word, capped: long enough that its ripple is gone. */
        const val QUIET_AGE = 10f
        const val FLOW_EASE = 1.2f

        /** Beat on top of the counter-spin: bass leans on it, onsets kick it. */
        const val SPIN_BASS = 0.8f
        const val SPIN_ONSET = 2.5f

        const val HUE_DRIFT = 0.015f
        const val HUE_ONSET = 0.35f
    }
}
