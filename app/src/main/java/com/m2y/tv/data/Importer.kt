package com.m2y.tv.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.BufferedInputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * Refreshes a source's catalogue. Lists are streamed from the network straight into SQLite in batches; each kind is
 * published as soon as it is complete (live TV first), and until then the UI keeps showing the previous catalogue.
 * Call from an IO dispatcher; cancelling the coroutine aborts the download.
 */
class Importer(private val db: Db, private val http: OkHttpClient) {
    class Result(val counts: IntArray, val failed: List<Kind>, val epg: Int)

    suspend fun run(src: Source, creds: Creds, progress: (String) -> Unit, published: (Kind) -> Unit): Result {
        val job = coroutineContext.job
        val gen = System.currentTimeMillis()
        val counts = IntArray(Kind.entries.size)
        val failed = ArrayList<Kind>()
        var epgUrl = src.epgUrl
        when (creds) {
            is Creds.M3u -> {
                val announced = importM3u(job, src.id, gen, creds.url, counts, progress)
                // an error page served with status 200 parses to nothing: keep the current catalogue
                if (counts.sum() == 0) throw IOException("Liste boş ya da M3U değil")
                epgUrl = epgUrl ?: announced // an address the user typed wins over the playlist header
                for (k in Kind.entries) db.publish(src.id, k, gen).also { published(k) }
            }
            is Creds.Xtream -> {
                val (x, acc) = login(creds, db.get(serverKey(src.id)))
                db.put(serverKey(src.id), x.server)
                db.put(liveExtKey(src.id), x.formats.joinToString(","))
                db.put(connsKey(src.id), acc.conns.toString())
                for (k in Kind.entries) {
                    try {
                        counts[k.ordinal] = importXtream(job, x, src.id, gen, k, progress)
                        db.publish(src.id, k, gen)
                        published(k)
                    } catch (e: IOException) {
                        failed += k // one missing section (some panels have no series) must not lose the others
                    }
                }
                if (failed.size == Kind.entries.size) throw IOException("Liste alınamadı")
            }
        }
        db.finishImport(src.id, summary(counts))
        var programmes = 0
        if (epgUrl != null && counts[Kind.LIVE.ordinal] > 0) {
            progress("Program rehberi yükleniyor…")
            programmes = try {
                importEpg(job, src.copyWithLiveGen(gen), epgUrl)
            } catch (e: IOException) {
                -1
            }
        }
        return Result(counts, failed, programmes)
    }

    /**
     * Signs in on the first server that answers, the one that worked last time first, then the "Alternatif sunucu".
     * A refused login is final (the other server shares the account); an unreachable server moves on.
     */
    private fun login(creds: Creds.Xtream, last: String?): Pair<Xtream, Account> {
        var error: IOException? = null
        for (s in creds.servers(last)) {
            val x = Xtream(http, creds, s)
            val acc = try {
                x.account()
            } catch (e: IOException) {
                error = e
                continue
            }
            if (!acc.ok) throw IOException("Hesap doğrulanamadı")
            return x to acc
        }
        throw error ?: IOException("Sunucu yok")
    }

    /** Replaces the guide of [src] with the XMLTV file at [url] (Xtream panels that refuse `get_short_epg`). */
    suspend fun guide(src: Source, url: String): Int = importEpg(coroutineContext.job, src, url)

    private fun importM3u(job: Job, src: Long, gen: Long, url: String, counts: IntArray, progress: (String) -> Unit): String? {
        val parser = M3uParser()
        val mask = UrlMask.forPlaylist(url)
        val groups = Kind.entries.map { LinkedHashSet<String>() }
        db.Writer(src, gen).use { w ->
            get(job, url) { body ->
                body.charStream().buffered().useLines { lines ->
                    for (e in parser.parse(lines)) {
                        val kind = kindOfUrl(e.url)
                        val group = e.group ?: OTHER
                        groups[kind.ordinal] += group
                        w.item(kind, group, mask.mask(e.url), e.name, e.logo, null, e.tvgId, e.chno ?: 0, null, e.opts)
                        if (++counts[kind.ordinal] % TICK == 0) tick(job, progress, counts)
                    }
                }
            }
            for (k in Kind.entries) groups[k.ordinal].forEach { w.cat(k, it, it) }
            w.commit()
        }
        parser.epgUrl?.let { db.setEpgUrl(src, it) }
        return parser.epgUrl
    }

    private fun importXtream(job: Job, x: Xtream, src: Long, gen: Long, kind: Kind, progress: (String) -> Unit): Int =
        db.Writer(src, gen).use { w ->
            x.categories(kind) { id, name -> w.cat(kind, id, name) }
            var n = 0
            x.streams(kind) { s ->
                w.item(kind, s.cat, s.id, s.name, s.logo, s.cext, s.epg, s.num, s.rating, null)
                if (++n % TICK == 0) {
                    job.ensureActive()
                    progress("${LABELS[kind.ordinal]} ${fmt(n)}")
                }
            }
            w.commit()
            n
        }

    private fun importEpg(job: Job, src: Source, url: String): Int {
        val keep = db.epgIds(src)
        if (keep.isEmpty()) return 0
        val now = System.currentTimeMillis() / 1000
        var n = 0
        get(job, url) { body ->
            val raw = BufferedInputStream(body.byteStream(), 64 * 1024)
            raw.mark(2)
            val gz = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            val input = if (gz) GZIPInputStream(raw, 64 * 1024) else raw
            n = db.replaceEpg(src.id) { emit ->
                var seen = 0
                Xmltv.parse(input, now - EPG_PAST_S, now + EPG_AHEAD_S, keep::contains) { ch, p ->
                    emit(ch, p)
                    if (++seen % TICK == 0) job.ensureActive()
                }
            }
        }
        return n
    }

    /** GET [url] and hand the body to [read]; cancelling [job] cancels the call so a stalled download ends at once. */
    private fun get(job: Job, url: String, read: (ResponseBody) -> Unit) {
        val call = http.newCall(Request.Builder().url(url).build())
        val hook = job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                read(resp.body ?: throw IOException("Boş yanıt"))
            }
        } finally {
            hook.dispose()
        }
        job.ensureActive()
    }

    private fun tick(job: Job, progress: (String) -> Unit, counts: IntArray) {
        job.ensureActive()
        progress(summary(counts))
    }

    private fun Source.copyWithLiveGen(g: Long) = Source(id, name, xtream, secret, epgUrl, gens.copyOf().also { it[Kind.LIVE.ordinal] = g }, updated, info)

    companion object {
        const val OTHER = "Diğer"
        private const val TICK = 2000
        private const val EPG_PAST_S = 2 * 3600L
        private const val EPG_AHEAD_S = 36 * 3600L
        val LABELS = arrayOf("Canlı", "Film", "Dizi")
        private val TR = Locale.forLanguageTag("tr")

        /** kv key of the live outputs an Xtream account allows, preferred first ("ts,m3u8"). */
        fun liveExtKey(src: Long) = "fmt_$src"

        /** kv key of the Xtream server (main or alternative) that answered the last import. */
        fun serverKey(src: Long) = "srv_$src"

        /** kv key of the account's `max_connections` (0 = the panel did not say). */
        fun connsKey(src: Long) = "conns_$src"

        fun fmt(n: Int): String = String.format(TR, "%,d", n)

        fun summary(c: IntArray) = Kind.entries.filter { c[it.ordinal] > 0 }.joinToString(" · ") { "${LABELS[it.ordinal]} ${fmt(c[it.ordinal])}" }
            .ifEmpty { "Boş liste" }
    }
}
