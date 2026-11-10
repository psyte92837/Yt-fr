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
    /** Language of the voice-over, subtitles and schemes ([Studio.VOICE_LANGS]); empty = by the script. */
    var voiceLang: String
        get() = p.getString("studio_voice_lang", "") ?: ""
        set(v) = p.edit().putString("studio_voice_lang", v).apply()
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
    /** Saved characters to reuse in other cartoons. */
    var library: List<SChar>
        get() = StudioToon.parseCast(p.getString("studio_cast_lib", null), StudioToon.MAX_LIB)
        set(v) = p.edit().putString("studio_cast_lib", StudioToon.castJson(v.take(StudioToon.MAX_LIB))).apply()
    /** Frame of the Cuts task: [StudioAspect.id]. */
    var cutsAspect: String
        get() = p.getString("studio_aspect_cuts", StudioAspect.LANDSCAPE.id) ?: StudioAspect.LANDSCAPE.id
        set(v) = p.edit().putString("studio_aspect_cuts", v).apply()
    /** Short side of the frame: 720 or 1080. */
    var quality: Int
        get() = p.getInt("studio_quality", if (height >= 1920) 1080 else 720)
        set(v) = p.edit().putInt("studio_quality", v).apply()
    /** Quality cap of downloaded YouTube clips (short side, px; 0 = as [quality]): Studio clips and Mix. */
    var clipQ: Int
        get() = p.getInt("studio_clip_q", 1080)
        set(v) = p.edit().putInt("studio_clip_q", v).apply()
    /** The cap clips are downloaded with now. */
    val clipCap: Int get() = StudioCuts.capOf(clipQ, quality)
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
        get() = p.getString("studio_approach", StudioApproach.HYBRID.id) ?: StudioApproach.HYBRID.id
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

    /** B-roll: real video clips from stock sources first, photos when no clip is found. */
    var brollVideo: Boolean
        get() = p.getBoolean("studio_broll_video", true)
        set(v) = p.edit().putBoolean("studio_broll_video", v).apply()
    /** The AI director may cut out filler words and slips of the take. */
    var dropFillers: Boolean
        get() = p.getBoolean("studio_drop_fillers", true)
        set(v) = p.edit().putBoolean("studio_drop_fillers", v).apply()
    var seconds: Int
        get() = p.getInt("studio_seconds", 60)
        set(v) = p.edit().putInt("studio_seconds", v).apply()
    /** "edge", "gemini", "azure", "mic", "file" or "video"; an animated voice-over uses "file" or "mic". */
    var voiceMode: String
        get() = p.getString(key("studio_voice_mode"), null) ?: if (StudioTask.of(task) == StudioTask.VOICE) "file" else "edge"
        set(v) = p.edit().putString(key("studio_voice_mode"), v).apply()
    var voice: String
        get() = p.getString("studio_voice", "") ?: ""
        set(v) = p.edit().putString("studio_voice", v).apply()
    var rate: Int
        get() = p.getInt("studio_rate", 10)
        set(v) = p.edit().putInt("studio_rate", v).apply()
    /** Tone of the synthesised voice-over ([VoiceTone]): Gemini reads it as an instruction, Edge as rate/pitch. */
    var tone: String
        get() = p.getString("studio_voice_tone", VoiceTone.DEFAULT) ?: VoiceTone.DEFAULT
        set(v) = p.edit().putString("studio_voice_tone", v).apply()
    /** Gemini TTS: voice ([GeminiVoice.VOICES]), model and the author's reading wish. */
    /** Azure Speech: region of the resource, voice (ShortName) and emotion strength 0.5–2. */
    var azureRegion: String
        get() = p.getString("studio_azure_region", "westeurope") ?: "westeurope"
        set(v) = p.edit().putString("studio_azure_region", v).apply()
    var azureVoice: String
        get() = p.getString("studio_azure_voice", "") ?: ""
        set(v) = p.edit().putString("studio_azure_voice", v).apply()
    var azureDegree: Double
        get() = p.getFloat("studio_azure_degree", 1.3f).toDouble()
        set(v) = p.edit().putFloat("studio_azure_degree", v.toFloat()).apply()
    var geminiVoice: String
        get() = p.getString("studio_gemini_voice", GeminiVoice.DEFAULT_VOICE) ?: GeminiVoice.DEFAULT_VOICE
        set(v) = p.edit().putString("studio_gemini_voice", v).apply()
    var geminiModel: String
        get() = GeminiVoice.model(p.getString("studio_gemini_model", "") ?: "")
        set(v) = p.edit().putString("studio_gemini_model", v).apply()
    /** Emotions through the text: [VoiceTone.MIX_ONE], [VoiceTone.MIX_AUTO] or tone ids separated by commas. */
    var geminiMix: String
        get() = p.getString("studio_gemini_mix", VoiceTone.MIX_AUTO) ?: VoiceTone.MIX_AUTO
        set(v) = p.edit().putString("studio_gemini_mix", v).apply()
    var geminiWish: String
        get() = p.getString("studio_gemini_wish", "") ?: ""
        set(v) = p.edit().putString("studio_gemini_wish", v).apply()
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
    /**
     * What goes over the picture in every task: subtitles or info-schemes and their options ([StudioOverlay],
     * lumen.overlay v1). The first read takes the settings of the old screens (what to overlay, style, size).
     */
    var over: StudioOverlay
        get() = StudioOverlay.parse(p.getString("studio_over", null)) ?: StudioOverlay.migrate(
            p.getString("studio_layers", StudioLayers.ALL.id) ?: StudioLayers.ALL.id, p.getBoolean("studio_overlay", true),
            p.getInt("studio_over_size", 80), p.getString("studio_scheme_style", SchemeStyles.AUTO) ?: SchemeStyles.AUTO,
            p.getString("studio_scheme_place", "style") ?: "style", p.getString("studio_toon_schemes", "board") ?: "board")
        set(v) = p.edit().putString("studio_over", v.toJson().toString()).apply()
    /** Wish of the author for the AI schemes (optional, in «⚙ Опции схем»). */
    var schemeWish: String
        get() = p.getString("studio_scheme_wish", "") ?: ""
        set(v) = p.edit().putString("studio_scheme_wish", v).apply()
    /** Key of the script the AI was last asked to compose schemes for: it is not asked again by itself. */
    var schemesTried: String
        get() = p.getString(key("studio_schemes_tried"), "") ?: ""
        set(v) = p.edit().putString(key("studio_schemes_tried"), v).apply()
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
    /** Background clips cut into pieces of this length that fill the voice-over (0 = whole clips in a loop). */
    var bgPiece: Long
        get() = p.getLong("studio_bg_piece", 0)
        set(v) = p.edit().putLong("studio_bg_piece", v).apply()
    /** Cuts task: a scene longer than its clip goes on with other clips (false = the clip loops). */
    var cutsFill: Boolean
        get() = p.getBoolean("studio_cuts_fill", true)
        set(v) = p.edit().putBoolean("studio_cuts_fill", v).apply()
    /** Gemini/Azure voice: an AI that hears audio times the phrases for the subtitles (false = by the pauses only). */
    var aiTiming: Boolean
        get() = p.getBoolean("studio_ai_timing", true)
        set(v) = p.edit().putBoolean("studio_ai_timing", v).apply()
    /** Cuts task: the videos are cut at the storyboard, by the scenes of the voice-over (false = the marked pieces beforehand). */
    var cutsLate: Boolean
        get() = p.getBoolean("studio_cuts_late", true)
        set(v) = p.edit().putBoolean("studio_cuts_late", v).apply()
    /** Length of a piece cut at the storyboard, ms (0 = one piece for the whole scene). */
    var cutPiece: Long
        get() = p.getLong("studio_cut_piece", 0)
        set(v) = p.edit().putLong("studio_cut_piece", v).apply()
    /** The take follows the script (teleprompter); otherwise it is recognised. */
    var readScript: Boolean
        get() = p.getBoolean("studio_read_script", true)
        set(v) = p.edit().putBoolean("studio_read_script", v).apply()
    /** Speed of the word highlight of the teleprompter, words per minute. */
    var prompterWpm: Int
        get() = p.getInt("studio_prompter_wpm", StudioPrompter.DEFAULT_WPM)
        set(v) = p.edit().putInt("studio_prompter_wpm", v).apply()

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
        private const val REQ_PODCAST = 7409
        private const val REQ_MIC = 7402
        private const val REQ_VIDEO = 7403
        private const val REQ_CAPTURE = 7404
        private const val REQ_MUSIC = 7405
        private const val REQ_BG = 7406
        private const val REQ_CHAR_IMG = 7407

        /** Index of the Mix in [tabs]: the Mix is its own screen, the other items are the tasks of this one. */
        const val MIX_TAB = 5
        /** Lumen Studio only (no YouTube clips or Mix there): the editor and the AI layer take their places. */
        const val EDITOR_TAB = 6
        const val LAYER_TAB = 7
        /** "📋 Tasks": the author's task board (its own screen) — in both apps. */
        const val TASKS_TAB = 8
        /** "🎧 Audio editor": its own screen — in both apps. */
        const val AUDIO_TAB = 9

        /** Items of the panel by place: Lumen — the five tasks and the Mix; Lumen Studio — without Clips and Mix. */
        private val tabIds get() = if (Flavor.STUDIO) listOf(0, 1, 2, 4, EDITOR_TAB, LAYER_TAB, TASKS_TAB, AUDIO_TAB) else (0..5).toList() + TASKS_TAB + AUDIO_TAB

        /**
         * The Studio panel: the five tasks and the Mix, in two rows of three so the names fit on a phone
         * (the same panel on the Mix screen, with the Mix selected).
         */
        fun tabs(ctx: Context, selected: Int, onSelect: (Int) -> Unit): View {
            val all = listOf("📱 Shorts", t("🎬 Видео", "🎬 Video"), t("🎙 Озвучка", "🎙 Voice-over"),
                t("🎞 Клипы", "🎞 Clips"), t("🎨 Анимация", "🎨 Animation"), t("🔀 Микс", "🔀 Mix"), t("✏️ Редактор", "✏️ Editor"), t("🎛 Слой ИИ", "🎛 AI layer"),
                t("📋 Задачи", "📋 Tasks"), t("🎧 Аудиоредактор", "🎧 Audio editor"))
            val ids = tabIds
            val labels = ids.map { all[it] }
            val at = ids.indexOf(selected)
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                // rows of three; the rest (Tasks, Audio editor) share the last row
                for (r in 0..(labels.size - 1) / 3) {
                    val from = r * 3
                    val to = minOf(from + 3, labels.size)
                    addView(segBar(ctx, labels.subList(from, to), if (at in from until to) at - from else -1) { onSelect(ids[from + it]) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (r > 0) topMargin = ctx.dp(6) })
                }
            }
        }

        /** Opens panel item [i] from a screen of its own (the Mix, Tasks, the audio editor). */
        fun openTab(ctx: Context, i: Int) {
            when (i) {
                MIX_TAB -> StudioMixActivity.open(ctx)
                EDITOR_TAB -> StudioEditorActivity.open(ctx)
                LAYER_TAB -> WebLayerActivity.open(ctx, null, false)
                TASKS_TAB -> BoardActivity.open(ctx)
                AUDIO_TAB -> AudioEditorActivity.open(ctx)
                else -> openTask(ctx, StudioTask.entries[i])
            }
        }

        /** Back from the Mix to a task of the Studio: the open Studio screen switches to it (or a new one opens). */
        fun openTask(ctx: Context, task: StudioTask) {
            ctx.startActivity(Intent(ctx, StudioActivity::class.java).putExtra("switch", task.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }

        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, StudioActivity::class.java).putExtra("fresh", true)
                .addFlags(if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Opens [task] with a ready script (e.g. from the language game); the task's old work goes to the drafts. */
        fun openWith(ctx: Context, task: String, script: String, topic: String) {
            ctx.startActivity(Intent(ctx, StudioActivity::class.java).putExtra("task", task).putExtra("script", script).putExtra("topic", topic)
                .addFlags(if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private val dir by lazy { File(filesDir, "lumen/studio").apply { mkdirs() } }
    private val task get() = StudioTask.of(StudioPrefs.task).let { if (Flavor.STUDIO && it == StudioTask.CUTS) StudioTask.SHORTS else it }
    /** Take, video and analysis of the current task (Shorts keeps the old place); music and clips are shared. */
    private val tdir get() = (if (task == StudioTask.SHORTS) dir else File(dir, task.id)).apply { mkdirs() }
    private val takeFile get() = File(tdir, "take.wav")
    private val prompterFile get() = File(tdir, "prompter.json")
    private val sourceFile get() = File(tdir, "source.mp4")
    private val musicFile get() = File(dir, "music.wav")
    /** Cuts task: marked YouTube pieces and their cut files. */
    private val cutsFile get() = File(tdir, "clips.json")
    private val cutsDir get() = File(tdir, "clips").apply { mkdirs() }
    private var cutClips: List<SClip> = emptyList()
    /** Pieces cut at the storyboard (StudioPrefs.cutsLate): clips/parts.json, files in clips/parts/. */
    private var cutParts: List<SClip> = emptyList()
    private val partsDir get() = File(cutsDir, "parts").apply { mkdirs() }
    private val partsFile get() = File(cutsDir, "parts.json")
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
    /** Word highlight of the teleprompter while recording, with the log of what lit up when. */
    private var prompterClock: PrompterClock? = null
    private var prompterText: TextView? = null
    private var prompterSpeed: TextView? = null
    private var prompterRanges: List<IntRange> = emptyList()
    private var prompterBody = ""
    /** Time of the take now (from the recorder), for taps on the prompter. */
    private var recMs = 0L
    private var preview: ImageView? = null
    private var previewMs = 0L

    /** A closed Studio screen is still working in the background: this one only shows it and saves nothing. */
    private var foreign = false
    private var reloading = false
    private val watcher: () -> Unit = { onForeign() }

    /** Opened from the menu: every task starts clean on its first visit, the old work goes to the drafts. */
    private var fresh = false
    /** Tasks already opened on this screen: their work is the current one. */
    private val seen = HashSet<String>()
    private val draftsDir get() = File(dir, "drafts")

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("switch")?.let { if (!foreign) switchTask(StudioTask.of(it)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        foreign = BgTasks.has("studio:") && BgTasks.find("studio:")?.key != "studio:render"
        BgTasks.watchers += watcher
        fresh = savedInstanceState?.getBoolean("fresh") ?: intent.getBooleanExtra("fresh", false)
        savedInstanceState?.getStringArrayList("seen")?.let { seen.addAll(it) }
        // the notification reopens this screen with its intent: it must show the work, not start a new one
        intent.removeExtra("fresh")
        if (savedInstanceState == null) intent.getStringExtra("switch")?.let { id ->
            if (!foreign && !StudioJob.running && !BgTasks.has("studio:")) StudioPrefs.task = StudioTask.of(id).id
        }
        intent.removeExtra("switch")
        val given = if (savedInstanceState == null) intent.getStringExtra("script") else null
        intent.removeExtra("script")
        if (given != null) {
            if (foreign || StudioJob.running || BgTasks.has("studio:")) toast(t("Студия занята: сценарий не подставлен", "Studio is busy: the script was not set"))
            else {
                StudioPrefs.task = intent.getStringExtra("task") ?: StudioPrefs.task
                seen.add(task.id)
                stashCurrent()
                StudioPrefs.script = given
                StudioPrefs.topic = intent.getStringExtra("topic").orEmpty()
            }
        }
        val stashed = savedInstanceState == null && given == null && visit()
        scriptEdit = edt(this, t("Сценарий: сцены через пустую строку", "Script: scenes separated by a blank line"), StudioPrefs.script, multiline = true).apply {
            minLines = 8
            maxLines = 18
        }
        topicEdit = edt(this, t("Тема или идея ролика", "Topic or idea of the video"), StudioPrefs.topic)
        wasRunning = StudioJob.running
        if (!stashed) StudioJob.result?.takeIf { it.exists() && !StudioJob.running }?.let {
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("fresh", fresh)
        outState.putStringArrayList("seen", ArrayList(seen))
    }

    // ---------- drafts ----------

    /** First visit of the current task on a fresh screen: its old work goes to the drafts. True when something was moved. */
    private fun visit(): Boolean {
        val first = seen.add(task.id)
        if (!fresh || !first || foreign || StudioJob.running || BgTasks.has("studio:")) return false
        return stashCurrent()
    }

    private fun stashCurrent(): Boolean {
        val d = runCatching { StudioDrafts.stash(draftsDir, task.id, tdir, StudioPrefs.script, StudioPrefs.topic, System.currentTimeMillis()) }
            .onFailure { L.w("studio", "stash: ${it.message}") }.getOrNull() ?: return false
        StudioPrefs.script = ""
        StudioPrefs.topic = ""
        L.i("studio", "draft ${d.id}: ${d.title}")
        return true
    }

    private fun canLeave(): Boolean {
        if (busy.isNotEmpty() || recorder != null || StudioJob.running || BgTasks.has("studio:")) {
            toast(t("Дождитесь окончания текущего шага", "Wait for the current step to finish"))
            return false
        }
        return true
    }

    /** The screen shows the task's work as it is on disk now. */
    private fun reload() {
        player?.release()
        player = null
        prevScript = null
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

    private fun newWork() {
        if (!canLeave()) return
        saveInputs()
        val moved = stashCurrent()
        reload()
        toast(if (moved) t("Новая работа. Прежняя — в «Черновиках»", "New work. The previous one is in Drafts") else t("Новая работа", "New work"))
    }

    private fun draftsSheet() {
        val list = StudioDrafts.list(draftsDir, task.id)
        bottomSheet(t("Черновики", "Drafts")) { dismiss ->
            if (list.isEmpty()) muted(t("Пока пусто. Когда вы начинаете новую работу, прежняя сохраняется сюда.",
                "Empty for now. When you start a new work, the previous one is kept here."))
            for (d in list) card {
                add(text(ctx, d.title.ifBlank { t("Без названия", "Untitled") }, Type.TITLE_M, bold = true), 0)
                muted(Fmt.dateTime(d.at) + (if (d.script.isNotBlank()) t(" · символов: ", " · characters: ") + d.script.length else "") +
                    (if (d.bytes > 0) " · " + StudioDrafts.mb(d.bytes) else ""))
                row(
                    btn(ctx, t("Открыть", "Open"), style = BtnStyle.TONAL) { dismiss(); openDraft(d) },
                    btn(ctx, t("Удалить", "Delete"), style = BtnStyle.TEXT) {
                        dismiss()
                        undoable(t("Черновик удалён", "Draft deleted"), apply = {
                            if (!StudioDrafts.hide(draftsDir, d)) StudioDrafts.delete(draftsDir, d)
                            render()
                        }, revert = {
                            StudioDrafts.unhide(draftsDir, d)
                            render()
                        }, commit = { StudioDrafts.purge(draftsDir, d) })
                    }
                )
            }
            if (list.size > 1) button(t("Удалить все", "Delete all"), style = BtnStyle.TEXT) {
                confirm(t("Удалить все черновики (${list.size})?", "Delete all drafts (${list.size})?"), "", t("Удалить", "Delete")) {
                    StudioDrafts.deleteAll(draftsDir, task.id)
                    dismiss()
                    render()
                }
            }
        }
    }

    /** Opens a draft; the current work (if any) becomes a draft itself. */
    private fun openDraft(d: SDraft) {
        if (!canLeave()) return
        saveInputs()
        stashCurrent()
        runCatching { StudioDrafts.restore(draftsDir, d, tdir) }.onFailure { err(it); return }
        StudioPrefs.script = d.script
        StudioPrefs.topic = d.topic
        reload()
        toast(t("Открыт черновик", "Draft opened"))
    }

    override fun onPause() {
        saveInputs()
        super.onPause()
    }

    /** Saved templates (screen "📚 Templates"), reloaded whenever the Studio comes back. */
    private var motionTpls: Map<String, MotionTpl> = emptyMap()
    private var editTpls: List<EditTpl> = emptyList()

    private fun loadTemplates() {
        val st = StudioTemplatesActivity.store(this)
        motionTpls = runCatching { st.motionMap() }.getOrDefault(emptyMap())
        editTpls = runCatching { st.edits() }.getOrDefault(emptyList())
    }

    /** The visuals for the prompts, with the saved motion templates. */
    private fun visuals() = STUDIO_VISUALS + StudioTplCatalog.visuals(motionTpls.values.toList())

    override fun onStart() {
        super.onStart()
        val before = motionTpls.keys to editTpls.map { it.id to it.json }
        loadTemplates()
        if (before != (motionTpls.keys to editTpls.map { it.id to it.json })) {
            board = emptyList()
            if (busy.isEmpty() && recorder == null) render()
        }
        StudioJob.listener = jobListener
        if (wasRunning && !StudioJob.running) onJob()
    }

    /** Kept as one object: the Mix screen sets its own listener and must not lose it when this screen stops. */
    private val jobListener: () -> Unit = { onJob() }

    override fun onStop() {
        if (StudioJob.listener === jobListener) StudioJob.listener = null
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

    /** Lumen Studio: this screen is the home, settings are reached from its top bar. */
    private fun appActions(): List<Pair<String, () -> Unit>> =
        if (Flavor.STUDIO) listOf("⚙" to { startActivity(Intent(this, SettingsActivity::class.java)) }) else emptyList()

    private fun lang(): String = Studio.voiceLang(StudioPrefs.voiceLang, EdgeTts.detectLang(scriptEdit.text.toString()), Settings.lang)

    // ---------- layout ----------

    private fun render() {
        saveInputs()
        bars.clear()
        val c = Col(this)
        c.add(tabs(this, task.ordinal) { i ->
            when (i) {
                MIX_TAB -> StudioMixActivity.open(this)
                EDITOR_TAB -> StudioEditorActivity.open(this)
                LAYER_TAB -> WebLayerActivity.open(this, null, false)
                TASKS_TAB -> BoardActivity.open(this)
                AUDIO_TAB -> AudioEditorActivity.open(this)
                else -> switchTask(StudioTask.entries[i])
            }
        }, 0)
        c.muted(when (task) {
            StudioTask.SHORTS -> t(
                "Короткие вертикальные ролики 9:16: слова появляются под голос, рядом схемы из эмодзи. Четыре шага, всё собирается на телефоне.",
                "Short vertical 9:16 videos: words appear with the voice, next to emoji schemes. Four steps, everything is built on the phone.")
            StudioTask.VIDEO -> t(
                "Горизонтальный ролик для YouTube 16:9 на 2–10 минут: ИИ пишет сценарий с главами, голос озвучивает, ИИ-режиссёр ставит графику, зумы, плашки и звуки.",
                "A horizontal 16:9 YouTube video of 2–10 minutes: AI writes a script with chapters, a voice reads it, the AI director adds graphics, zooms, stickers and sounds.")
            StudioTask.VOICE -> t(
                "Готовая озвучка → анимированный ролик. Загрузите аудио или запишите голос: ИИ-режиссёр сам разметит сцены, графику, зумы, плашки и звуки и вырежет оговорки. Кадр 9:16, 16:9 или 1:1.",
                "A ready voice-over → an animated video. Upload audio or record your voice: the AI director lays out scenes, graphics, zooms, stickers and sounds and cuts slips. Frame 9:16, 16:9 or 1:1.")
            StudioTask.CUTS -> t(
                "Клипы под ваш текст: отметьте куски видео с YouTube (ссылка и время), Lumen скачает только эти секунды, ИИ-режиссёр поставит клипы на нужные слова, между ними — графика. Кадр 16:9, 9:16 или 1:1. Ритмичная нарезка без текста — «🔀 Микс» в панели сверху.",
                "Clips for your text: mark pieces of YouTube videos (link and time), Lumen downloads only those seconds, the AI director puts the clips on the right words with graphics in between. Frame 16:9, 9:16 or 1:1. A rhythmic cut without a text is the «🔀 Mix» in the panel above.")
            StudioTask.CARTOON -> t(
                "Познавательная анимация: персонажи (лиса, робот, сова…) разговаривают разными голосами и объясняют тему, рядом появляются схемы и цифры. Рот двигается под слоги, герои моргают, жестикулируют и показывают эмоции. Кадр 16:9, 9:16 или 1:1.",
                "An informational animation: characters (fox, robot, owl…) talk in different voices and explain the topic while schemes and numbers appear next to them. Mouths follow the syllables, characters blink, gesture and show emotions. Frame 16:9, 9:16 or 1:1.")
        })
        if (!foreign) {
            val n = StudioDrafts.count(draftsDir, task.id)
            c.row(
                btn(this, t("＋ Новая", "＋ New"), style = BtnStyle.TONAL) { newWork() },
                btn(this, t("🗂 Черновики", "🗂 Drafts") + if (n > 0) " ($n)" else "", style = BtnStyle.TONAL) { draftsSheet() }
            )
        }
        if (foreign) {
            c.card {
                add(text(ctx, t("⏳ Задача выполняется в фоне", "⏳ A task is running in the background"), Type.TITLE_M, bold = true), 0)
                muted(t("Её начал прежний экран Студии. Экран обновится сам, когда она закончится; прогресс — и в уведомлении.",
                    "It was started by the previous Studio screen. This screen refreshes when it ends; progress is also in the notification."))
                bars["foreign"] = working(BgTasks.find("studio:")?.let { it.title + ": " + it.message } ?: "…")
            }
            otherTools(c)
            page("Lumen Studio", c.root, back = !Flavor.STUDIO, actions = appActions(), keepScroll = true)
            return
        }
        val order = cards()
        c.steps(stepNow(order.size), order.size)
        order.forEachIndexed { i, card -> card(c, i + 1) }
        otherTools(c)
        page("Lumen Studio", c.root, back = !Flavor.STUDIO, actions = appActions(), keepScroll = true)
    }

    /**
     * Cards of the task in the order of the work: what the script refers to (characters, clips) and what changes
     * it (frame, language) come before it; the voice after the text it reads.
     */
    private fun cards(): List<(Col, Int) -> Unit> = when (task) {
        StudioTask.VOICE -> listOf({ c, n -> voiceCard(c, n) }, { c, n -> scriptCard(c, n) }, { c, n -> analyseCard(c, n) }, { c, n -> videoCard(c, n) })
        StudioTask.CUTS -> listOf({ c, n -> scriptCard(c, n) }, { c, n -> cutsCard(c, n) }, { c, n -> voiceCard(c, n) },
            { c, n -> analyseCard(c, n) }, { c, n -> videoCard(c, n) })
        StudioTask.CARTOON -> listOf({ c, n -> castCard(c, n) }, { c, n -> scriptCard(c, n) }, { c, n -> voiceCard(c, n) },
            { c, n -> analyseCard(c, n) }, { c, n -> videoCard(c, n) })
        else -> listOf({ c, n -> scriptCard(c, n) }, { c, n -> voiceCard(c, n) }, { c, n -> analyseCard(c, n) }, { c, n -> videoCard(c, n) })
    }

    /** The Mix and the Editor are their own screens: entry chips with "↗" at the bottom, after the steps (the AI layer opens from a finished video). */
    private fun otherTools(c: Col) {
        // the Mix, the Montage (hand editor + JS layers by the protocol) and the Editor are their own screens:
        // entry buttons with "↗", not tabs, so it is clear they open another screen (Back returns here)
        c.add(text(this, t("Другие инструменты", "Other tools"), Type.LABEL, bold = true, color = Palette.MUTED), 20)
        c.add(chipScroller(this, listOf(
            chip(this, t("✏️ Редактор ↗", "✏️ Editor ↗")) { StudioEditorActivity.open(this) }.apply { contentDescription = t("Открыть редактор", "Open the editor") }
        )), 4)
    }

    /** Long work of the screen: runs through [BgTasks] with a notification, so it survives minimising or closing. */
    private fun <T> job(key: String, work: () -> T, fail: (Throwable) -> Unit, done: (T) -> Unit) {
        BgTasks.run(this, "studio:$key", "Lumen Studio: " + jobTitle(key), BgTasks.reopen(this), { work() }, { e ->
            if (e !is java.util.concurrent.CancellationException) {
                autoAll = false
                podcastAuto = false
            }
            runCatching { fail(e) }.onFailure { L.w("studio", "$key: ${it.message}") }
        }, { cancel = true }) { r ->
            runCatching { done(r) }.onFailure { L.w("studio", "$key: ${it.message}") }
        }
    }

    private fun jobTitle(key: String) = when (key) {
        "ai_script" -> t("ИИ пишет сценарий", "AI writes the script")
        "ai_edit" -> t("ИИ правит сценарий", "AI edits the script")
        "analyse" -> t("озвучка и разметка", "voice and timing")
        "ai_direct" -> t("ИИ-режиссёр", "AI director")
        "broll" -> t("поиск B-roll", "finding B-roll")
        "schemes" -> t("ИИ составляет инфоблоки", "AI composes info-blocks")
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

    private fun stepNow(total: Int): Int = when {
        lastVideo != null || words.isNotEmpty() -> total - 1
        pcm != null || takeFile.exists() -> total - 2
        else -> 0
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
            muted(t("Необязательно. Вставьте текст, который звучит в записи, — слова встанут по времени точнее. Без текста запись распознаётся (Android 13+). Сцены, графику и эффекты расставит ИИ-режиссёр.",
                "Optional. Paste the text spoken in the recording for more exact word timing. Without it the take is recognised (Android 13+). The AI director lays out scenes, graphics and effects."))
            add(detach(scriptEdit), 8)
            return@card
        }
        add(text(ctx, "$n. " + t("Сценарий", "Script"), Type.TITLE_M, bold = true), 0)
        add(detach(topicEdit), 8)
        if (task == StudioTask.CUTS || task == StudioTask.CARTOON) aspectPicker(this)
        if (longScript()) {
            spinner(t("Длительность", "Length"), listOf(120 to "2 мин", 180 to "3 мин", 300 to "5 мин", 480 to "8 мин", 600 to "10 мин"), StudioPrefs.videoSeconds) {
                StudioPrefs.videoSeconds = it
                render()
            }
        } else spinner(t("Длительность", "Length"), listOf(30 to "30 с", 60 to "60 с", 90 to "90 с", 120 to "2 мин"), StudioPrefs.seconds) {
            StudioPrefs.seconds = it
            render()
        }
        langPicker(this)
        button(t("✨ Написать (ИИ)", "✨ Write (AI)"), style = BtnStyle.TONAL) { writeScript() }
        if (task == StudioTask.CARTOON && busy.isEmpty()) {
            button(t("🤖 ИИ сделает всё: идея → готовый мультфильм", "🤖 AI makes everything: idea → finished cartoon"), style = BtnStyle.TONAL) { autoDialog() }
            muted(t("Необязательно: опишите идею, сцены или сценарий — ИИ напишет реплики, озвучит героев, смонтирует и соберёт видео. Работает и в свёрнутом приложении.",
                "Optional: describe an idea, scenes or a script; AI writes the lines, voices the characters, edits and builds the video. Works with the app minimised."))
        }
        progress("ai_script", t("ИИ пишет сценарий…", "AI is writing the script…"))
        add(detach(scriptEdit), 8)
        val sc = Studio.parseText(scriptEdit.text.toString())
        if (sc.scenes.isNotEmpty()) {
            val n = sc.scenes.sumOf { Studio.tokens(it.say).size }
            muted(t("Сцен: ${sc.scenes.size} · слов: $n · примерно ${Studio.clock(n * 1000L * 10 / 24)}",
                "Scenes: ${sc.scenes.size} · words: $n · about ${Studio.clock(n * 1000L * 10 / 24)}"))
        }
        if (scriptEdit.text.isNotBlank() && busy.isEmpty()) {
            val undo = prevScript
            if (undo != null) row(
                btn(ctx, t("🪄 ИИ-помощник", "🪄 AI assistant"), style = BtnStyle.TONAL) { assist() },
                btn(ctx, t("↩ Вернуть прежний", "↩ Undo"), style = BtnStyle.TEXT) {
                    scriptEdit.setText(undo)
                    prevScript = null
                    resetAnalysis()
                    render()
                }
            ) else button(t("🪄 ИИ-помощник: улучшить сценарий", "🪄 AI assistant: improve the script"), style = BtnStyle.TONAL) { assist() }
        }
        progress("ai_edit", t("ИИ правит сценарий…", "AI is editing the script…"))
        button(t("ⓘ Как писать сценарий", "ⓘ How to write the script"), style = BtnStyle.TEXT) { formatHelp() }
    }

    private fun voiceCard(c: Col, n: Int) = c.card {
        add(text(ctx, "$n. " + if (task == StudioTask.VOICE) t("Озвучка", "Voice-over") else t("Голос", "Voice"), Type.TITLE_M, bold = true), 0)
        val modes = when (task) {
            StudioTask.VOICE -> listOf("file", "mic")
            StudioTask.CUTS, StudioTask.CARTOON -> listOf("edge", "gemini", "azure", "mic", "file")
            else -> listOf("edge", "gemini", "azure", "mic", "file", "video")
        }
        if (task == StudioTask.VOICE) {
            aspectPicker(this)
            langPicker(this)
            button(t("🎙 Подкаст → ролик сам", "🎙 Podcast → video, hands-free"), primary = true) {
                if (busy.isNotEmpty() || StudioJob.running) {
                    toast(t("Дождитесь окончания текущей задачи", "Wait for the current task to end"))
                    return@button
                }
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*")), REQ_PODCAST)
            }
            muted(t("Выберите запись диктофона или подкаст — дальше всё само: громкость, распознавание, ИИ-режиссёр (вырежет оговорки и паузы), инфосхемы, B-roll, стиль «Объяснение» с главами по вопросам, звуки и сборка видео. Прежняя работа уйдёт в «Черновики».",
                "Pick a voice memo or a podcast and the rest happens by itself: loudness, recognition, the AI director (cuts slips and pauses), info-schemes, B-roll, the «Explainer» style with chapters from the questions, sounds and the video build. The previous work goes to Drafts."))
        }
        val labels = if (task == StudioTask.VOICE) listOf(t("📎 Аудиофайл", "📎 Audio file"), t("🎙 Запись", "🎙 Record"))
        else listOf(t("🗣 Синтез", "🗣 Synth"), "✨ Gemini", "☁ Azure", t("🎙 Запись", "🎙 Record"), t("📎 Файл", "📎 File"), t("🎥 Видео", "🎥 Video")).take(modes.size)
        val sel = modes.indexOf(StudioPrefs.voiceMode).coerceAtLeast(0)
        // rows of three: six ways do not fit one row on a phone
        for (from in labels.indices step 3) {
            val to = minOf(from + 3, labels.size)
            add(segBar(ctx, labels.subList(from, to), if (sel in from until to) sel - from else -1) {
                StudioPrefs.voiceMode = modes[from + it]
                resetAnalysis()
                render()
            }, if (from == 0) 8 else 4)
        }
        when (StudioPrefs.voiceMode) {
            "edge" -> edgeVoice(this)
            "gemini" -> geminiVoice(this)
            "azure" -> azureVoice(this)
            "mic" -> micVoice(this)
            "video" -> videoVoice(this)
            else -> fileVoice(this)
        }
    }

    /** Frame of the video: chosen first, the script length, the clips and the layout depend on it. */
    private fun aspectPicker(c: Col) = with(c) {
        add(text(ctx, t("Кадр", "Frame"), Type.LABEL, bold = true, color = Palette.MUTED), 8)
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

    /** Language of the voice-over; subtitles and schemes follow it (AI prompts get its name). */
    private fun langPicker(c: Col) {
        val auto = t("Авто — по сценарию", "Auto — by the script")
        val opts = listOf("" to auto) + Studio.VOICE_LANGS.map { it to Studio.langName(it) }
        c.spinner(t("Язык озвучки, субтитров и схем", "Language of the voice-over, subtitles and schemes"), opts, StudioPrefs.voiceLang) { code ->
            if (code == StudioPrefs.voiceLang) return@spinner
            StudioPrefs.voiceLang = code
            resetAnalysis()
            render()
            val text = scriptEdit.text.toString()
            if (code.isEmpty() || text.isBlank() || foreign) return@spinner
            val name = Studio.langName(code)
            confirm(t("Перевести сценарий?", "Translate the script?"),
                t("Озвучка, субтитры и схемы будут на языке «$name». Перевести сценарий на этот язык с помощью ИИ? Прежний текст можно вернуть отменой правки.",
                    "The voice-over, subtitles and schemes will be in $name. Translate the script into it with AI? The previous text can be restored by undoing the edit."),
                t("Перевести", "Translate")) {
                runAssist("- Переведи весь сценарий на язык: $name — текст озвучки (say), заголовки и подписи плиток (title, items). " +
                    "Сохрани смысл, порядок и число сцен, вид сцен, эмодзи, числа и имена персонажей; пиши естественно для носителя языка.")
            }
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
        tonePicker(c)
    }

    /** The voice is synthesised (Edge or Gemini): the script is the speech, no pauses or slips to cut. */
    private fun synth() = StudioPrefs.voiceMode in listOf("edge", "gemini", "azure")

    private fun geminiVoice(c: Col) {
        c.muted(t("Живые голоса Google Gemini, 30 штук, говорят на любом языке. Нужен ключ Gemini (Настройки → ИИ), текст уходит в Google. Один запрос — до ~1,5 минуты речи; на бесплатном ключе запросов в день немного.",
            "Lifelike Google Gemini voices, 30 of them, speak any language. Needs a Gemini key (Settings → AI); the text goes to Google. One request is up to ~1.5 minutes of speech; a free key allows few requests a day."))
        if (Llm.geminiKey() == null) {
            c.add(text(this, t("⚠ Ключ Gemini не задан", "⚠ No Gemini key"), Type.LABEL, bold = true, color = 0xFFFF8A3D.toInt()), 8)
            c.button(t("🔑 Ввести ключ", "🔑 Enter the key")) { startActivity(Intent(this, SettingsActivity::class.java)) }
        }
        if (task == StudioTask.CARTOON) c.muted(t("Персонажи получат свои голоса Gemini того же пола; здесь — голос рассказчика.",
            "Characters get their own Gemini voices of the same gender; this is the narrator's voice."))
        val cur = GeminiVoice.voice(StudioPrefs.geminiVoice)
        for ((female, label) in listOf(true to t("Женские", "Female"), false to t("Мужские", "Male"))) {
            c.add(text(this, label, Type.LABEL, bold = true, color = Palette.MUTED), 12)
            c.chips(GeminiVoice.VOICES.filter { it.female == female }.map { v ->
                chip(this, (if (female) "♀ " else "♂ ") + v.name + " · " + t(v.ru, v.en), v.name == cur.name) {
                    StudioPrefs.geminiVoice = v.name
                    resetAnalysis()
                    render()
                }
            }, collapseAfter = 16)
        }
        c.button(t("▶ Послушать «${cur.name}»", "▶ Listen to \"${cur.name}\"")) { tryGemini(cur.name) }
        c.spinner(t("Модель", "Model"), GeminiVoice.MODELS.map { it.first to t(it.second, it.third) }, StudioPrefs.geminiModel) {
            StudioPrefs.geminiModel = it
            resetAnalysis()
            render()
        }
        c.spinner(t("Темп речи", "Speech rate"), listOf(-10 to t("спокойно", "calm"), 0 to t("обычно", "normal"), 10 to t("бодро", "brisk"), 20 to t("быстро", "fast")), StudioPrefs.rate) {
            StudioPrefs.rate = it
            resetAnalysis()
            render()
        }
        tonePicker(c)
        emotionMix(c)
        timingCheck(c)
        c.add(text(this, t("Как читать (по желанию)", "How to read (optional)"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        val wish = c.edit(t("например: в начале интригуй, на цифрах замедляйся, в конце — энергично", "e.g. build intrigue at the start, slow down on numbers, energetic at the end"), StudioPrefs.geminiWish, multiline = true)
        wish.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                StudioPrefs.geminiWish = s?.toString().orEmpty()
            }
        })
    }

    /** The Gemini voice-over prompt for the current settings (template from Settings → AI → Prompts, key studio_tts_gemini). */
    private fun geminiDirection(): String = VoiceTone.direction(Prompts.get("studio_tts_gemini"), StudioPrefs.tone, StudioPrefs.rate,
        StudioPrefs.geminiWish, StudioPrefs.geminiMix, Studio.langName(lang()))

    /** How emotions change: one tone, by the meaning of the paragraphs, or the author's own mix of tones. */
    private fun emotionMix(c: Col) {
        val mix = StudioPrefs.geminiMix
        val own = mix != VoiceTone.MIX_ONE && mix != VoiceTone.MIX_AUTO
        c.add(text(this, t("Эмоции по ходу текста", "Emotions through the text"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        c.add(segBar(this, listOf(t("Один тон", "One tone"), t("По смыслу", "By meaning"), t("Свой набор", "Own mix")),
            if (own) 2 else if (mix == VoiceTone.MIX_ONE) 0 else 1) {
            StudioPrefs.geminiMix = when (it) {
                0 -> VoiceTone.MIX_ONE
                1 -> VoiceTone.MIX_AUTO
                else -> "humor,dramatic,motivational"
            }
            resetAnalysis()
            render()
        }, 4)
        if (own) {
            val set = mix.split(',').map { it.trim() }.toMutableSet()
            c.muted(t("Отметьте, какие эмоции смешивать: Gemini возьмёт каждую там, где она подходит по смыслу абзаца. Основа — «Тон озвучки».",
                "Tick the emotions to mix: Gemini uses each where it fits the paragraph. The base is the tone above."))
            c.chips(VoiceTone.ALL.filter { it.id != VoiceTone.of(StudioPrefs.tone).id }.map { tn ->
                chip(this, tn.emoji + " " + t(tn.ru, tn.en), tn.id in set) {
                    if (!set.remove(tn.id)) set += tn.id
                    StudioPrefs.geminiMix = set.filter { id -> VoiceTone.ALL.any { it.id == id } }.joinToString(",").ifEmpty { VoiceTone.MIX_ONE }
                    resetAnalysis()
                    render()
                }
            }, collapseAfter = 8)
        }
        c.button(t("📝 Промпт озвучки", "📝 Voice-over prompt"), style = BtnStyle.TONAL) { geminiPromptSheet() }
    }

    /** The voice-over prompt like the script prompts: edit the template, see the filled one, reset. */
    private fun geminiPromptSheet() {
        bottomSheet(t("📝 Промпт озвучки Gemini", "📝 Gemini voice-over prompt"), {}) { dismiss ->
            muted(t("Это указания диктору перед текстом: как быстро, каким тоном, какие эмоции и где. Переменные: {lang} язык, {tone} тон, {pace} темп, {emotions} эмоции, {wish} ваше пожелание. Текст сцен добавляется после строки «ТЕКСТ:». Тот же шаблон — в Настройки → ИИ → Промпты (studio_tts_gemini).",
                "Directions to the narrator before the text: how fast, in what tone, which emotions and where. Variables: {lang}, {tone}, {pace}, {emotions}, {wish}. The scenes' text follows the line «ТЕКСТ:». The same template is in Settings → AI → Prompts (studio_tts_gemini)."))
            val ed = edit(t("Шаблон промпта", "Prompt template"), Prompts.get("studio_tts_gemini"), multiline = true)
            add(text(ctx, t("Сейчас уйдёт в Gemini:", "Sent to Gemini now:"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            muted(geminiDirection())
            row(
                btn(ctx, t("Сбросить", "Reset"), style = BtnStyle.TEXT) {
                    Prompts.set("studio_tts_gemini", "")
                    resetAnalysis()
                    dismiss()
                    render()
                },
                btn(ctx, t("Сохранить", "Save"), style = BtnStyle.FILLED) {
                    val v = ed.text.toString()
                    if (!v.contains("ТЕКСТ") && !v.contains("TEXT", true)) toast(t("Совет: закончите шаблон строкой «ТЕКСТ:», чтобы Gemini не читал указания вслух", "Tip: end the template with «ТЕКСТ:» so Gemini does not read the notes aloud"))
                    Prompts.set("studio_tts_gemini", v)
                    resetAnalysis()
                    dismiss()
                    render()
                }
            )
        }
    }

    /** Tone chips for both synthesised voices: Gemini follows it closely, Edge only by rate and pitch. */
    /** Subtitles by the pace the AI hears in the voice-over, or by the pauses only. */
    private fun timingCheck(c: Col) {
        c.check(t("🎯 Субтитры по темпу озвучки: ИИ слушает запись и отмечает фразы (Gemini, 1 запрос на кусок)",
            "🎯 Subtitles by the voice pace: the AI listens and marks the phrases (Gemini, 1 request a chunk)"), StudioPrefs.aiTiming) {
            StudioPrefs.aiTiming = it
            resetAnalysis()
            render()
        }
    }

    private fun tonePicker(c: Col) {
        c.add(text(this, t("Тон озвучки", "Tone of the voice-over"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        c.chips(VoiceTone.ALL.map { tn ->
            chip(this, tn.emoji + " " + t(tn.ru, tn.en), tn.id == VoiceTone.of(StudioPrefs.tone).id) {
                StudioPrefs.tone = tn.id
                resetAnalysis()
                render()
            }
        }, collapseAfter = 8)
        if (StudioPrefs.voiceMode == "edge" && StudioPrefs.tone != VoiceTone.DEFAULT) c.muted(t("Голоса Microsoft передают тон только темпом и высотой. Живые эмоции и юмор лучше у «✨ Gemini».",
            "Microsoft voices show the tone only by pace and pitch. Real emotion and humour sound better with \"✨ Gemini\"."))
    }

    @Volatile private var azureVoices: List<AzureSpeech.Voice> = emptyList()
    private val azureFile get() = File(filesDir, "lumen/azure_voices.json")

    private fun azureList(): List<AzureSpeech.Voice> {
        if (azureVoices.isEmpty()) azureVoices = runCatching { AzureSpeech.parseVoices(azureFile.readText()) }.getOrDefault(emptyList())
        return azureVoices
    }

    /** Voices of the region (also checks the key); kept on the phone. */
    private fun fetchAzureVoices(region: String): List<AzureSpeech.Voice> {
        val raw = Llm.azureVoices(region)
        val list = AzureSpeech.parseVoices(raw)
        if (list.isNotEmpty()) runCatching { azureFile.parentFile?.mkdirs(); azureFile.writeText(raw) }
        azureVoices = list
        return list
    }

    private fun azureVoice(c: Col) {
        c.muted(t("Голоса Microsoft Azure с эмоциями: весёлый, грустный, шёпот, новости, документальный и другие. Бесплатно 0,5 млн символов в месяц (около 9 часов речи). Нужен свой ключ Azure Speech (уровень Free F0); текст уходит в Microsoft.",
            "Microsoft Azure voices with emotions: cheerful, sad, whisper, newscast, documentary and more. Free: 0.5 million characters a month (about 9 hours of speech). Needs your own Azure Speech key (Free F0 tier); the text goes to Microsoft."))
        val hasKey = Llm.azureKey() != null
        c.button(if (hasKey) t("🔑 Ключ и регион Azure", "🔑 Azure key and region") else t("🔑 Ввести ключ Azure", "🔑 Enter an Azure key"), primary = !hasKey) { azureKeySheet() }
        if (!hasKey) return
        val list = azureList()
        if (list.isEmpty()) {
            c.button(t("⬇ Загрузить голоса", "⬇ Load the voices"), primary = true) { loadAzureVoices() }
            return
        }
        val lang = lang()
        val ofLang = AzureSpeech.forLang(list, lang)
        val cur = ofLang.firstOrNull { it.shortName == StudioPrefs.azureVoice } ?: ofLang.firstOrNull()
        fun voiceChips(vs: List<AzureSpeech.Voice>, after: Int) = c.chips(vs.map { v ->
            chip(this, (if (v.female) "♀ " else "♂ ") + v.name + (if (v.styles.isNotEmpty()) " 🎭${v.styles.size}" else "") +
                (if (v.locale.substringBefore('-').equals(lang, true)) "" else " · " + v.locale), v.shortName == cur?.shortName) {
                StudioPrefs.azureVoice = v.shortName
                resetAnalysis()
                render()
            }
        }, collapseAfter = after)
        val native = ofLang.filter { it.locale.substringBefore('-').equals(lang, true) }
        if (native.isNotEmpty()) {
            c.add(text(this, if (task == StudioTask.CARTOON) t("Голос рассказчика", "Narrator voice") else t("Голос", "Voice"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            voiceChips(native, 12)
        }
        val multi = ofLang - native.toSet()
        if (multi.isNotEmpty()) {
            c.add(text(this, t("Многоязычные с эмоциями 🎭 (говорят и на этом языке, с лёгким акцентом)", "Multilingual with emotions 🎭 (also speak this language, with a slight accent)"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            voiceChips(multi, 8)
        }
        tonePicker(c)
        timingCheck(c)
        if (cur != null) {
            val ru = !t("ru", "en").startsWith("en")
            val st = AzureSpeech.styleFor(StudioPrefs.tone, cur.styles)
            c.muted(when {
                st != null -> t("Тон передаётся стилем Azure «${AzureSpeech.styleLabel(st, ru)}».", "The tone is the Azure style \"$st\".")
                cur.styles.isEmpty() -> t("У этого голоса нет эмоций — тон передаётся только темпом и высотой. Эмоции есть у голосов с 🎭.",
                    "This voice has no emotions: the tone is only pace and pitch. Voices with 🎭 have emotions.")
                else -> t("Для этого тона у голоса нет стиля — только темп и высота. Его эмоции: ", "The voice has no style for this tone, only pace and pitch. Its emotions: ") +
                    cur.styles.joinToString(", ") { AzureSpeech.styleLabel(it, ru) }
            })
            if (st != null) c.spinner(t("Сила эмоции", "Emotion strength"), listOf(0.6 to t("слабо", "light"), 1.0 to t("обычно", "normal"), 1.3 to t("ярко", "vivid"), 2.0 to t("максимум", "maximum")),
                listOf(0.6, 1.0, 1.3, 2.0).minByOrNull { kotlin.math.abs(it - StudioPrefs.azureDegree) } ?: 1.3) {
                StudioPrefs.azureDegree = it
                resetAnalysis()
                render()
            }
            c.button(t("▶ Послушать «${cur.name}»", "▶ Listen to \"${cur.name}\"")) { tryAzure(cur) }
        }
        c.spinner(t("Темп речи", "Speech rate"), listOf(-10 to t("спокойно", "calm"), 0 to t("обычно", "normal"), 10 to t("бодро", "brisk"), 20 to t("быстро", "fast")), StudioPrefs.rate) {
            StudioPrefs.rate = it
            resetAnalysis()
            render()
        }
    }

    private fun loadAzureVoices() {
        val region = StudioPrefs.azureRegion
        toast(t("Загружаю голоса Azure…", "Loading Azure voices…"))
        Lumen.async({ fetchAzureVoices(region) }, ::err) { list ->
            toast(t("Голосов Azure: ${list.size}", "Azure voices: ${list.size}"))
            render()
        }
    }

    private fun azureKeySheet() {
        bottomSheet(t("☁ Azure Speech", "☁ Azure Speech"), {}) { dismiss ->
            muted(t("Как получить бесплатный ключ: portal.azure.com → «Создать ресурс» → «Speech» → ценовая категория Free F0 → после создания «Ключи и конечная точка»: скопируйте «Ключ 1» и запомните «Расположение/Регион». Ключ хранится только на телефоне.",
                "How to get a free key: portal.azure.com → Create a resource → Speech → pricing tier Free F0 → then Keys and Endpoint: copy KEY 1 and note the Location/Region. The key stays on the phone."))
            button(t("Открыть портал Azure", "Open the Azure portal"), style = BtnStyle.TEXT) { openUrl(AzureSpeech.KEY_URL) }
            val key = edit(if (Llm.azureKey() != null) t("Ключ сохранён (вставьте новый, чтобы заменить)", "Key saved (paste a new one to replace)") else t("Ключ Azure Speech", "Azure Speech key"))
            var region = StudioPrefs.azureRegion
            spinner(t("Регион ресурса", "Resource region"), AzureSpeech.REGIONS.map { it to it }, region) { region = it }
            button(t("Сохранить и проверить", "Save and check"), primary = true) {
                val k = key.text.toString().trim()
                if (k.length >= 20) KeyVault.put(AzureSpeech.KEY, k)
                if (Llm.azureKey() == null) {
                    toast(t("Вставьте ключ", "Paste the key"))
                    return@button
                }
                StudioPrefs.azureRegion = region
                azureVoices = emptyList()
                runCatching { azureFile.delete() }
                dismiss()
                loadAzureVoices()
            }
        }
    }

    /** A short sample of an Azure voice in the current tone (kept on disk: the free tier counts characters). */
    private fun tryAzure(v: AzureSpeech.Voice) {
        val tone = StudioPrefs.tone
        val degree = StudioPrefs.azureDegree
        val f = File(File(cacheDir, "azure_try").apply { mkdirs() }, "${v.shortName}_${tone}_${degree}_${StudioPrefs.rate}.wav")
        fun play() {
            musicPreview?.let { runCatching { it.stop() }; it.release() }
            musicPreview = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ -> toast(t("Не удалось проиграть", "Cannot play")); true }
                prepareAsync()
            }
        }
        if (f.length() > 44) return play()
        val lang = lang()
        val sample = if (lang == "ru") "Привет! Я ${v.name}. Так будет звучать ваш ролик." else "Hi! I'm ${v.name}. This is how your video will sound."
        val style = AzureSpeech.styleFor(tone, v.styles)
        val (r, p) = AzureSpeech.prosody(tone, StudioPrefs.rate, style != null)
        val region = StudioPrefs.azureRegion
        Lumen.async({ f.writeBytes(Llm.azureSpeech(AzureSpeech.ssml(sample, v, lang, style, degree, r, p), region, v.shortName, sample.length, "studio_tts_azure_try")) }, ::err) { play() }
    }

    /** A short sample of a Gemini voice (kept on disk: every sample is a request of the daily limit). */
    private fun tryGemini(name: String) {
        val model = StudioPrefs.geminiModel
        val f = File(File(cacheDir, "gemini_try").apply { mkdirs() }, "${name}_${model}_${geminiDirection().hashCode()}.wav")
        fun play() {
            musicPreview?.let { runCatching { it.stop() }; it.release() }
            musicPreview = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, _, _ -> toast(t("Не удалось проиграть", "Cannot play")); true }
                prepareAsync()
            }
        }
        if (f.length() > 44) return play()
        toast(t("Gemini озвучивает пример…", "Gemini is voicing a sample…"))
        val sample = if (lang() == "ru") "Привет! Я $name. Так будет звучать ваш ролик." else "Hi! I'm $name. This is how your video will sound."
        Lumen.async({
            val (pcm, rate) = GeminiVoice.audio(Llm.speech(sample, name, model, geminiDirection(), "studio_tts_gemini_try"))
            writeWav(f, pcm, rate)
        }, ::err) { play() }
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
        c.check(t("Суфлёр: показывать сценарий при записи", "Teleprompter: show the script while recording"), StudioPrefs.prompter) {
            StudioPrefs.prompter = it
            render()
        }
        if (StudioPrefs.prompter) {
            c.spinner(t("Скорость подсветки слов, слов в минуту", "Word highlight speed, words per minute"),
                StudioPrompter.SPEEDS.map { it to it.toString() }.let { l -> if (StudioPrefs.prompterWpm in StudioPrompter.SPEEDS) l else l + (StudioPrefs.prompterWpm to StudioPrefs.prompterWpm.toString()) },
                StudioPrefs.prompterWpm) { StudioPrefs.prompterWpm = it }
            c.muted(t("Суфлёр подсвечивает слово за словом; скорость меняется и во время записи, ⏸ — пауза, тап по слову — «я здесь». " +
                "Что и когда подсветилось, запоминается: по этому слова встанут в ролике, если вы читаете сценарий.",
                "The teleprompter lights up word by word; change the speed while recording too, ⏸ pauses, tap a word to say \"I am here\". " +
                    "What lit up when is remembered and times the words in the video when you read the script."))
        }
        if (task != StudioTask.VOICE) c.check(readScriptLabel(), StudioPrefs.readScript) {
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
        if (task != StudioTask.VOICE) c.check(readScriptLabel(), StudioPrefs.readScript) {
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
        c.check(readScriptLabel(), StudioPrefs.readScript) {
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

    /** One wording for every way of the voice: with the script read aloud the words are timed by it, else recognised. */
    private fun readScriptLabel() = t("В записи звучит мой сценарий (слова встанут точнее; иначе запись распознаётся)",
        "The take speaks my script (more exact word timing; otherwise the take is recognised)")

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
                // the take is moved aside first: "Undo" in the snackbar brings it back
                val files = listOfNotNull(takeFile, sourceFile.takeIf { StudioPrefs.voiceMode == "video" }).filter { it.exists() }
                val aside = files.map { java.io.File(it.path + ".undo") }
                undoable(t("Запись удалена", "Take deleted"), apply = {
                    files.zip(aside).forEach { (f, b) -> b.delete(); if (!f.renameTo(b)) f.delete() }
                    resetAnalysis()
                    render()
                }, revert = {
                    files.zip(aside).forEach { (f, b) -> if (b.exists()) b.renameTo(f) }
                    render()
                }, commit = { aside.forEach { it.delete() } })
            }
        )
    }

    /** Big script text that scrolls with the expected pace of reading. */
    private fun prompterScenes(): List<List<String>> = Studio.parseText(scriptEdit.text.toString()).scenes.map { Studio.tokens(it.say) }

    private fun prompter(c: Col) {
        val (body, ranges) = StudioPrompter.layout(prompterScenes())
        prompterBody = body
        prompterRanges = ranges
        val clock = prompterClock
        if (clock != null) {
            prompterSpeed = text(this, "", 14f, bold = true).apply { gravity = android.view.Gravity.CENTER }
            val pause = btn(this, "⏸", style = BtnStyle.TONAL) {}
            pause.setOnClickListener {
                clock.togglePause(recMs)
                pause.text = if (clock.paused) "▶" else "⏸"
                paintPrompter()
            }
            c.row(
                btn(this, "−", style = BtnStyle.TONAL) { changeWpm(-StudioPrompter.STEP_WPM) },
                prompterSpeed!!,
                btn(this, "＋", style = BtnStyle.TONAL) { changeWpm(StudioPrompter.STEP_WPM) },
                pause
            )
        }
        val tv = text(this, body, 24f, bold = true).apply {
            setPadding(dp(16), dp(80), dp(16), dp(200))
        }
        // tap on a word: the reader is there now
        tv.setOnTouchListener { v, e ->
            if (e.action == android.view.MotionEvent.ACTION_UP && clock != null) {
                val off = (v as TextView).getOffsetForPosition(e.x, e.y)
                val i = StudioPrompter.wordAt(prompterRanges, off)
                if (i >= 0 && clock.jump(i, recMs)) paintPrompter()
                v.performClick()
            }
            true
        }
        prompterText = tv
        val sv = ScrollView(this).apply {
            addView(tv)
            background = roundBg(Palette.SURFACE, 12f, this@StudioActivity)
        }
        prompterScroll = sv
        c.add(sv, 12).layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280))
        paintPrompter()
    }

    private fun changeWpm(d: Int) {
        val clock = prompterClock ?: return
        clock.tick(recMs)
        clock.wpm += d
        StudioPrefs.prompterWpm = clock.wpm
        paintPrompter()
    }

    /** Read words dimmed, the lit one on the accent, the line of the lit word kept in the upper third. */
    private fun paintPrompter() {
        val clock = prompterClock
        prompterSpeed?.text = clock?.let { t("${it.wpm} сл/мин", "${it.wpm} wpm") + if (it.paused) t(" · пауза", " · paused") else "" }.orEmpty()
        val tv = prompterText ?: return
        val i = clock?.index ?: -1
        val r = prompterRanges.getOrNull(i)
        if (r == null) {
            tv.text = prompterBody
            return
        }
        val sp = android.text.SpannableString(prompterBody)
        if (r.first > 0) sp.setSpan(android.text.style.ForegroundColorSpan(Palette.MUTED), 0, r.first, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(android.text.style.BackgroundColorSpan(Palette.ACCENT), r.first, r.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(android.text.style.ForegroundColorSpan(Palette.ON_ACCENT), r.first, r.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = sp
        val sv = prompterScroll ?: return
        tv.post {
            val lay = tv.layout ?: return@post
            val y = lay.getLineTop(lay.getLineForOffset(r.first)) + tv.paddingTop
            sv.smoothScrollTo(0, (y - sv.height / 3).coerceAtLeast(0))
        }
    }

    private fun analyseCard(c: Col, n: Int) = c.card {
        add(text(ctx, "$n. " + t("Сцены и режиссура", "Scenes and direction"), Type.TITLE_M, bold = true), 0)
        muted(when (StudioPrefs.voiceMode) {
            "edge", "gemini", "azure" -> t("Озвучка сцен и разметка слов по времени.", "Voicing the scenes and timing every word.")
            else -> t("Поиск речи и пауз, темп, расстановка слов по времени.", "Finding speech and pauses, the pace, timing every word.")
        })
        if (busy.isEmpty()) button(if (words.isEmpty()) t("🔍 Разметить сцены", "🔍 Lay out scenes") else t("🔍 Разметить заново", "🔍 Lay out again"), primary = words.isEmpty()) { analyse() }
        progress("analyse", t("Подготовка…", "Preparing…"))
        progress("ai_scenes", t("ИИ делит текст на сцены…", "AI is splitting the text into scenes…"))
        val s = stats
        val sc = script
        if (s != null && sc != null && words.isNotEmpty()) {
            p(t("Длительность ${Studio.clock(s.totalMs)} · речь ${Studio.clock(s.speechMs)} · слов ${s.words} · темп ${s.wordsPerMinute} сл/мин",
                "Length ${Studio.clock(s.totalMs)} · speech ${Studio.clock(s.speechMs)} · words ${s.words} · pace ${s.wordsPerMinute} wpm"))
            if (!synth()) {
                muted(t("Пауз: ${s.pauses} · самая длинная ${s.longestPauseMs / 100 / 10.0} с", "Pauses: ${s.pauses} · longest ${s.longestPauseMs / 100 / 10.0} s"))
                paceTip(s)?.let { muted("💡 $it") }
            }
            add(text(ctx, if (task == StudioTask.VOICE) t("Как анимировать", "How to animate") else t("Режиссура", "Direction"),
                Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val cur = StudioApproach.of(StudioPrefs.approach)
            val ownWay = editTpls.firstOrNull { "edit:" + it.id == StudioPrefs.approach }
            muted(if (ownWay != null) "🧪 " + ownWay.name + t(" — свой подход, без ИИ и токенов. ", " — own way, no AI or tokens. ") + ownWay.use else approachHelp(cur))
            progress("ai_direct", t("Режиссёр размечает ролик…", "The director is planning the video…"))
            if (busy.isEmpty()) {
                fun b(ap: StudioApproach, label: String) = btn(ctx, (if (ap == cur) "● " else "") + label,
                    style = if (ap == cur) BtnStyle.TONAL else BtnStyle.TEXT) { animate(ap) }
                row(b(StudioApproach.RULES, t("⚡ Правила", "⚡ Rules")), b(StudioApproach.RHYTHM, t("🥁 Ритм", "🥁 Rhythm")))
                row(b(StudioApproach.LITE, t("💸 Эконом ИИ", "💸 Lite AI")), b(StudioApproach.HYBRID, t("🧩 Гибрид", "🧩 Hybrid")))
                add(b(StudioApproach.FULL, t("🎬 Полный ИИ-режиссёр", "🎬 Full AI director")), 4)
                // own ways of editing (lumen.edit), made by the AI on the templates screen: no tokens
                editTpls.chunked(2).forEach { pair ->
                    row(*pair.map { e ->
                        val on = StudioPrefs.approach == "edit:" + e.id
                        btn(ctx, (if (on) "● " else "") + "🧪 " + e.name, style = if (on) BtnStyle.TONAL else BtnStyle.TEXT) { animateEdit(e) }
                    }.toTypedArray())
                }
                button(t("📚 Шаблоны: своя графика и способы режиссуры (ИИ)", "📚 Templates: own graphics and ways of direction (AI)"), style = BtnStyle.TEXT) {
                    StudioTemplatesActivity.open(this@StudioActivity)
                }
                if (transcript.isNotBlank()) button(t("Текст расшифровки", "Transcript"), style = BtnStyle.TEXT) { showText(t("Расшифровка", "Transcript"), transcript) }
            }
            check(t("Запускать выбранный способ сразу после разбора", "Run the chosen way right after the analysis"), StudioPrefs.autoDirect) { StudioPrefs.autoDirect = it }
            // no API needed: every AI step of the Studio is copied to Gemini/ChatGPT and the answer pasted back (RelayActivity)
            check(t("🌐 Через другой ИИ вручную: запрос копируется в Gemini/ChatGPT, ответ вставляется обратно",
                "🌐 Through another AI by hand: the request is copied to Gemini/ChatGPT, the answer pasted back"), Relay.mode != "off") {
                Relay.mode = if (!it) "off" else if (Relay.mode == "all") "all" else "studio"
            }
            if (cur == StudioApproach.FULL) {
                if (!synth()) check(t("✂ Вырезать слова-паразиты и оговорки", "✂ Cut filler words and slips"), StudioPrefs.dropFillers) { StudioPrefs.dropFillers = it }
                check(t("🖼 Подбирать B-roll (свободные лицензии)", "🖼 Find B-roll (open licences)"), StudioPrefs.broll) { StudioPrefs.broll = it }
                if (StudioPrefs.broll) {
                    check(t("🎞 Сначала реальные видео-клипы (Pexels, Pixabay, Wikimedia), иначе фото", "🎞 Real video clips first (Pexels, Pixabay, Wikimedia), else photos"),
                        StudioPrefs.brollVideo) { StudioPrefs.brollVideo = it; render() }
                    if (StudioPrefs.brollVideo) button(t("🔑 Ключи Pexels и Pixabay", "🔑 Pexels and Pixabay keys") +
                        " (" + StudioBroll.stockSources().size + "/3)", style = BtnStyle.TEXT) { stockKeys() }
                }
            }
            progress("broll", t("Поиск B-roll…", "Finding B-roll…"))
            val (zooms, pops, sounds) = StudioDirector.summary(sc)
            val figures = sc.scenes.sumOf { it.shapes.size }
            val asked = sc.scenes.count { it.broll.isNotBlank() }
            val got = sc.scenes.count { it.broll.isNotBlank() && it.media.isNotBlank() }
            if (zooms + pops + sounds + figures + asked > 0) muted("🎬 " + t(
                "зумов $zooms · плашек $pops · звуков $sounds · фигур $figures" + (if (asked > 0) " · фото $got из $asked" else ""),
                "zooms $zooms · stickers $pops · sounds $sounds · shapes $figures" + (if (asked > 0) " · photos $got of $asked" else "")) +
                sc.scenes.count { StudioStock.isVideo(it.media) }.let { n -> if (n > 0) t(" (клипов $n)", " ($n clips)") else "" })
            if (busy.isEmpty() && got < asked) button(t("🖼 Подобрать B-roll", "🖼 Find B-roll"), style = BtnStyle.TEXT) { fetchBroll() }
            progress("schemes", t("ИИ составляет инфоблоки…", "AI composes info-blocks…"))
            val plan = currentPlan() ?: return@card
            add(text(ctx, t("Сцены", "Scenes"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val pickable = task == StudioTask.CUTS && placeable().isNotEmpty()
            if (pickable) {
                muted(t("Нажмите на сцену, чтобы выбрать ей клип.", "Tap a scene to pick its clip."))
                if (busy.isEmpty()) button(t("🎞 Расставить клипы по сценам без ИИ", "🎞 Place the clips on scenes without AI"), style = BtnStyle.TEXT) { autoPlace() }
                if (StudioPrefs.cutsLate) cutBlock(this, plan)
            }
            val ready = placeIds()
            plan.times.take(60).forEach { st ->
                val scn = sc.scenes[st.scene]
                val fx = scn.cues.joinToString("") { when (it.type) { "zoom" -> "🔍"; "pop" -> "💬"; else -> "🔊" } } +
                    "✏️".repeat(scn.shapes.size) + (if (StudioStock.isVideo(scn.media)) "🎞" else if (scn.media.isNotBlank()) "🖼" else if (scn.broll.isNotBlank()) "▫️" else "")
                val what = if (scn.clip in ready) "🎞 ${scn.clip}" else kindLabel(scn.kind)
                val line = "${Studio.clock(st.startMs)} · $what" + (if (fx.isNotEmpty()) " $fx" else "") + " · " +
                    scn.say.take(70) + if (scn.say.length > 70) "…" else ""
                if (pickable && StudioPrefs.cutsLate && busy.isEmpty()) {
                    // every piece: 🔄 another piece of this video, another clip or a new video
                    add(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        addView(text(ctx, line, 13f, color = if (scn.clip in ready) Palette.ON_SURFACE else Palette.MUTED).apply {
                            setPadding(0, dp(4), 0, dp(4))
                            setOnClickListener { pieceMenu(st.scene) }
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(btn(ctx, "🔄", style = BtnStyle.TEXT) { pieceMenu(st.scene) }.apply {
                            contentDescription = t("Другой кусок для сцены ${st.scene + 1}", "Another piece for scene ${st.scene + 1}")
                        })
                    }, 2)
                } else if (pickable) {
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
        "scheme" -> t("🧩 инфоблок", "🧩 info-block")
        else -> t("текст", "text")
    }

    private fun videoCard(c: Col, n: Int) = c.card {
        add(text(ctx, "$n. " + t("Ролик", "Video"), Type.TITLE_M, bold = true), 0)
        val styles = listOfNotNull(StudioStyle.DARK, StudioStyle.LIGHT, StudioStyle.EXPLAIN, if (montageReady()) StudioStyle.VIDEO else null)
        val labels = listOf(t("Тёмный", "Dark"), t("Светлый", "Light"), t("Объяснение", "Explainer"),
            if (videoReady()) t("Моё видео", "My video") else t("Клипы на фоне", "Clips behind")).take(styles.size)
        if (cutMontage()) muted(t("Клипы на сценах: клипы идут на своих сценах, между ними — графика, внизу слово под голос. Звук клипов выключен.",
            "Cut montage: clips play on their scenes, graphics in between, the spoken word below. Clip sound is off."))
        else add(segBar(ctx, labels, styles.indexOf(currentStyle()).coerceAtLeast(0)) {
            StudioPrefs.style = styles[it].name
            render()
        }, 8)
        if (!cutMontage()) muted(when (currentStyle()) {
            StudioStyle.DARK -> t("Слова появляются группами с размытием, схемы из эмодзи на тёмном фоне.", "Word groups blur in, emoji schemes on a dark screen.")
            StudioStyle.LIGHT -> t("Заголовок капсом и одно крупное слово под голос на светлом фоне.", "A capital headline and one big word at a time on a light screen.")
            StudioStyle.EXPLAIN -> t("Объяснение: графитовый фон, схемы на карточках, вопросы голоса — главы с номерами и прогрессом сверху, субтитры по 1–3 слова, звуки на склейках. B-roll — размытым фоном.",
                "Modern explainer: graphite screen, schemes on cards, the voice's questions become numbered chapters with progress on top, 1–3 word captions, sounds on cuts. B-roll as a blurred background.")
            StudioStyle.VIDEO -> if (videoReady()) t("Ваше видео, слово под голос внизу, графика вставками.", "Your video, the spoken word below, graphics cut in.")
            else t("Фоновые клипы по кругу под озвучку, слово внизу, графика вставками.", "Background clips loop under the voice-over, the word below, graphics cut in.")
        })
        overlayBlock(this)
        if (currentStyle() == StudioStyle.VIDEO) {
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
            muted(t("Сначала разметьте сцены (шаг ${n - 1}).", "Lay out the scenes first (step ${n - 1})."))
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
            val made = lastVideo?.exists() == true
            if (made) button(t("🎬 Сохранить заново", "🎬 Save again"), style = BtnStyle.TONAL) { renderVideo() }
            else button(t("🎬 Сохранить видео", "🎬 Save the video"), primary = true) { renderVideo() }
        }
        lastVideo?.takeIf { it.exists() }?.let { f ->
            p("✅ " + t("Готово: ", "Done: ") + savedAs.ifBlank { f.name })
            // the result first: open or share; then finishing it in the Editor; one text of the authors for the description
            row(
                btn(ctx, t("▶ Открыть", "▶ Open"), primary = true) { openFile(f) },
                btn(ctx, t("↗ Поделиться", "↗ Share"), style = BtnStyle.TONAL) { ReportKit.share(this@StudioActivity, f, false, f.name, "video/mp4") }
            )
            button(t("✏️ Доработать в Редакторе: инфоблоки, картинки, надписи", "✏️ Finish in the Editor: info-blocks, pictures, captions"), style = BtnStyle.TONAL) {
                StudioEditorActivity.open(this@StudioActivity, f, plan.words)
            }
            val authors = StudioDirector.authors(if (musicFile.exists()) StudioPrefs.musicCredit else "", script?.let { StudioDirector.credits(it) }.orEmpty())
            if (authors.isNotEmpty()) button(t("⧉ Текст для описания: авторы музыки и кадров", "⧉ Text for the description: authors of the music and shots"),
                style = BtnStyle.TEXT) { copyText(authors) }
            button(t("🎛 Анимированный слой ИИ поверх ролика", "🎛 An animated AI layer over the video"), style = BtnStyle.TEXT) { openLayer(f, plan.layers.subs) }
        }
    }

    /** The second renderer: the finished video with its speech and scenes goes to the Montage tab (lumen.canvas). */
    private fun openLayer(f: File, subs: Boolean) {
        val sc = script
        val rr = ranges
        val ws = words
        val scenes = if (sc != null && rr != null) sc.scenes.zip(rr).mapNotNull { (s, r) ->
            ws.getOrNull(r.first)?.let { a -> Triple(s.title, a.startMs, ws.getOrNull(r.last)?.endMs ?: a.endMs) }
        } else emptyList()
        val json = WebLayer.dataJson(ws.map { Triple(it.text, it.startMs, it.endMs) }, scenes, sc?.title.orEmpty(), aiLang())
        File(WebLayerActivity.dir(this), "data.json").writeText(org.json.JSONObject(json).put("video", f.absolutePath).toString())
        WebLayerActivity.open(this, f, subs)
    }

    /** Storyboard: a small frame of every scene; a tap shows that scene in the preview. */
    private fun storyboard(c: Col, plan: StudioPlan) {
        val key = "${plan.style}:${plan.accent}:${plan.overlay}:${plan.layers}:${plan.overScale}:${plan.blockBg}:${plan.fullMode}:${plan.schemeStyle?.id}:${plan.schemePlace}:${plan.toonSchemes}:${plan.zooms}:${plan.times.size}:${plan.totalMs}:${clips().size}:${aspect().id}:" +
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
                chip(ctx, if (!I18n.ru) q else label) {
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
    private fun cutMontage() = task == StudioTask.CUTS && (readyCuts().isNotEmpty() || StudioPrefs.cutsLate && readyParts().isNotEmpty())

    /**
     * What goes under a VIDEO-style video: the speaker's own video (with cuts), the background clips,
     * or (Cuts task) the YouTube clips on the scenes of [plan].
     */
    private fun bgSpec(plan: StudioPlan): StudioBg {
        if (task == StudioTask.CUTS) {
            val ready = readyCuts()
            if (StudioPrefs.cutsLate) {
                // pieces cut by the scenes; a scene without its pieces falls back to a downloaded marked clip
                val sceneClips = StudioCuts.effectiveClips(plan.script.scenes, placeIds(), all = plan.fullMode != StudioOverlay.FULL)
                val parts = readyParts()
                val covered = coveredScenes(sceneClips)
                val rest = sceneClips.mapIndexed { i, id -> if (i in covered) "" else id }
                val old = if (ready.isEmpty()) emptyList() else StudioCuts.fillSlots(plan.times, rest, ready.associateBy { it.id })
                val slots = (StudioCuts.pieceSlots(plan.times, sceneClips, parts) + old).sortedBy { it.startMs }
                return StudioBg(null, null, emptyList(), slots,
                    ready.associate { it.id to File(cutsDir, it.file) } + parts.associate { it.id to File(partsDir, it.file) })
            }
            val sceneClips = StudioCuts.effectiveClips(plan.script.scenes, ready.map { it.id }.toSet(), all = plan.fullMode != StudioOverlay.FULL)
            val slots = if (StudioPrefs.cutsFill) StudioCuts.fillSlots(plan.times, sceneClips, ready.associateBy { it.id })
                else StudioCuts.slots(plan.times, sceneClips, ready.associateBy { it.id })
            return StudioBg(null, null, emptyList(), slots, ready.associate { it.id to File(cutsDir, it.file) })
        }
        if (!videoReady() && StudioPrefs.bgPiece > 0) {
            // background clips cut into pieces that fill the whole voice-over
            val list = clips()
            val slots = StudioMix.bgSlots(list.map { it.first.name to it.second }, plan.totalMs, StudioPrefs.bgPiece, StudioPrefs.bgClips.hashCode().toLong())
            if (slots.isNotEmpty()) return StudioBg(null, null, emptyList(), slots, list.associate { it.first.name to it.first })
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
        if (list.isNotEmpty()) {
            c.add(text(this, t("Как заполнить озвучку", "How to fill the voice-over"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val piece = StudioPrefs.bgPiece
            c.add(choiceChips(this, listOf(0L to t("🔁 Клипы целиком по кругу", "🔁 Whole clips in a loop")) +
                listOf(1_000L, 2_000L, 3_000L, 5_000L).map { it to t("🧩 Куски по ", "🧩 Pieces of ") + StudioMix.pieceLabel(it) }, piece) { v ->
                StudioPrefs.bgPiece = v
                board = emptyList()
                render()
            }, 4)
            val voice = pcm?.ms ?: 0L
            val total = list.sumOf { it.second }
            if (piece > 0) c.muted(t("Клипы режутся на куски по ${StudioMix.pieceLabel(piece)}, куски разных клипов идут вперемешку, пока не заполнят всю озвучку; повтор — только когда кончатся все куски.",
                "The clips are cut into pieces of ${StudioMix.pieceLabel(piece)}; pieces of different clips take turns until they fill the whole voice-over; they repeat only when all pieces are used.") +
                if (voice > 0 && total < voice) t(" Сейчас клипов на ${Studio.clock(total)} из ${Studio.clock(voice)} — добавьте ещё, чтобы не было повторов.",
                    " Now the clips last ${Studio.clock(total)} of ${Studio.clock(voice)} — add more to avoid repeats.") else "")
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
        cutParts = if (task == StudioTask.CUTS && partsFile.exists()) StudioCuts.fromJson(partsFile.readText()) else emptyList()
    }

    private fun saveParts(list: List<SClip>) {
        cutParts = list
        runCatching { partsFile.writeText(StudioCuts.toJson(list)) }.onFailure { L.w("studio", "save pieces: ${it.message}") }
    }

    /** Pieces cut at the storyboard that are on disk. */
    private fun readyParts(): List<SClip> = cutParts.filter { it.file.isNotEmpty() && it.lenMs > 0 && File(partsDir, it.file).exists() }

    /** Clips the scenes can take: every marked video when cutting at the storyboard, else the downloaded ones. */
    private fun placeable(): List<SClip> = if (StudioPrefs.cutsLate) cutClips else readyCuts()

    private fun placeIds(): Set<String> = placeable().map { it.id }.toSet()

    /** Scenes of [sceneClips] that have their own cut pieces on disk. */
    private fun coveredScenes(sceneClips: List<String>): Set<Int> =
        readyParts().filter { sceneClips.getOrNull(it.scene) == it.src }.map { it.scene }.toSet()

    private fun saveCuts(list: List<SClip>) {
        cutClips = list
        runCatching { cutsFile.writeText(StudioCuts.toJson(list)) }.onFailure { L.w("studio", "save clips: ${it.message}") }
    }

    /** Clips that are downloaded and cut. */
    private fun readyCuts(): List<SClip> = cutClips.filter { it.file.isNotEmpty() && it.lenMs > 0 && File(cutsDir, it.file).exists() }

    private fun readyIds(): Set<String> = readyCuts().map { it.id }.toSet()

    private fun withClips(sc: SScript, ids: List<String>) = sc.copy(scenes = sc.scenes.mapIndexed { i, s -> s.copy(clip = ids.getOrElse(i) { "" }) })

    private fun cutsCard(c: Col, n: Int) = c.card {
        add(text(ctx, "$n. " + t("🎞 Клипы с YouTube", "🎞 YouTube clips"), Type.TITLE_M, bold = true), 0)
        val late = StudioPrefs.cutsLate
        if (late) muted(t("Добавьте видео: ссылка, откуда брать (начало) и что в кадре — по этой подписи ИИ-режиссёр поймёт, куда поставить клип. " +
            "Заранее ничего не качается: после раскадровки куски нарежутся сами — ровно на длину озвучки, без звука.",
            "Add videos: link, where to take from (start) and what is in the shot; the AI director uses this note to place the clip. " +
                "Nothing is downloaded beforehand: after the storyboard the pieces are cut by themselves, exactly as long as the voice-over, without sound."))
        else muted(t("Отметьте куски видео: ссылка, начало и конец (до 30 с) и что в кадре — по этой подписи ИИ-режиссёр поймёт, куда поставить клип. " +
            "Скачиваются только эти секунды, без звука. Короткие вставки по 1–5 с безопаснее для авторских прав.",
            "Mark pieces of videos: link, start and end (up to 30 s) and what is in the shot; the AI director uses this note to place the clip. " +
                "Only these seconds are downloaded, without sound. Short 1–5 s inserts are safer for copyright."))
        if (busy.isEmpty()) add(choiceChips(ctx, listOf(true to t("✂ Резать при раскадровке", "✂ Cut at the storyboard"),
            false to t("⬇ Скачать отмеченное заранее", "⬇ Download the marks beforehand")), late) { v ->
            StudioPrefs.cutsLate = v
            board = emptyList()
            render()
        }, 8)
        cutClips.forEach { cl ->
            val ready = !late && cl.file.isNotEmpty() && File(cutsDir, cl.file).exists()
            val about = cl.note.ifBlank { cl.title }.ifBlank { cl.url }
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(text(ctx, (if (late) "🎞 " else if (ready) "✅ " else "⬇ ") + "${cl.id} · ${StudioCuts.stamp(cl.fromMs)}" +
                    (if (late) "" else "–${StudioCuts.stamp(cl.toMs)}") + "\n" +
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
        clipQuality(this, if (late) emptyList() else readyCuts(), { render() }) { downloadCuts(it) }
        if (late) return@card
        if (cutClips.any { it !in readyCuts() }) button(t("⬇ Скачать", "⬇ Download"), primary = true) { downloadCuts() }
        val ready = readyCuts()
        if (ready.isNotEmpty()) {
            add(text(ctx, t("Сцена длиннее клипа", "A scene longer than its clip"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            add(choiceChips(ctx, listOf(true to t("🧩 Продолжить другими клипами", "🧩 Go on with other clips"),
                false to t("🔁 Повторить клип", "🔁 Repeat the clip")), StudioPrefs.cutsFill) { v ->
                StudioPrefs.cutsFill = v
                board = emptyList()
                render()
            }, 4)
            val voice = pcm?.ms ?: 0L
            val have = ready.sumOf { it.lenMs }
            val lack = voice - have
            if (StudioPrefs.cutsFill && voice > 0 && lack > 1_000) {
                muted(t("Клипов на ${Studio.clock(have)} из ${Studio.clock(voice)} озвучки: без новых отрезков часть кадров повторится.",
                    "Clips last ${Studio.clock(have)} of the ${Studio.clock(voice)} voice-over: without new parts some shots repeat."))
                button(t("🧩 Добрать отрезки из тех же видео (${Studio.clock(lack)})", "🧩 Take more parts of the same videos (${Studio.clock(lack)})"),
                    style = BtnStyle.TONAL) {
                    val extra = StudioMix.extraParts(ready, lack, StudioCuts.MAX_CLIPS - cutClips.size)
                    if (extra.isEmpty()) toast(t("Больше нечего добрать: видео закончились или ${StudioCuts.MAX_CLIPS} клипов", "Nothing more to take: the videos ended or ${StudioCuts.MAX_CLIPS} clips"))
                    else {
                        saveCuts(cutClips + extra)
                        toast(t("Добавлено отрезков: ${extra.size} — качаю", "Parts added: ${extra.size} — downloading"))
                        downloadCuts()
                    }
                }
            }
        }
    }

    // ---------- cartoon characters ----------

    /** Characters of the cartoon: who they are, how they look and sound. */
    private fun castCard(c: Col, n: Int) = c.card {
        add(text(ctx, "$n. " + t("🎭 Персонажи", "🎭 Characters"), Type.TITLE_M, bold = true), 0)
        muted(t("До ${StudioToon.MAX_CAST} героев. Соберите своего из деталей, опишите словами, создайте по картинке или возьмите свою картинку — встроенным ИИ или любым другим через протокол lumen.char. В сценарии реплика начинается с @Имя:",
            "Up to ${StudioToon.MAX_CAST} characters. Build your own from parts, describe it, make it from a picture or use your picture as it is — with the built-in AI or any other through the lumen.char protocol. In the script a line starts with @Name:"))
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
                    contentDescription = StudioToon.kindLabel(ch)
                }, LinearLayout.LayoutParams(dp(56), dp(56)))
                val persona = if (ch.persona.isNotBlank()) "\n" + ch.persona.take(60) + if (ch.persona.length > 60) "…" else "" else ""
                addView(text(ctx, "@${ch.name}\n${StudioToon.kindLabel(ch)} · $voice$persona", 14f),
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
        if (busy.isEmpty()) {
            val views = mutableListOf<View>()
            if (cast.size < StudioToon.MAX_CAST) views += btn(ctx, t("＋ Персонаж", "＋ Character"), style = BtnStyle.TONAL) { editChar(-1) }
            views += btn(ctx, t("📚 Библиотека", "📚 Library") + StudioPrefs.library.size.let { if (it > 0) " ($it)" else "" }, style = BtnStyle.TEXT) {
                charLibrary { picked -> addFromLibrary(picked) }
            }
            row(*views.toTypedArray())
        }
    }

    /** Puts a character of the library into the cast: replaces the one with its name, or takes a free place. */
    private fun addFromLibrary(ch: SChar) {
        val cast = StudioPrefs.cast.toMutableList()
        val same = cast.indexOfFirst { it.name.equals(ch.name, true) }
        when {
            same >= 0 -> cast[same] = ch
            cast.size < StudioToon.MAX_CAST -> cast += ch
            else -> {
                toast(t("В ролике уже ${StudioToon.MAX_CAST} героя: уберите одного", "The cast is full (${StudioToon.MAX_CAST}): remove one first"))
                return
            }
        }
        StudioPrefs.cast = cast
        board = emptyList()
        render()
    }

    /** A character drawn on a transparent square [px]. */
    private fun charBitmap(ch: SChar, px: Int, mood: String = "happy"): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        StudioToonArt().draw(android.graphics.Canvas(bmp), ch, px / 2f, px * 0.97f, px * 0.78f,
            ToonPose(mood = mood, tMs = 1000, accent = StudioPrefs.accent))
        return bmp
    }

    /** Waits for the picture picked for the character editor. */
    private var onCharImage: ((android.net.Uri) -> Unit)? = null

    /** Adds a character ([index] == -1) or changes one. */
    private fun editChar(index: Int) {
        val cast = StudioPrefs.cast
        val old = cast.getOrNull(index)
        val start = old ?: run {
            val look = StudioToon.LOOKS.firstOrNull { l -> cast.none { it.look == l } } ?: "blob"
            SChar(StudioToon.cleanName(StudioToon.lookLabel(look)), look)
        }
        StudioCharEditor(this, start, old == null, aiLang(),
            taken = { name -> cast.withIndex().any { (k, ch) -> k != index && ch.name.equals(name, true) } },
            pickImage = { cb ->
                onCharImage = cb
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_CHAR_IMG)
            }
        ) { ch ->
            val list = StudioPrefs.cast.toMutableList()
            if (old == null || index !in list.indices) list += ch else list[index] = ch
            StudioPrefs.cast = list.take(StudioToon.MAX_CAST)
            val name = ch.name
            // the script refers to the old name: rename the lines
            if (old != null && old.name != name) {
                scriptEdit.setText(scriptEdit.text.toString().replace(Regex("(^|\\n)@" + Regex.escape(old.name) + "(?=[(:\\s])"), "$1@$name"))
                script = script?.let { sc -> sc.copy(scenes = sc.scenes.map { if (it.who == old.name) it.copy(who = name) else it }) }
            }
            board = emptyList()
            render()
        }.show()
    }

    /**
     * Finds videos by title on YouTube, optionally lets AI keep only the ones that fit a description,
     * and adds the marked ones as clips (start 0:00, the time is set later with ✎).
     */
    private fun findClips(forScene: Int = -1) {
        var hits: List<CutHit> = emptyList()
        var notes: Map<String, String> = emptyMap()
        val marked = LinkedHashSet<String>()
        // own parts marked while watching: url → from, to (ms)
        val parts = HashMap<String, Pair<Long, Long>>()
        val look = MiniLook(this)
        var watching = ""
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
            (look.view.parent as? ViewGroup)?.removeView(look.view)
            val picked = hits.filter { it.url in notes }
            val rest = hits.filter { it.url !in notes }
            fun group(title: String, list: List<CutHit>) {
                if (list.isEmpty()) return
                if (picked.isNotEmpty()) box.addView(text(this, title, Type.LABEL, bold = true, color = Palette.MUTED),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
                for (h in list) {
                    val sub = listOf(h.channel, if (h.durationMs > 0) StudioCuts.stamp(h.durationMs) else "").filter { it.isNotBlank() }.joinToString(" · ")
                    val note = notes[h.url].orEmpty()
                    val part = parts[h.url]?.let { (a, b) -> "\n✂ " + StudioCuts.stamp(a) + "–" + StudioCuts.stamp(b) }.orEmpty()
                    val label = h.title + (if (sub.isNotEmpty()) "\n$sub" else "") + (if (note.isNotEmpty()) "\n✨ $note" else "") + part
                    box.addView(hitRow(h, label, h.url in marked, watching == h.url, { on ->
                        if (on) marked += h.url else marked -= h.url
                        updateAdd()
                    }) {
                        watching = if (watching == h.url) "" else h.url
                        if (watching.isEmpty()) look.stop() else {
                            look.open(h.url)
                            look.dur = h.durationMs
                            look.marks = listOfNotNull(parts[h.url])
                        }
                        fill()
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
                    if (watching == h.url) {
                        box.addView(look.view)
                        box.addView(partRow(look, parts[h.url]) { range ->
                            if (range == null) parts -= h.url else {
                                parts[h.url] = range
                                marked += h.url
                            }
                            look.marks = listOfNotNull(range)
                            fill()
                        })
                    }
                }
            }
            group(t("✨ Подобрал ИИ", "✨ Picked by AI"), picked)
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
                    CutHit(it.url, it.title, it.channel, if (it.duration > 0) it.duration * 1000 else 0,
                        YtQuery.videoIdFromUrl(it.url)?.let { id -> "https://i.ytimg.com/vi/$id/mqdefault.jpg" } ?: it.thumb)
                }
                val picks = if (useAi && found.isNotEmpty()) {
                    runOnUiThread { status.first.text = t("ИИ отбирает видео…", "AI is picking videos…") }
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
                    useAi -> t("Найдено: ${found.size}, ИИ подобрал: ${picks.size}", "Found: ${found.size}, AI picked: ${picks.size}")
                    else -> t("Найдено: ${found.size}. Отметьте нужные.", "Found: ${found.size}. Mark the ones you need.")
                }
                fill()
            }
        }

        bottomSheet(t("Найти видео для нарезки", "Find videos for cuts"), onDismiss = { look.release() }) { dismiss ->
            dismissSheet = dismiss
            muted(t("Поиск по названию на YouTube. ИИ-фильтр (необязательно) оставит видео, подходящие под описание, — по названиям; в ИИ уходят только названия. " +
                "Нажмите на картинку: кадры по всему видео и просмотр с любого места; на нужном месте «⟦ Начало» и «Конец ⟧» — отрезок видно на полосе под видео.",
                "Search YouTube by title. The AI filter (optional) keeps videos that fit the description, by titles; only the titles go to AI. " +
                    "Tap the picture: frames across the video and watching from any place; at the right moment \"⟦ Start\" and \"End ⟧\" — the part shows on the bar under the video."))
            val query = edit(t("Что искать, напр. «Хоумлендер реакция»", "What to search, e.g. \"shocked reaction meme\""))
            val want = edit(t("ИИ-фильтр: что должно быть в кадре (можно пусто)", "AI filter: what must be in the shot (optional)"))
            row(
                btn(ctx, t("🔎 Искать", "🔎 Search"), style = BtnStyle.TONAL) { search(query.text.toString().trim(), "") },
                btn(ctx, t("✨ Искать с ИИ", "✨ Search with AI"), primary = true) {
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
                val list = StudioCuts.addHits(cutClips, chosen, notes).map { c ->
                    // the part marked while watching
                    parts[c.url]?.takeIf { c.id !in cutClips.map { it.id } }?.let { (a, b) -> StudioCuts.span(a, b).let { (x, y) -> c.copy(fromMs = x, toMs = y) } } ?: c
                }
                if (list.size - before < chosen.size) {
                    toast(t("Добавлено ${list.size - before}: повторы пропущены, максимум ${StudioCuts.MAX_CLIPS}",
                        "Added ${list.size - before}: repeats skipped, at most ${StudioCuts.MAX_CLIPS}"))
                }
                saveCuts(list)
                board = emptyList()
                dismissSheet()
                // a new video for one scene of the storyboard: the scene takes it, its piece is cut at the marked part
                val picked = list.firstOrNull { c -> c.url == chosen.first().url }
                if (forScene >= 0 && picked != null) {
                    setSceneClip(forScene, picked.id)
                    cutPieces(only = setOf(forScene))
                } else render()
            }
            updateAdd()
        }
    }

    /** A found video: its picture (tap — frames and watching), the check and ▾. */
    private fun hitRow(h: CutHit, label: String, checked: Boolean, open: Boolean, onCheck: (Boolean) -> Unit, onLook: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ImageView(this@StudioActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF202020.toInt())
                Thumbs.load(this, h.thumb)
                contentDescription = t("Кадры видео", "Video frames")
                setOnClickListener { onLook() }
            }, LinearLayout.LayoutParams(dp(96), dp(54)).apply { rightMargin = dp(6) })
            addView(chk(this@StudioActivity, label, checked, onCheck), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn(this@StudioActivity, if (open) "▴" else "▾", style = BtnStyle.TEXT) { onLook() }.apply {
                contentDescription = t("Кадры и просмотр", "Frames and watching")
            })
        }

    /** Marks of a part while the video plays: start and end at the playing moment, ✕ clears; [set] gets the part. */
    private fun partRow(look: MiniLook, cur: Pair<Long, Long>?, set: (Pair<Long, Long>?) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        fun now(): Long? = look.posMs().takeIf { it >= 0 }?.let { it / 100 * 100 }
            ?: run { toast(t("Включите видео: тап по кадру", "Play the video: tap a frame")); null }
        addView(btn(this@StudioActivity, t("⟦ Начало", "⟦ Start"), style = BtnStyle.TONAL) {
            val at = now() ?: return@btn
            val end = cur?.second?.takeIf { it > at + 300 } ?: (at + StudioCuts.DEFAULT_CLIP_MS)
            set(StudioCuts.span(at, end))
        })
        addView(btn(this@StudioActivity, t("Конец ⟧", "End ⟧"), style = BtnStyle.TONAL) {
            val at = now() ?: return@btn
            val from = cur?.first?.takeIf { it < at - 300 } ?: maxOf(0, at - StudioCuts.DEFAULT_CLIP_MS)
            set(StudioCuts.span(from, at))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        addView(text(this@StudioActivity, cur?.let { (a, b) -> "✂ " + StudioCuts.stamp(a) + "–" + StudioCuts.stamp(b) }
            ?: t("отрезок не отмечен", "no part marked"), 13f, color = Palette.MUTED).apply { setPadding(dp(8), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (cur != null) addView(btn(this@StudioActivity, "✕", style = BtnStyle.TEXT) { set(null) }.apply { contentDescription = t("Убрать отрезок", "Clear the part") })
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
    private fun downloadCuts(again: Set<String> = emptySet()) {
        val todo = cutClips.filter { it !in readyCuts() || it.id in again }
        if (todo.isEmpty()) return
        busy = "cuts"
        cancel = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        val dirNow = cutsDir
        val maxH = StudioPrefs.clipCap
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

    /** Signature of the scenes the pieces were last cut for automatically (no second try on the same layout). */
    private var autoCutKey = ""

    /**
     * Cutting at the storyboard: length of the pieces for all scenes at once, what is cut against the voice-over,
     * "cut again in other places"; new or changed scenes are cut by themselves once.
     */
    private fun cutBlock(c: Col, plan: StudioPlan) = with(c) {
        val sceneClips = StudioCuts.effectiveClips(plan.script.scenes, placeIds(), all = plan.fullMode != StudioOverlay.FULL)
        val need = StudioCuts.needCut(plan.times, sceneClips, cutParts)
        add(text(ctx, t("✂ Куски клипов", "✂ Clip pieces"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
        val piece = StudioPrefs.cutPiece
        val presets = listOf(1_500L, 2_000L, 3_000L, 5_000L)
        if (busy.isEmpty()) add(choiceChips(ctx, listOf(0L to t("По сцене", "Per scene")) + presets.map { it to StudioMix.pieceLabel(it) } +
            listOf((if (piece > 0 && piece !in presets) piece else -1L) to (if (piece > 0 && piece !in presets) "✎ " + StudioMix.pieceLabel(piece) else t("✎ Своя", "✎ Own"))), piece) { v ->
            if (v == -1L || v == piece && v > 0 && v !in presets) {
                askText(t("Длина куска, секунд", "Piece length, seconds"), if (piece > 0) (piece / 1000.0).toString() else "2.5") { s ->
                    StudioMix.parsePiece(s)?.let { setPiece(it) } ?: toast(t("Нужно число секунд", "A number of seconds is needed"))
                }
            } else setPiece(v)
        }, 4)
        val clipped = plan.times.filter { sceneClips.getOrNull(it.scene).orEmpty().isNotEmpty() }.sumOf { it.endMs - it.startMs }
        val parts = readyParts().filter { sceneClips.getOrNull(it.scene) == it.src }
        muted(if (parts.isEmpty()) t("Куски нарежутся по сценам с клипами: ${Studio.clock(clipped)} из ${Studio.clock(plan.totalMs)} ролика.",
            "Pieces are cut for the scenes with clips: ${Studio.clock(clipped)} of the ${Studio.clock(plan.totalMs)} video.")
        else t("Нарезано кусков: ${parts.size} на ${Studio.clock(clipped)} из ${Studio.clock(plan.totalMs)} ролика.",
            "Pieces cut: ${parts.size} for ${Studio.clock(clipped)} of the ${Studio.clock(plan.totalMs)} video.") +
            if (need.isNotEmpty()) t(" Не нарезано сцен: ${need.size}.", " Scenes not cut: ${need.size}.") else "")
        progress("cutparts", t("Нарезка кусков…", "Cutting pieces…"))
        if (busy.isNotEmpty()) return@with
        if (need.isNotEmpty()) button(t("✂ Нарезать куски (${need.size})", "✂ Cut the pieces (${need.size})"), primary = true) { cutPieces() }
        if (parts.isNotEmpty()) row(
            btn(ctx, t("🎲 В других местах", "🎲 Other places"), style = BtnStyle.TONAL) { cutPieces(reshuffle = true) },
            btn(ctx, t("✂ Нарезать заново", "✂ Cut again"), style = BtnStyle.TEXT) { cutPieces(again = true) }
        )
        // a fresh layout cuts its pieces by itself, once
        val key = sceneClips.joinToString(",") + "|" + plan.times.joinToString(",") { "${it.startMs}-${it.endMs}" }
        if (need.isNotEmpty() && key != autoCutKey) {
            autoCutKey = key
            window.decorView.post { cutPieces() }
        }
    }

    private fun setPiece(ms: Long) {
        if (ms == StudioPrefs.cutPiece) return
        StudioPrefs.cutPiece = ms
        if (cutParts.isNotEmpty()) cutPieces(again = true) else render()
    }

    private fun askText(title: String, value: String, done: (String) -> Unit) {
        val e = edt(this, title, value)
        val box = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        android.app.AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> done(e.text.toString()) }
            .setNegativeButton(t("Отмена", "Cancel"), null).show()
    }

    /**
     * Cuts the pieces of the scenes with clips: the missing ones, or [again] all of them from the marked starts,
     * or [reshuffle] all at new places of the videos (away from the pieces before). Lengths of the videos are
     * read first, the pieces download two at a time.
     */
    private fun cutPieces(again: Boolean = false, reshuffle: Boolean = false, only: Set<Int>? = null) {
        if (busy.isNotEmpty() || task != StudioTask.CUTS) return
        val plan = currentPlan() ?: return
        val sceneClips = StudioCuts.effectiveClips(plan.script.scenes, placeIds(), all = plan.fullMode != StudioOverlay.FULL)
        val old = cutParts
        val need = only ?: if (again || reshuffle) null else StudioCuts.needCut(plan.times, sceneClips, old).toSet()
        if (need != null && need.isEmpty()) return
        val times = if (need == null) plan.times else plan.times.filter { it.scene in need }
        val keep = if (need == null) emptyList() else old.filter { it.scene !in need && sceneClips.getOrNull(it.scene) == it.src }
        val gone = old.filter { it !in keep }
        val used = (if (reshuffle || only != null) old else keep).groupBy({ it.url }, { it.fromMs to it.toMs })
        val seed = if (reshuffle) System.nanoTime() or 1L else 0L
        val pieceMs = StudioPrefs.cutPiece
        val clipsNow = cutClips
        val wanted = sceneClips.toSet()
        val dir = partsDir
        val maxH = StudioPrefs.clipCap
        busy = "cutparts"
        cancel = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        job("cutparts", {
            gone.forEach { if (it.file.isNotEmpty()) File(dir, it.file).delete() }
            report("cutparts", t("Длина видео…", "Video lengths…"))
            // new places are picked inside the whole video: its length first
            val withDur = clipsNow.map { c ->
                if (c.durMs > 0 || c.id !in wanted) c
                else runCatching { c.copy(durMs = StudioCutsLoad.durationMs(c.url)) }.getOrElse { e ->
                    if (e is java.util.concurrent.CancellationException) throw e
                    c
                }
            }
            val todo = StudioCuts.cutPlan(times, sceneClips, withDur.associateBy { it.id }, pieceMs, seed, used)
            val done = java.util.concurrent.ConcurrentHashMap<String, SClip>()
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            val count = java.util.concurrent.atomic.AtomicInteger()
            Par.mapAll(todo, 2) { i, p ->
                if (cancel) throw java.util.concurrent.CancellationException()
                try {
                    done[p.id] = StudioCutsLoad.fetch(p, dir, maxH, { cancel }) { }
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w("studio", "piece ${p.id}: ${e.message}")
                    errors += "${p.src} → ${p.id}: ${e.message ?: e.javaClass.simpleName}"
                }
                report("cutparts", t("Кусок ${count.incrementAndGet()}/${todo.size}", "Piece ${count.get()}/${todo.size}"))
                i
            }
            Triple(withDur, todo.mapNotNull { done[it.id] }, errors.toList())
        }, { e ->
            busy = ""
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            saveParts(keep)
            render()
            if (e !is java.util.concurrent.CancellationException) err(e)
        }) { (withDur, got, errors) ->
            busy = ""
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val dur = withDur.associate { it.id to it.durMs }
            saveCuts(cutClips.map { c -> dur[c.id]?.takeIf { it > 0 && c.durMs <= 0 }?.let { c.copy(durMs = it) } ?: c })
            saveParts((keep + got).sortedWith(compareBy({ it.scene }, { it.part })))
            board = emptyList()
            render()
            if (errors.isNotEmpty()) showText(t("Не все куски скачались", "Some pieces failed"), errors.joinToString("\n"))
            else toast(t("Нарезано кусков: ${got.size}", "Pieces cut: ${got.size}"))
        }
    }

    /** Scene [i] of the storyboard: another piece of its video, another added clip, a new video from the search, or graphics. */
    private fun pieceMenu(i: Int) {
        val sc = script ?: return
        val plan = currentPlan() ?: return
        val ids = StudioCuts.effectiveClips(plan.script.scenes, placeIds(), all = plan.fullMode != StudioOverlay.FULL)
        val own = ids.getOrNull(i).orEmpty()
        val src = cutClips.firstOrNull { it.id == own }
        val items = mutableListOf<Pair<String, () -> Unit>>()
        if (src != null) items += t("🎲 Другой кусок этого видео", "🎲 Another piece of this video") to {
            // the scene keeps its video: a new place, away from the pieces already taken
            if (sc.scenes[i].clip != own) setSceneClip(i, own)
            cutPieces(only = setOf(i), reshuffle = true)
        }
        items += t("🎞 Другое добавленное видео…", "🎞 Another added video…") to { pickClip(i) }
        items += t("🔎 Новое видео: поиск…", "🔎 A new video: search…") to { findClips(forScene = i) }
        items += t("— Графика, без клипа", "— Graphics, no clip") to { setSceneClip(i, "") }
        android.app.AlertDialog.Builder(this).setTitle(t("Сцена ${i + 1}", "Scene ${i + 1}") + (src?.let { " · 🎞 ${it.id}" } ?: ""))
            .setItems(items.map { it.first }.toTypedArray()) { _, k -> items[k].second() }
            .setNegativeButton(t("Отмена", "Cancel"), null).show()
    }

    private fun setSceneClip(i: Int, id: String) {
        val sc = script ?: return
        script = sc.copy(scenes = sc.scenes.mapIndexed { j, s -> if (j == i) s.copy(clip = id) else s })
        board = emptyList()
        saveAnalysis()
        render()
    }

    /** Picks the clip of scene [i] (or graphics). */
    private fun pickClip(i: Int) {
        val sc = script ?: return
        val ready = placeable()
        val labels = listOf(t("— графика, без клипа", "— graphics, no clip")) +
            ready.map { "🎞 ${it.id} · ${it.note.ifBlank { it.title }.take(50)}" + if (StudioPrefs.cutsLate) "" else " · ${(it.lenMs + 500) / 1000} с" }
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
        script = withClips(sc, StudioCuts.autoAssign(sc.scenes, placeable()))
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
        val clipScenes = if (task == StudioTask.CUTS && style == StudioStyle.VIDEO) {
            if (StudioPrefs.cutsLate) {
                val ids = StudioCuts.effectiveClips(sc.scenes, placeIds())
                val covered = coveredScenes(ids)
                val ready = readyIds()
                sc.scenes.indices.filter { ids[it].isNotEmpty() && (it in covered || ids[it] in ready) }.toSet()
            } else StudioCuts.effectiveClips(sc.scenes, readyIds()).let { ids -> sc.scenes.indices.filter { ids[it].isNotEmpty() }.toSet() }
        } else null
        val ov = StudioPrefs.over
        return StudioPlan.build(sc, words, voice.ms + 500, style, StudioPrefs.accent, ranges, ov.overlay(),
            StudioPrefs.zooms, StudioPrefs.transitions,
            if (StudioPrefs.pulse) pulseOf(voice) else null,
            if (StudioPrefs.liveEmoji) emojiDir else null,
            clipScenes, if (task == StudioTask.CARTOON) StudioPrefs.cast else null, motionTpls,
            ov.layers(), ov.overScale(), schemeStyleOf(ov, style), if (style == StudioStyle.VIDEO) ov.schemePlace() else "overlay", "auto",
            ov.toon, ov.bg, ov.fullMode(), TextStyles.of(ov.subStyle))
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
        visit()
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

    private fun aiLang() = Studio.langName(lang())

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
            t("ИИ напишет новый сценарий по теме; текущий текст будет учтён как черновик.", "AI will write a new script; the current text is used as a draft."),
            t("Написать", "Write"), onOk = go) else go()
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
                "lang" to aiLang(), "extra" to extra, "visuals" to visuals(), "cast" to StudioToon.promptCast(cast)
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
            toast(t("Подключите ИИ в настройках", "Connect an AI in the settings"))
            return
        }
        val ideaE = edt(this, t("Идея: о чём мультфильм", "Idea: what the cartoon is about"), topicEdit.text.toString(), multiline = true)
        val scenesE = edt(this, t("Сцены (необязательно): что происходит по порядку, по строке на сцену", "Scenes (optional): what happens in order, one line per scene"), "", multiline = true)
        val scriptE = edt(this, t("Сценарий или реплики (необязательно)", "Script or lines (optional)"), scriptEdit.text.toString(), multiline = true).apply { maxLines = 8 }
        var build = true
        val col = Col(this, 20)
        col.muted(t("Заполните любое из полей — остальное ИИ придумает сам: сценарий с репликами персонажей, сцены и графику, озвучку голосами героев, монтаж режиссёра и, если включено, сборку видео. Персонажи — из карточки «🎭 Персонажи», длительность и кадр — как выбраны на экране.",
            "Fill in any of the fields; AI invents the rest: the script with the characters' lines, scenes and graphics, the voices, the director's edit and, if on, the video build. Characters come from the \"🎭 Characters\" card, length and frame as chosen on the screen."))
        col.add(ideaE, 12)
        col.add(scenesE, 8)
        col.add(scriptE, 8)
        col.add(android.widget.CheckBox(this).apply {
            text = t("Сразу собрать видео", "Build the video right away")
            isChecked = true
            setOnCheckedChangeListener { _, v -> build = v }
        }, 8)
        android.app.AlertDialog.Builder(this).setTitle(t("🤖 ИИ сделает всё", "🤖 AI makes everything"))
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
                if (!synth()) StudioPrefs.voiceMode = "edge"
                autoAll = build
                podcastAuto = false
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
        android.app.AlertDialog.Builder(this).setTitle(t("🪄 ИИ-помощник", "🪄 AI assistant"))
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
                "visuals" to visuals(), "script" to Studio.toJson(before)
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
    private fun <T : Any> aiJson(
        key: String, vars: Map<String, String>, system: String, limit0: Int, fast: Boolean = false, arrayKey: String? = null, parse: (String) -> T?
    ): T {
        if (cancel) throw java.util.concurrent.CancellationException()
        val prov = AiTasks.requireProvider()
        // a broken answer is fixed alone, a cut one is continued; the whole request goes again only after that (LlmJson)
        val out = LlmJson.call(prov, if (fast) prov.fastModel else prov.smartModel, system, Prompts.fill(key, vars), key, "studio", limit0, parse,
            arrayKey = arrayKey, fullRetries = 2)
        lastTokens = out.inTokens to out.outTokens
        return out.value
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
        prompterFile.delete()
        recMs = 0
        prompterClock = if (StudioPrefs.prompter) prompterScenes().flatten().takeIf { it.isNotEmpty() }?.let { PrompterClock(it, StudioPrefs.prompterWpm) } else null
        val rec = StudioRecorder { level, ms ->
            levelBar?.progress = (level * 3000).toInt().coerceAtMost(1000)
            timerText?.text = Studio.clock(ms)
            recMs = ms
            if (prompterClock?.tick(ms) == true) paintPrompter()
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
        prompterText = null
        prompterSpeed = null
        prompterClock?.let { clock ->
            // what lit up when: the word timing of the take
            if (clock.log.isNotEmpty()) runCatching {
                prompterFile.writeText(StudioPrompter.toJson(clock.tokens, clock.log, Studio.durationMs(take.size, rec.rate)))
            }.onFailure { L.w("studio", "prompter log: ${it.message}") }
        }
        prompterClock = null
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

    /** «Podcast → video»: a fresh work from the recording, then the whole chain without stops up to the built video. */
    private var podcastAuto = false

    private fun podcast(uri: Uri) {
        if (busy.isNotEmpty() || StudioJob.running || !canLeave()) return
        saveInputs()
        val moved = stashCurrent()
        reload()
        StudioPrefs.voiceMode = "file"
        StudioPrefs.style = StudioStyle.EXPLAIN.name
        StudioPrefs.broll = true
        StudioPrefs.sfx = true
        scriptEdit.setText("")
        saveInputs()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1) }
        }
        if (moved) toast(t("Прежняя работа — в «Черновиках»", "The previous work is in Drafts"))
        if (Provider.active() == null) toast(t("ИИ не подключён: монтаж будет по правилам, без инфоблоков", "No AI connected: rule-based edit, no info-blocks"))
        podcastAuto = true
        busy = "import"
        cancel = false
        render()
        job("import", {
            report("import", t("Декодирование и громкость…", "Decoding and loudness…"))
            val p = StudioAudio.decode(this, uri)
            if (p.data.isEmpty()) error(t("В файле нет звука", "No sound in the file"))
            writeTake(Studio.normalize(Studio.resample(p.data, p.rate, 44_100)), 44_100)
        }, { e -> busy = ""; podcastAuto = false; render(); err(e) }) {
            busy = ""
            resetAnalysis()
            autoAll = true
            analyse()
            if (busy.isEmpty()) {
                autoAll = false
                podcastAuto = false
            }
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
            REQ_PODCAST -> podcast(uri)
            REQ_VIDEO, REQ_CAPTURE -> importVideo(uri)
            REQ_MUSIC -> importMusic(uri)
            REQ_CHAR_IMG -> onCharImage?.invoke(uri).also { onCharImage = null }
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
        val synth = mode == "edge" || mode == "gemini" || mode == "azure"
        if (synth && sc.scenes.isEmpty()) {
            toast(t("Нужен сценарий (шаг 1)", "A script is needed (step 1)"))
            return
        }
        if (!synth && !takeFile.exists()) {
            toast(when (mode) {
                "mic" -> t("Сначала запишите голос (шаг 2)", "Record your voice first (step 2)")
                "video" -> t("Сначала снимите или выберите видео (шаг 2)", "Film or pick a video first (step 2)")
                else -> t("Сначала выберите аудио (шаг 2)", "Pick audio first (step 2)")
            })
            return
        }
        val useScript = !synth && (task == StudioTask.VOICE || StudioPrefs.readScript) && sc.scenes.isNotEmpty()
        // no script and no recogniser: edit by the sound alone with a no-AI template
        val canRecognise = synth || useScript || StudioRecognizer.available(this)
        var noText = false
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
            if (noText) {
                // no text: the chosen own template (it works by timing and loudness), else Rhythm
                val own = editTpls.firstOrNull { "edit:" + it.id == StudioPrefs.approach }
                val name = own?.name ?: t("Ритм", "Rhythm")
                toast(t("Речь не распознана — смонтировано по звуку шаблоном «$name»", "No speech recognised: edited by the sound with the \"$name\" template"))
                if (own != null) animateEdit(own) else animate(StudioApproach.RHYTHM, remember = false)
            } else if (StudioPrefs.autoDirect || autoAll) {
                // a podcast always gets the full AI director (it also cuts slips and long pauses)
                val ap = if (podcastAuto) StudioApproach.FULL else StudioApproach.of(StudioPrefs.approach)
                val own = if (podcastAuto) null else editTpls.firstOrNull { "edit:" + it.id == StudioPrefs.approach }
                if (own != null) animateEdit(own)
                else animate(if (ap.usesAi && Provider.active() == null) StudioApproach.RULES else ap, remember = false)
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
            mode == "gemini" -> job("analyse", { analyseGemini(sc) }, fail, done)
            mode == "azure" -> job("analyse", { analyseAzure(sc) }, fail, done)
            useScript -> job("analyse", { analyseTake(sc) }, fail, done)
            else -> Lumen.async({ speechOf(loadTake(), silentOk = true) }, fail) { (take, speech) ->
                // the take cannot be turned into text: beats by the sound, then a template
                fun byBeats() {
                    noText = true
                    pcm = take
                    words = Studio.beatWords(speech, take.ms)
                    transcript = ""
                    script = SScript("", listOf(SScene("")))
                    ranges = listOf(words.indices)
                    stats = Studio.stats(take.ms, speech, 0)
                    done(Unit)
                }
                fun use(res: List<Pair<Span, String>>, w: List<SWord>) {
                    pcm = take
                    words = w
                    transcript = res.joinToString(" ") { it.second }
                    script = Studio.autoScenes(w)
                    ranges = null
                    stats = Studio.stats(take.ms, speech, w.size)
                    done(Unit)
                }
                // the phone could not recognise the speech: Gemini listens to the take, else edit by the beats
                fun byAiOrBeats() {
                    val prov = Provider.active()?.takeIf { it.kind == ApiKind.GEMINI }
                    if (prov == null || speech.isEmpty()) {
                        L.w("studio", "no words recognised, editing by beats")
                        return byBeats()
                    }
                    Lumen.async({ aiTranscribe(prov, take, speech) }, { e ->
                        if (e is java.util.concurrent.CancellationException) fail(e)
                        else { L.w("studio", "AI transcription: ${e.message}"); byBeats() }
                    }) { res ->
                        val w = Studio.alignPieces(res, speech)
                        if (w.isEmpty()) byBeats() else use(res, w)
                    }
                }
                if (speech.isEmpty()) return@async byBeats()
                if (!canRecognise) return@async byAiOrBeats()
                report("analyse", t("Распознавание речи…", "Recognising speech…"))
                val pieces = Studio.groupSpans(speech)
                StudioRecognizer.recognize(this, take.data, take.rate, pieces, Studio.recogLang(lang()),
                    { i, n -> report("analyse", t("Распознавание $i/$n", "Recognising $i/$n")) }, { cancel },
                    { msg -> report("analyse", msg) }) { res ->
                    if (cancel) {
                        fail(java.util.concurrent.CancellationException())
                        return@recognize
                    }
                    val w = Studio.alignPieces(res, speech)
                    if (w.isEmpty()) byAiOrBeats() else use(res, w)
                }
            }
        }
    }

    /** Text of the take by Gemini (it hears audio): pieces of up to 3 minutes go as 16 kHz WAV. Runs off the main thread. */
    private fun aiTranscribe(prov: Provider, take: StudioAudio.Pcm, speech: List<Span>): List<Pair<Span, String>> {
        val chunks = Studio.groupSpans(speech, 180_000)
        return chunks.mapIndexedNotNull { k, c ->
            if (cancel) throw java.util.concurrent.CancellationException()
            report("analyse", t("Расшифровка через ИИ ${k + 1}/${chunks.size}…", "AI transcription ${k + 1}/${chunks.size}…"))
            val from = (c.startMs * take.rate / 1000).toInt().coerceIn(0, take.data.size)
            val to = (c.endMs * take.rate / 1000).toInt().coerceIn(from, take.data.size)
            val pcm16 = Studio.resample(take.data.copyOfRange(from, to), take.rate, StudioRecognizer.RATE)
            val body = java.nio.ByteBuffer.allocate(pcm16.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            body.asShortBuffer().put(pcm16)
            val wav = Studio.wavHeader(pcm16.size * 2, StudioRecognizer.RATE) + body.array()
            val r = Llm.complete(prov, prov.fastModel, "You transcribe speech exactly. Answer with the plain text only.",
                Prompts.fill("studio_stt", emptyMap()), maxTokens = 8192, task = "studio_stt", pid = "studio",
                image = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP), imageMime = "audio/wav")
            Studio.cleanTranscript(r.text).takeIf { it.isNotBlank() }?.let { c to it }
        }
    }

    private fun loadTake(): StudioAudio.Pcm {
        report("analyse", t("Чтение записи…", "Reading the take…"))
        val (data, rate) = Studio.readWav(takeFile.readBytes()) ?: error(t("Запись повреждена, запишите заново", "The take is damaged, record again"))
        return StudioAudio.Pcm(Studio.normalize(data), rate)
    }

    /** Speech of the take; with "cut pauses" on, the take is cut first and [cuts] remembers what was kept. */
    private fun speechOf(take0: StudioAudio.Pcm, silentOk: Boolean = false): Pair<StudioAudio.Pcm, List<Span>> {
        report("analyse", t("Поиск речи и пауз…", "Finding speech and pauses…"))
        var take = take0
        var speech = Studio.speechSpans(take.data, take.rate)
        cuts = null
        if (speech.isEmpty() && silentOk) return take to speech
        if (speech.isEmpty()) error(t("В записи не слышно речи. Говорите ближе к микрофону.", "No speech heard. Speak closer to the microphone."))
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
        val take0 = loadTake()
        val (take, speech) = speechOf(take0)
        report("analyse", t("Расстановка слов…", "Timing the words…"))
        val tokens = sc.scenes.flatMap { Studio.tokens(it.say) }
        // the teleprompter log of this take and script times the words; otherwise they are spread over the speech
        val log = prompterFile.takeIf { it.exists() }?.let { StudioPrompter.fromJson(it.readText(), tokens, take0.ms) }
        val w = log?.let { StudioPrompter.words(tokens, it, speech, take.ms, cuts).takeIf { ws -> ws.isNotEmpty() } }
            ?: Studio.alignAnchored(tokens, speech)
        if (log != null) L.i("studio", "words from the teleprompter: ${log.size} marks")
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
        val o = VoiceTone.edge(TtsOptions(voice, StudioPrefs.rate), StudioPrefs.tone)
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
            val so = if (ci >= 0) TtsOptions(castVoices[ci], StudioToon.rateOf(cast[ci], StudioPrefs.rate), StudioToon.pitchOf(cast[ci])) else o
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
                t(" Озвучено сцен: ${ready.get()} из $n, они сохранены — нажмите «🔍 Разметить сцены» ещё раз, продолжится с остальных. Проверьте интернет; держите Студию открытой или свёрнутой с уведомлением.",
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
            StudioTtsCache.key(scene.say, if (ci >= 0) TtsOptions(castVoices[ci], StudioToon.rateOf(cast[ci], StudioPrefs.rate), StudioToon.pitchOf(cast[ci])) else o)
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

    private fun analyseGemini(sc: SScript) {
        val narrator = GeminiVoice.voice(StudioPrefs.geminiVoice).name
        val model = StudioPrefs.geminiModel
        val style = geminiDirection()
        val cast = if (task == StudioTask.CARTOON) StudioPrefs.cast else emptyList()
        val castVoices = if (cast.isEmpty()) emptyList() else GeminiVoice.castVoices(castFemale(cast), narrator)
        val perScene = sc.scenes.map { s -> StudioToon.find(cast, s.who).let { if (it >= 0) castVoices[it] else narrator } }
        analyseChunked(sc, "Gemini", "gm", perScene, "gemini:$model:$style") { text, voice ->
            GeminiVoice.audio(Llm.speech(text, voice, model, style))
        }
    }

    private fun analyseAzure(sc: SScript) {
        val region = StudioPrefs.azureRegion
        val list = azureList().ifEmpty { fetchAzureVoices(region) }
        val ofLang = AzureSpeech.forLang(list, lang())
        if (ofLang.isEmpty()) error(t("У Azure нет голосов для этого языка", "Azure has no voices for this language"))
        val narrator = ofLang.firstOrNull { it.shortName == StudioPrefs.azureVoice } ?: ofLang.first()
        val cast = if (task == StudioTask.CARTOON) StudioPrefs.cast else emptyList()
        val used = mutableSetOf(narrator.shortName)
        val castVoices = castFemale(cast).map { f ->
            val pool = ofLang.filter { f == null || it.female == f }.ifEmpty { ofLang }
            (pool.firstOrNull { it.shortName !in used } ?: pool.first()).shortName.also { used += it }
        }
        val perScene = sc.scenes.map { s -> StudioToon.find(cast, s.who).let { if (it >= 0) castVoices[it] else narrator.shortName } }
        val tone = StudioPrefs.tone
        val degree = StudioPrefs.azureDegree
        analyseChunked(sc, "Azure", "az", perScene, "azure:$region:$tone:$degree:${StudioPrefs.rate}") { text, name ->
            val v = ofLang.first { it.shortName == name }
            val style = AzureSpeech.styleFor(tone, v.styles)
            val (r, p) = AzureSpeech.prosody(tone, StudioPrefs.rate, style != null)
            Studio.readWav(Llm.azureSpeech(AzureSpeech.ssml(text, v, lang(), style, degree, r, p), region, name, text.length))
                ?: error(t("Azure вернул не WAV", "Azure did not return a WAV"))
        }
    }

    /** Gender of each character's Edge voice (null = unknown). */
    private fun castFemale(cast: List<SChar>): List<Boolean?> =
        StudioToon.pickVoices(cast, voices, lang(), "").map { v -> voices.firstOrNull { it.shortName == v }?.let { it.gender == "Female" } }

    /**
     * Voices the script by chunks of neighbouring scenes with one voice (few requests), caching every chunk on disk
     * so an interrupted run continues; words are spread over the speech parts (these services give no word marks).
     */
    private fun analyseChunked(sc: SScript, who: String, prefix: String, perScene: List<String>, salt: String,
                               synth: (String, String) -> Pair<ShortArray, Int>) {
        val says = sc.scenes.map { it.say }
        val n = says.size
        val chunks = GeminiVoice.chunks(says, perScene)
        if (chunks.isEmpty()) error(t("Сценарий пустой", "The script is empty"))
        val tmp = File(cacheDir, "studio_tts").apply { mkdirs() }
        val keys = chunks.map { c -> prefix + StudioTtsCache.key(salt + "\n" + GeminiVoice.text(says, c), TtsOptions(c.voice)) }
        val chunks16 = mutableListOf<ShortArray>()
        val all = mutableListOf<SWord>()
        val rs = arrayOfNulls<IntRange>(n)
        var len = 0
        var rate = 24_000
        report("analyse", t("$who озвучивает 0/${chunks.size}", "$who is voicing 0/${chunks.size}"))
        chunks.forEachIndexed { k, c ->
            if (cancel) throw java.util.concurrent.CancellationException()
            // a chunk voiced in an earlier, interrupted attempt is taken from disk: no second request
            val f = File(tmp, keys[k] + ".wav")
            val (data0, r) = f.takeIf { it.length() > 44 }?.let { Studio.readWav(it.readBytes()) } ?: try {
                synth(GeminiVoice.text(says, c), c.voice).also { (p, pr) -> writeWav(f, p, pr) }
            } catch (e: LlmException) {
                throw LlmException(e.message.orEmpty() + t(" Озвучено кусков: $k из ${chunks.size}, они сохранены — «🔍 Разметить сцены» продолжит с остальных.",
                    " Chunks voiced: $k of ${chunks.size}, they are kept: \"🔍 Lay out scenes\" continues with the rest."), e.raw, e.code)
            }
            if (chunks16.isEmpty()) rate = r
            val data = if (r == rate) data0 else Studio.resample(data0, r, rate)
            val shift = Studio.durationMs(len, rate)
            val timing = voiceTiming(File(tmp, keys[k] + ".t.json"), says, c, data, rate, who, k, chunks.size)
            GeminiVoice.wordsTimed(says, c, data, rate, timing).forEachIndexed { j, ws ->
                val i = c.scenes[j]
                val first = all.size
                all += ws.map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) }
                rs[i] = first until all.size
            }
            chunks16 += data
            len += data.size
            val gap = Studio.silence(if (k < chunks.size - 1) 180 else 400, rate)
            chunks16 += gap
            len += gap.size
            report("analyse", t("$who озвучивает ${k + 1}/${chunks.size}", "$who is voicing ${k + 1}/${chunks.size}"))
        }
        // blank scenes own no words
        var at = 0
        val ranges0 = rs.map { r -> (r ?: (at until at)).also { at = it.last + 1 } }
        tmp.listFiles()?.filter { it.name.startsWith(prefix) && it.name.substringBefore('.') !in keys }?.forEach { it.delete() }
        if (all.isEmpty()) error(t("$who не вернул речь. Попробуйте другой голос.", "$who returned no speech. Try another voice."))
        val take = StudioAudio.Pcm(Studio.concat(chunks16), rate)
        pcm = take
        words = all
        script = sc
        ranges = ranges0
        stats = VoiceStats(take.ms, take.ms, 0, 0, all.size)
    }

    /**
     * The voice gives no word marks: an AI that hears audio (Gemini, or the free AIs through Gemini) tells
     * when every phrase of the chunk is said; the answer is kept next to the chunk. Null without such an AI,
     * with timing off or on an error — then the words are pinned to the pauses.
     */
    private fun voiceTiming(cache: File, says: List<String>, c: GeminiVoice.Chunk, pcm: ShortArray, rate: Int,
                            who: String, k: Int, n: Int): String? {
        if (!StudioPrefs.aiTiming) return null
        cache.takeIf { it.length() > 2 }?.let { return it.readText() }
        val prov = Provider.active()?.takeIf { it.kind == ApiKind.GEMINI || it == Provider.FREE }
            ?: Provider.GEMINI.takeIf { it.isConfigured } ?: return null
        val (_, flat, hard) = GeminiVoice.chunkWords(says, c)
        val ph = GeminiVoice.phrases(flat, hard)
        if (ph.size < 2) return null
        report("analyse", t("$who: ИИ слушает темп ${k + 1}/$n…", "$who: AI listens to the pace ${k + 1}/$n…"))
        return try {
            val pcm16 = Studio.resample(pcm, rate, StudioRecognizer.RATE)
            val body = java.nio.ByteBuffer.allocate(pcm16.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            body.asShortBuffer().put(pcm16)
            val wav = Studio.wavHeader(pcm16.size * 2, StudioRecognizer.RATE) + body.array()
            val r = Llm.complete(prov, prov.fastModel, "You time speech in audio. Answer with JSON only.",
                Prompts.fill("studio_tts_timing", mapOf("phrases" to GeminiVoice.phraseList(flat, ph))), json = true, maxTokens = 4000,
                task = "studio_tts_timing", pid = "studio", schema = GeminiVoice.TIMING_SCHEMA,
                image = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP), imageMime = "audio/wav")
            val ok = GeminiVoice.parseTiming(r.text, ph.size, pcm.size * 1000L / rate) != null
            if (ok) cache.writeText(r.text) else L.w("studio", "voice timing: the answer does not fit")
            r.text.takeIf { ok }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w("studio", "voice timing: ${e.message}")
            null
        }
    }

    /** Tokens of the last AI direction (in → out), to compare the ways. */
    @Volatile private var lastTokens: Pair<Int, Int>? = null

    private fun approachHelp(a: StudioApproach) = when (a) {
        StudioApproach.RULES -> t("⚡ Правила — без ИИ и токенов: цифры, шаги, сравнения, списки, цитаты и эмодзи по смыслу слов; зум и подсветка на главном слове.",
            "⚡ Rules: no AI, no tokens. Numbers, steps, comparisons, lists, quotes and emoji from the words; zoom and highlight on the key word.")
        StudioApproach.RHYTHM -> t("🥁 Ритм — без ИИ: сцены по паузам, зумы на самых громких словах и долях музыки, «бум» на пике, пульс картинки под голос.",
            "🥁 Rhythm: no AI. Scenes by pauses, zooms on the loudest words and music beats, a boom on the peak, the picture pulses with the voice.")
        StudioApproach.LITE -> t("💸 Эконом ИИ — быстрая модель и сжатый протокол: метки только в начале фраз, ответ короткими строками. В 3–5 раз меньше токенов.",
            "💸 Lite AI: the fast model and a compact protocol (marks only at clause starts, short string answers). 3–5 times fewer tokens.")
        StudioApproach.HYBRID -> t("🧩 Гибрид — черновик по правилам, а умная модель переделывает только ключевые сцены (хук, цифры, вывод).",
            "🧩 Hybrid: a rules draft; the smart model redoes only the key scenes (hook, numbers, ending).")
        StudioApproach.FULL -> t("🎬 Полный ИИ-режиссёр — подробный протокол: графика, фигуры на ключевых кадрах, B-roll, звуки, вырезка паразитов. Больше всего токенов.",
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

    /** An own way of editing (lumen.edit): the whole plan by its rules, without AI. */
    private fun animateEdit(e: EditTpl) {
        StudioPrefs.approach = "edit:" + e.id
        when (e.style) {
            "dark" -> StudioPrefs.style = StudioStyle.DARK.name
            "light" -> StudioPrefs.style = StudioStyle.LIGHT.name
            "explain" -> StudioPrefs.style = StudioStyle.EXPLAIN.name
        }
        directBy(StudioApproach.RULES, e)
    }

    /** Rules, rhythm, lite AI or hybrid: a plan without cutting the take. */
    private fun directBy(ap: StudioApproach, edit: EditTpl? = null) {
        val p0 = pcm ?: return
        val ws = words
        if (ws.isEmpty() || busy.isNotEmpty()) return
        val music = musicFile.takeIf { ap == StudioApproach.RHYTHM && it.exists() }
        val long = task == StudioTask.VIDEO || p0.ms > 180_000
        val cast = if (task == StudioTask.CARTOON) StudioPrefs.cast else emptyList()
        val roles = script?.let { sc -> ranges?.let { StudioToon.wordRoles(sc.scenes, it, ws.size, cast) } }
        val schemesBefore = schemeSpans()
        busy = "ai_direct"
        cancel = false
        render()
        job("ai_direct", {
            val d = if (edit != null) StudioEditTpl.direct(edit, ws, StudioRhythm.wordLoudness(ws, Studio.frameRms(p0.data, p0.rate))) else when (ap) {
                StudioApproach.RULES -> StudioRules.direct(ws)
                StudioApproach.RHYTHM -> {
                    val loud = StudioRhythm.wordLoudness(ws, Studio.frameRms(p0.data, p0.rate))
                    val beats = music?.let { f -> Studio.readWav(f.readBytes())?.let { (m, rate) -> StudioRhythm.onsets(m, rate) } } ?: emptyList()
                    StudioRhythm.direct(ws, loud, beats)
                }
                StudioApproach.LITE -> aiJson("studio_direct_lite", mapOf(
                    "format" to formatText(), "long" to if (long) ", в длинном ролике до 12 с" else "", "lang" to aiLang(),
                    "tpls" to StudioTplCatalog.kinds(motionTpls.values.toList()), "data" to StudioCompact.transcript(ws)
                ), "You are a video editor. Answer with compact JSON only.", if (ws.size > 600) 8000 else 3000, fast = true) { StudioCompact.parse(it, ws) }
                else -> {
                    val draft = StudioRules.direct(ws)
                    val n = maxOf(2, draft.scenes.size * 3 / 10)
                    runCatching {
                        aiJson("studio_direct_patch", mapOf(
                            "format" to formatText(), "n" to n.toString(), "lang" to aiLang(),
                            "tpls" to StudioTplCatalog.kinds(motionTpls.values.toList()), "data" to StudioCompact.listing(draft, ws)
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
            // info-schemes made before stay on the scenes with the same speech
            res.copy(script = res.script.copy(scenes = StudioScheme.carry(schemesBefore, res.script.scenes, res.ranges) { it }))
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
            if (ap.usesAi) lastTokens?.let { (i, o) -> toast(t("ИИ: $i → $o токенов", "AI: $i → $o tokens")) }
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
        val allowDrop = !synth() && StudioPrefs.dropFillers
        val oldCuts = cuts
        val format = formatText()
        val cast = if (task == StudioTask.CARTOON) StudioPrefs.cast else emptyList()
        val roles = script?.let { sc -> ranges?.let { StudioToon.wordRoles(sc.scenes, it, ws.size, cast) } }
        val cutsNow = if (task == StudioTask.CUTS) placeable() else emptyList()
        val vars = mapOf(
            "format" to format,
            "long" to if (task == StudioTask.VIDEO || p0.ms > 180_000) ", в длинном ролике до 12 секунд" else "",
            "visuals" to visuals() + StudioCuts.promptBlock(cutsNow, StudioPrefs.cutsLate),
            "drop" to if (allowDrop) "drop — номера слов-паразитов («эм», «ну», «короче», «как бы», «вот»), повторов и оговорок, которые нужно вырезать из записи. " +
                "Не трогай значимые слова и не вырезай больше 10% текста. Если вырезать нечего — пустой массив."
            else "drop — всегда пустой массив.",
            "lang" to aiLang(),
            "data" to StudioDirector.transcript(ws)
        )
        busy = "ai_direct"
        cancel = false
        render()
        val schemesBefore = schemeSpans()
        job("ai_direct", {
            val d = aiJson("studio_direct", vars, "You are a video editor and motion designer. Answer with JSON only.",
                if (ws.size > 600) 16_000 else 8000, arrayKey = "scenes") { StudioDirector.parse(it, ws.size, cutsNow.map { c -> c.id }.toSet()) }
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
            // info-schemes made before stay on the scenes with the same speech (word numbers follow the cut)
            ap = ap.copy(script = ap.script.copy(scenes = StudioScheme.carry(schemesBefore, ap.script.scenes, ap.ranges, StudioScheme.dropIndex(drop))))
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
            if (ap.dropped > 0) toast(t("ИИ-режиссёр вырезал слов: ${ap.dropped}", "The AI director cut ${ap.dropped} words"))
            else lastTokens?.let { (i, o) -> toast(t("ИИ: $i → $o токенов", "AI: $i → $o tokens")) }
            if (StudioPrefs.broll && StudioDirector.needBroll(ap.script).isNotEmpty()) fetchBroll() else autoFinish()
        }
    }

    // ---------- what goes over the picture: subtitles or info-schemes (lumen.overlay) ----------

    private fun overlayWhere() = StudioOverlayUi.Where(
        video = currentStyle() == StudioStyle.VIDEO, cartoon = task == StudioTask.CARTOON,
        schemes = script?.scenes?.count { it.scheme != null } ?: 0, working = busy.isNotEmpty(), inserts = task == StudioTask.CUTS)

    /** Look of the schemes in a montage (null = the colours of the video style, "auto"). */
    private fun schemeStyleOf(ov: StudioOverlay, style: StudioStyle): SchemeStyle? {
        if (ov.look == SchemeStyles.AUTO) return null
        // an info-block style works in every video style; the old looks only in the montage
        InfoStyles.look(ov.look, ov.schemePlace())?.let { return it }
        return if (style != StudioStyle.VIDEO) null else StudioOverlay.schemeLook(ov)
    }

    /** Key of the current script for [StudioPrefs.schemesTried]. */
    private fun schemesKey(): String = script?.let { sc -> task.id + ":" + sc.scenes.joinToString("|") { it.say }.hashCode() } ?: ""

    private fun setOverlay(n: StudioOverlay) {
        val was = StudioPrefs.over
        StudioPrefs.over = n
        board = emptyList()
        render()
        // the schemes are chosen: the AI composes them now (once per script), no separate button
        if (n.anySchemes() && !was.anySchemes()) composeIfNeeded(explicit = true)
    }

    private fun overlayBlock(c: Col) {
        val w = overlayWhere()
        StudioOverlayUi.block(c, StudioPrefs.over, w, { setOverlay(it) }) { overlaySheet() }
        c.progress("schemes", t("ИИ составляет инфоблоки…", "AI composes info-blocks…"))
    }

    private fun overlaySheet() {
        val have = script?.scenes?.count { it.scheme != null } ?: 0
        StudioOverlayUi.sheet(this, StudioPrefs.over, overlayWhere(), { n ->
            StudioPrefs.over = n
            board = emptyList()
            render()
        }) { dismiss ->
            add(text(ctx, t("Пожелание к инфоблокам (необязательно)", "A wish for the info-blocks (optional)"), Type.LABEL, bold = true, color = Palette.MUTED), 12)
            val wish = edit(t("например: больше чисел и сравнений, без пирамид", "e.g. more numbers and comparisons, no pyramids"), StudioPrefs.schemeWish)
            StudioOverlayUi.templateWish(this, this@StudioActivity, wish)
            if (script != null && hasAi()) button(if (have > 0) t("🔄 Составить инфоблоки заново", "🔄 Compose the info-blocks again") else t("🧩 Составить инфоблоки", "🧩 Compose the info-blocks"),
                style = BtnStyle.TONAL) {
                StudioPrefs.schemeWish = wish.text.toString().trim()
                dismiss()
                composeSchemes(StudioPrefs.schemeWish)
            }
            if (have > 0) button(t("Убрать инфоблоки ИИ ($have)", "Remove the AI info-blocks ($have)"), style = BtnStyle.TEXT) {
                StudioPrefs.schemeWish = wish.text.toString().trim()
                dismiss()
                removeSchemes()
            }
        }
    }

    private fun hasAi() = Provider.active() != null || Relay.mode != "off"

    /**
     * The schemes mode is on and the scenes have none: the AI composes them — at once when the user chose the
     * schemes ([explicit], a manual relay is fine then), before the build only with a connected AI.
     */
    private fun composeIfNeeded(explicit: Boolean, then: (() -> Unit)? = null): Boolean {
        val sc = script
        val ai = if (explicit) hasAi() else Provider.active() != null
        if (sc == null || busy.isNotEmpty() || currentPlan() == null ||
            !StudioOverlay.needCompose(StudioPrefs.over, ai, sc.scenes.count { it.scheme != null }, StudioPrefs.schemesTried, schemesKey())) return false
        StudioPrefs.schemesTried = schemesKey()
        composeSchemes(StudioPrefs.schemeWish, then)
        return true
    }

    /** Schemes of the current scenes with the words they cover: they survive a new direction ([StudioScheme.carry]). */
    private fun schemeSpans(): List<Pair<SScheme, IntRange>> {
        val sc = script ?: return emptyList()
        if (sc.scenes.none { it.scheme != null }) return emptyList()
        val times = currentPlan()?.times ?: return emptyList()
        return sc.scenes.indices.mapNotNull { i -> sc.scenes[i].scheme?.let { s -> times.firstOrNull { it.scene == i }?.words?.let { s to it } } }
    }

    private fun removeSchemes() {
        val cur = script ?: return
        script = cur.copy(scenes = cur.scenes.map { s ->
            if (s.scheme == null) s else s.copy(kind = if (s.items.isNotEmpty()) "icons" else "text", scheme = null)
        })
        saveAnalysis()
        board = emptyList()
        render()
    }

    /** The AI writes schemes for the scenes that need them; broken ones go back to it once with the list of problems. */
    /** [then] runs when the schemes are done or could not be made (not on cancel). */
    private fun composeSchemes(wish: String, then: (() -> Unit)? = null) {
        val sc0 = script ?: return run { then?.invoke() }
        val plan = currentPlan() ?: return run { then?.invoke() }
        if (busy.isNotEmpty()) return run { then?.invoke() }
        val ranges = sc0.scenes.indices.map { i -> plan.times.firstOrNull { it.scene == i }?.words ?: IntRange.EMPTY }
        val vars = mapOf(
            "format" to formatText(), "lang" to aiLang(), "max" to StudioPrefs.over.maxSchemes(sc0.scenes.size).toString(),
            "wish" to if (wish.isNotEmpty()) "Пожелание автора: $wish" else "", "protocol" to StudioScheme.PROTOCOL,
            "scenes" to StudioScheme.scenesBlock(sc0.scenes, words, ranges)
        )
        val system = "You are an infographic designer for videos. Answer with JSON only."
        busy = "schemes"
        cancel = false
        render()
        job("schemes", {
            val ok = HashMap<Int, SScheme>()
            val bad = mutableListOf<Pair<org.json.JSONObject, List<String>>>()
            fun take(list: List<Pair<Int, org.json.JSONObject>>) {
                bad.clear()
                for ((n, o) in list) {
                    val i = n - 1
                    if (i !in sc0.scenes.indices) continue
                    val s = StudioScheme.fromJson(o)
                    val pr = StudioScheme.problems(s, ranges[i])
                    if (s != null && pr.isEmpty()) ok[i] = StudioScheme.repair(s, ranges[i]) else bad += o to pr
                }
            }
            take(aiJson("studio_schemes", vars, system, 900 + sc0.scenes.size * 80) { StudioScheme.parseAnswer(it) })
            if (bad.isNotEmpty() && !cancel) {
                report("schemes", t("Исправляю инфоблоки: ${bad.size}", "Fixing info-blocks: ${bad.size}"))
                val errors = bad.joinToString("\n") { (o, pr) -> "Сцена ${o.optInt("scene")}: " + pr.joinToString("; ") }
                val json = org.json.JSONArray().apply { bad.forEach { put(it.first) } }.toString()
                runCatching {
                    aiJson("studio_scheme_fix", mapOf("errors" to errors, "protocol" to StudioScheme.PROTOCOL, "json" to json), system, 600 + bad.size * 400) {
                        StudioScheme.parseAnswer(it)
                    }
                }.onSuccess { take(it) }.onFailure { if (it is java.util.concurrent.CancellationException) throw it }
            }
            HashMap(ok) to bad.size
        }, { e ->
            busy = ""
            render()
            if (e !is java.util.concurrent.CancellationException) {
                if (then != null) then() else err(e)
            }
        }) { (found, failed) ->
            busy = ""
            val cur = script
            if (cur != null && cur.scenes.size == sc0.scenes.size) {
                script = cur.copy(scenes = cur.scenes.mapIndexed { i, s -> found[i]?.let { s.copy(kind = "scheme", scheme = it) } ?: s })
                saveAnalysis()
            }
            board = emptyList()
            render()
            toast(if (found.isEmpty()) t("ИИ не нашёл сцен для инфоблоков", "AI found no scenes for info-blocks")
            else t("Инфоблоков: ${found.size}", "Info-blocks: ${found.size}") + if (failed > 0) t(" · не удалось: $failed", " · failed: $failed") else "")
            then?.invoke()
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
        val video = StudioPrefs.brollVideo
        val times = currentPlan()?.times
        busy = "broll"
        cancel = false
        render()
        job("broll", {
            val found = java.util.concurrent.ConcurrentHashMap<Int, Pair<String, String>>()
            val ready = java.util.concurrent.atomic.AtomicInteger(0)
            val used = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            report("broll", t("B-roll 0/${need.size}", "B-roll 0/${need.size}"))
            Par.mapAll(need, 3) { _, i ->
                if (cancel) throw java.util.concurrent.CancellationException()
                val stamp = "b_${System.currentTimeMillis()}_$i"
                // real footage first (a clip not used in another scene), a photo when none plays
                val clip = if (!video) null else try {
                    val needMs = times?.firstOrNull { it.scene == i }?.let { it.endMs - it.startMs } ?: 4000L
                    StudioBroll.fetchClip(sc0.scenes[i].broll, asp, needMs, used, bdir, stamp) { cancel }
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w("studio", "clip: ${e.message}")
                    null
                }
                if (clip != null) {
                    used += clip.first.source + ":" + clip.first.id
                    found[i] = clip.second.absolutePath to StudioStock.credit(clip.first)
                    report("broll", t("B-roll ${ready.incrementAndGet()}/${need.size}", "B-roll ${ready.get()}/${need.size}"))
                    return@mapAll i
                }
                val f = File(bdir, "$stamp.jpg")
                val hit = try {
                    StudioBroll.fetch(sc0.scenes[i].broll, asp, true, f, maxSide) { cancel }
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w("studio", "broll: ${e.message}")
                    null
                }
                if (hit != null) found[i] = f.absolutePath to StudioBrollCatalog.credit(hit)
                report("broll", t("B-roll ${ready.incrementAndGet()}/${need.size}", "B-roll ${ready.get()}/${need.size}"))
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
            val nClips = found.values.count { StudioStock.isVideo(it.first) }
            toast(t("B-roll найден: ${found.size} из ${need.size} (клипов $nClips, фото ${found.size - nClips})",
                "B-roll found: ${found.size} of ${need.size} ($nClips clips, ${found.size - nClips} photos)"))
            if (autoFinishLater) {
                autoFinishLater = false
                autoFinish()
            }
        }
    }

    private var autoFinishLater = false

    /** Free keys of Pexels and Pixabay (Wikimedia Commons needs none); they stay on the phone. */
    private fun stockKeys() {
        bottomSheet(t("Видео-клипы для B-roll", "Video clips for B-roll"), {}) { dismiss ->
            muted(t("Без ключей клипы ищутся в Wikimedia Commons. С бесплатными ключами Pexels и Pixabay выбор намного больше. " +
                "Ключи хранятся только на телефоне, на сервер уходят только слова поиска.",
                "Without keys clips come from Wikimedia Commons. Free Pexels and Pixabay keys give far more choice. " +
                    "The keys stay on the phone; only the search words are sent."))
            listOf(Triple("Pexels", StudioStock.PEXELS_KEY, StudioStock.PEXELS_KEY_URL), Triple("Pixabay", StudioStock.PIXABAY_KEY, StudioStock.PIXABAY_KEY_URL))
                .forEach { (name, id, url) ->
                    val key = edit(if (KeyVault.has(id)) t("$name: ключ сохранён (вставьте новый, чтобы заменить)", "$name: key saved (paste a new one to replace)")
                    else t("Ключ $name", "$name key"))
                    row(
                        btn(ctx, t("Получить ключ", "Get a key"), style = BtnStyle.TEXT) { openUrl(url) },
                        btn(ctx, t("Сохранить", "Save"), style = BtnStyle.TONAL) {
                            val k = key.text.toString().trim()
                            if (k.length < 10) return@btn
                            KeyVault.put(id, k)
                            toast(t("Ключ $name сохранён", "$name key saved"))
                            dismiss()
                            render()
                        }
                    )
                }
        }
    }

    /** End of the "AI makes everything" chain: the video is built right away. */
    private fun autoFinish() {
        if (!autoAll) return
        autoAll = false
        podcastAuto = false
        // with «🧩 Инфосхемы» the AI composes them before the build (renderVideo does it)
        renderVideo()
    }

    // ---------- step 4: video ----------

    /** Starts the build in [StudioService]: it goes on with the screen closed or the phone locked. */
    private fun renderVideo() {
        val sc = script ?: return
        // «🧩 Инфосхемы» chosen and none made for this script yet: the AI composes them first, then the build goes on
        if (composeIfNeeded(explicit = false) { renderVideo() }) return
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


/**
 * "Clip quality" chips of every screen that downloads YouTube clips, and "⟳ Download again" for the [ready] clips
 * taken with a lower cap; [refresh] redraws the screen, [again] gets the ids to download again.
 */
internal fun clipQuality(c: Col, ready: List<SClip>, refresh: () -> Unit, again: (Set<String>) -> Unit) {
    val ctx = c.ctx
    val now = StudioPrefs.clipQ
    c.add(text(ctx, t("Качество клипов", "Clip quality"), Type.LABEL, bold = true, color = Palette.MUTED), 10)
    c.chips(StudioCuts.QUALITIES.map { q ->
        val label = if (q == 0) t("как экспорт (${StudioPrefs.quality}p)", "as export (${StudioPrefs.quality}p)") else if (q >= 1080) t("${q}p — лучшее", "${q}p — best") else "${q}p"
        chip(ctx, label, q == now) {
            if (q != now) {
                StudioPrefs.clipQ = q
                refresh()
            }
        }
    }, 99)
    val low = StudioCuts.belowCap(ready, StudioPrefs.clipCap)
    if (low.isNotEmpty()) c.button(t("⟳ Перекачать в ${StudioPrefs.clipCap}p (${low.size})", "⟳ Download again in ${StudioPrefs.clipCap}p (${low.size})"),
        style = BtnStyle.TEXT) { again(low.map { it.id }.toSet()) }
}
