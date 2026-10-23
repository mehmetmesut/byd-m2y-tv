package com.m2y.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/** What the on-screen form and the phone page collect: server details, or one "Akıllı Bağlantı" address. */
class Entry(val smart: Boolean, val name: String, val user: String, val pass: String, val server: String, val alt: String, val url: String)

/**
 * Entry → credentials, or the error text when it is incomplete. An Akıllı Bağlantı carrying username/password becomes
 * an Xtream account (API catalogue, guide, VOD); any other address is a plain M3U playlist.
 */
fun entryCreds(e: Entry): Pair<Creds?, String?> {
    if (e.smart) {
        val url = e.url.trim()
        if (!isHttpUrl(url)) return null to "Geçerli bir Akıllı Bağlantı (http:// ya da https://) girin."
        return (parseSmartLink(url) ?: Creds.M3u(url)) to null
    }
    val server = e.server.trim()
    val user = e.user.trim()
    if (server.isEmpty()) return null to "Sunucu adresini girin."
    if (user.isEmpty() || e.pass.isEmpty()) return null to "Kullanıcı adı ve şifreyi girin."
    if ('\n' in user || '\n' in e.pass) return null to "Kullanıcı adı ve şifre tek satır olmalı."
    val alt = e.alt.trim().takeIf { it.isNotEmpty() }?.let(::normalizeServer)
    return Creds.Xtream(normalizeServer(server), user, e.pass, alt) to null
}

/**
 * Phone pairing. While the QR screen is open, a one-time page is served on the local network: the car joins the
 * phone's hotspot, so the phone reaches it. One source per QR code; the page closes after [TTL_MS], after
 * [MAX_BAD] wrong-address requests, or when [close] is called. Nothing listens at any other time.
 */
class PairServer(private val scope: CoroutineScope) {
    private var server: ServerSocket? = null
    private var jobs: List<Job> = emptyList()

    /**
     * Opens the page and returns its address; blocking, call from an IO thread. [onEntry] checks and saves a posted
     * entry and returns an error text (shown on the phone) or null; [onDone] runs (main thread) once the phone got its
     * answer and the page is closed; [onClosed] gets the reason the page closed itself.
     */
    fun open(onEntry: suspend (Entry) -> String?, onDone: () -> Unit, onClosed: (String) -> Unit): String {
        close()
        val ip = lanIp() ?: throw IOException("Araç bir ağa bağlı değil (telefonun hotspot'unu açın).")
        val socket = PORTS.firstNotNullOfOrNull { p ->
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(p))
                }
            } catch (e: IOException) {
                null
            }
        } ?: throw IOException("Boş port yok")
        server = socket
        val token = ByteArray(TOKEN_BYTES).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        jobs = listOf(
            scope.launch(Dispatchers.IO) { serve(socket, "/$token", onEntry, onDone, onClosed) },
            scope.launch {
                delay(TTL_MS)
                if (server === socket) onClosed("Süre doldu (10 dk). Yeniden açın.")
                closeIf(socket)
            },
        )
        return "http://$ip:${socket.localPort}/$token"
    }

    fun close() {
        jobs.forEach(Job::cancel)
        jobs = emptyList()
        server?.let { runCatching(it::close) }
        server = null
    }

    private fun closeIf(socket: ServerSocket) {
        if (server === socket) close()
    }

    private suspend fun serve(socket: ServerSocket, path: String, onEntry: suspend (Entry) -> String?, onDone: () -> Unit, onClosed: (String) -> Unit) {
        var bad = 0
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                return // closed
            }
            client.use { c ->
                c.soTimeout = SOCKET_TIMEOUT_MS // a slow client must not hold the page
                val done = try {
                    answer(c, path, onEntry)
                } catch (e: IOException) {
                    null // the phone went away mid-request
                }
                if (done == false && ++bad > MAX_BAD) {
                    withContext(Dispatchers.Main) { onClosed("Sayfa güvenlik için kapatıldı. Yeniden açın.") }
                    socket.close()
                }
                if (done == true) {
                    delay(CLOSE_DELAY_MS) // the phone's connection gets its answer before the page goes away
                    socket.close()
                    withContext(Dispatchers.Main) { onDone() } // last: the caller's close() cancels this coroutine
                }
            }
        }
    }

    /** Handles one request; true = a source was saved, false = wrong address, null = anything else. */
    private suspend fun answer(c: Socket, path: String, onEntry: suspend (Entry) -> String?): Boolean? {
        val input = c.getInputStream().buffered()
        val head = readHead(input) ?: return null
        val (method, target) = head.first.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        if (target.substringBefore('?') != path) {
            send(c, 404, "Bulunamadı")
            return false
        }
        val length = head.second["content-length"]?.toIntOrNull() ?: 0
        val refusal = when {
            method == "GET" -> pairPage(path, null, emptyMap())
            method != "POST" -> "İzin verilmiyor"
            length !in 0..MAX_BODY -> "Çok büyük"
            else -> null
        }
        if (refusal != null) {
            send(c, if (method == "GET") 200 else 400, refusal)
            return null
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        val fields = parseForm(String(body, Charsets.UTF_8))
        val entry = formEntry(fields)
        val error = entryCreds(entry).second ?: onEntry(entry)
        send(c, if (error == null) 200 else 400, pairPage(path, error, fields, done = error == null))
        return if (error == null) true else null
    }

    private fun send(c: Socket, code: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        // no keep-alive: closing the page must not wait for idle phone connections
        val head = "HTTP/1.1 $code \r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nX-Frame-Options: DENY\r\nConnection: close\r\n\r\n"
        c.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    companion object {
        private val PORTS = listOf(8732, 8733, 8734, 8735, 8736)
        private const val TTL_MS = 10 * 60_000L
        private const val MAX_BODY = 8192
        private const val MAX_HEAD = 8192
        private const val MAX_BAD = 30
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val CLOSE_DELAY_MS = 1_000L
        private const val TOKEN_BYTES = 16
        private const val MAX_FIELD = 2048

        /** IPv4 address of the Wi-Fi (or any other non-loopback) interface. */
        fun lanIp(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress

        /** Request line and lower-cased headers; null when the client sent nothing usable. */
        internal fun readHead(input: InputStream): Pair<String, Map<String, String>>? {
            val lines = ArrayList<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val b = input.read()
                if (b < 0 || ++total > MAX_HEAD) return null
                if (b == '\n'.code) {
                    val s = line.toString().trimEnd('\r')
                    line.setLength(0)
                    if (s.isEmpty()) break
                    lines += s
                } else {
                    line.append(b.toChar())
                }
            }
            val first = lines.firstOrNull() ?: return null
            val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':', "").trim() }
            return first to headers
        }

        internal fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) return@mapNotNull null
            try {
                URLDecoder.decode(part.substring(0, i), "UTF-8") to URLDecoder.decode(part.substring(i + 1), "UTF-8").take(MAX_FIELD)
            } catch (e: IllegalArgumentException) {
                null
            }
        }.toMap()

        internal fun formEntry(f: Map<String, String>): Entry {
            val smart = f["mode"] == "smart"
            fun get(k: String) = f[k].orEmpty().trim()
            return if (smart) {
                Entry(true, get("name"), "", "", "", "", get("url"))
            } else {
                Entry(false, get("name"), get("user"), f["pass"].orEmpty(), get("server"), get("alt"), "")
            }
        }

        internal fun esc(s: String) = buildString {
            for (ch in s) {
                append(
                    when (ch) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&#39;"
                        else -> ch
                    },
                )
            }
        }

        private const val STYLE = "body{margin:0;padding:20px;background:#07080a;color:#d0d4d9;font:17px/1.4 system-ui,sans-serif}" +
            "h1{font-size:22px;color:#fff}label{display:block;margin:14px 0 4px;color:#969ca4}" +
            "input{width:100%;box-sizing:border-box;padding:12px;border:0;border-radius:8px;background:#16191e;color:#fff;font-size:17px}" +
            ".t{display:flex}.t label{flex:1;margin:0 4px 0 0;padding:12px;border-radius:8px;background:#16191e;text-align:center;color:#d0d4d9}" +
            ".t input{display:none}.t input:checked+span{color:#0a84ff;font-weight:600}" +
            "button{width:100%;margin-top:22px;padding:14px;border:0;border-radius:8px;background:#0a84ff;color:#fff;font-size:18px}" +
            ".e{color:#ff453a}.n{color:#969ca4;font-size:14px}"

        /** The form, or the result page when [done]. Values are re-filled escaped after an error; never the password. */
        internal fun pairPage(action: String, error: String?, values: Map<String, String>, done: Boolean = false): String {
            val head = "<!doctype html><html lang=\"tr\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>M2Y TV</title><style>$STYLE</style></head><body>"
            if (done) return "$head<h1>Kaydedildi</h1><p>Liste araçta yükleniyor. Bu sayfayı kapatabilirsiniz.</p></body></html>"
            val smart = values["mode"] != "server" // Akıllı Bağlantı first and by default: one address to paste
            fun field(name: String, label: String, type: String, ph: String? = null) =
                "<label>$label</label><input name=\"$name\" type=\"$type\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\"" +
                    (ph?.let { " placeholder=\"${esc(it)}\"" } ?: "") +
                    (values[name]?.takeIf { type != "password" && it.isNotEmpty() }?.let { " value=\"${esc(it)}\"" } ?: "") + ">"
            return head + "<h1>M2Y TV'ye oynatma listesi ekle</h1>" +
                (error?.let { "<p class=\"e\">${esc(it)}</p>" } ?: "") +
                // novalidate: "sunucu:8080" without a scheme is valid here (the app adds http://); checks run in the car
                "<form novalidate method=\"post\" action=\"${esc(action)}\">" +
                "<div class=\"t\"><label><input type=\"radio\" name=\"mode\" value=\"smart\"${if (smart) " checked" else ""} onchange=\"s()\"><span>Akıllı Bağlantı</span></label>" +
                "<label><input type=\"radio\" name=\"mode\" value=\"server\"${if (smart) "" else " checked"} onchange=\"s()\"><span>Sunucu bilgileri</span></label></div>" +
                field("name", "Oynatma Listesi Adı", "text", "İsteğe bağlı") +
                "<div id=\"sm\">" + field("url", "Akıllı Bağlantı ya da M3U adresi", "url", "http://sunucu/get.php?username=…&password=…") + "</div>" +
                "<div id=\"sv\">" + field("user", "Kullanıcı Adı", "text") + field("pass", "Şifre", "password") +
                field("server", "Sunucu Adresi", "url", "http://sunucu:8080") +
                field("alt", "Alternatif Sunucu Adresi (İsteğe Bağlı)", "url") + "</div>" +
                "<button type=\"submit\">Araca gönder</button></form>" +
                "<p class=\"n\">Bilgiler yalnız aynı ağdaki aracınıza gider; bu sayfa tek kullanımlıktır ve araçta QR ekranı kapanınca kapanır.</p>" +
                "<script>function s(){var m=document.querySelector('input[value=smart]').checked;" +
                "document.getElementById('sv').style.display=m?'none':'';document.getElementById('sm').style.display=m?'':'none'}s()</script>" +
                "</body></html>"
        }
    }
}
