package com.microphun.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import com.microphun.BuildConfig
import com.microphun.audio.AudioEngine
import com.microphun.audio.AudioFrame
import com.microphun.speech.PhraseSource
import com.microphun.speech.WordStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.random.Random

/**
 * Draws one fullscreen triangle per frame and feeds the audio analysis in as
 * uniforms. All the visual character lives in the fragment shaders under
 * `assets/shaders/`; this class only plumbs.
 *
 * The [SPEECH_SHADERS] and [FIRE_SHADER] are the exceptions. The fire's
 * backdrop burns an image [FireLayer] supplies, which may be a frame of another
 * visual rendered offscreen here. The [SPEECH_SHADERS] have backdrops that are
 * fragment shaders like any other, but words are drawn over them afterwards —
 * recognised words by [WordLayer], invented phrases by [SpiralLayer] — which
 * needs geometry and textures a single triangle cannot give.
 */
class VisualizerRenderer(
    private val assets: AssetManager,
    private val audio: AudioEngine,
    private val words: WordStream,
    private val photos: PhotoSource,
) : GLSurfaceView.Renderer {

    private var program: ShaderProgram? = null
    private lateinit var vertices: FloatBuffer

    private var wordLayer: WordLayer? = null
    private var spiralLayer: SpiralLayer? = null
    private var fireLayer: FireLayer? = null

    // Other visuals, compiled on demand for the fire to burn a frame of.
    private val snapshotPrograms = HashMap<String, ShaderProgram>()
    private val snapshotFrame = AudioFrame(FloatArray(AudioEngine.BAND_COUNT))

    // Shared so a word costs one rasterisation however many layouts show it.
    private var glyphs: GlyphCache? = null

    // What the room said, kept on this thread because this is where it is read.
    private val phrases = PhraseSource()

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
        spiralLayer = SpiralLayer(assets, words, cache, phrases).apply { create() }
        fireLayer = FireLayer(photos)
        // Programs from the old context are already gone with it.
        snapshotPrograms.clear()
        // Not a GL resource, but it goes with the word cloud: a surface is only
        // recreated after the app has been away, and the session it belonged
        // to is over.
        phrases.clear()
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
            // Leaving a visual discards what it had on screen; coming back
            // starts empty rather than with whatever the room said minutes ago.
            // The glyph textures and the corpus survive a move between the two
            // speech visuals — talk in one, then watch the other spiral — and
            // go once neither is up: nothing outlives the visuals that listened.
            val name = SHADERS[wanted]
            if (name != WORDS_SHADER) wordLayer?.clear()
            if (name != SPIRAL_SHADER) spiralLayer?.clear()
            if (name != FIRE_SHADER) fireLayer?.clear()
            if (name !in SPEECH_SHADERS) {
                glyphs?.clear()
                phrases.clear()
            }
        }

        val shader = program ?: return
        val frame = audio.read()

        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameMillis) / 1000f).coerceIn(0f, MAX_DELTA_SECONDS)
        lastFrameMillis = now

        val name = SHADERS[currentShader]
        if (name in SPEECH_SHADERS) {
            collectPhrases(logging = name == WORDS_SHADER)
            if (name == SPIRAL_SHADER) spiralLayer?.update(frame, dt, width, height)
        }
        if (name == FIRE_SHADER) {
            fireLayer?.update(frame, dt, width.toInt(), height.toInt()) { framebuffer, w, h ->
                renderSnapshot(framebuffer, w, h, frame)
            }
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        shader.use()
        setFrame(shader, frame, (now - startMillis) / 1000f, width, height)
        when (name) {
            SPIRAL_SHADER -> spiralLayer?.bindBackdrop(shader)
            FIRE_SHADER -> fireLayer?.bindBackdrop(shader)
        }
        drawFullscreen(shader)

        when (name) {
            WORDS_SHADER -> wordLayer?.draw(frame, dt, width, height)
            SPIRAL_SHADER -> spiralLayer?.draw(frame, width, height)
        }
    }

    /** Moves new utterances into the corpus. */
    private fun collectPhrases(logging: Boolean) {
        val arrived = phrases.collect(words)
        if (arrived > 0 && logging && BuildConfig.DEBUG) {
            // The word cloud shows no phrases, so in that visual the log is the
            // way to see the chain working. The spiral logs what it spills.
            val phrase = phrases.generate()?.joinToString(" ") ?: "(corpus too thin)"
            Log.d(TAG, "corpus ${phrases.words} words, invented: $phrase")
        }
    }

    /** The uniforms every visual gets: see the `Frame` contract in CLAUDE.md. */
    private fun setFrame(shader: ShaderProgram, frame: AudioFrame, time: Float, w: Float, h: Float) {
        shader.set("uTime", time)
        shader.set("uResolution", w, h)
        shader.set("uLevel", frame.level)
        shader.set("uBass", frame.bass)
        shader.set("uMid", frame.mid)
        shader.set("uTreble", frame.treble)
        shader.set("uOnset", frame.onset)
        shader.setArray("uBands", frame.bands)
    }

    private fun drawFullscreen(shader: ShaderProgram) {
        val position = shader.attribute("aPosition")
        GLES30.glEnableVertexAttribArray(position)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, 0, vertices)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDisableVertexAttribArray(position)
    }

    /**
     * Draws one frame of a random other visual into [framebuffer], for the fire
     * to burn: a screenshot of the app, taken of a visual that is not on screen.
     *
     * A random moment of it, so the same visual never burns the same way twice,
     * and never quieter than [SNAPSHOT_FLOOR]: most visuals go nearly black in
     * a silent room, and a black image has nothing in it to burn.
     */
    private fun renderSnapshot(framebuffer: Int, w: Int, h: Int, live: AudioFrame) {
        val name = SNAPSHOT_SOURCES.random()
        val source = snapshotPrograms.getOrPut(name) { link(name) }

        for (i in snapshotFrame.bands.indices) {
            // A falling spectrum, roughly what music looks like, as the floor.
            val floor = SNAPSHOT_FLOOR * (1f - 0.5f * i / snapshotFrame.bands.size) * (0.7f + 0.3f * Random.nextFloat())
            snapshotFrame.bands[i] = maxOf(live.bands[i], floor)
        }
        snapshotFrame.level = maxOf(live.level, SNAPSHOT_FLOOR)
        snapshotFrame.bass = maxOf(live.bass, SNAPSHOT_FLOOR)
        snapshotFrame.mid = maxOf(live.mid, SNAPSHOT_FLOOR * 0.8f)
        snapshotFrame.treble = maxOf(live.treble, SNAPSHOT_FLOOR * 0.6f)
        snapshotFrame.onset = live.onset

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        source.use()
        setFrame(source, snapshotFrame, Random.nextFloat() * SNAPSHOT_TIME_RANGE, w.toFloat(), h.toFloat())
        drawFullscreen(source)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width.toInt(), height.toInt())
    }

    private fun loadShader(index: Int) {
        program?.release()
        program = link(SHADERS[index])
    }

    private fun link(name: String): ShaderProgram {
        val vertex = readAsset("shaders/fullscreen.vert")
        val fragment = readAsset("shaders/$name.frag")
            .replace(BAND_COUNT_TOKEN, AudioEngine.BAND_COUNT.toString())
        return ShaderProgram.link(vertex, fragment)
    }

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        /** Fragment shaders in `assets/shaders/`, without the extension. */
        val SHADERS = listOf("kaleidoscope", "plasma", "tunnel", "spectrum", "words", "spiral", "fire")

        const val WORDS_SHADER = "words"
        const val SPIRAL_SHADER = "spiral"
        const val FIRE_SHADER = "fire"

        /** The visuals that need speech recognition running. */
        val SPEECH_SHADERS = setOf(WORDS_SHADER, SPIRAL_SHADER)

        /**
         * What the fire can burn a frame of: the visuals that are complete as a
         * shader alone. `words` and `spiral` are mostly the text drawn over
         * them, and their backdrops alone are nearly empty.
         */
        private val SNAPSHOT_SOURCES = listOf("kaleidoscope", "plasma", "tunnel", "spectrum")

        /** How lively a snapshot's audio is at least; the live frame wins above it. */
        private const val SNAPSHOT_FLOOR = 0.55f

        /** Seconds of each visual's own time a snapshot picks its moment from. */
        private const val SNAPSHOT_TIME_RANGE = 600f

        private const val TAG = "microphun.phrases"

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
