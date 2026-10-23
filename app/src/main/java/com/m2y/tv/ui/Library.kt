package com.m2y.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.m2y.tv.Graph
import com.m2y.tv.data.AutoCheck
import com.m2y.tv.data.Cat
import com.m2y.tv.data.Item
import com.m2y.tv.data.Kind
import com.m2y.tv.data.Source
import com.m2y.tv.data.probe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * List management carried over from the webOS app: channel health lights (real playback, the background check and
 * the on-demand scan), recently watched, deleted channels and packages, user groups. Deleted entries are only hidden:
 * Settings brings them back. Dialogs are the platform's AlertDialog. Main thread only.
 */
class Library(private val act: Activity, private val ui: CoroutineScope, private val host: Host) {
    interface Host {
        val source: Source?
        val tab: Kind
        fun urlsOf(item: Item): List<String>
        fun playingExt(): String?

        /** Groups or packages changed: rebuild the category rail and the list. */
        fun reloadRail()
        fun reloadList()

        /** The light of [ext] changed: redraw its row. */
        fun onLight(ext: String)
        fun progress(text: String?)

        /** Stops playback for a scan (one connection); returns what played, to reopen afterwards. */
        fun pauseForScan(): Item?
        fun resumeAfterScan(item: Item?)
    }

    private val db get() = Graph.db
    private val health = HashMap<String, Boolean>()
    private var healthSrc = -1L
    private val auto = AutoCheck(ui, Graph.http) { done, total -> host.progress(if (total > 0) "Kanallar denetleniyor: $done / $total" else null) }
    private var scanJob: Job? = null

    /** Loads the lights of [src] (once per source). */
    suspend fun load(src: Source) {
        if (healthSrc == src.id) return
        auto.stop()
        val map = io { db.health(src.id) }
        health.clear()
        health.putAll(map)
        healthSrc = src.id
    }

    fun light(ext: String): Boolean? = health[ext]

    /** A real result: a picture came (true) or the stream failed for good (false). */
    fun record(ext: String, ok: Boolean) {
        val src = host.source ?: return
        if (health[ext] == ok) return
        health[ext] = ok
        host.onLight(ext)
        ui.launch { io { db.setHealth(src.id, ext, ok) } }
    }

    /** While on, the background check starts no probe: a stream is starting, or plays on a one-connection account. */
    fun hold(on: Boolean) {
        auto.held = on
    }

    /** Background check of the opened live list: channels without a light, the playing one never. */
    fun check(key: String, items: List<Item>) {
        val src = host.source ?: return
        auto.run("${src.id}/$key", items, host::urlsOf, health::containsKey, { it == host.playingExt() }) { item, ok -> record(item.ext, ok) }
    }

    fun stop() {
        auto.stop()
        scanJob?.cancel()
    }

    fun watched(item: Item) {
        val src = host.source ?: return
        ui.launch { io { db.addRecent(src.id, item.kind, item.ext) } }
    }

    // ---- item actions (side pane buttons)

    /** "Gruba ekle" outside a group, "Gruptan çıkar" inside the group [key]. */
    fun groupAction(item: Item, key: String) {
        val src = host.source ?: return
        val inGroup = Cat.groupId(key)
        if (inGroup != null) {
            ui.launch {
                io { db.setInGroup(inGroup, item.ext, false) }
                host.reloadList()
            }
            return
        }
        ui.launch {
            val groups = io { db.groups(src.id, item.kind) }
            val labels = groups.map { it.name } + "+ Yeni grup"
            dialog().setTitle("“${item.name}” hangi gruba eklensin?")
                .setItems(labels.toTypedArray()) { _, i ->
                    val g = groups.getOrNull(i)
                    if (g != null) addTo(g.id, g.name, item, false) else askName("Grup ${groups.size + 1}") { addTo(null, it, item, true) }
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
    }

    private fun addTo(id: Long?, name: String, item: Item, created: Boolean) {
        val src = host.source ?: return
        ui.launch {
            io { db.setInGroup(id ?: db.addGroup(src.id, item.kind, name), item.ext, true) }
            toast("“$name” grubuna eklendi")
            if (created) host.reloadRail()
        }
    }

    fun deleteItem(item: Item) {
        val src = host.source ?: return
        confirm("“${item.name}” listeden silinsin mi?", RESTORE_HINT, "Evet, sil") {
            ui.launch {
                io { db.hide(src.id, item.kind, listOf(item.ext), isCat = false) }
                host.reloadList()
            }
        }
    }

    // ---- category rail (long press)

    fun railMenu(key: String, name: String) {
        val src = host.source ?: return
        val kind = host.tab
        val group = Cat.groupId(key)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (kind == Kind.LIVE) actions += "Çevrimiçi tara" to { scan(key, name) }
        when {
            key == Cat.FAV -> actions += "Favorileri temizle" to {
                confirm("Favoriler temizlensin mi?", "Kanallar silinmez, yalnız bu liste boşalır.", "Evet, temizle") {
                    change { db.clearFavs(src.id, kind) }
                }
            }
            key == Cat.REC -> actions += "Son izlenenleri temizle" to {
                confirm("Son izlenenler temizlensin mi?", "Kanallar silinmez, yalnız bu liste boşalır.", "Evet, temizle") {
                    change { db.clearRecent(src.id, kind) }
                }
            }
            group != null -> actions += "Grubu sil" to {
                confirm("“$name” grubu silinsin mi?", "Kanallar silinmez, yalnız grup kaldırılır.", "Evet, sil") { change { db.deleteGroup(group) } }
            }
            key != Cat.ALL -> actions += "Paketi sil (kanallarıyla)" to {
                confirm("“$name” paketi ve içindeki kanallar silinsin mi?", RESTORE_HINT, "Evet, sil") {
                    change { db.hide(src.id, kind, listOf(key), isCat = true) }
                }
            }
        }
        if (actions.isEmpty()) return
        dialog().setTitle(name).setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }.setNegativeButton("Vazgeç", null).show()
    }

    private fun change(write: () -> Unit) {
        ui.launch {
            io { write() }
            host.reloadRail()
        }
    }

    // ---- on-demand scan

    /**
     * Probes every channel of the list [key] one by one behind a progress dialog, with playback stopped so the probes
     * never compete with the stream for the account's connection. "Aktif" means the server sends the stream, not that
     * the head unit can decode it.
     */
    private fun scan(key: String, name: String) {
        val src = host.source ?: return
        auto.stop()
        val resume = host.pauseForScan()
        val dlg = dialog().setTitle("Çevrimiçi tarama: $name").setMessage("Kanallar alınıyor…").setCancelable(false)
            .setNegativeButton("Durdur", null)
            .setPositiveButton("Çalışmayanları sil", null)
            .create()
        dlg.setOnDismissListener {
            scanJob?.cancel()
            host.resumeAfterScan(resume)
            host.reloadList()
        }
        dlg.show()
        val stop = dlg.getButton(DialogInterface.BUTTON_NEGATIVE)
        val del = dlg.getButton(DialogInterface.BUTTON_POSITIVE).apply { visibility = View.GONE }
        val bad = ArrayList<String>()
        stop.setOnClickListener { if (scanJob?.isActive == true) scanJob?.cancel() else dlg.dismiss() }
        scanJob = ui.launch {
            val items = io { all(src, key) }
            var ok = 0
            var unknown = 0
            val t0 = SystemClock.elapsedRealtime()
            for ((i, item) in items.withIndex()) {
                if (!isActive) break
                val left = if (i > 0) (SystemClock.elapsedRealtime() - t0) / i * (items.size - i) / 1000 else 0
                dlg.setMessage(
                    "$i / ${items.size}" + (if (left > 0) " · Kalan yaklaşık ${left / 60} dk ${left % 60} sn" else "") +
                        "\nAktif (yeşil) $ok · Pasif (kırmızı) ${bad.size} · Doğrulanamadı $unknown",
                )
                when (probe(Graph.http, host.urlsOf(item))) {
                    true -> {
                        ok++
                        record(item.ext, true)
                    }
                    false -> {
                        bad += item.ext
                        record(item.ext, false)
                    }
                    null -> unknown++
                }
            }
            val done = ok + bad.size + unknown
            dlg.setMessage(
                (if (done < items.size) "Durduruldu: " else "Bitti: ") + "$done / ${items.size} kanal tarandı." +
                    "\nAktif (yeşil) $ok · Pasif (kırmızı) ${bad.size} · Doğrulanamadı $unknown" +
                    "\n\n“Aktif”: sunucu yayını veriyor; aracın yayını çözebildiğini garanti etmez.",
            )
        }
        scanJob?.invokeOnCompletion {
            ui.launch {
                stop.text = "Kapat"
                if (bad.isNotEmpty()) {
                    del.text = "Çalışmayanları sil (${bad.size})"
                    del.visibility = View.VISIBLE
                    del.setOnClickListener {
                        confirm("${bad.size} çalışmayan kanal listeden silinsin mi?", RESTORE_HINT, "Evet, sil") {
                            ui.launch {
                                io { db.hide(src.id, Kind.LIVE, bad, isCat = false) }
                                dlg.dismiss()
                            }
                        }
                    }
                }
            }
        }
    }

    /** Every visible item of a rail key, page by page. */
    private fun all(src: Source, key: String): List<Item> {
        val out = ArrayList<Item>()
        while (true) {
            val page = db.items(src, Kind.LIVE, key, out.lastOrNull()?.ord ?: -1, PAGE)
            out += page
            if (page.size < PAGE || key == Cat.FAV || key == Cat.REC || Cat.groupId(key) != null) return out
        }
    }

    // ---- small dialogs

    private fun dialog() = AlertDialog.Builder(act, android.R.style.Theme_Material_Dialog_Alert)

    /** Negative action left, the confirming one right. */
    private fun confirm(title: String, message: String, yes: String, action: () -> Unit) {
        dialog().setTitle(title).setMessage(message).setNegativeButton("Vazgeç", null).setPositiveButton(yes) { _, _ -> action() }.show()
    }

    private fun askName(initial: String, done: (String) -> Unit) {
        val field = EditText(act).apply {
            setText(initial)
            setSelection(initial.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        dialog().setTitle("Yeni grup adı").setView(field).setNegativeButton("Vazgeç", null)
            .setPositiveButton("Oluştur") { _, _ -> field.text.toString().trim().ifEmpty { initial }.let(done) }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(act, text, Toast.LENGTH_SHORT).show()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val PAGE = 500
        const val RESTORE_HINT = "Ayarlar > Silinenleri geri al ile geri getirilebilir."
    }
}
