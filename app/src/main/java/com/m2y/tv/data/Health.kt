package com.m2y.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val PROBE_TIMEOUT_S = 8L
private const val AUTH_RETRY_MS = 2_500L
private const val NO_ANSWER = -1
private const val HTML = -2

/**
 * Opens each candidate address only until the response headers arrive, then closes it: no video is downloaded.
 * 401/403 is asked once more after a pause (a single-connection account may still count the previous stream) and is
 * then reported as undecided (null), so a connection limit never paints working channels red.
 * true = the server sends the stream, false = it does not.
 */
suspend fun probe(http: OkHttpClient, urls: List<String>): Boolean? {
    val client = http.newBuilder().connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS).readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS).build()
    var refused = false
    for (url in urls) {
        for (attempt in 0..1) {
            val code = status(client, url)
            if (code in 200..299) return true
            if (code != 401 && code != 403) break // an error page with status 200 (HTML) or any other answer
            refused = true
            if (attempt == 0) delay(AUTH_RETRY_MS)
        }
    }
    return if (refused) null else false
}

/**
 * Asks the panel only, without following its redirect to the stream server, so a one-connection account keeps its
 * connection for the stream being watched (measured on the car 23.10.2026: three such probes, playback went on).
 * A redirect only says the panel accepts the request, not that a picture comes: undecided (null). 404 = gone (false).
 */
suspend fun probePanel(http: OkHttpClient, url: String): Boolean? {
    val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS).readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS).build()
    return if (status(client, url) == 404) false else null
}

/** HTTP status of [url], [HTML] for an HTML page, [NO_ANSWER] when unreachable; the body is never read. */
private suspend fun status(http: OkHttpClient, url: String): Int = suspendCancellableCoroutine { cont ->
    val call = try {
        http.newCall(Request.Builder().url(url).build())
    } catch (e: IllegalArgumentException) {
        return@suspendCancellableCoroutine cont.resume(NO_ANSWER) // not an http(s) address
    }
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(NO_ANSWER)
            }

            override fun onResponse(call: Call, response: Response) {
                val html = "text/html" in response.header("Content-Type").orEmpty().lowercase()
                val code = response.code
                response.close() // drops the connection: the account's only stream slot is free again
                if (cont.isActive) cont.resume(if (code in 200..299 && html) HTML else code)
            }
        },
    )
}

/**
 * Background check of the list the user opened: only channels without a light yet are probed, one at a time. The
 * playing channel is never probed (the player decides its light), nothing starts while [held] (a stream is starting)
 * and, while a stream plays on an account that allows one connection, only the panel is asked ([panelOnly]), so the
 * check never competes with the picture. Main thread only.
 */
class AutoCheck(private val scope: CoroutineScope, private val http: OkHttpClient, private val progress: (done: Int, total: Int) -> Unit) {
    private var job: Job? = null
    private var key = ""

    var held = false
    var panelOnly = false

    /** Checks [items] of the list [key]; [known] is true for channels with a light, [skip] for the playing one. */
    fun run(
        key: String,
        items: List<Item>,
        urlsOf: (Item) -> List<String>,
        known: (String) -> Boolean,
        skip: (String) -> Boolean,
        result: (Item, Boolean) -> Unit,
    ) {
        if (job?.isActive == true && this.key == key) return
        stop()
        val todo = items.filter { !known(it.ext) }
        if (todo.isEmpty()) return
        this.key = key
        val j = scope.launch(start = CoroutineStart.LAZY) {
            try {
                var refused = 0
                for ((i, item) in todo.withIndex()) {
                    if (held) progress(0, 0) // no counter while waiting for the stream to end
                    while (held) delay(HOLD_POLL_MS)
                    if (known(item.ext) || skip(item.ext)) continue // the player got there first
                    progress(i + 1, todo.size)
                    val panel = panelOnly
                    val v = if (panel) urlsOf(item).firstOrNull()?.let { probePanel(http, it) } else probe(http, urlsOf(item))
                    if (v == null) {
                        // a panel answer is undecided by design; refusals of full probes mean a connection limit
                        if (!panel && ++refused >= MAX_REFUSALS) break // the rest waits for another time
                        continue
                    }
                    refused = 0
                    if (!skip(item.ext)) result(item, v)
                }
            } finally {
                if (job === coroutineContext[Job]) finish()
            }
        }
        job = j
        j.start()
    }

    fun stop() {
        job?.cancel()
        finish()
    }

    private fun finish() {
        job = null
        key = ""
        progress(0, 0)
    }

    private companion object {
        const val MAX_REFUSALS = 2
        const val HOLD_POLL_MS = 500L
    }
}
