/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.Locale

/** "Read aloud" screen: Microsoft Edge online neural voices with language, voice, rate, pitch and volume. */
class TtsActivity : LumenBase() {
    companion object {
        private const val REQ_SAVE = 7301
        private const val REQ_PICK = 7302
        private const val MAX_FILE = 30 * 1024 * 1024
        private const val MAX_CHARS = 300_000
        val pickTypes = arrayOf(
            "text/*", "application/epub+zip", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/x-fictionbook+xml", "application/x-subrip", "application/json", "application/xml", "application/octet-stream"
        )

        fun open(ctx: Context, text: String = "", title: String = "") {
            // very long texts are kept in a file: intent extras are size-limited
            Lumen.init(ctx)
            val f = File(ctx.cacheDir, "tts_input.txt")
            f.writeText(text)
            ctx.startActivity(Intent(ctx, TtsActivity::class.java).putExtra("title", title).putExtra("cached", true)
                .addFlags(if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private var voices: List<TtsVoice> = EdgeTts.builtIn
    private var locale = ""
    private var voice = TtsPrefs.voice
    private lateinit var input: EditText
    private lateinit var voiceBox: LinearLayout
    private lateinit var langBox: LinearLayout
    private lateinit var status: TextView
    private lateinit var playBtn: TextView
    private var player: MediaPlayer? = null
    private var busy = false
    private lateinit var bar: ProgressBar
    private var handledSeq = -1
    private var lastAudio: File? = null
    private var lastKey = ""
    private var gender = TtsPrefs.gender
    private var setRate: (Int) -> Unit = {}
    private var setPitch: (Int) -> Unit = {}
    private var speedRow: android.view.View? = null
    private var pitchRow: android.view.View? = null
    private var playRow: android.view.View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = (if (intent.getBooleanExtra("cached", false)) runCatching { File(cacheDir, "tts_input.txt").readText() }.getOrNull() else null)
            ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        locale = voice.split('-').take(2).joinToString("-")
        build(initial)
        if (TtsPrefs.auto && initial.isNotBlank()) autoPick(initial)
        loadVoices(false)
        // a file shared to Lumen (Share → Lumen) or opened from another app
        @Suppress("DEPRECATION")
        val shared = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: intent.data?.takeIf { intent.action == Intent.ACTION_VIEW }
        if (shared != null && savedInstanceState == null) loadFile(shared)
    }

    private fun build(initial: String) {
        val c = Col(this)
        c.muted(t(
            "Голоса Microsoft Edge (онлайн, бесплатно, без ключа). Текст отправляется на сервер Microsoft, нужен интернет.",
            "Microsoft Edge voices (online, free, no key). The text is sent to Microsoft's server; internet is required."
        ))
        input = c.edit(t("Текст для озвучки", "Text to read aloud"), initial, multiline = true).apply {
            minLines = 6
            maxLines = 14
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) = updateCount()
            })
        }
        c.row(
            btn(this, t("📎 Выбрать файл", "📎 Pick a file"), style = BtnStyle.TONAL) { pickFile() },
            btn(this, t("Очистить", "Clear"), style = BtnStyle.OUTLINED) { input.setText("") }
        )
        c.muted(t("TXT, MD, FB2, EPUB, DOCX, HTML, SRT/VTT субтитры. Кодировка определяется сама (UTF-8, UTF-16, Windows-1251).",
            "TXT, MD, FB2, EPUB, DOCX, HTML, SRT/VTT subtitles. Encoding is detected automatically (UTF-8, UTF-16, Windows-1251)."))
        status = c.muted("")
        bar = c.add(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = android.view.View.GONE })
        c.check(t("Подбирать язык голоса по тексту", "Match the voice language to the text"), TtsPrefs.auto) {
            TtsPrefs.auto = it
            if (it) autoPick(input.text.toString())
        }
        c.check(t("Читать выделенное или с места курсора", "Read the selection or from the cursor"), TtsPrefs.fromCursor) {
            TtsPrefs.fromCursor = it
        }
        c.check(t("Сохранять MP3 в Музыка/Lumen сразу после озвучки (Android 10+)", "Save the MP3 to Music/Lumen as soon as it is ready (Android 10+)"), TtsPrefs.autoSave) {
            TtsPrefs.autoSave = it
        }
        c.check(t("Проигрывать после озвучки", "Play when ready"), TtsPrefs.playAfter) { TtsPrefs.playAfter = it }
        c.h2(t("Язык и голос", "Language and voice"))
        langBox = c.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })
        voiceBox = c.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })
        c.row(
            btn(this, t("▷ Прослушать голос", "▷ Preview voice"), style = BtnStyle.TONAL) { preview() },
            btn(this, t("Обновить список", "Refresh list"), style = BtnStyle.TEXT) { loadVoices(true) }
        )

        c.h2(t("Звучание", "Sound"))
        speedRow = c.add(LinearLayout(this))
        setRate = slider(c, t("Скорость речи", "Speech rate"), -50, 100, 5, TtsPrefs.rate, "%") { TtsPrefs.rate = it; renderPresets() }
        pitchRow = c.add(LinearLayout(this))
        setPitch = slider(c, t("Тон (высота голоса)", "Pitch"), -50, 50, 2, TtsPrefs.pitch, " Hz") { TtsPrefs.pitch = it; renderPresets() }
        val setVolume = slider(c, t("Громкость", "Volume"), -50, 50, 5, TtsPrefs.volume, "%") { TtsPrefs.volume = it }
        c.button(t("Сбросить звучание", "Reset sound"), style = BtnStyle.TEXT) {
            TtsPrefs.rate = 0
            TtsPrefs.pitch = 0
            TtsPrefs.volume = 0
            setRate(0)
            setPitch(0)
            setVolume(0)
            renderPresets()
        }
        c.h2(t("Воспроизведение", "Playback"))
        c.muted(t("Меняет скорость уже озвученного звука без нового запроса (в MP3 не сохраняется).",
            "Changes the speed of already synthesized audio without a new request (not saved to MP3)."))
        playRow = c.add(LinearLayout(this))
        renderPresets()

        playBtn = c.button(t("▶  Озвучить", "▶  Read aloud"), primary = true) { play() }
        c.row(
            btn(this, t("⏸ Пауза", "⏸ Pause"), style = BtnStyle.TONAL) { pause() },
            btn(this, t("■ Стоп", "■ Stop"), style = BtnStyle.TONAL) { stop() }
        )
        c.button(t("Сохранить MP3", "Save MP3"), style = BtnStyle.OUTLINED) { save() }
        renderVoicePickers()
        updateCount()
        page(intent.getStringExtra("title")?.takeIf { it.isNotBlank() } ?: t("Озвучка текста", "Read aloud"), c.root)
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

    /** Quick presets above the sliders and for playback speed. */
    private fun renderPresets() {
        fillRow(speedRow, TtsText.speeds.map { (label, v) ->
            chip(this, label, TtsPrefs.rate == v) {
                TtsPrefs.rate = v
                setRate(v)
                renderPresets()
            }
        })
        fillRow(pitchRow, listOf(t("Ниже", "Lower") to -20, t("Обычный", "Normal") to 0, t("Выше", "Higher") to 20).map { (label, v) ->
            chip(this, label, TtsPrefs.pitch == v) {
                TtsPrefs.pitch = v
                setPitch(v)
                renderPresets()
            }
        })
        fillRow(playRow, listOf(1f, 1.25f, 1.5f, 1.75f, 2f).map { v ->
            chip(this, (if (v == 1f) "1" else v.toString()) + "×", TtsPrefs.playSpeed == v) {
                TtsPrefs.playSpeed = v
                applyPlaySpeed()
                renderPresets()
            }
        })
    }

    private fun applyPlaySpeed() {
        val p = player ?: return
        runCatching {
            val wasPlaying = p.isPlaying
            p.playbackParams = p.playbackParams.setSpeed(TtsPrefs.playSpeed)
            if (!wasPlaying) p.pause() // setting params starts playback on some devices
        }
    }

    private fun updateCount() {
        if (!::status.isInitialized || busy) return
        val n = input.text.length
        val parts = (input.text.toString().toByteArray().size + EdgeTts.CHUNK_BYTES - 1) / EdgeTts.CHUNK_BYTES
        status.text = t("Символов: $n", "Characters: $n") + if (parts > 1) t(" · частей: $parts", " · parts: $parts") else ""
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

    private fun options() = TtsOptions(voice, TtsPrefs.rate, TtsPrefs.pitch, TtsPrefs.volume)

    private fun play() {
        if (busy) return
        val text = textToRead()
        if (text.isBlank()) {
            toast(t("Введите текст", "Enter some text"))
            return
        }
        val o = options()
        val key = sha1(text + "|" + o)
        val cached = lastAudio
        if (cached != null && cached.exists() && key == lastKey) {
            startPlayer(cached)
            return
        }
        stopPlayer()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        TtsState.file = null
        TtsService.start(this, text, o, key, TtsPrefs.autoSave)
        showState()
    }

    /** Syncs the screen with the background job: progress bar, status, button, result. */
    private fun showState() {
        val s = TtsState
        busy = s.running
        playBtn.text = if (s.running) t("Озвучивается…", "Synthesizing…") else t("▶  Озвучить", "▶  Read aloud")
        bar.visibility = if (s.running) android.view.View.VISIBLE else android.view.View.GONE
        if (s.running) {
            bar.isIndeterminate = s.total == 0
            bar.progress = TtsProgress.percent(s.done, s.total)
            status.text = if (s.total > 0) TtsProgress.label(s.done, s.total) else t("Подготовка…", "Preparing…")
            return
        }
        updateCount()
        if (handledSeq == s.seq) return
        handledSeq = s.seq
        val f = s.file
        val e = s.error
        when {
            e != null -> status.text = e
            f != null && f.exists() -> {
                lastAudio = f
                lastKey = s.key
                status.text = s.savedAs?.let { t("Готово. Сохранено: $it", "Done. Saved: $it") } ?: t("Готово. Нажмите «Сохранить MP3»", "Done. Tap “Save MP3”")
                if (TtsPrefs.playAfter && !isFinishing) startPlayer(f)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        TtsState.listener = ::showState
        showState()
    }

    override fun onStop() {
        TtsState.listener = null
        super.onStop()
    }

    /** Whole text, or the selection / the rest from the cursor when that option is on. */
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

    private fun preview() {
        if (busy) return
        val lang = voice.substringBefore('-')
        busy = true
        status.text = t("Загружаю образец…", "Loading sample…")
        Lumen.async({
            File(cacheDir, "tts_sample.mp3").apply { writeBytes(EdgeTts.synthesize(TtsText.sample(lang), options())) }
        }, { e ->
            busy = false
            updateCount()
            err(e)
        }) { f ->
            busy = false
            if (!isFinishing) startPlayer(f)
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, pickTypes)
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_PICK)
    }

    private fun fileName(uri: android.net.Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    private fun loadFile(uri: android.net.Uri) {
        status.text = t("Читаю файл…", "Reading file…")
        Lumen.async({
            val name = fileName(uri)
            val bytes = contentResolver.openInputStream(uri)?.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_FILE) error(t("Файл больше 30 МБ", "File is larger than 30 MB"))
                }
                out.toByteArray()
            } ?: error(t("Не удалось открыть файл", "Could not open the file"))
            name to TtsText.fromFile(name, contentResolver.getType(uri), bytes)
        }, { e ->
            updateCount()
            err(e)
        }) { (name, text) ->
            if (text.isBlank()) {
                toast(t("В файле нет текста", "No text in the file"))
                updateCount()
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

    private fun startPlayer(f: File) {
        stopPlayer()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(f.absolutePath)
                setOnCompletionListener { status.text = t("Готово", "Done") }
                prepare()
                if (TtsPrefs.playSpeed != 1f) runCatching { playbackParams = playbackParams.setSpeed(TtsPrefs.playSpeed) }
                start()
            }
            status.text = t("Воспроизведение…", "Playing…")
        } catch (e: Exception) {
            err(e)
        }
    }

    private fun pause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
            status.text = t("Пауза", "Paused")
        } else {
            p.start()
            status.text = t("Воспроизведение…", "Playing…")
        }
    }

    private fun stop() {
        TtsService.stop(this)
        stopPlayer()
        status.text = t("Остановлено", "Stopped")
    }

    private fun stopPlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private fun save() {
        val f = lastAudio
        if (f == null || !f.exists() || lastKey != sha1(textToRead() + "|" + options())) {
            toast(t("Сначала нажмите «Озвучить»", "Press “Read aloud” first"))
            return
        }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/mpeg")
            .putExtra(Intent.EXTRA_TITLE, "lumen-tts-${Fmt.isoDay(System.currentTimeMillis())}.mp3")
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_SAVE)
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
        val f = lastAudio ?: return
        Lumen.async({
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        }, ::err) { toast(t("MP3 сохранён", "MP3 saved")) }
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }
}
