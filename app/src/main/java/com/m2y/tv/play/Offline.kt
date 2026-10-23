package com.m2y.tv.play

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.m2y.tv.data.Kind
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors

/**
 * Films and episodes away from the network.
 * Downloads: Media3's DownloadManager into the app's own storage (no permission needed), one at a time, at most
 * [DOWNLOAD_CAP] in all. There is no service (BYD blocks background services): downloading goes on while the app's
 * process lives. Watch cache: what a film already loaded stays on disk up to [WATCH_CAP], least recently used first
 * out, so rewinding or reopening it does not download it again. Created on first use; main thread only.
 */
@OptIn(UnstableApi::class)
class Offline(ctx: Context, http: OkHttpClient) {
    /** One download; [percent] is 0–100 or -1 while the size is unknown. */
    class Entry(val id: String, val kind: Kind, val title: String, val logo: String?, val url: String, val state: Int, val percent: Int) {
        val done get() = state == Download.STATE_COMPLETED
    }

    private val app = ctx.applicationContext
    private val db = StandaloneDatabaseProvider(app)
    private val downloads = SimpleCache(File(app.filesDir, "downloads"), NoOpCacheEvictor(), db)
    private val watch = SimpleCache(File(app.cacheDir, "watch"), LeastRecentlyUsedCacheEvictor(WATCH_CAP), db)
    private val handler = Handler(Looper.getMainLooper())
    private val manager = DownloadManager(app, db, downloads, OkHttpDataSource.Factory(http), Executors.newSingleThreadExecutor()).apply {
        maxParallelDownloads = 1 // the account may allow one connection in all
        addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(dm: DownloadManager, download: Download, finalException: Exception?) {
                    onChange?.invoke()
                    tick()
                }

                override fun onDownloadRemoved(dm: DownloadManager, download: Download) {
                    onChange?.invoke()
                }
            },
        )
    }
    private var held = true // a DownloadManager starts paused; the first hold(false) lets it run

    /** Pauses downloading while a stream plays on a one-connection account; resumes when it stops. */
    fun hold(on: Boolean) {
        if (on == held) return
        held = on
        if (on) manager.pauseDownloads() else manager.resumeDownloads()
    }

    /** Something in the list changed (state, or progress every [TICK_MS] while downloading). */
    var onChange: (() -> Unit)? = null

    /** A download was stopped because the [DOWNLOAD_CAP] would be exceeded; gets its title. */
    var onCapReached: ((String) -> Unit)? = null

    /** Films and episodes read the downloads first (never written there), then the watch cache, then the network. */
    fun vod(upstream: DataSource.Factory): DataSource.Factory = CacheDataSource.Factory()
        .setCache(downloads)
        .setCacheWriteDataSinkFactory(null)
        .setUpstreamDataSourceFactory(
            CacheDataSource.Factory().setCache(watch).setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        )

    /** Queues a download; returns the reason when it cannot start. */
    fun add(id: String, kind: Kind, title: String, logo: String?, url: String): String? {
        if (downloads.cacheSpace >= DOWNLOAD_CAP) return "İndirme alanı dolu (5 GB). Önce indirilen bir şeyi silin."
        val req = DownloadRequest.Builder(id, Uri.parse(url))
            .setMimeType(if (url.substringBefore('?').endsWith(".m3u8", true)) MimeTypes.APPLICATION_M3U8 else null)
            .setData(listOf(kind.ordinal.toString(), title, logo.orEmpty()).joinToString("\n").toByteArray())
            .build()
        manager.addDownload(req)
        return null
    }

    fun remove(id: String) = manager.removeDownload(id)

    /** Every download of [kind], newest first; reads the download index (disk): call from an IO thread. */
    fun list(kind: Kind): List<Entry> = manager.downloadIndex.getDownloads().use { c ->
        buildList { while (c.moveToNext()) add(c.download) }
    }.sortedByDescending { it.startTimeMs }.mapNotNull { d -> entry(d)?.takeIf { it.kind == kind } }

    /** One download or null; disk read, IO thread. */
    fun get(id: String): Entry? = manager.downloadIndex.getDownload(id)?.let(::entry)

    /** Removes the downloads of a deleted playlist (ids start with its id). IO thread. */
    fun removeSource(src: Long) {
        val ids = manager.downloadIndex.getDownloads().use { c -> buildList { while (c.moveToNext()) add(c.download.request.id) } }
        ids.filter { it.startsWith("$src/") }.forEach { handler.post { manager.removeDownload(it) } }
    }

    private fun entry(d: Download): Entry? {
        val p = String(d.request.data).split('\n')
        val kind = Kind.entries.getOrNull(p.getOrNull(0)?.toIntOrNull() ?: return null) ?: return null
        val percent = if (d.state == Download.STATE_COMPLETED) 100 else d.percentDownloaded.takeIf { it >= 0 }?.toInt() ?: -1
        return Entry(d.request.id, kind, p.getOrNull(1).orEmpty(), p.getOrNull(2)?.takeIf { it.isNotEmpty() }, d.request.uri.toString(), d.state, percent)
    }

    /** While downloading: progress for the UI and the size cap (the file's length is known once it started). */
    private fun tick() {
        handler.removeCallbacks(ticker)
        if (manager.currentDownloads.any { it.state == Download.STATE_DOWNLOADING }) handler.postDelayed(ticker, TICK_MS)
    }

    private val ticker = Runnable {
        for (d in manager.currentDownloads) {
            if (d.state != Download.STATE_DOWNLOADING) continue
            val total = downloads.cacheSpace - d.bytesDownloaded + maxOf(d.contentLength, d.bytesDownloaded)
            if (total > DOWNLOAD_CAP) {
                manager.removeDownload(d.request.id)
                onCapReached?.invoke(entry(d)?.title.orEmpty())
            }
        }
        onChange?.invoke()
        tick()
    }

    companion object {
        const val DOWNLOAD_CAP = 5L shl 30
        const val WATCH_CAP = 500L shl 20
        private const val TICK_MS = 3_000L

        /** Download id of a film ([kind] MOVIE, [key] its stream id or address) or an episode (SERIES, episode id). */
        fun id(src: Long, kind: Kind, key: String) = "$src/${kind.ordinal}/$key"
    }
}
