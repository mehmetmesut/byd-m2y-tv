package com.m2y.tv.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.m2y.tv.Graph
import com.m2y.tv.R
import com.m2y.tv.data.Creds
import com.m2y.tv.data.Secrets
import com.m2y.tv.data.Source
import com.m2y.tv.data.Xtream
import com.m2y.tv.data.isHttpUrl
import com.m2y.tv.data.normalizeServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Date

/** Playlists (add, edit, refresh, select, delete) and About; inline forms, no dialogs. */
class SettingsPanel(private val act: Activity, private val scope: CoroutineScope, private val cb: Callbacks) {
    interface Callbacks {
        fun onSelect(src: Source)
        fun onRefresh(src: Source)
        fun onSaved(id: Long)
        fun onDeleted(src: Source)
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
    private val fEpg: EditText = act.findViewById(R.id.fEpg)
    private val fError: TextView = act.findViewById(R.id.fError)
    private val fSave: TextView = act.findViewById(R.id.fSave)
    private val handler = Handler(Looper.getMainLooper())

    private var editing: Source? = null
    private var xtreamForm = false
    private var busy = false

    val isOpen get() = panel.visibility == View.VISIBLE

    init {
        act.findViewById<View>(R.id.sClose).setOnClickListener { cb.onClose() }
        act.findViewById<View>(R.id.addM3u).setOnClickListener { openForm(null, false) }
        act.findViewById<View>(R.id.addXtream).setOnClickListener { openForm(null, true) }
        act.findViewById<View>(R.id.fCancel).setOnClickListener { closeForm() }
        fSave.setOnClickListener { save() }
        val version = act.packageManager.getPackageInfo(act.packageName, 0).versionName
        act.findViewById<TextView>(R.id.about).text = act.getString(R.string.about_text, version)
    }

    @Suppress("DEPRECATION") // ADJUST_RESIZE is the Android 10 way to keep the focused field above the keyboard
    fun open(sources: List<Source>, activeId: Long?) {
        panel.visibility = View.VISIBLE
        act.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        showSources(sources, activeId)
        if (sources.isEmpty()) openForm(null, false)
    }

    fun close() {
        closeForm()
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
            row.findViewById<TextView>(R.id.rDelete).setOnClickListener { v -> confirmDelete(v as TextView, s) }
            list.addView(row)
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
        editing = src
        xtreamForm = xtream
        fTitle.setText(if (xtream) R.string.form_xtream else R.string.form_m3u)
        fUrl.setHint(if (xtream) R.string.f_server else R.string.f_m3u)
        fUser.visibility = if (xtream) View.VISIBLE else View.GONE
        fPass.visibility = if (xtream) View.VISIBLE else View.GONE
        fEpg.visibility = if (xtream) View.GONE else View.VISIBLE
        for (f in listOf(fName, fUrl, fUser, fPass, fEpg)) f.setText("")
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
                }
                null -> error("Kayıtlı bilgiler çözülemedi; yeniden girin.")
            }
        }
    }

    private fun closeForm() {
        form.visibility = View.GONE
        for (f in listOf(fName, fUrl, fUser, fPass, fEpg)) f.setText("") // no credentials left in views
        editing = null
    }

    private fun save() {
        if (busy) return
        val url = fUrl.text.toString().trim()
        val user = fUser.text.toString().trim()
        val pass = fPass.text.toString()
        val epg = fEpg.text.toString().trim().ifEmpty { null }
        val creds: Creds = if (xtreamForm) {
            if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) return error("Sunucu, kullanıcı adı ve şifre gerekli.")
            if ('\n' in user || '\n' in pass) return error("Kullanıcı adı ve şifre tek satır olmalı.")
            Creds.Xtream(normalizeServer(url), user, pass)
        } else {
            if (!isHttpUrl(url)) return error("Adres http:// ya da https:// ile başlamalı.")
            if (epg != null && !isHttpUrl(epg)) return error("EPG adresi http:// ya da https:// ile başlamalı.")
            Creds.M3u(url)
        }
        val name = fName.text.toString().trim().ifEmpty { hostOf(url) }
        busy = true
        fSave.setText(R.string.checking)
        fError.visibility = View.GONE
        scope.launch {
            val result = withContext(Dispatchers.IO) { check(creds)?.let { Result.failure(IOException(it)) } ?: store(name, creds, epg) }
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

    private fun store(name: String, creds: Creds, epg: String?): Result<Long> = try {
        Result.success(Graph.db.saveSource(editing?.id, name, creds is Creds.Xtream, Secrets.seal(creds), epg))
    } catch (e: GeneralSecurityException) {
        Result.failure(IOException("Bilgiler şifrelenemedi (Keystore): ${e.message}"))
    }

    private fun error(text: String) {
        fError.text = text
        fError.visibility = View.VISIBLE
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore(':').ifEmpty { "Liste" }

    fun release() = handler.removeCallbacksAndMessages(null)

    private companion object {
        const val ARMED = "armed"
        const val CONFIRM_MS = 3_000L
    }
}
