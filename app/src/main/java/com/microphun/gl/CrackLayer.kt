package com.microphun.gl

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Log
import com.microphun.BuildConfig
import com.microphun.audio.AudioFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * The `crack` visual's moving parts: the image under the glass, and the mask
 * of every crack struck into it so far.
 *
 * Each noise loud enough to register is a blow at a random point, sized by
 * how loud it was ([CrackCycle]); [CrackPattern] turns it into lines, which are
 * drawn once into the mask and stay there. The backdrop shader does the rest —
 * refraction, the dark rims, the lit cores — from the mask alone, so a screen
 * full of cracks costs the same to draw as an empty one. When the glass gives
 * way, the mask is wiped and a fresh frame of another visual goes under it.
 *
 * GL thread only. Everything here dies with the context, so it is rebuilt
 * rather than released when the surface is recreated.
 */
class CrackLayer(private val assets: AssetManager) {

    private val cycle = CrackCycle()
    private val image = OffscreenImage()
    private val mask = OffscreenImage(GLES30.GL_R8)
    private val random = Random.Default

    private var lines: ShaderProgram? = null
    private var vertices: FloatBuffer = allocate(1024)

    private var impactX = 0f
    private var impactY = 0f
    private var reveal = 1f

    /** Links the line program. Call on the GL thread with a current context. */
    fun create() {
        lines = ShaderProgram.link(readAsset("shaders/crackline.vert"), readAsset("shaders/crackline.frag"))
    }

    /** Starts again from a fresh, whole image the next time the visual is drawn. */
    fun clear() {
        cycle.reset()
    }

    /**
     * Advances by [dt] seconds: a fresh image when one is due, a blow when a
     * noise lands. Call before the backdrop is drawn.
     */
    fun update(frame: AudioFrame, dt: Float, width: Int, height: Int, render: Snapshot) {
        val step = cycle.advance(dt, frame.level, frame.onset)
        reveal = (reveal + dt / REVEAL_SECONDS).coerceAtMost(1f)

        if (step.newImage) {
            image.capture(width, height, render)
            wipe(width, height)
            reveal = 0f
        }
        if (step.impact >= 0f) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "blow: peak level %.3f -> size %.2f, damage %.2f".format(step.peak, step.impact, cycle.damage))
            }
            strike(step.impact, width, height)
        }
    }

    /** Hands the backdrop the image and the cracks. Call with its program in use. */
    fun bindBackdrop(shader: ShaderProgram) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mask.texture)
        shader.setInt("uMask", 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, image.texture)
        shader.setInt("uImage", 0)
        shader.set("uImageAspect", image.aspect)

        shader.set("uImpactPos", impactX, impactY)
        shader.set("uFlash", cycle.flash)
        shader.set("uShatter", cycle.shatter)
        shader.set("uReveal", reveal)
    }

    private fun strike(size: Float, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        // Anywhere but hard against an edge, where half the crack is wasted.
        impactX = width * (EDGE_MARGIN + random.nextFloat() * (1f - 2f * EDGE_MARGIN))
        impactY = height * (EDGE_MARGIN + random.nextFloat() * (1f - 2f * EDGE_MARGIN))

        val segments = CrackPattern.generate(impactX, impactY, size, min(width, height).toFloat(), random)
        draw(segments, width, height)
    }

    /** Draws [segments] into the mask, where they stay until the next wipe. */
    private fun draw(segments: List<CrackPattern.Segment>, width: Int, height: Int) {
        val program = lines ?: return
        if (segments.isEmpty()) return
        mask.ensure(width, height)

        val floats = segments.size * VERTICES_PER_SEGMENT * FLOATS_PER_VERTEX
        if (vertices.capacity() < floats) vertices = allocate(floats)
        vertices.clear()
        for (s in segments) quad(s)
        vertices.flip()

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mask.framebuffer)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_MAX)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)

        program.use()
        program.set("uResolution", width.toFloat(), height.toFloat())
        val position = program.attribute("aPosition")
        val edge = program.attribute("aEdge")
        val stride = FLOATS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(position)
        GLES30.glEnableVertexAttribArray(edge)
        vertices.position(0)
        GLES30.glVertexAttribPointer(position, 2, GLES30.GL_FLOAT, false, stride, vertices)
        vertices.position(2)
        GLES30.glVertexAttribPointer(edge, 1, GLES30.GL_FLOAT, false, stride, vertices)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, segments.size * VERTICES_PER_SEGMENT)
        GLES30.glDisableVertexAttribArray(position)
        GLES30.glDisableVertexAttribArray(edge)

        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
    }

    /**
     * One segment as two triangles, widened for the soft falloff and stretched
     * past both ends so consecutive pieces of a crack overlap at the joins.
     */
    private fun quad(s: CrackPattern.Segment) {
        val length = hypot(s.x1 - s.x0, s.y1 - s.y0)
        if (length <= 0f) return
        val half = s.width * HALF_WIDTH_PER_WIDTH
        val dx = (s.x1 - s.x0) / length
        val dy = (s.y1 - s.y0) / length
        val nx = -dy * half
        val ny = dx * half
        val ax = s.x0 - dx * half
        val ay = s.y0 - dy * half
        val bx = s.x1 + dx * half
        val by = s.y1 + dy * half

        vertex(ax + nx, ay + ny, 1f)
        vertex(ax - nx, ay - ny, -1f)
        vertex(bx + nx, by + ny, 1f)
        vertex(bx + nx, by + ny, 1f)
        vertex(ax - nx, ay - ny, -1f)
        vertex(bx - nx, by - ny, -1f)
    }

    private fun vertex(x: Float, y: Float, edge: Float) {
        vertices.put(x).put(y).put(edge)
    }

    private fun wipe(width: Int, height: Int) {
        mask.ensure(width, height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mask.framebuffer)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    private companion object {
        const val TAG = "microphun.crack"

        const val VERTICES_PER_SEGMENT = 6
        const val FLOATS_PER_VERTEX = 3

        /** The quad is wider than the crack so its coverage can fade out. */
        const val HALF_WIDTH_PER_WIDTH = 1.5f

        const val EDGE_MARGIN = 0.08f
        const val REVEAL_SECONDS = 0.6f

        fun allocate(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
}
