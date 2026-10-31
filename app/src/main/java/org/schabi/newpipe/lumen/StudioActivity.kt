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
import android.widget.Button
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
    /** What is being made: [StudioTask.id]. */
    var task: String
        get() = p.getString("studio_task", StudioTask.SHORTS.id) ?: StudioTask.SHORTS.id
        set(v) = p.edit().putString("studio_task", v).apply()
    /** Key of a setting kept separately for every task (Shorts keeps the old keys). */
    private fun key(base: String) = StudioTask.of(task).let { if (it == StudioTask.SHORTS) base else base + "_" + it.id }
    var script: String
        get() = p.getString(key("studio_script"), "") ?: ""
        set(v) = p.edit().putString(key("studio_script"), v).apply()
    var topic: String
        get() = p.getString(key("studio_topic"), "") ?: ""
        set(v) = p.edit().putString(key("studio_topic"), v).apply()
    /** Length of a horizontal YouTube video, seconds. */
    var videoSeconds: Int
        get() = p.getInt("studio_video_seconds", 300)
        set(v) = p.edit().putInt("studio_video_seconds", v).apply()
    /** Frame of an animated voice-over: [StudioAspect.id]. */
    var aspect: String
        get() = p.getString("studio_aspect", StudioAspect.PORTRAIT.id) ?: StudioAspect.PORTRAIT.id
        set(v) = p.edit().putString("studio_aspect", v).apply()
    /** Frame of the Cartoon task: [StudioAspect.id]. */
    var cartoonAspect: String
        get() = p.getString("studio_aspect_cartoon", StudioAspect.LANDSCAPE.id) ?: StudioAspect.LANDSCAPE.id
        set(v) = p.edit().putString("studio_aspect_cartoon", v).apply()
    /** Characters of the Cartoon task. */
    var cast: List<SChar>
        get() = StudioToon.parseCast(p.getString("studio_cast", null)).ifEmpty { StudioToon.defaultCast() }
        set(v) = p.edit().putString("studio_cast", StudioToon.castJson(v)).apply()
    /** Frame of the Cuts task: [StudioAspect.id]. */
    var cutsAspect: String
        get() = p.getString("studio_aspect_cuts", StudioAspect.LANDSCAPE.id) ?: StudioAspect.LANDSCAPE.id
        set(v) = p.edit().putString("studio_aspect_cuts", v).apply()
    /** Short side of the frame: 720 or 1080. */
    var quality: Int
        get() = p.getInt("studio_quality", if (height >= 1920) 1080 else 720)
        set(v) = p.edit().putInt("studio_quality", v).apply()
    /** Sound effects of the AI director. */
    var sfx: Boolean
        get() = p.getBoolean("studio_sfx", true)
        set(v) = p.edit().putBoolean("studio_sfx", v).apply()
    /** Run the AI director right after the analysis. */
    var autoDirect: Boolean
        get() = p.getBoolean("studio_auto_direct", true)
        set(v) = p.edit().putBoolean("studio_auto_direct", v).apply()
    /** How the voice-over is animated: [StudioApproach.id]. */
    var approach: String
        get() = p.getString("studio_approach", StudioApproach.FULL.id) ?: StudioApproach.FULL.id
        set(v) = p.edit().putString("studio_approach", v).apply()
    /** The picture pulses with the loudness of the voice. */
    var pulse: Boolean
        get() = p.getBoolean("studio_pulse", false)
        set(v) = p.edit().putBoolean("studio_pulse", v).apply()
    /** Animated Noto emoji instead of still ones. */
    var liveEmoji: Boolean
        get() = p.getBoolean("studio_live_emoji", true)
        set(v) = p.edit().putBoolean("studio_live_emoji", v).apply()
    /** Find B-roll photos for the scenes the AI director asks for. */
    var broll: Boolean
        get() = p.getBoolean("studio_broll", true)
        set(v) = p.edit().putBoolean("studio_broll", v).apply()
    /** The AI director may cut out filler words and slips of the take. */
    var dropFillers: Boolean
        get() = p.getBoolean("studio_drop_fillers", true)
        set(v) = p.edit().putBoolean("studio_drop_fillers", v).apply()
    var seconds: Int
        get() = p.getInt("studio_seconds", 60)
        set(v) = p.edit().putInt("studio_seconds", v).apply()
    /** "edge", "mic", "file" or "video"; an animated voice-over uses "file" or "mic". */
    var voiceMode: String
        get() = p.getString(key("studio_voice_mode"), null) ?: if (StudioTask.of(task) == StudioTask.VOICE) "file" else "edge"
        set(v) = p.edit().putString(key("studio_voice_mode"), v).apply()
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
    private val task get() = StudioTask.of(StudioPrefs.task)
    /** Take, video and analysis of the current task (Shorts keeps the old place); music and clips are shared. */
    private val tdir get() = (if (task == StudioTask.SHORTS) dir else File(dir, task.id)).apply { mkdirs() }
    private val takeFile get() = File(tdir, "take.wav")
    private val sourceFile get() = File(tdir, "source.mp4")
    private val musicFile get() = File(dir, "music.wav")
    /** Cuts task: marked YouTube pieces and their cut files. */
    private val cutsFile get() = File(tdir, "clips.json")
    private val cutsDir get() = File(tdir, "clips").apply { mkdirs() }
    private var cutClips: List<SClip> = emptyList()
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
    private val analysisFile get() = File(tdir, "analysis.json")
    private val voiceFile get() = File(tdir, "voice.wav")
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

    /** A closed Studio screen is still working in the background: this one only shows it and saves nothing. */
    private var foreign = false
    private var reloading = false
    private val watcher: () -> Unit = { onForeign() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        foreign = BgTasks.has("studio:") && BgTasks.find("studio:")?.key != "studio:render"
        BgTasks.watchers += watcher
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
        loadCuts()
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
        // background jobs (AI, imports, downloads) go on: their results are saved to disk
        BgTasks.watchers -= watcher
        recorder?.stop()
        player?.release()
        super.onDestroy()
    }

    private fun saveInputs() {
        if (foreign) return
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
        c.add(segBar(this, listOf("📱 Shorts", t("🎬 Видео", "🎬 Video"), t("🎙 Озвучка", "🎙 Voice-over"), t("🎞 Нарезка", "🎞 Cuts"), t("🎨 Анимация", "🎨 Animation")), task.ordinal) {
            switchTask(StudioTask.entries[it])
        }, 0)
        c.muted(when (task) {
            StudioTask.SHORTS -> t(
                "Короткие вертикальные ролики 9:16: слова появляются под голос, рядом схемы из эмодзи. Четыре шага, всё собирается на телефоне.",
                "Short vertical 9:16 videos: words appear with the voice, next to emoji schemes. Four steps, everything is built on the phone.")
            StudioTask.VIDEO -> t(
                "Горизонтальный ролик для YouTube 16:9 на 2–10 минут: AI пишет сценарий с главами, голос озвучивает, AI-режиссёр ставит графику, зумы, плашки и звуки.",
                "A horizontal 16:9 YouTube video of 2–10 minutes: AI writes a script with chapters, a voice reads it, the AI director adds graphics, zooms, stickers and sounds.")
            StudioTask.VOICE -> t(
                "Готовая озвучка → анимированный ролик. Загрузите аудио или запишите голос: AI-режиссёр сам разметит сцены, графику, зумы, плашки и звуки и вырежет оговорки. Кадр 9:16, 16:9 или 1:1.",
                "A ready voice-over → an animated video. Upload audio or record your voice: the AI director lays out scenes, graphics, zooms, stickers and sounds and cuts slips. Frame 9:16, 16:9 or 1:1.")
            StudioTask.CUTS -> t(
                "Нарезка с голосом за кадром: отметьте куски видео с YouTube (ссылка и время), Lumen скачает только эти секунды, AI-режиссёр поставит клипы на нужные слова, между ними — графика. Кадр 16:9, 9:16 или 1:1.",
                "A voice-over over cuts: mark pieces of YouTube videos (link and time), Lumen downloads only those seconds, the AI director puts the clips on the right words with graphics in between. Frame 16:9, 9:16 or 1:1.")
            StudioTask.CARTOON -> t(
                "Познавательная анимация: персонажи (лиса, робот, сова…) разговаривают разными голосами и объясняют тему, рядом появляются схемы и цифры. Рот двигается под слоги, герои моргают, жестикулируют и показывают эмоции. Кадр 16:9, 9:16 или 1:1.",
                "An informational animation: characters (fox, robot, owl…) talk in different voices and explain the topic while schemes and numbers appear next to them. Mouths follow the syllables, characters blink, gesture and show emotions. Frame 16:9, 9:16 or 1:1.")
        })
        if (foreign) {
            c.card {
                add(text(ctx, t("⏳ Задача выполняется в фоне", "⏳ A task is running in the background"), Type.TITLE_M, bold = true), 0)
                muted(t("Её начал прежний экран Студии. Экран обновится сам, когда она закончится; прогресс — и в уведомлении.",
                    "It was started by the previous Studio screen. This screen refreshes when it ends; progress is also in the notification."))
                bars["foreign"] = working(BgTasks.find("studio:")?.let { it.title + ": " + it.message } ?: "…")
            }
            page("Lumen Studio", c.root, keepScroll = true)
            return
        }
        c.steps(stepNow(), 4)
        if (task == StudioTask.VOICE) {
            voiceCard(c, 1)
            scriptCard(c, 2)
        } else {
            scriptCard(c, 1)
            voiceCard(c, 2)
        }
        if (task == StudioTask.CUTS) cutsCard(c)
        if (task == StudioTask.CARTOON) castCard(c)
        analyseCard(c)
        videoCard(c)
        page("Lumen Studio", c.root, keepScroll = true)
    }

    /** Long work of the screen: runs through [BgTasks] with a notification, so it survives minimising or closing. */
    private fun <T> job(key: String, work: () -> T, fail: (Throwable) -> Unit, done: (T) -> Unit) {
        BgTasks.run(this, "studio:$key", "Lumen Studio: " + jobTitle(key), BgTasks.reopen(this), { work() }, { e ->
            if (e !is java.util.concurrent.CancellationException) autoAll = false
            runCatching { fail(e) }.onFailure { L.w("studio", "$key: ${it.message}") }
        }, { cancel = true }) { r ->
            runCatching { done(r) }.onFailure { L.w("studio", "$key: ${it.message}") }
        }
    }

    private fun jobTitle(key: String) = when (key) {
        "ai_script" -> t("AI пишет сценарий", "AI writes the script")
        "ai_edit" -> t("AI правит сценарий", "AI edits the script")
        "analyse" -> t("озвучка и разметка", "voice and timing")
        "ai_direct" -> t("AI-режиссёр", "AI director")
        "broll" -> t("поиск фото", "finding photos")
        "board" -> t("раскадровка", "storyboard")
        "music" -> t("музыка", "music")
        "bg" -> t("клипы фона", "background clips")
        "cuts" -> t("загрузка нарезки", "downloading cuts")
        "emoji" -> t("живые эмодзи", "live emoji")
        else -> t("импорт", "import")
    }

    private fun onForeign() {
        if (!foreign) return
        val tk = BgTasks.find("studio:")
        if (tk == null || tk.key == "studio:render") {
            // the closed screen has just saved its results (files are written a moment later)
            android.os.Handler(mainLooper).postDelayed({
                if (!isDestroyed && foreign && !reloading && BgTasks.find("studio:")?.key.let { it == null || it == "studio:render" }) {
                    reloading = true
                    recreate()
                }
            }, 900)
            return
        }
        bars["foreign"]?.let { (tv, bar) ->
            val m = tk.title + ": " + tk.message
            tv.text = m
            bar.showProgress(m)
        }
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
        BgTasks.progress("studio:$key", msg)
        bars[key]?.let { (tv, bar) ->
            tv.text = msg
            bar.showProgress(msg)
        }
    }

    private fun scriptCard(c: Col, n: Int) = c.card {
        if (task == StudioTask.VOICE) {
            add(text(ctx, "$n. " + t("Текст озвучки", "Voice-over text"), Type.TITLE_M, bold = true), 0)
            muted(t("Необязательно. Вставьте текст, который звучит в записи, — слова встанут по времени точнее. Без текста запись распознаётся (Android 13+). Сцены, графику и эффекты расставит AI-режиссёр.",
                "Optional. Paste the text spoken in the recording for more exact word timing. Without it the take is recognised (Android 13+). The AI director lays out scenes, graphics and effects."))
            add(detach(scriptEdit), 8)
            return@card
        }
        add(text(ctx, "$n. " + t("Сценарий", "Script"), Type.TITLE_M, bold = true), 0)
        add(detach(topicEdit), 8)
        if (longScript()) {
            spinner(t("Длительность", "Length"), listOf(120 to "2 мин", 180 to "3 мин", 300 to "5 мин", 480 to "8 мин", 600 to "10 мин"), StudioPrefs.videoSeconds) {
                StudioPrefs.videoSeconds = it
                render()
            }
        } else spinner(t("Длительность", "Length"), listOf(30 to "30 с", 60 to "60 с", 90 to "90 с", 120 to "2 мин"), StudioPrefs.seconds) {
            StudioPrefs.seconds = it
            render()
        }
        row(
            btn(ctx, t("✨ Написать (AI)", "✨ Write (AI)"), style = BtnStyle.TONAL) { writeScript() },
            btn(ctx, t("Формат", "Format"), style = BtnStyle.TEXT) { formatHelp() }
        )
        if (task == StudioTask.CARTOON && busy.isEmpty()) {
            button(t("🤖 AI сделает всё: идея → готовый мультфильм", "🤖 AI makes everything: idea → finished cartoon"), style = BtnStyle.TONAL) { autoDialog() }
            muted(t("Необязательно: опишите идею, сцены или сценарий — AI напишет реплики, озвучит героев, смонтирует и соберёт видео. Работает и в свёрнутом приложении.",
                "Optional: describe an idea, scenes or a script; AI writes the lines, voices the characters, edits and builds the video. Works with the app minimised."))
        }
        progress("ai_script", t("AI пишет сценарий…", "AI is writing the script…"))
        if (scriptEdit.text.isNotBlank() && busy.isEmpty()) {
            val undo = prevScript
            if (undo != null) row(
                btn(ctx, t("🪄 AI-помощник", "🪄 AI assistant"), style = BtnStyle.TONAL) { assist() },
                btn(ctx, t("↩ Вернуть прежний", "↩ Undo"), style = BtnStyle.TEXT) {
                    scriptEdit.setText(undo)
                    prevScript = null
                    resetAnalysis()
                    render()
                }
            ) else button(t("🪄 AI-помощник: улучшить сценарий", "🪄 AI assistant: improve the script"), style = BtnStyle.TONAL) { assist() }
        }
        progress("ai_edit", t("AI правит сценарий…", "AI is editing the script…"))
        add(detach(scriptEdit), 8)
        val sc = Studio.parseText(scriptEdit.text.toString())
        if (sc.scenes.isNotEmpty()) {
            val n = sc.scenes.sumOf { Studio.tokens(it.say).size }
            muted(t("Сцен: ${sc.scenes.size} · слов: $n · примерно ${Studio.clock(n * 1000L * 10 / 24)}",
                "Scenes: ${sc.scenes.size} · words: $n · about ${Studio.clock(n * 1000L * 10 / 24)}"))
        }
    }

    private fun voiceCard(c: Col, n: Int) = c.card {
        add(text(ctx, "$n. " + if (task == StudioTask.VOICE) t("Озвучка", "Voice-over") else t("Голос", "Voice"), Type.TITLE_M, bold = true), 0)
        val modes = when (task) {
            StudioTask.VOICE -> listOf("file", "mic")
            StudioTask.CUTS, StudioTask.CARTOON -> listOf("edge", "mic", "file")
            else -> listOf("edge", "mic", "file", "video")
        }
        val labels = if (task == StudioTask.VOICE) listOf(t("Аудиофайл", "Audio file"), t("Микрофон", "Mic"))
        else listOf("Microsoft", t("Микрофон", "Mic"), t("Аудио", "Audio"), t("Видео", "Video")).take(modes.size)
        add(segBar(ctx, labels, modes.indexOf(StudioPrefs.voiceMode).coerceAtLeast(0)) {
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
        if (task == StudioTask.CARTOON) c.muted(t("Голоса персонажей задаются в карточке «🎭 Персонажи»; здесь — голос рассказчика (реплики без @Имени).",
            "Character voices are set in the \"🎭 Characters\" card; this is the narrator's voice (lines without @Name)."))
        c.add(text(this, if (task == StudioTask.CARTOON) t("Голос рассказчика", "Narrator voice") else t("Голос", "Voice"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
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
        if (task != StudioTask.VOICE) c.check(t("Я читаю сценарий (иначе запись распознаётся)", "I read the script (otherwise the take is recognised)"), StudioPrefs.readScript) {
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
        if (task != StudioTask.VOICE) c.check(t("В записи звучит текст сценария", "The recording speaks the script text"), StudioPrefs.readScript) {
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
        job("import", {
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
            add(text(ctx, if (task == StudioTask.VOICE) t("Как анимировать", "How to animate") else t("Режиссура", "Direction"),
                Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val cur = StudioApproach.of(StudioPrefs.approach)
            muted(approachHelp(cur))
            progress("ai_direct", t("Режиссёр размечает ролик…", "The director is planning the video…"))
            if (busy.isEmpty()) {
                fun b(ap: StudioApproach, label: String) = btn(ctx, (if (ap == cur) "● " else "") + label,
                    style = if (ap == cur) BtnStyle.TONAL else BtnStyle.TEXT) { animate(ap) }
                row(b(StudioApproach.RULES, t("⚡ Правила", "⚡ Rules")), b(StudioApproach.RHYTHM, t("🥁 Ритм", "🥁 Rhythm")))
                row(b(StudioApproach.LITE, t("💸 Эконом AI", "💸 Lite AI")), b(StudioApproach.HYBRID, t("🧩 Гибрид", "🧩 Hybrid")))
                add(b(StudioApproach.FULL, t("🎬 Полный AI-режиссёр", "🎬 Full AI director")), 4)
                if (transcript.isNotBlank()) button(t("Текст расшифровки", "Transcript"), style = BtnStyle.TEXT) { showText(t("Расшифровка", "Transcript"), transcript) }
            }
            check(t("Запускать выбранный способ сразу после разбора", "Run the chosen way right after the analysis"), StudioPrefs.autoDirect) { StudioPrefs.autoDirect = it }
            if (cur == StudioApproach.FULL) {
                if (StudioPrefs.voiceMode != "edge") check(t("✂ Вырезать слова-паразиты и оговорки", "✂ Cut filler words and slips"), StudioPrefs.dropFillers) { StudioPrefs.dropFillers = it }
                check(t("🖼 Подбирать фото B-roll (Openverse, свободные лицензии)", "🖼 Find B-roll photos (Openverse, open licences)"), StudioPrefs.broll) { StudioPrefs.broll = it }
            }
            lastTokens?.let { (i, o) -> muted(t("Последний вызов AI: $i → $o токенов", "Last AI call: $i → $o tokens")) }
            progress("broll", t("Поиск фото…", "Finding photos…"))
            val (zooms, pops, sounds) = StudioDirector.summary(sc)
            val figures = sc.scenes.sumOf { it.shapes.size }
            val asked = sc.scenes.count { it.broll.isNotBlank() }
            val got = sc.scenes.count { it.broll.isNotBlank() && it.media.isNotBlank() }
            if (zooms + pops + sounds + figures + asked > 0) muted("🎬 " + t(
                "зумов $zooms · плашек $pops · звуков $sounds · фигур $figures" + (if (asked > 0) " · фото $got из $asked" else ""),
                "zooms $zooms · stickers $pops · sounds $sounds · shapes $figures" + (if (asked > 0) " · photos $got of $asked" else "")))
            if (busy.isEmpty() && got < asked) button(t("🖼 Подобрать B-roll", "🖼 Find B-roll"), style = BtnStyle.TEXT) { fetchBroll() }
            if (got > 0) button(t("⧉ Источники фото для описания", "⧉ Photo credits for the description"), style = BtnStyle.TEXT) { copyText(StudioDirector.credits(sc)) }
            val plan = currentPlan() ?: return@card
            add(text(ctx, t("Сцены", "Scenes"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val pickable = task == StudioTask.CUTS && readyCuts().isNotEmpty()
            if (pickable) muted(t("Нажмите на сцену, чтобы выбрать ей клип.", "Tap a scene to pick its clip."))
            val ready = readyIds()
            plan.times.take(60).forEach { st ->
                val scn = sc.scenes[st.scene]
                val fx = scn.cues.joinToString("") { when (it.type) { "zoom" -> "🔍"; "pop" -> "💬"; else -> "🔊" } } +
                    "✏️".repeat(scn.shapes.size) + (if (scn.media.isNotBlank()) "🖼" else if (scn.broll.isNotBlank()) "▫️" else "")
                val what = if (scn.clip in ready) "🎞 ${scn.clip}" else kindLabel(scn.kind)
                val line = "${Studio.clock(st.startMs)} · $what" + (if (fx.isNotEmpty()) " $fx" else "") + " · " +
                    scn.say.take(70) + if (scn.say.length > 70) "…" else ""
                if (pickable) {
                    add(text(ctx, line, 13f, color = if (scn.clip in ready) Palette.ON_SURFACE else Palette.MUTED).apply {
                        setPadding(0, dp(4), 0, dp(4))
                        setOnClickListener { pickClip(st.scene) }
                    }, 2)
                } else {
                    muted(line)
                }
            }
            if (plan.times.size > 60) muted("… " + t("ещё ${plan.times.size - 60}", "${plan.times.size - 60} more"))
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
        "quote" -> t("цитата", "quote")
        "chart" -> t("диаграмма", "chart")
        "compare" -> t("сравнение", "compare")
        else -> t("текст", "text")
    }

    private fun videoCard(c: Col) = c.card {
        add(text(ctx, t("4. Ролик", "4. Video"), Type.TITLE_M, bold = true), 0)
        val styles = listOfNotNull(StudioStyle.DARK, StudioStyle.LIGHT, if (montageReady()) StudioStyle.VIDEO else null)
        val labels = listOf(t("Тёмный", "Dark"), t("Светлый", "Light"), t("Монтаж", "Montage")).take(styles.size)
        if (cutMontage()) muted(t("Монтаж нарезки: клипы идут на своих сценах, между ними — графика, внизу слово под голос. Звук клипов выключен.",
            "Cut montage: clips play on their scenes, graphics in between, the spoken word below. Clip sound is off."))
        else add(segBar(ctx, labels, styles.indexOf(currentStyle()).coerceAtLeast(0)) {
            StudioPrefs.style = styles[it].name
            render()
        }, 8)
        if (!cutMontage()) muted(when (currentStyle()) {
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
        if (!videoReady() && task != StudioTask.CUTS && task != StudioTask.CARTOON) bgBlock(this)
        add(text(ctx, t("Цвет акцента", "Accent colour"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        chips(StudioPrefs.ACCENTS.map { (col, e) ->
            chip(ctx, e, col == StudioPrefs.accent) {
                StudioPrefs.accent = col
                render()
            }
        })
        if (task == StudioTask.VOICE || task == StudioTask.CUTS || task == StudioTask.CARTOON) {
            add(text(ctx, t("Кадр", "Frame"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val shapes = StudioAspect.entries
            add(segBar(ctx, listOf(t("9:16 Shorts", "9:16 Shorts"), t("16:9 YouTube", "16:9 YouTube"), t("1:1 квадрат", "1:1 square")), shapes.indexOf(aspect())) {
                when (task) {
                    StudioTask.CUTS -> StudioPrefs.cutsAspect = shapes[it].id
                    StudioTask.CARTOON -> StudioPrefs.cartoonAspect = shapes[it].id
                    else -> StudioPrefs.aspect = shapes[it].id
                }
                board = emptyList()
                render()
            }, 4)
        }
        spinner(t("Качество", "Quality"), listOf(720, 1080).map { q -> aspect().size(q).let { (a, b) -> q to "$a×$b" } }, StudioPrefs.quality) {
            StudioPrefs.quality = it
        }
        check(t("🔊 Звуки эффектов: whoosh, pop, click, ding…", "🔊 Sound effects: whoosh, pop, click, ding…"), StudioPrefs.sfx) { StudioPrefs.sfx = it }
        check(t("💓 Пульс: картинка дышит в такт голосу", "💓 Pulse: the picture breathes with the voice"), StudioPrefs.pulse) {
            StudioPrefs.pulse = it
            board = emptyList()
            render()
        }
        check(t("✨ Живые эмодзи (анимированные Noto от Google, загружаются при сборке)", "✨ Live emoji (animated Noto by Google, downloaded when building)"), StudioPrefs.liveEmoji) {
            StudioPrefs.liveEmoji = it
            board = emptyList()
            render()
        }
        progress("emoji", t("Загрузка живых эмодзи…", "Downloading live emoji…"))
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
        val (pw, ph) = when (aspect()) {
            StudioAspect.PORTRAIT -> 180 to 320
            StudioAspect.LANDSCAPE -> 320 to 180
            StudioAspect.SQUARE -> 240 to 240
        }
        add(img, 12).layoutParams = LinearLayout.LayoutParams(dp(pw), dp(ph)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
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
        val key = "${plan.style}:${plan.accent}:${plan.overlay}:${plan.zooms}:${plan.times.size}:${plan.totalMs}:${clips().size}:${aspect().id}:" +
            plan.script.scenes.joinToString(",") { it.clip } + ":" + readyIds().joinToString(",")
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
                    }, aspect().size(72).let { (cw, chh) -> LinearLayout.LayoutParams(dp(cw), dp(chh)) })
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
        val bg = if (plan.style == StudioStyle.VIDEO) bgSpec(plan) else null
        job("board", {
            val n = plan.times.size
            val (bw, bh) = aspect().size(144)
            val ready = java.util.concurrent.atomic.AtomicInteger(0)
            Par.mapAll(plan.times, Studio.cpuWorkers()) { _, st ->
                if (cancel) throw java.util.concurrent.CancellationException()
                val at = (st.startMs + minOf(900L, (st.endMs - st.startMs) / 2)).coerceAtMost(plan.totalMs)
                val frame = StudioVideo.preview(plan, at, bw, bh, bg)
                report("board", t("Раскадровка ${ready.incrementAndGet()}/$n", "Storyboard ${ready.get()}/$n"))
                at to frame
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

    private fun copyText(s: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("credits", s))
        toast(t("Скопировано", "Copied"))
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
        job("music", {
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
        job("music", {
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

    private fun montageReady() = task != StudioTask.CUTS && task != StudioTask.CARTOON && (videoReady() || clips().isNotEmpty())

    /** Cuts task with downloaded clips: always a montage (clips on their scenes, graphics in between). */
    private fun cutMontage() = task == StudioTask.CUTS && readyCuts().isNotEmpty()

    /**
     * What goes under a VIDEO-style video: the speaker's own video (with cuts), the background clips,
     * or (Cuts task) the YouTube clips on the scenes of [plan].
     */
    private fun bgSpec(plan: StudioPlan): StudioBg {
        if (task == StudioTask.CUTS) {
            val ready = readyCuts()
            val slots = StudioCuts.slots(plan.times, plan.script.scenes.map { it.clip }, ready.associateBy { it.id })
            return StudioBg(null, null, emptyList(), slots, ready.associate { it.id to File(cutsDir, it.file) })
        }
        return if (videoReady()) StudioBg(sourceFile, cuts, emptyList()) else StudioBg(null, null, clips())
    }

    private fun currentStyle(): StudioStyle {
        if (cutMontage()) return StudioStyle.VIDEO
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
        job("bg", {
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

    // ---------- Cuts: YouTube clips ----------

    private fun loadCuts() {
        cutClips = if (task == StudioTask.CUTS && cutsFile.exists()) StudioCuts.fromJson(cutsFile.readText()) else emptyList()
    }

    private fun saveCuts(list: List<SClip>) {
        cutClips = list
        runCatching { cutsFile.writeText(StudioCuts.toJson(list)) }.onFailure { L.w("studio", "save clips: ${it.message}") }
    }

    /** Clips that are downloaded and cut. */
    private fun readyCuts(): List<SClip> = cutClips.filter { it.file.isNotEmpty() && it.lenMs > 0 && File(cutsDir, it.file).exists() }

    private fun readyIds(): Set<String> = readyCuts().map { it.id }.toSet()

    private fun withClips(sc: SScript, ids: List<String>) = sc.copy(scenes = sc.scenes.mapIndexed { i, s -> s.copy(clip = ids.getOrElse(i) { "" }) })

    private fun cutsCard(c: Col) = c.card {
        add(text(ctx, t("🎞 Клипы с YouTube", "🎞 YouTube clips"), Type.TITLE_M, bold = true), 0)
        muted(t("Отметьте куски видео: ссылка, начало и конец (до 30 с) и что в кадре — по этой подписи AI-режиссёр поймёт, куда поставить клип. " +
            "Скачиваются только эти секунды, без звука. Короткие вставки по 1–5 с безопаснее для авторских прав.",
            "Mark pieces of videos: link, start and end (up to 30 s) and what is in the shot; the AI director uses this note to place the clip. " +
                "Only these seconds are downloaded, without sound. Short 1–5 s inserts are safer for copyright."))
        cutClips.forEach { cl ->
            val ready = cl.file.isNotEmpty() && File(cutsDir, cl.file).exists()
            val about = cl.note.ifBlank { cl.title }.ifBlank { cl.url }
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(text(ctx, (if (ready) "✅ " else "⬇ ") + "${cl.id} · ${StudioCuts.stamp(cl.fromMs)}–${StudioCuts.stamp(cl.toMs)}\n" +
                    about.take(90), 14f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (busy.isEmpty()) {
                    addView(btn(ctx, "✎", style = BtnStyle.TEXT) { editClip(cl) }.apply { contentDescription = t("Изменить ${cl.id}", "Edit ${cl.id}") })
                    addView(btn(ctx, "✕", style = BtnStyle.TEXT) { removeClip(cl) }.apply { contentDescription = t("Убрать ${cl.id}", "Remove ${cl.id}") })
                }
            }
            add(line, 6)
        }
        progress("cuts", t("Загрузка клипов…", "Downloading clips…"))
        if (busy.isNotEmpty()) return@card
        val addBtn = btn(ctx, t("＋ Клип по ссылке", "＋ Clip by link"), style = BtnStyle.TONAL) { editClip(null) }
        val findBtn = btn(ctx, t("🔎 Найти видео", "🔎 Find videos"), style = BtnStyle.TONAL) { findClips() }
        row(addBtn, findBtn)
        if (cutClips.any { it !in readyCuts() }) button(t("⬇ Скачать", "⬇ Download"), primary = true) { downloadCuts() }
        if (script != null && readyCuts().isNotEmpty()) {
            button(t("🎞 Расставить по сценам без AI", "🎞 Place on scenes without AI"), style = BtnStyle.TEXT) { autoPlace() }
        }
    }

    // ---------- cartoon characters ----------

    /** Characters of the cartoon: who they are, how they look and sound. */
    private fun castCard(c: Col) = c.card {
        add(text(ctx, t("🎭 Персонажи", "🎭 Characters"), Type.TITLE_M, bold = true), 0)
        muted(t("До ${StudioToon.MAX_CAST} героев. В сценарии реплика начинается с @Имя: — AI сам распределит реплики. Голоса Microsoft подбираются по полу, тон — по виду героя.",
            "Up to ${StudioToon.MAX_CAST} characters. In the script a line starts with @Name: — AI assigns the lines. Microsoft voices are picked by gender, the pitch by the look."))
        val cast = StudioPrefs.cast
        cast.forEachIndexed { i, ch ->
            val voice = when (ch.voice) {
                "m" -> t("♂ мужской", "♂ male")
                "f" -> t("♀ женский", "♀ female")
                "" -> if (StudioToon.gender(ch.look) == "Male") t("♂ мужской", "♂ male") else t("♀ женский", "♀ female")
                else -> ch.voice.substringAfterLast('-').removeSuffix("Neural")
            }
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply {
                    setImageBitmap(charBitmap(ch, dp(56)))
                    contentDescription = StudioToon.lookLabel(ch.look)
                }, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(text(ctx, "@${ch.name}\n${StudioToon.emoji(ch.look)} ${StudioToon.lookLabel(ch.look)} · $voice", 14f),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
                if (busy.isEmpty()) {
                    addView(btn(ctx, "✎", style = BtnStyle.TEXT) { editChar(i) }.apply { contentDescription = t("Изменить ${ch.name}", "Edit ${ch.name}") })
                    if (cast.size > 1) addView(btn(ctx, "✕", style = BtnStyle.TEXT) {
                        StudioPrefs.cast = cast.filterIndexed { k, _ -> k != i }
                        render()
                    }.apply { contentDescription = t("Убрать ${ch.name}", "Remove ${ch.name}") })
                }
            }
            add(line, 6)
        }
        if (cast.size < StudioToon.MAX_CAST && busy.isEmpty()) button(t("＋ Персонаж", "＋ Character"), style = BtnStyle.TONAL) { editChar(-1) }
    }

    /** A character drawn on a transparent square [px]. */
    private fun charBitmap(ch: SChar, px: Int, mood: String = "happy"): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        StudioToonArt().draw(android.graphics.Canvas(bmp), ch, px / 2f, px * 0.97f, px * 0.78f,
            ToonPose(mood = mood, tMs = 1000, accent = StudioPrefs.accent))
        return bmp
    }

    /** Adds a character ([index] == -1) or changes one. */
    private fun editChar(index: Int) {
        val cast = StudioPrefs.cast
        val old = cast.getOrNull(index)
        var look = old?.look ?: StudioToon.LOOKS.firstOrNull { l -> cast.none { it.look == l } } ?: "blob"
        var voice = old?.voice ?: ""
        val nameE = edt(this, t("Имя (одно слово)", "Name (one word)"), old?.name ?: StudioToon.lookLabel(look))
        val col = Col(this, 20)
        val img = ImageView(this)
        fun build() {
            col.root.removeAllViews()
            img.setImageBitmap(charBitmap(SChar(nameE.text.toString().ifBlank { "?" }, look, voice), dp(120), "explain"))
            (img.parent as? ViewGroup)?.removeView(img)
            col.add(img, 0).layoutParams = LinearLayout.LayoutParams(dp(120), dp(120)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL }
            (nameE.parent as? ViewGroup)?.removeView(nameE)
            col.add(nameE, 8)
            col.add(text(this, t("Вид", "Look"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            col.chips(StudioToon.LOOKS.map { l ->
                chip(this, StudioToon.emoji(l) + " " + StudioToon.lookLabel(l), l == look) {
                    val auto = nameE.text.isBlank() || StudioToon.LOOKS.any { StudioToon.lookLabel(it) == nameE.text.toString() }
                    look = l
                    if (auto) nameE.setText(StudioToon.lookLabel(l))
                    build()
                }
            }, collapseAfter = 99)
            col.add(text(this, t("Голос", "Voice"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val opts = listOf("" to t("Авто", "Auto"), "f" to t("♀ Женский", "♀ Female"), "m" to t("♂ Мужской", "♂ Male"))
            col.chips(opts.map { (v, label) ->
                chip(this, label, v == voice || (voice !in listOf("", "f", "m") && v == "")) {
                    voice = v
                    build()
                }
            })
        }
        build()
        android.app.AlertDialog.Builder(this).setTitle(if (old == null) t("Новый персонаж", "New character") else "@" + old.name)
            .setView(ScrollView(this).apply { addView(col.root) })
            .setPositiveButton("OK") { _, _ ->
                val name = StudioToon.cleanName(nameE.text.toString()).ifEmpty { StudioToon.cleanName(StudioToon.lookLabel(look)) }
                if (cast.withIndex().any { (k, ch) -> k != index && ch.name.equals(name, true) }) {
                    toast(t("Имя «$name» уже занято", "The name \"$name\" is taken"))
                    return@setPositiveButton
                }
                val ch = SChar(name, look, voice)
                val list = cast.toMutableList()
                if (old == null) list += ch else list[index] = ch
                StudioPrefs.cast = list
                // the script refers to the old name: rename the lines
                if (old != null && old.name != name) {
                    scriptEdit.setText(scriptEdit.text.toString().replace(Regex("(^|\\n)@" + Regex.escape(old.name) + "(?=[(:\\s])"), "$1@$name"))
                    script = script?.let { sc -> sc.copy(scenes = sc.scenes.map { if (it.who == old.name) it.copy(who = name) else it }) }
                }
                board = emptyList()
                render()
            }
            .setNegativeButton(t("Отмена", "Cancel"), null).show()
    }

    /**
     * Finds videos by title on YouTube, optionally lets AI keep only the ones that fit a description,
     * and adds the marked ones as clips (start 0:00, the time is set later with ✎).
     */
    private fun findClips() {
        var hits: List<CutHit> = emptyList()
        var notes: Map<String, String> = emptyMap()
        val marked = LinkedHashSet<String>()
        lateinit var box: LinearLayout
        lateinit var status: Pair<TextView, ProgressBar>
        lateinit var addBtn: Button
        var dismissSheet: () -> Unit = {}

        fun updateAdd() {
            addBtn.text = t("＋ Добавить отмеченные (${marked.size})", "＋ Add marked (${marked.size})")
            addBtn.isEnabled = marked.isNotEmpty()
        }

        fun fill() {
            box.removeAllViews()
            val picked = hits.filter { it.url in notes }
            val rest = hits.filter { it.url !in notes }
            fun group(title: String, list: List<CutHit>) {
                if (list.isEmpty()) return
                if (picked.isNotEmpty()) box.addView(text(this, title, Type.LABEL, bold = true, color = Palette.MUTED),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
                for (h in list) {
                    val sub = listOf(h.channel, if (h.durationMs > 0) StudioCuts.stamp(h.durationMs) else "").filter { it.isNotBlank() }.joinToString(" · ")
                    val note = notes[h.url].orEmpty()
                    val label = h.title + (if (sub.isNotEmpty()) "\n$sub" else "") + (if (note.isNotEmpty()) "\n✨ $note" else "")
                    box.addView(chk(this, label, h.url in marked) { on ->
                        if (on) marked += h.url else marked -= h.url
                        updateAdd()
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                }
            }
            group(t("✨ Подобрал AI", "✨ Picked by AI"), picked)
            group(t("Остальные", "Others"), rest)
            updateAdd()
        }

        fun search(q: String, want: String) {
            if (q.isBlank()) return
            val useAi = want.isNotBlank()
            status.first.text = t("Поиск…", "Searching…")
            status.first.visibility = View.VISIBLE
            status.second.visibility = View.VISIBLE
            cancel = false
            Lumen.async({
                val found = YouTube.search(listOf(q), SearchFilters(limit = 30)).map {
                    CutHit(it.url, it.title, it.channel, if (it.duration > 0) it.duration * 1000 else 0)
                }
                val picks = if (useAi && found.isNotEmpty()) {
                    runOnUiThread { status.first.text = t("AI отбирает видео…", "AI is picking videos…") }
                    aiJson("studio_cuts_filter", mapOf("want" to want, "lang" to aiLang(), "hits" to StudioCuts.hitsBlock(found)),
                        "You pick YouTube videos by their titles. Answer with JSON only.", 2000, fast = true) { StudioCuts.parsePicks(it, found.size) }
                } else emptyMap()
                found to picks
            }, { e ->
                status.second.visibility = View.GONE
                status.first.text = (e.message ?: e.javaClass.simpleName).take(200)
            }) { (found, picks) ->
                status.second.visibility = View.GONE
                hits = found
                notes = picks.mapKeys { found[it.key].url }.mapValues { it.value.ifBlank { found.first { h -> h.url == it.key }.title } }
                marked.clear()
                marked += notes.keys
                status.first.text = when {
                    found.isEmpty() -> t("Ничего не нашлось. Попробуйте другие слова.", "Nothing found. Try other words.")
                    useAi -> t("Найдено: ${found.size}, AI подобрал: ${picks.size}", "Found: ${found.size}, AI picked: ${picks.size}")
                    else -> t("Найдено: ${found.size}. Отметьте нужные.", "Found: ${found.size}. Mark the ones you need.")
                }
                fill()
            }
        }

        bottomSheet(t("Найти видео для нарезки", "Find videos for cuts")) { dismiss ->
            dismissSheet = dismiss
            muted(t("Поиск по названию на YouTube. AI-фильтр (необязательно) оставит видео, подходящие под описание, — по названиям; в AI уходят только названия. " +
                "Отмеченные добавятся клипами с 0:00 на 5 с — время поправьте через ✎.",
                "Search YouTube by title. The AI filter (optional) keeps videos that fit the description, by titles; only the titles go to AI. " +
                    "Marked ones are added as clips from 0:00 for 5 s; adjust the time with ✎."))
            val query = edit(t("Что искать, напр. «Хоумлендер реакция»", "What to search, e.g. \"shocked reaction meme\""))
            val want = edit(t("AI-фильтр: что должно быть в кадре (можно пусто)", "AI filter: what must be in the shot (optional)"))
            row(
                btn(ctx, t("🔎 Искать", "🔎 Search"), style = BtnStyle.TONAL) { search(query.text.toString().trim(), "") },
                btn(ctx, t("✨ Искать с AI", "✨ Search with AI"), primary = true) {
                    val w = want.text.toString().trim().ifBlank { query.text.toString().trim() }
                    search(query.text.toString().trim(), w)
                }
            )
            status = working("")
            status.first.visibility = View.GONE
            status.second.visibility = View.GONE
            box = add(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL })
            addBtn = button(t("＋ Добавить отмеченные", "＋ Add marked"), primary = true) {
                val chosen = hits.filter { it.url in marked }
                if (chosen.isEmpty()) return@button
                val before = cutClips.size
                val list = StudioCuts.addHits(cutClips, chosen, notes)
                if (list.size - before < chosen.size) {
                    toast(t("Добавлено ${list.size - before}: повторы пропущены, максимум ${StudioCuts.MAX_CLIPS}",
                        "Added ${list.size - before}: repeats skipped, at most ${StudioCuts.MAX_CLIPS}"))
                }
                saveCuts(list)
                board = emptyList()
                dismissSheet()
                render()
            }
            updateAdd()
        }
    }

    /** Adds a clip ([old] == null) or changes one; a new link or time drops the downloaded file. */
    private fun editClip(old: SClip?) {
        val clipText = runCatching {
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull()?.trim().orEmpty()
        val url0 = old?.url ?: clipText.takeIf { YtQuery.videoIdFromUrl(it) != null }.orEmpty()
        val urlE = edt(this, t("Ссылка на видео YouTube", "YouTube video link"), url0)
        val fromE = edt(this, t("Начало: 1:23 или 83", "Start: 1:23 or 83"), old?.let { StudioCuts.stamp(it.fromMs) } ?: StudioCuts.startOf(url0)?.let { StudioCuts.stamp(it) }.orEmpty())
        val toE = edt(this, t("Конец (по умолчанию +5 с)", "End (default +5 s)"), old?.let { StudioCuts.stamp(it.toMs) }.orEmpty())
        val noteE = edt(this, t("Что в кадре: «Хоумлендер в шоке», «интерфейс Codex»", "What is in the shot: \"shocked face\", \"Codex UI\""), old?.note.orEmpty())
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(24)
            setPadding(p, dp(8), p, 0)
            listOf(urlE, fromE, toE, noteE).forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        }
        android.app.AlertDialog.Builder(this).setTitle(if (old == null) t("Новый клип", "New clip") else t("Клип ${old.id}", "Clip ${old.id}")).setView(box)
            .setPositiveButton("OK") { _, _ ->
                val url = urlE.text.toString().trim()
                val id = YtQuery.videoIdFromUrl(url)
                if (id == null) {
                    toast(t("Нужна ссылка на видео YouTube", "A YouTube video link is needed"))
                    return@setPositiveButton
                }
                val from = StudioCuts.parseTime(fromE.text.toString()) ?: StudioCuts.startOf(url) ?: 0L
                val to = toE.text.toString().takeIf { it.isNotBlank() }?.let { StudioCuts.parseTime(it) }
                val (a, b) = StudioCuts.span(from, to)
                val note = noteE.text.toString().trim().take(200)
                val list = cutClips.toMutableList()
                if (old == null) {
                    if (list.size >= StudioCuts.MAX_CLIPS) {
                        toast(t("Не больше ${StudioCuts.MAX_CLIPS} клипов", "At most ${StudioCuts.MAX_CLIPS} clips"))
                        return@setPositiveButton
                    }
                    list += SClip(StudioCuts.newId(list), url, a, b, note)
                } else {
                    val same = old.url == url && old.fromMs == a && old.toMs == b
                    if (!same && old.file.isNotEmpty()) File(cutsDir, old.file).delete()
                    val upd = if (same) old.copy(note = note) else old.copy(url = url, fromMs = a, toMs = b, note = note, title = "", file = "", leadMs = 0, lenMs = 0)
                    list[list.indexOfFirst { it.id == old.id }.coerceAtLeast(0)] = upd
                }
                saveCuts(list)
                board = emptyList()
                render()
            }
            .setNegativeButton(t("Отмена", "Cancel"), null).show()
    }

    private fun removeClip(cl: SClip) = confirm(t("Убрать клип ${cl.id}?", "Remove clip ${cl.id}?"), cl.note.ifBlank { cl.url }, t("Убрать", "Remove")) {
        if (cl.file.isNotEmpty()) File(cutsDir, cl.file).delete()
        saveCuts(cutClips.filter { it.id != cl.id })
        board = emptyList()
        render()
    }

    /** Downloads every clip that is not ready yet, one after another; a failed one is reported and skipped. */
    private fun downloadCuts() {
        val todo = cutClips.filter { it !in readyCuts() }
        if (todo.isEmpty()) return
        busy = "cuts"
        cancel = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        val dirNow = cutsDir
        val maxH = StudioPrefs.quality
        job("cuts", {
            val done = java.util.concurrent.ConcurrentHashMap<String, SClip>()
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            // two clips at a time: YouTube throttles more parallel downloads
            Par.mapAll(todo, 2) { i, cl ->
                if (cancel) throw java.util.concurrent.CancellationException()
                report("cuts", t("Клип ${i + 1}/${todo.size}", "Clip ${i + 1}/${todo.size}"))
                try {
                    done[cl.id] = StudioCutsLoad.fetch(cl, dirNow, maxH, { cancel }) { report("cuts", it) }
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w("studio", "clip ${cl.id}: ${e.message}")
                    errors += "${cl.id}: ${e.message ?: e.javaClass.simpleName}"
                }
                i
            }
            HashMap(done) to errors.toList()
        }, { e ->
            busy = ""
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            render()
            if (e !is java.util.concurrent.CancellationException) err(e)
        }) { (done, errors) ->
            busy = ""
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            saveCuts(cutClips.map { done[it.id] ?: it })
            board = emptyList()
            render()
            if (errors.isNotEmpty()) showText(t("Не все клипы скачались", "Some clips failed"), errors.joinToString("\n"))
            else toast(t("Клипов скачано: ${done.size}", "Clips downloaded: ${done.size}"))
        }
    }

    /** Picks the clip of scene [i] (or graphics). */
    private fun pickClip(i: Int) {
        val sc = script ?: return
        val ready = readyCuts()
        val labels = listOf(t("— графика, без клипа", "— graphics, no clip")) +
            ready.map { "🎞 ${it.id} · ${it.note.ifBlank { it.title }.take(50)} · ${(it.lenMs + 500) / 1000} с" }
        android.app.AlertDialog.Builder(this).setTitle(t("Сцена ${i + 1}", "Scene ${i + 1}"))
            .setItems(labels.toTypedArray()) { _, k ->
                val id = if (k == 0) "" else ready[k - 1].id
                script = sc.copy(scenes = sc.scenes.mapIndexed { j, s -> if (j == i) s.copy(clip = id) else s })
                board = emptyList()
                saveAnalysis()
                render()
            }
            .setNegativeButton(t("Отмена", "Cancel"), null).show()
    }

    /** Puts the downloaded clips on scenes by the words of their notes, without the AI. */
    private fun autoPlace() {
        val sc = script ?: return
        script = withClips(sc, StudioCuts.autoAssign(sc.scenes, readyCuts()))
        board = emptyList()
        saveAnalysis()
        render()
        toast(t("Клипы расставлены: нажмите на сцену, чтобы поменять", "Clips placed: tap a scene to change"))
    }

    private fun drawPreview(plan: StudioPlan) {
        val img = preview ?: return
        val at = previewMs.coerceIn(0, plan.totalMs)
        img.contentDescription = t("Кадр на ", "Frame at ") + Studio.clock(previewMs)
        val (pw, ph) = aspect().size(360)
        if (plan.style != StudioStyle.VIDEO) {
            img.setImageBitmap(StudioVideo.preview(plan, at, pw, ph))
            return
        }
        // reading a video frame is slow: one at a time in the background, the latest position wins
        if (previewBusy) {
            previewAgain = true
            return
        }
        previewBusy = true
        val src = bgSpec(plan)
        Lumen.async({ StudioVideo.preview(plan, at, pw, ph, src) }, { previewBusy = false }) { bmp ->
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
        val style = currentStyle()
        val clipScenes = if (task == StudioTask.CUTS && style == StudioStyle.VIDEO) readyIds().let { ids -> sc.scenes.indices.filter { sc.scenes[it].clip in ids }.toSet() }
        else null
        return StudioPlan.build(sc, words, voice.ms + 500, style, StudioPrefs.accent, ranges, StudioPrefs.overlay,
            StudioPrefs.zooms, StudioPrefs.transitions,
            if (StudioPrefs.pulse) pulseOf(voice) else null,
            if (StudioPrefs.liveEmoji) emojiDir else null,
            clipScenes, if (task == StudioTask.CARTOON) StudioPrefs.cast else null)
    }

    private val emojiDir get() = File(dir, "emoji")
    private var pulseKey: ShortArray? = null
    private var pulseVal: FloatArray? = null

    /** Smoothed loudness of the voice for the pulse (computed once per voice track). */
    private fun pulseOf(voice: StudioAudio.Pcm): FloatArray {
        if (pulseKey === voice.data) pulseVal?.let { return it }
        val v = StudioRhythm.pulse(Studio.frameRms(voice.data, voice.rate))
        pulseKey = voice.data
        pulseVal = v
        return v
    }

    private fun detach(v: View): View {
        (v.parent as? ViewGroup)?.removeView(v)
        return v
    }

    /** Shape of the frame for the current task. */
    private fun aspect(): StudioAspect = when (task) {
        StudioTask.SHORTS -> StudioAspect.PORTRAIT
        StudioTask.VIDEO -> StudioAspect.LANDSCAPE
        StudioTask.VOICE -> StudioAspect.of(StudioPrefs.aspect)
        StudioTask.CUTS -> StudioAspect.of(StudioPrefs.cutsAspect)
        StudioTask.CARTOON -> StudioAspect.of(StudioPrefs.cartoonAspect)
    }

    /** A long YouTube script (2–10 minutes with chapters) rather than a Shorts one. */
    private fun longScript() = task == StudioTask.VIDEO || (task == StudioTask.CUTS || task == StudioTask.CARTOON) && aspect() != StudioAspect.PORTRAIT

    /** Another task: its own script, take and analysis are loaded; nothing is deleted. */
    private fun switchTask(to: StudioTask) {
        if (to == task) return
        if (busy.isNotEmpty() || recorder != null || StudioJob.running) {
            toast(t("Дождитесь окончания текущего шага", "Wait for the current step to finish"))
            render()
            return
        }
        saveInputs()
        player?.release()
        player = null
        StudioPrefs.task = to.id
        prevScript = null
        if (to == StudioTask.VOICE && StudioPrefs.voiceMode !in listOf("file", "mic")) StudioPrefs.voiceMode = "file"
        if ((to == StudioTask.CUTS || to == StudioTask.CARTOON) && StudioPrefs.voiceMode == "video") StudioPrefs.voiceMode = "edge"
        clearState()
        loadCuts()
        lastVideo = null
        savedAs = ""
        previewMs = 0
        scriptEdit.setText(StudioPrefs.script)
        topicEdit.setText(StudioPrefs.topic)
        render()
        restoreAnalysis()
    }

    private fun resetAnalysis() {
        analysisFile.delete()
        voiceFile.delete()
        clearState()
    }

    private fun clearState() {
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
            "[stat: 80%] Столько времени уходит на подготовку.\n⏱ подготовка\n\n" +
            "Анимация: реплику персонажа начните с @Имя: или @Имя(эмоция): — эмоции calm, happy, explain, think, surprise, sad, angry.\n" +
            "@Лиса(surprise): [big] Подожди, а почему небо синее?\n💡 загадка",
        "Each scene is its own paragraph (a blank line between scenes).\n\n" +
            "The first line may start with the kind of visual:\n" +
            "[icons] emoji tiles\n[list] checklist\n[flow] process steps\n[cycle] a cycle\n" +
            "[step: Title] stage number\n[big] one big emoji\n[stat: 80%] a big number\n" +
            "Without a tag it is plain text.\n\n" +
            "A line with \"|\" lists tiles: 🔍 research | 📚 context | ⚙️ process\n\n" +
            "Example:\n[icons] How is engineering different from vibe coding?\n🔍 research | 📚 context | ✅ checks\n\n" +
            "[step: Context] Stage two: collect the docs.\n\n" +
            "[stat: 80%] That much time goes to preparation.\n⏱ preparation\n\n" +
            "Animation: start a character's line with @Name: or @Name(mood): — moods calm, happy, explain, think, surprise, sad, angry.\n" +
            "@Foxy(surprise): [big] Wait, why is the sky blue?\n💡 riddle"
    ))

    // ---------- step 1: AI script ----------

    private fun aiLang() = if (lang() == "en" || Settings.lang == "en" && scriptEdit.text.isBlank()) "English" else "русский"

    private fun writeScript() {
        val topic = topicEdit.text.toString().trim().ifBlank { scriptEdit.text.toString().trim().take(300) }
        if (topic.isBlank()) {
            toast(t("Введите тему или идею ролика", "Type a topic or idea first"))
            return
        }
        val extra = if (scriptEdit.text.isNotBlank() && topicEdit.text.isNotBlank())
            "Учти черновик автора и сохрани его мысли:\n" + scriptEdit.text.toString().take(3000) else ""
        val go = { runWrite(topic, extra) {} }
        if (scriptEdit.text.isNotBlank()) confirm(t("Заменить сценарий?", "Replace the script?"),
            t("AI напишет новый сценарий по теме; текущий текст будет учтён как черновик.", "AI will write a new script; the current text is used as a draft."),
            t("Написать", "Write"), go) else go()
    }

    /** AI writes the script on [topic] (with the author's [extra] wishes); [then] runs after it is in the editor. */
    private fun runWrite(topic: String, extra: String, then: () -> Unit) {
        busy = "ai_script"
        cancel = false
        render()
        val long = longScript()
        val sec = if (long) StudioPrefs.videoSeconds else StudioPrefs.seconds
        val cartoon = task == StudioTask.CARTOON
        val cast = StudioPrefs.cast
        job("ai_script", {
            val sc = aiScript(if (cartoon) "studio_script_cartoon" else if (long) "studio_script_video" else "studio_script", mapOf(
                "topic" to topic, "seconds" to sec.toString(), "words" to Studio.wordsFor(sec).toString(),
                "lang" to aiLang(), "extra" to extra, "visuals" to STUDIO_VISUALS, "cast" to StudioToon.promptCast(cast)
            ), if (long) 14_000 else 6000)
            // names as in the cast, so "@Name" finds its character
            if (cartoon) sc.copy(scenes = sc.scenes.map { s -> s.copy(who = cast.getOrNull(StudioToon.find(cast, s.who))?.name ?: "") }) else sc
        }, { e -> busy = ""; render(); err(e) }) { sc ->
            busy = ""
            prevScript = scriptEdit.text.toString().takeIf { it.isNotBlank() }
            scriptEdit.setText(Studio.toText(sc))
            resetAnalysis()
            render()
            then()
        }
    }

    /** "AI makes everything" for the animation: the author describes an idea, scenes and/or a script (all optional). */
    private var autoAll = false

    private fun autoDialog() {
        if (Provider.active() == null) {
            toast(t("Подключите AI в настройках", "Connect an AI in the settings"))
            return
        }
        val ideaE = edt(this, t("Идея: о чём мультфильм", "Idea: what the cartoon is about"), topicEdit.text.toString(), multiline = true)
        val scenesE = edt(this, t("Сцены (необязательно): что происходит по порядку, по строке на сцену", "Scenes (optional): what happens in order, one line per scene"), "", multiline = true)
        val scriptE = edt(this, t("Сценарий или реплики (необязательно)", "Script or lines (optional)"), scriptEdit.text.toString(), multiline = true).apply { maxLines = 8 }
        var build = true
        val col = Col(this, 20)
        col.muted(t("Заполните любое из полей — остальное AI придумает сам: сценарий с репликами персонажей, сцены и графику, озвучку голосами героев, монтаж режиссёра и, если включено, сборку видео. Персонажи — из карточки «🎭 Персонажи», длительность и кадр — как выбраны на экране.",
            "Fill in any of the fields; AI invents the rest: the script with the characters' lines, scenes and graphics, the voices, the director's edit and, if on, the video build. Characters come from the \"🎭 Characters\" card, length and frame as chosen on the screen."))
        col.add(ideaE, 12)
        col.add(scenesE, 8)
        col.add(scriptE, 8)
        col.add(android.widget.CheckBox(this).apply {
            text = t("Сразу собрать видео", "Build the video right away")
            isChecked = true
            setOnCheckedChangeListener { _, v -> build = v }
        }, 8)
        android.app.AlertDialog.Builder(this).setTitle(t("🤖 AI сделает всё", "🤖 AI makes everything"))
            .setView(ScrollView(this).apply { addView(col.root) })
            .setPositiveButton(t("Начать", "Start")) { _, _ ->
                val idea = ideaE.text.toString().trim()
                val scenes = scenesE.text.toString().trim()
                val draft = scriptE.text.toString().trim()
                val topic = StudioAuto.topic(idea, scenes, draft)
                if (topic.isBlank()) {
                    toast(t("Опишите идею, сцены или сценарий", "Describe an idea, scenes or a script"))
                    return@setPositiveButton
                }
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                    runCatching { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1) }
                }
                topicEdit.setText(idea.ifBlank { topic })
                if (StudioPrefs.voiceMode != "edge") StudioPrefs.voiceMode = "edge"
                autoAll = build
                runWrite(topic, StudioAuto.extra(scenes, draft)) {
                    analyse()
                    if (busy.isEmpty()) autoAll = false
                }
            }
            .setNegativeButton(t("Отмена", "Cancel"), null).show()
    }

    /** Text of the script before the last edit of the AI assistant (for undo). */
    private var prevScript: String? = null

    /** AI assistant: ready requests as chips or the author's own words; the script comes back rewritten. */
    private fun assist() {
        val cartoon = task == StudioTask.CARTOON
        val presets = StudioAssist.presets(cartoon)
        val chosen = mutableSetOf<String>()
        val askE = edt(this, t("Своими словами: «добавь пример про деньги», «сделай 5 сцен»…", "In your words: \"add an example about money\", \"make it 5 scenes\"…"), multiline = true)
        val col = Col(this, 20)
        fun build() {
            col.root.removeAllViews()
            col.muted(t("Выберите одну или несколько просьб и/или напишите свою. Прежний текст можно будет вернуть.",
                "Pick one or more requests and/or write your own. The old text can be restored."))
            col.chips(presets.map { pr ->
                chip(this, pr.label, pr.id in chosen) {
                    if (!chosen.remove(pr.id)) chosen += pr.id
                    build()
                }
            }, collapseAfter = 99)
            (askE.parent as? ViewGroup)?.removeView(askE)
            col.add(askE, 12)
        }
        build()
        android.app.AlertDialog.Builder(this).setTitle(t("🪄 AI-помощник", "🪄 AI assistant"))
            .setView(ScrollView(this).apply { addView(col.root) })
            .setPositiveButton(t("Улучшить", "Improve")) { _, _ ->
                val asks = presets.filter { it.id in chosen }.map { it.ask } + listOfNotNull(askE.text.toString().trim().takeIf { it.isNotEmpty() })
                if (asks.isEmpty()) {
                    toast(t("Выберите просьбу или напишите свою", "Pick a request or write your own"))
                    return@setPositiveButton
                }
                runAssist(asks.joinToString("\n") { "- $it" })
            }
            .setNegativeButton(t("Отмена", "Cancel"), null).show()
    }

    private fun runAssist(ask: String) {
        val text = scriptEdit.text.toString()
        val before = Studio.parseText(text)
        if (before.scenes.isEmpty()) {
            toast(t("Сценарий пуст", "The script is empty"))
            return
        }
        val cast = if (task == StudioTask.CARTOON) StudioPrefs.cast else emptyList()
        val format = when (task) {
            StudioTask.VIDEO -> t("горизонтального ролика для YouTube", "a horizontal YouTube video")
            StudioTask.CARTOON -> t("познавательной анимации с персонажами", "an informational animation with characters")
            StudioTask.CUTS -> t("ролика-нарезки с голосом за кадром", "a cut video with a voice-over")
            else -> t("короткого вертикального ролика", "a short vertical video")
        }
        busy = "ai_edit"
        cancel = false
        render()
        job("ai_edit", {
            aiJson("studio_script_edit", mapOf(
                "format" to format, "lang" to aiLang(), "ask" to ask, "cast" to StudioAssist.castRule(cast),
                "visuals" to STUDIO_VISUALS, "script" to Studio.toJson(before)
            ), "You edit scripts for explainer videos. Answer with JSON only.", if (longScript()) 16_000 else 8000) { StudioAssist.parse(it) }
        }, { e -> busy = ""; render(); if (e !is java.util.concurrent.CancellationException) err(e) }) { res ->
            busy = ""
            val sc = if (cast.isEmpty()) res.script
            else res.script.copy(scenes = res.script.scenes.map { s -> s.copy(who = cast.getOrNull(StudioToon.find(cast, s.who))?.name ?: "") })
            prevScript = text
            scriptEdit.setText(Studio.toText(sc))
            resetAnalysis()
            render()
            showText(t("Сценарий обновлён", "Script updated"), (listOf(StudioAssist.summary(before, sc)) + res.notes.map { "• $it" }).joinToString("\n"))
        }
    }

    private fun aiScript(key: String, vars: Map<String, String>, limit: Int): SScript =
        aiJson(key, vars, "You write scripts for explainer videos. Answer with JSON only.", limit) { Studio.parseScript(it) }

    /** JSON call with a retry and a larger limit when the answer is cut off; [parse] turns the reply into a result. */
    private fun <T : Any> aiJson(key: String, vars: Map<String, String>, system: String, limit0: Int, fast: Boolean = false, parse: (String) -> T?): T {
        val prov = AiTasks.requireProvider()
        var limit = limit0
        var prompt = Prompts.fill(key, vars)
        var tokIn = 0
        var tokOut = 0
        repeat(3) { attempt ->
            if (cancel) throw java.util.concurrent.CancellationException()
            val r = Llm.complete(prov, if (fast) prov.fastModel else prov.smartModel, system, prompt, json = true, maxTokens = limit, task = key, pid = "studio")
            tokIn += r.inTokens
            tokOut += r.outTokens
            lastTokens = tokIn to tokOut
            val v = parse(r.text)
            if (v != null && !r.truncated) return v
            if (v != null && attempt == 2) return v
            L.i("studio", "$key attempt ${attempt + 1}: ${if (r.truncated) "truncated" else "invalid json"}")
            if (r.truncated) limit = minOf(limit * 2, 32_000)
            prompt = Prompts.fill(key, vars) + "\n\nПредыдущий ответ был неполным или не в формате JSON. Верни только JSON, компактно."
        }
        throw LlmException(t("AI не вернул ответ в нужном формате. Откройте «Ответы AI».", "AI did not answer in the expected format. See \"AI answers\"."))
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
        job("import", {
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
        val useScript = mode != "edge" && (task == StudioTask.VOICE || StudioPrefs.readScript) && sc.scenes.isNotEmpty()
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
            if (StudioPrefs.autoDirect || autoAll) {
                val ap = StudioApproach.of(StudioPrefs.approach)
                animate(if (ap.usesAi && Provider.active() == null) StudioApproach.RULES else ap, remember = false)
            } else autoFinish()
        }
        val fail = { e: Throwable ->
            busy = ""
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            render()
            if (e !is java.util.concurrent.CancellationException) err(e)
        }
        when {
            mode == "edge" -> job("analyse", { analyseEdge(sc) }, fail, done)
            useScript -> job("analyse", { analyseTake(sc) }, fail, done)
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

    /** Pieces of a scene saved by [storeScene]: "<key>_<k>.mp3" + "<key>_<k>.json" (words); null if incomplete. */
    private fun cachedScene(dir: File, key: String): List<Pair<ByteArray, List<SWord>>>? {
        val n = File(dir, "${key}_n").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: return null
        return (0 until n).map { k ->
            val mp3 = File(dir, "${key}_$k.mp3").takeIf { it.exists() }?.readBytes() ?: return null
            val ws = File(dir, "${key}_$k.json").takeIf { it.exists() }?.readText()?.let { StudioTtsCache.parseWords(it) } ?: return null
            mp3 to ws
        }
    }

    private fun storeScene(dir: File, key: String, pieces: List<Pair<ByteArray, List<SWord>>>) = runCatching {
        pieces.forEachIndexed { k, (mp3, ws) ->
            File(dir, "${key}_$k.mp3").writeBytes(mp3)
            File(dir, "${key}_$k.json").writeText(StudioTtsCache.wordsJson(ws))
        }
        File(dir, "${key}_n").writeText(pieces.size.toString())
    }

    private fun analyseEdge(sc: SScript) {
        val voice = StudioPrefs.voice.takeIf { v -> voices.any { it.shortName == v } && v.startsWith(lang(), true) }
            ?: EdgeTts.pickVoice(voices, lang(), TtsPrefs.voiceFor(lang()))?.shortName ?: TtsPrefs.voice
        val o = TtsOptions(voice, StudioPrefs.rate)
        val cast = if (task == StudioTask.CARTOON) StudioPrefs.cast else emptyList()
        val castVoices = StudioToon.pickVoices(cast, voices, lang(), voice)
        val tmp = File(cacheDir, "studio_tts").apply { mkdirs() }
        val all = mutableListOf<SWord>()
        val rs = mutableListOf<IntRange>()
        var audio = ShortArray(0)
        var rate = 24_000
        val n = sc.scenes.size
        // scenes are voiced and decoded at the same time (each is its own connection), then joined in order
        val ready = java.util.concurrent.atomic.AtomicInteger(0)
        report("analyse", t("Озвучка сцен 0/$n", "Voicing scenes 0/$n"))
        val voiced = try { Par.mapAll(sc.scenes, EdgeTts.WORKERS) { i, scene ->
            if (cancel) throw java.util.concurrent.CancellationException()
            val ci = StudioToon.find(cast, scene.who)
            val so = if (ci >= 0) TtsOptions(castVoices[ci], StudioPrefs.rate, StudioToon.pitch(cast[ci].look)) else o
            // a scene voiced in an earlier, interrupted attempt is taken from disk
            val key = StudioTtsCache.key(scene.say, so)
            val cached = cachedScene(tmp, key)
            val pieces = cached ?: (EdgeTts.synthesizeWords(scene.say, so) { cancel } ?: throw java.util.concurrent.CancellationException())
            if (cached == null) storeScene(tmp, key, pieces)
            val out = pieces.mapIndexed { k, (mp3, ws) ->
                val f = File(tmp, "dec_${key}_$k.mp3").apply { writeBytes(mp3) }
                StudioAudio.decode(f).also { f.delete() } to ws
            }
            report("analyse", t("Озвучка сцен ${ready.incrementAndGet()}/$n", "Voicing scenes ${ready.get()}/$n"))
            out
        } } catch (e: EdgeTts.TtsException) {
            // "Read aloud" is the TTS screen's button; here the step is restarted, and the voiced scenes are kept
            throw EdgeTts.TtsException(e.message.orEmpty().substringBefore(t(" Нажмите", " Tap")).substringBefore(t(" Готово частей", " Parts done")) +
                t(" Озвучено сцен: ${ready.get()} из $n, они сохранены — нажмите «🔍 Разобрать» ещё раз, продолжится с остальных. Проверьте интернет; держите Студию открытой или свёрнутой с уведомлением.",
                    " Scenes voiced: ${ready.get()} of $n, they are kept — tap \"🔍 Analyse\" again to continue with the rest. Check the internet; keep the Studio open or minimised with its notification."))
        }
        if (cancel) throw java.util.concurrent.CancellationException()
        report("analyse", t("Сборка дорожки…", "Joining the track…"))
        // pieces are joined once at the end: no copying of the growing track for every scene
        val chunks = mutableListOf<ShortArray>()
        var len = 0
        sc.scenes.forEachIndexed { i, scene ->
            val first = all.size
            voiced[i].forEach { (p, ws) ->
                val data = if (chunks.isEmpty() || p.rate == rate) p.data else Studio.resample(p.data, p.rate, rate)
                if (chunks.isEmpty()) rate = p.rate
                val shift = Studio.durationMs(len, rate)
                all += Studio.dress(ws, Studio.tokens(scene.say)).map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) }
                chunks += data
                len += data.size
            }
            rs += first until all.size
            val gap = Studio.silence(if (i < n - 1) 180 else 400, rate)
            chunks += gap
            len += gap.size
        }
        audio = Studio.concat(chunks)
        report("analyse", t("Озвучка сцен $n/$n", "Voicing scenes $n/$n"))
        // only the scenes of this script stay for the next run (e.g. after a small edit)
        val keep = sc.scenes.mapIndexed { i, scene ->
            val ci = StudioToon.find(cast, scene.who)
            StudioTtsCache.key(scene.say, if (ci >= 0) TtsOptions(castVoices[ci], StudioPrefs.rate, StudioToon.pitch(cast[ci].look)) else o)
        }.toSet()
        tmp.listFiles()?.filter { it.name.substringBefore('_') !in keep }?.forEach { it.delete() }
        if (all.isEmpty()) error(t("Сервис не вернул разметку слов. Попробуйте другой голос.", "The service returned no word timings. Try another voice."))
        val take = StudioAudio.Pcm(audio, rate)
        pcm = take
        words = all
        script = sc
        ranges = rs
        stats = VoiceStats(take.ms, take.ms, 0, 0, all.size)
    }

    /** Tokens of the last AI direction (in → out), to compare the ways. */
    @Volatile private var lastTokens: Pair<Int, Int>? = null

    private fun approachHelp(a: StudioApproach) = when (a) {
        StudioApproach.RULES -> t("⚡ Правила — без AI и токенов: цифры, шаги, сравнения, списки, цитаты и эмодзи по смыслу слов; зум и подсветка на главном слове.",
            "⚡ Rules: no AI, no tokens. Numbers, steps, comparisons, lists, quotes and emoji from the words; zoom and highlight on the key word.")
        StudioApproach.RHYTHM -> t("🥁 Ритм — без AI: сцены по паузам, зумы на самых громких словах и долях музыки, «бум» на пике, пульс картинки под голос.",
            "🥁 Rhythm: no AI. Scenes by pauses, zooms on the loudest words and music beats, a boom on the peak, the picture pulses with the voice.")
        StudioApproach.LITE -> t("💸 Эконом AI — быстрая модель и сжатый протокол: метки только в начале фраз, ответ короткими строками. В 3–5 раз меньше токенов.",
            "💸 Lite AI: the fast model and a compact protocol (marks only at clause starts, short string answers). 3–5 times fewer tokens.")
        StudioApproach.HYBRID -> t("🧩 Гибрид — черновик по правилам, а умная модель переделывает только ключевые сцены (хук, цифры, вывод).",
            "🧩 Hybrid: a rules draft; the smart model redoes only the key scenes (hook, numbers, ending).")
        StudioApproach.FULL -> t("🎬 Полный AI-режиссёр — подробный протокол: графика, фигуры на ключевых кадрах, B-roll, звуки, вырезка паразитов. Больше всего токенов.",
            "🎬 Full AI director: the detailed protocol with graphics, key-frame shapes, B-roll, sounds and cutting fillers. Most tokens.")
    }

    private fun formatText() = when (task) {
        StudioTask.SHORTS -> t("короткого вертикального ролика (Shorts 9:16)", "a short vertical video (Shorts 9:16)")
        StudioTask.VIDEO -> t("горизонтального ролика для YouTube (16:9)", "a horizontal YouTube video (16:9)")
        StudioTask.VOICE -> t("анимированного ролика по готовой озвучке (кадр ", "an animated video for a ready voice-over (frame ") + aspect().label + ")"
        StudioTask.CUTS -> t("ролика-нарезки: голос за кадром поверх вставок из чужих видео и графики (кадр ",
            "a cut video: a voice-over over pieces of other videos and graphics (frame ") + aspect().label + ")"
        StudioTask.CARTOON -> t("познавательной анимации: персонажи говорят по очереди, графика рядом с ними (кадр ",
            "an informational animation: characters talk in turns, graphics next to them (frame ") + aspect().label + ")"
    }

    /** Runs a way of animation; [remember] makes it the chosen one. */
    private fun animate(ap: StudioApproach, remember: Boolean = true) {
        if (remember) StudioPrefs.approach = ap.id
        if (ap == StudioApproach.FULL) direct() else directBy(ap)
    }

    /** Rules, rhythm, lite AI or hybrid: a plan without cutting the take. */
    private fun directBy(ap: StudioApproach) {
        val p0 = pcm ?: return
        val ws = words
        if (ws.isEmpty() || busy.isNotEmpty()) return
        val music = musicFile.takeIf { ap == StudioApproach.RHYTHM && it.exists() }
        val long = task == StudioTask.VIDEO || p0.ms > 180_000
        val cast = if (task == StudioTask.CARTOON) StudioPrefs.cast else emptyList()
        val roles = script?.let { sc -> ranges?.let { StudioToon.wordRoles(sc.scenes, it, ws.size, cast) } }
        busy = "ai_direct"
        cancel = false
        render()
        job("ai_direct", {
            val d = when (ap) {
                StudioApproach.RULES -> StudioRules.direct(ws)
                StudioApproach.RHYTHM -> {
                    val loud = StudioRhythm.wordLoudness(ws, Studio.frameRms(p0.data, p0.rate))
                    val beats = music?.let { f -> Studio.readWav(f.readBytes())?.let { (m, rate) -> StudioRhythm.onsets(m, rate) } } ?: emptyList()
                    StudioRhythm.direct(ws, loud, beats)
                }
                StudioApproach.LITE -> aiJson("studio_direct_lite", mapOf(
                    "format" to formatText(), "long" to if (long) ", в длинном ролике до 12 с" else "", "lang" to aiLang(),
                    "data" to StudioCompact.transcript(ws)
                ), "You are a video editor. Answer with compact JSON only.", if (ws.size > 600) 8000 else 3000, fast = true) { StudioCompact.parse(it, ws) }
                else -> {
                    val draft = StudioRules.direct(ws)
                    val n = maxOf(2, draft.scenes.size * 3 / 10)
                    runCatching {
                        aiJson("studio_direct_patch", mapOf(
                            "format" to formatText(), "n" to n.toString(), "lang" to aiLang(), "data" to StudioCompact.listing(draft, ws)
                        ), "You are a video editor. Answer with compact JSON only.", 2500 + n * 120) { StudioCompact.patch(draft, it, ws) }
                    }.getOrElse { e -> if (e is java.util.concurrent.CancellationException) throw e else draft }
                }
            }
            var res = StudioDirector.apply(d, ws, p0.ms, emptySet())
            // cartoon: the speakers of the words come back to the new scenes
            if (cast.isNotEmpty() && roles != null) {
                val (ss, rr) = StudioToon.restore(res.script.scenes, res.ranges, roles, cast, res.words)
                res = res.copy(script = res.script.copy(scenes = ss), ranges = rr)
            }
            res
        }, { e ->
            busy = ""
            render()
            if (e !is java.util.concurrent.CancellationException) err(e)
        }) { ap2 ->
            busy = ""
            if (ap2.script.scenes.isEmpty()) return@job
            // keep photos already found for the same scenes
            script = ap2.script
            ranges = ap2.ranges
            if (ap == StudioApproach.RHYTHM) StudioPrefs.pulse = true
            board = emptyList()
            saveAnalysis()
            render()
            if (ap.usesAi) lastTokens?.let { (i, o) -> toast(t("AI: $i → $o токенов", "AI: $i → $o tokens")) }
            if (StudioPrefs.broll && StudioDirector.needBroll(ap2.script).isNotEmpty()) fetchBroll() else autoFinish()
        }
    }

    /**
     * AI director: the numbered transcript goes to the AI, the plan comes back (scenes, visuals, highlights, zooms,
     * stickers, sounds, transitions, words to cut) and is applied to the take.
     */
    private fun direct() {
        val p0 = pcm ?: return
        val ws = words
        if (ws.isEmpty() || busy.isNotEmpty()) return
        val allowDrop = StudioPrefs.voiceMode != "edge" && StudioPrefs.dropFillers
        val oldCuts = cuts
        val format = formatText()
        val cast = if (task == StudioTask.CARTOON) StudioPrefs.cast else emptyList()
        val roles = script?.let { sc -> ranges?.let { StudioToon.wordRoles(sc.scenes, it, ws.size, cast) } }
        val cutsNow = if (task == StudioTask.CUTS) readyCuts() else emptyList()
        val vars = mapOf(
            "format" to format,
            "long" to if (task == StudioTask.VIDEO || p0.ms > 180_000) ", в длинном ролике до 12 секунд" else "",
            "visuals" to STUDIO_VISUALS + StudioCuts.promptBlock(cutsNow),
            "drop" to if (allowDrop) "drop — номера слов-паразитов («эм», «ну», «короче», «как бы», «вот»), повторов и оговорок, которые нужно вырезать из записи. " +
                "Не трогай значимые слова и не вырезай больше 10% текста. Если вырезать нечего — пустой массив."
            else "drop — всегда пустой массив.",
            "lang" to aiLang(),
            "data" to StudioDirector.transcript(ws)
        )
        busy = "ai_direct"
        cancel = false
        render()
        job("ai_direct", {
            val d = aiJson("studio_direct", vars, "You are a video editor and motion designer. Answer with JSON only.",
                if (ws.size > 600) 16_000 else 8000) { StudioDirector.parse(it, ws.size, cutsNow.map { c -> c.id }.toSet()) }
            val drop = if (allowDrop) StudioDirector.safeDrop(d, ws.size) else emptySet()
            var ap = StudioDirector.apply(d, ws, p0.ms, drop)
            // cartoon: the speakers of the words come back to the new scenes, split where the speaker changes
            if (cast.isNotEmpty() && roles != null) {
                val kept = roles.filterIndexed { k, _ -> k !in drop }
                val (ss, rr) = StudioToon.restore(ap.script.scenes, ap.ranges, kept, cast, ap.words)
                ap = ap.copy(script = ap.script.copy(scenes = ss), ranges = rr)
            }
            // the AI put no clip anywhere: spread them by words of their notes
            if (cutsNow.isNotEmpty() && ap.script.scenes.none { it.clip.isNotEmpty() }) {
                ap = ap.copy(script = withClips(ap.script, StudioCuts.autoAssign(ap.script.scenes, cutsNow)))
            }
            val np = ap.keep?.let { StudioAudio.Pcm(Studio.cutPcm(p0.data, p0.rate, it), p0.rate) } ?: p0
            val nc = ap.keep?.let { StudioDirector.composeCuts(oldCuts, it) } ?: oldCuts
            Triple(ap, np, nc)
        }, { e ->
            busy = ""
            render()
            if (e !is java.util.concurrent.CancellationException) err(e)
        }) { (ap, np, nc) ->
            busy = ""
            if (ap.script.scenes.isEmpty()) return@job
            pcm = np
            words = ap.words
            script = ap.script
            ranges = ap.ranges
            cuts = nc
            stats = stats?.let { it.copy(totalMs = np.ms, speechMs = minOf(it.speechMs, np.ms), words = ap.words.size) }
            board = emptyList()
            saveAnalysis()
            render()
            if (ap.dropped > 0) toast(t("AI-режиссёр вырезал слов: ${ap.dropped}", "The AI director cut ${ap.dropped} words"))
            else lastTokens?.let { (i, o) -> toast(t("AI: $i → $o токенов", "AI: $i → $o tokens")) }
            if (StudioPrefs.broll && StudioDirector.needBroll(ap.script).isNotEmpty()) fetchBroll() else autoFinish()
        }
    }

    /** Photos for the scenes whose B-roll has none yet; old photos no scene uses are removed. */
    private fun fetchBroll() {
        val sc0 = script ?: return
        val need = StudioDirector.needBroll(sc0)
        if (need.isEmpty() || busy.isNotEmpty()) return
        val asp = aspect()
        val bdir = File(tdir, "broll").apply { mkdirs() }
        val used = sc0.scenes.map { it.media }.toSet()
        bdir.listFiles()?.filter { it.absolutePath !in used }?.forEach { it.delete() }
        val maxSide = if (StudioPrefs.quality >= 1080) 1920 else 1280
        busy = "broll"
        cancel = false
        render()
        job("broll", {
            val found = java.util.concurrent.ConcurrentHashMap<Int, Pair<String, String>>()
            val ready = java.util.concurrent.atomic.AtomicInteger(0)
            report("broll", t("Фото 0/${need.size}", "Photos 0/${need.size}"))
            Par.mapAll(need, 3) { _, i ->
                if (cancel) throw java.util.concurrent.CancellationException()
                val f = File(bdir, "b_${System.currentTimeMillis()}_$i.jpg")
                val hit = try {
                    StudioBroll.fetch(sc0.scenes[i].broll, asp, true, f, maxSide) { cancel }
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w("studio", "broll: ${e.message}")
                    null
                }
                if (hit != null) found[i] = f.absolutePath to StudioBrollCatalog.credit(hit)
                report("broll", t("Фото ${ready.incrementAndGet()}/${need.size}", "Photos ${ready.get()}/${need.size}"))
                i
            }
            HashMap(found)
        }, { e ->
            busy = ""
            render()
            if (e !is java.util.concurrent.CancellationException) err(e)
        }) { found ->
            autoFinishLater = true
            busy = ""
            val cur = script
            if (cur != null && cur.scenes.size == sc0.scenes.size) {
                script = cur.copy(scenes = cur.scenes.mapIndexed { i, s -> found[i]?.let { (m, cr) -> s.copy(media = m, credit = cr) } ?: s })
                saveAnalysis()
            }
            board = emptyList()
            render()
            toast(t("Фото найдено: ${found.size} из ${need.size}", "Photos found: ${found.size} of ${need.size}"))
            if (autoFinishLater) {
                autoFinishLater = false
                autoFinish()
            }
        }
    }

    private var autoFinishLater = false

    /** End of the "AI makes everything" chain: the video is built right away. */
    private fun autoFinish() {
        if (!autoAll) return
        autoAll = false
        renderVideo()
    }

    // ---------- step 4: video ----------

    /** Starts the build in [StudioService]: it goes on with the screen closed or the phone locked. */
    private fun renderVideo() {
        val sc = script ?: return
        if (StudioPrefs.liveEmoji && busy.isEmpty()) {
            val need = StudioLiveEmoji.used(sc).filter { !StudioLottie.file(emojiDir, it).exists() && !StudioLottie.missing(emojiDir, it).exists() }.toSet()
            if (need.isNotEmpty()) {
                busy = "emoji"
                cancel = false
                render()
                job("emoji", {
                    StudioBroll.liveEmoji(need, emojiDir, { cancel }) { i, n -> report("emoji", t("Эмодзи $i/$n", "Emoji $i/$n")) }
                }, { e ->
                    busy = ""
                    render()
                    if (e !is java.util.concurrent.CancellationException) startRender()
                }) {
                    busy = ""
                    startRender()
                }
                return
            }
        }
        startRender()
    }

    private fun startRender() {
        val plan = currentPlan() ?: return
        val voice = pcm ?: return
        if (StudioJob.running) return
        val (wdt, hgt) = aspect().size(StudioPrefs.quality)
        val day = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val name = Studio.fileName(plan.script.title.ifBlank { topicEdit.text.toString() }, day)
        val out = ReportKit.shareFile(this, name)
        lastVideo = null
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1) }
        }
        StudioService.start(this, StudioJob.Request(
            plan, voice, out, name, wdt, hgt,
            if (plan.style == StudioStyle.VIDEO) bgSpec(plan).takeIf { !it.empty } else null,
            musicFile.takeIf { it.exists() }, StudioPrefs.musicVolume, StudioPrefs.musicDuck, StudioPrefs.sfx
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
