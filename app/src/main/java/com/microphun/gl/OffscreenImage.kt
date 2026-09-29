package com.microphun.gl

import android.opengl.GLES30
import android.util.Log

/** Renders one frame of some other visual into the framebuffer given. */
fun interface Snapshot {
    fun into(framebuffer: Int, width: Int, height: Int)
}

/**
 * A texture with its own framebuffer, for visuals that work on a picture of
 * the app: a frame of another visual, rendered here through [Snapshot], which
 * the fire then burns and the crack visual shatters.
 *
 * GL thread only. Dies with the context, so it is rebuilt rather than released
 * when the surface is recreated.
 */
class OffscreenImage(
    /** Pixel format; a mask needs one channel, a picture needs four. */
    private val internalFormat: Int = GLES30.GL_RGBA8,
) {

    var texture = 0
        private set
    var framebuffer = 0
        private set

    var width = 0
        private set
    var height = 0
        private set

    val aspect: Float get() = if (height > 0) width.toFloat() / height else 1f

    /** Makes sure the target is [width] × [height], reallocating if not. */
    fun ensure(width: Int, height: Int) {
        if (width == this.width && height == this.height && texture != 0) return
        if (texture == 0) texture = newTexture()

        val (format, type) = when (internalFormat) {
            GLES30.GL_R8 -> GLES30.GL_RED to GLES30.GL_UNSIGNED_BYTE
            else -> GLES30.GL_RGBA to GLES30.GL_UNSIGNED_BYTE
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, null)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        if (framebuffer == 0) {
            val ids = IntArray(1)
            GLES30.glGenFramebuffers(1, ids, 0)
            framebuffer = ids[0]
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D, texture, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "offscreen framebuffer incomplete: 0x${status.toString(16)}")
        }

        this.width = width
        this.height = height
    }

    /** Renders a frame of another visual into this, at [width] × [height]. */
    fun capture(width: Int, height: Int, snapshot: Snapshot) {
        if (width <= 0 || height <= 0) return
        ensure(width, height)
        snapshot.into(framebuffer, width, height)
    }

    companion object {
        private const val TAG = "microphun.gl"

        /** A linear, clamped texture with nothing in it yet. */
        fun newTexture(): Int {
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
    }
}
