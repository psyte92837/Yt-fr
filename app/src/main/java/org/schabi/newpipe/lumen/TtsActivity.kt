/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * "Read aloud": text or a document → Microsoft Edge neural voices. Two actions: Listen (sound starts with the first
 * part) and Save MP3 (background job, file goes to Music/Lumen). The work itself lives in [TtsService]; this screen
 * only shows [TtsState] and sends commands, so rotating or leaving the screen never interrupts it.
 */
class TtsActivity : LumenBase() {
    companion object {
        private const val REQ_SAVE = 7301
        private const val REQ_PICK = 7302
        private const val MAX_FILE = 30 * 1024 * 1024
        private const val MAX_CHARS = 300_000
        private const val INPUT_FILE = "tts_input.txt"
        val pickTypes = arrayOf(
            "text/*", "application/epub+zip", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/x-fictionbook+xml", "application/x-subrip", "application/json", "application/xml", "application/octet-stream"
        )

        /** Opens the screen with [text]; [autoStart] begins listening or saving at once. */
        fun open(ctx: Context, text: String = "", title: String = "", autoStart: TtsMode? = null) {
            // very long texts are kept in a file: intent extras are size-limited
            Lumen.init(ctx)
            File(ctx.cacheDir, INPUT_FILE).writeText(text)
            ctx.startActivity(Intent(ctx, TtsActivity::class.java).putExtra("title", title).putExtra("cached", true)
                .putExtra("autostart", autoStart?.name)
                .addFlags(if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private var voices: List<TtsVoice> = EdgeTts.builtIn
    private var locale = ""
    private var voice = TtsPrefs.voice
    private var gender = TtsPrefs.gender
    private lateinit var input: EditText
    private lateinit var voiceBox: LinearLayout
    private lateinit var langBox: LinearLayout
    private lateinit var info: TextView
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var playRow: LinearLayout
    private lateinit var toggleBtn: android.widget.Button
    private lateinit var stopOnly: android.widget.Button
    private lateinit var speedRow: LinearLayout
    private lateinit var actionRow: LinearLayout
    private lateinit var resultBox: LinearLayout
    private lateinit var resultText: TextView
    private lateinit var openBtn: android.widget.Button
    private var setRate: (Int) -> Unit = {}
    private var setPitch: (Int) -> Unit = {}
    private var speedChips: View? = null
    private var pitchChips: View? = null
    private val summaries = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fromFile = intent.getBooleanExtra("cached", false) || savedInstanceState != null
        val initial = (if (fromFile) runCatching { File(cacheDir, INPUT_FILE).readText() }.getOrNull() else null)
            ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        locale = voice.split('-').take(2).joinToString("-")
        build(initial)
        if (TtsPrefs.auto && initial.isNotBlank()) autoPick(initial)
        loadVoices(false)
        // a file shared to Lumen (Share → Lumen) or opened from another app
        @Suppress("DEPRECATION")
        val shared = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: intent.data?.takeIf { intent.action == Intent.ACTION_VIEW }
        if (shared != null && savedInstanceState == null) loadFile(shared)
        val auto = intent.getStringExtra("autostart")?.let { runCatching { TtsMode.valueOf(it) }.getOrNull() }
        if (auto != null && savedInstanceState == null && initial.isNotBlank()) input.post { start(auto) }
    }

    override fun onStart() {
        super.onStart()
        TtsState.listener = ::render
        render()
    }

    override fun onStop() {
        TtsState.listener = null
        // the notification reopens the screen with this text
        runCatching { File(cacheDir, INPUT_FILE).writeText(input.text.toString()) }
        super.onStop()
    }

    // ---------- layout ----------

    private fun build(initial: String) {
        val c = Col(this)
        c.muted(t(
            "Голоса Microsoft Edge (онлайн, бесплатно, без ключа). Текст отправляется на сервер Microsoft, нужен интернет.",
            "Microsoft Edge voices (online, free, no key). The text is sent to Microsoft's server; internet is required."
        ))
        input = c.edit(t("Вставьте или введите текст", "Paste or type text"), initial, multiline = true).apply {
            minLines = 6
            maxLines = 14
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        c.row(
            btn(this, t("📎 Файл", "📎 File"), style = BtnStyle.TONAL) { pickFile() },
            btn(this, t("📋 Вставить", "📋 Paste"), style = BtnStyle.TONAL) { paste() },
            btn(this, t("Очистить", "Clear"), style = BtnStyle.TEXT) { input.setText("") }
        )
        info = c.muted("")
        c.muted(t("TXT, MD, FB2, EPUB, DOCX, HTML, SRT/VTT. Кодировка определяется сама.", "TXT, MD, FB2, EPUB, DOCX, HTML, SRT/VTT. Encoding is detected automatically."))

        panel(c, t("Голос", "Voice"), { (voices.firstOrNull { it.shortName == voice }?.display ?: voice) + " · " + locale }, TtsPrefs.voiceOpen, { TtsPrefs.voiceOpen = it }) {
            check(t("Подбирать язык голоса по тексту", "Match the voice language to the text"), TtsPrefs.auto) {
                TtsPrefs.auto = it
                if (it) autoPick(input.text.toString())
            }
            langBox = add(LinearLayout(this@TtsActivity).apply { orientation = LinearLayout.VERTICAL })
            voiceBox = add(LinearLayout(this@TtsActivity).apply { orientation = LinearLayout.VERTICAL })
            row(
                btn(this@TtsActivity, t("▷ Образец голоса", "▷ Voice sample"), style = BtnStyle.TONAL) { preview() },
                btn(this@TtsActivity, t("Обновить список", "Refresh list"), style = BtnStyle.TEXT) { loadVoices(true) }
            )
        }
        panel(c, t("Звучание", "Sound"), { t("скорость ", "rate ") + EdgeTts.signed(TtsPrefs.rate, "%") + " · " + t("тон ", "pitch ") + EdgeTts.signed(TtsPrefs.pitch, " Hz") }, TtsPrefs.soundOpen, { TtsPrefs.soundOpen = it }) {
            speedChips = add(LinearLayout(this@TtsActivity))
            setRate = slider(this, t("Скорость речи", "Speech rate"), -50, 100, 5, TtsPrefs.rate, "%") { TtsPrefs.rate = it; renderPresets() }
            pitchChips = add(LinearLayout(this@TtsActivity))
            setPitch = slider(this, t("Тон (высота голоса)", "Pitch"), -50, 50, 2, TtsPrefs.pitch, " Hz") { TtsPrefs.pitch = it; renderPresets() }
            val setVolume = slider(this, t("Громкость", "Volume"), -50, 50, 5, TtsPrefs.volume, "%") { TtsPrefs.volume = it }
            button(t("Сбросить звучание", "Reset sound"), style = BtnStyle.TEXT) {
                TtsPrefs.rate = 0
                TtsPrefs.pitch = 0
                TtsPrefs.volume = 0
                setRate(0)
                setPitch(0)
                setVolume(0)
                renderPresets()
            }
        }
        panel(c, t("Ещё", "More"), { "" }, false, {}) {
            check(t("Читать выделенное или с места курсора", "Read the selection or from the cursor"), TtsPrefs.fromCursor) { TtsPrefs.fromCursor = it }
            check(t("Сохранять MP3 в Музыка/Lumen и после «Слушать»", "Also save an MP3 to Music/Lumen after “Listen”"), TtsPrefs.saveAfterListen) { TtsPrefs.saveAfterListen = it }
        }
        renderPresets()

        val scroll = ScrollView(this).apply { isFillViewport = true; addView(c.root) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        page(intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: t("Озвучка текста", "Read aloud"), root, scroll = false)
        render()
    }

    /** Fixed bottom panel: status, progress, player controls and the two main actions. */
    private fun buildBar(): View {
        val b = Col(this)
        b.root.background = roundBg(Palette.CARD, 0f, this)
        status = b.p("", 14f)
        bar = b.add(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }, 4)
        playRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun ctl(glyph: String, action: String) = btn(this, glyph, style = BtnStyle.TONAL) { TtsService.command(this, action) }
        toggleBtn = ctl("⏸", TtsService.ACTION_TOGGLE)
        for (v in listOf(ctl("⏮", TtsService.ACTION_PREV), toggleBtn, ctl("⏭", TtsService.ACTION_NEXT), ctl("■", TtsService.ACTION_STOP))) {
            playRow.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        }
        b.add(playRow, 4)
        speedRow = b.add(LinearLayout(this), 4)
        stopOnly = b.button(t("■ Остановить", "■ Stop"), style = BtnStyle.TONAL) { TtsService.command(this, TtsService.ACTION_STOP) }
        resultBox = b.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }, 4)
        resultText = text(this, "", 13f, color = Palette.MUTED)
        resultBox.addView(resultText)
        openBtn = btn(this, t("Открыть", "Open"), style = BtnStyle.TEXT) { openSaved() }
        resultBox.addView(rowOf(this, openBtn, btn(this, t("Сохранить как…", "Save as…"), style = BtnStyle.TEXT) { saveAs() }))
        actionRow = b.row(
            btn(this, t("▶  Слушать", "▶  Listen"), primary = true) { start(TtsMode.LISTEN) },
            btn(this, t("⬇  Сохранить MP3", "⬇  Save MP3"), style = BtnStyle.TONAL) { start(TtsMode.SAVE) }
        )
        return b.root
    }

    /** Labeled SeekBar; returns a setter that moves it from code. */
    private fun slider(c: Col, label: String, min: Int, max: Int, step: Int, value: Int, unit: String, onSet: (Int) -> Unit): (Int) -> Unit {
        val tv = c.p("")
        fun show(v: Int) {
            tv.text = "$label: ${EdgeTts.signed(v, unit)}"
        }
        show(value)
        val bar = c.add(SeekBar(this).apply {
            this.max = (max - min) / step
            progress = (value.coerceIn(min, max) - min) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = show(min + p * step)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) = onSet(min + (sb?.progress ?: 0) * step)
            })
        }, 0)
        return { v ->
            bar.progress = (v.coerceIn(min, max) - min) / step
            show(v)
        }
    }


    private fun fillRow(holder: android.view.View?, chips: List<android.view.View>) {
        val box = holder as? LinearLayout ?: return
        box.removeAllViews()
        box.addView(chipRow(this, chips), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }



    /** Collapsible section; [summary] is shown next to the title and refreshed when settings change. */
    private fun panel(c: Col, title: String, summary: () -> String, open: Boolean, onToggle: (Boolean) -> Unit, build: Col.() -> Unit) {
        val body = Col(this, 0)
        body.build()
        val sum = text(this, "", 13f, color = Palette.MUTED)
        val arrow = text(this, "", 18f, color = Palette.MUTED)
        fun show(o: Boolean) {
            body.root.visibility = if (o) View.VISIBLE else View.GONE
            arrow.text = if (o) "▴" else "▾"
        }
        var opened = open
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            background = ripple(this@TtsActivity, roundBg(0x00000000, 12f, this@TtsActivity), Palette.ON_SURFACE)
            val texts = LinearLayout(this@TtsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(this@TtsActivity, title, Type.TITLE_M, bold = true))
                addView(sum)
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(arrow)
            setOnClickListener {
                opened = !opened
                show(opened)
                onToggle(opened)
            }
        }
        summaries.add {
            val s = summary()
            sum.text = s
            sum.visibility = if (s.isBlank()) View.GONE else View.VISIBLE
        }
        c.add(head, 12)
        c.add(body.root, 0)
        show(opened)
        refreshSummaries()
    }

    private fun refreshSummaries() = summaries.forEach { it() }

    private fun renderPresets() {
        fillRow(speedChips, TtsText.speeds.map { (label, v) ->
            chip(this, label, TtsPrefs.rate == v) {
                TtsPrefs.rate = v
                setRate(v)
                renderPresets()
            }
        })
        fillRow(pitchChips, listOf(t("Ниже", "Lower") to -20, t("Обычный", "Normal") to 0, t("Выше", "Higher") to 20).map { (label, v) ->
            chip(this, label, TtsPrefs.pitch == v) {
                TtsPrefs.pitch = v
                setPitch(v)
                renderPresets()
            }
        })
        refreshSummaries()
    }

    private fun renderSpeed() {
        fillRow(speedRow, listOf(1f, 1.25f, 1.5f, 1.75f, 2f).map { v ->
            chip(this, (if (v == 1f) "1" else v.toString()) + "×", TtsPrefs.playSpeed == v) {
                TtsPrefs.playSpeed = v
                TtsService.command(this, TtsService.ACTION_SPEED)
                renderSpeed()
            }
        })
    }

    private fun options() = TtsService.options(voice)

    // ---------- state ----------

    /** Draws [TtsState] into the bottom panel; called on every change of the job and of the text. */
    private fun render() {
        if (!::actionRow.isInitialized || !::input.isInitialized) return
        val s = TtsState
        val playing = s.playing
        val working = s.synth || s.saving
        val idle = !s.active
        actionRow.visibility = if (idle) View.VISIBLE else View.GONE
        playRow.visibility = if (playing) View.VISIBLE else View.GONE
        speedRow.visibility = if (playing) View.VISIBLE else View.GONE
        if (playing) renderSpeed()
        stopOnly.visibility = if (working && !playing) View.VISIBLE else View.GONE
        toggleBtn.text = if (s.paused) "▶" else "⏸"
        bar.visibility = if (working) View.VISIBLE else View.GONE
        bar.isIndeterminate = s.saving || s.total == 0
        bar.progress = TtsProgress.percent(s.done, s.total)
        val parts = maxOf(s.total, 1)
        val err = s.error
        status.setTextColor(if (idle && err != null) Palette.BAD else Palette.ON_SURFACE)
        status.text = when {
            s.saving -> t("Собираю и сохраняю MP3…", "Joining and saving the MP3…")
            playing && s.buffering -> t("Ждём часть ${s.part + 1}…", "Waiting for part ${s.part + 1}…") + synthSuffix()
            playing && s.paused -> t("Пауза · часть ${s.part + 1} из $parts", "Paused · part ${s.part + 1} of $parts") + synthSuffix()
            playing -> t("Играет · часть ${s.part + 1} из $parts", "Playing · part ${s.part + 1} of $parts") + synthSuffix()
            s.synth -> (if (s.total > 0) TtsProgress.label(s.done, s.total) else t("Подготовка…", "Preparing…")) +
                if (s.mode == TtsMode.SAVE) t("\nМожно свернуть приложение: озвучка идёт в фоне.", "\nYou can leave the app: it keeps going in the background.") else ""
            err != null -> err
            else -> {
                val n = input.text.length
                if (n == 0) t("Введите текст или выберите файл", "Enter text or pick a file")
                else t("Символов: $n · ", "Characters: $n · ") + TtsProgress.duration(TtsProgress.estimateSec(n, TtsPrefs.rate))
            }
        }
        val r = s.result
        resultBox.visibility = if (idle && r != null && r.exists()) View.VISIBLE else View.GONE
        resultText.text = s.savedName?.let { t("Последняя озвучка сохранена: $it", "Last audio saved: $it") }
            ?: t("Последняя озвучка готова, но не сохранена.", "The last audio is ready but not saved.")
        openBtn.visibility = if (s.savedUri != null) View.VISIBLE else View.GONE
    }

    private fun synthSuffix(): String =
        if (TtsState.synth) " · " + t("озвучено ${TtsProgress.percent(TtsState.done, TtsState.total)}%", "synthesised ${TtsProgress.percent(TtsState.done, TtsState.total)}%") else ""

    // ---------- actions ----------

    /** Text to read: everything, or the selection / the rest from the cursor when that option is on. */
    private fun textToRead(): String {
        val all = input.text.toString()
        if (!TtsPrefs.fromCursor) return all
        val a = minOf(input.selectionStart, input.selectionEnd).coerceIn(0, all.length)
        val b = maxOf(input.selectionStart, input.selectionEnd).coerceIn(0, all.length)
        return when {
            b > a -> all.substring(a, b)
            a in 1 until all.length -> all.substring(a)
            else -> all
        }
    }

    private fun busy(): Boolean {
        if (TtsState.synth || TtsState.saving) {
            toast(t("Озвучка уже идёт — дождитесь конца или нажмите «Стоп»", "A job is already running — wait for it or press Stop"))
            return true
        }
        return false
    }

    private fun askNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
    }

    private fun start(mode: TtsMode) {
        if (busy()) return
        val text = textToRead()
        if (text.isBlank()) {
            toast(t("Введите текст", "Enter some text"))
            return
        }
        val o = options()
        val r = TtsState.result
        val cached = r != null && r.exists() && TtsState.resultKey == sha1(text + "|" + o)
        if (cached && mode == TtsMode.SAVE && TtsState.savedName != null) {
            toast(t("Уже сохранено: ${TtsState.savedName}", "Already saved: ${TtsState.savedName}"))
            return
        }
        askNotifications()
        TtsState.error = null
        TtsService.startJob(this, text, o, mode, cached = cached)
        render()
    }

    private fun preview() {
        if (busy()) return
        askNotifications()
        TtsState.error = null
        TtsService.startJob(this, TtsText.sample(voice.substringBefore('-')), options(), TtsMode.PREVIEW, title = t("Образец голоса", "Voice sample"))
        render()
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (clip.isNullOrBlank()) {
            toast(t("Буфер обмена пуст", "The clipboard is empty"))
            return
        }
        input.setText(clip)
        input.setSelection(0)
        if (TtsPrefs.auto) autoPick(clip)
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, pickTypes)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_PICK)
    }

    private fun loadFile(uri: android.net.Uri) {
        status.text = t("Читаю файл…", "Reading file…")
        Lumen.async({ TtsText.load(this, uri, MAX_FILE) }, { e ->
            render()
            err(e)
        }) { (name, text) ->
            if (text.isBlank()) {
                toast(t("В файле нет текста", "No text in the file"))
                render()
                return@async
            }
            val cut = text.length > MAX_CHARS
            input.setText(if (cut) text.substring(0, MAX_CHARS) else text)
            input.setSelection(0)
            if (cut) toast(t("Файл длинный: загружены первые 300 000 символов", "Long file: the first 300,000 characters were loaded"))
            if (TtsPrefs.auto) autoPick(text)
            toast(t("Загружено: $name", "Loaded: $name"))
        }
    }

    private fun saveAs() {
        val f = TtsState.result
        if (f == null || !f.exists()) {
            toast(t("Сначала озвучьте текст", "Synthesise the text first"))
            return
        }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/mpeg")
            .putExtra(Intent.EXTRA_TITLE, TtsState.savedName?.substringAfterLast('/') ?: TtsProgress.fileName(TtsState.title, Fmt.isoDay(System.currentTimeMillis())))
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_SAVE)
    }

    private fun openSaved() {
        val u = TtsState.savedUri ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(u), "audio/mpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast(t("Нет приложения для открытия", "No app to open it"))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_PICK) {
            loadFile(uri)
            return
        }
        if (requestCode != REQ_SAVE) return
        val f = TtsState.result ?: return
        Lumen.async({
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        }, ::err) { toast(t("MP3 сохранён", "MP3 saved")) }
    }

    private fun localeName(tag: String): String {
        val ui = Locale.forLanguageTag(if (Settings.lang == "en") "en" else "ru")
        val name = Locale.forLanguageTag(tag).getDisplayName(ui).replaceFirstChar { it.titlecase(ui) }
        return "$name · $tag"
    }

    private fun voiceLabel(v: TtsVoice): String {
        val g = when (v.gender) {
            "Female" -> t("жен.", "female")
            "Male" -> t("муж.", "male")
            else -> v.gender
        }
        val extra = (if (v.multilingual) listOf(t("многоязычный", "multilingual")) else emptyList()) + v.personalities
        return v.display + " · " + g + if (extra.isNotEmpty()) " · " + extra.joinToString(", ") else ""
    }

    private fun renderVoicePickers() {
        val locales = voices.map { it.locale }.distinct().sortedBy { localeName(it) }
        if (locale !in locales) locale = voices.firstOrNull { it.shortName == voice }?.locale ?: locales.firstOrNull() ?: ""
        langBox.removeAllViews()
        langBox.addView(text(this, t("Язык", "Language"), 13f, color = Palette.MUTED))
        langBox.addView(spin(this, locales.map { it to localeName(it) }, locale) {
            if (it != locale) {
                locale = it
                val lang = it.substringBefore('-')
                voice = voices.firstOrNull { v -> v.locale == it && v.shortName == TtsPrefs.voiceFor(lang) }?.shortName
                    ?: voices.first { v -> v.locale == it }.shortName
                TtsPrefs.voice = voice
                renderVoices()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        renderVoices()
    }

    private fun renderVoices() {
        val ofLocale = voices.filter { it.locale == locale }
        val list = ofLocale.filter { gender == "all" || it.gender == gender }.ifEmpty { ofLocale }
        if (list.none { it.shortName == voice }) list.firstOrNull()?.let {
            voice = it.shortName
            TtsPrefs.voice = voice
        }
        voiceBox.removeAllViews()
        voiceBox.addView(chipRow(this, listOf("all" to t("Все", "All"), "Female" to t("Женские", "Female"), "Male" to t("Мужские", "Male")).map { (g, label) ->
            chip(this, label + " " + ofLocale.count { g == "all" || it.gender == g }, gender == g) {
                gender = g
                TtsPrefs.gender = g
                renderVoices()
            }
        }).apply { setPadding(0, dp(8), 0, 0) })
        voiceBox.addView(text(this, t("Голос", "Voice"), 13f, color = Palette.MUTED).apply { setPadding(0, dp(8), 0, 0) })
        voiceBox.addView(spin(this, list.map { it.shortName to voiceLabel(it) }, voice) {
            voice = it
            TtsPrefs.voice = it
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.firstOrNull { it.shortName == voice }?.categories?.takeIf { it.isNotEmpty() }?.let {
            voiceBox.addView(text(this, t("Подходит для: ", "Good for: ") + it.joinToString(", "), 13f, color = Palette.MUTED))
        }
        refreshSummaries()
    }

    private fun autoPick(text: String) {
        val lang = EdgeTts.detectLang(text) ?: return
        if (voice.substringBefore('-').equals(lang, true)) return
        val v = EdgeTts.pickVoice(voices, lang, TtsPrefs.voiceFor(lang)) ?: return
        voice = v.shortName
        locale = v.locale
        if (::langBox.isInitialized) renderVoicePickers()
    }

    private fun loadVoices(force: Boolean) {
        Lumen.async({ EdgeTts.voices(force) }, ::err) {
            voices = it
            if (TtsPrefs.auto) autoPick(input.text.toString())
            renderVoicePickers()
            if (force) toast(t("Голосов: ${it.size}", "Voices: ${it.size}"))
        }
    }
}
