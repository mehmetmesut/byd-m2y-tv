package com.m2y.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.m2y.tv.Graph
import com.m2y.tv.R
import com.m2y.tv.data.Episode
import com.m2y.tv.data.Item
import com.m2y.tv.data.Kind
import com.m2y.tv.play.Offline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads of films and episodes: the side pane's "İndir" button, the episode long-press menu and the İndirilenler
 * list of the film and series tabs. Items of that list carry the download id in `ext` and the address in `opts`.
 * Main thread only.
 */
class Downloads(
    private val act: Activity,
    private val ui: CoroutineScope,
    private val host: Library.Host,
    /** The download manager was just opened: the activity decides whether it may run (one connection). */
    private val opened: () -> Unit,
) {
    private val button: TextView = act.findViewById(R.id.dl)
    private var item: Item? = null
    private var inList = false
    private var stateJob: Job? = null

    /** The list is open and needs redrawing on progress; set by the activity. */
    var listShown = false

    init {
        button.setOnClickListener { item?.let(::toggle) }
    }

    /** Opens the download manager at start when something was downloaded or is still waiting, so it goes on. */
    fun resumePending() {
        if (File(act.filesDir, "downloads").list()?.isNotEmpty() == true) offline()
    }

    private fun offline(): Offline = Graph.offline.also {
        if (it.onChange == null) {
            it.onChange = ::changed
            it.onCapReached = { title -> toast("“$title” durduruldu: indirme alanı 5 GB ile sınırlı") }
            opened()
        }
    }

    private fun changed() {
        if (listShown) host.reloadList()
        item?.let { bind(it, inList, true) }
    }

    /** Side pane: shows the button for a film, an M3U episode or a downloaded entry ([downloadable]), with its state. */
    fun bind(item: Item?, downloadable: Boolean, fromList: Boolean) {
        stateJob?.cancel()
        this.item = item?.takeIf { downloadable }
        inList = fromList
        button.visibility = if (this.item == null) View.GONE else View.VISIBLE
        val it = this.item ?: return
        val src = host.source ?: return
        val off = offline()
        stateJob = ui.launch {
            val e = io { off.get(if (fromList) it.ext else Offline.id(src.id, it.kind, it.ext)) }
            button.text = when {
                e == null -> "İndir"
                e.done -> "İndirildi ✓"
                e.percent >= 0 -> "İndiriliyor %${e.percent}"
                else -> "Sırada"
            }
        }
    }

    private fun toggle(it: Item) {
        val src = host.source ?: return
        val id = if (inList) it.ext else Offline.id(src.id, it.kind, it.ext)
        val off = offline()
        ui.launch {
            val e = io { off.get(id) }
            if (e != null) return@launch askRemove(id, it.name.substringBefore(" · "))
            start(id, it.kind, it.name, it.logo, host.urlsOf(it).firstOrNull() ?: return@launch)
        }
    }

    /** Episode rows: long press offers downloading or deleting that episode. */
    fun episodeMenu(series: Item, ep: Episode) {
        val src = host.source ?: return
        val id = Offline.id(src.id, Kind.SERIES, ep.id)
        val title = "${series.name} · ${ep.season}. sezon ${ep.num}. bölüm"
        val off = offline()
        ui.launch {
            val e = io { off.get(id) }
            if (e != null) return@launch askRemove(id, title)
            val url = host.urlsOf(Item(0, Kind.SERIES, null, ep.id, title, series.logo, ep.cext, null, 0, 0, null, null)).firstOrNull() ?: return@launch
            AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert).setTitle(title)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Bölümü indir") { _, _ -> start(id, Kind.SERIES, title, series.logo, url) }
                .show()
        }
    }

    private fun start(id: String, kind: Kind, title: String, logo: String?, url: String) {
        val problem = offline().add(id, kind, title, logo, url)
        toast(problem ?: "İndirme sıraya alındı. Yayın izlerken duraklar, uygulama açıkken sürer.")
        item?.let { bind(it, true, inList) }
    }

    private fun askRemove(id: String, title: String) {
        AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert).setTitle("“$title” indirmesi silinsin mi?")
            .setMessage("Dosya araçtan silinir; içerik listede kalır.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Evet, sil") { _, _ ->
                offline().remove(id)
                if (listShown) host.reloadList()
            }
            .show()
    }

    /** İndirilenler of [kind] as list items: `ext` = download id, `opts` = address, state in the name. */
    suspend fun items(kind: Kind): List<Item> = offline().let { off -> io { off.list(kind) } }.mapIndexed { i, e ->
        val state = when {
            e.done -> ""
            e.percent >= 0 -> " · %${e.percent}"
            else -> " · sırada"
        }
        Item(0, kind, null, e.id, e.title + state, e.logo, null, null, 0, i, null, e.url)
    }

    private fun toast(text: String) = Toast.makeText(act, text, Toast.LENGTH_LONG).show()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
