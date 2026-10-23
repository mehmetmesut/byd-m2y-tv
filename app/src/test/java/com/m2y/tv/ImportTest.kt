package com.m2y.tv

import org.robolectric.RuntimeEnvironment
import com.m2y.tv.data.Cat
import com.m2y.tv.data.Creds
import com.m2y.tv.data.Db
import com.m2y.tv.data.Importer
import com.m2y.tv.data.Kind
import com.m2y.tv.data.Source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real SQLite (Robolectric) + canned HTTP: the whole refresh path without network. */
@RunWith(RobolectricTestRunner::class)
class ImportTest {
    private lateinit var db: Db

    @Before
    fun setUp() {
        db = Db(RuntimeEnvironment.getApplication())
    }

    private fun source(id: Long) = db.sources().first { it.id == id }

    private val playlist = """
        #EXTM3U x-tvg-url="http://h/epg.xml"
        #EXTINF:-1 tvg-id="trt1" group-title="Ulusal",TRT 1
        http://h/live/ali/s3cr/1.ts
        #EXTINF:-1 tvg-id="show" group-title="Ulusal",Show TV
        http://h/live/ali/s3cr/2.ts
        #EXTINF:-1 group-title="Spor",Şampiyonlar Ligi Özet
        http://h/live/ali/s3cr/3.ts
        #EXTINF:-1 group-title="Filmler",Kış Uykusu
        http://h/movie/ali/s3cr/10.mkv
        #EXTINF:-1 group-title="Dizi",Diriliş S01E01
        http://h/series/ali/s3cr/20.mp4
    """.trimIndent()

    private val now = System.currentTimeMillis() / 1000

    private fun xmltvAt(sec: Long): String {
        val f = java.text.SimpleDateFormat("yyyyMMddHHmmss Z", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        fun t(s: Long) = f.format(java.util.Date(s * 1000))
        return """<tv><programme start="${t(sec - 600)}" stop="${t(sec + 600)}" channel="trt1"><title>Ana Haber</title></programme>
            <programme start="${t(sec - 600)}" stop="${t(sec + 600)}" channel="yok"><title>X</title></programme></tv>"""
    }

    @Test
    fun m3uImportMasksCredentialsSplitsKindsAndLoadsGuide() = runBlocking {
        val url = "http://h/get.php?username=ali&password=s3cr&type=m3u_plus"
        val id = db.saveSource(null, "Test", false, "sealed", null)
        val importer = Importer(db, fakeHttp(mapOf("get.php" to playlist, "epg.xml" to xmltvAt(now))))
        val published = ArrayList<Kind>()
        val r = importer.run(source(id), Creds.M3u(url), {}) { published += it }
        assertEquals(listOf(3, 1, 1), r.counts.toList())
        assertEquals(Kind.entries, published)
        assertEquals(1, r.epg)
        val src = source(id)
        assertEquals("http://h/epg.xml", src.epgUrl)
        assertEquals(listOf("Ulusal", "Spor"), db.cats(src, Kind.LIVE).map { it.name })
        val live = db.items(src, Kind.LIVE, Cat.ALL, -1, 100)
        assertEquals(listOf("TRT 1", "Show TV", "Şampiyonlar Ligi Özet"), live.map { it.name })
        assertTrue(live.none { "ali" in it.ext || "s3cr" in it.ext }) // no credentials in the catalogue
        assertEquals(1, db.items(src, Kind.LIVE, "Spor", -1, 100).size)
        assertEquals("Şampiyonlar Ligi Özet", db.search(src, Kind.LIVE, "sampiyon", 10).single().name)
        assertEquals("Ana Haber", db.epg(id, "trt1", now, 5).single().title)
        assertTrue(db.epg(id, "yok", now, 5).isEmpty()) // guide filtered to the playlist's channels
    }

    @Test
    fun refreshSwapsGenerationsAndKeepsFavourites() = runBlocking {
        val id = db.saveSource(null, "Test", false, "sealed", null)
        val first = Importer(db, fakeHttp(mapOf("get.php" to playlist)))
        first.run(source(id), Creds.M3u("http://h/get.php?username=ali&password=s3cr"), {}) {}
        val src1 = source(id)
        val trt = db.items(src1, Kind.LIVE, Cat.ALL, -1, 10).first()
        db.setFav(id, Kind.LIVE, trt.ext, true)

        // second refresh: one channel dropped, one added
        val next = playlist.replace("Show TV", "Kanal D").replace("/2.ts", "/4.ts")
        val w = db.Writer(id, 1) // an abandoned partial import of an older run
        w.item(Kind.LIVE, "Ulusal", "junk", "Junk", null, null, null, 0, null, null)
        w.commit()
        w.close()
        Importer(db, fakeHttp(mapOf("get.php" to next))).run(src1, Creds.M3u("http://h/get.php?username=ali&password=s3cr"), {}) {}
        val src2 = source(id)
        assertTrue(src2.gen(Kind.LIVE) > src1.gen(Kind.LIVE))
        assertEquals(listOf("TRT 1", "Kanal D", "Şampiyonlar Ligi Özet"), db.items(src2, Kind.LIVE, Cat.ALL, -1, 10).map { it.name })
        assertTrue(db.items(src1, Kind.LIVE, Cat.ALL, -1, 10).isEmpty()) // old generation and the junk are gone
        assertEquals(listOf("TRT 1"), db.items(src2, Kind.LIVE, Cat.FAV, -1, 10).map { it.name })

        // provider answers 200 with an HTML page: refresh fails, catalogue stays
        try {
            Importer(db, fakeHttp(mapOf("get.php" to "<html>bakım</html>"))).run(src2, Creds.M3u("http://h/get.php"), {}) {}
            throw AssertionError("expected IOException")
        } catch (e: java.io.IOException) {
            assertEquals(3, db.items(source(id), Kind.LIVE, Cat.ALL, -1, 10).size)
        }

        db.deleteSource(id)
        assertTrue(db.sources().none { it.id == id })
        assertTrue(db.favKeys(id, Kind.LIVE).isEmpty())
    }

    @Test
    fun pagingIsKeysetOnOrder() {
        val id = db.saveSource(null, "Big", true, "sealed", null)
        db.Writer(id, 5).use { w ->
            w.cat(Kind.MOVIE, "1", "Aksiyon")
            repeat(1234) { w.item(Kind.MOVIE, "1", "$it", "Film $it", null, "mp4", null, 0, null, null) }
            w.commit()
        }
        db.publish(id, Kind.MOVIE, 5)
        val src: Source = source(id)
        var after = -1
        var total = 0
        while (true) {
            val page = db.items(src, Kind.MOVIE, "1", after, 500)
            if (page.isEmpty()) break
            total += page.size
            after = page.last().ord
        }
        assertEquals(1234, total)
        assertEquals("Film 1233", db.find(src, Kind.MOVIE, "1233")?.name)
    }
}
