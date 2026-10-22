package com.m2y.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import java.io.Closeable

/**
 * Framework SQLite (no Room: no annotation processor, no runtime library, compiled statements for bulk import).
 * WAL lets the UI read the published catalogue while an import writes the next generation.
 * Every call does disk I/O: use from an IO dispatcher only.
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "tv.db", null, 1) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = SCHEMA.forEach(db::execSQL)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- sources

    fun sources(): List<Source> = readableDatabase.rawQuery(
        "SELECT id,name,xtream,secret,epg_url,g0,g1,g2,updated,info FROM source ORDER BY id",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    Source(
                        c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getString(3), c.str(4),
                        longArrayOf(c.getLong(5), c.getLong(6), c.getLong(7)), c.getLong(8), c.str(9),
                    ),
                )
            }
        }
    }

    /** Inserts or edits a source; an edit clears `updated` so the catalogue is fetched again with the new details. */
    fun saveSource(id: Long?, name: String, xtream: Boolean, secret: String, epgUrl: String?): Long {
        val v = ContentValues().apply {
            put("name", name)
            put("xtream", if (xtream) 1 else 0)
            put("secret", secret)
            put("epg_url", epgUrl)
            put("updated", 0)
        }
        return if (id == null) writableDatabase.insertOrThrow("source", null, v) else id.also { writableDatabase.update("source", v, "id=?", arrayOf("$id")) }
    }

    fun setEpgUrl(id: Long, url: String) {
        writableDatabase.update("source", ContentValues().apply { put("epg_url", url) }, "id=? AND epg_url IS NULL", arrayOf("$id"))
    }

    fun deleteSource(id: Long) = tx { db ->
        for (t in listOf("item", "cat", "fav", "epg")) db.delete(t, "src=?", arrayOf("$id"))
        db.delete("source", "id=?", arrayOf("$id"))
        db.delete("kv", "k LIKE ? ESCAPE '\\' OR k IN (?,?)", arrayOf("cat\\_${id}\\_%", "last_$id", "fmt_$id"))
    }

    // ---- import

    /**
     * Writes one kind of one source under a new generation in short transactions, so a favourite toggled during a
     * long download never waits. Readers keep seeing the previous generation until [publish].
     * Not thread-safe; a transaction is open between calls, so use it from one thread without suspending.
     */
    inner class Writer(private val src: Long, val gen: Long) : Closeable {
        private val db = writableDatabase
        private val itemSt = db.compileStatement(ITEM_INSERT + ITEM_ROW)

        // one statement per ROWS rows: each statement execution carries fixed framework overhead
        private val blockSt = db.compileStatement(ITEM_INSERT + List(ROWS) { ITEM_ROW }.joinToString(","))
        private val nums = LongArray(ROWS * 5) // src, kind, gen, num, ord per row
        private val texts = arrayOfNulls<String>(ROWS * 9) // cat, ext, name, q, logo, cext, epg, rating, opts
        private var buffered = 0
        private val catSt = db.compileStatement("INSERT INTO cat(src,kind,gen,ext,name,ord) VALUES(?,?,?,?,?,?)")
        private var open = false
        private var cats = 0
        var count = 0
            private set

        fun cat(kind: Kind, ext: String, name: String) {
            begin()
            catSt.bindLong(1, src)
            catSt.bindLong(2, kind.ordinal.toLong())
            catSt.bindLong(3, gen)
            catSt.bindString(4, ext)
            catSt.bindString(5, name)
            catSt.bindLong(6, (cats++).toLong())
            catSt.executeInsert()
        }

        fun item(kind: Kind, cat: String?, ext: String, name: String, logo: String?, cext: String?, epg: String?, num: Int, rating: String?, opts: String?) {
            begin()
            val n = buffered * 5
            nums[n] = src
            nums[n + 1] = kind.ordinal.toLong()
            nums[n + 2] = gen
            nums[n + 3] = num.toLong()
            nums[n + 4] = count.toLong()
            val t = buffered * 9
            texts[t] = cat
            texts[t + 1] = ext
            texts[t + 2] = name
            texts[t + 3] = searchKey(name)
            texts[t + 4] = logo
            texts[t + 5] = cext
            texts[t + 6] = epg
            texts[t + 7] = rating
            texts[t + 8] = opts
            if (++buffered == ROWS) flushRows()
            if (++count % BATCH == 0) commit()
        }

        private fun flushRows() {
            if (buffered == ROWS) {
                for (r in 0 until ROWS) bindRow(blockSt, r, r * ITEM_COLS)
                blockSt.execute()
            } else {
                for (r in 0 until buffered) {
                    bindRow(itemSt, r, 0)
                    itemSt.executeInsert()
                }
            }
            buffered = 0
        }

        /** Binds buffered row [r] to parameters base+1..base+14 in ITEM_INSERT column order. */
        private fun bindRow(st: SQLiteStatement, r: Int, base: Int) {
            val n = r * 5
            val t = r * 9
            st.bindLong(base + 1, nums[n])
            st.bindLong(base + 2, nums[n + 1])
            st.bindLong(base + 3, nums[n + 2])
            for (i in 0 until 7) st.bindOpt(base + 4 + i, texts[t + i])
            st.bindLong(base + 11, nums[n + 3])
            st.bindLong(base + 12, nums[n + 4])
            st.bindOpt(base + 13, texts[t + 7])
            st.bindOpt(base + 14, texts[t + 8])
        }

        private fun begin() {
            if (!open) db.beginTransactionNonExclusive().also { open = true }
        }

        fun commit() {
            if (buffered > 0) flushRows()
            if (open) {
                db.setTransactionSuccessful()
                db.endTransaction()
                open = false
            }
        }

        /** Ends without committing the tail; rows of an abandoned generation are removed by the next [publish]. */
        override fun close() {
            if (open) db.endTransaction().also { open = false }
            itemSt.close()
            blockSt.close()
            catSt.close()
        }
    }

    /** Makes generation [gen] the visible catalogue of [kind] and drops every other generation (old or abandoned). */
    fun publish(src: Long, kind: Kind, gen: Long) = tx { db ->
        val a = arrayOf("$src", "${kind.ordinal}", "$gen")
        db.delete("item", "src=? AND kind=? AND gen<>?", a)
        db.delete("cat", "src=? AND kind=? AND gen<>?", a)
        db.update("source", ContentValues().apply { put("g${kind.ordinal}", gen) }, "id=?", arrayOf("$src"))
    }

    fun finishImport(src: Long, info: String) {
        writableDatabase.update(
            "source",
            ContentValues().apply {
                put("updated", System.currentTimeMillis())
                put("info", info)
            },
            "id=?",
            arrayOf("$src"),
        )
    }

    // ---- catalogue

    fun cats(src: Source, kind: Kind): List<Cat> = readableDatabase.rawQuery(
        "SELECT ext,name FROM cat WHERE src=? AND kind=? AND gen=? ORDER BY ord",
        arrayOf("${src.id}", "${kind.ordinal}", "${src.gen(kind)}"),
    ).use { c -> buildList { while (c.moveToNext()) add(Cat(c.getString(0), c.getString(1))) } }

    /** One page of a category (or of everything when [cat] is null), keyset-paged on `ord`. */
    fun items(src: Source, kind: Kind, cat: String?, afterOrd: Int, limit: Int): List<Item> {
        val args = mutableListOf("${src.id}", "${kind.ordinal}", "${src.gen(kind)}")
        if (cat != null) args += cat
        args += "$afterOrd"
        args += "$limit"
        return readableDatabase.rawQuery(
            "SELECT $COLS FROM item WHERE src=? AND kind=? AND gen=?" + (if (cat != null) " AND cat=?" else "") +
                " AND ord>? ORDER BY ord LIMIT ?",
            args.toTypedArray(),
        ).use { it.items() }
    }

    fun favItems(src: Source, kind: Kind): List<Item> = readableDatabase.rawQuery(
        "SELECT $COLS FROM item i WHERE src=? AND kind=? AND gen=? AND EXISTS" +
            "(SELECT 1 FROM fav f WHERE f.src=i.src AND f.kind=i.kind AND f.ext=i.ext) ORDER BY ord",
        arrayOf("${src.id}", "${kind.ordinal}", "${src.gen(kind)}"),
    ).use { it.items() }

    fun search(src: Source, kind: Kind, query: String, limit: Int): List<Item> = readableDatabase.rawQuery(
        "SELECT $COLS FROM item WHERE src=? AND kind=? AND gen=? AND q LIKE ? ESCAPE '\\' ORDER BY ord LIMIT ?",
        arrayOf("${src.id}", "${kind.ordinal}", "${src.gen(kind)}", likePattern(query), "$limit"),
    ).use { it.items() }

    /** One item by its key (restoring the last channel); a scan of one kind, used once per start. */
    fun find(src: Source, kind: Kind, ext: String): Item? = readableDatabase.rawQuery(
        "SELECT $COLS FROM item WHERE src=? AND kind=? AND gen=? AND ext=? LIMIT 1",
        arrayOf("${src.id}", "${kind.ordinal}", "${src.gen(kind)}", ext),
    ).use { it.items().firstOrNull() }

    fun favKeys(src: Long, kind: Kind): HashSet<String> = readableDatabase.rawQuery(
        "SELECT ext FROM fav WHERE src=? AND kind=?",
        arrayOf("$src", "${kind.ordinal}"),
    ).use { c -> HashSet<String>().also { while (c.moveToNext()) it += c.getString(0) } }

    fun setFav(src: Long, kind: Kind, ext: String, on: Boolean) {
        val a = arrayOf("$src", "${kind.ordinal}", ext)
        if (on) {
            writableDatabase.execSQL("INSERT OR IGNORE INTO fav(src,kind,ext,at) VALUES(?,?,?,${System.currentTimeMillis()})", a)
        } else {
            writableDatabase.delete("fav", "src=? AND kind=? AND ext=?", a)
        }
    }

    // ---- EPG (XMLTV, M3U sources)

    fun epgIds(src: Source): HashSet<String> = readableDatabase.rawQuery(
        "SELECT DISTINCT epg FROM item WHERE src=? AND kind=0 AND gen=? AND epg IS NOT NULL",
        arrayOf("${src.id}", "${src.gen(Kind.LIVE)}"),
    ).use { c -> HashSet<String>().also { while (c.moveToNext()) it += c.getString(0) } }

    fun epg(src: Long, ch: String, fromSec: Long, limit: Int): List<Programme> = readableDatabase.rawQuery(
        "SELECT start,stop,title,descr FROM epg WHERE src=? AND ch=? AND stop>? ORDER BY start LIMIT ?",
        arrayOf("$src", ch, "$fromSec", "$limit"),
    ).use { c -> buildList { while (c.moveToNext()) add(Programme(c.getLong(0), c.getLong(1), c.getString(2), c.str(3))) } }

    /** Replaces the guide of [src] by whatever [fill] emits, in batched transactions. Same threading rule as [Writer]. */
    fun replaceEpg(src: Long, fill: ((String, Programme) -> Unit) -> Unit): Int {
        val db = writableDatabase
        db.delete("epg", "src=?", arrayOf("$src"))
        val st = db.compileStatement("INSERT INTO epg(src,ch,start,stop,title,descr) VALUES(?,?,?,?,?,?)")
        var n = 0
        db.beginTransactionNonExclusive()
        try {
            fill { ch, p ->
                st.bindLong(1, src)
                st.bindString(2, ch)
                st.bindLong(3, p.start)
                st.bindLong(4, p.stop)
                st.bindString(5, p.title)
                st.bindOpt(6, p.desc)
                st.executeInsert()
                if (++n % BATCH == 0) {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    db.beginTransactionNonExclusive()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            st.close()
        }
        return n
    }

    // ---- small key/value state (active source, tab, last channel)

    fun get(k: String): String? = readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use { if (it.moveToFirst()) it.str(0) else null }

    fun put(k: String, v: String?) {
        if (v == null) writableDatabase.delete("kv", "k=?", arrayOf(k)) else writableDatabase.execSQL("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)", arrayOf(k, v))
    }

    private inline fun tx(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            block(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private companion object {
        const val BATCH = 1000
        const val ROWS = 50 // 50 rows × 14 columns stays under SQLite's 999 bound-variable limit
        const val ITEM_COLS = 14
        const val ITEM_INSERT = "INSERT INTO item(src,kind,gen,cat,ext,name,q,logo,cext,epg,num,ord,rating,opts) VALUES"
        const val ITEM_ROW = "(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        const val COLS = "id,kind,cat,ext,name,logo,cext,epg,num,ord,rating,opts"

        val SCHEMA = listOf(
            // AUTOINCREMENT: a deleted source's id (and any rows an interrupted import left behind) is never reused
            "CREATE TABLE source(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, xtream INTEGER NOT NULL, secret TEXT NOT NULL, " +
                "epg_url TEXT, g0 INTEGER NOT NULL DEFAULT 0, g1 INTEGER NOT NULL DEFAULT 0, g2 INTEGER NOT NULL DEFAULT 0, " +
                "updated INTEGER NOT NULL DEFAULT 0, info TEXT)",
            "CREATE TABLE cat(src INTEGER NOT NULL, kind INTEGER NOT NULL, gen INTEGER NOT NULL, ext TEXT NOT NULL, name TEXT NOT NULL, ord INTEGER NOT NULL)",
            "CREATE INDEX cat_i ON cat(src,kind,gen,ord)",
            // only the indexes the list queries need: each extra one slows every import
            "CREATE TABLE item(id INTEGER PRIMARY KEY, src INTEGER NOT NULL, kind INTEGER NOT NULL, gen INTEGER NOT NULL, cat TEXT, " +
                "ext TEXT NOT NULL, name TEXT NOT NULL, q TEXT NOT NULL, logo TEXT, cext TEXT, epg TEXT, num INTEGER NOT NULL, " +
                "ord INTEGER NOT NULL, rating TEXT, opts TEXT)",
            "CREATE INDEX item_cat ON item(src,kind,gen,cat,ord)",
            "CREATE INDEX item_ord ON item(src,kind,gen,ord)",
            "CREATE TABLE fav(src INTEGER NOT NULL, kind INTEGER NOT NULL, ext TEXT NOT NULL, at INTEGER NOT NULL, " +
                "PRIMARY KEY(src,kind,ext)) WITHOUT ROWID",
            "CREATE TABLE epg(src INTEGER NOT NULL, ch TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL, descr TEXT)",
            "CREATE INDEX epg_i ON epg(src,ch,stop)",
            "CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT) WITHOUT ROWID",
        )

        fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

        fun SQLiteStatement.bindOpt(i: Int, s: String?) = if (s == null) bindNull(i) else bindString(i, s)

        fun Cursor.items(): List<Item> = buildList {
            val kinds = Kind.entries
            while (moveToNext()) {
                add(
                    Item(
                        getLong(0), kinds[getInt(1)], str(2), getString(3), getString(4), str(5), str(6), str(7),
                        getInt(8), getInt(9), str(10), str(11),
                    ),
                )
            }
        }
    }
}
