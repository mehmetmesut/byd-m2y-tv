package com.m2y.tv.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.m2y.tv.Graph
import com.m2y.tv.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.m2y.tv.data.Creds
import com.m2y.tv.data.Entry
import com.m2y.tv.data.PairServer
import com.m2y.tv.data.Secrets
import com.m2y.tv.data.Source
import com.m2y.tv.data.Xtream
import com.m2y.tv.data.entryCreds
import com.m2y.tv.data.isHttpUrl
import com.m2y.tv.play.qualityLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Date

/** Playlists (add on screen or from the phone, edit, refresh, select, restore deleted, delete) and playback; inline forms. */
class SettingsPanel(private val act: Activity, private val scope: CoroutineScope, private val cb: Callbacks) {
    interface Callbacks {
        fun onSelect(src: Source)
        fun onRefresh(src: Source)
        fun onSaved(id: Long)
        fun onDeleted(src: Source)
        fun onRestore(src: Source)
        fun onMaxQuality(maxHeight: Int)
        fun onClose()
    }

    private val panel: View = act.findViewById(R.id.settingsPanel)
    private val list: LinearLayout = act.findViewById(R.id.sources)
    private val form: View = act.findViewById(R.id.form)
    private val fTitle: TextView = act.findViewById(R.id.fTitle)
    private val fName: EditText = act.findViewById(R.id.fName)
    private val fUrl: EditText = act.findViewById(R.id.fUrl)
    private val fUser: EditText = act.findViewById(R.id.fUser)
    private val fPass: EditText = act.findViewById(R.id.fPass)
    private val fAlt: EditText = act.findViewById(R.id.fAlt)
    private val fEpg: EditText = act.findViewById(R.id.fEpg)
    private val fError: TextView = act.findViewById(R.id.fError)
    private val fSave: TextView = act.findViewById(R.id.fSave)
    private val qualityChips: LinearLayout = act.findViewById(R.id.qualityChips)
    private val qr: View = act.findViewById(R.id.qr)
    private val qrImage: ImageView = act.findViewById(R.id.qrImage)
    private val qrUrl: TextView = act.findViewById(R.id.qrUrl)
    private val handler = Handler(Looper.getMainLooper())
    private val pair = PairServer(scope)

    private var editing: Source? = null
    private var xtreamForm = false
    private var busy = false

    @Volatile private var pairedId: Long? = null // saved from the phone (IO thread), opened after the reply

    val isOpen get() = panel.visibility == View.VISIBLE

    init {
        act.findViewById<View>(R.id.sClose).setOnClickListener { cb.onClose() }
        act.findViewById<View>(R.id.addM3u).setOnClickListener { openForm(null, false) }
        act.findViewById<View>(R.id.addXtream).setOnClickListener { openForm(null, true) }
        act.findViewById<View>(R.id.addPhone).setOnClickListener { openQr() }
        act.findViewById<View>(R.id.qrClose).setOnClickListener { closeQr() }
        act.findViewById<View>(R.id.fCancel).setOnClickListener { closeForm() }
        fSave.setOnClickListener { save() }
    }

    @Suppress("DEPRECATION") // ADJUST_RESIZE is the Android 10 way to keep the focused field above the keyboard
    fun open(sources: List<Source>, activeId: Long?) {
        panel.visibility = View.VISIBLE
        act.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        showSources(sources, activeId)
        openQr() // adding from the phone is the usual way: its page is ready as soon as Settings opens
        toTop()
    }

    /** Keeps the title row with Kapat in view: focus moving to a chip must not scroll it under the status bar. */
    private fun toTop() = panel.post { panel.scrollTo(0, 0) }

    fun close() {
        closeForm()
        closeQr()
        panel.visibility = View.GONE
        act.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }

    fun showSources(sources: List<Source>, activeId: Long?) {
        list.removeAllViews()
        val inflater = LayoutInflater.from(act)
        for (s in sources) {
            val row = inflater.inflate(R.layout.row_source, list, false)
            row.findViewById<TextView>(R.id.rName).text = s.name
            val kind = if (s.xtream) "Xtream" else "M3U"
            val when_ = if (s.updated > 0) DateFormat.format("dd.MM.yyyy HH:mm", Date(s.updated)) else "henüz yüklenmedi"
            row.findViewById<TextView>(R.id.rMeta).text = "$kind · ${s.info ?: "—"} · $when_"
            row.findViewById<TextView>(R.id.rSelect).apply {
                isActivated = s.id == activeId
                setText(if (s.id == activeId) R.string.selected else R.string.select)
                setOnClickListener { cb.onSelect(s) }
            }
            row.findViewById<View>(R.id.rRefresh).setOnClickListener { cb.onRefresh(s) }
            row.findViewById<View>(R.id.rEdit).setOnClickListener { openForm(s, s.xtream) }
            row.findViewById<View>(R.id.rRestore).setOnClickListener { cb.onRestore(s) }
            row.findViewById<TextView>(R.id.rDelete).setOnClickListener { v -> confirmDelete(v as TextView, s) }
            list.addView(row)
        }
    }

    /** Default maximum quality for every stream; a height picked in the player that is not on the ladder is shown too. */
    fun showQuality(current: Int) {
        qualityChips.removeAllViews()
        val inflater = LayoutInflater.from(act)
        for (h in if (current in LADDER) LADDER else LADDER + current) {
            val chip = inflater.inflate(R.layout.row_chip, qualityChips, false) as TextView
            chip.text = if (h == SAVER) act.getString(R.string.quality_saver) else qualityLabel(h)
            chip.isActivated = h == current
            chip.setOnClickListener { cb.onMaxQuality(h) }
            qualityChips.addView(chip)
        }
    }

    /** Two taps: the first arms the button for a few seconds. */
    private fun confirmDelete(v: TextView, s: Source) {
        if (v.tag == ARMED) {
            cb.onDeleted(s)
            return
        }
        v.tag = ARMED
        v.setText(R.string.delete_confirm)
        v.setTextColor(act.getColor(R.color.danger))
        handler.postDelayed({
            v.tag = null
            v.setText(R.string.delete)
            v.setTextColor(act.getColor(R.color.text))
        }, CONFIRM_MS)
    }

    private fun openForm(src: Source?, xtream: Boolean) {
        closeQr()
        editing = src
        xtreamForm = xtream
        fTitle.setText(if (xtream) R.string.form_xtream else R.string.form_m3u)
        fUrl.setHint(if (xtream) R.string.f_server else R.string.f_smart)
        for (f in listOf(fUser, fPass, fAlt)) f.visibility = if (xtream) View.VISIBLE else View.GONE
        fEpg.visibility = if (xtream) View.GONE else View.VISIBLE
        for (f in listOf(fName, fUrl, fUser, fPass, fAlt, fEpg)) f.setText("")
        fError.visibility = View.GONE
        form.visibility = View.VISIBLE
        if (src == null) return
        fName.setText(src.name)
        fEpg.setText(src.epgUrl.orEmpty())
        scope.launch {
            val creds = withContext(Dispatchers.IO) { runCatching { Secrets.open(src.secret) }.getOrNull() }
            when (creds) {
                is Creds.M3u -> fUrl.setText(creds.url)
                is Creds.Xtream -> {
                    fUrl.setText(creds.server)
                    fUser.setText(creds.user)
                    fPass.setText(creds.pass)
                    fAlt.setText(creds.alt.orEmpty())
                }
                null -> error("Kayıtlı bilgiler çözülemedi; yeniden girin.")
            }
        }
    }

    private fun closeForm() {
        form.visibility = View.GONE
        for (f in listOf(fName, fUrl, fUser, fPass, fAlt, fEpg)) f.setText("") // no credentials left in views
        editing = null
    }

    private fun save() {
        if (busy) return
        val url = fUrl.text.toString().trim()
        val epg = fEpg.text.toString().trim().ifEmpty { null }.takeIf { !xtreamForm }
        if (epg != null && !isHttpUrl(epg)) return error("EPG adresi http:// ya da https:// ile başlamalı.")
        val entry = Entry(!xtreamForm, fName.text.toString(), fUser.text.toString(), fPass.text.toString(), url, fAlt.text.toString(), url)
        val (creds, problem) = entryCreds(entry)
        if (creds == null) return error(problem ?: "Eksik bilgi")
        busy = true
        fSave.setText(R.string.checking)
        fError.visibility = View.GONE
        scope.launch {
            val result = withContext(Dispatchers.IO) { checkAndStore(editing?.id, entry.name, creds, epg) }
            busy = false
            fSave.setText(R.string.save)
            result.onSuccess { id ->
                closeForm()
                cb.onSaved(id)
            }.onFailure { error(it.message ?: "Kaydedilemedi") }
        }
    }

    /** Reaches the server before saving, so a typo is caught here and not as an empty list later. Null when fine. */
    private fun check(creds: Creds): String? = try {
        when (creds) {
            is Creds.Xtream -> if (Xtream(Graph.http, creds).account().ok) null else "Giriş reddedildi: kullanıcı adı ya da şifre hatalı, ya da hesap süresi dolmuş."
            is Creds.M3u -> Graph.http.newCall(Request.Builder().url(creds.url).build()).execute().use { if (it.isSuccessful) null else "Liste alınamadı (HTTP ${it.code})." }
        }
    } catch (e: IOException) {
        "Sunucuya ulaşılamadı: ${e.message ?: "bağlantı hatası"}"
    } catch (e: IllegalArgumentException) {
        "Adres geçersiz."
    }

    /** Blocking (IO thread): reaches the server, then seals and saves; the EPG address belongs to M3U playlists only. */
    private fun checkAndStore(id: Long?, typedName: String, creds: Creds, epg: String?): Result<Long> {
        check(creds)?.let { return Result.failure(IOException(it)) }
        val name = typedName.trim().ifEmpty {
            hostOf(
                when (creds) {
                    is Creds.Xtream -> creds.server
                    is Creds.M3u -> creds.url
                },
            )
        }
        return try {
            Result.success(Graph.db.saveSource(id, name, creds is Creds.Xtream, Secrets.seal(creds), epg.takeIf { creds is Creds.M3u }))
        } catch (e: GeneralSecurityException) {
            Result.failure(IOException("Bilgiler şifrelenemedi (Keystore): ${e.message}"))
        }
    }

    // ---- phone pairing (QR)

    private fun openQr() {
        closeForm()
        qr.visibility = View.VISIBLE
        qrImage.setImageDrawable(null)
        qrUrl.setText(R.string.qr_opening)
        scope.launch {
            val opened = withContext(Dispatchers.IO) {
                runCatching {
                    val url = pair.open(::fromPhone, ::phoneDone) { reason ->
                        qrImage.setImageDrawable(null)
                        qrUrl.text = reason
                    }
                    url to qrBitmap(url)
                }
            }
            if (qr.visibility != View.VISIBLE) return@launch pair.close() // closed while opening
            opened.onSuccess { (url, bmp) ->
                qrImage.setImageBitmap(bmp)
                if (panel.visibility == View.VISIBLE && form.visibility != View.VISIBLE) toTop()
                qrUrl.text = url
            }.onFailure { qrUrl.text = it.message ?: "Sayfa açılamadı" }
        }
    }

    /** An entry posted from the phone (IO thread): saved like the on-screen form; the error text goes back to the phone. */
    private suspend fun fromPhone(e: Entry): String? {
        val (creds, problem) = entryCreds(e)
        if (creds == null) return problem
        val result = checkAndStore(null, e.name, creds, null)
        pairedId = result.getOrNull()
        return result.exceptionOrNull()?.message
    }

    /** The phone has its "Kaydedildi" page: now the QR screen closes and the new playlist opens. */
    private fun phoneDone() {
        val id = pairedId ?: return
        pairedId = null
        closeQr()
        cb.onSaved(id)
    }

    private fun closeQr() {
        pair.close()
        qr.visibility = View.GONE
        qrImage.setImageDrawable(null)
    }

    /** Black-on-white QR of [text], [QR_PX] pixels per module; small RGB_565 bitmap, scaled by the ImageView. */
    private fun qrBitmap(text: String): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
        val size = m.width * QR_PX
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x / QR_PX, y / QR_PX]) BLACK else WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }

    private fun error(text: String) {
        fError.text = text
        fError.visibility = View.VISIBLE
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore(':').ifEmpty { "Liste" }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        pair.close()
    }

    private companion object {
        const val ARMED = "armed"
        const val CONFIRM_MS = 3_000L
        const val SAVER = 480
        val LADDER = listOf(0, 1080, 720, SAVER)
        const val QR_PX = 6
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
