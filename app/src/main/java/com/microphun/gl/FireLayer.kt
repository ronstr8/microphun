package com.microphun.gl

import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import com.microphun.audio.AudioFrame
import java.nio.ByteBuffer
import kotlin.math.min
import kotlin.random.Random

/**
 * The `fire` visual's moving parts: the image being burned, and the state its
 * backdrop shader burns it with.
 *
 * Each round burns one image. Rounds alternate between a frame of another
 * visual, rendered offscreen with the live audio the moment the round starts,
 * and a photo the user picked, if they have picked any. [BurnCycle] decides
 * when a round ends; [PhotoSource] has the next photo decoded before it is
 * needed, and when it has not, the round burns a visual instead of waiting.
 *
 * GL thread only. Everything here dies with the context, so it is rebuilt
 * rather than released when the surface is recreated.
 */
class FireLayer(private val photos: PhotoSource) {

    /** Renders one frame of some other visual into the framebuffer given. */
    fun interface Snapshot {
        fun into(framebuffer: Int, width: Int, height: Int)
    }

    private val cycle = BurnCycle()

    private var heat = 0f

    // A frame of another visual, rendered here through its own framebuffer.
    private var snapshotTexture = 0
    private var framebuffer = 0
    private var snapshotWidth = 0
    private var snapshotHeight = 0

    // A decoded photo, uploaded.
    private var photoTexture = 0

    // Random bytes for the shader's noise; see `hash` in fire.frag.
    private var noiseTexture = 0

    private var showingPhoto = false
    private var imageAspect = 1f

    /** Starts again from a fresh image the next time the visual is drawn. */
    fun clear() {
        cycle.reset()
        heat = 0f
    }

    /**
     * Advances the burn by [dt] seconds and, when a round ends, fetches the
     * next image — which may mean rendering another visual through [snapshot].
     * Call before the backdrop is drawn.
     */
    fun update(frame: AudioFrame, dt: Float, width: Int, height: Int, snapshot: Snapshot) {
        photos.prefetch(width, height)

        val target = (frame.level * 1.2f + frame.onset).coerceIn(0f, 1f)
        heat += (target - heat) * min(1f, dt * HEAT_EASE)

        if (!cycle.advance(dt, frame.level, frame.onset)) return

        if (cycle.source(photos.hasPhotos) == BurnCycle.Source.PHOTO && showPhoto(width, height)) return
        showSnapshot(width, height, snapshot)
    }

    /** Hands the backdrop the image and the burn. Call with its program in use. */
    fun bindBackdrop(shader: ShaderProgram) {
        if (noiseTexture == 0) noiseTexture = newNoise()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTexture)
        shader.setInt("uNoise", 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (showingPhoto) photoTexture else snapshotTexture)
        shader.setInt("uImage", 0)
        shader.set("uImageAspect", imageAspect)
        shader.set("uImageFlip", if (showingPhoto) 1f else 0f)
        shader.set("uBurn", cycle.burn)
        shader.set("uHeat", heat)
        shader.set("uPoof", cycle.poof)
    }

    private fun showPhoto(width: Int, height: Int): Boolean {
        val bitmap = photos.take(width, height) ?: return false
        if (photoTexture == 0) photoTexture = newTexture()

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, photoTexture)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        imageAspect = bitmap.width.toFloat() / bitmap.height
        bitmap.recycle()
        showingPhoto = true
        return true
    }

    private fun showSnapshot(width: Int, height: Int, snapshot: Snapshot) {
        if (width <= 0 || height <= 0) return
        if (width != snapshotWidth || height != snapshotHeight) allocateSnapshot(width, height)

        snapshot.into(framebuffer, width, height)

        imageAspect = width.toFloat() / height
        showingPhoto = false
    }

    private fun allocateSnapshot(width: Int, height: Int) {
        if (snapshotTexture == 0) snapshotTexture = newTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, snapshotTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        if (framebuffer == 0) {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            framebuffer = ids[0]
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, snapshotTexture, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "snapshot framebuffer incomplete: 0x${status.toString(16)}")
        }

        snapshotWidth = width
        snapshotHeight = height
    }

    private fun newNoise(): Int {
        val bytes = ByteArray(NOISE_SIZE * NOISE_SIZE).also { Random(NOISE_SEED).nextBytes(it) }
        val texture = newTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, NOISE_SIZE, NOISE_SIZE, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(bytes),
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return texture
    }

    private fun newTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return ids[0]
    }

    private companion object {
        const val TAG = "microphun.fire"

        /** Per second: flames flare within a fraction of a second of a beat. */
        const val HEAT_EASE = 6f

        /** Must match the `& 255` in fire.frag's `hash`. */
        const val NOISE_SIZE = 256
        const val NOISE_SEED = 0x6d696370L
    }
}
