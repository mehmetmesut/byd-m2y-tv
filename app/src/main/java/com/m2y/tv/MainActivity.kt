package com.m2y.tv

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.database.SQLException
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.m2y.tv.data.Cat
import com.m2y.tv.data.Creds
import com.m2y.tv.data.Episode
import com.m2y.tv.data.Guide
import com.m2y.tv.data.Importer
import com.m2y.tv.data.Item
import com.m2y.tv.data.Kind
import com.m2y.tv.data.Programme
import com.m2y.tv.data.Secrets
import com.m2y.tv.data.Source
import com.m2y.tv.data.UrlMask
import com.m2y.tv.data.Xtream
import com.m2y.tv.data.liveFormats
import com.m2y.tv.data.m3uCandidates
import com.m2y.tv.play.Media
import com.m2y.tv.play.Playback
import com.m2y.tv.ui.Downloads
import com.m2y.tv.ui.ItemAdapter
import com.m2y.tv.ui.Library
import com.m2y.tv.ui.Row
import com.m2y.tv.ui.SettingsPanel
import com.m2y.tv.ui.TextAdapter
import com.m2y.tv.ui.VideoBox
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Single screen: tabs + search on top, categories | list | preview & details below. The player, the full-screen
 * view and the settings panel live in the same window, so nothing is rebuilt when switching between them.
 */
class MainActivity : Activity(), Playback.Listener, VideoBox.Callbacks, SettingsPanel.Callbacks, Library.Host {
    private val debuggable by lazy { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    private val ui = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "ui", e) // never crash the car screen over one failed load
            status.text = "Hata: ${e.message}"
        },
    )
    private val handler = Handler(Looper.getMainLooper())
    private val db get() = Graph.db

    private lateinit var playback: Playback
    private lateinit var video: VideoBox
    private lateinit var settings: SettingsPanel
    private lateinit var library: Library
    private lateinit var downloads: Downloads
    private lateinit var cats: TextAdapter
    private lateinit var items: ItemAdapter
    private lateinit var sideRows: TextAdapter
    private lateinit var tabs: Map<Kind, TextView>
    private lateinit var status: TextView
    private lateinit var empty: TextView
    private lateinit var search: EditText
    private lateinit var itemsView: RecyclerView
    private lateinit var title: TextView
    private lateinit var sub: TextView
    private lateinit var desc: TextView
    private lateinit var poster: ImageView
    private lateinit var actions: View
    private lateinit var favBtn: TextView
    private lateinit var groupBtn: TextView
    private lateinit var removeBtn: View
    private lateinit var playBtn: TextView

    private var sources: List<Source> = emptyList()
    override var source: Source? = null
    private var xtream: Xtream? = null
    private var servers: List<String> = emptyList() // Xtream servers, the one that answered last first
    private var solo = true // the account allows one stream at a time (M3U and silent panels count as one)
    private var mask: UrlMask? = null
    override var tab = Kind.LIVE
    private var catKey: String = Cat.ALL
    private var favs: Set<String> = emptySet()
    private var selected: Item? = null
    private var playing: Item? = null
    private var query = ""
    private var endReached = false
    private var listJob: Job? = null
    private var pageJob: Job? = null
    private var searchJob: Job? = null
    private var sideJob: Job? = null
    private var importJob: Job? = null
    private var zapTo = -1
    private val guide = Guide(ui) { src, ok ->
        if (!ok) status.text = "Program rehberi alınamadı"
        selected?.takeIf { it.kind == Kind.LIVE && source?.id == src.id }?.let(::showSide)
    }
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) {
            handler.post { playback.retryIfFailed() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        if (debuggable) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build())
        }
        setContentView(R.layout.activity_main)
        bindViews()
        playback = Playback(this, Graph.http, this) { Graph.offline.vod(it) }
        video = VideoBox(this, { playback }, this)
        playback.attach(video.surface)
        settings = SettingsPanel(this, ui, this)
        library = Library(this, ui, this)
        downloads = Downloads(this, ui, this, ::syncDownloads)
        ui.launch { boot() }
    }

    private fun bindViews() {
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)
        search = findViewById(R.id.search)
        title = findViewById(R.id.title)
        sub = findViewById(R.id.sub)
        desc = findViewById(R.id.desc)
        poster = findViewById(R.id.poster)
        actions = findViewById(R.id.actions)
        favBtn = findViewById(R.id.fav)
        groupBtn = findViewById(R.id.group)
        removeBtn = findViewById(R.id.remove)
        playBtn = findViewById(R.id.play)
        tabs = mapOf(Kind.LIVE to findViewById(R.id.tabLive), Kind.MOVIE to findViewById(R.id.tabMovie), Kind.SERIES to findViewById(R.id.tabSeries))
        tabs.forEach { (k, v) -> v.setOnClickListener { if (k != tab || query.isNotEmpty()) showTab(k) } }

        cats = TextAdapter({ r -> onCategory(r.tag as String) }) { r -> library.railMenu(r.tag as String, r.text.toString()) }
        findViewById<RecyclerView>(R.id.cats).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = cats
            itemAnimator = null
        }
        items = ItemAdapter(ui, Graph.images, ::onItem, ::toggleFav, ::loadMore) { library.light(it) }
        itemsView = findViewById<RecyclerView>(R.id.items).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = items
            itemAnimator = null
            setHasFixedSize(true)
        }
        sideRows = TextAdapter({ r -> (r.tag as? Episode)?.let(::playEpisode) }) { r -> (r.tag as? Episode)?.let { selected?.let { s -> downloads.episodeMenu(s, it) } } }
        findViewById<RecyclerView>(R.id.sideList).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = sideRows
            itemAnimator = null
        }
        findViewById<View>(R.id.settings).setOnClickListener { openSettings() }
        favBtn.setOnClickListener { selected?.let(::toggleFav) }
        groupBtn.setOnClickListener { selected?.let { library.groupAction(it, if (query.isEmpty()) catKey else Cat.ALL) } }
        removeBtn.setOnClickListener { selected?.let(library::deleteItem) }
        findViewById<View>(R.id.about).setOnClickListener { library.about() }
        playBtn.setOnClickListener { selected?.let { play(it, full = true) } }
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable) = onQuery(s.toString().trim())
            },
        )
        search.setOnEditorActionListener { v, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEARCH) hideKeyboard(v)
            id == EditorInfo.IME_ACTION_SEARCH
        }
    }

    // ---- lifecycle

    override fun onStart() {
        super.onStart()
        playback.resume()
        getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(network)
    }

    override fun onStop() {
        library.stop() // no probes while nobody looks
        playback.release() // frees the hardware decoder and buffers while another app is in front
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(network) }
        super.onStop()
    }

    override fun onDestroy() {
        ui.cancel()
        handler.removeCallbacksAndMessages(null)
        video.release()
        settings.release()
        playback.clear()
        super.onDestroy()
    }

    @Deprecated("Android 10 back handling")
    override fun onBackPressed() {
        when {
            video.fullscreen -> video.setFullscreen(false)
            settings.isOpen -> onClose()
            query.isNotEmpty() -> search.setText("")
            else -> moveTaskToBack(true) // keep state for a fast return
        }
    }

    // ---- sources

    private suspend fun boot() {
        val saved = io { Triple(db.sources(), db.get(K_SOURCE)?.toLongOrNull(), db.get(K_TAB)?.toIntOrNull()) }
        playback.setMaxHeight(io { db.get(K_MAX_QUALITY) }?.toIntOrNull() ?: 0)
        sources = saved.first
        tab = Kind.entries.getOrElse(saved.third ?: 0) { Kind.LIVE }
        val src = sources.firstOrNull { it.id == saved.second } ?: sources.firstOrNull()
        if (src == null) {
            empty.setText(R.string.no_source)
            empty.visibility = View.VISIBLE
            openSettings()
        } else {
            activate(src)
            delay(AUTO_REFRESH_DELAY_MS) // unfinished downloads go on, once the first channel has started
            downloads.resumePending()
        }
    }

    private suspend fun activate(src: Source) {
        if (source?.id != src.id) {
            playback.clear()
            video.hide()
            playing = null
            selected = null
            showSide(null)
        }
        source = src
        val creds = io { runCatching { Secrets.open(src.secret) }.getOrNull() }
        if (creds == null) {
            status.text = "Kayıtlı bilgiler çözülemedi; Ayarlar'dan listeyi düzenleyin."
            return
        }
        account(src, creds)
        library.load(src)
        mask = (creds as? Creds.M3u)?.let { UrlMask.forPlaylist(it.url) }
        io { db.put(K_SOURCE, src.id.toString()) }
        showTab(tab)
        when {
            src.updated == 0L -> refresh(src)
            System.currentTimeMillis() - src.updated > STALE_MS -> ui.launch {
                delay(AUTO_REFRESH_DELAY_MS) // let the first channel start before downloading lists
                refresh(src)
            }
        }
    }

    /** Xtream details the last import learnt: allowed live outputs, the server that answered, max_connections. */
    private suspend fun account(src: Source, creds: Creds) {
        val (fmt, srv, conns) = io { Triple(db.get(Importer.liveExtKey(src.id)), db.get(Importer.serverKey(src.id)), db.get(Importer.connsKey(src.id))) }
        val x = creds as? Creds.Xtream
        servers = x?.servers(srv).orEmpty()
        xtream = x?.let { Xtream(Graph.http, it, servers.first()).apply { formats = liveFormats(fmt?.split(',').orEmpty()) } }
        solo = (conns?.toIntOrNull() ?: 0) < 2
    }

    private fun refresh(src: Source) {
        if (importJob?.isActive == true) {
            status.text = "Güncelleme sürüyor…"
            return
        }
        importJob = ui.launch {
            status.text = "Güncelleniyor…"
            try {
                val r = withContext(Dispatchers.IO) {
                    Graph.importer.run(src, Secrets.open(src.secret), { s -> status.post { status.text = s } }) { k -> ui.launch { onPublished(src.id, k) } }
                }
                if (src.id == source?.id) io { runCatching { Secrets.open(src.secret) }.getOrNull() }?.let { account(src, it) }
                status.text = Importer.summary(r.counts) +
                    (if (r.failed.isEmpty()) "" else " · alınamadı: " + r.failed.joinToString { Importer.LABELS[it.ordinal] }) +
                    (if (r.epg < 0) " · rehber alınamadı" else "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                status.text = "Güncellenemedi: ${e.message ?: "bağlantı hatası"}"
            } catch (e: GeneralSecurityException) {
                status.text = "Kayıtlı bilgiler çözülemedi"
            } catch (e: SQLException) {
                status.text = "Kayıt hatası: ${e.message}"
            }
            reloadSources()
        }
    }

    private suspend fun onPublished(srcId: Long, kind: Kind) {
        reloadSources()
        if (srcId == source?.id && kind == tab && query.isEmpty()) showTab(tab)
    }

    private suspend fun reloadSources() {
        sources = io { db.sources() }
        source = sources.firstOrNull { it.id == source?.id }
        if (settings.isOpen) settings.showSources(sources, source?.id)
    }

    // ---- tabs, categories, lists

    /**
     * A preview belongs to its tab: leaving the tab stops it (frees decoder, network and the side pane), and
     * Live TV reopens the last channel.
     */
    private fun showTab(k: Kind) {
        tab = k
        tabs.forEach { (kind, v) -> v.isActivated = kind == k }
        clearSearch()
        if (playing != null && playing?.kind != k && !video.fullscreen) {
            playback.clear()
            video.hide()
            playing = null
        }
        val src = source ?: return
        listJob?.cancel()
        listJob = ui.launch {
            val (list, keys, saved) = io { Triple(db.cats(src, k), db.favKeys(src.id, k), db.get(catKey(src.id, k))) }
            val groups = io { db.groups(src.id, k) }
            favs = keys
            // under Favoriler in every playlist: Pasif Yayınlar (live) and Çevrimdışı (downloads, every tab)
            val all = listOf(Cat(Cat.FAV, getString(R.string.favorites))) +
                listOfNotNull(Cat(Cat.BAD, getString(R.string.bad)).takeIf { k == Kind.LIVE }) +
                listOf(Cat(Cat.DL, getString(R.string.downloads)), Cat(Cat.REC, getString(R.string.recent)), Cat(Cat.ALL, getString(R.string.all))) +
                groups.map { Cat(Cat.group(it.id), "▸ ${it.name}") } + list
            // an empty favourites list is a poor landing page
            catKey = all.firstOrNull { it.key == saved && !(it.key == Cat.FAV && keys.isEmpty()) }?.key ?: Cat.ALL
            cats.submit(TextAdapter.cats(all, catKey))
            io { db.put(K_TAB, k.ordinal.toString()) }
            loadItems()
            if (selected?.kind != k) showSide(playing?.takeIf { it.kind == k })
            if (k == Kind.LIVE) restoreLast(src)
        }
    }

    private fun onCategory(key: String) {
        catKey = key
        cats.activate(key)
        clearSearch()
        val src = source?.id ?: return
        ui.launch { io { db.put(catKey(src, tab), key) } }
        ui.launch { loadItems() }
    }

    private suspend fun loadItems() {
        pageJob?.cancel()
        val src = source ?: return
        val k = tab
        val key = catKey
        downloads.listShown = key == Cat.DL
        val list = if (key == Cat.DL) downloads.items() else io { db.items(src, k, key, -1, PAGE) }
        if (k != tab || key != catKey) return
        endReached = list.size < PAGE || Cat.whole(key)
        items.submit(k, list, favs)
        items.setCurrent((playing?.takeIf { it.kind == k } ?: selected)?.ext)
        itemsView.scrollToPosition(maxOf(0, items.indexOf(playing?.ext)))
        showEmpty(
            list.isEmpty(),
            when {
                key == Cat.FAV -> R.string.empty_fav
                key == Cat.REC -> R.string.empty_recent
                key == Cat.DL -> R.string.empty_dl
                key == Cat.BAD -> R.string.empty_bad
                Cat.groupId(key) != null -> R.string.empty_group
                importJob?.isActive == true -> R.string.loading
                else -> R.string.empty_list
            },
        )
        if (k == Kind.LIVE && key != Cat.ALL && key != Cat.DL) library.check(key, list) // whole-catalogue checks only on request
    }

    private fun loadMore() {
        if (endReached || query.isNotEmpty() || pageJob?.isActive == true || listJob?.isActive == true) return
        val src = source ?: return
        val after = items.items.lastOrNull()?.ord ?: return
        val k = tab
        val key = catKey
        pageJob = ui.launch {
            val more = io { db.items(src, k, key, after, PAGE) }
            if (k != tab || key != catKey) return@launch
            endReached = more.size < PAGE
            items.append(more)
        }
    }

    private fun onQuery(q: String) {
        if (q == query) return
        query = q
        searchJob?.cancel()
        if (q.length < 2) {
            if (q.isEmpty()) {
                cats.activate(catKey)
                ui.launch { loadItems() }
            }
            return
        }
        val src = source ?: return
        val k = tab
        searchJob = ui.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val found = io { db.search(src, k, q, SEARCH_LIMIT) }
            cats.activate(null)
            endReached = true
            items.submit(k, found, favs)
            itemsView.scrollToPosition(0)
            showEmpty(found.isEmpty(), R.string.empty_search)
        }
    }

    /** Empties the search box without the watcher reloading the list (the caller loads what it needs). */
    private fun clearSearch() {
        searchJob?.cancel()
        query = ""
        if (search.text.isNotEmpty()) search.setText("")
    }

    private fun showEmpty(isEmpty: Boolean, text: Int) {
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (isEmpty) empty.setText(text)
    }

    /**
     * The last channel comes back selected, with its guide, but does not start (as on the LG app): nothing holds the
     * account's only connection, so the background check can light the packages the user browses.
     */
    private suspend fun restoreLast(src: Source) {
        if (playing != null || selected != null) return
        val ext = io { db.get(lastKey(src.id)) } ?: return
        val item = items.items.firstOrNull { it.ext == ext } ?: io { db.find(src, Kind.LIVE, ext) } ?: return
        items.setCurrent(item.ext)
        itemsView.scrollToPosition(maxOf(0, items.indexOf(item.ext)))
        showSide(item)
    }

    // ---- selection, details, playback

    private fun onItem(item: Item) {
        selected = item
        items.setCurrent(item.ext)
        when {
            item.kind == Kind.LIVE && playing?.ext == item.ext && playback.media != null -> video.setFullscreen(true)
            item.kind == Kind.LIVE -> play(item, full = false)
            else -> showSide(item)
        }
    }

    private fun play(item: Item, full: Boolean) {
        if (inDownloads()) { // a downloaded film or episode: its own address, read from the download first
            startMedia(Media(listOfNotNull(item.opts), live = false), item.name, full = true)
            playing = item
            return
        }
        if (item.kind == Kind.SERIES && xtream != null) return // Xtream series play per episode
        if (item.kind == Kind.LIVE && playing?.ext == item.ext && playback.media != null) {
            video.setFullscreen(full || video.fullscreen)
            return
        }
        val opts = item.opts?.split('\n')
        startMedia(Media(urlsOf(item), item.kind == Kind.LIVE, opts?.getOrNull(0), opts?.getOrNull(1)), item.name, full)
        playing = item
        library.watched(item)
        if (item.kind == Kind.LIVE) {
            showSide(item)
            val src = source ?: return
            ui.launch { io { db.put(lastKey(src.id), item.ext) } }
        }
    }

    private fun playEpisode(ep: Episode) {
        val series = selected ?: return
        val urls = xtream?.streamUrls(Kind.SERIES, ep.id, ep.cext, servers) ?: return
        startMedia(Media(urls, live = false), "${series.name} · ${ep.season}. sezon ${ep.num}. bölüm", full = true)
        playing = series
        library.watched(series)
    }

    private fun startMedia(m: Media, name: String, full: Boolean) {
        playback.play(m)
        video.show(name, m.live)
        if (full) video.setFullscreen(true)
    }

    /** Side pane: live → now/next and the day's guide; movie → poster and plot; Xtream series → episodes. */
    private fun showSide(item: Item?) {
        sideJob?.cancel()
        selected = item
        actions.visibility = if (item == null) View.GONE else View.VISIBLE
        title.text = item?.name.orEmpty()
        sub.text = item?.rating?.let { "★ $it" }.orEmpty()
        desc.text = ""
        sideRows.submit(emptyList())
        poster.visibility = if (item == null || item.kind == Kind.LIVE) View.GONE else View.VISIBLE
        if (item == null) return
        favBtn.setText(if (item.ext in favs) R.string.fav_remove else R.string.fav_add)
        groupBtn.setText(if (query.isEmpty() && Cat.groupId(catKey) != null) R.string.ungroup else R.string.group)
        val dl = inDownloads()
        for (v in listOf(favBtn, groupBtn, removeBtn)) v.visibility = if (dl) View.GONE else View.VISIBLE
        playBtn.visibility = if (item.kind == Kind.SERIES && xtream != null && !dl) View.GONE else View.VISIBLE
        downloads.bind(item, dl || item.kind == Kind.MOVIE || (item.kind == Kind.SERIES && xtream == null), dl)
        playBtn.setText(if (item.kind == Kind.LIVE) R.string.fullscreen else R.string.play)
        if (item.kind != Kind.LIVE) {
            val d = resources.displayMetrics.density
            Graph.images.load(ui, poster, item.logo, (96 * d).toInt(), (144 * d).toInt(), opaque = true)
        }
        val src = source ?: return
        val x = xtream
        if (dl) return // nothing more to fetch: it may be watched offline
        sideJob = ui.launch {
            when {
                item.kind == Kind.LIVE -> showGuide(guide.of(src, x, item, GUIDE_ROWS))
                x == null -> Unit
                item.kind == Kind.MOVIE -> io { runCatching { x.vodInfo(item.ext) }.getOrNull() }?.let { v ->
                    sub.text = listOfNotNull(v.year, v.genre, v.duration, item.rating?.let { "★ $it" }).filter { it.isNotBlank() }.joinToString(" · ")
                    desc.text = v.plot.orEmpty()
                }
                else -> {
                    desc.setText(R.string.loading)
                    val info = io { runCatching { x.seriesInfo(item.ext) }.getOrNull() }
                    desc.text = info?.plot ?: if (info == null) "Bölümler alınamadı" else ""
                    sideRows.submit(episodeRows(info?.episodes.orEmpty()))
                }
            }
        }
    }

    private fun inDownloads() = catKey == Cat.DL && query.isEmpty()

    private fun showGuide(all: List<Programme>) {
        val now = System.currentTimeMillis() / 1000
        val list = all.filter { it.stop > now } // some panels start the "short" guide hours back
        val current = list.firstOrNull { it.start <= now }
        sub.text = current?.let { "${hm(it.start)}–${hm(it.stop)} · ${it.title}" } ?: "Program bilgisi yok"
        desc.text = current?.desc.orEmpty()
        sideRows.submit(list.map { Row("${hm(it.start)}   ${it.title}", active = it === current, tag = it) })
    }

    private fun episodeRows(eps: List<Episode>): List<Row> {
        val rows = ArrayList<Row>()
        var season = -1
        for (e in eps) {
            if (e.season != season) {
                season = e.season
                rows += Row("$season. Sezon", header = true)
            }
            rows += Row("${e.num}. ${e.title}", tag = e)
        }
        return rows
    }

    private fun toggleFav(item: Item) {
        val src = source ?: return
        val on = item.ext !in favs
        favs = if (on) favs + item.ext else favs - item.ext
        items.setFavs(favs, item.ext)
        if (selected?.ext == item.ext) favBtn.setText(if (on) R.string.fav_remove else R.string.fav_add)
        ui.launch {
            io { db.setFav(src.id, item.kind, item.ext, on) }
            if (catKey == Cat.FAV && query.isEmpty()) loadItems()
        }
    }

    // ---- VideoBox / Playback callbacks

    override fun onPreviewTap() = video.setFullscreen(true)

    override fun onExitFull() = video.setFullscreen(false)

    /**
     * Next/previous channel of the current list. A single step opens at once; steps following within
     * [ZAP_DELAY_MS] are coalesced so a fast series of swipes opens only the final channel.
     */
    override fun onStep(delta: Int) {
        val list = items.items
        val from = if (zapTo >= 0) zapTo else items.indexOf(playing?.ext)
        if (from < 0 || list.isEmpty() || playing?.kind != Kind.LIVE) return
        zapTo = (from + delta).mod(list.size)
        video.show(list[zapTo].name, true)
        handler.removeCallbacks(zap)
        if (SystemClock.uptimeMillis() - lastZap > ZAP_DELAY_MS) zap.run() else handler.postDelayed(zap, ZAP_DELAY_MS)
    }

    private var lastZap = 0L
    private val zap = Runnable {
        lastZap = SystemClock.uptimeMillis()
        val item = items.items.getOrNull(zapTo)
        zapTo = -1
        if (item != null) {
            selected = item
            items.setCurrent(item.ext)
            itemsView.scrollToPosition(items.indexOf(item.ext))
            play(item, full = video.fullscreen)
        }
    }

    override fun onStatus(status: Playback.Status, detail: String?) {
        video.onStatus(status, detail)
        val live = playing?.takeIf { it.kind == Kind.LIVE && playback.media?.live == true }
        // the light follows real playback: a picture makes it green, a final failure red
        if (live != null && status == Playback.Status.PLAYING) library.record(live.ext, true)
        if (live != null && status == Playback.Status.FAILED) {
            library.record(live.ext, false)
            video.setFullscreen(false) // the reconnect failed too: back to the channel list
        }
        // a one-connection account drops the picture on a stream probe: while one plays the check asks the panel only
        library.hold(status == Playback.Status.CONNECTING, solo && status in ON_AIR)
        syncDownloads()
    }

    /** One connection: a download waits while anything plays (or starts). */
    private fun syncDownloads() {
        val s = playback.status
        Graph.offlineIfOpen?.hold(solo && (s == Playback.Status.CONNECTING || s in ON_AIR))
    }

    override fun onVideoSize(width: Int, height: Int, pixelRatio: Float) = video.onVideoSize(width, height, pixelRatio)

    override fun onFirstFrame(ms: Long) {
        if (debuggable) Log.i(TAG, "first-frame $ms ms")
    }

    override fun onQualities(choices: List<Int>) = video.onQualities(choices)

    /** One cap for every stream: picked in the player or in Settings, remembered for new channels. */
    override fun onQuality(maxHeight: Int) {
        playback.setMaxHeight(maxHeight)
        ui.launch { io { db.put(K_MAX_QUALITY, maxHeight.toString()) } }
        if (settings.isOpen) settings.showQuality(maxHeight)
    }

    override fun onMaxQuality(maxHeight: Int) = onQuality(maxHeight)

    // ---- Library host

    override fun urlsOf(item: Item): List<String> {
        xtream?.let { return it.streamUrls(item.kind, item.ext, item.cext, servers) }
        val url = mask?.unmask(item.ext) ?: item.ext
        return if (item.kind == Kind.LIVE) m3uCandidates(url) else listOf(url)
    }

    override fun playingExt() = playing?.takeIf { playback.media != null }?.ext

    override fun reloadRail() = showTab(tab)

    override fun reloadList() {
        ui.launch { loadItems() }
    }

    override fun onLight(ext: String) = items.changed(ext)

    override fun progress(text: String?) {
        if (text != null) status.text = text else if (status.text.startsWith(CHECK_PREFIX)) status.text = ""
    }

    override fun pauseForScan(): Item? {
        val was = playing?.takeIf { it.kind == Kind.LIVE && playback.media != null }
        playback.clear()
        video.hide()
        playing = null
        return was
    }

    override fun resumeAfterScan(item: Item?) {
        if (item != null && tab == Kind.LIVE && playing == null) play(item, full = false)
    }

    // ---- SettingsPanel callbacks

    override fun onRestore(src: Source) {
        ui.launch {
            val n = io { db.unhideAll(src.id) }
            Toast.makeText(this@MainActivity, if (n > 0) "$n silinen öğe geri getirildi" else "Silinen öğe yok", Toast.LENGTH_SHORT).show()
            if (src.id == source?.id) showTab(tab)
        }
    }

    private fun openSettings() {
        hideKeyboard(search)
        settings.open(sources, source?.id)
        settings.showQuality(playback.maxHeight)
    }

    override fun onClose() {
        hideKeyboard(search)
        settings.close()
    }

    override fun onSelect(src: Source) {
        onClose()
        ui.launch { activate(src) }
    }

    override fun onRefresh(src: Source) {
        settings.close()
        ui.launch {
            if (src.id != source?.id) activate(src)
            refresh(sources.firstOrNull { it.id == src.id } ?: src)
        }
    }

    override fun onSaved(id: Long) {
        ui.launch {
            reloadSources()
            val src = sources.firstOrNull { it.id == id } ?: return@launch
            settings.close()
            if (src.id == source?.id) importJob?.cancel()
            source = null // force a full switch: new credentials mean new stream addresses
            activate(src)
        }
    }

    override fun onDeleted(src: Source) {
        ui.launch {
            if (src.id == source?.id) {
                importJob?.cancel()
                playback.clear()
                video.hide()
                playing = null
                showSide(null)
                items.submit(tab, emptyList(), emptySet())
                cats.submit(emptyList())
                source = null
            }
            io { db.deleteSource(src.id) }
            Graph.offline.let { off -> io { off.removeSource(src.id) } }
            reloadSources()
            val next = source ?: sources.firstOrNull()
            if (next == null) {
                settings.open(sources, null)
            } else {
                settings.showSources(sources, next.id)
                if (source == null) activate(next)
            }
        }
    }

    // ---- helpers

    private fun hideKeyboard(v: View) {
        v.clearFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun hm(sec: Long): CharSequence = DateFormat.format("HH:mm", sec * 1000)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val TAG = "M2YTV"
        const val K_SOURCE = "src"
        const val K_TAB = "tab"
        const val K_MAX_QUALITY = "max_q"
        const val PAGE = 500
        const val SEARCH_LIMIT = 300
        const val SEARCH_DEBOUNCE_MS = 300L
        const val ZAP_DELAY_MS = 350L
        const val GUIDE_ROWS = 24
        const val STALE_MS = 24 * 3600_000L
        const val AUTO_REFRESH_DELAY_MS = 8_000L
        const val CHECK_PREFIX = "Kanallar denetleniyor"
        val ON_AIR = setOf(Playback.Status.PLAYING, Playback.Status.BUFFERING, Playback.Status.PAUSED)

        fun catKey(src: Long, k: Kind) = "cat_${src}_${k.ordinal}"
        fun lastKey(src: Long) = "last_$src"
    }
}
