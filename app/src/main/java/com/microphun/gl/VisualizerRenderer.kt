package com.microphun.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.microphun.audio.AudioEngine
import com.microphun.speech.WordStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws one fullscreen triangle per frame and feeds the audio analysis in as
 * uniforms. All the visual character lives in the fragment shaders under
 * `assets/shaders/`; this class only plumbs.
 *
 * The [WORDS_SHADER] visual is the one exception: its backdrop is a fragment
 * shader like any other, but recognised words are drawn over it afterwards by
 * [WordLayer], which needs geometry and textures a single triangle cannot give.
 */
class VisualizerRenderer(
    private val assets: AssetManager,
    private val audio: AudioEngine,
    private val words: WordStream,
) : GLSurfaceView.Renderer {

    private var program: ShaderProgram? = null
    private lateinit var vertices: FloatBuffer

    private var wordLayer: WordLayer? = null

    // Shared so a word costs one rasterisation however many layouts show it.
    private var glyphs: GlyphCache? = null

    private var width = 1f
    private var height = 1f
    private var startMillis = 0L
    private var lastFrameMillis = 0L

    private val pendingShader = AtomicInteger(0)
    private var currentShader = -1

    /** Queues a shader swap; applied on the GL thread at the next frame. */
    fun selectShader(index: Int) {
        pendingShader.set(index.mod(SHADERS.size))
    }

    fun nextShader() {
        pendingShader.set((pendingShader.get() + 1).mod(SHADERS.size))
    }

    fun currentShaderName(): String = SHADERS[pendingShader.get()]

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // A single triangle that covers the clip volume: no index buffer, no
        // diagonal seam, one fewer vertex than a quad.
        val data = floatArrayOf(
            -1f, -1f,
            3f, -1f,
            -1f, 3f,
        )
        vertices = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(data)
                position(0)
            }

        startMillis = SystemClock.elapsedRealtime()
        lastFrameMillis = startMillis
        currentShader = -1
        GLES30.glClearColor(0f, 0f, 0f, 1f)

        // The context is new, so every program and texture from the old one is
        // already gone: the glyph cache and word layer are rebuilt rather than
        // released.
        val cache = GlyphCache()
        glyphs = cache
        wordLayer = WordLayer(assets, words, cache).apply { create() }
    }

    override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        width = w.toFloat()
        height = h.toFloat()
    }

    override fun onDrawFrame(unused: GL10?) {
        val wanted = pendingShader.get()
        if (wanted != currentShader) {
            loadShader(wanted)
            currentShader = wanted
            // Leaving the word cloud discards it; coming back starts empty
            // rather than with whatever the room said minutes ago. The glyph
            // textures go with it, since nothing else is using them.
            if (SHADERS[wanted] != WORDS_SHADER) {
                wordLayer?.clear()
                glyphs?.clear()
            }
        }

        val shader = program ?: return
        val frame = audio.read()

        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameMillis) / 1000f).coerceIn(0f, MAX_DELTA_SECONDS)
        lastFrameMillis = now

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        shader.use()

        shader.set("uTime", (now - startMillis) / 1000f)
        shader.set("uResolution", width, height)
        shader.set("uLevel", frame.level)
        shader.set("uBass", frame.bass)
        shader.set("uMid", frame.mid)
        shader.set("uTreble", frame.treble)
        shader.set("uOnset", frame.onset)
        shader.setArray("uBands", frame.bands)

        val position = shader.attribute("aPosition")
        GLES30.glEnableVertexAttribArray(position)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, vertices)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDisableVertexAttribArray(position)

        if (SHADERS[currentShader] == WORDS_SHADER) {
            wordLayer?.draw(frame, dt, width, height)
        }
    }

    private fun loadShader(index: Int) {
        program?.release()
        val vertex = readAsset("shaders/fullscreen.vert")
        val fragment = readAsset("shaders/${SHADERS[index]}.frag")
            .replace(BAND_COUNT_TOKEN, AudioEngine.BAND_COUNT.toString())
        program = ShaderProgram.link(vertex, fragment)
    }

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        /** Fragment shaders in `assets/shaders/`, without the extension. */
        val SHADERS = listOf("kaleidoscope", "plasma", "tunnel", "spectrum", "words")

        /** The one visual that also needs speech recognition running. */
        const val WORDS_SHADER = "words"

        /** Substituted so the shaders never hardcode the band count. */
        private const val BAND_COUNT_TOKEN = "\$BAND_COUNT"

        /**
         * Animation is clamped to this per frame, so a stall — a shader
         * compile, the app coming back from the background — does not jump the
         * word cloud forward by seconds.
         */
        private const val MAX_DELTA_SECONDS = 0.1f
    }
}
