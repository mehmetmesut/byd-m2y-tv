package com.m2y.tv.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Pull-parses an XMLTV guide in one pass with O(1) memory. Only programmes whose channel passes [keep] and that
 * overlap the [from]..[to] window (epoch seconds) are emitted, so a guide for thousands of channels and two weeks
 * shrinks to what this playlist can show.
 */
object Xmltv {
    fun parse(input: InputStream, from: Long, to: Long, keep: (String) -> Boolean, emit: (String, Programme) -> Unit) {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var ch: String? = null
        var start = 0L
        var stop = 0L
        var title: String? = null
        var desc: String? = null
        var field = 0 // 1 title, 2 desc
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "programme" -> {
                        start = time(p.getAttributeValue(null, "start"))
                        stop = time(p.getAttributeValue(null, "stop"))
                        ch = p.getAttributeValue(null, "channel")?.takeIf { stop > from && start < to && keep(it) }
                        title = null
                        desc = null
                    }
                    "title" -> if (ch != null && title == null) field = 1
                    "desc" -> if (ch != null && desc == null) field = 2
                }
                XmlPullParser.TEXT -> when (field) {
                    1 -> title = p.text?.trim()
                    2 -> desc = p.text?.trim()
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "title", "desc" -> field = 0
                    "programme" -> {
                        val c = ch
                        val t = title
                        if (c != null && !t.isNullOrEmpty() && stop > start) emit(c, Programme(start, stop, t, desc?.takeIf { it.isNotEmpty() }))
                        ch = null
                    }
                }
            }
            ev = p.next()
        }
    }

    /** XMLTV time "20261022183000 +0300" → epoch seconds; no offset means UTC; 0 when unreadable. */
    fun time(v: String?): Long {
        val s = v?.trim() ?: return 0
        if (s.length < 14 || !s.substring(0, 14).all(Char::isDigit)) return 0
        fun n(a: Int, b: Int) = s.substring(a, b).toInt()
        val off = s.substring(14).trim()
        val offSec = if (off.length >= 5 && (off[0] == '+' || off[0] == '-') && off.substring(1, 5).all(Char::isDigit)) {
            (if (off[0] == '-') -1 else 1) * (off.substring(1, 3).toInt() * 3600 + off.substring(3, 5).toInt() * 60)
        } else {
            0
        }
        return try {
            LocalDateTime.of(n(0, 4), n(4, 6), n(6, 8), n(8, 10), n(10, 12), n(12, 14)).toEpochSecond(ZoneOffset.ofTotalSeconds(offSec))
        } catch (e: DateTimeException) {
            0
        }
    }
}
