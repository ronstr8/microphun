package com.microphun.gl

import android.opengl.GLES30
import android.opengl.GLUtils
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

    private val cycle = BurnCycle()

    private var heat = 0f

    // A frame of another visual.
    private val snapshot = OffscreenImage()

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
     * next image — which may mean rendering another visual through [render].
     * Call before the backdrop is drawn.
     */
    fun update(frame: AudioFrame, dt: Float, width: Int, height: Int, render: Snapshot) {
        photos.prefetch(width, height)

        val target = (frame.level * 1.2f + frame.onset).coerceIn(0f, 1f)
        heat += (target - heat) * min(1f, dt * HEAT_EASE)

        if (!cycle.advance(dt, frame.level, frame.onset)) return

        if (cycle.source(photos.hasPhotos) == BurnCycle.Source.PHOTO && showPhoto(width, height)) return
        showSnapshot(width, height, render)
    }

    /** Hands the backdrop the image and the burn. Call with its program in use. */
    fun bindBackdrop(shader: ShaderProgram) {
        if (noiseTexture == 0) noiseTexture = newNoise()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTexture)
        shader.setInt("uNoise", 1)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (showingPhoto) photoTexture else snapshot.texture)
        shader.setInt("uImage", 0)
        shader.set("uImageAspect", imageAspect)
        shader.set("uImageFlip", if (showingPhoto) 1f else 0f)
        shader.set("uBurn", cycle.burn)
        shader.set("uHeat", heat)
        shader.set("uPoof", cycle.poof)
    }

    private fun showPhoto(width: Int, height: Int): Boolean {
        val bitmap = photos.take(width, height) ?: return false
        if (photoTexture == 0) photoTexture = OffscreenImage.newTexture()

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, photoTexture)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        imageAspect = bitmap.width.toFloat() / bitmap.height
        bitmap.recycle()
        showingPhoto = true
        return true
    }

    private fun showSnapshot(width: Int, height: Int, render: Snapshot) {
        snapshot.capture(width, height, render)
        imageAspect = snapshot.aspect
        showingPhoto = false
    }

    private fun newNoise(): Int {
        val bytes = ByteArray(NOISE_SIZE * NOISE_SIZE).also { Random(NOISE_SEED).nextBytes(it) }
        val texture = OffscreenImage.newTexture()
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

    private companion object {
        /** Per second: flames flare within a fraction of a second of a beat. */
        const val HEAT_EASE = 6f

        /** Must match the `& 255` in fire.frag's `hash`. */
        const val NOISE_SIZE = 256
        const val NOISE_SEED = 0x6d696370L
    }
}
