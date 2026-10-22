package com.m2y.tv.data

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder

class XtreamStream(
    val id: String,
    val name: String,
    val cat: String?,
    val logo: String?,
    val epg: String?,
    val num: Int,
    val cext: String?,
    val rating: String?,
)

class Account(val ok: Boolean, val status: String?, val expiry: Long?, val formats: List<String>)

class VodInfo(val plot: String?, val genre: String?, val year: String?, val duration: String?)

class SeriesInfo(val plot: String?, val episodes: List<Episode>)

/**
 * Xtream Codes `player_api.php` client. Every response is read with a streaming [JsonReader] straight off the
 * socket: a provider's VOD list can be tens of MB and is never materialised as a String or object list.
 * All calls block; run them on an IO dispatcher.
 */
class Xtream(private val http: OkHttpClient, private val creds: Creds.Xtream) {
    val base = normalizeServer(creds.server)
    /** Live output extension; "ts" unless the account only allows HLS. */
    var liveExt = "ts"

    private fun api(action: String? = null, extra: String = "") =
        "$base/player_api.php?username=${enc(creds.user)}&password=${enc(creds.pass)}" +
            (action?.let { "&action=$it" } ?: "") + extra

    fun streamUrl(kind: Kind, id: String, cext: String?): String {
        val dir = when (kind) {
            Kind.LIVE -> "live"
            Kind.MOVIE -> "movie"
            Kind.SERIES -> "series"
        }
        val ext = if (kind == Kind.LIVE) liveExt else cext ?: "mp4"
        return "$base/$dir/${creds.user}/${creds.pass}/$id.$ext"
    }

    fun account(): Account = read(api()) { r ->
        var acc = Account(false, null, null, emptyList())
        r.obj { key ->
            if (key == "user_info") acc = readAccount(r) else r.skipValue()
        }
        acc.also { if (it.ok && "ts" !in it.formats && "m3u8" in it.formats) liveExt = "m3u8" }
    }

    private fun readAccount(r: JsonReader): Account {
        if (r.peek() != JsonToken.BEGIN_OBJECT) return Account(false, null, null, emptyList()).also { r.skipValue() }
        var auth = false
        var status: String? = null
        var exp: Long? = null
        val formats = ArrayList<String>()
        r.obj { k ->
            when (k) {
                "auth" -> auth = r.str() == "1"
                "status" -> status = r.str()
                "exp_date" -> exp = r.str()?.toLongOrNull()
                "allowed_output_formats" -> if (r.peek() == JsonToken.BEGIN_ARRAY) r.arr { r.str()?.let(formats::add) } else r.skipValue()
                else -> r.skipValue()
            }
        }
        return Account(auth, status, exp, formats)
    }

    fun categories(kind: Kind, onCat: (id: String, name: String) -> Unit) = read(api(action(kind, "categories"))) { r ->
        r.arr {
            var id: String? = null
            var name: String? = null
            r.obj { k ->
                when (k) {
                    "category_id" -> id = r.str()
                    "category_name" -> name = r.str()
                    else -> r.skipValue()
                }
            }
            val i = id
            if (i != null) onCat(i, name?.takeIf { it.isNotBlank() } ?: i)
        }
    }

    /** Streams the whole list of [kind]; [onItem] runs once per entry while the response is still downloading. */
    fun streams(kind: Kind, onItem: (XtreamStream) -> Unit) = read(api(action(kind, "streams"))) { r ->
        r.arr {
            var id: String? = null
            var name: String? = null
            var cat: String? = null
            var logo: String? = null
            var epg: String? = null
            var num = 0
            var cext: String? = null
            var rating: String? = null
            r.obj { k ->
                when (k) {
                    "stream_id", "series_id" -> id = r.str()
                    "name" -> name = r.str()
                    "category_id" -> cat = r.str()
                    "stream_icon", "cover" -> logo = r.str()?.takeIf { it.isNotBlank() }
                    "epg_channel_id" -> epg = r.str()?.takeIf { it.isNotBlank() }
                    "num" -> num = r.str()?.toIntOrNull() ?: 0
                    "container_extension" -> cext = r.str()
                    "rating" -> rating = r.str()?.takeIf { it.isNotBlank() && it != "0" }
                    else -> r.skipValue()
                }
            }
            val i = id
            if (i != null) onItem(XtreamStream(i, name?.trim().orEmpty().ifEmpty { i }, cat, logo, epg, num, cext, rating))
        }
    }

    fun vodInfo(id: String): VodInfo = read(api("get_vod_info", "&vod_id=${enc(id)}")) { r ->
        var info = VodInfo(null, null, null, null)
        r.obj { k -> if (k == "info" && r.peek() == JsonToken.BEGIN_OBJECT) info = readVod(r) else r.skipValue() }
        info
    }

    private fun readVod(r: JsonReader): VodInfo {
        var plot: String? = null
        var genre: String? = null
        var year: String? = null
        var duration: String? = null
        r.obj { k ->
            when (k) {
                "plot", "description" -> plot = plot ?: r.str()?.takeIf { it.isNotBlank() }
                "genre" -> genre = r.str()
                "releasedate", "release_date" -> year = year ?: r.str()?.take(4)?.takeIf { it.isNotBlank() }
                "duration" -> duration = r.str()
                else -> r.skipValue()
            }
        }
        return VodInfo(plot, genre, year, duration)
    }

    fun seriesInfo(id: String): SeriesInfo = read(api("get_series_info", "&series_id=${enc(id)}")) { r ->
        var plot: String? = null
        val eps = ArrayList<Episode>()
        r.obj { k ->
            when {
                k == "info" && r.peek() == JsonToken.BEGIN_OBJECT -> plot = readVod(r).plot
                // object keyed by season ("1": [...]) on most panels, an array of arrays on some
                k == "episodes" && r.peek() == JsonToken.BEGIN_OBJECT -> r.obj { s -> readEpisodes(r, s.toIntOrNull() ?: 0, eps) }
                k == "episodes" && r.peek() == JsonToken.BEGIN_ARRAY -> {
                    var s = 0
                    r.arr { readEpisodes(r, ++s, eps) }
                }
                else -> r.skipValue()
            }
        }
        eps.sortWith(compareBy({ it.season }, { it.num }))
        SeriesInfo(plot, eps)
    }

    private fun readEpisodes(r: JsonReader, season: Int, out: MutableList<Episode>) {
        if (r.peek() != JsonToken.BEGIN_ARRAY) return r.skipValue()
        r.arr {
            var id: String? = null
            var num = 0
            var title: String? = null
            var cext = "mp4"
            var s = season
            r.obj { k ->
                when (k) {
                    "id" -> id = r.str()
                    "episode_num" -> num = r.str()?.toIntOrNull() ?: 0
                    "title" -> title = r.str()
                    "container_extension" -> cext = r.str() ?: cext
                    "season" -> s = r.str()?.toIntOrNull() ?: s
                    else -> r.skipValue()
                }
            }
            id?.let { out += Episode(s, num, title?.takeIf { t -> t.isNotBlank() } ?: "$num. bölüm", it, cext) }
        }
    }

    /** Now/next listings of a live stream; titles arrive base64-encoded. */
    fun shortEpg(streamId: String, limit: Int): List<Programme> =
        read(api("get_short_epg", "&stream_id=${enc(streamId)}&limit=$limit")) { r ->
            val out = ArrayList<Programme>()
            r.obj { k ->
                if (k == "epg_listings" && r.peek() == JsonToken.BEGIN_ARRAY) {
                    r.arr {
                        var title: String? = null
                        var desc: String? = null
                        var start = 0L
                        var stop = 0L
                        r.obj { f ->
                            when (f) {
                                "title" -> title = r.str()?.let(::b64)
                                "description" -> desc = r.str()?.let(::b64)
                                "start_timestamp" -> start = r.str()?.toLongOrNull() ?: 0
                                "stop_timestamp" -> stop = r.str()?.toLongOrNull() ?: 0
                                else -> r.skipValue()
                            }
                        }
                        val t = title
                        if (!t.isNullOrBlank() && stop > start) out += Programme(start, stop, t, desc?.takeIf { it.isNotBlank() })
                    }
                } else {
                    r.skipValue()
                }
            }
            out
        }

    private fun <T> read(url: String, parse: (JsonReader) -> T): T {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Boş yanıt")
            return try {
                JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { r ->
                    // an HTML error page with status 200 must fail the refresh, not publish an empty catalogue
                    val top = r.peek()
                    if (top != JsonToken.BEGIN_ARRAY && top != JsonToken.BEGIN_OBJECT) throw IOException("Sunucu yanıtı okunamadı")
                    parse(r)
                }
            } catch (e: IllegalStateException) {
                throw IOException("Sunucu yanıtı okunamadı", e) // HTML error page, empty object instead of list...
            } catch (e: NumberFormatException) {
                throw IOException("Sunucu yanıtı okunamadı", e)
            }
        }
    }

    private companion object {
        fun action(kind: Kind, what: String) = when (kind) {
            Kind.LIVE -> "get_live_$what"
            Kind.MOVIE -> "get_vod_$what"
            Kind.SERIES -> if (what == "streams") "get_series" else "get_series_categories"
        }

        fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

        fun b64(s: String): String = try {
            String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            s // some panels send plain text
        }
    }
}

/** Primitive value as text whatever its JSON type; null for null, objects and arrays (skipped). */
internal fun JsonReader.str(): String? = when (peek()) {
    JsonToken.NULL -> null.also { nextNull() }
    JsonToken.BOOLEAN -> nextBoolean().toString()
    JsonToken.STRING, JsonToken.NUMBER -> nextString()
    else -> null.also { skipValue() }
}

/** Iterates an object's members; a non-object value (provider sent [] or "") is skipped. */
internal inline fun JsonReader.obj(member: (String) -> Unit) {
    if (peek() != JsonToken.BEGIN_OBJECT) return skipValue()
    beginObject()
    while (hasNext()) member(nextName())
    endObject()
}

/** Iterates an array's elements; a non-array value (provider sent {} for "empty") is skipped. */
internal inline fun JsonReader.arr(element: () -> Unit) {
    if (peek() != JsonToken.BEGIN_ARRAY) return skipValue()
    beginArray()
    while (hasNext()) element()
    endArray()
}
