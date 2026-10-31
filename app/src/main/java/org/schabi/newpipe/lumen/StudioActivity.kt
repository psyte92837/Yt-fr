/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings of Lumen Studio kept between visits. */
object StudioPrefs {
    private val p get() = Lumen.prefs
    var script: String
        get() = p.getString("studio_script", "") ?: ""
        set(v) = p.edit().putString("studio_script", v).apply()
    var topic: String
        get() = p.getString("studio_topic", "") ?: ""
        set(v) = p.edit().putString("studio_topic", v).apply()
    var seconds: Int
        get() = p.getInt("studio_seconds", 60)
        set(v) = p.edit().putInt("studio_seconds", v).apply()
    /** "edge", "mic" or "file". */
    var voiceMode: String
        get() = p.getString("studio_voice_mode", "edge") ?: "edge"
        set(v) = p.edit().putString("studio_voice_mode", v).apply()
    var voice: String
        get() = p.getString("studio_voice", "") ?: ""
        set(v) = p.edit().putString("studio_voice", v).apply()
    var rate: Int
        get() = p.getInt("studio_rate", 10)
        set(v) = p.edit().putInt("studio_rate", v).apply()
    var style: String
        get() = p.getString("studio_style", StudioStyle.DARK.name) ?: StudioStyle.DARK.name
        set(v) = p.edit().putString("studio_style", v).apply()
    var accent: Int
        get() = p.getInt("studio_accent", ACCENTS[0].first)
        set(v) = p.edit().putInt("studio_accent", v).apply()
    var height: Int
        get() = p.getInt("studio_height", 1280)
        set(v) = p.edit().putInt("studio_height", v).apply()
    var prompter: Boolean
        get() = p.getBoolean("studio_prompter", true)
        set(v) = p.edit().putBoolean("studio_prompter", v).apply()
    /** VIDEO style: graphics as cards over the speaker instead of full-screen inserts. */
    var overlay: Boolean
        get() = p.getBoolean("studio_overlay", false)
        set(v) = p.edit().putBoolean("studio_overlay", v).apply()
    var musicName: String
        get() = p.getString("studio_music_name", "") ?: ""
        set(v) = p.edit().putString("studio_music_name", v).apply()
    /** Music loudness, 0..1 of full scale. */
    var musicVolume: Float
        get() = p.getFloat("studio_music_volume", 0.15f)
        set(v) = p.edit().putFloat("studio_music_volume", v).apply()
    /** Credit line of the chosen track ("" for own files and CC0). */
    var musicCredit: String
        get() = p.getString("studio_music_credit", "") ?: ""
        set(v) = p.edit().putString("studio_music_credit", v).apply()
    var musicCommercial: Boolean
        get() = p.getBoolean("studio_music_commercial", true)
        set(v) = p.edit().putBoolean("studio_music_commercial", v).apply()
    var musicSource: String
        get() = p.getString("studio_music_source", StudioMusic.SRC_OPENVERSE) ?: StudioMusic.SRC_OPENVERSE
        set(v) = p.edit().putString("studio_music_source", v).apply()
    var musicDuck: Boolean
        get() = p.getBoolean("studio_music_duck", true)
        set(v) = p.edit().putBoolean("studio_music_duck", v).apply()
    /** Auto-edit: cut pauses out of the take (jump cuts). */
    var cutPauses: Boolean
        get() = p.getBoolean("studio_cut_pauses", true)
        set(v) = p.edit().putBoolean("studio_cut_pauses", v).apply()
    /** Auto-edit: camera zooms and push-ins on the video. */
    var zooms: Boolean
        get() = p.getBoolean("studio_zooms", true)
        set(v) = p.edit().putBoolean("studio_zooms", v).apply()
    /** Auto-edit: inserts slide in. */
    var transitions: Boolean
        get() = p.getBoolean("studio_transitions", true)
        set(v) = p.edit().putBoolean("studio_transitions", v).apply()
    /** Background clips "file:durationMs;…" in the order they play. */
    var bgClips: String
        get() = p.getString("studio_bg_clips", "") ?: ""
        set(v) = p.edit().putString("studio_bg_clips", v).apply()
    /** The take follows the script (teleprompter); otherwise it is recognised. */
    var readScript: Boolean
        get() = p.getBoolean("studio_read_script", true)
        set(v) = p.edit().putBoolean("studio_read_script", v).apply()

    val ACCENTS = listOf(
        0xFF4F8BFF.toInt() to "🔵",
        0xFFFF8A3D.toInt() to "🟠",
        0xFF34C759.toInt() to "🟢",
        0xFFA970FF.toInt() to "🟣",
        0xFFFF4D6A.toInt() to "🔴"
    )
}

/**
 * Lumen Studio: script → voice (Edge voice, microphone or audio file) → analysis → vertical MP4.
 * Every long step shows its own progress bar.
 */
class StudioActivity : LumenBase() {
    companion object {
        private const val REQ_AUDIO = 7401
        private const val REQ_MIC = 7402
        private const val REQ_VIDEO = 7403
        private const val REQ_CAPTURE = 7404
        private const val REQ_MUSIC = 7405
        private const val REQ_BG = 7406

        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, StudioActivity::class.java)
                .addFlags(if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val dir by lazy { File(filesDir, "lumen/studio").apply { mkdirs() } }
    private val takeFile get() = File(dir, "take.wav")
    private val sourceFile get() = File(dir, "source.mp4")
    private val musicFile get() = File(dir, "music.wav")
    private var previewBusy = false
    private var previewAgain = false

    // results of the analysis
    private var pcm: StudioAudio.Pcm? = null
    private var words: List<SWord> = emptyList()
    private var script: SScript? = null
    private var ranges: List<IntRange>? = null
    private var stats: VoiceStats? = null
    private var transcript = ""
    /** Parts of the take kept after cutting pauses (null = nothing cut). */
    private var cuts: List<Span>? = null
    private val bgDir get() = File(dir, "bg").apply { mkdirs() }
    private var board: List<Pair<Long, android.graphics.Bitmap>> = emptyList()
    private var boardKey = ""
    private var lastVideo: File? = null
    private var savedAs = ""

    @Volatile private var cancel = false
    private var wasRunning = false
    private val analysisFile get() = File(dir, "analysis.json")
    private val voiceFile get() = File(dir, "voice.wav")
    private var busy = ""
    private var recorder: StudioRecorder? = null
    private var player: MediaPlayer? = null
    private var voices: List<TtsVoice> = EdgeTts.builtIn

    private lateinit var scriptEdit: EditText
    private lateinit var topicEdit: EditText

    // live widgets updated without a full re-render
    private val bars = HashMap<String, Pair<TextView, ProgressBar>>()
    private var levelBar: ProgressBar? = null
    private var timerText: TextView? = null
    private var prompterScroll: ScrollView? = null
    private var preview: ImageView? = null
    private var previewMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scriptEdit = edt(this, t("Сценарий: сцены через пустую строку", "Script: scenes separated by a blank line"), StudioPrefs.script, multiline = true).apply {
            minLines = 8
            maxLines = 18
        }
        topicEdit = edt(this, t("Тема или идея ролика", "Topic or idea of the video"), StudioPrefs.topic)
        wasRunning = StudioJob.running
        StudioJob.result?.takeIf { it.exists() && !StudioJob.running }?.let {
            lastVideo = it
            savedAs = StudioJob.savedAs
        }
        render()
        restoreAnalysis()
        Lumen.async({ EdgeTts.voices() }, {}) {
            voices = it
            if (busy.isEmpty() && recorder == null) render()
        }
    }

    override fun onPause() {
        saveInputs()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        StudioJob.listener = ::onJob
        if (wasRunning && !StudioJob.running) onJob()
    }

    override fun onStop() {
        StudioJob.listener = null
        super.onStop()
    }

    // ---------- saved analysis ----------

    /** Keeps step 3 on disk: words, scenes and the voice track. */
    private fun saveAnalysis() {
        val p = pcm ?: return
        val sc = script ?: return
        val st = stats ?: return
        val a = Studio.Analysis(StudioPrefs.voiceMode, sc, words, ranges, transcript, st, cuts)
        val w = words
        Lumen.async({
            writeWav(voiceFile, p.data, p.rate)
            analysisFile.writeText(Studio.analysisToJson(a.copy(words = w)))
        }, { e -> L.w("studio", "save analysis: ${e.message}") }) {}
    }

    private fun restoreAnalysis() {
        if (!analysisFile.exists() || !voiceFile.exists()) return
        Lumen.async({
            val a = Studio.analysisFromJson(analysisFile.readText()) ?: error("bad analysis")
            val (data, rate) = Studio.readWav(voiceFile.readBytes()) ?: error("bad voice")
            a to StudioAudio.Pcm(data, rate)
        }, { e -> L.w("studio", "restore analysis: ${e.message}") }) { (a, p) ->
            if (a.mode != StudioPrefs.voiceMode || words.isNotEmpty() || busy.isNotEmpty()) return@async
            pcm = p
            words = a.words
            script = a.script
            ranges = a.ranges
            transcript = a.transcript
            stats = a.stats
            cuts = a.cuts
            render()
        }
    }

    private fun writeWav(f: File, pcm: ShortArray, rate: Int) {
        val data = java.nio.ByteBuffer.allocate(pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.asShortBuffer().put(pcm)
        f.outputStream().use {
            it.write(Studio.wavHeader(pcm.size * 2, rate))
            it.write(data.array())
        }
    }

    override fun onDestroy() {
        musicPreview?.release()
        musicPreview = null
        cancel = true
        recorder?.stop()
        player?.release()
        super.onDestroy()
    }

    private fun saveInputs() {
        StudioPrefs.script = scriptEdit.text.toString()
        StudioPrefs.topic = topicEdit.text.toString()
    }

    private fun lang(): String = EdgeTts.detectLang(scriptEdit.text.toString())
        ?: if (Settings.lang == "en") "en" else "ru"

    // ---------- layout ----------

    private fun render() {
        saveInputs()
        bars.clear()
        val c = Col(this)
        c.muted(t(
            "Короткие вертикальные ролики: слова появляются под голос, рядом схемы из эмодзи. Четыре шага, всё собирается на телефоне.",
            "Short vertical videos: words appear with the voice, next to emoji schemes. Four steps, everything is built on the phone."
        ))
        c.steps(stepNow(), 4)
        scriptCard(c)
        voiceCard(c)
        analyseCard(c)
        videoCard(c)
        page("Lumen Studio", c.root, keepScroll = true)
    }

    private fun stepNow(): Int = when {
        lastVideo != null -> 4
        words.isNotEmpty() -> 3
        pcm != null || takeFile.exists() -> 2
        else -> 1
    }

    /** Status line + progress bar for a running job [key]; shown while [busy] == key. */
    private fun Col.progress(key: String, initial: String) {
        if (busy != key) return
        val pair = working(initial)
        bars[key] = pair
        button(t("Отмена", "Cancel"), style = BtnStyle.TEXT) { cancel = true }
    }

    private fun report(key: String, msg: String) = Lumen.ui {
        bars[key]?.let { (tv, bar) ->
            tv.text = msg
            bar.showProgress(msg)
        }
    }

    private fun scriptCard(c: Col) = c.card {
        add(text(ctx, t("1. Сценарий", "1. Script"), Type.TITLE_M, bold = true), 0)
        add(detach(topicEdit), 8)
        spinner(t("Длительность", "Length"), listOf(30 to "30 с", 60 to "60 с", 90 to "90 с", 120 to "2 мин"), StudioPrefs.seconds) {
            StudioPrefs.seconds = it
            render()
        }
        row(
            btn(ctx, t("✨ Написать (AI)", "✨ Write (AI)"), style = BtnStyle.TONAL) { writeScript() },
            btn(ctx, t("Формат", "Format"), style = BtnStyle.TEXT) { formatHelp() }
        )
        progress("ai_script", t("AI пишет сценарий…", "AI is writing the script…"))
        add(detach(scriptEdit), 8)
        val sc = Studio.parseText(scriptEdit.text.toString())
        if (sc.scenes.isNotEmpty()) {
            val n = sc.scenes.sumOf { Studio.tokens(it.say).size }
            muted(t("Сцен: ${sc.scenes.size} · слов: $n · примерно ${Studio.clock(n * 1000L * 10 / 24)}",
                "Scenes: ${sc.scenes.size} · words: $n · about ${Studio.clock(n * 1000L * 10 / 24)}"))
        }
    }

    private fun voiceCard(c: Col) = c.card {
        add(text(ctx, t("2. Голос", "2. Voice"), Type.TITLE_M, bold = true), 0)
        val modes = listOf("edge", "mic", "file", "video")
        add(segBar(ctx, listOf("Microsoft", t("Микрофон", "Mic"), t("Аудио", "Audio"), t("Видео", "Video")),
            modes.indexOf(StudioPrefs.voiceMode).coerceAtLeast(0)) {
            StudioPrefs.voiceMode = modes[it]
            resetAnalysis()
            render()
        }, 8)
        when (StudioPrefs.voiceMode) {
            "edge" -> edgeVoice(this)
            "mic" -> micVoice(this)
            "video" -> videoVoice(this)
            else -> fileVoice(this)
        }
    }

    private fun edgeVoice(c: Col) {
        val lang = lang()
        val ofLang = voices.filter { it.locale.substringBefore('-').equals(lang, true) }.take(14)
        val cur = ofLang.firstOrNull { it.shortName == StudioPrefs.voice } ?: EdgeTts.pickVoice(voices, lang, TtsPrefs.voiceFor(lang))
        c.muted(t("Точные тайминги каждого слова. Нужен интернет, текст уходит на сервер Microsoft.",
            "Exact timing of every word. Needs internet; the text goes to Microsoft's server."))
        c.add(text(this, t("Голос", "Voice"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        c.chips(ofLang.map { v ->
            chip(this, (if (v.gender == "Male") "♂ " else "♀ ") + v.display + if (v.locale.length > 2 && ofLang.map { it.locale }.distinct().size > 1) " · " + v.locale.substringAfter('-') else "",
                v.shortName == cur?.shortName) {
                StudioPrefs.voice = v.shortName
                resetAnalysis()
                render()
            }
        })
        c.spinner(t("Темп речи", "Speech rate"), listOf(-10 to t("спокойно", "calm"), 0 to t("обычно", "normal"), 10 to t("бодро", "brisk"), 20 to t("быстро", "fast")), StudioPrefs.rate) {
            StudioPrefs.rate = it
            resetAnalysis()
            render()
        }
    }

    private fun micVoice(c: Col) {
        val rec = recorder
        if (rec != null) {
            c.add(text(this, t("● Идёт запись", "● Recording"), Type.TITLE_M, bold = true, color = 0xFFFF4D6A.toInt()), 12)
            timerText = c.add(text(this, "0:00", 28f, bold = true), 4)
            levelBar = c.add(busyBar(this).apply { isIndeterminate = false; progress = 0 }, 8).also { it.layoutParams.height = dp(8) }
            if (StudioPrefs.prompter) prompter(c)
            c.button(t("■ Остановить", "■ Stop"), primary = true) { stopRecording() }
            return
        }
        c.muted(t("Запись до ${StudioRecorder.MAX_MIN} минут. Говорите в 15–20 см от телефона, в тихом месте.",
            "Up to ${StudioRecorder.MAX_MIN} minutes. Speak 15–20 cm from the phone, somewhere quiet."))
        c.check(t("Суфлёр: показывать сценарий при записи", "Teleprompter: show the script while recording"), StudioPrefs.prompter) { StudioPrefs.prompter = it }
        c.check(t("Я читаю сценарий (иначе запись распознаётся)", "I read the script (otherwise the take is recognised)"), StudioPrefs.readScript) {
            StudioPrefs.readScript = it
            resetAnalysis()
        }
        cutCheck(c)
        c.button(if (takeFile.exists()) t("● Перезаписать", "● Record again") else t("● Начать запись", "● Start recording"), primary = !takeFile.exists()) { startRecording() }
        takeControls(c)
    }

    private fun fileVoice(c: Col) {
        c.muted(t("MP3, M4A, WAV, OGG, а также видео: берётся звуковая дорожка. До 15 минут.",
            "MP3, M4A, WAV, OGG, or a video: its sound track is used. Up to 15 minutes."))
        c.check(t("В записи звучит текст сценария", "The recording speaks the script text"), StudioPrefs.readScript) {
            StudioPrefs.readScript = it
            resetAnalysis()
        }
        cutCheck(c)
        c.button(t("📎 Выбрать аудио", "📎 Pick audio"), style = BtnStyle.TONAL) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*")), REQ_AUDIO)
        }
        c.progress("import", t("Чтение файла…", "Reading the file…"))
        takeControls(c)
    }

    /** The speaker's own video: its sound is the voice, its picture the background (style "Montage"). */
    private fun videoVoice(c: Col) {
        c.muted(t("Снимите себя на камеру или выберите готовое видео. Звук станет озвучкой, а картинка — фоном ролика; графика встанет вставками.",
            "Film yourself or pick a video. Its sound becomes the voice-over, its picture the background; graphics are cut in."))
        c.check(t("В видео звучит текст сценария", "The video speaks the script text"), StudioPrefs.readScript) {
            StudioPrefs.readScript = it
            resetAnalysis()
        }
        cutCheck(c)
        c.row(
            btn(this, t("🎥 Снять", "🎥 Film"), style = BtnStyle.TONAL) { captureVideo() },
            btn(this, t("📎 Выбрать видео", "📎 Pick video"), style = BtnStyle.TONAL) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"), REQ_VIDEO)
            }
        )
        c.progress("import", t("Чтение видео…", "Reading the video…"))
        if (sourceFile.exists()) c.muted("🎞 " + t("Видео: ", "Video: ") + "${sourceFile.length() / (1024 * 1024)} MB")
        takeControls(c)
    }

    private fun captureVideo() {
        val i = Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE)
            .putExtra("android.intent.extras.CAMERA_FACING", 1)
            .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            .putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY, 1)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_CAPTURE)
        } catch (e: android.content.ActivityNotFoundException) {
            toast(t("Нет приложения камеры", "No camera app found"))
        }
    }

    /** Copies the picked video into the app (the link may expire) and takes its sound as the voice. */
    private fun importVideo(uri: Uri) {
        busy = "import"
        cancel = false
        render()
        Lumen.async({
            val total = runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
            }.getOrNull() ?: -1L
            val tmp = File(dir, "source.tmp")
            contentResolver.openInputStream(uri)?.use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        if (cancel) throw java.util.concurrent.CancellationException()
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && done - last > total / 50) {
                            last = done
                            report("import", t("Копирование ${done * 100 / total}/100", "Copying ${done * 100 / total}/100"))
                        }
                    }
                }
            } ?: error(t("Не удалось открыть видео", "Cannot open the video"))
            if (!tmp.renameTo(sourceFile)) {
                tmp.copyTo(sourceFile, overwrite = true)
                tmp.delete()
            }
            report("import", t("Звук из видео…", "Sound of the video…"))
            val p = StudioAudio.decode(sourceFile)
            if (p.data.isEmpty()) error(t("В видео нет звука", "The video has no sound"))
            writeTake(Studio.resample(p.data, p.rate, 44_100), 44_100)
        }, { e ->
            busy = ""
            render()
            if (e !is java.util.concurrent.CancellationException) err(e)
        }) {
            busy = ""
            StudioPrefs.style = StudioStyle.VIDEO.name
            resetAnalysis()
            render()
        }
    }

    private fun cutCheck(c: Col) = c.check(t("✂ Вырезать паузы (джамп-каты)", "✂ Cut out pauses (jump cuts)"), StudioPrefs.cutPauses) {
        StudioPrefs.cutPauses = it
        resetAnalysis()
    }

    private fun takeControls(c: Col) {
        if (!takeFile.exists()) return
        val ms = runCatching { (takeFile.length() - 44) / 2 * 1000 / 44_100 }.getOrDefault(0)
        c.muted(t("Запись: ", "Take: ") + Studio.clock(ms))
        c.row(
            btn(this, if (player?.isPlaying == true) t("■ Стоп", "■ Stop") else t("▶ Прослушать", "▶ Listen"), style = BtnStyle.TONAL) { togglePlay() },
            btn(this, t("Удалить", "Delete"), style = BtnStyle.TEXT) {
                player?.release()
                player = null
                takeFile.delete()
                if (StudioPrefs.voiceMode == "video") sourceFile.delete()
                resetAnalysis()
                render()
            }
        )
    }

    /** Big script text that scrolls with the expected pace of reading. */
    private fun prompter(c: Col) {
        val tv = text(this, Studio.parseText(scriptEdit.text.toString()).scenes.joinToString("\n\n") { it.say }, 24f, bold = true).apply {
            setPadding(dp(16), dp(80), dp(16), dp(200))
        }
        val sv = ScrollView(this).apply {
            addView(tv)
            background = roundBg(Palette.SURFACE, 12f, this@StudioActivity)
        }
        prompterScroll = sv
        c.add(sv, 12).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280))
    }

    private fun analyseCard(c: Col) = c.card {
        add(text(ctx, t("3. Разбор", "3. Analysis"), Type.TITLE_M, bold = true), 0)
        muted(when (StudioPrefs.voiceMode) {
            "edge" -> t("Озвучка сцен и разметка слов по времени.", "Voicing the scenes and timing every word.")
            else -> t("Поиск речи и пауз, темп, расстановка слов по времени.", "Finding speech and pauses, the pace, timing every word.")
        })
        if (busy.isEmpty()) button(if (words.isEmpty()) t("🔍 Разобрать", "🔍 Analyse") else t("🔍 Разобрать заново", "🔍 Analyse again"), primary = words.isEmpty()) { analyse() }
        progress("analyse", t("Подготовка…", "Preparing…"))
        progress("ai_scenes", t("AI делит текст на сцены…", "AI is splitting the text into scenes…"))
        val s = stats
        val sc = script
        if (s != null && sc != null && words.isNotEmpty()) {
            p(t("Длительность ${Studio.clock(s.totalMs)} · речь ${Studio.clock(s.speechMs)} · слов ${s.words} · темп ${s.wordsPerMinute} сл/мин",
                "Length ${Studio.clock(s.totalMs)} · speech ${Studio.clock(s.speechMs)} · words ${s.words} · pace ${s.wordsPerMinute} wpm"))
            if (StudioPrefs.voiceMode != "edge") {
                muted(t("Пауз: ${s.pauses} · самая длинная ${s.longestPauseMs / 100 / 10.0} с", "Pauses: ${s.pauses} · longest ${s.longestPauseMs / 100 / 10.0} s"))
                paceTip(s)?.let { muted("💡 $it") }
            }
            if (transcript.isNotBlank()) {
                row(
                    btn(ctx, t("✨ Разметить сцены (AI)", "✨ Split into scenes (AI)"), style = BtnStyle.TONAL) { aiScenes() },
                    btn(ctx, t("Текст", "Text"), style = BtnStyle.TEXT) { showText(t("Расшифровка", "Transcript"), transcript) }
                )
            }
            val plan = currentPlan() ?: return@card
            add(text(ctx, t("Сцены", "Scenes"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            plan.times.take(40).forEach { st ->
                val scn = sc.scenes[st.scene]
                muted("${Studio.clock(st.startMs)} · ${kindLabel(scn.kind)} · " + scn.say.take(70) + if (scn.say.length > 70) "…" else "")
            }
        }
    }

    private fun paceTip(s: VoiceStats): String? = when {
        s.wordsPerMinute in 1..109 -> t("Темп спокойный. Для Shorts обычно 140–170 слов в минуту.", "A calm pace. Shorts usually run at 140–170 words per minute.")
        s.wordsPerMinute > 190 -> t("Очень быстро: зрителю может быть трудно успевать за текстом.", "Very fast: viewers may struggle to follow the text.")
        s.longestPauseMs > 2500 -> t("Есть длинные паузы, их можно вырезать и перезаписать.", "There are long pauses; consider re-recording.")
        else -> null
    }

    private fun kindLabel(k: String) = when (k) {
        "step" -> t("этап", "step")
        "icons" -> t("плитки", "tiles")
        "list" -> t("список", "list")
        "flow" -> t("процесс", "flow")
        "cycle" -> t("цикл", "cycle")
        "big" -> t("крупно", "big")
        "stat" -> t("цифра", "number")
        else -> t("текст", "text")
    }

    private fun videoCard(c: Col) = c.card {
        add(text(ctx, t("4. Ролик", "4. Video"), Type.TITLE_M, bold = true), 0)
        val styles = listOfNotNull(StudioStyle.DARK, StudioStyle.LIGHT, if (montageReady()) StudioStyle.VIDEO else null)
        val labels = listOf(t("Тёмный", "Dark"), t("Светлый", "Light"), t("Монтаж", "Montage")).take(styles.size)
        add(segBar(ctx, labels, styles.indexOf(currentStyle()).coerceAtLeast(0)) {
            StudioPrefs.style = styles[it].name
            render()
        }, 8)
        muted(when (currentStyle()) {
            StudioStyle.DARK -> t("Слова появляются группами с размытием, схемы из эмодзи на тёмном фоне.", "Word groups blur in, emoji schemes on a dark screen.")
            StudioStyle.LIGHT -> t("Заголовок капсом и одно крупное слово под голос на светлом фоне.", "A capital headline and one big word at a time on a light screen.")
            StudioStyle.VIDEO -> if (videoReady()) t("Ваше видео, слово под голос внизу, графика вставками.", "Your video, the spoken word below, graphics cut in.")
            else t("Фоновые клипы по кругу под озвучку, слово внизу, графика вставками.", "Background clips loop under the voice-over, the word below, graphics cut in.")
        })
        if (currentStyle() == StudioStyle.VIDEO) {
            add(segBar(ctx, listOf(t("Вставки на весь экран", "Full-screen inserts"), t("Карточки поверх видео", "Cards over video")),
                if (StudioPrefs.overlay) 1 else 0) {
                StudioPrefs.overlay = it == 1
                render()
            }, 8)
            add(text(ctx, t("Автомонтаж", "Auto-edit"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            check(t("🔍 Зумы и наезды камеры по сценам", "🔍 Camera zooms and push-ins per scene"), StudioPrefs.zooms) {
                StudioPrefs.zooms = it
                render()
            }
            check(t("↔ Динамичные переходы к вставкам", "↔ Dynamic transitions to inserts"), StudioPrefs.transitions) {
                StudioPrefs.transitions = it
                render()
            }
        }
        if (!videoReady()) bgBlock(this)
        add(text(ctx, t("Цвет акцента", "Accent colour"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        chips(StudioPrefs.ACCENTS.map { (col, e) ->
            chip(ctx, e, col == StudioPrefs.accent) {
                StudioPrefs.accent = col
                render()
            }
        })
        spinner(t("Качество", "Quality"), listOf(1280 to "720×1280", 1920 to "1080×1920"), StudioPrefs.height) {
            StudioPrefs.height = it
        }
        musicBlock(this)
        val plan = currentPlan()
        if (plan == null) {
            muted(t("Сначала выполните разбор (шаг 3).", "Run the analysis first (step 3)."))
            return@card
        }
        // preview of any moment
        val img = ImageView(ctx).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = roundBg(0x00000000, 12f, ctx, Palette.OUTLINE)
        }
        preview = img
        add(img, 12).layoutParams = LinearLayout.LayoutParams(dp(180), dp(320)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
        val seek = SeekBar(ctx).apply {
            max = 1000
            progress = if (plan.totalMs > 0) (previewMs * 1000 / plan.totalMs).toInt() else 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    previewMs = plan.totalMs * v / 1000
                    drawPreview(plan)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        add(seek, 4)
        drawPreview(plan)
        storyboard(this, plan)
        if (StudioJob.running) {
            bars["render"] = working(StudioJob.message)
            muted(t("Сборка идёт в фоне: экран можно закрыть, прогресс — в уведомлении.", "Building in the background: you can close the screen, progress is in the notification."))
            button(t("Отмена", "Cancel"), style = BtnStyle.TEXT) { StudioService.cancel(this@StudioActivity) }
        } else if (busy.isEmpty()) {
            button(t("🎬 Создать ролик", "🎬 Make video"), primary = true) { renderVideo() }
        }
        lastVideo?.takeIf { it.exists() }?.let { f ->
            p("✅ " + t("Готово: ", "Done: ") + savedAs.ifBlank { f.name })
            if (StudioPrefs.musicCredit.isNotBlank() && musicFile.exists()) button(t("⧉ Подпись музыки для описания", "⧉ Music credit for the description"), style = BtnStyle.TEXT) { copyCredit() }
            row(
                btn(ctx, t("▶ Открыть", "▶ Open"), style = BtnStyle.TONAL) { openFile(f) },
                btn(ctx, t("Поделиться", "Share"), style = BtnStyle.TONAL) { ReportKit.share(this@StudioActivity, f, false, f.name, "video/mp4") }
            )
        }
    }

    /** Storyboard: a small frame of every scene; a tap shows that scene in the preview. */
    private fun storyboard(c: Col, plan: StudioPlan) {
        val key = "${plan.style}:${plan.accent}:${plan.overlay}:${plan.zooms}:${plan.times.size}:${plan.totalMs}:${clips().size}"
        if (board.isNotEmpty() && boardKey == key) {
            c.add(text(this, t("Раскадровка", "Storyboard"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val flow = FlowLayout(this, dp(6), dp(6))
            board.forEachIndexed { i, (at, bmp) ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ImageView(this@StudioActivity).apply {
                        setImageBitmap(bmp)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = roundBg(0x00000000, 6f, this@StudioActivity, Palette.OUTLINE)
                    }, LinearLayout.LayoutParams(dp(72), dp(128)))
                    addView(text(this@StudioActivity, "${i + 1} · ${Studio.clock(at)}", 11f, color = Palette.MUTED))
                    contentDescription = t("Сцена ${i + 1}", "Scene ${i + 1}")
                    setOnClickListener {
                        previewMs = at
                        render()
                    }
                }
                flow.addView(cell)
            }
            c.add(flow, 4)
            return
        }
        c.progress("board", t("Раскадровка…", "Storyboard…"))
        if (busy.isEmpty()) c.button(t("🎞 Раскадровка по сценам", "🎞 Storyboard of the scenes"), style = BtnStyle.TEXT) { buildBoard(plan, key) }
    }

    private fun buildBoard(plan: StudioPlan, key: String) {
        busy = "board"
        cancel = false
        render()
        val bg = if (plan.style == StudioStyle.VIDEO) bgSpec() else null
        Lumen.async({
            val n = plan.times.size
            plan.times.mapIndexed { i, st ->
                if (cancel) throw java.util.concurrent.CancellationException()
                report("board", t("Раскадровка $i/$n", "Storyboard $i/$n"))
                val at = (st.startMs + minOf(900L, (st.endMs - st.startMs) / 2)).coerceAtMost(plan.totalMs)
                at to StudioVideo.preview(plan, at, 144, 256, bg)
            }
        }, { e ->
            busy = ""
            render()
            if (e !is java.util.concurrent.CancellationException) err(e)
        }) { list ->
            busy = ""
            board = list
            boardKey = key
            render()
        }
    }

    /** Background music: pick, loudness, ducking under the voice. */
    private fun musicBlock(c: Col) {
        c.add(text(this, t("Музыка", "Music"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        c.progress("music", t("Чтение музыки…", "Reading the music…"))
        if (!musicFile.exists()) {
            if (busy != "music") c.row(
                btn(this, t("🔎 Найти бесплатную", "🔎 Find free music"), style = BtnStyle.TONAL) { musicSearch() },
                btn(this, t("📎 Свой файл", "📎 Own file"), style = BtnStyle.TEXT) { pickMusic() }
            )
            c.muted(t("Музыка зациклится на длину ролика, плавно начнётся и затихнет в конце.", "Music loops to the video length, fades in and out."))
            return
        }
        c.p("🎵 " + StudioPrefs.musicName.ifBlank { t("музыка", "music") })
        creditLine(c)
        c.spinner(t("Громкость", "Volume"), listOf(0.08f to t("тихо", "quiet"), 0.15f to t("средне", "medium"), 0.25f to t("громко", "loud")), StudioPrefs.musicVolume) {
            StudioPrefs.musicVolume = it
        }
        c.check(t("Приглушать музыку, когда звучит голос", "Lower the music under the voice"), StudioPrefs.musicDuck) { StudioPrefs.musicDuck = it }
        c.row(
            btn(this, t("🔎 Другая", "🔎 Another"), style = BtnStyle.TEXT) { musicSearch() },
            btn(this, t("📎 Файл", "📎 File"), style = BtnStyle.TEXT) { pickMusic() },
            btn(this, t("Убрать", "Remove"), style = BtnStyle.TEXT) {
                musicFile.delete()
                StudioPrefs.musicName = ""
                StudioPrefs.musicCredit = ""
                render()
            }
        )
    }

    /** The credit the licence asks for: shown with a copy button (paste it into the video description). */
    private fun creditLine(c: Col) {
        val credit = StudioPrefs.musicCredit
        if (credit.isBlank()) return
        c.muted(t("Лицензия требует подпись в описании ролика:", "The licence asks for a credit in the video description:"))
        c.add(text(this, credit, 13f).apply { setTextIsSelectable(true) }, 2)
        c.button(t("⧉ Скопировать подпись", "⧉ Copy the credit"), style = BtnStyle.TEXT) { copyCredit() }
    }

    private fun copyCredit() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("credit", StudioPrefs.musicCredit))
        toast(t("Подпись скопирована", "Credit copied"))
    }

    // ---------- free music search ----------

    private var musicPreview: MediaPlayer? = null

    /** Bottom sheet: search free catalogs by mood or words, listen, take a track. */
    private fun musicSearch() {
        var results: List<MusicHit> = emptyList()
        lateinit var box: LinearLayout
        lateinit var status: Pair<TextView, ProgressBar>
        var playing = ""
        var dismissSheet: () -> Unit = {}
        lateinit var query: EditText

        fun stopPreview() {
            musicPreview?.let { runCatching { it.stop() }; it.release() }
            musicPreview = null
            playing = ""
        }

        fun fill() {
            box.removeAllViews()
            for (h in results) {
                val row = Col(this, 12)
                row.root.background = roundBg(Palette.CARD, 12f, this)
                row.add(text(this, h.title, 15f, bold = true).apply { maxLines = 2 }, 0)
                row.muted(listOf(h.creator, Studio.clock(h.durationMs), StudioMusicCatalog.licenseLabel(h), h.source.substringAfter('/'))
                    .filter { it.isNotBlank() && it != "0:00" }.joinToString(" · ") +
                    if (!StudioMusicCatalog.commercialOk(h)) t(" · ⚠ без монетизации", " · ⚠ non-commercial") else "")
                row.row(
                    btn(this, if (playing == h.id) "■" else "▶", style = BtnStyle.TONAL) {
                        if (playing == h.id) {
                            stopPreview()
                        } else {
                            stopPreview()
                            playing = h.id
                            musicPreview = MediaPlayer().apply {
                                setDataSource(h.url)
                                setOnPreparedListener { it.start() }
                                setOnErrorListener { _, _, _ -> toast(t("Не удалось проиграть", "Cannot play")); true }
                                prepareAsync()
                            }
                        }
                        fill()
                    },
                    btn(this, t("Взять", "Use"), style = BtnStyle.FILLED) {
                        stopPreview()
                        dismissSheet()
                        takeTrack(h)
                    }
                )
                box.addView(row.root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }

        fun search(q: String) {
            if (q.isBlank()) return
            if (StudioPrefs.musicSource == StudioMusic.SRC_FREESOUND && !KeyVault.has(StudioMusic.FREESOUND_KEY)) {
                toast(t("Сначала вставьте ключ Freesound", "Paste a Freesound key first"))
                return
            }
            stopPreview()
            status.first.text = t("Поиск…", "Searching…")
            status.first.visibility = View.VISIBLE
            status.second.visibility = View.VISIBLE
            Lumen.async({ StudioMusic.search(StudioPrefs.musicSource, q, StudioPrefs.musicCommercial) }, { e ->
                status.second.visibility = View.GONE
                status.first.text = (e.message ?: e.javaClass.simpleName).take(200)
            }) { list ->
                status.second.visibility = View.GONE
                status.first.text = if (list.isEmpty()) t("Ничего не нашлось. Попробуйте другие слова (лучше по-английски).", "Nothing found. Try other words.")
                else t("Найдено: ${list.size}", "Found: ${list.size}")
                results = list
                fill()
            }
        }

        bottomSheet(t("Бесплатная музыка", "Free music"), { stopPreview() }) { dismiss ->
            dismissSheet = dismiss
            muted(t("Официальные каталоги с лицензиями Creative Commons. На сервер уходят только слова поиска.",
                "Official catalogs with Creative Commons licences. Only the search words are sent."))
            add(segBar(ctx, listOf(t("Openverse · без ключа", "Openverse · no key"), t("Freesound · ключ", "Freesound · key")),
                if (StudioPrefs.musicSource == StudioMusic.SRC_FREESOUND) 1 else 0) {
                StudioPrefs.musicSource = if (it == 1) StudioMusic.SRC_FREESOUND else StudioMusic.SRC_OPENVERSE
                dismiss()
                musicSearch()
            }, 8)
            if (StudioPrefs.musicSource == StudioMusic.SRC_FREESOUND && !KeyVault.has(StudioMusic.FREESOUND_KEY)) {
                muted(t("Бесплатный ключ: зарегистрируйтесь на freesound.org и создайте ключ API. Он хранится только на телефоне.",
                    "Free key: sign up at freesound.org and create an API key. It stays on the phone."))
                val key = edit(t("Ключ Freesound", "Freesound key"))
                row(
                    btn(ctx, t("Получить ключ", "Get a key"), style = BtnStyle.TEXT) { openUrl(StudioMusic.FREESOUND_KEY_URL) },
                    btn(ctx, t("Сохранить", "Save"), style = BtnStyle.TONAL) {
                        val k = key.text.toString().trim()
                        if (k.length < 10) return@btn
                        KeyVault.put(StudioMusic.FREESOUND_KEY, k)
                        dismiss()
                        musicSearch()
                    }
                )
            }
            check(t("Только с правом на монетизацию (CC0, CC BY, CC BY-SA)", "Only usable in monetised videos (CC0, CC BY, CC BY-SA)"), StudioPrefs.musicCommercial) {
                StudioPrefs.musicCommercial = it
            }
            query = edit(t("Настроение или слова, напр. calm piano", "Mood or words, e.g. calm piano"))
            chips(StudioMusicCatalog.MOODS.map { (label, q) ->
                chip(ctx, if (Settings.lang == "en") q else label) {
                    query.setText(q)
                    search(q)
                }
            })
            button(t("🔎 Искать", "🔎 Search"), primary = true) { search(query.text.toString()) }
            status = working("")
            status.first.visibility = View.GONE
            status.second.visibility = View.GONE
            box = add(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL })
        }
    }

    /** Downloads the chosen track, decodes it and keeps its credit. */
    private fun takeTrack(h: MusicHit) {
        busy = "music"
        cancel = false
        render()
        Lumen.async({
            val f = File(cacheDir, "studio_music_dl")
            StudioMusic.download(h, f, { cancel }) { pct -> report("music", t("Загрузка $pct/100", "Downloading $pct/100")) }
            report("music", t("Декодирование…", "Decoding…"))
            val p = StudioAudio.decode(f, 10 * 60_000L)
            f.delete()
            storeMusic(p)
        }, { e ->
            busy = ""
            render()
            if (e !is java.util.concurrent.CancellationException) err(e)
        }) {
            busy = ""
            StudioPrefs.musicName = h.title + if (h.creator.isNotBlank()) " — " + h.creator else ""
            StudioPrefs.musicCredit = if (StudioMusicCatalog.needsCredit(h)) StudioMusicCatalog.credit(h) else ""
            render()
            if (StudioPrefs.musicCredit.isNotBlank()) toast(t("Не забудьте подпись автора в описании ролика", "Remember to credit the author in the description"))
        }
    }

    private fun storeMusic(p: StudioAudio.Pcm) {
        if (p.data.isEmpty()) error(t("В файле нет звука", "No sound in the file"))
        val pcm44 = Studio.normalize(Studio.resample(p.data, p.rate, 44_100), 0.7)
        writeWav(musicFile, pcm44, 44_100)
    }

    private fun pickMusic() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*"), REQ_MUSIC)
    }

    private fun importMusic(uri: Uri) {
        busy = "music"
        cancel = false
        render()
        Lumen.async({
            report("music", t("Декодирование…", "Decoding…"))
            storeMusic(StudioAudio.decode(this, uri, 10 * 60_000L))
            runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "music"
        }, { e -> busy = ""; render(); err(e) }) { name ->
            busy = ""
            StudioPrefs.musicName = name
            StudioPrefs.musicCredit = ""
            render()
        }
    }

    private fun videoReady() = StudioPrefs.voiceMode == "video" && sourceFile.exists()

    /** Background clips that exist on disk, with their lengths. */
    private fun clips(): List<Pair<File, Long>> = StudioPrefs.bgClips.split(';').mapNotNull { e ->
        val name = e.substringBeforeLast(':', "")
        val ms = e.substringAfterLast(':', "").toLongOrNull() ?: return@mapNotNull null
        File(bgDir, name).takeIf { name.isNotBlank() && it.exists() && ms > 0 }?.let { it to ms }
    }

    private fun montageReady() = videoReady() || clips().isNotEmpty()

    /** What goes under a VIDEO-style video: the speaker's own video (with cuts) or the background clips. */
    private fun bgSpec(): StudioBg = if (videoReady()) StudioBg(sourceFile, cuts, emptyList()) else StudioBg(null, null, clips())

    private fun currentStyle(): StudioStyle {
        val s = runCatching { StudioStyle.valueOf(StudioPrefs.style) }.getOrDefault(StudioStyle.DARK)
        return if (s == StudioStyle.VIDEO && !montageReady()) StudioStyle.DARK else s
    }

    /** Background clips under any voice-over (Microsoft voice, microphone, audio): played in order and repeated. */
    private fun bgBlock(c: Col) {
        c.add(text(this, t("Видео на фоне", "Background video"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        val list = clips()
        if (list.isEmpty()) {
            c.muted(t("Добавьте один или несколько клипов: они пойдут друг за другом и повторятся по кругу, пока звучит озвучка. Сценарий может быть пустым.",
                "Add one or more clips: they play one after another and repeat while the voice-over lasts. The script may be empty."))
        } else {
            val total = list.sumOf { it.second }
            c.muted(t("Клипов: ${list.size} · круг ${Studio.clock(total)}", "Clips: ${list.size} · loop ${Studio.clock(total)}") +
                (pcm?.let { p -> if (total > 0) t(" · повторов: ", " · repeats: ") + ((p.ms + 500 + total - 1) / total) else "" } ?: ""))
            list.forEachIndexed { i, (f, ms) ->
                c.row(
                    text(this, "${i + 1}. 🎞 ${Studio.clock(ms)} · ${f.length() / (1024 * 1024)} MB", 14f),
                    btn(this, t("Убрать", "Remove"), style = BtnStyle.TEXT) {
                        f.delete()
                        StudioPrefs.bgClips = list.filter { it.first != f }.joinToString(";") { it.first.name + ":" + it.second }
                        board = emptyList()
                        render()
                    }
                )
            }
        }
        c.progress("bg", t("Копирование клипа…", "Copying the clip…"))
        if (busy != "bg") c.button(t("＋ Добавить клипы", "＋ Add clips"), style = BtnStyle.TONAL) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_BG)
        }
    }

    /** Copies picked clips into the app (links may expire) and reads their lengths. */
    private fun importClips(uris: List<Uri>) {
        busy = "bg"
        cancel = false
        render()
        Lumen.async({
            val added = mutableListOf<Pair<File, Long>>()
            uris.forEachIndexed { i, uri ->
                if (cancel) throw java.util.concurrent.CancellationException()
                report("bg", t("Копирование клипов ${i}/${uris.size}", "Copying clips ${i}/${uris.size}"))
                val f = File(bgDir, "clip_${System.currentTimeMillis()}_$i.mp4")
                contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } } ?: return@forEachIndexed
                val r = android.media.MediaMetadataRetriever()
                val ms = try {
                    r.setDataSource(f.absolutePath)
                    r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally {
                    runCatching { r.release() }
                }
                if (ms > 0) added += f to ms else f.delete()
            }
            report("bg", t("Копирование клипов ${uris.size}/${uris.size}", "Copying clips ${uris.size}/${uris.size}"))
            added
        }, { e ->
            busy = ""
            render()
            if (e !is java.util.concurrent.CancellationException) err(e)
        }) { added ->
            busy = ""
            if (added.isEmpty()) toast(t("Клипы не прочитались", "The clips could not be read"))
            StudioPrefs.bgClips = (clips() + added).joinToString(";") { it.first.name + ":" + it.second }
            if (added.isNotEmpty()) StudioPrefs.style = StudioStyle.VIDEO.name
            board = emptyList()
            render()
        }
    }

    private fun drawPreview(plan: StudioPlan) {
        val img = preview ?: return
        val at = previewMs.coerceIn(0, plan.totalMs)
        img.contentDescription = t("Кадр на ", "Frame at ") + Studio.clock(previewMs)
        if (plan.style != StudioStyle.VIDEO) {
            img.setImageBitmap(StudioVideo.preview(plan, at, 360, 640))
            return
        }
        // reading a video frame is slow: one at a time in the background, the latest position wins
        if (previewBusy) {
            previewAgain = true
            return
        }
        previewBusy = true
        val src = bgSpec()
        Lumen.async({ StudioVideo.preview(plan, at, 360, 640, src) }, { previewBusy = false }) { bmp ->
            previewBusy = false
            if (preview === img) img.setImageBitmap(bmp)
            if (previewAgain) {
                previewAgain = false
                drawPreview(plan)
            }
        }
    }

    private fun currentPlan(): StudioPlan? {
        val sc = script ?: return null
        val voice = pcm ?: return null
        if (words.isEmpty()) return null
        return StudioPlan.build(sc, words, voice.ms + 500, currentStyle(), StudioPrefs.accent, ranges, StudioPrefs.overlay,
            StudioPrefs.zooms, StudioPrefs.transitions)
    }

    private fun detach(v: View): View {
        (v.parent as? ViewGroup)?.removeView(v)
        return v
    }

    private fun resetAnalysis() {
        analysisFile.delete()
        voiceFile.delete()
        pcm = null
        words = emptyList()
        script = null
        ranges = null
        stats = null
        cuts = null
        board = emptyList()
        transcript = ""
    }

    private fun formatHelp() = showText(t("Формат сценария", "Script format"), t(
        "Каждая сцена — отдельный абзац (между сценами пустая строка).\n\n" +
            "Первая строка может начинаться с вида визуала:\n" +
            "[icons] — плитки с эмодзи\n[list] — список с галочками\n[flow] — шаги процесса\n[cycle] — цикл\n" +
            "[step: Заголовок] — номер этапа\n[big] — один крупный эмодзи\n[stat: 80%] — крупная цифра\n" +
            "Без пометки — просто текст.\n\n" +
            "Строка с «|» — плитки: 🔍 исследование | 📚 контекст | ⚙️ процесс\n\n" +
            "Пример:\n[icons] Чем отличается инженерная разработка от вайб-кодинга?\n🔍 исследование | 📚 контекст | ✅ проверка\n\n" +
            "[step: Работа с контекстом] Второй этап — собрать документацию.\n\n" +
            "[stat: 80%] Столько времени уходит на подготовку.\n⏱ подготовка",
        "Each scene is its own paragraph (a blank line between scenes).\n\n" +
            "The first line may start with the kind of visual:\n" +
            "[icons] emoji tiles\n[list] checklist\n[flow] process steps\n[cycle] a cycle\n" +
            "[step: Title] stage number\n[big] one big emoji\n[stat: 80%] a big number\n" +
            "Without a tag it is plain text.\n\n" +
            "A line with \"|\" lists tiles: 🔍 research | 📚 context | ⚙️ process\n\n" +
            "Example:\n[icons] How is engineering different from vibe coding?\n🔍 research | 📚 context | ✅ checks\n\n" +
            "[step: Context] Stage two: collect the docs.\n\n" +
            "[stat: 80%] That much time goes to preparation.\n⏱ preparation"
    ))

    // ---------- step 1: AI script ----------

    private fun aiLang() = if (lang() == "en" || Settings.lang == "en" && scriptEdit.text.isBlank()) "English" else "русский"

    private fun writeScript() {
        val topic = topicEdit.text.toString().trim().ifBlank { scriptEdit.text.toString().trim().take(300) }
        if (topic.isBlank()) {
            toast(t("Введите тему или идею ролика", "Type a topic or idea first"))
            return
        }
        val go = {
            busy = "ai_script"
            cancel = false
            render()
            val sec = StudioPrefs.seconds
            val extra = if (scriptEdit.text.isNotBlank() && topicEdit.text.isNotBlank())
                "Учти черновик автора и сохрани его мысли:\n" + scriptEdit.text.toString().take(3000) else ""
            Lumen.async({
                aiScript("studio_script", mapOf(
                    "topic" to topic, "seconds" to sec.toString(), "words" to Studio.wordsFor(sec).toString(),
                    "lang" to aiLang(), "extra" to extra, "visuals" to STUDIO_VISUALS
                ))
            }, { e -> busy = ""; render(); err(e) }) { sc ->
                busy = ""
                scriptEdit.setText(Studio.toText(sc))
                resetAnalysis()
                render()
            }
        }
        if (scriptEdit.text.isNotBlank()) confirm(t("Заменить сценарий?", "Replace the script?"),
            t("AI напишет новый сценарий по теме; текущий текст будет учтён как черновик.", "AI will write a new script; the current text is used as a draft."),
            t("Написать", "Write"), go) else go()
    }

    /** JSON call with a retry and a larger limit when the answer is cut off. */
    private fun aiScript(key: String, vars: Map<String, String>): SScript {
        val prov = AiTasks.requireProvider()
        val system = "You write scripts for short vertical explainer videos. Answer with JSON only."
        var limit = 6000
        var prompt = Prompts.fill(key, vars)
        repeat(3) { attempt ->
            if (cancel) throw java.util.concurrent.CancellationException()
            val r = Llm.complete(prov, prov.smartModel, system, prompt, json = true, maxTokens = limit, task = key, pid = "studio")
            val sc = Studio.parseScript(r.text)
            if (sc != null && !r.truncated) return sc
            if (sc != null && attempt == 2) return sc
            L.i("studio", "$key attempt ${attempt + 1}: ${if (r.truncated) "truncated" else "invalid json"}")
            if (r.truncated) limit = minOf(limit * 2, 24_000)
            prompt = Prompts.fill(key, vars) + "\n\nПредыдущий ответ был неполным или не в формате JSON. Верни только JSON, компактно."
        }
        throw LlmException(t("AI не вернул сценарий в нужном формате. Откройте «Ответы AI».", "AI did not return the script in the expected format. See \"AI answers\"."))
    }

    // ---------- step 2: recording and import ----------

    private fun startRecording() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        player?.release()
        player = null
        resetAnalysis()
        val rec = StudioRecorder { level, ms ->
            levelBar?.progress = (level * 3000).toInt().coerceAtMost(1000)
            timerText?.text = Studio.clock(ms)
            prompterScroll?.let { sv ->
                // expected reading time at ~2.4 words per second
                val n = Studio.parseText(scriptEdit.text.toString()).scenes.sumOf { Studio.tokens(it.say).size }.coerceAtLeast(1)
                val total = n * 1000L * 10 / 24
                val range = (sv.getChildAt(0)?.height ?: 0) - sv.height
                if (range > 0) sv.smoothScrollTo(0, (range * (ms.toFloat() / total)).toInt().coerceIn(0, range))
            }
        }
        try {
            rec.start()
        } catch (e: Exception) {
            err(e)
            return
        }
        recorder = rec
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val take = rec.stop()
        rec.failure?.let { err(it) }
        levelBar = null
        timerText = null
        prompterScroll = null
        Lumen.async({ writeTake(take, rec.rate) }, ::err) { render() }
    }

    private fun writeTake(pcm: ShortArray, rate: Int) = writeWav(takeFile, pcm, rate)

    private fun importAudio(uri: Uri) {
        busy = "import"
        cancel = false
        render()
        Lumen.async({
            report("import", t("Декодирование…", "Decoding…"))
            val p = StudioAudio.decode(this, uri)
            if (p.data.isEmpty()) error(t("В файле нет звука", "No sound in the file"))
            writeTake(Studio.resample(p.data, p.rate, 44_100), 44_100)
        }, { e -> busy = ""; render(); err(e) }) {
            busy = ""
            resetAnalysis()
            render()
        }
    }

    private fun togglePlay() {
        val p = player
        if (p != null && p.isPlaying) {
            p.stop()
            p.release()
            player = null
            render()
            return
        }
        player = MediaPlayer().apply {
            setDataSource(takeFile.absolutePath)
            setOnCompletionListener {
                it.release()
                player = null
                render()
            }
            prepare()
            start()
        }
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_BG) {
            val clip = data?.clipData
            val uris = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull(uri)
            if (uris.isNotEmpty()) importClips(uris)
            return
        }
        if (uri == null) return
        when (requestCode) {
            REQ_AUDIO -> importAudio(uri)
            REQ_VIDEO, REQ_CAPTURE -> importVideo(uri)
            REQ_MUSIC -> importMusic(uri)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
            else toast(t("Без доступа к микрофону запись невозможна", "Recording needs microphone access"))
        }
    }

    // ---------- step 3: analysis ----------

    private fun analyse() {
        saveInputs()
        val text = scriptEdit.text.toString()
        val sc = Studio.parseText(text)
        val mode = StudioPrefs.voiceMode
        if (mode == "edge" && sc.scenes.isEmpty()) {
            toast(t("Нужен сценарий (шаг 1)", "A script is needed (step 1)"))
            return
        }
        if (mode != "edge" && !takeFile.exists()) {
            toast(when (mode) {
                "mic" -> t("Сначала запишите голос (шаг 2)", "Record your voice first (step 2)")
                "video" -> t("Сначала снимите или выберите видео (шаг 2)", "Film or pick a video first (step 2)")
                else -> t("Сначала выберите аудио (шаг 2)", "Pick audio first (step 2)")
            })
            return
        }
        val useScript = mode != "edge" && StudioPrefs.readScript && sc.scenes.isNotEmpty()
        if (mode != "edge" && !useScript && !StudioRecognizer.available(this)) {
            showText(t("Нужен текст", "Text needed"), t(
                "Распознавание записи работает на Android 13 и новее с установленным сервисом распознавания речи. " +
                    "Вставьте текст, который звучит в записи, в сценарий (шаг 1) и включите «Я читаю сценарий».",
                "Recognising a take needs Android 13+ with a speech recognition service. " +
                    "Paste the spoken text into the script (step 1) and turn on \"I read the script\"."
            ))
            return
        }
        resetAnalysis()
        busy = "analyse"
        cancel = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        val done = { _: Unit ->
            saveAnalysis()
            busy = ""
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            previewMs = 0
            render()
        }
        val fail = { e: Throwable ->
            busy = ""
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            render()
            if (e !is java.util.concurrent.CancellationException) err(e)
        }
        when {
            mode == "edge" -> Lumen.async({ analyseEdge(sc) }, fail, done)
            useScript -> Lumen.async({ analyseTake(sc) }, fail, done)
            else -> Lumen.async({ speechOf(loadTake()) }, fail) { (take, speech) ->
                report("analyse", t("Распознавание речи…", "Recognising speech…"))
                val pieces = Studio.groupSpans(speech)
                StudioRecognizer.recognize(this, take.data, take.rate, pieces, lang() + "-" + lang().uppercase().replace("EN", "US").replace("UK", "UA"),
                    { i, n -> report("analyse", t("Распознавание $i/$n", "Recognising $i/$n")) }, { cancel }) { res ->
                    if (cancel) {
                        fail(java.util.concurrent.CancellationException())
                        return@recognize
                    }
                    val w = Studio.alignPieces(res, speech)
                    if (w.isEmpty()) {
                        fail(IllegalStateException(t("Речь не распознана. Проверьте язык и громкость записи или вставьте текст в сценарий.",
                            "No speech recognised. Check the language and loudness, or paste the text into the script.")))
                        return@recognize
                    }
                    pcm = take
                    words = w
                    transcript = res.joinToString(" ") { it.second }
                    script = Studio.autoScenes(w)
                    ranges = null
                    stats = Studio.stats(take.ms, speech, w.size)
                    done(Unit)
                }
            }
        }
    }

    private fun loadTake(): StudioAudio.Pcm {
        report("analyse", t("Чтение записи…", "Reading the take…"))
        val (data, rate) = Studio.readWav(takeFile.readBytes()) ?: error(t("Запись повреждена, запишите заново", "The take is damaged, record again"))
        return StudioAudio.Pcm(Studio.normalize(data), rate)
    }

    /** Speech of the take; with "cut pauses" on, the take is cut first and [cuts] remembers what was kept. */
    private fun speechOf(take0: StudioAudio.Pcm): Pair<StudioAudio.Pcm, List<Span>> {
        report("analyse", t("Поиск речи и пауз…", "Finding speech and pauses…"))
        var take = take0
        var speech = Studio.speechSpans(take.data, take.rate)
        if (speech.isEmpty()) error(t("В записи не слышно речи. Говорите ближе к микрофону.", "No speech heard. Speak closer to the microphone."))
        cuts = null
        if (StudioPrefs.cutPauses) {
            val keep = Studio.keepSegments(speech, take.ms)
            if (keep.sumOf { it.ms } < take.ms - 300) {
                report("analyse", t("Вырезание пауз…", "Cutting pauses…"))
                take = StudioAudio.Pcm(Studio.cutPcm(take.data, take.rate, keep), take.rate)
                cuts = keep
                speech = Studio.speechSpans(take.data, take.rate).ifEmpty { speech }
            }
        }
        return take to speech
    }

    private fun analyseTake(sc: SScript) {
        val (take, speech) = speechOf(loadTake())
        report("analyse", t("Расстановка слов…", "Timing the words…"))
        val tokens = sc.scenes.flatMap { Studio.tokens(it.say) }
        val w = Studio.alignWords(tokens, speech)
        pcm = take
        words = w
        script = sc
        ranges = Studio.assignScenes(w.size, sc.scenes.map { Studio.tokens(it.say).size })
        stats = Studio.stats(take.ms, speech, w.size)
    }

    private fun analyseEdge(sc: SScript) {
        val voice = StudioPrefs.voice.takeIf { v -> voices.any { it.shortName == v } && v.startsWith(lang(), true) }
            ?: EdgeTts.pickVoice(voices, lang(), TtsPrefs.voiceFor(lang()))?.shortName ?: TtsPrefs.voice
        val o = TtsOptions(voice, StudioPrefs.rate)
        val tmp = File(cacheDir, "studio_tts").apply { deleteRecursively(); mkdirs() }
        val all = mutableListOf<SWord>()
        val rs = mutableListOf<IntRange>()
        var audio = ShortArray(0)
        var rate = 24_000
        val n = sc.scenes.size
        sc.scenes.forEachIndexed { i, scene ->
            if (cancel) throw java.util.concurrent.CancellationException()
            report("analyse", t("Озвучка сцен $i/$n", "Voicing scenes $i/$n"))
            val pieces = EdgeTts.synthesizeWords(scene.say, o) { cancel } ?: throw java.util.concurrent.CancellationException()
            val first = all.size
            pieces.forEachIndexed { k, (mp3, ws) ->
                val f = File(tmp, "s${i}_$k.mp3").apply { writeBytes(mp3) }
                val p = StudioAudio.decode(f)
                val data = if (audio.isEmpty() || p.rate == rate) p.data else Studio.resample(p.data, p.rate, rate)
                if (audio.isEmpty()) rate = p.rate
                val shift = Studio.durationMs(audio.size, rate)
                all += Studio.dress(ws, Studio.tokens(scene.say)).map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) }
                audio += data
            }
            rs += first until all.size
            audio += Studio.silence(if (i < n - 1) 180 else 400, rate)
        }
        report("analyse", t("Озвучка сцен $n/$n", "Voicing scenes $n/$n"))
        tmp.deleteRecursively()
        if (all.isEmpty()) error(t("Сервис не вернул разметку слов. Попробуйте другой голос.", "The service returned no word timings. Try another voice."))
        val take = StudioAudio.Pcm(audio, rate)
        pcm = take
        words = all
        script = sc
        ranges = rs
        stats = VoiceStats(take.ms, take.ms, 0, 0, all.size)
    }

    private fun aiScenes() {
        val text = transcript
        busy = "ai_scenes"
        cancel = false
        render()
        Lumen.async({
            aiScript("studio_scenes", mapOf("data" to text, "lang" to aiLang(), "visuals" to STUDIO_VISUALS))
        }, { e -> busy = ""; render(); err(e) }) { sc ->
            busy = ""
            script = sc
            ranges = null
            saveAnalysis()
            scriptEdit.setText(Studio.toText(sc))
            render()
        }
    }

    // ---------- step 4: video ----------

    /** Starts the build in [StudioService]: it goes on with the screen closed or the phone locked. */
    private fun renderVideo() {
        val plan = currentPlan() ?: return
        val voice = pcm ?: return
        if (StudioJob.running) return
        val hgt = StudioPrefs.height
        val wdt = hgt * 9 / 16
        val day = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val name = Studio.fileName(plan.script.title.ifBlank { topicEdit.text.toString() }, day)
        val out = ReportKit.shareFile(this, name)
        lastVideo = null
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        StudioService.start(this, StudioJob.Request(
            plan, voice, out, name, wdt, hgt,
            if (plan.style == StudioStyle.VIDEO) bgSpec().takeIf { !it.empty } else null,
            musicFile.takeIf { it.exists() }, StudioPrefs.musicVolume, StudioPrefs.musicDuck
        ))
        wasRunning = true
        render()
    }

    /** Progress and the end of a background build (main thread). */
    private fun onJob() {
        if (StudioJob.running) {
            bars["render"]?.let { (tv, bar) ->
                tv.text = StudioJob.message
                bar.showProgress(StudioJob.message)
            } ?: render()
            return
        }
        if (!wasRunning) return
        wasRunning = false
        StudioJob.result?.takeIf { it.exists() }?.let {
            lastVideo = it
            savedAs = StudioJob.savedAs
        }
        render()
        StudioJob.error?.let { e ->
            StudioJob.error = null
            err(IllegalStateException(e))
        }
    }

    private fun openFile(f: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.provider", f)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            ReportKit.share(this, f, false, f.name, "video/mp4")
        }
    }
}
