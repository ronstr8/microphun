package com.microphun.gl

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Photos the user picked for the `fire` visual to burn, decoded one ahead.
 *
 * The photos come from the system photo picker, so microphun never holds a
 * storage permission: it gets read access to exactly the photos chosen, and
 * keeps that access across launches with a persistable grant. The choice is
 * remembered — it is a setting the user made, unlike anything heard through
 * the microphone — but the photos themselves are only ever read, never copied.
 *
 * Decoding a full-size photo takes long enough to drop frames, so it happens
 * on a worker thread. The GL thread only ever takes a finished bitmap, and
 * asks for the next one as it does. Every list of photos lives on that worker;
 * the only state other threads touch is the finished bitmap and [hasPhotos].
 */
class PhotoSource(context: Context) : AutoCloseable {

    private val resolver = context.applicationContext.contentResolver
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "microphun-photos").apply { isDaemon = true }
    }

    private val ready = AtomicReference<Bitmap?>()
    private val decoding = AtomicBoolean(false)

    // Worker thread only.
    private var chosen: List<Uri> = emptyList()
    private val order = ArrayDeque<Uri>()

    @Volatile
    var hasPhotos: Boolean = false
        private set

    init {
        worker.execute {
            chosen = prefs.getStringSet(KEY_PHOTOS, emptySet()).orEmpty().map(Uri::parse)
            hasPhotos = chosen.isNotEmpty()
        }
    }

    /**
     * Replaces the chosen photos. [picked] are fresh picker results; each gets a
     * persistable read grant so it still opens after a restart. Any bitmap
     * already decoded from the old choice is thrown away.
     */
    @MainThread
    fun choose(picked: List<Uri>) {
        if (picked.isEmpty()) return
        for (uri in picked) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Some pickers do not offer persistable grants. The photo still
                // opens this session; it will be dropped when it no longer does.
                Log.w(TAG, "no persistable grant for a picked photo", e)
            }
        }
        worker.execute {
            releaseGrants(chosen - picked.toSet())
            chosen = picked.toList()
            order.clear()
            save()
            ready.getAndSet(null)?.recycle()
        }
    }

    /**
     * The next photo, decoded to cover a [width] × [height] screen, or null if
     * none is ready yet. Starts decoding the one after. GL thread.
     */
    fun take(width: Int, height: Int): Bitmap? {
        val bitmap = ready.getAndSet(null)
        prefetch(width, height)
        return bitmap
    }

    /** Starts decoding the next photo if one is not ready or on its way. */
    fun prefetch(width: Int, height: Int) {
        if (!hasPhotos || ready.get() != null || !decoding.compareAndSet(false, true)) return
        worker.execute {
            try {
                decodeNext(width, height)?.let { decoded ->
                    ready.getAndSet(decoded)?.recycle()
                }
            } finally {
                decoding.set(false)
            }
        }
    }

    override fun close() {
        worker.shutdownNow()
        ready.getAndSet(null)?.recycle()
    }

    /** Worker thread. Walks a shuffled order, dropping photos that no longer open. */
    private fun decodeNext(width: Int, height: Int): Bitmap? {
        repeat(chosen.size) {
            if (order.isEmpty()) order.addAll(chosen.shuffled())
            val uri = order.removeFirstOrNull() ?: return null
            try {
                return decode(uri, width, height)
            } catch (e: Exception) {
                // Deleted, or the grant is gone. Forget it rather than failing
                // on it every round.
                Log.w(TAG, "dropping a photo that no longer opens", e)
                chosen = chosen - uri
                order.remove(uri)
                save()
            }
        }
        return null
    }

    private fun decode(uri: Uri, width: Int, height: Int): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(resolver, uri)
            // ImageDecoder applies EXIF rotation, so photos come out upright.
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // GLUtils cannot upload hardware bitmaps
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, width, height))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
            }
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IllegalStateException("undecodable photo")
        }

    /**
     * The largest power-of-two reduction that still covers the screen, so a
     * 50-megapixel photo does not become a 200 MB bitmap to draw at 4 MP.
     */
    private fun sampleSize(imageWidth: Int, imageHeight: Int, width: Int, height: Int): Int {
        var sample = 1
        while (imageWidth / (sample * 2) >= width && imageHeight / (sample * 2) >= height) sample *= 2
        return sample
    }

    private fun save() {
        hasPhotos = chosen.isNotEmpty()
        prefs.edit().putStringSet(KEY_PHOTOS, chosen.map(Uri::toString).toSet()).apply()
    }

    private fun releaseGrants(dropped: List<Uri>) {
        for (uri in dropped) {
            try {
                resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Never had one; nothing to release.
            }
        }
    }

    private companion object {
        const val TAG = "microphun.photos"
        const val PREFS = "fire"
        const val KEY_PHOTOS = "photos"
    }
}
