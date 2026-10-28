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
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.Locale

/** "Read aloud" screen: Microsoft Edge online neural voices with language, voice, rate, pitch and volume. */
class TtsActivity : LumenBase() {
    companion object {
        private const val REQ_SAVE = 7301

        fun open(ctx: Context, text: String = "", title: String = "") {
            // very long texts are kept in a file: intent extras are size-limited
            Lumen.init(ctx)
            val f = File(ctx.cacheDir, "tts_input.txt")
            f.writeText(text)
            ctx.startActivity(Intent(ctx, TtsActivity::class.java).putExtra("title", title).putExtra("file", f.absolutePath)
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
    @Volatile private var cancel = false
    private var lastAudio: File? = null
    private var lastKey = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initial = intent.getStringExtra("file")?.let { runCatching { File(it).readText() }.getOrNull() }
            ?: intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        locale = voice.split('-').take(2).joinToString("-")
        build(initial)
        if (TtsPrefs.auto && initial.isNotBlank()) autoPick(initial)
        loadVoices(false)
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
        status = c.muted("")
        c.check(t("Подбирать язык голоса по тексту", "Match the voice language to the text"), TtsPrefs.auto) {
            TtsPrefs.auto = it
            if (it) autoPick(input.text.toString())
        }
        c.h2(t("Язык и голос", "Language and voice"))
        langBox = c.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })
        voiceBox = c.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })
        c.button(t("Обновить список голосов", "Refresh voice list"), style = BtnStyle.TEXT) { loadVoices(true) }

        c.h2(t("Звучание", "Sound"))
        slider(c, t("Скорость", "Rate"), -50, 100, 5, TtsPrefs.rate, "%") { TtsPrefs.rate = it }
        slider(c, t("Тон (высота)", "Pitch"), -50, 50, 2, TtsPrefs.pitch, " Hz") { TtsPrefs.pitch = it }
        slider(c, t("Громкость", "Volume"), -50, 50, 5, TtsPrefs.volume, "%") { TtsPrefs.volume = it }
        c.button(t("Сбросить звучание", "Reset sound"), style = BtnStyle.TEXT) {
            TtsPrefs.rate = 0
            TtsPrefs.pitch = 0
            TtsPrefs.volume = 0
            recreate()
        }

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

    private fun slider(c: Col, label: String, min: Int, max: Int, step: Int, value: Int, unit: String, onSet: (Int) -> Unit) {
        val tv = c.p("")
        fun show(v: Int) {
            tv.text = "$label: ${EdgeTts.signed(v, unit)}"
        }
        show(value)
        c.add(SeekBar(this).apply {
            this.max = (max - min) / step
            progress = (value.coerceIn(min, max) - min) / step
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = show(min + p * step)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) = onSet(min + (sb?.progress ?: 0) * step)
            })
        }, 0)
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
        val list = voices.filter { it.locale == locale }
        if (list.none { it.shortName == voice }) list.firstOrNull()?.let { voice = it.shortName }
        voiceBox.removeAllViews()
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
        val text = input.text.toString()
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
        busy = true
        cancel = false
        playBtn.text = t("Озвучивается…", "Synthesizing…")
        Lumen.async({
            val mp3 = EdgeTts.synthesize(text, o, { cancel }) { done, total ->
                if (total > 1) Lumen.ui { status.text = t("Озвучено частей: $done из $total", "Parts done: $done of $total") }
            }
            File(cacheDir, "tts_last.mp3").apply { writeBytes(mp3) }
        }, { e ->
            busy = false
            playBtn.text = t("▶  Озвучить", "▶  Read aloud")
            updateCount()
            err(e)
        }) { f ->
            busy = false
            playBtn.text = t("▶  Озвучить", "▶  Read aloud")
            updateCount()
            if (cancel || isFinishing) return@async
            lastAudio = f
            lastKey = key
            startPlayer(f)
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
        cancel = true
        stopPlayer()
        status.text = t("Остановлено", "Stopped")
    }

    private fun stopPlayer() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    private fun save() {
        val f = lastAudio
        if (f == null || !f.exists() || lastKey != sha1(input.text.toString() + "|" + options())) {
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
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK) return
        val f = lastAudio ?: return
        Lumen.async({
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        }, ::err) { toast(t("MP3 сохранён", "MP3 saved")) }
    }

    override fun onDestroy() {
        cancel = true
        stopPlayer()
        super.onDestroy()
    }
}
