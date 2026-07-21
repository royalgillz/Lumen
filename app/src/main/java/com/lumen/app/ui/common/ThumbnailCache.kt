package com.lumen.app.ui.common

import android.graphics.Bitmap
import androidx.collection.LruCache

/**
 * App-wide LRU for page thumbnails, keyed by "uri#pageIndex".
 *
 * Without it every thumbnail composition reopened and reparsed its whole PDF —
 * per grid card, per list row, again each time a row scrolled back on screen —
 * all serialised through the single global render permit and contending with
 * the viewer.
 *
 * Evicted bitmaps are NOT recycled: a row may still be drawing one. On API 26+
 * bitmap pixels live on the native heap and are reclaimed by the GC's cleaner,
 * so dropping the reference is safe and sufficient.
 */
object ThumbnailCache {

    private const val MAX_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun key(uriString: String, pageIndex: Int): String = "$uriString#$pageIndex"

    fun get(key: String): Bitmap? = cache.get(key)?.takeIf { !it.isRecycled }

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }
}
