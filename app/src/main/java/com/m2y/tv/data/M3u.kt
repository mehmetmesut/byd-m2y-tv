package com.m2y.tv.data

class M3uEntry(
    val name: String,
    val url: String,
    val group: String?,
    val logo: String?,
    val tvgId: String?,
    val chno: Int?,
    val opts: String?,
)

/**
 * Streaming M3U/M3U8 reader: consumes lines lazily and yields one entry at a time, so a playlist of any size
 * is never held in RAM. Malformed entries are skipped instead of failing the import.
 */
class M3uParser {
    /** XMLTV address announced in the #EXTM3U header (url-tvg / x-tvg-url), if any. */
    var epgUrl: String? = null
        private set

    fun parse(lines: Sequence<String>): Sequence<M3uEntry> = sequence {
        var head: String? = null // attribute part of the pending #EXTINF
        var name = ""
        var group: String? = null
        var ua: String? = null
        var ref: String? = null
        for (raw in lines) {
            val line = raw.trim().trimStart(BOM)
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    val a = attrs(line)
                    epgUrl = (a["url-tvg"] ?: a["x-tvg-url"])?.split(',')?.firstOrNull { it.isNotBlank() }?.trim()
                }
                line.startsWith("#EXTINF") -> {
                    val comma = nameComma(line)
                    head = if (comma < 0) line else line.substring(0, comma)
                    name = if (comma < 0) "" else line.substring(comma + 1).trim()
                }
                line.startsWith("#EXTGRP:") -> group = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:") -> {
                    val o = line.substring(11).trim()
                    if (o.startsWith("http-user-agent=")) ua = o.substringAfter('=')
                    if (o.startsWith("http-referrer=")) ref = o.substringAfter('=')
                }
                line.startsWith("#") -> Unit
                else -> {
                    val h = head
                    if (h != null) {
                        val a = attrs(h)
                        val title = name.ifBlank { a["tvg-name"].orEmpty() }.ifBlank { line.substringAfterLast('/') }
                        yield(
                            M3uEntry(
                                name = title,
                                url = line,
                                group = a["group-title"]?.takeIf { it.isNotBlank() } ?: group,
                                logo = a["tvg-logo"]?.takeIf { it.isNotBlank() },
                                tvgId = a["tvg-id"]?.takeIf { it.isNotBlank() },
                                chno = a["tvg-chno"]?.toIntOrNull(),
                                opts = if (ua == null && ref == null) null else "${ua.orEmpty()}\n${ref.orEmpty()}",
                            ),
                        )
                    }
                    head = null
                    group = null
                    ua = null
                    ref = null
                }
            }
        }
    }

    /** The comma that ends the attribute list; commas inside quoted values (group-title="A, B") do not count. */
    private fun nameComma(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return i
            }
        }
        return -1
    }

    private fun attrs(s: String): Map<String, String> =
        ATTR.findAll(s).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }

    private companion object {
        val BOM = Char(0xFEFF) // byte-order mark some panels put before #EXTM3U
        val ATTR = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    }
}
