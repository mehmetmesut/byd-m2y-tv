package com.m2y.tv

import com.m2y.tv.data.Creds
import com.m2y.tv.data.Kind
import com.m2y.tv.data.Xmltv
import com.m2y.tv.data.Xtream
import com.m2y.tv.data.XtreamStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/** Canned HTTP: answers by `action=` (or path) without touching the network. */
fun fakeHttp(routes: Map<String, String>): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
    val url = chain.request().url.toString()
    val key = routes.keys.firstOrNull { it in url }
    val body = key?.let(routes::getValue)
    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(if (body == null) 404 else 200).message("x")
        .body((body ?: "").toResponseBody("application/json".toMediaType())).build()
}.build()

@RunWith(RobolectricTestRunner::class)
class ParsersTest {
    private val creds = Creds.Xtream("http://srv:8080/", "u", "p")

    @Test
    fun xtreamAccountStreamsAndUrls() {
        val http = fakeHttp(
            mapOf(
                "action=get_live_streams" to """[
                    {"num":1,"name":"TRT 1","stream_id":101,"stream_icon":"http://l/1.png","epg_channel_id":"trt1","category_id":"5","extra":{"a":[1,2]}},
                    {"num":"2","name":null,"stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":null,"tv_archive":true},
                    {"name":"no id"}, 7 ]""",
                "action=get_live_categories" to """[{"category_id":"5","category_name":"Ulusal","parent_id":0}]""",
                "player_api.php?username=u&password=p" to """{"user_info":{"auth":1,"status":"Active","exp_date":"1893456000",
                    "allowed_output_formats":["m3u8"]},"server_info":{"url":"srv"}}""",
            ),
        )
        val x = Xtream(http, creds)
        val acc = x.account()
        assertTrue(acc.ok)
        assertEquals(1893456000L, acc.expiry)
        assertEquals(listOf("m3u8"), x.formats) // account does not allow ts
        val cats = ArrayList<String>()
        x.categories(Kind.LIVE) { id, name -> cats += "$id=$name" }
        assertEquals(listOf("5=Ulusal"), cats)
        val got = ArrayList<XtreamStream>()
        x.streams(Kind.LIVE) { got += it }
        assertEquals(2, got.size)
        assertEquals("101", got[0].id)
        assertEquals("trt1", got[0].epg)
        assertEquals(2, got[1].num)
        assertEquals("102", got[1].name) // falls back to the id
        assertEquals(null, got[1].logo)
        assertEquals(listOf("http://srv:8080/live/u/p/101.m3u8", "http://alt/live/u/p/101.m3u8"), x.streamUrls(Kind.LIVE, "101", null, listOf("http://srv:8080/", "alt")))
        assertEquals(listOf("http://srv:8080/movie/u/p/9.mkv"), x.streamUrls(Kind.MOVIE, "9", "mkv", listOf("http://srv:8080")))
    }

    @Test
    fun xtreamRejectedLoginAndBrokenResponses() {
        val x = Xtream(fakeHttp(mapOf("player_api.php?username=u&password=p" to """{"user_info":{"auth":0}}""")), creds)
        assertFalse(x.account().ok)
        val html = Xtream(fakeHttp(mapOf("action=get_vod_streams" to "<html>Bad gateway</html>")), creds)
        try {
            html.streams(Kind.MOVIE) {}
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            // reported to the user as a failed refresh, never a crash
        }
        // an empty object instead of a list means "nothing"
        var n = 0
        Xtream(fakeHttp(mapOf("action=get_series" to "{}")), creds).streams(Kind.SERIES) { n++ }
        assertEquals(0, n)
    }

    @Test
    fun xtreamSeriesEpisodesAndShortEpg() {
        val title = android.util.Base64.encodeToString("Ana Haber".toByteArray(), android.util.Base64.NO_WRAP)
        val http = fakeHttp(
            mapOf(
                "series_id=7" to """{"info":{"plot":"Konu"},"episodes":{"2":[{"id":"22","episode_num":1,"title":"B","container_extension":"mkv"}],
                    "1":[{"id":"12","episode_num":"2","title":""},{"id":"11","episode_num":1,"title":"A"}]}}""",
                "series_id=8" to """{"episodes":[[{"id":"81","episode_num":1}],[{"id":"91","episode_num":1}]]}""",
                "action=get_short_epg" to """{"epg_listings":[{"title":"$title","description":"","start_timestamp":"100","stop_timestamp":"200"},
                    {"title":"","start_timestamp":"200","stop_timestamp":"300"}]}""",
            ),
        )
        val x = Xtream(http, creds)
        val s = x.seriesInfo("7")
        assertEquals("Konu", s.plot)
        assertEquals(listOf("11", "12", "22"), s.episodes.map { it.id })
        assertEquals("2. bölüm", s.episodes[1].title)
        assertEquals("mkv", s.episodes[2].cext)
        assertEquals(listOf(1, 2), x.seriesInfo("8").episodes.map { it.season })
        val epg = x.shortEpg("101", 4)
        assertEquals(1, epg.size)
        assertEquals("Ana Haber", epg[0].title)
    }

    @Test
    fun xmltvTimesAndFiltering() {
        assertEquals(0L, Xmltv.time("20260101000000 +0000") - 1767225600L)
        assertEquals(1767225600L - 3 * 3600, Xmltv.time("20260101000000 +0300"))
        assertEquals(1767225600L, Xmltv.time("20260101000000"))
        assertEquals(0L, Xmltv.time("2026-01-01"))
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <tv><channel id="trt1"><display-name>TRT 1</display-name></channel>
            <programme start="20260101000000 +0000" stop="20260101010000 +0000" channel="trt1"><title lang="tr">Haber &amp; Spor</title><desc>Gündem</desc></programme>
            <programme start="20260101010000 +0000" stop="20260101020000 +0000" channel="other"><title>X</title></programme>
            <programme start="20250101000000 +0000" stop="20250101010000 +0000" channel="trt1"><title>Eski</title></programme>
            <programme start="20260101020000 +0000" stop="20260101030000 +0000" channel="trt1"><title></title></programme>
            </tv>"""
        val out = ArrayList<String>()
        Xmltv.parse(xml.byteInputStream(), 1767225600L - 3600, 1767225600L + 86400, { it == "trt1" }) { ch, p -> out += "$ch|${p.title}|${p.desc}" }
        assertEquals(listOf("trt1|Haber & Spor|Gündem"), out)
    }
}
