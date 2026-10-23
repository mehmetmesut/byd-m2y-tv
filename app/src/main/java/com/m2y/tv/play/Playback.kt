package com.m2y.tv.play

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** What to play: [urls] are tried in order (output formats, HLS twin, alternative server); [ua]/[referer] come from M3U #EXTVLCOPT lines. */
class Media(val urls: List<String>, val live: Boolean, val ua: String? = null, val referer: String? = null)

/** Film/episode speeds in tap order, starting from normal. */
private val SPEEDS = floatArrayOf(1f, 1.25f, 1.5f, 0.5f, 0.75f)

fun nextSpeed(current: Float): Float = SPEEDS[(SPEEDS.indexOfFirst { it == current } + 1) % SPEEDS.size]

/** "1x", "1,25x", "0,5x" (Turkish decimal comma). */
fun speedLabel(s: Float): String = (if (s % 1f == 0f) s.toInt().toString() else s.toString().replace('.', ',')) + "x"

/** The head unit's screen height: nothing above it is worth decoding, "automatic" included. */
const val MAX_HEIGHT = 1080

/** Height cap actually applied for a chosen maximum (0 = automatic). */
fun videoCap(maxHeight: Int) = if (maxHeight in 1 until MAX_HEIGHT) maxHeight else MAX_HEIGHT

/** Selectable heights of a stream: distinct, at most [MAX_HEIGHT], highest first. Fewer than two = single quality. */
fun qualityChoices(heights: Collection<Int>): List<Int> = heights.filter { it in 1..MAX_HEIGHT }.distinct().sortedDescending()

fun qualityLabel(maxHeight: Int) = if (maxHeight <= 0) "Otomatik" else "${maxHeight}p"

/**
 * The app's only ExoPlayer (one hardware decoder at a time). A channel switch reuses it; every request bumps a
 * generation so a retry or timeout armed for a channel the user already left can never touch the current one.
 * Main thread only.
 */
@OptIn(UnstableApi::class)
class Playback(
    private val ctx: Context,
    http: OkHttpClient,
    private val ui: Listener,
    /** Wraps the network source for films and episodes (downloads first, then the watch cache); never used for live. */
    private val vodCache: (DataSource.Factory) -> DataSource.Factory,
) {
    interface Listener {
        fun onStatus(status: Status, detail: String?)
        fun onVideoSize(width: Int, height: Int, pixelRatio: Float)
        fun onFirstFrame(ms: Long)

        /** Heights the current stream offers (see [qualityChoices]); empty or one = single quality. */
        fun onQualities(choices: List<Int>)
    }

    enum class Status { IDLE, CONNECTING, PLAYING, BUFFERING, PAUSED, ENDED, FAILED }

    // streams are open-ended responses: no overall call timeout, a stalled read still fails
    private val dataSource = OkHttpDataSource.Factory(
        http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
    )
    // few loader-level retries: errors reach [retry] quickly, which reconnects with backoff (progressive live default is 6)
    private val loadErrors = DefaultLoadErrorHandlingPolicy(LOAD_RETRIES)
    private val hls = HlsMediaSource.Factory(dataSource).setLoadErrorHandlingPolicy(loadErrors)
    private val progressive = ProgressiveMediaSource.Factory(dataSource).setLoadErrorHandlingPolicy(loadErrors)
    private val vodSource by lazy { vodCache(dataSource) } // opens the caches on the first film, not at app start
    private val vodHls by lazy { HlsMediaSource.Factory(vodSource).setLoadErrorHandlingPolicy(loadErrors) }
    private val vodProgressive by lazy { ProgressiveMediaSource.Factory(vodSource).setLoadErrorHandlingPolicy(loadErrors) }
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var builtLive = true // the player's buffer sizes follow the kind of stream it was built for
    private var surface: SurfaceView? = null
    private var gen = 0
    private var cand = 0 // index of the address being tried in media.urls
    private var retried = false // the one reconnect after a drop was used
    private var asHls = false
    private var startedAt = 0L
    private var resumeAt = 0L // VOD position to reopen at after release/retry
    private var resumePaused = false

    var media: Media? = null
        private set
    var status = Status.IDLE
        private set

    /** Playback speed of the current film/episode; every new stream starts at 1x. */
    var speed = 1f
        private set

    /** Highest video height the user allows (0 = automatic, still capped at [MAX_HEIGHT]); kept for every stream. */
    var maxHeight = 0
        private set

    val positionMs get() = player?.currentPosition ?: 0
    val durationMs get() = player?.duration?.takeIf { it != C.TIME_UNSET } ?: 0

    fun attach(view: SurfaceView) {
        surface = view
        player?.setVideoSurfaceView(view)
    }

    fun play(m: Media) {
        media = m
        resumeAt = 0
        resumePaused = false
        speed = 1f
        open()
    }

    /** Re-opens the current stream after [release] (activity back in front). */
    fun resume() {
        if (media != null && player == null) open()
    }

    /** Network is back or the user tapped the error: try the current stream again (a waiting film at once). */
    fun retryIfFailed() {
        if (status == Status.FAILED || (waiting && status == Status.CONNECTING)) open()
    }

    /** A film or episode lost the network and waits for it; its position is kept. */
    private val waiting get() = media?.live == false && retried

    private fun open() {
        retried = false
        candidate(0)
        start()
    }

    private fun candidate(i: Int) {
        cand = i
        asHls = media?.urls?.getOrNull(i)?.substringBefore('?')?.endsWith(".m3u8", true) == true
    }

    fun togglePause() {
        val p = player ?: return
        when (status) {
            Status.FAILED -> retryIfFailed()
            Status.ENDED -> p.seekTo(0).also { p.playWhenReady = true }
            else -> {
                p.playWhenReady = !p.playWhenReady
                set(if (p.playWhenReady) Status.PLAYING else Status.PAUSED)
            }
        }
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0))
    }

    /** Next film/episode speed (pitch is kept by the player's time stretching); returns the new value. */
    fun cycleSpeed(): Float {
        speed = nextSpeed(speed)
        player?.setPlaybackSpeed(speed)
        return speed
    }

    /** Caps the video track (adaptive streams switch at the next segment, no reload); applies to later streams too. */
    fun setMaxHeight(h: Int) {
        maxHeight = h
        player?.let(::applyQuality)
    }

    private fun applyQuality(p: ExoPlayer) {
        // too-high only variants still play: the selector exceeds the cap when nothing fits under it
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(Int.MAX_VALUE, videoCap(maxHeight)).build()
    }

    /** Releases the decoder and buffers; [media] and the VOD position are kept for [resume]. */
    fun release() {
        player?.let {
            remember(it)
            resumePaused = !it.playWhenReady
            it.release()
        }
        player = null
        gen++
        handler.removeCallbacksAndMessages(null)
        set(Status.IDLE)
    }

    private fun remember(p: ExoPlayer) {
        if (media?.live == false && p.currentPosition > 0) resumeAt = p.currentPosition
    }

    fun clear() {
        media = null
        release()
        set(Status.IDLE)
    }

    private fun start() {
        val m = media ?: return
        if (player != null && builtLive != m.live) {
            player?.release() // live and film buffers differ; a new stream reinitialises the decoder anyway
            player = null
        }
        val p = player ?: build(m.live).also {
            player = it
            builtLive = m.live
        }
        val g = ++gen
        handler.removeCallbacksAndMessages(null)
        dataSource.setDefaultRequestProperties(
            buildMap {
                m.ua?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
                m.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            },
        )
        val item = MediaItem.Builder().setUri(m.urls[cand]).apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        val source = when {
            m.live && asHls -> hls.createMediaSource(item)
            m.live -> progressive.createMediaSource(item)
            asHls -> vodHls.createMediaSource(item)
            else -> vodProgressive.createMediaSource(item)
        }
        if (resumeAt > 0) p.setMediaSource(source, resumeAt) else p.setMediaSource(source)
        p.playWhenReady = !resumePaused
        p.setPlaybackSpeed(speed) // also restores it after release/retry
        p.prepare()
        resumeAt = 0
        resumePaused = false
        startedAt = SystemClock.elapsedRealtime()
        set(Status.CONNECTING, if (waiting) WAIT_TEXT else if (retried) "Bağlantı koptu, yeniden bağlanılıyor…" else null)
        handler.postDelayed({ if (g == gen && status == Status.CONNECTING) failed(g, "Yayın yanıt vermiyor", false) }, START_TIMEOUT_MS)
    }

    private fun build(live: Boolean): ExoPlayer {
        // video + audio only: no text, metadata or image renderers decoding data nobody shows
        val renderers = RenderersFactory { h, video, audio, _, _ ->
            arrayOf(
                MediaCodecVideoRenderer.Builder(ctx)
                    .setMediaCodecSelector(MediaCodecSelector.DEFAULT)
                    .setAllowedJoiningTimeMs(JOIN_MS)
                    .setEnableDecoderFallback(true) // hardware first, software decoder if the hardware one fails
                    .setEventHandler(h)
                    .setEventListener(video)
                    .setMaxDroppedFramesToNotify(MAX_DROPPED)
                    .build(),
                MediaCodecAudioRenderer(ctx, MediaCodecSelector.DEFAULT, true, h, audio, DefaultAudioSink.Builder(ctx).build()),
            )
        }
        // films read further ahead so a tunnel or a dead zone is bridged; live stays short for fast zaps
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(if (live) MIN_BUFFER_MS else VOD_MIN_BUFFER_MS, if (live) MAX_BUFFER_MS else VOD_MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            // byte cap wins over time: buffers live in the Java heap (256 MB limit on the head unit)
            .setTargetBufferBytes(if (live) BUFFER_BYTES else VOD_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(load)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
            .apply {
                addListener(events)
                surface?.let(::setVideoSurfaceView)
                applyQuality(this)
            }
    }

    private val events = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val heights = ArrayList<Int>()
            for (g in tracks.groups) {
                if (g.type != C.TRACK_TYPE_VIDEO) continue
                for (i in 0 until g.length) if (g.isTrackSupported(i)) heights += g.getTrackFormat(i).height
            }
            ui.onQualities(qualityChoices(heights))
        }

        override fun onPlaybackStateChanged(state: Int) {
            val p = player ?: return
            when (state) {
                Player.STATE_READY -> {
                    retried = false // the picture is back: the next drop gets its own reconnect
                    set(if (p.playWhenReady) Status.PLAYING else Status.PAUSED)
                }
                Player.STATE_BUFFERING -> if (status != Status.CONNECTING) set(Status.BUFFERING)
                // a live feed that "ends" is a dropped connection
                Player.STATE_ENDED -> if (media?.live == true) failed(gen, "Yayın kesildi", false) else set(Status.ENDED)
            }
        }

        // only the first frame after opening a stream counts (seeks and track changes render "first frames" too)
        override fun onRenderedFirstFrame() {
            if (startedAt > 0) ui.onFirstFrame(SystemClock.elapsedRealtime() - startedAt)
            startedAt = 0
        }

        override fun onVideoSizeChanged(size: VideoSize) = ui.onVideoSize(size.width, size.height, size.pixelWidthHeightRatio)

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            when {
                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    p.seekToDefaultPosition()
                    p.prepare()
                }
                // HLS behind an address without .m3u8 (common in M3U lists): sniffing fails, try once as HLS
                !asHls && error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                    asHls = true
                    start()
                }
                else -> failed(gen, message(error), error.errorCode in PERMANENT)
            }
        }
    }

    /**
     * The next address is tried at once; when none is left, a dropped stream waits [RETRY_MS] (a short network
     * wobble, or a single-connection account still counting the old stream) and reconnects once from the first
     * address. A second drop, or an error retrying cannot fix, is reported.
     */
    private fun failed(g: Int, reason: String, permanent: Boolean) {
        if (g != gen) return
        val m = media ?: return
        player?.let(::remember)
        if (cand + 1 < m.urls.size) {
            candidate(cand + 1)
            start()
            return
        }
        // live: one reconnect; a film keeps waiting for the network (every WAIT_RETRY_MS while the app is in front)
        if (permanent || (retried && m.live)) return fail(reason)
        val delay = if (retried) WAIT_RETRY_MS else RETRY_MS
        retried = true
        candidate(0)
        val next = ++gen
        handler.removeCallbacksAndMessages(null)
        player?.stop() // frees the account's connection while waiting
        set(Status.CONNECTING, if (m.live) "Bağlantı koptu, yeniden bağlanılıyor…" else WAIT_TEXT)
        handler.postDelayed({ if (next == gen) start() }, delay)
    }

    private fun fail(reason: String) {
        gen++
        handler.removeCallbacksAndMessages(null)
        player?.let(::remember)
        player?.stop()
        set(Status.FAILED, reason)
    }

    private fun set(s: Status, detail: String? = null) {
        status = s
        ui.onStatus(s, detail)
    }

    private fun message(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "Sunucu yayını vermedi (HTTP ${(e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: "?"})"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Bağlantı kurulamadı"
        in PERMANENT -> "Bu yayının biçimi desteklenmiyor"
        else -> "Yayın oynatılamadı"
    }

    private companion object {
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 30_000
        const val START_BUFFER_MS = 1_000 // fast first frame on a zap
        const val REBUFFER_MS = 4_000 // after a stall, build a cushion before resuming
        const val BUFFER_BYTES = 32 shl 20
        const val JOIN_MS = 5_000L
        const val MAX_DROPPED = 50
        const val START_TIMEOUT_MS = 12_000L
        const val RETRY_MS = 3_000L
        const val WAIT_RETRY_MS = 10_000L
        const val WAIT_TEXT = "İnternet bekleniyor; gelince kaldığı yerden sürecek…"
        const val VOD_MIN_BUFFER_MS = 60_000
        const val VOD_MAX_BUFFER_MS = 120_000
        const val VOD_BUFFER_BYTES = 80 shl 20 // ~110 s of a 6 Mbit/s film; the heap limit is 256 MB
        const val LOAD_RETRIES = 2

        // retrying cannot fix these
        val PERMANENT = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )
    }
}
