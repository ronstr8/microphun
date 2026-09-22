package com.microphun.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import kotlin.math.abs

/**
 * Words drawn by [Canvas] into GL textures, kept for as long as they are used.
 *
 * Rasterising is the expensive part of putting a word on screen — a bitmap
 * allocation, a text layout and a texture upload — so it happens once per
 * distinct word rather than once per appearance. Anything that lays words out
 * shares one of these, which is also what stops two visuals holding two copies
 * of the same texture.
 *
 * Entries are evicted least-recently-used once [maxEntries] is exceeded. **A
 * caller must therefore ask for a glyph on every frame it draws it**, not hold
 * the [Glyph] indefinitely: a lookup is what marks it as still in use. As long
 * as [maxEntries] comfortably exceeds the number of words on screen, nothing
 * visible is ever evicted.
 *
 * GL thread only: it uploads and deletes textures.
 */
class GlyphCache(private val maxEntries: Int = MAX_ENTRIES) {

    /** One word, rasterised. Sizes are in texels; quads keep this aspect ratio. */
    class Glyph(
        val text: String,
        val texture: Int,
        val width: Float,
        val height: Float,
        /** Derived from the text, so a word keeps its colour wherever it appears. */
        val hue: Float,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = RASTER_TEXT_PX
        color = Color.WHITE
        // A black halo, baked into the glyph texture, is what lets a word stay
        // readable over a moving visual without dimming the visual itself.
        setShadowLayer(GLOW_RADIUS, 0f, 0f, Color.BLACK)
    }
    private val bounds = Rect()

    private val glyphs = object : LinkedHashMap<String, Glyph>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Glyph>): Boolean {
            if (size <= maxEntries) return false
            GLES30.glDeleteTextures(1, intArrayOf(eldest.value.texture), 0)
            return true
        }
    }

    val size: Int get() = glyphs.size

    /**
     * The glyph for [text], rasterising it if this is the first time, and
     * marking it as recently used either way. Null only if the text has no
     * drawable extent.
     */
    fun get(text: String): Glyph? {
        glyphs[text]?.let { return it }

        val glyph = rasterise(text) ?: return null
        glyphs[text] = glyph
        return glyph
    }

    /** Deletes every texture. Call when the visual using them goes away. */
    fun clear() {
        if (glyphs.isEmpty()) return
        val textures = glyphs.values.map { it.texture }.toIntArray()
        GLES30.glDeleteTextures(textures.size, textures, 0)
        glyphs.clear()
    }

    /**
     * Draws [text] at a size generous enough that scaling it up later stays
     * crisp; everything downstream scales this on the GPU.
     */
    private fun rasterise(text: String): Glyph? {
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

        val hue = (abs(text.hashCode()) % 997) / 997f
        return Glyph(text, texture, width.toFloat(), height.toFloat(), hue)
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

    private companion object {
        /** Rasterised once at this size; the GPU scales from here. */
        const val RASTER_TEXT_PX = 132f
        const val GLOW_RADIUS = 18f

        /**
         * Roughly a quarter of a megabyte per word at the raster size above, so
         * this is a ceiling of about 12 MB of texture — generous next to the 18
         * words the cloud shows, and enough that a repeated word is nearly
         * always still cached.
         */
        const val MAX_ENTRIES = 48
    }
}
