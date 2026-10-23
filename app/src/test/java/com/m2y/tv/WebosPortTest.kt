package com.m2y.tv

import com.m2y.tv.data.Cat
import com.m2y.tv.data.Creds
import com.m2y.tv.data.Db
import com.m2y.tv.data.Entry
import com.m2y.tv.data.Kind
import com.m2y.tv.data.PairServer
import com.m2y.tv.data.entryCreds
import com.m2y.tv.data.liveFormats
import com.m2y.tv.data.m3uCandidates
import com.m2y.tv.data.parseSmartLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Behaviour carried over from the webOS app: source entry, stream candidates, pairing page, list management. */
@RunWith(RobolectricTestRunner::class)
class WebosPortTest {
    @Test
    fun smartLinkWithLoginIsXtreamOtherwiseM3u() {
        val x = parseSmartLink("http://srv.tv:8080/get.php?username=ali%40x&password=p+1&type=m3u_plus")
        assertEquals("http://srv.tv:8080", x?.server)
        assertEquals("ali@x", x?.user)
        assertEquals("p 1", x?.pass)
        assertNull(parseSmartLink("http://h/list.m3u"))
        assertNull(parseSmartLink("http://h/get.php?username=ali"))
        assertTrue(entryCreds(Entry(true, "", "", "", "", "", "http://h/list.m3u")).first is Creds.M3u)
        assertTrue(entryCreds(Entry(true, "", "", "", "", "", "http://h/get.php?username=a&password=b")).first is Creds.Xtream)
        assertEquals("Geçerli bir Akıllı Bağlantı (http:// ya da https://) girin.", entryCreds(Entry(true, "", "", "", "", "", "h/x")).second)
        assertEquals("Kullanıcı adı ve şifreyi girin.", entryCreds(Entry(false, "", "u", "", "srv", "", "")).second)
        val server = entryCreds(Entry(false, "", " u ", "p", "srv:8080", "alt:80", "")).first as Creds.Xtream
        assertEquals("http://srv:8080", server.server)
        assertEquals("u", server.user)
        assertEquals("http://alt:80", server.alt)
    }

    @Test
    fun tsFirstThenItsHlsTwin() {
        assertEquals(listOf("http://h/live/u/p/1.ts", "http://h/live/u/p/1.m3u8"), m3uCandidates("http://h/live/u/p/1.ts"))
        assertEquals(listOf("http://h:80/u/p/12", "http://h:80/live/u/p/12.m3u8"), m3uCandidates("http://h:80/u/p/12"))
        assertEquals(listOf("http://h/hls/index.m3u8"), m3uCandidates("http://h/hls/index.m3u8"))
        assertEquals(listOf("ts", "m3u8"), liveFormats(emptyList()))
        assertEquals(listOf("ts", "m3u8"), liveFormats(listOf("m3u8", "ts", "rtmp")))
        assertEquals(listOf("m3u8"), liveFormats(listOf("m3u8")))
    }

    @Test
    fun serverThatWorkedLastComesFirstAndAltSurvivesSealing() {
        val c = Creds.Xtream("http://a", "u", "p", "http://b")
        assertEquals(listOf("http://a", "http://b"), c.servers())
        assertEquals(listOf("http://b", "http://a"), c.servers("http://b"))
        assertEquals(listOf("http://a", "http://b"), c.servers("http://gone"))
        assertEquals("http://b", (Creds.decode(c.encode()) as Creds.Xtream).alt)
        assertNull((Creds.decode("x\nhttp://a\nu\np") as Creds.Xtream).alt) // sources saved by 1.25.07.68.0
    }

    @Test
    fun pairingFormParsesAndEscapes() {
        val f = PairServer.parseForm("mode=server&name=Ev&user=ali&pass=a%26b%3Dc&server=srv%3A8080&alt=")
        val e = PairServer.formEntry(f)
        assertFalse(e.smart)
        assertEquals("a&b=c", e.pass)
        assertEquals("srv:8080", e.server)
        val head = PairServer.readHead("POST /abc HTTP/1.1\r\nHost: x\r\nContent-Length: 12\r\n\r\nbody".byteInputStream())
        assertEquals("POST /abc HTTP/1.1", head?.first)
        assertEquals("12", head?.second?.get("content-length"))
        assertNull(PairServer.readHead("GET / HTTP/1.1".byteInputStream())) // never finished
        assertEquals("&lt;b&gt; &quot;x&quot; &#39;", PairServer.esc("<b> \"x\" '"))
        val page = PairServer.pairPage("/t", "Hata <x>", mapOf("user" to "\"><script>", "pass" to "secret"))
        assertTrue("&quot;&gt;&lt;script&gt;" in page)
        assertFalse("secret" in page) // the password is never sent back
        assertTrue("Hata &lt;x&gt;" in page)
    }

    @Test
    fun pairingPageOpensOnSmartLink() {
        val blank = PairServer.pairPage("/t", null, emptyMap())
        val smart = blank.indexOf("value=\"smart\" checked")
        assertTrue(smart >= 0 && smart < blank.indexOf("value=\"server\"")) // first tab and selected
        assertTrue("value=\"server\" checked" in PairServer.pairPage("/t", "x", mapOf("mode" to "server"))) // an error keeps the tab
    }

    @Test
    fun hiddenRecentAndGroupLists() {
        val db = Db(RuntimeEnvironment.getApplication())
        val id = db.saveSource(null, "T", true, "sealed", null)
        db.Writer(id, 1).use { w ->
            w.cat(Kind.LIVE, "5", "Ulusal")
            w.cat(Kind.LIVE, "6", "Spor")
            for ((i, c) in listOf("5", "5", "6").withIndex()) w.item(Kind.LIVE, c, "${i + 1}", "K${i + 1}", null, null, null, i + 1, null, null)
            w.commit()
        }
        db.publish(id, Kind.LIVE, 1)
        val src = db.sources().first { it.id == id }
        fun names(key: String) = db.items(src, Kind.LIVE, key, -1, 10).map { it.name }
        assertEquals(listOf("K1", "K2", "K3"), names(Cat.ALL))

        db.hide(id, Kind.LIVE, listOf("2"), isCat = false)
        db.hide(id, Kind.LIVE, listOf("6"), isCat = true)
        assertEquals(listOf("K1"), names(Cat.ALL))
        assertEquals(listOf("5"), db.cats(src, Kind.LIVE).map { it.key })
        assertEquals(2, db.unhideAll(id))
        assertEquals(3, names(Cat.ALL).size)

        db.addRecent(id, Kind.LIVE, "1")
        Thread.sleep(2)
        db.addRecent(id, Kind.LIVE, "3")
        assertEquals(listOf("K3", "K1"), names(Cat.REC))
        assertTrue(db.items(src, Kind.LIVE, Cat.REC, 0, 10).isEmpty()) // whole lists have no second page

        val g = db.addGroup(id, Kind.LIVE, "Haber")
        db.setInGroup(g, "3", true)
        Thread.sleep(2)
        db.setInGroup(g, "1", true)
        assertEquals(listOf("K3", "K1"), names(Cat.group(g)))
        assertEquals(g, Cat.groupId(Cat.group(g)))
        assertNull(Cat.groupId(Cat.FAV))
        db.setHealth(id, "1", false)
        assertEquals(false, db.health(id)["1"])
        db.setHealth(id, "3", true)
        assertEquals(listOf("K1"), names(Cat.BAD)) // only the failed ones gather in Pasif Yayınlar
        db.hide(id, Kind.LIVE, listOf("1"), isCat = false)
        assertTrue(names(Cat.BAD).isEmpty()) // deleted there = gone from every package
        assertTrue(Cat.whole(Cat.BAD) && Cat.whole(Cat.DL) && !Cat.whole(Cat.ALL))

        db.deleteSource(id)
        assertTrue(db.groups(id, Kind.LIVE).isEmpty())
        assertTrue(db.health(id).isEmpty())
    }
}
