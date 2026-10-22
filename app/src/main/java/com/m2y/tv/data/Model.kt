package com.m2y.tv.data

enum class Kind { LIVE, MOVIE, SERIES }

/** A playlist. [secret] holds the M3U address or Xtream login, sealed with the Keystore key ([Secrets]). */
class Source(
    val id: Long,
    val name: String,
    val xtream: Boolean,
    val secret: String,
    val epgUrl: String?,
    /** Current import generation per [Kind]; rows of other generations are a refresh in progress. */
    val gens: LongArray,
    val updated: Long,
    val info: String?,
) {
    fun gen(kind: Kind) = gens[kind.ordinal]
}

/** Decrypted credentials of a [Source]. */
sealed class Creds {
    class M3u(val url: String) : Creds()
    class Xtream(val server: String, val user: String, val pass: String) : Creds()

    fun encode() = when (this) {
        is M3u -> "m\n$url"
        is Xtream -> "x\n$server\n$user\n$pass"
    }

    companion object {
        fun decode(s: String): Creds {
            val p = s.split('\n')
            return if (p[0] == "x") Xtream(p[1], p[2], p[3]) else M3u(p[1])
        }
    }
}

/**
 * One row of the unified catalogue. M3U and Xtream share it:
 * [ext] is the masked stream URL for M3U and the provider stream/series id for Xtream.
 */
class Item(
    val id: Long,
    val kind: Kind,
    val cat: String?,
    val ext: String,
    val name: String,
    val logo: String?,
    /** Xtream container extension (ts, m3u8, mkv, mp4...). */
    val cext: String?,
    val epg: String?,
    val num: Int,
    val ord: Int,
    val rating: String?,
    /** M3U #EXTVLCOPT user agent and referrer as "ua\nreferrer". */
    val opts: String?,
)

class Cat(val key: String, val name: String) {
    companion object {
        const val ALL = "\u0001all"
        const val FAV = "\u0001fav"
    }
}

class Programme(val start: Long, val stop: Long, val title: String, val desc: String?)

class Episode(val season: Int, val num: Int, val title: String, val id: String, val cext: String)
