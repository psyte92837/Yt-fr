/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * A look into a YouTube video without leaving the sheet (Mix, Clips): frames across the video
 * (storyboard), a low-quality mini player from any frame, ⏪ ⏩; [posMs] gives the moment to cut at.
 */
class MiniLook(private val a: android.app.Activity) {
    private val looks = HashMap<String, StudioCutsLoad.Look>()
    private var player: com.google.android.exoplayer2.ExoPlayer? = null
    private var current = ""
    private var started = false
    private val surface = android.view.TextureView(a)
    private val frame = FrameLayout(a).apply {
        setBackgroundColor(0xFF000000.toInt())
        visibility = View.GONE
        addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        setOnClickListener { player?.let { p -> p.playWhenReady = !p.playWhenReady } }
    }
    private val strip = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    private val stripScroll = android.widget.HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        addView(strip)
    }
    private val state = text(a, "", 12f, color = Palette.MUTED)
    private val playBtn = btn(a, "▶", style = BtnStyle.TONAL) { toggle() }
    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (current.isNotEmpty() && started && p.duration > 0) {
                state.text = StudioCuts.stamp(p.currentPosition) + " / " + StudioCuts.stamp(p.duration)
                playBtn.text = if (p.playWhenReady) "⏸" else "▶"
                bar.update(p.currentPosition, p.duration)
            }
            frame.postDelayed(this, 250)
        }
    }
    private val bar = MarkBar()
    val view: LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        addView(stripScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(4) })
        addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(200)).apply { topMargin = a.dp(4) })
        addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(28)).apply { topMargin = a.dp(2) })
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(playBtn)
            addView(state, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = a.dp(8) })
            addView(btn(a, "⏪ 10", style = BtnStyle.TEXT) { seek(-10_000) })
            addView(btn(a, "10 ⏩", style = BtnStyle.TEXT) { seek(10_000) })
        })
    }

    /** Your marks on the video (from–to, ms): drawn on the bar under the player; [dur] — length when the player has none yet. */
    var marks: List<Pair<Long, Long>> = emptyList()
        set(v) {
            field = v
            bar.visibility = if (started || v.isNotEmpty()) View.VISIBLE else View.GONE
            bar.invalidate()
        }
    var dur: Long = 0

    /** Playhead, length and the marks; a tap or drag seeks. */
    private inner class MarkBar : View(a) {
        private var pos = -1L
        private var len = 0L
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        init {
            visibility = View.GONE
            contentDescription = t("Полоса видео с вашими отметками", "Video bar with your marks")
        }

        fun update(p: Long, d: Long) {
            pos = p
            len = d
            visibility = View.VISIBLE
            invalidate()
        }

        private fun total() = len.takeIf { it > 0 } ?: dur.takeIf { it > 0 } ?: marks.maxOfOrNull { it.second } ?: 0L

        override fun onDraw(c: android.graphics.Canvas) {
            val d = total()
            val pad = a.dp(8).toFloat()
            val w = width.toFloat() - 2 * pad
            val df = d.toFloat()
            val cy = height / 2f
            paint.color = Palette.OUTLINE
            c.drawRoundRect(pad, cy - a.dp(3), pad + w, cy + a.dp(3), a.dp(3).toFloat(), a.dp(3).toFloat(), paint)
            if (d <= 0) return
            paint.color = Palette.ACCENT
            for ((x, y) in marks) {
                val l = pad + w * x.toFloat() / df
                val r = maxOf(l + a.dp(3), pad + w * y.toFloat() / df)
                c.drawRoundRect(l, cy - a.dp(7), r, cy + a.dp(7), a.dp(2).toFloat(), a.dp(2).toFloat(), paint)
            }
            if (pos >= 0) {
                paint.color = 0xFFE53935.toInt()
                val x = pad + w * pos.toFloat() / df
                c.drawRect(x - a.dp(1), 0f, x + a.dp(1), height.toFloat(), paint)
            }
        }

        override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
            val d = total()
            if (d <= 0) return false
            if (e.action == android.view.MotionEvent.ACTION_DOWN || e.action == android.view.MotionEvent.ACTION_MOVE) {
                val pad = a.dp(8).toFloat()
                val at = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * d
                parent?.requestDisallowInterceptTouchEvent(true)
                if (started) player?.seekTo(at.toLong()) else play(at.toLong())
                return true
            }
            return true
        }
    }

    private fun seek(d: Long) {
        val p = player ?: return
        if (!started) return
        p.seekTo((p.currentPosition + d).coerceIn(0, if (p.duration > 0) maxOf(0, p.duration - 500) else Long.MAX_VALUE))
    }

    private fun toggle() {
        val p = player
        if (started && p != null) p.playWhenReady = !p.playWhenReady else play(0)
    }

    private fun exo(): com.google.android.exoplayer2.ExoPlayer = player ?: com.google.android.exoplayer2.ExoPlayer.Builder(a).build().also { p ->
        player = p
        p.setVideoTextureView(surface)
        p.addListener(object : com.google.android.exoplayer2.Player.Listener {
            override fun onVideoSizeChanged(size: com.google.android.exoplayer2.video.VideoSize) {
                // keep the shape of the video inside the frame
                if (size.width <= 0 || size.height <= 0) return
                val fw = frame.width.takeIf { it > 0 } ?: return
                val fh = frame.height
                val vh = size.height / size.pixelWidthHeightRatio.coerceAtLeast(0.1f)
                val k = minOf(fw.toFloat() / size.width, fh.toFloat() / vh)
                surface.layoutParams = FrameLayout.LayoutParams((size.width * k).toInt(), (vh * k).toInt(), Gravity.CENTER)
            }

            override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                state.text = t("Не играет: ", "Cannot play: ") + (error.message ?: error.errorCodeName)
            }
        })
        frame.post(tick)
    }

    /** Playback speed of the mini player (the feed: ×1, ×1.5, ×2). */
    var speed = 1f
        set(v) {
            field = v
            player?.setPlaybackSpeed(v)
        }

    /** Opens the look at [url]: the frames strip first, the video only on a tap ([auto]: plays at once from [fromMs], or the middle when −1). */
    fun open(url: String, auto: Boolean = false, fromMs: Long = -1) {
        marks = emptyList()
        dur = 0
        stop()
        current = url
        strip.removeAllViews()
        playBtn.text = "▶"
        fun go(lk: StudioCutsLoad.Look) {
            show(lk)
            if (auto) play(if (fromMs >= 0) fromMs else lk.frames.getOrNull(lk.frames.size / 2)?.first ?: 0)
        }
        looks[url]?.let { return go(it) }
        state.text = t("Загрузка кадров…", "Loading frames…")
        Lumen.async({ StudioCutsLoad.look(url) }, { e ->
            if (current == url) state.text = (e.message ?: e.javaClass.simpleName).take(160)
        }) { lk ->
            looks[url] = lk
            if (current == url && !a.isDestroyed) go(lk)
        }
    }

    private fun show(lk: StudioCutsLoad.Look) {
        strip.removeAllViews()
        val h = a.dp(64)
        for ((ms, bmp) in lk.frames) {
            val w = (h.toLong() * bmp.width / bmp.height.coerceAtLeast(1)).toInt()
            strip.addView(FrameLayout(a).apply {
                addView(ImageView(a).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(bmp)
                }, FrameLayout.LayoutParams(w, h))
                addView(text(a, StudioCuts.stamp(ms), 10f, bold = true, color = 0xFFFFFFFF.toInt()).apply {
                    setShadowLayer(3f, 0f, 1f, 0xFF000000.toInt())
                    setPadding(a.dp(3), 0, a.dp(3), a.dp(1))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
                contentDescription = t("Смотреть с ", "Watch from ") + StudioCuts.stamp(ms)
                setOnClickListener { play(ms) }
            }, LinearLayout.LayoutParams(w, h).apply { rightMargin = a.dp(3) })
        }
        stripScroll.visibility = if (lk.frames.isEmpty()) View.GONE else View.VISIBLE
        state.text = when {
            lk.stream.isEmpty() -> t("Только кадры: поток для просмотра не найден", "Frames only: no stream to watch")
            lk.frames.isEmpty() -> t("Кадров нет — ▶ включит видео", "No frames — ▶ plays the video")
            else -> t("Кадры по всему видео. Нажмите на кадр — видео с этого места", "Frames across the video. Tap a frame to watch from there")
        }
    }

    /** Plays the video from [fromMs] in the mini player. */
    fun play(fromMs: Long) {
        val lk = looks[current]?.takeIf { it.stream.isNotEmpty() } ?: return
        val src = com.google.android.exoplayer2.source.ProgressiveMediaSource.Factory(StudioCutsLoad.dataSource()).createMediaSource(com.google.android.exoplayer2.MediaItem.fromUri(lk.stream))
        frame.visibility = View.VISIBLE
        state.text = t("Загрузка…", "Loading…")
        val p = exo()
        p.setMediaSource(src)
        p.prepare()
        if (fromMs > 0) p.seekTo(fromMs)
        p.setPlaybackSpeed(speed)
        p.playWhenReady = true
        started = true
        playBtn.text = "⏸"
    }

    /** Position of the playing video, ms; −1 when nothing plays. */
    fun posMs(): Long = player?.takeIf { started && current.isNotEmpty() }?.currentPosition ?: -1

    /** Length of the playing video, ms; 0 when not known. */
    fun durMs(): Long = player?.takeIf { started }?.duration?.takeIf { it > 0 } ?: 0

    fun stop() {
        current = ""
        started = false
        frame.visibility = View.GONE
        bar.visibility = if (marks.isNotEmpty()) View.VISIBLE else View.GONE
        player?.stop()
    }

    fun release() {
        stop()
        frame.removeCallbacks(tick)
        player?.release()
        player = null
    }
}
