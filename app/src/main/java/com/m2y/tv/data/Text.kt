package com.m2y.tv.data

/** Search key: lower case, Turkish letters folded to ASCII, so "Şok" matches "sok" and "İzmir" matches "izmir". */
fun searchKey(s: String): String {
    val b = StringBuilder(s.length)
    for (c in s) {
        b.append(
            when (c) {
                'ç', 'Ç' -> 'c'
                'ğ', 'Ğ' -> 'g'
                'ı', 'I', 'İ', 'i' -> 'i'
                'ö', 'Ö' -> 'o'
                'ş', 'Ş' -> 's'
                'ü', 'Ü' -> 'u'
                else -> c.lowercaseChar()
            },
        )
    }
    return b.toString()
}

/** LIKE pattern for [searchKey]-ed text, '\' escaped. */
fun likePattern(query: String): String =
    "%" + searchKey(query.trim()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** Kind of an M3U entry from its URL: Xtream-style panels put VOD under /movie/ and /series/. */
fun kindOfUrl(url: String): Kind {
    val path = url.substringBefore('?').lowercase()
    return when {
        "/series/" in path -> Kind.SERIES
        "/movie/" in path -> Kind.MOVIE
        "/live/" !in path && (path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".avi")) -> Kind.MOVIE
        else -> Kind.LIVE
    }
}

/** "host:port/" or ".../player_api.php" → "http://host:port". */
fun normalizeServer(input: String): String {
    var s = input.trim().trimEnd('/')
    s = s.substringBefore("/player_api.php").substringBefore("/get.php").trimEnd('/')
    if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
    return s
}

fun isHttpUrl(s: String) = Regex("^https?://[^\\s/]+.*", RegexOption.IGNORE_CASE).matches(s.trim())

/**
 * Keeps M3U credentials out of the catalogue: when the playlist address carries `username`/`password`
 * (Xtream panels' get.php), stream URL path segments equal to them are stored as placeholders.
 */
class UrlMask private constructor(private val user: String, private val pass: String) {
    fun mask(url: String) = mapSegments(url) { if (it == user) U else if (it == pass) P else it }
    fun unmask(url: String) = mapSegments(url) { if (it == U) user else if (it == P) pass else it }

    private fun mapSegments(url: String, f: (String) -> String): String {
        val q = url.indexOf('?').let { if (it < 0) url.length else it }
        return url.substring(0, q).split('/').joinToString("/") { f(it) } + url.substring(q)
    }

    companion object {
        private const val U = "{u}"
        private const val P = "{p}"
        private val NONE = UrlMask("\u0000", "\u0000")

        fun forPlaylist(m3uUrl: String): UrlMask {
            val query = m3uUrl.substringAfter('?', "").substringBefore('#')
            val params = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val u = params["username"].orEmpty()
            val p = params["password"].orEmpty()
            return if (u.isEmpty() || p.isEmpty()) NONE else UrlMask(u, p)
        }
    }
}
