package com.m2y.tv

import com.m2y.tv.data.Kind
import com.m2y.tv.data.M3uParser
import com.m2y.tv.data.UrlMask
import com.m2y.tv.data.isHttpUrl
import com.m2y.tv.data.kindOfUrl
import com.m2y.tv.data.likePattern
import com.m2y.tv.data.normalizeServer
import com.m2y.tv.data.searchKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test
    fun searchKeyFoldsTurkish() {
        assertEquals("sok tv izmir cagri", searchKey("ŞOK TV İzmir Çağrı"))
        assertEquals("ilgi", searchKey("ILGI"))
        assertEquals("ilgi", searchKey("ılgı"))
        assertEquals("%a\\%b\\_c%", likePattern(" A%b_C "))
    }

    @Test
    fun kindFromUrl() {
        assertEquals(Kind.LIVE, kindOfUrl("http://h:80/live/u/p/1.ts"))
        assertEquals(Kind.LIVE, kindOfUrl("http://h/u/p/123"))
        assertEquals(Kind.MOVIE, kindOfUrl("http://h/movie/u/p/9.mkv"))
        assertEquals(Kind.MOVIE, kindOfUrl("http://h/films/x.MP4?token=1"))
        assertEquals(Kind.SERIES, kindOfUrl("http://h/series/u/p/77.mp4"))
    }

    @Test
    fun serverAndUrlChecks() {
        assertEquals("http://host:8080", normalizeServer(" host:8080/ "))
        assertEquals("https://h.tv", normalizeServer("https://h.tv/player_api.php?username=a"))
        assertTrue(isHttpUrl("http://a.b/c.m3u"))
        assertFalse(isHttpUrl("ftp://a.b/c"))
        assertFalse(isHttpUrl("http:// a"))
    }

    @Test
    fun urlMaskHidesCredentialSegmentsAndRestoresThem() {
        val m = UrlMask.forPlaylist("http://h:80/get.php?username=ali&password=s3cr&type=m3u_plus")
        val url = "http://h:80/live/ali/s3cr/1234.ts?x=ali"
        val masked = m.mask(url)
        assertFalse("ali/" in masked || "s3cr" in masked.substringBefore('?'))
        assertEquals(url, m.unmask(masked))
        // a segment that merely contains the user name stays untouched
        assertEquals("http://h/alice/1", m.mask("http://h/alice/1"))
        val none = UrlMask.forPlaylist("http://h/list.m3u")
        assertEquals("http://h/a/b", none.mask("http://h/a/b"))
    }

    @Test
    fun m3uParserReadsAttributesGroupsAndOptions() {
        val text = Char(0xFEFF) + """
            #EXTM3U url-tvg="http://e/epg.xml.gz,http://e/2.xml"
            #EXTINF:-1 tvg-id="trt1.tr" tvg-logo="http://l/1.png" group-title="Ulusal, HD" tvg-chno="5",TRT 1 HD
            http://h/live/u/p/1.ts
            #EXTINF:-1,Radyo
            #EXTGRP:Radyolar
            #EXTVLCOPT:http-user-agent=VLC/3
            #EXTVLCOPT:http-referrer=http://r/
            http://h/radio.aac
            http://h/orphan-url-without-extinf
            #EXTINF:-1 tvg-name="Yedek Ad",
            http://h/x/42
        """.trimIndent().replace("\n", "\r\n")
        val p = M3uParser()
        val list = p.parse(text.lineSequence()).toList()
        assertEquals("http://e/epg.xml.gz", p.epgUrl)
        assertEquals(3, list.size)
        with(list[0]) {
            assertEquals("TRT 1 HD", name)
            assertEquals("Ulusal, HD", group)
            assertEquals("trt1.tr", tvgId)
            assertEquals("http://l/1.png", logo)
            assertEquals(5, chno)
            assertNull(opts)
        }
        with(list[1]) {
            assertEquals("Radyo", name)
            assertEquals("Radyolar", group)
            assertEquals("VLC/3\nhttp://r/", opts)
        }
        assertEquals("Yedek Ad", list[2].name)
        assertNull(list[2].group)
    }
}
