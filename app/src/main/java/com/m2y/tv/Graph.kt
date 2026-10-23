package com.m2y.tv

import android.content.Context
import com.m2y.tv.data.Db
import com.m2y.tv.data.Importer
import com.m2y.tv.play.Offline
import com.m2y.tv.ui.Images
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Process-wide singletons, created on first use. Holds only the application context. */
object Graph {
    const val USER_AGENT = "M2YTV/1.0 (Linux; Android 10)"

    private lateinit var app: Context

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
    }

    val db by lazy { Db(app) }

    /** API and playlist client; no HTTP cache so a multi-MB list or a stream is never written to disk. */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", USER_AGENT).build() else req)
            }
            .build()
    }

    /** Logos and posters share the connection pool but get a capped disk cache. */
    val images by lazy { Images { http.newBuilder().cache(Cache(File(app.cacheDir, "img"), 40L shl 20)).build() } }

    val importer by lazy { Importer(db, http) }

    private var offlineOpen: Offline? = null

    /** Downloads and the watch cache; opened by the first film or download, never at start for live TV only. */
    val offline: Offline get() = offlineOpen ?: Offline(app, http).also { offlineOpen = it }

    /** Null until something opened [offline]: live TV alone never creates it. */
    val offlineIfOpen: Offline? get() = offlineOpen
}
