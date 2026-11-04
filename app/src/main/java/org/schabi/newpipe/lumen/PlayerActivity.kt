/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter
import com.google.android.exoplayer2.video.VideoSize
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import org.schabi.newpipe.player.helper.PlayerDataSource
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.mediaitem.MediaItemTag
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.ListHelper
import org.schabi.newpipe.util.NavigationHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "▶ Player": YouTube search in the Lumen style. Quick filters (period, year, views, comments, length, Shorts, order),
 * an optional AI filter by topic and criteria (what it dropped can be opened and brought back), a compact list with
 * thumbnails (watch here, listen in the background, queue, window, download…), past searches cached and deleted after
 * N days, "Continue watching", and a built-in player on NewPipe's stream resolver (quality, speed, double-tap seek,
 * full screen, next video; leaving the app goes on as sound in the background).
 */
class PlayerActivity : LumenBase() {
    companion object {
        private const val REQ_VOICE = 7461

        fun open(ctx: Context, url: String = "", query: String = "") = ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
            if (url.isNotBlank()) putExtra("url", url)
            if (query.isNotBlank()) putExtra("q", query)
            if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private val ctx: Context get() = this

    // ---------- search state ----------
    private var filter = PFilter()
    private var query = ""
    private var search: PSearch? = null
    private var engine: PlayerSearch? = null
    private var fromCache = false
    private var busy = ""
    private var searchErr = ""
    private var aiNote = ""
    private var showDropped = false
    private var cancel = AtomicBoolean(false)
    private var gen = 0
    private var loadingMore = false
    private var history: List<PWatch> = emptyList()

    // ---------- views kept between renders ----------
    private val body by lazy { Col(this, 12) }
    private val video by lazy { VideoBox() }
    private lateinit var searchEdit: EditText
    private lateinit var searchRow: LinearLayout
    private val suggestBox by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
    private val chipsHolder by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
    private var statusView: TextView? = null
    private var scroll: ScrollView? = null
    private var appBarView: View? = null
    private var suggestGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filter = PlayerStore.filter
        // NewPipe's player service: bound (if it runs) so we know when sound plays in the background
        runCatching { PlayerHolder.getInstance().tryBindIfNeeded(applicationContext) }
        buildSearchRow()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(video.frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, video.portraitHeight()))
        }
        video.frame.visibility = View.GONE
        page(t("▶ Плеер", "▶ Player"), body.root, actions = listOf(
            "🔍" to { focusSearch() },
            "⋮" to { menu() }
        ), header = header)
        scroll = body.root.parent as? ScrollView
        appBarView = (header.parent as? ViewGroup)?.getChildAt(0)
        scroll?.setOnScrollChangeListener { _, _, y, _, _ ->
            val sv = scroll ?: return@setOnScrollChangeListener
            val child = sv.getChildAt(0) ?: return@setOnScrollChangeListener
            if (child.height - (y + sv.height) < dp(700)) loadMore(auto = true)
        }
        history = PlayerStore.history()
        render()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        i ?: return
        i.getStringExtra("url")?.takeIf { it.isNotBlank() }?.let { u ->
            YtQuery.videoIdFromUrl(u)?.let { id -> video.play(PItem(id, ""), PlayerStore.resumeOf(id)) }
        }
        i.getStringExtra("q")?.takeIf { it.isNotBlank() }?.let { q ->
            searchEdit.setText(q)
            go(q)
        }
    }

    override fun onStop() {
        video.leaving()
        super.onStop()
    }

    override fun onDestroy() {
        cancel.set(true)
        video.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            video.full -> video.exitFull()
            suggestBox.childCount > 0 -> hideSuggestions()
            else -> super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // turning the phone opens the video on the whole screen, turning back closes it
        val land = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land && video.item != null && !video.full) video.enterFull(lock = false)
        else if (!land && video.full && !video.locked) video.exitFull()
        else video.fit()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (said.isNotBlank()) {
                searchEdit.setText(said)
                go(said)
            }
        }
    }

    // ---------- labels ----------

    private fun ago(ms: Long): String {
        val (n, u) = PlayerCore.ago(ms, System.currentTimeMillis()) ?: return ""
        val unit = when (u) {
            "min" -> t("мин", "min")
            "h" -> t("ч", "h")
            "d" -> t("дн.", "d")
            "w" -> t("нед.", "w")
            "mo" -> t("мес.", "mo")
            else -> t("г.", "y")
        }
        return "$n $unit " + t("назад", "ago")
    }

    private fun meta(p: PItem): String = listOfNotNull(
        p.channel.takeIf { it.isNotBlank() },
        p.views.takeIf { it >= 0 }?.let { PlayerCore.count(it) + t(" просм.", " views") },
        ago(p.uploadMs).ifBlank { p.uploadText }.takeIf { it.isNotBlank() },
        p.comments.takeIf { it >= 0 }?.let { "💬 " + PlayerCore.count(it.toLong()) }
    ).joinToString(" · ")

    private fun periodLabel(f: PFilter): String {
        val y = PlayerCore.year(f)
        if (y > 0) return y.toString()
        return when (f.period) {
            "today" -> t("Сегодня", "Today")
            "week" -> t("Неделя", "Week")
            "month" -> t("Месяц", "Month")
            "year" -> t("Год", "Year")
            else -> t("Любое время", "Any time")
        }
    }

    private fun lengthLabel(l: String) = when (l) {
        "short" -> t("< 4 мин", "< 4 min")
        "medium" -> t("4–20 мин", "4–20 min")
        "long" -> t("> 20 мин", "> 20 min")
        else -> t("Любая длина", "Any length")
    }

    private fun sortLabel(s: String) = when (s) {
        "date" -> t("Сначала новые", "Newest first")
        "views" -> t("Популярные", "Most viewed")
        else -> t("По релевантности", "Relevance")
    }

    private fun shortsLabel(s: String) = when (s) {
        "no" -> t("Без Shorts", "No Shorts")
        "only" -> t("Только Shorts", "Shorts only")
        else -> "Shorts"
    }

    private fun aiLabel(f: PFilter): String {
        val parts = mutableListOf<String>()
        PlayerCore.domain(f.domain)?.let { parts += it.emoji + " " + t(it.ru, it.en) }
        if (f.rules.isNotEmpty()) parts += "+" + f.rules.size
        if (f.custom.isNotBlank()) parts += "✎"
        return parts.joinToString(" ")
    }

    /** One line about the filters of a search (for the past searches). */
    private fun filterLine(f: PFilter): String = listOfNotNull(
        periodLabel(f).takeIf { f.period != "any" },
        f.views.takeIf { it > 0 }?.let { "≥" + PlayerCore.count(it) + t(" просм.", " views") },
        f.comments.takeIf { it > 0 }?.let { "💬≥" + it },
        lengthLabel(f.length).takeIf { f.length != "any" },
        shortsLabel(f.shorts).takeIf { f.shorts != "any" },
        sortLabel(f.sort).takeIf { f.sort != "relevance" },
        ("🧠 " + aiLabel(f)).takeIf { PlayerCore.aiOn(f) }
    ).joinToString(", ")

    // ---------- search bar ----------

    private fun buildSearchRow() {
        searchEdit = EditText(this).apply {
            hint = t("Найти на YouTube или вставить ссылку", "Search YouTube or paste a link")
            setHintTextColor(Palette.MUTED)
            setTextColor(Palette.ON_SURFACE)
            textSize = sp(16f)
            background = null
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(4), 0, dp(4), 0)
            contentDescription = t("Что искать", "What to search")
        }
        searchEdit.onEnter { go(it) }
        val clear = smallBtn("✕", t("Очистить", "Clear"), Palette.MUTED) {
            searchEdit.setText("")
            searchEdit.requestFocus()
            showKeyboard()
        }
        clear.visibility = View.GONE
        val mic = smallBtn("🎤", t("Сказать голосом", "Say it"), Palette.MUTED) { voice() }
        searchEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s?.toString().orEmpty()
                clear.visibility = if (v.isEmpty()) View.GONE else View.VISIBLE
                mic.visibility = if (v.isEmpty()) View.VISIBLE else View.GONE
                if (searchEdit.hasFocus()) suggest(v)
            }
        })
        searchEdit.setOnFocusChangeListener { _, has -> if (has) suggest(searchEdit.text.toString()) }
        searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundBg(Palette.CARD, 28f, this@PlayerActivity)
            minimumHeight = dp(56)
            setPadding(dp(14), 0, dp(4), 0)
            addView(ImageView(this@PlayerActivity).apply { setImageDrawable(IconDrawable(Ic.SEARCH, Palette.MUTED)) }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(searchEdit, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
            addView(clear, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(mic, LinearLayout.LayoutParams(dp(44), dp(44)))
            setOnClickListener { focusSearch() }
        }
    }

    private fun smallBtn(glyph: String, label: String, color: Int = Palette.ON_SURFACE, size: Float = 18f, onClick: () -> Unit): TextView =
        text(this, glyph, size, color = color).apply {
            gravity = Gravity.CENTER
            contentDescription = label
            background = ripple(this@PlayerActivity, roundBg(0x00000000, 22f, this@PlayerActivity), Palette.ON_SURFACE)
            setOnClickListener { onClick() }
        }

    private fun focusSearch() {
        scroll?.smoothScrollTo(0, 0)
        searchEdit.requestFocus()
        searchEdit.setSelection(searchEdit.text.length)
        showKeyboard()
    }

    private fun showKeyboard() {
        searchEdit.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchEdit, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchEdit.windowToken, 0)
        searchEdit.clearFocus()
    }

    private fun voice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, t("Что найти на YouTube?", "What to find on YouTube?"))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_VOICE)
        } catch (e: Exception) {
            toast(t("Голосовой ввод недоступен на этом телефоне", "Voice input is not available on this phone"))
        }
    }

    /** Past queries at once, YouTube suggestions a moment later. */
    private fun suggest(text: String) {
        val g = ++suggestGen
        val past = PlayerCore.recent(PlayerStore.searches(), text, if (text.isBlank()) 6 else 3)
        fill(past, emptyList())
        if (text.isBlank()) return
        searchEdit.postDelayed({
            if (g != suggestGen) return@postDelayed
            Lumen.async({ PlayerSearch.suggestions(text) }, {}) { s -> if (g == suggestGen && searchEdit.hasFocus()) fill(past, s.filter { x -> past.none { it.equals(x, true) } }) }
        }, 250)
    }

    private fun fill(past: List<String>, yt: List<String>) {
        suggestBox.removeAllViews()
        if (!searchEdit.hasFocus()) return
        fun line(icon: String, s: String) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            background = ripple(this@PlayerActivity, roundBg(0x00000000, 12f, this@PlayerActivity), Palette.ON_SURFACE)
            setPadding(dp(12), 0, 0, 0)
            addView(text(this@PlayerActivity, icon, 15f, color = Palette.MUTED), LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(text(this@PlayerActivity, s, 15f).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(smallBtn("↖", t("Вставить в строку", "Put into the field"), Palette.MUTED) {
                searchEdit.setText("$s ")
                searchEdit.setSelection(searchEdit.text.length)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            contentDescription = s
            setOnClickListener {
                searchEdit.setText(s)
                go(s)
            }
        }
        past.forEach { suggestBox.addView(line("🕘", it)) }
        yt.forEach { suggestBox.addView(line("🔍", it)) }
    }

    private fun hideSuggestions() {
        suggestGen++
        suggestBox.removeAllViews()
    }

    // ---------- filter chips ----------

    private fun chipsRow(): View {
        val f = filter
        val list = mutableListOf<View>()
        if (PlayerCore.active(f) > 0) list += chip(this, t("✕ Сбросить", "✕ Reset")) { setFilter(PFilter()) }
        list += chip(this, "📅 " + periodLabel(f) + " ▾", f.period != "any") { periodSheet() }
        list += chip(this, if (f.views > 0) "👁 ≥" + PlayerCore.count(f.views) + " ▾" else t("👁 Просмотры ▾", "👁 Views ▾"), f.views > 0) { viewsSheet() }
        list += chip(this, if (f.comments > 0) "💬 ≥" + f.comments + " ▾" else t("💬 Комментарии ▾", "💬 Comments ▾"), f.comments > 0) { commentsSheet() }
        list += chip(this, "⏱ " + lengthLabel(f.length) + " ▾", f.length != "any") { lengthSheet() }
        list += chip(this, shortsLabel(f.shorts) + " ▾", f.shorts != "any") { shortsSheet() }
        list += chip(this, "↕ " + sortLabel(f.sort) + " ▾", f.sort != "relevance") { sortSheet() }
        list += chip(this, if (PlayerCore.aiOn(f)) "🧠 " + aiLabel(f) + " ▾" else t("🧠 Тема и критерии (ИИ) ▾", "🧠 Topic & criteria (AI) ▾"), PlayerCore.aiOn(f)) { aiSheet() }
        return chipScroller(this, list)
    }

    private fun refreshChips() {
        chipsHolder.removeAllViews()
        chipsHolder.addView(chipsRow())
    }

    /** New filters: the list is searched again (or only judged again when just the AI criteria changed). */
    private fun setFilter(f: PFilter) {
        if (f == filter) return
        val old = filter
        filter = f
        PlayerStore.filter = f
        refreshChips()
        val s = search ?: return render()
        if (PlayerCore.plain(old) == PlayerCore.plain(f) && s.items.isNotEmpty()) {
            // same videos, new criteria: no new search
            val fresh = s.copy(filter = f, dropped = emptyMap(), restored = emptySet())
            search = fresh
            showDropped = false
            render()
            judge(fresh.items, gen)
        } else {
            go(query, scrollTop = false)
        }
    }

    private fun pick(title: String, options: List<Pair<String, String>>, selected: String, note: String = "", apply: (String) -> Unit) =
        bottomSheet(title) { dismiss ->
            if (note.isNotBlank()) muted(note)
            add(choiceChips(ctx, options, selected) { v ->
                dismiss()
                apply(v)
            }, 12)
        }

    private fun periodSheet() = bottomSheet(t("📅 Когда загружено", "📅 Upload date")) { dismiss ->
        val now = System.currentTimeMillis()
        add(text(ctx, t("Период", "Period"), Type.LABEL, bold = true, color = Palette.MUTED), 8)
        add(choiceChips(ctx, listOf("any", "today", "week", "month", "year").map { it to periodLabel(PFilter(period = it)) }, filter.period) {
            dismiss()
            setFilter(filter.copy(period = it))
        }, 4)
        add(text(ctx, t("Год выпуска", "Year"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
        add(choiceChips(ctx, PlayerCore.years(now, 10).map { "y$it" to it.toString() }, filter.period) {
            dismiss()
            setFilter(filter.copy(period = it))
        }, 4)
    }

    private fun viewsSheet() = pick(t("👁 Просмотров не меньше", "👁 At least views"),
        listOf("0" to t("Любые", "Any")) + PlayerCore.VIEWS.map { it.toString() to "≥ " + PlayerCore.count(it) }, filter.views.toString()) {
        setFilter(filter.copy(views = it.toLong()))
    }

    private fun commentsSheet() = pick(t("💬 Комментариев не меньше", "💬 At least comments"),
        listOf("0" to t("Любые", "Any")) + PlayerCore.COMMENTS.map { it.toString() to "≥ $it" }, filter.comments.toString(),
        t("Число комментариев YouTube не показывает в поиске: Lumen проверит каждое видео, поэтому поиск дольше.",
            "YouTube does not show comment counts in search: Lumen checks each video, so the search takes longer.")) {
        setFilter(filter.copy(comments = it.toInt()))
    }

    private fun lengthSheet() = pick(t("⏱ Длина видео", "⏱ Video length"),
        listOf("any", "short", "medium", "long").map { it to lengthLabel(it) }, filter.length) { setFilter(filter.copy(length = it)) }

    private fun shortsSheet() = pick("Shorts", listOf("any" to t("Все видео", "All videos"), "no" to t("Без Shorts", "No Shorts"),
        "only" to t("Только Shorts", "Shorts only")), filter.shorts) { setFilter(filter.copy(shorts = it)) }

    private fun sortSheet() = pick(t("↕ Порядок", "↕ Order"),
        listOf("relevance", "date", "views").map { it to sortLabel(it) }, filter.sort,
        t("Порядок применяется к загруженным видео.", "The order applies to the loaded videos.")) { setFilter(filter.copy(sort = it)) }

    private fun aiSheet() = bottomSheet(t("🧠 Тема и критерии", "🧠 Topic and criteria")) { dismiss ->
        var domain = filter.domain
        val rules = filter.rules.toMutableSet()
        muted(t("ИИ уберёт из выдачи видео не по теме и не по критериям. Отброшенное не пропадает: его можно раскрыть под списком и вернуть.",
            "The AI removes videos off the topic or criteria. Nothing is lost: the dropped ones can be opened under the list and brought back."))
        if (!PlayerAi.ready()) {
            add(text(ctx, t("⚠ Нужен ИИ: подключите провайдера в Настройках → AI (подойдут и бесплатные).",
                "⚠ Needs an AI: connect a provider in Settings → AI (free ones work too)."), 13f, color = Palette.WARN), 8)
            add(btn(ctx, t("Подключить ИИ", "Connect an AI"), style = BtnStyle.TONAL) {
                dismiss()
                startActivity(Intent(ctx, SettingsActivity::class.java).putExtra("ai", true))
            }, 4)
        }
        add(text(ctx, t("Тема", "Topic"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        val domBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun fillDomains() {
            domBox.removeAllViews()
            domBox.addView(chipRow(ctx, PlayerCore.DOMAINS.map { d ->
                chip(ctx, d.emoji + " " + t(d.ru, d.en), d.id == domain) {
                    domain = if (domain == d.id) "" else d.id
                    fillDomains()
                }
            }, collapseAfter = 99))
        }
        fillDomains()
        add(domBox, 4)
        add(text(ctx, t("Критерии (можно несколько)", "Criteria (several allowed)"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        val ruleBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun fillRules() {
            ruleBox.removeAllViews()
            ruleBox.addView(chipRow(ctx, PlayerCore.RULES.map { r ->
                chip(ctx, r.emoji + " " + t(r.ru, r.en), r.id in rules) {
                    if (!rules.add(r.id)) rules.remove(r.id)
                    fillRules()
                }
            }, collapseAfter = 99))
        }
        fillRules()
        add(ruleBox, 4)
        add(text(ctx, t("Своими словами", "In your own words"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        val own = add(edt(ctx, t("Например: только обзоры смартфонов до 30 тыс., без распаковок", "E.g. only phone reviews under $400, no unboxings"),
            filter.custom, multiline = true), 4)
        val projects = runCatching { Store.projects().filter { !it.archived && it.phrase.isNotBlank() }.take(8) }.getOrDefault(emptyList())
        if (projects.isNotEmpty()) {
            add(text(ctx, t("Тема моего проекта", "My project's topic"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(chipScroller(ctx, projects.map { p ->
                chip(ctx, "📁 " + p.name.ifBlank { p.phrase }.take(30)) {
                    own.setText(p.phrase + if (p.domain.isNotBlank()) " — " + p.domain.take(200) else "")
                }
            }), 4)
        }
        add(rowOf(ctx,
            btn(ctx, t("Выключить", "Turn off"), style = BtnStyle.OUTLINED) {
                dismiss()
                setFilter(filter.copy(domain = "", rules = emptyList(), custom = ""))
            },
            btn(ctx, t("Применить", "Apply"), primary = true) {
                dismiss()
                setFilter(filter.copy(domain = domain, rules = PlayerCore.RULES.map { it.id }.filter { it in rules }, custom = own.text.toString().trim()))
            }
        ), 16)
    }

    // ---------- searching ----------

    /** Searches [q] (a video link plays at once); a fresh cached result opens without the network. */
    private fun go(q: String, scrollTop: Boolean = true) {
        val v = q.trim()
        if (v.isEmpty()) return
        hideSuggestions()
        hideKeyboard()
        YtQuery.videoIdFromUrl(v)?.let { id ->
            video.play(PItem(id, ""), PlayerStore.resumeOf(id))
            return
        }
        query = v
        val cached = PlayerStore.searches().firstOrNull { it.key == PlayerCore.key(v, filter) }
        cancel.set(true)
        cancel = AtomicBoolean(false)
        val g = ++gen
        searchErr = ""
        aiNote = ""
        showDropped = false
        loadingMore = false
        if (cached != null) {
            search = cached
            engine = null
            fromCache = true
            busy = ""
            render()
            if (scrollTop) scrollToResults()
            return
        }
        fromCache = false
        val eng = PlayerSearch(v, filter)
        engine = eng
        search = PSearch(v, filter, System.currentTimeMillis(), emptyList())
        busy = t("Ищу на YouTube…", "Searching YouTube…")
        render()
        if (scrollTop) scrollToResults()
        val c = cancel
        Lumen.async({ eng.fetch(20, c) { m -> Lumen.ui { if (g == gen) statusView?.text = m } } }, { e ->
            if (g != gen) return@async
            busy = ""
            searchErr = (e.message ?: e.javaClass.simpleName).take(300)
            render()
        }) { items ->
            if (g != gen) return@async
            val s = (search ?: return@async).copy(items = items, more = eng.more, at = System.currentTimeMillis())
            search = PSearch(s.query, s.filter, s.at, PlayerCore.sort(s.items, s.filter.sort), more = s.more)
            busy = ""
            render()
            PlayerStore.save(search!!)
            judge(items, g)
        }
    }

    private fun scrollToResults() {
        scroll?.post { scroll?.smoothScrollTo(0, 0) }
    }

    /** Next page of the results (also when scrolled near the end). */
    private fun loadMore(auto: Boolean = false) {
        val s = search ?: return
        if (loadingMore || busy.isNotEmpty() || !s.more || s.items.isEmpty()) return
        if (auto && searchErr.isNotEmpty()) return
        val eng = engine ?: PlayerSearch(s.query, s.filter).also {
            it.seed(s.items.map { x -> x.id })
            engine = it
        }
        loadingMore = true
        val g = gen
        val c = cancel
        render()
        Lumen.async({ eng.fetch(20, c) { m -> Lumen.ui { if (g == gen) statusView?.text = m } } }, { e ->
            if (g != gen) return@async
            loadingMore = false
            searchErr = (e.message ?: e.javaClass.simpleName).take(300)
            render()
        }) { items ->
            if (g != gen) return@async
            loadingMore = false
            val cur = search ?: return@async
            search = cur.copy(items = cur.items + PlayerCore.sort(items, cur.filter.sort), more = eng.more)
            render()
            PlayerStore.save(search!!)
            judge(items, g)
        }
    }

    /** AI filter of new [items] (when criteria are set). */
    private fun judge(items: List<PItem>, g: Int) {
        val s = search ?: return
        if (!PlayerCore.aiOn(s.filter) || items.isEmpty()) return
        if (!PlayerAi.ready()) {
            aiNote = t("ИИ не подключён — фильтр по теме не работает. Настройки → AI.", "No AI connected — the topic filter is off. Settings → AI.")
            return render()
        }
        busy = t("🧠 ИИ проверяет выдачу…", "🧠 The AI checks the results…")
        render()
        val c = cancel
        Lumen.async({ PlayerAi.judge(s.query, s.filter, items, c) { m -> Lumen.ui { if (g == gen) statusView?.text = m } } }, { e ->
            if (g != gen) return@async
            busy = ""
            aiNote = t("ИИ не ответил: ", "The AI did not answer: ") + (e.message ?: e.javaClass.simpleName).take(200)
            render()
        }) { dropped ->
            if (g != gen) return@async
            busy = ""
            val cur = search ?: return@async
            search = cur.copy(dropped = cur.dropped + dropped)
            aiNote = ""
            render()
            PlayerStore.save(search!!)
        }
    }

    private fun refresh() {
        val s = search ?: return
        PlayerStore.delete(s.key)
        go(s.query, scrollTop = false)
    }

    private fun restore(p: PItem) {
        val s = search ?: return
        search = s.copy(restored = s.restored + p.id)
        PlayerStore.save(search!!)
        render()
        snack(t("Вернул в выдачу", "Back in the list"))
    }

    // ---------- screen ----------

    private fun render() {
        // the search field keeps the focus and the cursor while the page is rebuilt under it
        val typing = searchEdit.hasFocus()
        val sel = searchEdit.selectionStart
        body.clear()
        if (video.item != null) nowPlaying()
        (searchRow.parent as? ViewGroup)?.removeView(searchRow)
        body.add(searchRow, if (video.item != null) 12 else 0)
        if (typing) {
            searchEdit.requestFocus()
            if (sel in 0..searchEdit.text.length) searchEdit.setSelection(sel)
        }
        (suggestBox.parent as? ViewGroup)?.removeView(suggestBox)
        body.add(suggestBox, 0)
        (chipsHolder.parent as? ViewGroup)?.removeView(chipsHolder)
        refreshChips()
        body.add(chipsHolder, 8)
        statusView = null
        if (busy.isNotEmpty()) statusView = body.working(busy).first.also { (it.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = dp(12) }
        val s = search
        if (s == null) home() else results(s)
    }

    /** No search yet: continue watching and past searches. */
    private fun home() {
        val c = body
        val cont = PlayerCore.unfinished(history).take(12)
        if (cont.isNotEmpty()) {
            c.h2(t("Продолжить просмотр", "Continue watching"))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            cont.forEach { w -> row.addView(continueCard(w), LinearLayout.LayoutParams(dp(168), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }) }
            c.add(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }, 8)
        }
        val past = PlayerStore.searches()
        val keep = PlayerStore.keepDays
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(ctx, t("Прошлые поиски", "Past searches"), Type.TITLE_M, bold = true).apply { heading() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip(ctx, "🗓 " + keepLabel(keep) + " ▾") { keepSheet() })
        }
        c.add(head, 24)
        if (past.isEmpty()) {
            c.empty(t("Найдите что-нибудь", "Find something"),
                if (keep > 0) t("Результаты поисков сохранятся здесь на ${keepLabel(keep).lowercase()} и откроются мгновенно, без интернета. Потом удалятся сами.",
                    "Search results are kept here for ${keepLabel(keep).lowercase()} and open instantly, offline. Then they delete themselves.")
                else t("Хранение поисков выключено.", "Keeping searches is off."),
                t("🔍 Искать", "🔍 Search")) { focusSearch() }
            return
        }
        past.forEach { s -> c.add(pastCard(s), 8) }
        c.add(btn(this, t("🗑 Очистить прошлые поиски", "🗑 Clear past searches"), style = BtnStyle.TEXT) { clearPast() }, 8)
    }

    private fun keepLabel(d: Int) = when (d) {
        0 -> t("Не хранить", "Do not keep")
        1 -> t("1 день", "1 day")
        3 -> t("3 дня", "3 days")
        7 -> t("7 дней", "7 days")
        30 -> t("30 дней", "30 days")
        else -> t("$d дн.", "$d d")
    }

    private fun keepSheet() = pick(t("🗓 Сколько хранить прошлые поиски", "🗓 How long to keep past searches"),
        PlayerCore.KEEP_DAYS.map { it.toString() to keepLabel(it) }, PlayerStore.keepDays.toString(),
        t("Старые поиски удаляются сами. Поиск с теми же фильтрами открывается из кэша мгновенно; ↻ — обновить.",
            "Old searches delete themselves. The same search with the same filters opens from the cache instantly; ↻ — refresh.")) {
        PlayerStore.keepDays = it.toInt()
        render()
    }

    private fun clearPast() {
        val all = PlayerStore.searches()
        if (all.isEmpty()) return
        undoable(t("Прошлые поиски удалены", "Past searches deleted"), apply = {
            PlayerStore.clearSearches()
            render()
        }, revert = {
            all.forEach { PlayerStore.restoreSearch(it) }
            render()
        })
    }

    private fun pastCard(s: PSearch): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(this@PlayerActivity, roundBg(Palette.CARD, 16f, this@PlayerActivity), Palette.ON_SURFACE)
            setPadding(dp(14), dp(10), dp(4), dp(12))
            contentDescription = s.query
            setOnClickListener {
                searchEdit.setText(s.query)
                filter = s.filter
                PlayerStore.filter = s.filter
                go(s.query)
            }
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(ctx, "🕘  " + s.query, 15f, bold = true).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(smallBtn("✕", t("Удалить поиск", "Delete the search"), Palette.MUTED) {
                undoable(t("Поиск удалён", "Search deleted"), apply = {
                    PlayerStore.delete(s.key)
                    render()
                }, revert = {
                    PlayerStore.restoreSearch(s)
                    render()
                })
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
        })
        val info = listOf(t("${s.shown.size} видео", "${s.shown.size} videos"), ago(s.at)) +
            listOfNotNull(filterLine(s.filter).takeIf { it.isNotBlank() }, s.hidden.size.takeIf { it > 0 }?.let { t("ИИ убрал $it", "AI removed $it") })
        card.addView(text(this, info.joinToString(" · "), 12f, color = Palette.MUTED).apply { maxLines = 2 })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        s.shown.take(4).forEach { p ->
            strip.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundBg(Palette.ACCENT_SOFT, 6f, this@PlayerActivity)
                clipToOutline = true
                Thumbs.load(this, p.thumb)
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
        }
        if (strip.childCount > 0) card.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            marginEnd = dp(10)
        })
        return card
    }

    private fun continueCard(w: PWatch): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ripple(this@PlayerActivity, roundBg(0x00000000, 12f, this@PlayerActivity), Palette.ON_SURFACE)
        contentDescription = w.title
        addView(thumb(w.thumb, w.duration, PlayerCore.progress(w.posMs, w.duration), false), LinearLayout.LayoutParams(dp(168), dp(94)))
        addView(text(ctx, w.title.ifBlank { w.id }, 13f, bold = true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(6), dp(2), 0)
        })
        if (w.channel.isNotBlank()) addView(text(ctx, w.channel, 12f, color = Palette.MUTED).apply { isSingleLine = true; setPadding(dp(2), 0, dp(2), dp(4)) })
        setOnClickListener { video.play(PItem(w.id, w.title, w.channel, duration = w.duration), PlayerCore.resumeFrom(w.posMs, w.duration * 1000)) }
        setOnLongClickListener {
            undoable(t("Убрано из «Продолжить»", "Removed from Continue"), apply = {
                PlayerStore.forget(w.id)
                history = PlayerStore.history()
                render()
            }, revert = {
                PlayerStore.remember(w)
                history = PlayerStore.history()
                render()
            })
            true
        }
    }

    /** Thumbnail with a duration badge, a Shorts mark and a watched bar. */
    private fun thumb(url: String, durSec: Long, watched: Float, short: Boolean, live: Boolean = false): FrameLayout = FrameLayout(this).apply {
        background = roundBg(Palette.ACCENT_SOFT, 10f, this@PlayerActivity)
        clipToOutline = true
        addView(ImageView(this@PlayerActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            Thumbs.load(this, url)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val badge = when {
            live -> t("● ЭФИР", "● LIVE")
            durSec > 0 -> PlayerCore.clock(durSec)
            else -> ""
        }
        if (badge.isNotEmpty()) addView(text(this@PlayerActivity, badge, 11f, bold = true, color = 0xFFFFFFFF.toInt()).apply {
            background = roundBg(if (live) 0xE0C62828.toInt() else 0xCC000000.toInt(), 4f, this@PlayerActivity)
            setPadding(dp(4), dp(1), dp(4), dp(1))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(4), dp(5))
        })
        if (short) addView(text(this@PlayerActivity, "Shorts", 10f, bold = true, color = 0xFFFFFFFF.toInt()).apply {
            background = roundBg(0xE0E53935.toInt(), 4f, this@PlayerActivity)
            setPadding(dp(4), dp(1), dp(4), dp(1))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(4), dp(4), 0, 0)
        })
        if (watched > 0.01f) {
            addView(LinearLayout(this@PlayerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0x66FFFFFF)
                addView(View(this@PlayerActivity).apply { setBackgroundColor(0xFFE53935.toInt()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, watched))
                addView(View(this@PlayerActivity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - watched))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM))
        }
    }

    private fun results(s: PSearch) {
        val c = body
        if (searchErr.isNotEmpty()) {
            c.add(text(this, "⚠ " + searchErr, 13f, color = Palette.BAD), 12)
            c.add(btn(this, t("↻ Повторить", "↻ Retry"), style = BtnStyle.TONAL) { if (s.items.isEmpty()) go(s.query, false) else { searchErr = ""; loadMore() } }, 4)
        }
        if (aiNote.isNotEmpty()) {
            c.add(text(this, "🧠 " + aiNote, 13f, color = Palette.WARN), 12)
            if (!PlayerAi.ready()) c.add(btn(this, t("Подключить ИИ", "Connect an AI"), style = BtnStyle.TONAL) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
            }, 4)
            else c.add(btn(this, t("↻ Проверить снова", "↻ Check again"), style = BtnStyle.TONAL) {
                aiNote = ""
                judge(s.items.filter { it.id !in s.dropped }, gen)
            }, 4)
        }
        val shown = s.shown
        val hidden = s.hidden
        if (s.items.isNotEmpty() || fromCache) {
            val line = mutableListOf(t("${shown.size} видео", "${shown.size} videos"))
            if (fromCache) line += t("из кэша, ", "cached, ") + ago(s.at)
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(ctx, line.joinToString(" · "), 13f, color = Palette.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (fromCache) addView(btn(ctx, t("↻ Обновить", "↻ Refresh"), style = BtnStyle.TEXT) { refresh() })
            }
            c.add(bar, 12)
        }
        if (shown.isNotEmpty()) {
            c.add(chipScroller(this, listOf(
                chip(this, t("▶ Смотреть подряд", "▶ Watch in a row")) { video.play(shown[0], PlayerStore.resumeOf(shown[0].id)) },
                chip(this, t("🎧 Слушать все в фоне", "🎧 Listen to all in background")) {
                    video.pause()
                    PlayerHandoff.backgroundAll(this, shown, 0)
                    snack(t("Играет в фоне: ${shown.size} видео по очереди", "Playing in background: ${shown.size} videos in a row"))
                },
                chip(this, t("＋ Все в очередь фона", "＋ All to the background queue")) {
                    shown.forEach { PlayerHandoff.enqueue(this, it) }
                    snack(t("В очереди: ${shown.size}", "Queued: ${shown.size}"))
                }
            )), 4)
        }
        val wmap = history.associateBy { it.id }
        shown.forEach { p -> c.add(row(p, null, wmap[p.id]), 2) }
        if (busy.isEmpty() && s.items.isNotEmpty() && shown.isEmpty()) {
            c.empty(t("Всё отброшено ИИ", "The AI dropped everything"),
                t("Ни одно видео не прошло критерии. Раскройте отброшенное ниже или смягчите критерии.", "No video met the criteria. Open the dropped ones below or soften the criteria."),
                t("🧠 Изменить критерии", "🧠 Change criteria")) { aiSheet() }
        }
        if (busy.isEmpty() && s.items.isEmpty() && searchErr.isEmpty() && !loadingMore) {
            c.empty(t("Ничего не нашлось", "Nothing found"),
                if (PlayerCore.active(PlayerCore.plain(s.filter)) > 0) t("Фильтры могли отсечь всё. Попробуйте их ослабить.", "The filters may have cut everything. Try loosening them.")
                else t("Попробуйте другие слова.", "Try other words."),
                if (PlayerCore.active(s.filter) > 0) t("✕ Сбросить фильтры", "✕ Reset filters") else null) { setFilter(PFilter()) }
        }
        if (loadingMore) statusView = c.working(t("Загружаю ещё…", "Loading more…"), bold = false).first
        else if (s.more && s.items.isNotEmpty() && busy.isEmpty()) c.add(btn(this, t("Показать ещё", "Show more"), style = BtnStyle.TONAL) { loadMore() }, 12)
        if (hidden.isNotEmpty()) {
            c.section(t("🧠 Отброшено ИИ: ${hidden.size}", "🧠 Dropped by AI: ${hidden.size}"),
                t("не подходят под критерии — раскрыть", "do not fit the criteria — open"), showDropped, { showDropped = it; render() }) {
                hidden.forEach { p -> add(row(p, s.dropped[p.id].orEmpty(), wmap[p.id]), 2) }
            }
        }
    }

    /** Compact result row: thumbnail, title, channel · views · age; 🎧 and ⋮ on the right. */
    private fun row(p: PItem, why: String?, w: PWatch?): View {
        val playing = video.item?.id == p.id
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(this@PlayerActivity, roundBg(if (playing) Palette.ACCENT_SOFT else 0x00000000, 12f, this@PlayerActivity), Palette.ON_SURFACE)
            setPadding(dp(4), dp(6), 0, dp(6))
            contentDescription = p.title + ". " + meta(p)
            setOnClickListener { video.play(p, PlayerStore.resumeOf(p.id)) }
            setOnLongClickListener { itemSheet(p, why); true }
        }
        val live = p.duration <= 0 && p.uploadMs < 0 && p.views >= 0
        row.addView(thumb(p.thumb, p.duration, w?.let { PlayerCore.progress(it.posMs, p.duration) } ?: 0f, p.shortLike, live),
            LinearLayout.LayoutParams(dp(120), dp(68)).apply { marginEnd = dp(10) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(text(this, (if (playing) "▶ " else "") + p.title, 14f, bold = true, color = if (playing) Palette.ON_SOFT else Palette.ON_SURFACE).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(text(this, meta(p), 12f, color = Palette.MUTED).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        if (why != null) info.addView(text(this, "🧠 " + why.ifBlank { t("не подходит под критерии", "does not fit the criteria") }, 12f, color = Palette.WARN).apply { maxLines = 2 })
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            if (why != null) addView(smallBtn("↩", t("Вернуть в выдачу", "Bring back"), Palette.ACCENT) { restore(p) }, LinearLayout.LayoutParams(dp(44), dp(40)))
            else addView(smallBtn("🎧", t("Слушать в фоне", "Listen in background")) { listen(p) }, LinearLayout.LayoutParams(dp(44), dp(40)))
            addView(smallBtn("⋮", t("Ещё действия", "More actions"), Palette.MUTED, 20f) { itemSheet(p, why) }, LinearLayout.LayoutParams(dp(44), dp(40)))
        }
        row.addView(side)
        return row
    }

    /** Sound of [p] in the background (NewPipe's background player with its notification). */
    private fun listen(p: PItem) {
        if (video.item?.id == p.id && video.info != null) return video.toBackground()
        video.pause()
        PlayerHandoff.backgroundAll(this, listOf(p), 0)
        snack(t("🎧 Играет в фоне", "🎧 Playing in background") + ": " + p.title.take(40))
    }

    private fun itemSheet(p: PItem, why: String?) = bottomSheet(p.title.ifBlank { p.id }.take(90)) { dismiss ->
        muted(meta(p))
        if (why != null) add(text(ctx, "🧠 " + why.ifBlank { t("не подходит под критерии", "does not fit the criteria") }, 13f, color = Palette.WARN), 6)
        fun act(s: String, f: () -> Unit) = add(btn(ctx, s, style = BtnStyle.TEXT) {
            dismiss()
            f()
        }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, 0)
        if (why != null) act(t("↩ Вернуть в выдачу", "↩ Bring back to the list")) { restore(p) }
        act(t("▶ Смотреть здесь", "▶ Watch here")) { video.play(p, PlayerStore.resumeOf(p.id)) }
        act(t("🎧 Слушать в фоне", "🎧 Listen in background")) { listen(p) }
        act(t("＋ В очередь фона", "＋ Add to the background queue")) {
            PlayerHandoff.enqueue(this@PlayerActivity, p)
            snack(t("В очереди", "Queued"))
        }
        act(t("⧉ Окном поверх приложений", "⧉ Floating window")) {
            val here = video.item?.id == p.id
            val pos = if (here) video.pos() else 0
            if (here) video.pause()
            PlayerHandoff.popup(this@PlayerActivity, if (here) video.info else null, p, pos)
        }
        act(MediaLink.label(MediaAction.AUDIO)) { runMedia(p.url, MediaAction.AUDIO) }
        act(MediaLink.label(MediaAction.VIDEO)) { runMedia(p.url, MediaAction.VIDEO) }
        act(t("✂ Откусить кусок", "✂ Bite a piece")) { BiteActivity.open(this@PlayerActivity, p.url) }
        act(t("📊 Анализ в Lumen…", "📊 Analyse in Lumen…")) { chooseLinkAction(p.url) }
        if (p.channel.isNotBlank()) act(t("🔎 Ещё с канала «${p.channel.take(30)}»", "🔎 More from \"${p.channel.take(30)}\"")) {
            searchEdit.setText(p.channel)
            go(p.channel)
        }
        act(t("↗ Поделиться ссылкой", "↗ Share the link")) { shareText(p.title, p.title + "\n" + p.url) }
        act(t("🌐 Открыть в YouTube", "🌐 Open in YouTube")) { openUrl(p.url) }
    }

    /** What plays: title, channel and actions (background, window, download, analysis…). */
    private fun nowPlaying() {
        val p = video.item ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(this, p.title.ifBlank { t("Загрузка…", "Loading…") }, Type.TITLE_M, bold = true).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val m = meta(p)
        if (m.isNotBlank()) box.addView(text(this, m, 12f, color = Palette.MUTED).apply { setPadding(0, dp(2), 0, 0) })
        if (video.handedOff) {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundBg(Palette.ACCENT_SOFT, 12f, this@PlayerActivity)
                setPadding(dp(12), dp(4), dp(4), dp(4))
                addView(text(ctx, t("🎧 Звук играет в фоне", "🎧 Sound plays in the background"), 13f, bold = true, color = Palette.ON_SOFT),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(btn(ctx, t("▶ Сюда", "▶ Here"), style = BtnStyle.TEXT) { video.bringBack() })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        box.addView(chipScroller(this, listOf(
            chip(this, t("🎧 В фоне", "🎧 Background")) { video.toBackground() },
            chip(this, t("⧉ Окном", "⧉ Window")) {
                val pos = video.pos()
                video.pause()
                PlayerHandoff.popup(this, video.info, p, pos)
                video.handedOff = true
                render()
            },
            chip(this, t("⬇ Скачать", "⬇ Download")) { videoMenu(p.url, p.title) },
            chip(this, t("✂ Кусок", "✂ Piece")) { BiteActivity.open(this, p.url) },
            chip(this, t("📊 Анализ", "📊 Analyse")) { chooseLinkAction(p.url) },
            chip(this, "↗") { shareText(p.title, p.title + "\n" + p.url) },
            chip(this, t("⚙ Видео", "⚙ Video")) { video.settingsSheet() }
        )), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        body.add(box, 0)
    }

    private fun menu() {
        val items = listOf<Pair<String, () -> Unit>>(
            t("🗓 Хранить поиски: ", "🗓 Keep searches: ") + keepLabel(PlayerStore.keepDays) to { keepSheet() },
            t("🗑 Очистить прошлые поиски", "🗑 Clear past searches") to { clearPast() },
            t("🗑 Очистить историю просмотра", "🗑 Clear watch history") to {
                val all = PlayerStore.history()
                undoable(t("История просмотра очищена", "Watch history cleared"), apply = {
                    PlayerStore.clearHistory()
                    history = emptyList()
                    render()
                }, revert = {
                    all.reversed().forEach { PlayerStore.remember(it) }
                    history = PlayerStore.history()
                    render()
                })
            },
            t("⚙ Настройки видео", "⚙ Video settings") to { video.settingsSheet() },
            t("🏠 Новый поиск", "🏠 New search") to {
                cancel.set(true)
                search = null
                engine = null
                busy = ""
                searchErr = ""
                aiNote = ""
                query = ""
                searchEdit.setText("")
                history = PlayerStore.history()
                render()
            },
            t("Плеер NewPipe", "NewPipe player") to { startActivity(Intent().setClassName(packageName, "org.schabi.newpipe.MainActivity")) },
            t("❓ Справка", "❓ Help") to { HelpActivity.open(this, "player") }
        )
        val theme = if (isNight()) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        android.app.AlertDialog.Builder(this, theme).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    /** Next shown video after [p] (for "next video"). */
    private fun nextOf(p: PItem): PItem? {
        val list = search?.shown ?: return null
        val i = list.indexOfFirst { it.id == p.id }
        return if (i >= 0) list.getOrNull(i + 1) else null
    }

    /** Remembers where the user is in a video (for "Continue watching" and the watched bars). */
    private fun saveWatch(p: PItem, posMs: Long, durSec: Long) {
        if (p.title.isBlank()) return
        val w = PWatch(p.id, p.title, p.channel, durSec, posMs, System.currentTimeMillis())
        history = PlayerCore.remember(history, w)
        Lumen.io.execute { PlayerStore.remember(w) }
    }

    // ---------- the built-in player ----------

    /**
     * The video on top: ExoPlayer with NewPipe's stream resolver (DASH, separate audio, quality) on a TextureView,
     * own controls over it. Kept between renders.
     */
    private inner class VideoBox {
        val ctx: Context = this@PlayerActivity
        private val surface = TextureView(ctx)
        val frame = FrameLayout(ctx).apply { setBackgroundColor(0xFF000000.toInt()) }
        private val controls = FrameLayout(ctx).apply { setBackgroundColor(0x55000000) }
        private val playBtn = ctl("▶", 34f, t("Пауза или воспроизведение", "Play or pause")) { toggle() }
        private val seek = SeekBar(ctx).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
            secondaryProgressTintList = android.content.res.ColorStateList.valueOf(0x99FFFFFF.toInt())
            contentDescription = t("Перемотка", "Seek")
        }
        private val time = text(ctx, "", 12f, bold = true, color = 0xFFFFFFFF.toInt())
        private val topTitle = text(ctx, "", 15f, bold = true, color = 0xFFFFFFFF.toInt()).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END }
        private val fullBtn = ctl("⛶", 20f, t("Во весь экран", "Full screen")) { if (full) exitFull() else enterFull(lock = true) }
        private val spinner = ProgressBar(ctx).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
        }
        private val msgBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xB0000000.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }
        private val flash = text(ctx, "", 18f, bold = true, color = 0xFFFFFFFF.toInt()).apply {
            background = roundBg(0x88000000.toInt(), 24f, ctx)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
        }
        var exo: ExoPlayer? = null
        var info: StreamInfo? = null
        var item: PItem? = null
        var full = false
        var locked = false
        var handedOff = false
        private var resolver: VideoPlaybackResolver? = null
        private var g = 0
        private var vw = 16
        private var vh = 9
        private var dragging = false
        private var lastSave = 0L
        private var nextAt = 0L
        private var nextItem: PItem? = null
        private var countdown: TextView? = null

        private val hideCtl = Runnable { if (exo?.isPlaying == true && !dragging) controls.visibility = View.GONE }
        private val tick = object : Runnable {
            override fun run() {
                update()
                frame.postDelayed(this, 500)
            }
        }

        init {
            frame.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            frame.addView(spinner, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
            // controls
            val top = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(4), 0)
                addView(topTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ctl("🎧", 18f, t("Слушать в фоне", "Listen in background")) { toBackground() })
                addView(ctl("⚙", 20f, t("Качество и скорость", "Quality and speed")) { settingsSheet() })
                addView(ctl("✕", 20f, t("Закрыть видео", "Close the video")) { close() })
            }
            controls.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            val mid = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(ctl("⏪", 24f, t("Назад 10 секунд", "Back 10 seconds")) { seekBy(-10_000) }, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(playBtn, LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(20), 0, dp(20), 0) })
                addView(ctl("⏩", 24f, t("Вперёд 10 секунд", "Forward 10 seconds")) { seekBy(10_000) }, LinearLayout.LayoutParams(dp(56), dp(56)))
            }
            controls.addView(mid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val bottom = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(4), dp(2))
                addView(time)
                addView(seek, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(fullBtn)
            }
            controls.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
            frame.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(flash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            frame.addView(msgBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) {
                    if (!user) return
                    val d = exo?.duration?.takeIf { it > 0 } ?: return
                    time.text = PlayerCore.clock(d * v / 1000 / 1000).ifBlank { "0:00" } + " / " + PlayerCore.clock(d / 1000)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    dragging = true
                    frame.removeCallbacks(hideCtl)
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    val p = exo ?: return
                    if (p.duration > 0) p.seekTo(p.duration * sb.progress / 1000)
                    showControls()
                }
            })
            val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (controls.visibility == View.VISIBLE) controls.visibility = View.GONE else showControls()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val w = frame.width.coerceAtLeast(1)
                    when {
                        e.x < w / 3f -> seekBy(-10_000)
                        e.x > w * 2 / 3f -> seekBy(10_000)
                        else -> toggle()
                    }
                    return true
                }
            })
            frame.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
            frame.post(tick)
        }

        private fun ctl(glyph: String, size: Float, label: String, onClick: () -> Unit): TextView = text(ctx, glyph, size, color = 0xFFFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(48)
            contentDescription = label
            setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
            background = ripple(ctx, roundBg(0x00000000, 36f, ctx), 0xFFFFFFFF.toInt())
            setOnClickListener {
                onClick()
                showControls()
            }
        }

        fun portraitHeight(): Int = resources.displayMetrics.widthPixels * 9 / 16

        private fun showControls() {
            controls.visibility = View.VISIBLE
            frame.removeCallbacks(hideCtl)
            frame.postDelayed(hideCtl, 3000)
        }

        private fun flashText(s: String) {
            flash.text = s
            flash.visibility = View.VISIBLE
            frame.removeCallbacks(hideFlash)
            frame.postDelayed(hideFlash, 700)
        }

        private val hideFlash = Runnable { flash.visibility = View.GONE }

        private fun exo(): ExoPlayer = exo ?: ExoPlayer.Builder(ctx)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build().also { p ->
                exo = p
                p.setVideoTextureView(surface)
                p.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(size: VideoSize) {
                        if (size.width <= 0 || size.height <= 0) return
                        vw = (size.width * size.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
                        vh = size.height
                        fit()
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        spinner.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                        if (state == Player.STATE_ENDED) ended()
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        playBtn.text = if (playing) "⏸" else "▶"
                        frame.keepScreenOn = playing
                        if (playing) showControls() else {
                            frame.removeCallbacks(hideCtl)
                            controls.visibility = View.VISIBLE
                            item?.let { saveWatch(it, p.currentPosition, durSec()) }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        L.w("player", "play ${item?.id}: ${error.errorCodeName} ${error.message}")
                        problem(t("Не играет: ", "Cannot play: ") + (error.message ?: error.errorCodeName))
                    }
                })
            }

        private fun durSec(): Long = exo?.duration?.takeIf { it > 0 }?.let { it / 1000 } ?: info?.duration ?: item?.duration ?: -1

        /** Plays [p] here from [fromMs] (loads its streams first). */
        fun play(p: PItem, fromMs: Long) {
            item?.let { old -> exo?.let { e -> if (old.id != p.id && e.currentPosition > 0) saveWatch(old, e.currentPosition, durSec()) } }
            val k = ++g
            item = p
            info = null
            handedOff = false
            nextItem = null
            hideMsg()
            if (PlayerHandoff.othersPlaying()) PlayerHandoff.stopOthers()
            exo?.stop()
            topTitle.text = p.title
            frame.visibility = View.VISIBLE
            spinner.visibility = View.VISIBLE
            controls.visibility = View.GONE
            render()
            scroll?.post { scroll?.smoothScrollTo(0, 0) }
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && !full) enterFull(lock = false)
            Lumen.async({ ExtractorHelper.getStreamInfo(ServiceList.YouTube.serviceId, p.url, false).blockingGet() }, { e ->
                if (k != g) return@async
                problem(t("Не удалось открыть видео: ", "Could not open the video: ") + (e.message ?: e.javaClass.simpleName).take(200))
            }) { si ->
                if (k != g || isDestroyed) return@async
                info = si
                item = p.copy(
                    title = p.title.ifBlank { si.name.orEmpty() }, channel = p.channel.ifBlank { si.uploaderName.orEmpty() },
                    channelUrl = p.channelUrl.ifBlank { si.uploaderUrl.orEmpty() }, duration = if (si.duration > 0) si.duration else p.duration,
                    views = if (p.views >= 0) p.views else si.viewCount, uploadMs = if (p.uploadMs > 0) p.uploadMs else si.uploadDate?.instant?.toEpochMilli() ?: -1
                )
                topTitle.text = item?.title
                prepare(si, fromMs)
                render()
            }
        }

        private fun prepare(si: StreamInfo, fromMs: Long) {
            val r = resolver ?: VideoPlaybackResolver(ctx, PlayerDataSource(ctx, DefaultBandwidthMeter.Builder(ctx).build()),
                object : VideoPlaybackResolver.QualityResolver {
                    override fun getDefaultResolutionIndex(sortedVideos: List<VideoStream>): Int = ListHelper.getDefaultResolutionIndex(ctx, sortedVideos)
                    override fun getOverrideResolutionIndex(sortedVideos: List<VideoStream>, playbackQuality: String): Int =
                        ListHelper.getResolutionIndex(ctx, sortedVideos, playbackQuality)
                }).also { resolver = it }
            r.playbackQuality = PlayerStore.quality.takeIf { it != "auto" }
            val src = runCatching { r.resolve(si) }.onFailure { L.w("player", "resolve: ${it.message}") }.getOrNull()
                ?: return problem(t("Нет потока для этого видео", "No stream for this video"))
            val p = exo()
            p.setMediaSource(src)
            p.prepare()
            if (fromMs > 0) {
                p.seekTo(fromMs)
                flashText(t("с ", "from ") + PlayerCore.clock(fromMs / 1000))
            }
            p.setPlaybackSpeed(PlayerStore.speed)
            p.playWhenReady = true
        }

        /** Fits the picture into the frame without stretching. */
        fun fit() {
            if (!full) {
                val h = portraitHeight()
                if (frame.layoutParams?.height != h) frame.layoutParams = frame.layoutParams?.apply { height = h }
            }
            frame.post {
                val fw = frame.width
                val fh = frame.height
                if (fw <= 0 || fh <= 0) return@post
                val k = minOf(fw.toFloat() / vw, fh.toFloat() / vh)
                surface.layoutParams = FrameLayout.LayoutParams((vw * k).toInt(), (vh * k).toInt(), Gravity.CENTER)
            }
        }

        private fun update() {
            val p = exo ?: return
            val d = p.duration
            if (d > 0 && !dragging) {
                seek.progress = (p.currentPosition * 1000 / d).toInt()
                seek.secondaryProgress = (p.bufferedPosition * 1000 / d).toInt()
                time.text = PlayerCore.clock(p.currentPosition / 1000).ifBlank { "0:00" } + " / " + PlayerCore.clock(d / 1000)
            } else if (d <= 0 && info != null && !dragging) {
                time.text = t("● ЭФИР", "● LIVE")
            }
            val now = System.currentTimeMillis()
            if (p.isPlaying && now - lastSave > 5000) {
                lastSave = now
                item?.let { saveWatch(it, p.currentPosition, durSec()) }
            }
            val n = nextItem
            if (n != null && nextAt > 0) {
                val left = ((nextAt - now) / 1000).coerceAtLeast(0)
                countdown?.text = t("Далее через $left с:", "Next in $left s:")
                if (now >= nextAt) {
                    nextAt = 0
                    play(n, PlayerStore.resumeOf(n.id))
                }
            }
        }

        private fun ended() {
            val p = item ?: return
            saveWatch(p, 0, durSec())
            val n = nextOf(p)
            if (n == null || !PlayerStore.autoNext) {
                controls.visibility = View.VISIBLE
                return
            }
            nextItem = n
            nextAt = System.currentTimeMillis() + 5000
            msgBox.removeAllViews()
            countdown = text(ctx, t("Далее через 5 с:", "Next in 5 s:"), 13f, color = 0xFFDDDDDD.toInt()).also { msgBox.addView(it) }
            msgBox.addView(text(ctx, n.title, 15f, bold = true, color = 0xFFFFFFFF.toInt()).apply { maxLines = 2; gravity = Gravity.CENTER })
            msgBox.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(btn(ctx, t("Отмена", "Cancel"), style = BtnStyle.TEXT) {
                    nextItem = null
                    nextAt = 0
                    hideMsg()
                    controls.visibility = View.VISIBLE
                })
                addView(btn(ctx, t("▶ Сейчас", "▶ Now"), primary = true) {
                    nextAt = 0
                    play(n, PlayerStore.resumeOf(n.id))
                })
            })
            msgBox.visibility = View.VISIBLE
        }

        private fun problem(s: String) {
            spinner.visibility = View.GONE
            msgBox.removeAllViews()
            msgBox.addView(text(ctx, s, 14f, color = 0xFFFFFFFF.toInt()).apply { gravity = Gravity.CENTER; maxLines = 4 })
            msgBox.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(btn(ctx, t("↻ Повторить", "↻ Retry"), style = BtnStyle.TEXT) {
                    val p = item ?: return@btn
                    resolver = null
                    play(p, exo?.currentPosition ?: 0)
                })
                addView(btn(ctx, t("Плеер NewPipe", "NewPipe player"), style = BtnStyle.TEXT) {
                    val p = item ?: return@btn
                    val q = info?.let { SinglePlayQueue(it, exo?.currentPosition ?: 0) } ?: SinglePlayQueue(PlayerHandoff.streamItem(p))
                    NavigationHelper.playOnMainPlayer(ctx, q, false)
                })
            })
            msgBox.visibility = View.VISIBLE
        }

        private fun hideMsg() {
            msgBox.visibility = View.GONE
            msgBox.removeAllViews()
            countdown = null
        }

        fun toggle() {
            val p = exo ?: return
            if (handedOff) return bringBack()
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.playWhenReady = !p.playWhenReady
        }

        fun pause() {
            exo?.playWhenReady = false
        }

        fun pos(): Long = exo?.currentPosition ?: 0

        fun seekBy(d: Long) {
            val p = exo ?: return
            val dur = p.duration
            p.seekTo((p.currentPosition + d).coerceIn(0, if (dur > 0) dur - 500 else Long.MAX_VALUE))
            flashText(if (d < 0) t("−10 с", "−10 s") else t("+10 с", "+10 s"))
        }

        /** The sound goes on in NewPipe's background player from the same place. */
        fun toBackground() {
            val si = info ?: return toast(t("Видео ещё загружается", "The video is still loading"))
            val pos = pos()
            pause()
            item?.let { saveWatch(it, pos, durSec()) }
            PlayerHandoff.background(ctx, si, pos)
            handedOff = true
            render()
            snack(t("🎧 Играет в фоне — управление в уведомлении", "🎧 Playing in background — controls are in the notification"))
        }

        /** Back from the background player to the screen (from where the screen stopped). */
        fun bringBack() {
            PlayerHandoff.stopOthers()
            handedOff = false
            render()
            exo?.playWhenReady = true
        }

        /** The screen goes away: a playing video goes on as sound (if the setting is on), otherwise pauses. */
        fun leaving() {
            val p = exo ?: return
            if (isChangingConfigurations) return
            if (p.isPlaying && PlayerStore.bgOnLeave && info != null) toBackground() else pause()
        }

        fun close() {
            item?.let { saveWatch(it, pos(), durSec()) }
            g++
            if (full) exitFull()
            exo?.stop()
            item = null
            info = null
            handedOff = false
            nextItem = null
            hideMsg()
            frame.visibility = View.GONE
            frame.keepScreenOn = false
            render()
        }

        fun release() {
            item?.let { i -> exo?.let { saveWatch(i, it.currentPosition, durSec()) } }
            frame.removeCallbacks(tick)
            exo?.release()
            exo = null
        }

        @Suppress("DEPRECATION")
        fun enterFull(lock: Boolean) {
            if (full) return
            full = true
            locked = lock
            if (lock && vw > vh) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            appBarView?.visibility = View.GONE
            scroll?.visibility = View.GONE
            frame.layoutParams = frame.layoutParams?.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            (frame.parent as? View)?.layoutParams = (frame.parent as? View)?.layoutParams?.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            fullBtn.text = "⤓"
            fit()
        }

        @Suppress("DEPRECATION")
        fun exitFull() {
            if (!full) return
            full = false
            locked = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            appBarView?.visibility = View.VISIBLE
            scroll?.visibility = View.VISIBLE
            (frame.parent as? View)?.layoutParams = (frame.parent as? View)?.layoutParams?.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
            frame.layoutParams = frame.layoutParams?.apply { height = portraitHeight() }
            window.decorView.systemUiVisibility = if (isNight()) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            fullBtn.text = "⛶"
            fit()
        }

        /** Quality (from what this video has), speed, next video, background on leaving. */
        fun settingsSheet() = bottomSheet(t("⚙ Видео", "⚙ Video")) { dismiss ->
            val q = exo?.currentMediaItem?.let { MediaItemTag.from(it).flatMap { tag -> tag.maybeQuality }.orElse(null) }
            val have = q?.sortedVideoStreams?.map { it.getResolution() }?.distinct().orEmpty()
            val cur = PlayerStore.quality
            add(text(ctx, t("Качество", "Quality"), Type.LABEL, bold = true, color = Palette.MUTED), 8)
            val options = listOf("auto" to t("Авто", "Auto")) + (have.ifEmpty { listOf("1080p", "720p", "480p", "360p", "240p") }).map { it to it }
            add(choiceChips(ctx, options, if (options.any { it.first == cur }) cur else "auto") { v ->
                PlayerStore.quality = v
                val si = info
                if (si != null) prepare(si, pos())
                dismiss()
            }, 4)
            q?.let { muted(t("Сейчас: ", "Now: ") + it.sortedVideoStreams.getOrNull(it.selectedVideoStreamIndex)?.getResolution().orEmpty()) }
            add(text(ctx, t("Скорость", "Speed"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
            val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
            add(choiceChips(ctx, speeds.map { it.toString() to "×" + it.toString().removeSuffix(".0") }, PlayerStore.speed.toString()) { v ->
                PlayerStore.speed = v.toFloat()
                exo?.setPlaybackSpeed(v.toFloat())
            }, 4)
            add(chk(ctx, t("Следующее видео списка — само", "Play the next video of the list"), PlayerStore.autoNext) { PlayerStore.autoNext = it }, 16)
            add(chk(ctx, t("Свернул приложение — слушать звук в фоне", "Leaving the app — keep the sound in the background"), PlayerStore.bgOnLeave) { PlayerStore.bgOnLeave = it }, 0)
            muted(t("Двойное касание слева/справа — на 10 с назад/вперёд, в центре — пауза. Поверните телефон — видео на весь экран.",
                "Double-tap left/right — 10 s back/forward, centre — pause. Turn the phone — full-screen video."))
        }
    }
}
