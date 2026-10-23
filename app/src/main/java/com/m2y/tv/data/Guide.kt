package com.m2y.tv.data

import com.m2y.tv.Graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Programme guide of the side pane. Xtream: `get_short_epg` per channel, cached; a refused call is asked once more
 * after [SHORT_RETRY_MS] (a network wobble), then the panel's xmltv.php is imported like an M3U guide and read from
 * the database. M3U: the XMLTV file the import loaded. Main thread only.
 */
class Guide(private val scope: CoroutineScope, private val onXmltv: (src: Source, ok: Boolean) -> Unit) {
    private val cache = object : LinkedHashMap<String, Pair<Long, List<Programme>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<Programme>>>) = size > CACHE_MAX
    }
    private val shortRefused = HashSet<Long>()
    private val xmltvAt = HashMap<Long, Long>()

    suspend fun of(src: Source, x: Xtream?, item: Item, rows: Int): List<Programme> {
        val now = System.currentTimeMillis() / 1000
        if (x != null && src.id !in shortRefused) {
            cache[item.ext]?.takeIf { now - it.first < TTL_S }?.let { return it.second }
            val list = short(x, item.ext, rows) ?: delay(SHORT_RETRY_MS).let { short(x, item.ext, rows) }
            if (list != null) {
                cache[item.ext] = now to list
                return list
            }
            shortRefused += src.id
        }
        if (x != null) loadXmltv(src, x)
        return item.epg?.let { ch -> withContext(Dispatchers.IO) { Graph.db.epg(src.id, ch, now, rows) } }.orEmpty()
    }

    private suspend fun short(x: Xtream, id: String, rows: Int): List<Programme>? = withContext(Dispatchers.IO) {
        try {
            x.shortEpg(id, rows)
        } catch (e: IOException) {
            null
        }
    }

    /** One xmltv.php import per [XMLTV_TTL_MS], also after a failure: a broken guide address is not hammered. */
    private fun loadXmltv(src: Source, x: Xtream) {
        val now = System.currentTimeMillis()
        if (now - (xmltvAt[src.id] ?: 0L) < XMLTV_TTL_MS) return
        xmltvAt[src.id] = now
        scope.launch {
            val n = withContext(Dispatchers.IO) {
                try {
                    Graph.importer.guide(src, x.xmltvUrl())
                } catch (e: IOException) {
                    -1
                }
            }
            onXmltv(src, n >= 0)
        }
    }

    private companion object {
        const val CACHE_MAX = 64
        const val TTL_S = 600L
        const val SHORT_RETRY_MS = 2_000L
        const val XMLTV_TTL_MS = 12 * 3600_000L
    }
}
