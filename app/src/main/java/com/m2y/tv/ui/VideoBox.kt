package com.m2y.tv.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.m2y.tv.R
import com.m2y.tv.play.Playback
import com.m2y.tv.play.qualityLabel
import com.m2y.tv.play.speedLabel
import kotlin.math.abs

/**
 * The one video surface. In preview it is laid over the side pane's slot; full screen it covers the window.
 * Moving it never recreates the SurfaceView, so the decoder keeps running across the switch.
 */
@SuppressLint("ClickableViewAccessibility")
class VideoBox(private val act: Activity, private val playback: () -> Playback, private val cb: Callbacks) {
    interface Callbacks {
        fun onPreviewTap()
        fun onExitFull()
        fun onStep(delta: Int)

        /** User picked a maximum height in the player (0 = automatic). */
        fun onQuality(maxHeight: Int)
    }

    private val root: FrameLayout = act.findViewById(R.id.root)
    private val box: FrameLayout = act.findViewById(R.id.video)
    private val slot: View = act.findViewById(R.id.slot)
    private val browse: View = act.findViewById(R.id.browse)
    val surface: SurfaceView = act.findViewById(R.id.surface)
    private val msg: TextView = act.findViewById(R.id.videoMsg)
    private val osd: View = act.findViewById(R.id.osd)
    private val osdTitle: TextView = act.findViewById(R.id.osdTitle)
    private val seekRow: View = act.findViewById(R.id.seekRow)
    private val seek: SeekBar = act.findViewById(R.id.seek)
    private val time: TextView = act.findViewById(R.id.time)
    private val pause: TextView = act.findViewById(R.id.oPause)
    private val liveOnly = listOf<View>(act.findViewById(R.id.oPrev), act.findViewById(R.id.oNext))
    private val speedBtn: TextView = act.findViewById(R.id.oSpeed)
    private val vodOnly = listOf<View>(act.findViewById(R.id.oBack), act.findViewById(R.id.oFwd), speedBtn, seekRow)
    private val qualityBtn: TextView = act.findViewById(R.id.oQuality)
    private val qualityRow: LinearLayout = act.findViewById(R.id.oQualityRow)
    private var choices: List<Int> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    var fullscreen = false
        private set
    private var live = true
    private var videoW = 16
    private var videoH = 9
    private var statusText: String? = null

    private val hideOsd = Runnable {
        osd.visibility = View.GONE
        qualityRow.visibility = View.GONE
    }
    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            if (osd.visibility == View.VISIBLE) handler.postDelayed(this, 1000)
        }
    }

    init {
        slot.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!fullscreen) place() }
        box.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (r - l != or - ol || b - t != ob - ot) fit() }
        val gestures = GestureDetector(
            act,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    when {
                        playback().status == Playback.Status.FAILED -> playback().retryIfFailed()
                        !fullscreen -> cb.onPreviewTap()
                        osd.visibility == View.VISIBLE -> osd.visibility = View.GONE
                        else -> showOsd()
                    }
                    return true
                }

                // vertical swipe zaps channels, like a TV remote
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    if (!fullscreen || !live || abs(vy) < abs(vx) * 2) return false
                    cb.onStep(if (vy < 0) 1 else -1)
                    return true
                }
            },
        )
        box.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
        act.findViewById<View>(R.id.oPrev).setOnClickListener { cb.onStep(-1).also { showOsd() } }
        act.findViewById<View>(R.id.oNext).setOnClickListener { cb.onStep(1).also { showOsd() } }
        act.findViewById<View>(R.id.oBack).setOnClickListener { seekBy(-10_000) }
        act.findViewById<View>(R.id.oFwd).setOnClickListener { seekBy(30_000) }
        act.findViewById<View>(R.id.oExit).setOnClickListener { cb.onExitFull() }
        speedBtn.setOnClickListener {
            speedBtn.text = speedLabel(playback().cycleSpeed())
            showOsd()
        }
        qualityBtn.setOnClickListener {
            if (qualityRow.visibility == View.VISIBLE) qualityRow.visibility = View.GONE else openQualities()
            showOsd()
        }
        pause.setOnClickListener {
            playback().togglePause()
            showOsd()
        }
        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) time.text = clock(p * 1000L, playback().durationMs)
                }

                override fun onStartTrackingTouch(s: SeekBar) = handler.removeCallbacks(hideOsd)

                override fun onStopTrackingTouch(s: SeekBar) {
                    playback().seekTo(s.progress * 1000L)
                    showOsd()
                }
            },
        )
    }

    fun show(title: String, isLive: Boolean) {
        live = isLive
        osdTitle.text = title
        speedBtn.text = speedLabel(playback().speed)
        qualityRow.visibility = View.GONE
        onQualities(emptyList()) // tracks of the new stream are not known yet
        liveOnly.forEach { it.visibility = if (live) View.VISIBLE else View.GONE }
        vodOnly.forEach { it.visibility = if (live) View.GONE else View.VISIBLE }
        slot.visibility = View.VISIBLE
        box.visibility = View.VISIBLE
        if (fullscreen) showOsd()
    }

    fun hide() {
        setFullscreen(false)
        slot.visibility = View.GONE
        box.visibility = View.GONE
    }

    @Suppress("DEPRECATION") // systemUiVisibility is the API on Android 10
    fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on
        browse.visibility = if (on) View.INVISIBLE else View.VISIBLE // invisible keeps the slot's geometry
        act.window.decorView.systemUiVisibility = if (on) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            0
        }
        if (on) showOsd() else osd.visibility = View.GONE
        place()
    }

    fun onVideoSize(w: Int, h: Int, ratio: Float) {
        if (w <= 0 || h <= 0) return
        videoW = (w * ratio).toInt()
        videoH = h
        fit()
    }

    /** Quality button: active only when the stream has two or more heights to choose from. */
    fun onQualities(c: List<Int>) {
        choices = c
        val multi = c.size >= 2
        qualityBtn.isEnabled = multi
        qualityBtn.alpha = if (multi) 1f else DISABLED_ALPHA
        qualityBtn.text = when {
            multi -> act.getString(R.string.quality_value, qualityLabel(playback().maxHeight))
            c.size == 1 -> act.getString(R.string.quality_single)
            else -> act.getString(R.string.quality)
        }
        if (!multi) qualityRow.visibility = View.GONE
    }

    /** One-tap row of choices above the controls: automatic and the stream's own heights. */
    private fun openQualities() {
        qualityRow.removeAllViews()
        val current = playback().maxHeight
        for (h in listOf(0) + choices) {
            val chip = LayoutInflater.from(act).inflate(R.layout.row_osd_chip, qualityRow, false) as TextView
            chip.text = qualityLabel(h)
            chip.isActivated = h == current
            chip.setOnClickListener {
                cb.onQuality(h)
                qualityRow.visibility = View.GONE
                onQualities(choices)
                showOsd()
            }
            qualityRow.addView(chip)
        }
        qualityRow.visibility = View.VISIBLE
    }

    fun onStatus(s: Playback.Status, detail: String?) {
        statusText = when (s) {
            Playback.Status.CONNECTING -> detail ?: act.getString(R.string.connecting)
            Playback.Status.BUFFERING -> act.getString(R.string.buffering)
            Playback.Status.FAILED -> "${detail.orEmpty()}\n${act.getString(R.string.tap_retry)}"
            else -> null
        }
        pause.setText(if (s == Playback.Status.PAUSED || s == Playback.Status.ENDED) R.string.resume else R.string.pause)
        msg.text = statusText
        msg.visibility = if (statusText == null) View.GONE else View.VISIBLE
    }

    private fun showOsd() {
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, OSD_MS)
        handler.removeCallbacks(tick)
        if (!live) tick.run()
    }

    private fun seekBy(ms: Long) {
        playback().seekTo(playback().positionMs + ms)
        showOsd()
    }

    private fun updateTime() {
        val d = playback().durationMs
        val p = playback().positionMs
        seek.max = (d / 1000).toInt()
        seek.progress = (p / 1000).toInt()
        time.text = clock(p, d)
    }

    private fun place() {
        val lp = box.layoutParams as FrameLayout.LayoutParams
        if (fullscreen) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.leftMargin = 0
            lp.topMargin = 0
        } else {
            val s = IntArray(2).also(slot::getLocationInWindow)
            val r = IntArray(2).also(root::getLocationInWindow)
            lp.width = slot.width
            lp.height = slot.height
            lp.leftMargin = s[0] - r[0]
            lp.topMargin = s[1] - r[1]
        }
        box.layoutParams = lp
    }

    /** Letterboxes the surface to the video's aspect ratio inside the box. */
    private fun fit() {
        val bw = box.width
        val bh = box.height
        if (bw == 0 || bh == 0) return
        val lp = surface.layoutParams
        if (bw.toLong() * videoH > bh.toLong() * videoW) {
            lp.height = bh
            lp.width = (bh.toLong() * videoW / videoH).toInt()
        } else {
            lp.width = bw
            lp.height = (bw.toLong() * videoH / videoW).toInt()
        }
        surface.layoutParams = lp
    }

    fun release() = handler.removeCallbacksAndMessages(null)

    private companion object {
        const val OSD_MS = 4_000L
        const val DISABLED_ALPHA = 0.5f

        fun clock(pos: Long, dur: Long) = "${hms(pos)} / ${hms(dur)}"

        fun hms(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}
