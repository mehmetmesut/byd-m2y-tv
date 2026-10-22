package com.m2y.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Minimal logo/poster loader: decodes at the displayed size (inSampleSize), opaque posters as RGB_565,
 * keeps an 8 MB memory LRU, and lets OkHttp's capped disk cache serve repeats. A rebound view cancels its request.
 */
class Images(client: () -> OkHttpClient) {
    private val http by lazy(client) // built on the first fetch, on an IO thread (cacheDir touches the disk)
    private val mem = object : LruCache<String, Bitmap>(MEM_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(4)

    fun load(scope: CoroutineScope, view: ImageView, url: String?, w: Int, h: Int, opaque: Boolean) {
        (view.tag as? Job)?.cancel()
        view.tag = null
        val key = "$w:$url"
        val hit = url?.let { mem.get(key) }
        view.setImageBitmap(hit)
        if (url == null || hit != null || synchronized(failed) { url in failed }) return
        view.tag = scope.launch {
            val bmp = withContext(io) { fetch(url, w, h, opaque) } ?: return@launch
            mem.put(key, bmp)
            view.setImageBitmap(bmp)
        }
    }

    /** Null on failure; only permanent failures (bad status, too big, not an image) are remembered and not retried. */
    private fun fetch(url: String, w: Int, h: Int, opaque: Boolean): Bitmap? = try {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val src = resp.body?.source()?.takeIf { resp.isSuccessful } ?: return giveUp(url)
            src.request(MAX_BYTES + 1L)
            if (src.buffer.size > MAX_BYTES) return giveUp(url)
            val bytes = src.readByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return giveUp(url)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                },
            )
        }
    } catch (e: IOException) {
        null // network trouble: try again next time the row is shown
    } catch (e: IllegalArgumentException) {
        giveUp(url) // malformed logo URL in the playlist
    }

    private fun giveUp(url: String): Bitmap? {
        synchronized(failed) { if (failed.size < MAX_FAILED) failed += url }
        return null
    }

    private companion object {
        const val MEM_BYTES = 8 shl 20
        const val MAX_BYTES = 2 shl 20
        const val MAX_FAILED = 2000
    }
}
