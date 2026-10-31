/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Shared state of Lumen Lens: what is on the YouTube screen now, the session feed, cached lookups. */
object LensState {
    @Volatile var service: LensService? = null
    @Volatile var screen: List<FeedItem> = emptyList()
    @Volatile var watching: FeedItem? = null
    @Volatile var dump: String = ""
    @Volatile var panelOpen = false

    private val p get() = Lumen.prefs
    var enabled: Boolean
        get() = p.getBoolean("lens_on", true)
        set(v) = p.edit().putBoolean("lens_on", v).apply()
    var record: Boolean
        get() = p.getBoolean("lens_record", true)
        set(v) = p.edit().putBoolean("lens_record", v).apply()
    var bubbleX: Int
        get() = p.getInt("lens_x", -1)
        set(v) = p.edit().putInt("lens_x", v).apply()
    var bubbleY: Int
        get() = p.getInt("lens_y", -1)
        set(v) = p.edit().putInt("lens_y", v).apply()

    private val sessionFile get() = File(Lumen.app.filesDir, "lumen/lens_session.json")
    private var sessionCache: FeedSession? = null
    private var savedAt = 0L

    val session: FeedSession
        get() = synchronized(this) {
            sessionCache ?: FeedSession.from(try {
                sessionFile.takeIf { it.exists() }?.readText()?.let(::JSONObject)
            } catch (e: Exception) {
                null
            }).also { sessionCache = it }
        }

    fun record(items: List<FeedItem>) = synchronized(this) {
        session.add(items, System.currentTimeMillis())
        if (System.currentTimeMillis() - savedAt > 15_000) saveSession()
    }

    fun saveSession() = synchronized(this) {
        savedAt = System.currentTimeMillis()
        try {
            sessionFile.parentFile?.mkdirs()
            sessionFile.writeText(session.toJson().toString())
        } catch (e: Exception) {
            L.w("lens", "session save: ${e.message}")
        }
    }

    fun clearSession() = synchronized(this) {
        sessionCache = FeedSession(System.currentTimeMillis())
        saveSession()
    }

    /** The accessibility service is switched on in the system settings. */
    fun isOn(ctx: Context): Boolean {
        if (service != null) return true
        val list = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { it.equals("${ctx.packageName}/${LensService::class.java.name}", true) || it.equals("${ctx.packageName}/.lumen.LensService", true) }
    }

    /** Hidden helper project that keeps the videos, summaries and channels looked at through the lens. */
    fun projectId(): String = synchronized(this) {
        val id = p.getString("lens_pid", "") ?: ""
        if (id.isNotEmpty() && Store.project(id) != null) return id
        val pr = Project(name = t("🫧 Линза: поверх YouTube", "🫧 Lens: over YouTube"), phrase = "YouTube", domain = t("лента", "feed"), queries = mutableListOf())
        pr.collect.commentsLimit = 100
        Store.save(pr)
        p.edit().putString("lens_pid", pr.id).apply()
        return pr.id
    }

    // --- lookups (blocking, call off the main thread) ---

    private val urls = ConcurrentHashMap<String, String>()

    /** Finds the watch link of a card by searching its title (the screen has no links). */
    fun resolve(item: FeedItem): Video? {
        if (item.url.isNotBlank()) return Video(id = YtQuery.videoIdFromUrl(item.url) ?: item.key, url = item.url, title = item.title, channel = item.channel, views = item.views)
        urls[item.key]?.let { u -> return Video(id = YtQuery.videoIdFromUrl(u) ?: item.key, url = u, title = item.title, channel = item.channel, views = item.views) }
        val queries = listOfNotNull("${item.title} ${item.channel}".trim(), item.title.takeIf { item.channel.isNotBlank() })
        for (q in queries) {
            val found = try {
                YouTube.search(listOf(q.take(150)), SearchFilters(limit = 12))
            } catch (e: Exception) {
                L.w("lens", "search: ${e.message}")
                emptyList()
            }
            val best = FeedMatch.best(item.title, item.channel, found.map { FeedMatch.Cand(it.title, it.channel, it.url) }) ?: continue
            val v = found.first { it.url == best.url }
            urls[item.key] = v.url
            session.setUrl(item.key, v.url)
            return v
        }
        return null
    }

    class Info(val video: Video, val related: List<FeedItem>)

    private val infos = ConcurrentHashMap<String, Info>()

    /** Watch page of a video: data for the card plus its related videos. */
    fun info(url: String): Info {
        infos[url]?.let { return it }
        Par.gate()
        val ex = ServiceList.YouTube.getStreamExtractor(url)
        ex.fetchPage()
        fun <T> safe(f: () -> T): T? = try {
            f()
        } catch (e: Exception) {
            null
        }
        val id = YtQuery.videoIdFromUrl(url) ?: url
        val v = Video(id = id, url = "https://www.youtube.com/watch?v=$id", query = "lens")
        safe { ex.name }?.let { v.title = it }
        safe { ex.uploaderName }?.let { v.channel = it }
        safe { ex.uploaderUrl }?.let { v.channelUrl = it }
        safe { ex.viewCount }?.takeIf { it >= 0 }?.let { v.views = it }
        safe { ex.length }?.takeIf { it > 0 }?.let { v.duration = it }
        safe { ex.uploadDate?.instant?.toEpochMilli() }?.let { v.uploadMs = it }
        safe { ex.thumbnails.maxByOrNull { it.width }?.url }?.let { v.thumb = it }
        val rel = (safe { ex.relatedItems?.items } ?: emptyList()).filterIsInstance<StreamInfoItem>().map { r ->
            FeedItem(r.name ?: "", r.uploaderName ?: "", r.viewCount, r.textualUploadDate ?: "", if (r.duration > 0) Fmt.duration(r.duration) else "",
                r.isShortFormContent, false, r.url ?: "")
        }.filter { it.title.isNotBlank() && it.url.isNotBlank() }
        return Info(v, rel).also { infos[url] = it }
    }
}

/** Links waiting for the full instant analysis; the collection service takes them one by one. */
object AnalysisQueue {
    private val file get() = File(Lumen.app.filesDir, "lumen/queue.json")
    private val lock = Any()

    fun all(): MutableList<QItem> = synchronized(lock) {
        QueueLogic.fromJson(try { file.takeIf { it.exists() }?.readText() } catch (e: Exception) { null })
    }

    private fun save(list: List<QItem>) = synchronized(lock) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(QueueLogic.toJson(list))
        } catch (e: Exception) {
            L.w("queue", "save: ${e.message}")
        }
    }

    private fun change(f: (MutableList<QItem>) -> Unit) = synchronized(lock) {
        val l = all()
        f(l)
        save(l)
    }

    /** Adds a link and starts the queue if nothing is running. */
    fun add(ctx: Context, url: String, title: String, mode: String = ""): Boolean {
        var ok = false
        change { ok = QueueLogic.add(it, QItem(url, title, mode, addedAt = System.currentTimeMillis())) }
        if (ok) {
            L.i("queue", "add $mode $url")
            pump(ctx)
        }
        return ok
    }

    fun pump(ctx: Context) {
        if (CollectState.running) return
        recover()
        val pid = claimNext() ?: return
        try {
            CollectService.startKind(ctx, pid, "quick")
        } catch (e: Exception) {
            // starting a foreground service from the background can be refused; the item waits for the next try
            L.w("queue", "start: ${e.message}")
            change { l -> l.firstOrNull { it.pid == pid }?.let { it.status = "wait"; it.pid = "" } }
        }
    }

    /** Takes the next waiting link: creates its project and marks it running. Returns the project id or null. */
    fun claimNext(): String? = synchronized(lock) {
        val l = all()
        while (true) {
            val it = QueueLogic.next(l) ?: break
            val link = YtLink.classify(it.url)
            if (link.type == "unknown" || (it.mode == "style" && link.type != "video")) {
                it.status = "error"
                it.note = t("не ссылка на видео, канал или плейлист", "not a video, channel or playlist link")
                continue
            }
            val p = QuickAnalysis.createProject(link, it.mode)
            it.status = "running"
            it.pid = p.id
            save(l)
            return p.id
        }
        save(l)
        null
    }

    fun finished(pid: String, ok: Boolean, note: String) = change { l ->
        l.firstOrNull { it.pid == pid && it.status == "running" }?.let {
            it.status = if (ok) "done" else "error"
            it.note = note.take(200)
        }
    }

    fun has(pid: String): Boolean = all().any { it.pid == pid }

    /** Nothing runs: items left "running" by a killed process go back to the queue. */
    fun recover() {
        if (CollectState.running) return
        change { l -> l.filter { it.status == "running" }.forEach { it.status = "wait"; it.pid = "" } }
    }

    fun clearFinished() = change { l -> l.removeAll { it.status == "done" || it.status == "error" } }

    fun remove(item: QItem) = change { l -> l.removeAll { it.url == item.url && it.mode == item.mode && it.addedAt == item.addedAt } }

    fun retry(item: QItem) = change { l -> l.firstOrNull { it.url == item.url && it.addedAt == item.addedAt }?.let { it.status = "wait"; it.pid = ""; it.note = "" } }

    fun statusLabel(s: String) = when (s) {
        "wait" -> t("⏳ в очереди", "⏳ queued")
        "running" -> t("⚙️ идёт", "⚙️ running")
        "done" -> t("✅ готово", "✅ done")
        else -> t("⚠️ ошибка", "⚠️ error")
    }

    fun modeLabel(m: String) = when (m) {
        "pains" -> t("боли", "pains")
        "style" -> t("стиль", "style")
        else -> t("полный анализ", "full analysis")
    }
}

/**
 * Accessibility service of Lumen Lens: shows a floating button over the YouTube app and reads the video cards
 * on its screen (titles, channels, views). Nothing leaves the device; only YouTube windows are read.
 */
class LensService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private val h = Handler(Looper.getMainLooper())
    private var bubble: View? = null
    private var badge: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: LensPanel? = null
    private var inYouTube = false
    private var hiddenUntilLeave = false
    @Volatile private var scanQueued = false
    @Volatile private var lastScan = 0L
    private val worker by lazy { android.os.HandlerThread("lens-scan").also { it.start() } }
    private val bgH by lazy { Handler(worker.looper) }

    val ui: Context get() = ContextThemeWrapper(this, org.schabi.newpipe.R.style.LumenTheme)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Lumen.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        LensState.service = this
        L.i("lens", "service connected")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !LensApps.isTransient(pkg, packageName)) {
            val yt = pkg in LensApps.YOUTUBE
            if (yt != inYouTube) {
                inYouTube = yt
                if (!yt) {
                    closePanel()
                    hiddenUntilLeave = false
                    LensState.watching = null
                    LensState.saveSession()
                }
                updateBubble()
            }
        }
        if (pkg in LensApps.YOUTUBE) {
            if (!inYouTube) {
                inYouTube = true
                updateBubble()
            }
            scheduleScan()
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        closePanel()
        try { worker.quitSafely() } catch (e: Exception) { /* not started */ }
        removeBubble()
        LensState.saveSession()
        if (LensState.service === this) LensState.service = null
    }

    // --- reading the screen ---

    private fun scheduleScan() {
        if (scanQueued) return
        scanQueued = true
        val wait = maxOf(0L, 700L - (System.currentTimeMillis() - lastScan))
        // reading the node tree takes many binder calls: off the main thread
        bgH.postDelayed({
            scanQueued = false
            lastScan = System.currentTimeMillis()
            try {
                scan()
            } catch (e: Exception) {
                L.w("lens", "scan: ${e.message}")
            }
        }, wait)
    }

    private fun scan() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() !in LensApps.YOUTUBE) return
        val items = LinkedHashMap<String, FeedItem>()
        val texts = mutableListOf<WatchPick.Node>()
        val dump = StringBuilder()
        var playerBottom = -1
        val screenH = resources.displayMetrics.heightPixels
        val r = Rect()
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count > 2500 || depth > 60) return
            count++
            n.getBoundsInScreen(r)
            val desc = n.contentDescription?.toString().orEmpty()
            val text = n.text?.toString().orEmpty()
            val id = n.viewIdResourceName.orEmpty()
            if (dump.length < 24_000 && (desc.isNotBlank() || text.isNotBlank())) {
                dump.append(depth).append(' ').append(id.substringAfter(":id/", "")).append(" [").append(r.top).append("] ")
                if (desc.isNotBlank()) dump.append("d=").append(desc.take(200)).append(' ')
                if (text.isNotBlank()) dump.append("t=").append(text.take(200))
                dump.append('\n')
            }
            if (playerBottom < 0 && id.contains("player", true) && r.height() > dp(120) && r.top < screenH / 2) playerBottom = r.bottom
            if (r.height() > 0) {
                FeedParse.parse(desc)?.let { items.putIfAbsent(it.key, it) }
                if (desc.isEmpty() && text.length in 2..300) texts += WatchPick.Node(text, r.top, r.bottom, n.isClickable)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        LensState.dump = dump.toString()
        val list = items.values.toList()
        LensState.screen = list
        LensState.watching = if (playerBottom > 0) WatchPick.title(texts, playerBottom)?.let { title ->
            val ch = WatchPick.channel(texts, playerBottom).orEmpty()
            val old = LensState.watching
            if (old != null && old.key == FeedParse.key(title)) old else FeedItem(title, ch)
        } else null
        if (LensState.record) LensState.record(list + listOfNotNull(LensState.watching))
        h.post {
            badge?.let { b ->
                val n = list.size
                b.text = if (n > 99) "99" else n.toString()
                b.visibility = if (n > 0) View.VISIBLE else View.GONE
            }
        }
    }

    // --- floating button ---

    private fun overlayType() = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    fun updateBubble() {
        val want = inYouTube && LensState.enabled && !hiddenUntilLeave && !LensState.panelOpen
        if (want && bubble == null) addBubble() else if (!want && bubble != null) removeBubble()
    }

    private fun addBubble() {
        val ctx = ui
        Palette.init(ctx)
        val size = dp(52)
        val frame = FrameLayout(ctx)
        val core = TextView(ctx).apply {
            text = "L"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Palette.ON_ACCENT)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Palette.ACCENT)
                setStroke(dp(2), 0x55FFFFFF)
            }
            elevation = dp(6).toFloat()
            contentDescription = t("Lumen: анализ экрана YouTube", "Lumen: analyse the YouTube screen")
        }
        frame.addView(core, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        val b = TextView(ctx).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFE53935.toInt())
            }
            elevation = dp(7).toFloat()
            visibility = View.GONE
        }
        frame.addView(b, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END))
        badge = b
        val total = size + dp(8)
        val dm = resources.displayMetrics
        val lp = WindowManager.LayoutParams(total, total, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = LensState.bubbleX.takeIf { it >= 0 } ?: (dm.widthPixels - total - dp(4))
            y = LensState.bubbleY.takeIf { it >= 0 } ?: (dm.heightPixels * 0.55).toInt()
        }
        bubbleLp = lp
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val longPress = Runnable {
            if (!dragging) {
                hiddenUntilLeave = true
                removeBubble()
                toast(t("Кнопка Lumen скрыта до выхода из YouTube", "Lumen button hidden until you leave YouTube"))
            }
        }
        frame.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    h.postDelayed(longPress, 700)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (!dragging && !LensPos.isTap(dx, dy, slop)) {
                        dragging = true
                        h.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(frame, lp) } catch (e: Exception) { /* gone */ }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(longPress)
                    if (dragging) {
                        val m = resources.displayMetrics
                        lp.x = LensPos.snapX(lp.x, total, m.widthPixels, dp(4))
                        lp.y = LensPos.clampY(lp.y, total, m.heightPixels, dp(48))
                        try { wm.updateViewLayout(frame, lp) } catch (e: Exception) { /* gone */ }
                        LensState.bubbleX = lp.x
                        LensState.bubbleY = lp.y
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        openPanel()
                    }
                }
            }
            true
        }
        try {
            wm.addView(frame, lp)
            bubble = frame
        } catch (e: Exception) {
            L.w("lens", "bubble: ${e.message}")
        }
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (e: Exception) { /* already gone */ } }
        bubble = null
        badge = null
    }

    // --- panel ---

    fun openPanel(tab: Int = -1) {
        if (panel != null) {
            if (tab >= 0) panel?.show(tab)
            return
        }
        LensState.panelOpen = true
        removeBubble()
        // a fresh look at the screen first, then the panel
        bgH.post {
            try {
                scan()
            } catch (e: Exception) {
                L.w("lens", "scan: ${e.message}")
            }
            h.post {
                if (panel != null || !LensState.panelOpen) return@post
                val p = LensPanel(this)
                panel = p
                try {
                    p.attach(wm, overlayType(), tab)
                } catch (e: Exception) {
                    L.e("lens", "panel", e)
                    panel = null
                    LensState.panelOpen = false
                    updateBubble()
                }
            }
        }
    }

    fun closePanel() {
        val p = panel
        panel = null
        if (p == null && !LensState.panelOpen) return
        LensState.panelOpen = false
        p?.detach(wm)
        LensState.saveSession()
        updateBubble()
    }

    /** The AI send review opens as a screen: the panel steps aside and comes back with the answer. */
    fun stepAside() = h.post { panel?.hideForReview(wm) }

    fun panelFor(): LensPanel? = panel

    /** Opens a Lumen screen over YouTube and closes the panel. */
    fun launch(i: Intent) {
        closePanel()
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(e.message ?: "error")
        }
    }

    companion object {
        /** Intent of the system screen where the service is switched on. */
        fun settingsIntent() = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** The sheet over YouTube: tabs Screen / Video / Feed / Queue. */
class LensPanel(private val svc: LensService) {
    private val ctx: Context = svc.ui
    private var root: FrameLayout? = null
    private var body: ScrollView? = null
    private var tabsHost: LinearLayout? = null
    private var tab = 0
    private var hiddenForReview = false

    // the selected video and what was loaded for it
    private var sel: FeedItem? = null
    private var selVideo: Video? = null
    private var resolving = false
    private var pending: ((Video) -> Unit)? = null
    private var resolveFailed = false
    private var summary: String? = null
    private var summaryBusy = ""
    private var related: List<FeedItem>? = null
    private var relatedBusy = false
    private var graphBusy = ""
    private var graph: Triple<List<RecNode>, List<Pair<Int, Int>>, Pair<DoubleArray, DoubleArray>>? = null
    private var screenAi: String? = null
    private var screenAiBusy = false
    private var feedAi: String? = null
    private var feedAiBusy = ""
    private var saveBusy = ""
    private val cancel = AtomicBoolean(false)

    fun attach(wm: WindowManager, type: Int, startTab: Int) {
        Palette.init(ctx)
        tab = if (startTab >= 0) startTab else 0
        val dm = ctx.resources.displayMetrics
        val r = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) svc.closePanel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        r.setBackgroundColor(0x66000000)
        r.setOnClickListener { svc.closePanel() }
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(Palette.SURFACE, 28f, ctx)
            isClickable = true
            elevation = ctx.dp(8).toFloat()
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(4), 0)
        }
        head.addView(text(ctx, "Lumen · " + t("поверх YouTube", "over YouTube"), Type.TITLE_M, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val s = ctx.dp(44)
        head.addView(iconBtn(ctx, "⟳") { svc.closePanel(); svc.openPanel(tab) }.apply { contentDescription = t("Обновить", "Refresh") }, LinearLayout.LayoutParams(s, s))
        head.addView(iconBtn(ctx, "⚙") { svc.launch(Intent(svc, LensActivity::class.java)) }, LinearLayout.LayoutParams(s, s))
        head.addView(iconBtn(ctx, "✕") { svc.closePanel() }.apply { contentDescription = t("Закрыть", "Close") }, LinearLayout.LayoutParams(s, s))
        sheet.addView(head)
        val th = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(4), ctx.dp(16), ctx.dp(4))
        }
        tabsHost = th
        sheet.addView(th)
        val sv = ScrollView(ctx)
        body = sv
        sheet.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        r.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (dm.heightPixels * 0.74).toInt(), Gravity.BOTTOM))
        root = r
        val lp = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        wm.addView(r, lp)
        r.isFocusableInTouchMode = true
        r.requestFocus()
        // the card the user is watching is the natural first choice
        if (sel == null) LensState.watching?.let { select(it, switch = false) }
        show(tab)
    }

    fun detach(wm: WindowManager) {
        cancel.set(true)
        root?.let { try { wm.removeView(it) } catch (e: Exception) { /* gone */ } }
        root = null
    }

    fun hideForReview(wm: WindowManager) {
        val r = root ?: return
        hiddenForReview = true
        r.visibility = View.GONE
        try { wm.updateViewLayout(r, (r.layoutParams as WindowManager.LayoutParams).apply { flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE }) } catch (e: Exception) { /* gone */ }
    }

    private fun comeBack() {
        val r = root ?: return
        if (!hiddenForReview) return
        hiddenForReview = false
        r.visibility = View.VISIBLE
        val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try { wm.updateViewLayout(r, (r.layoutParams as WindowManager.LayoutParams).apply { flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() }) } catch (e: Exception) { /* gone */ }
        r.requestFocus()
    }

    private val alive get() = root != null

    /** Runs [work] in the background and re-renders when it is done (if the panel is still open). */
    private fun <T> bg(work: () -> T, onError: (Throwable) -> Unit, onDone: (T) -> Unit) {
        Lumen.async(work, { e ->
            comeBack()
            if (alive) onError(e)
            L.w("lens", "task: ${e.message}")
        }) { r ->
            comeBack()
            if (alive) onDone(r)
        }
    }

    fun show(t0: Int) {
        tab = t0
        val th = tabsHost ?: return
        th.removeAllViews()
        th.addView(segBar(ctx, listOf(t("Экран", "Screen"), t("Видео", "Video"), t("Лента", "Feed"), t("Очередь", "Queue")), tab) { show(it) })
        render()
    }

    private fun render() {
        val sv = body ?: return
        val y = sv.scrollY
        val c = Col(ctx, 16)
        when (tab) {
            0 -> screenTab(c)
            1 -> videoTab(c)
            2 -> feedTab(c)
            else -> queueTab(c)
        }
        sv.removeAllViews()
        sv.addView(c.root)
        sv.post { sv.scrollTo(0, y) }
    }

    private fun row(c: Col, it: FeedItem, trailing: View? = null, onClick: () -> Unit) {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(ctx, roundBg(Palette.CARD, 12f, ctx), Palette.ON_SURFACE)
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(4), ctx.dp(8))
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(ctx, it.title, 14f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        val meta = it.meta()
        if (meta.isNotBlank()) col.addView(text(ctx, meta, 12f, color = Palette.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let { r.addView(it, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40))) }
        c.add(r, 6)
    }

    private fun queueBtn(it: FeedItem) = iconBtn(ctx, "＋", Palette.ACCENT) { enqueue(it, "") }.apply {
        contentDescription = t("В очередь на анализ", "Queue for analysis")
    }

    private fun clipLink(): YtLink.Link? = try {
        val cm = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val s = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(svc)?.toString().orEmpty()
        YtLink.classify(s).takeIf { it.type != "unknown" }
    } catch (e: Exception) {
        null
    }

    // --- Screen ---

    private fun screenTab(c: Col) {
        clipLink()?.let { link ->
            c.card {
                add(text(ctx, t("📋 В буфере ссылка: ", "📋 Link in clipboard: ") + link.type, 14f, bold = true), 0)
                muted(link.url)
                chips(listOf(
                    chip(ctx, t("Открыть здесь", "Open here")) { select(FeedItem(link.url, url = link.url), true) },
                    chip(ctx, t("➕ В очередь", "➕ Queue")) { enqueueUrl(link.url, link.url, "") },
                    chip(ctx, t("📊 Выбрать анализ", "📊 Choose analysis")) { openInLumen(link.url) }
                ))
            }
        }
        LensState.watching?.let { w ->
            c.add(text(ctx, t("▶ Сейчас открыто", "▶ Now open"), Type.LABEL, bold = true, color = Palette.MUTED), 8)
            row(c, w, queueBtn(w)) { select(w, true) }
        }
        val list = LensState.screen.filter { it.key != LensState.watching?.key }
        c.add(text(ctx, t("На экране: ", "On screen: ") + list.size, Type.LABEL, bold = true, color = Palette.MUTED), 12)
        if (list.isEmpty() && LensState.watching == null) {
            c.muted(t("Не вижу карточек видео. Пролистайте ленту или откройте видео и нажмите ⟳. " +
                "Если ничего не меняется, отправьте «🐞 Отчёт о разборе экрана» ниже.",
                "No video cards found. Scroll the feed or open a video and tap ⟳. If nothing changes, send the \"🐞 Screen parse report\" below."))
        }
        list.forEach { item -> row(c, item, queueBtn(item)) { select(item, true) } }
        if (list.isNotEmpty()) {
            c.chips(listOf(
                chip(ctx, t("➕ Всё в очередь (${list.size})", "➕ Queue all (${list.size})")) { enqueueAll(list) },
                chip(ctx, t("✨ AI: что на экране", "✨ AI: what is on screen")) { screenAi(list) }
            ))
        }
        if (screenAiBusy) c.working(t("AI смотрит на экран…", "AI looks at the screen…"))
        screenAi?.let { txt -> c.card { p(txt, 14f) } }
        c.add(text(ctx, t("🐞 Отчёт о разборе экрана", "🐞 Screen parse report"), 13f, color = Palette.ACCENT).apply {
            setPadding(0, ctx.dp(12), 0, ctx.dp(4))
            setOnClickListener {
                share("Lumen Lens dump", "Lumen Lens · " + Build.MODEL + " · Android " + Build.VERSION.RELEASE +
                    "\nparsed=${LensState.screen.size} watching=${LensState.watching?.title}\n\n" + LensState.dump.take(20_000))
            }
        }, 8)
    }

    // --- Video ---

    private fun select(item: FeedItem, switch: Boolean) {
        if (sel?.key != item.key || sel?.url != item.url) {
            sel = item
            pending = null
            selVideo = null
            summary = null
            related = null
            graph = null
            resolveFailed = false
            summaryBusy = ""
            graphBusy = ""
        }
        if (switch) show(1)
        ensureUrl()
    }

    private fun ensureUrl(then: ((Video) -> Unit)? = null) {
        val it = sel ?: return
        selVideo?.let { v -> then?.invoke(v); return }
        if (then != null) pending = then
        if (resolving) return
        resolving = true
        resolveFailed = false
        if (tab == 1) render()
        val key = it.key
        bg({ LensState.resolve(it) }, { resolving = false; resolveFailed = true; pending = null; if (tab == 1) render() }) { v ->
            resolving = false
            val next = pending
            pending = null
            if (sel?.key != key) return@bg
            if (v == null) resolveFailed = true else selVideo = v
            if (tab == 1) render()
            if (v != null) next?.invoke(v)
        }
    }

    private fun videoTab(c: Col) {
        val it = sel
        if (it == null) {
            c.muted(t("Выберите видео на вкладке «Экран» или «Лента».", "Pick a video on the Screen or Feed tab."))
            return
        }
        val v = selVideo
        c.add(text(ctx, v?.title?.ifBlank { null } ?: it.title, Type.TITLE_M, bold = true), 0)
        c.muted(listOf(v?.channel?.ifBlank { null } ?: it.channel, it.meta().substringAfter(" · ", "")).filter { s -> s.isNotBlank() }.joinToString(" · "))
        when {
            v != null -> c.muted("🔗 " + v.url)
            resolving -> c.working(t("Ищу ссылку на это видео…", "Looking for the link to this video…"))
            resolveFailed -> c.card {
                p(t("Не нашёл ссылку по названию. В YouTube нажмите «Поделиться → Копировать ссылку», затем ⟳ — ссылка из буфера появится на вкладке «Экран».",
                    "Could not find the link by title. In YouTube tap Share → Copy link, then ⟳ — the clipboard link shows up on the Screen tab."), 14f)
                button(t("Искать ещё раз", "Search again")) { ensureUrl() }
            }
        }
        c.chips(listOf(
            chip(ctx, t("✨ Сводка", "✨ Summary")) { summary() },
            chip(ctx, t("🔗 Похожие", "🔗 Similar")) { related() },
            chip(ctx, t("🕸 Граф", "🕸 Graph")) { miniGraph() },
            chip(ctx, t("📺 Канал", "📺 Channel")) { channel() },
            chip(ctx, t("➕ В очередь", "➕ Queue")) { enqueue(it, "") },
            chip(ctx, t("😣 Боли людей", "😣 Pains")) { enqueue(it, "pains") },
            chip(ctx, t("🎨 Стиль", "🎨 Style")) { enqueue(it, "style") },
            chip(ctx, t("📊 Все анализы…", "📊 All analyses…")) { ensureUrl { v -> openInLumen(v.url) } },
            chip(ctx, MediaLink.label(MediaAction.BACKGROUND)) { ensureUrl { v -> svc.closePanel(); svc.runMedia(v.url, MediaAction.BACKGROUND) } },
            chip(ctx, MediaLink.label(MediaAction.AUDIO)) { ensureUrl { v -> svc.closePanel(); svc.runMedia(v.url, MediaAction.AUDIO) } },
            chip(ctx, MediaLink.label(MediaAction.VIDEO)) { ensureUrl { v -> svc.closePanel(); svc.runMedia(v.url, MediaAction.VIDEO) } },
            chip(ctx, t("⧉ Копировать ссылку", "⧉ Copy link")) { ensureUrl { v -> copy(v.url) } }
        ), collapseAfter = 20)
        if (summaryBusy.isNotEmpty()) c.working(summaryBusy)
        summary?.let { s ->
            c.h2(t("Сводка", "Summary"))
            c.card { p(s, 14f) }
        }
        if (graphBusy.isNotEmpty()) c.working(graphBusy)
        graph?.let { (nodes, edges, xy) ->
            c.h2(t("Граф похожих", "Graph of similar"))
            val gv = GraphView(ctx)
            // pan and zoom the graph instead of scrolling the sheet
            gv.setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
            gv.onTap = { n -> select(FeedItem(n.title, n.channel, n.views, url = n.url), true) }
            c.add(gv, 8).layoutParams.height = ctx.dp(320)
            gv.post {
                gv.setGraph(nodes, edges, xy.first, xy.second)
                gv.colorBy = "channel"
            }
            c.muted(t("Узлов ${nodes.size} · связей ${edges.size}. Размер — как часто рекомендуют, цвет — канал. Нажмите на узел, чтобы открыть видео здесь.",
                "Nodes ${nodes.size} · edges ${edges.size}. Size = how often recommended, colour = channel. Tap a node to open it here."))
        }
        if (relatedBusy) c.working(t("Загружаю похожие…", "Loading similar…"))
        related?.let { list ->
            c.h2(t("Похожие (${list.size})", "Similar (${list.size})"))
            if (list.isEmpty()) c.muted(t("YouTube не вернул похожих.", "YouTube returned no similar videos."))
            list.forEach { r -> row(c, r, queueBtn(r)) { select(r, true) } }
            if (list.isNotEmpty()) c.chips(listOf(chip(ctx, t("➕ Все похожие в очередь", "➕ Queue all similar")) { enqueueAll(list) }))
        }
    }

    private fun summary() = ensureUrl { v0 ->
        if (Provider.active() == null) {
            noAi()
            return@ensureUrl
        }
        summaryBusy = t("Загружаю субтитры и комментарии…", "Loading subtitles and comments…")
        render()
        val pid = LensState.projectId()
        bg({
            val v = try { YouTube.single(v0.url) } catch (e: Exception) { v0 }
            Store.mergeVideos(pid, listOf(v))
            val p = Store.project(pid)!!
            val cfg = p.collect.copy(commentsLimit = 100, replies = false, channelStats = false)
            try {
                YouTube.collect(pid, p.phrase, v, cfg)
                Store.updateVideo(pid, v.id) { s -> QuickAnalysis.copyCollected(v, s) }
            } catch (e: Exception) {
                L.w("lens", "collect ${v.id}: ${e.message}")
            }
            Lumen.ui { if (alive) { summaryBusy = t("AI пишет сводку…", "AI writes the summary…"); render() } }
            val saved = Store.videos(pid)[v.id] ?: v
            AiTasks.videoSummary(p, saved).text
        }, { e -> summaryBusy = ""; summary = "⚠️ " + (e.message ?: e.javaClass.simpleName); render() }) { s ->
            summaryBusy = ""
            summary = s
            render()
        }
    }

    private fun related() = ensureUrl { v ->
        relatedBusy = true
        render()
        bg({ LensState.info(v.url) }, { e -> relatedBusy = false; related = emptyList(); svc.toast(e.message ?: "error"); render() }) { info ->
            relatedBusy = false
            selVideo = selVideo?.apply { if (channelUrl.isBlank()) channelUrl = info.video.channelUrl }
            related = info.related
            render()
        }
    }

    /** Seed + its related + the related of the top related: a small recommendation graph on the spot. */
    private fun miniGraph() = ensureUrl { v ->
        graphBusy = t("Строю граф похожих…", "Building the graph of similar…")
        render()
        bg({
            val seed = LensState.info(v.url)
            val nodes = LinkedHashMap<String, RecNode>()
            val edges = mutableListOf<Pair<String, String>>()
            fun node(it: FeedItem, depth: Int): String? {
                val id = YtQuery.videoIdFromUrl(it.url) ?: return null
                nodes.getOrPut(id) { RecNode(id = id, title = it.title, channel = it.channel, views = it.views, depth = depth) }
                return id
            }
            val sid = seed.video.id
            nodes[sid] = RecNode(id = sid, title = seed.video.title, channel = seed.video.channel, views = seed.video.views, seed = true)
            val first = seed.related.take(20)
            first.forEach { r -> node(r, 1)?.let { edges += sid to it } }
            first.take(5).forEachIndexed { i, r ->
                Lumen.ui { if (alive) { graphBusy = t("Строю граф похожих… ${i + 1}/5", "Building the graph… ${i + 1}/5"); render() } }
                val from = YtQuery.videoIdFromUrl(r.url) ?: return@forEachIndexed
                val sub = try { LensState.info(r.url).related.take(8) } catch (e: Exception) { emptyList() }
                sub.forEach { s -> node(s, 2)?.let { edges += from to it } }
            }
            val list = nodes.values.toList()
            val idx = list.withIndex().associate { it.value.id to it.index }
            val e = edges.mapNotNull { (a, b) -> val x = idx[a]; val y = idx[b]; if (x != null && y != null && x != y) x to y else null }.distinct()
            e.forEach { (_, b) -> list[b].hits++ }
            val pr = GraphAlgo.pageRank(list.size, e)
            list.forEachIndexed { i, n -> n.pr = pr[i] }
            Triple(list, e, GraphAlgo.layout(list.size, e))
        }, { e -> graphBusy = ""; svc.toast(e.message ?: "error"); render() }) { g ->
            graphBusy = ""
            graph = g
            render()
        }
    }

    private fun channel() = ensureUrl { v ->
        val known = selVideo?.channelUrl.orEmpty()
        val go = { url: String ->
            if (url.isBlank()) svc.toast(t("Канал не найден", "Channel not found"))
            else svc.launch(Intent(svc, ChannelActivity::class.java).putExtra("pid", LensState.projectId()).putExtra("url", url))
        }
        if (known.isNotBlank()) go(known) else {
            summaryBusy = t("Ищу канал…", "Looking for the channel…")
            render()
            bg({ LensState.info(v.url).video.channelUrl }, { e -> summaryBusy = ""; svc.toast(e.message ?: "error"); render() }) { url ->
                summaryBusy = ""
                go(url)
            }
        }
    }

    private fun openInLumen(url: String) = svc.launch(Intent(svc, LinkActivity::class.java).putExtra(Intent.EXTRA_TEXT, url))

    // --- Feed ---

    private fun feedTab(c: Col) {
        val s = LensState.session
        val st = s.stats()
        c.card {
            add(text(ctx, t("Лента за сессию", "Feed of the session"), Type.TITLE_M, bold = true), 0)
            p(t("Видео: ${st.videos} · каналов: ${st.channels} · Shorts: ${st.shorts} · эфиров: ${st.live} · повторов: ${st.repeated}",
                "Videos: ${st.videos} · channels: ${st.channels} · Shorts: ${st.shorts} · live: ${st.live} · shown again: ${st.repeated}"), 14f)
            if (st.medianViews >= 0) muted(t("Медиана просмотров: ", "Median views: ") + Fmt.num(st.medianViews))
            if (s.startedAt > 0) muted(t("С ", "Since ") + Fmt.dateTime(s.startedAt))
            check(t("Записывать ленту, пока открыт YouTube", "Record the feed while YouTube is open"), LensState.record) { LensState.record = it }
        }
        val top = s.topChannels(10)
        if (top.isNotEmpty()) {
            c.add(text(ctx, t("Чаще всего показывают", "Shown most often"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            c.chips(top.map { (name, n) -> chip(ctx, "$name · $n") })
        }
        c.chips(listOf(
            chip(ctx, t("✨ AI-разбор ленты", "✨ AI feed review")) { feedAi() },
            chip(ctx, t("💾 Сохранить в проект", "💾 Save as project")) { saveFeed() },
            chip(ctx, t("🗑 Очистить", "🗑 Clear")) {
                LensState.clearSession()
                feedAi = null
                render()
            }
        ))
        if (feedAiBusy.isNotEmpty()) c.working(feedAiBusy)
        feedAi?.let { txt -> c.card { p(txt, 14f) } }
        if (saveBusy.isNotEmpty()) c.working(saveBusy)
        val items = s.items.values.sortedByDescending { it.last }.take(60)
        if (items.isEmpty()) c.muted(t("Пока пусто: листайте YouTube, Lumen запомнит показанные видео.", "Empty so far: scroll YouTube and Lumen keeps the videos it shows."))
        else c.add(text(ctx, t("Последние", "Latest"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        items.forEach { seen -> row(c, seen.item, queueBtn(seen.item)) { select(seen.item, true) } }
    }

    private fun feedAi() {
        val prov = Provider.active() ?: return noAi()
        val s = LensState.session
        if (s.items.isEmpty()) return svc.toast(t("Лента пустая", "The feed is empty"))
        feedAiBusy = t("AI разбирает ленту…", "AI reviews the feed…")
        render()
        val pid = LensState.projectId()
        bg({
            val prompt = Prompts.get("lens_feed").replace("{data}", s.toPrompt()).replace("{lang}", if (Settings.lang == "en") "English" else "русский")
            val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, prompt, maxTokens = 3000, task = "lens_feed", pid = pid)
            Store.saveAi(pid, AiResult(sha1("lens_feed|" + prompt.hashCode()), "lens_feed", "feed", r.model, r.text, r.inTokens, r.outTokens))
            r.text
        }, { e -> feedAiBusy = ""; feedAi = "⚠️ " + (e.message ?: ""); render() }) { txt ->
            feedAiBusy = ""
            feedAi = txt
            render()
        }
    }

    private fun screenAi(list: List<FeedItem>) {
        val prov = Provider.active() ?: return noAi()
        screenAiBusy = true
        render()
        val data = FeedSession().also { it.add(list, 1) }.toPrompt(40)
        bg({
            val prompt = Prompts.get("lens_screen").replace("{data}", data).replace("{lang}", if (Settings.lang == "en") "English" else "русский")
            Llm.complete(prov, prov.fastModel, Prompts.SYSTEM, prompt, maxTokens = 1200, task = "lens_screen", pid = LensState.projectId()).text
        }, { e -> screenAiBusy = false; screenAi = "⚠️ " + (e.message ?: ""); render() }) { txt ->
            screenAiBusy = false
            screenAi = txt
            render()
        }
    }

    /** The session feed becomes a normal project (each card is found by its title). */
    private fun saveFeed() {
        val items = LensState.session.items.values.sortedByDescending { it.count * 1_000_000_000_000L + it.last }.map { it.item }.take(60)
        if (items.isEmpty()) return svc.toast(t("Лента пустая", "The feed is empty"))
        saveBusy = t("Ищу ссылки: 0/${items.size}", "Finding links: 0/${items.size}")
        render()
        cancel.set(false)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        bg({
            val vids = Par.map(items, cancel = cancel) { _, it ->
                LensState.resolve(it).also {
                    val n = done.incrementAndGet()
                    Lumen.ui { if (alive) { saveBusy = t("Ищу ссылки: $n/${items.size}", "Finding links: $n/${items.size}"); render() } }
                }
            }.filterNotNull().onEach { it.query = "lens feed" }
            if (vids.isEmpty()) throw IllegalStateException(t("Ни одно видео не нашлось поиском", "No video was found by search"))
            val p = Project(name = t("📱 Лента YouTube ", "📱 YouTube feed ") + Fmt.dateTime(System.currentTimeMillis()),
                phrase = t("лента YouTube", "YouTube feed"), queries = mutableListOf())
            Store.save(p)
            Store.mergeVideos(p.id, vids)
            p.id to vids.size
        }, { e -> saveBusy = ""; svc.toast(e.message ?: "error"); render() }) { (pid, n) ->
            saveBusy = ""
            svc.toast(t("Проект создан: $n видео", "Project created: $n videos"))
            svc.launch(Intent(svc, ProjectActivity::class.java).putExtra("id", pid))
        }
    }

    // --- Queue ---

    private fun queueTab(c: Col) {
        LensUi.queue(c, ctx, onOpen = { pid -> svc.launch(Intent(svc, ProjectActivity::class.java).putExtra("id", pid).putExtra("tab", "overview")) },
            onChange = { render() }, onStart = { AnalysisQueue.pump(svc); render() })
    }

    private fun enqueue(it: FeedItem, mode: String) {
        if (it.url.isNotBlank()) return enqueueUrl(it.url, it.title, mode)
        svc.toast(t("Ищу ссылку и ставлю в очередь…", "Finding the link and queueing…"))
        bg({ LensState.resolve(it) }, { e -> svc.toast(e.message ?: "error") }) { v ->
            if (v == null) svc.toast(t("Не нашёл ссылку: «${it.title.take(40)}»", "Link not found: \"${it.title.take(40)}\""))
            else enqueueUrl(v.url, it.title, mode)
        }
    }

    private fun enqueueUrl(url: String, title: String, mode: String) {
        val ok = AnalysisQueue.add(svc, url, title, mode)
        svc.toast(if (ok) t("В очереди: ", "Queued: ") + AnalysisQueue.modeLabel(mode) + " · " + title.take(40) else t("Уже в очереди", "Already queued"))
        if (tab == 3) render()
    }

    private fun enqueueAll(list: List<FeedItem>) {
        val take = list.take(30)
        svc.toast(t("Ставлю в очередь ${take.size} видео…", "Queueing ${take.size} videos…"))
        bg({ take.mapNotNull { LensState.resolve(it)?.let { v -> v to it.title } } }, { e -> svc.toast(e.message ?: "error") }) { found ->
            var n = 0
            found.forEach { (v, title) -> if (AnalysisQueue.add(svc, v.url, title)) n++ }
            svc.toast(t("Добавлено в очередь: $n из ${take.size}", "Queued: $n of ${take.size}"))
            if (tab == 3) render()
        }
    }

    private fun noAi() {
        svc.toast(t("Подключите AI в Настройках Lumen", "Connect AI in Lumen settings"))
        svc.launch(Intent(svc, SettingsActivity::class.java))
    }

    private fun copy(s: String) {
        try {
            (svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("YouTube", s))
            svc.toast(t("Ссылка скопирована", "Link copied"))
        } catch (e: Exception) {
            svc.toast(e.message ?: "error")
        }
    }

    private fun share(subject: String, body: String) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
        svc.launch(Intent.createChooser(i, subject))
    }
}

/** Pieces shared by the panel and the settings screen. */
object LensUi {
    fun queue(c: Col, ctx: Context, onOpen: (String) -> Unit, onChange: () -> Unit, onStart: () -> Unit) {
        val list = AnalysisQueue.all()
        val n = QueueLogic.counts(list)
        c.card {
            add(text(ctx, t("Очередь анализа", "Analysis queue"), Type.TITLE_M, bold = true), 0)
            p(t("В очереди ${n["wait"] ?: 0} · идёт ${n["running"] ?: 0} · готово ${n["done"] ?: 0} · ошибок ${n["error"] ?: 0}",
                "Queued ${n["wait"] ?: 0} · running ${n["running"] ?: 0} · done ${n["done"] ?: 0} · errors ${n["error"] ?: 0}"), 14f)
            muted(t("Каждая ссылка проходит полный быстрый анализ (сводка, похожие, граф каналов) по очереди, в фоне. Шаги настраиваются в Настройках → Быстрый анализ.",
                "Each link gets the full instant analysis (summary, similar, channel graph) one by one in the background. Steps: Settings → Instant analysis."))
            if (CollectState.running) muted("⚙️ " + CollectState.summary().take(160))
            chips(listOf(
                chip(ctx, t("▶ Запустить", "▶ Start")) { onStart() },
                chip(ctx, t("🧹 Убрать готовые", "🧹 Clear finished")) { AnalysisQueue.clearFinished(); onChange() }
            ))
        }
        if (list.isEmpty()) c.muted(t("Очередь пуста. Нажимайте «＋» у видео на экране YouTube.", "The queue is empty. Tap \"＋\" next to videos on the YouTube screen."))
        list.asReversed().forEach { q ->
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = ripple(ctx, roundBg(Palette.CARD, 12f, ctx), Palette.ON_SURFACE)
                setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(8))
            }
            r.addView(text(ctx, q.title.ifBlank { q.url }, 14f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            r.addView(text(ctx, AnalysisQueue.statusLabel(q.status) + " · " + AnalysisQueue.modeLabel(q.mode) +
                (if (q.note.isNotBlank()) " · " + q.note else ""), 12f, color = Palette.MUTED))
            r.setOnClickListener {
                when {
                    q.pid.isNotBlank() && Store.project(q.pid) != null -> onOpen(q.pid)
                    q.status == "error" -> { AnalysisQueue.retry(q); onChange() }
                    else -> Unit
                }
            }
            r.setOnLongClickListener {
                AnalysisQueue.remove(q)
                onChange()
                true
            }
            c.add(r, 6)
        }
        if (list.isNotEmpty()) c.muted(t("Нажмите — открыть проект (ошибку — повторить), удерживайте — убрать.", "Tap to open the project (an error: retry), hold to remove."))
    }
}

/** Setup screen of the floating button: switch on the service, options, queue and feed. */
class LensActivity : LumenBase() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val c = Col(this)
        val on = LensState.isOn(this)
        c.card {
            add(text(ctx, t("🫧 Кнопка Lumen поверх YouTube", "🫧 Lumen button over YouTube"), Type.TITLE_M, bold = true), 0)
            p(t("Пока открыт YouTube, на экране висит кнопка «L». Нажмите её: Lumen покажет, какие видео сейчас на экране, " +
                "сделает сводку, найдёт похожие, построит мини-граф, поставит видео в очередь на полный анализ и разберёт вашу ленту.",
                "While YouTube is open, an \"L\" button floats on the screen. Tap it: Lumen shows the videos on screen, " +
                    "summarises, finds similar ones, draws a mini graph, queues videos for the full analysis and reviews your feed."), 14f)
            add(text(ctx, if (on) t("✅ Служба включена", "✅ Service is on") else t("⛔ Служба выключена", "⛔ Service is off"), 15f, bold = true,
                color = if (on) Palette.OK else Palette.BAD), 12)
            if (!on) {
                p(t("Включите «Lumen: кнопка поверх YouTube» в Настройки → Спецвозможности → Установленные приложения.",
                    "Turn on \"Lumen: button over YouTube\" in Settings → Accessibility → Installed apps."), 14f)
                muted(t("Если переключатель серый (Android 13+): Настройки → Приложения → Lumen → ⋮ → «Разрешить ограниченные настройки», затем снова сюда.",
                    "If the switch is greyed out (Android 13+): Settings → Apps → Lumen → ⋮ → \"Allow restricted settings\", then come back."))
            }
            button(if (on) t("Настройки спецвозможностей", "Accessibility settings") else t("Включить", "Turn on"), primary = !on) {
                try { startActivity(LensService.settingsIntent()) } catch (e: Exception) { toast(e.message ?: "") }
            }
            if (!on) button(t("О приложении Lumen (ограниченные настройки)", "Lumen app info (restricted settings)")) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                } catch (e: Exception) { toast(e.message ?: "") }
            }
            if (on) button(t("Открыть YouTube", "Open YouTube"), primary = true) {
                val i = LensApps.YOUTUBE.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) }
                if (i != null) startActivity(i) else openUrl("https://www.youtube.com")
            }
        }
        c.card {
            add(text(ctx, t("Настройки", "Options"), Type.TITLE_M, bold = true), 0)
            check(t("Показывать кнопку в YouTube", "Show the button in YouTube"), LensState.enabled) {
                LensState.enabled = it
                LensState.service?.updateBubble()
            }
            check(t("Записывать ленту (что YouTube показывает)", "Record the feed (what YouTube shows)"), LensState.record) { LensState.record = it }
            muted(t("Перетащите кнопку к любому краю. Долгое нажатие прячет её до выхода из YouTube.",
                "Drag the button to any edge. A long press hides it until you leave YouTube."))
        }
        val st = LensState.session.stats()
        c.card {
            add(text(ctx, t("Лента за сессию", "Feed of the session"), Type.TITLE_M, bold = true), 0)
            p(t("Видео: ${st.videos} · каналов: ${st.channels} · Shorts: ${st.shorts}", "Videos: ${st.videos} · channels: ${st.channels} · Shorts: ${st.shorts}"), 14f)
            row(
                btn(ctx, t("Очистить", "Clear")) { LensState.clearSession(); render() },
                btn(ctx, t("Проект «Линза»", "Lens project")) {
                    startActivity(Intent(this@LensActivity, ProjectActivity::class.java).putExtra("id", LensState.projectId()))
                }
            )
        }
        LensUi.queue(c, this, onOpen = { pid -> startActivity(Intent(this, ProjectActivity::class.java).putExtra("id", pid).putExtra("tab", "overview")) },
            onChange = { render() }, onStart = { AnalysisQueue.pump(this); render() })
        c.card {
            add(text(ctx, t("Приватность", "Privacy"), Type.TITLE_M, bold = true), 0)
            muted(t("Lumen читает только окна приложения YouTube: названия, каналы и просмотры карточек. Данные остаются на устройстве. " +
                "В AI уходит только то, что вы запустили сами (сводка, разбор ленты), и вы видите это перед отправкой.",
                "Lumen reads only YouTube app windows: card titles, channels and views. Data stays on the device. " +
                    "Only what you start yourself (summary, feed review) goes to the AI, and you see it before sending."))
        }
        page(t("Поверх YouTube", "Over YouTube"), c.root, keepScroll = true)
    }

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, LensActivity::class.java))
    }
}
