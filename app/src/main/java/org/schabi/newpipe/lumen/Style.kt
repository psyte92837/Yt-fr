/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.Frameset
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pure style math: subtitles timing, title/description patterns, frame colours and cuts (covered by unit tests). */
object StyleMath {
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    data class Speech(
        val words: Int,
        val wpm: Int,
        val speechShare: Double,
        val longPauses: Int,
        val questions: Int,
        val firstSpeechMs: Long,
        val hook: String
    )

    data class Frame(val luma: Int, val sat: Int, val rgb: Int, val sig: IntArray)

    private val timeLine = Regex("""^((?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3})\s+-->\s+((?:\d{1,2}:)?\d{2}:\d{2}[.,]\d{3}).*$""")
    private val tag = Regex("<[^>]+>")

    fun parseTime(s: String): Long {
        val main = s.trim().replace(',', '.')
        val ms = main.substringAfter('.', "0").padEnd(3, '0').take(3).toLongOrNull() ?: 0
        val p = main.substringBefore('.').split(':').mapNotNull { it.toLongOrNull() }
        val sec = when (p.size) {
            3 -> p[0] * 3600 + p[1] * 60 + p[2]
            2 -> p[0] * 60 + p[1]
            1 -> p[0]
            else -> 0
        }
        return sec * 1000 + ms
    }

    /** WebVTT → cues with timings; drops styling tags and the rolling duplicates of YouTube auto-captions. */
    fun cues(vtt: String): List<Cue> {
        val out = mutableListOf<Cue>()
        val seen = ArrayDeque<String>()
        var start = -1L
        var end = -1L
        val buf = mutableListOf<String>()
        fun flush() {
            if (start >= 0 && buf.isNotEmpty()) out.add(Cue(start, max(end, start), buf.joinToString(" ")))
            buf.clear()
        }
        for (raw in vtt.lineSequence()) {
            val line = raw.trim()
            val m = timeLine.matchEntire(line)
            if (m != null) {
                flush()
                start = parseTime(m.groupValues[1])
                end = parseTime(m.groupValues[2])
                continue
            }
            if (line.isEmpty() || line == "WEBVTT" || line.startsWith("Kind:") || line.startsWith("Language:") ||
                line.startsWith("NOTE") || line.startsWith("STYLE") || start < 0
            ) {
                continue
            }
            val text = tag.replace(line, "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", "\"").trim()
            if (text.isEmpty() || seen.contains(text)) continue
            seen.addLast(text)
            if (seen.size > 4) seen.removeFirst()
            buf.add(text)
        }
        flush()
        return out
    }

    fun words(s: String): Int = s.split(Regex("""[\s\p{Punct}«»—–]+""")).count { it.any(Char::isLetterOrDigit) }

    /** Speaking pace, share of time with speech, pauses over 2 s and the hook (text of the first 30 s). */
    fun speech(cues: List<Cue>, durationSec: Long): Speech? {
        if (cues.isEmpty()) return null
        val sorted = cues.sortedBy { it.startMs }
        var speechMs = 0L
        var pauses = 0
        var curS = sorted[0].startMs
        var curE = sorted[0].endMs
        for (c in sorted.drop(1)) {
            if (c.startMs <= curE) {
                curE = max(curE, c.endMs)
            } else {
                if (c.startMs - curE > 2000) pauses++
                speechMs += curE - curS
                curS = c.startMs
                curE = c.endMs
            }
        }
        speechMs += curE - curS
        val total = sorted.sumOf { words(it.text) }
        val durMs = if (durationSec > 0) durationSec * 1000 else sorted.last().endMs
        return Speech(
            words = total,
            wpm = if (speechMs > 0) (total * 60000.0 / speechMs).roundToInt() else 0,
            speechShare = if (durMs > 0) min(1.0, speechMs.toDouble() / durMs) else 0.0,
            longPauses = pauses,
            questions = sorted.sumOf { c -> c.text.count { it == '?' } },
            firstSpeechMs = sorted[0].startMs,
            hook = sorted.filter { it.startMs < 30000 }.joinToString(" ") { it.text }.take(400)
        )
    }

    /** Title pattern: length, words, number, question, brackets, CAPS words, emoji, exclamation. */
    fun titleTags(title: String): List<String> {
        val s = title.trim()
        val tags = mutableListOf<String>()
        if (Regex("""\d""").containsMatchIn(s)) tags += "number"
        if (s.contains('?')) tags += "question"
        if (s.contains('!')) tags += "exclaim"
        if (Regex("""[(\[【「|]""").containsMatchIn(s)) tags += "brackets"
        if (s.split(Regex("""\s+""")).any { w -> w.length >= 3 && w.any(Char::isLetter) && w.filter(Char::isLetter).all(Char::isUpperCase) }) tags += "caps"
        if (hasEmoji(s)) tags += "emoji"
        if (Regex("""(?iu)^(как|почему|зачем|что|how|why|what)(?![\p{L}\d])""").containsMatchIn(s)) tags += "how_why"
        if (Regex("""(?iu)(^|[^\p{L}\d])(vs|против|или)([^\p{L}\d]|$)""").containsMatchIn(s)) tags += "versus"
        return tags
    }

    private fun hasEmoji(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            if (cp >= 0x1F300 || cp in 0x2600..0x27BF) return true
            i += Character.charCount(cp)
        }
        return false
    }

    fun timecodes(desc: String): Int = desc.lineSequence().count { Regex("""^\s*\(?(\d{1,2}:)?\d{1,2}:\d{2}\)?\s""").containsMatchIn(it) }
    fun links(desc: String): Int = Regex("""https?://\S+""").findAll(desc).count()
    fun hashtags(desc: String): Int = Regex("""(^|\s)#[\p{L}\d_]+""").findAll(desc).count()

    /** Mean colour, brightness (0–255), saturation (0–100) and an 8×8 brightness signature of one frame. */
    fun frame(px: IntArray, w: Int, h: Int): Frame {
        var r = 0L
        var g = 0L
        var b = 0L
        var sat = 0L
        val cell = LongArray(64)
        val cnt = IntArray(64)
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val cr = (c shr 16) and 0xFF
            val cg = (c shr 8) and 0xFF
            val cb = c and 0xFF
            r += cr
            g += cg
            b += cb
            val mx = max(cr, max(cg, cb))
            val mn = min(cr, min(cg, cb))
            if (mx > 0) sat += (mx - mn) * 100L / mx
            val i = (y * 8 / h) * 8 + (x * 8 / w)
            cell[i] += (299L * cr + 587L * cg + 114L * cb) / 1000
            cnt[i]++
        }
        val n = max(1, w * h)
        val mr = (r / n).toInt()
        val mg = (g / n).toInt()
        val mb = (b / n).toInt()
        return Frame(
            luma = (299 * mr + 587 * mg + 114 * mb) / 1000,
            sat = (sat / n).toInt(),
            rgb = (mr shl 16) or (mg shl 8) or mb,
            sig = IntArray(64) { if (cnt[it] > 0) (cell[it] / cnt[it]).toInt() else 0 }
        )
    }

    fun diff(a: Frame, b: Frame): Int = (0 until 64).sumOf { abs(a.sig[it] - b.sig[it]) } / 64

    /** Scene changes between neighbouring storyboard frames. */
    fun cuts(frames: List<Frame>, threshold: Int = 28): Int = frames.zipWithNext().count { (a, b) -> diff(a, b) > threshold }

    /** Dominant colours: 3 bits per channel, colours with at least [minShare] of the pixels, most frequent first. */
    fun palette(px: IntArray, n: Int = 5, minShare: Double = 0.03): List<Int> {
        if (px.isEmpty()) return emptyList()
        val bins = IntArray(512)
        for (c in px) bins[(((c shr 21) and 7) shl 6) or (((c shr 13) and 7) shl 3) or ((c shr 5) and 7)]++
        return bins.indices.filter { bins[it] >= px.size * minShare }.sortedByDescending { bins[it] }.take(n).map { i ->
            ((((i shr 6) and 7) * 32 + 16) shl 16) or ((((i shr 3) and 7) * 32 + 16) shl 8) or ((i and 7) * 32 + 16)
        }
    }

    fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

    fun lumaLabel(l: Int) = when {
        l < 85 -> t("тёмный", "dark")
        l > 170 -> t("светлый", "bright")
        else -> t("средний", "medium")
    }

    fun satLabel(s: Int) = when {
        s < 25 -> t("приглушённые цвета", "muted colours")
        s > 55 -> t("насыщенные цвета", "vivid colours")
        else -> t("умеренные цвета", "moderate colours")
    }
}

/** Measured style passport of one video (no AI). Stored in style_<videoId>.json. */
data class StyleMetrics(
    val videoId: String,
    val duration: Long = -1,
    val isShort: Boolean = false,
    val chapters: List<Pair<Int, String>> = emptyList(),
    val wpm: Int = 0,
    val words: Int = 0,
    val speechShare: Double = 0.0,
    val longPauses: Int = 0,
    val questions: Int = 0,
    val firstSpeechMs: Long = -1,
    val hook: String = "",
    val subtitleSource: String = "", // manual | auto | ""
    val frames: Int = 0,
    val frameStepMs: Long = 0,
    val cuts: Int = 0,
    val luma: Int = -1,
    val sat: Int = -1,
    val palette: List<String> = emptyList(),
    val thumbLuma: Int = -1,
    val thumbSat: Int = -1,
    val thumbPalette: List<String> = emptyList(),
    val titleLen: Int = 0,
    val titleTags: List<String> = emptyList(),
    val timecodes: Int = 0,
    val links: Int = 0,
    val hashtags: Int = 0,
    val at: Long = System.currentTimeMillis()
) {
    /** Approximate scene changes per minute, from the storyboard. */
    val cutsPerMin: Double get() = if (frames > 1 && frameStepMs > 0) cuts * 60000.0 / ((frames - 1) * frameStepMs) else 0.0

    val avgShotSec: Double get() = if (cuts > 0 && frames > 1) (frames - 1) * frameStepMs / 1000.0 / (cuts + 1) else 0.0

    fun toJson(): JSONObject = JSONObject().put("videoId", videoId).put("duration", duration).put("isShort", isShort)
        .put("chapters", JSONArray().also { a -> chapters.forEach { a.put(JSONObject().put("s", it.first).put("t", it.second)) } })
        .put("wpm", wpm).put("words", words).put("speechShare", speechShare).put("longPauses", longPauses).put("questions", questions)
        .put("firstSpeechMs", firstSpeechMs).put("hook", hook).put("subtitleSource", subtitleSource)
        .put("frames", frames).put("frameStepMs", frameStepMs).put("cuts", cuts).put("luma", luma).put("sat", sat)
        .put("palette", palette.toJsonArray()).put("thumbLuma", thumbLuma).put("thumbSat", thumbSat).put("thumbPalette", thumbPalette.toJsonArray())
        .put("titleLen", titleLen).put("titleTags", titleTags.toJsonArray()).put("timecodes", timecodes).put("links", links)
        .put("hashtags", hashtags).put("at", at)

    /** Readable lines for the screen and for the AI prompt. */
    fun lines(): List<String> = buildList {
        add("⏱ " + Fmt.duration(duration) + (if (isShort) " · Shorts" else "") +
            if (chapters.isNotEmpty()) t(" · глав: ", " · chapters: ") + chapters.size else t(" · без глав", " · no chapters"))
        if (frames > 1) {
            add(t("✂ Смена сцен ≈ ", "✂ Scene changes ≈ ") + String.format("%.1f", cutsPerMin) + t("/мин", "/min") +
                (if (avgShotSec > 0) t(" · средний план ≈ ", " · average shot ≈ ") + String.format("%.0f", avgShotSec) + t(" с", " s") else "") +
                t(" (по раскадровке, шаг ", " (storyboard, step ") + String.format("%.0f", frameStepMs / 1000.0) + t(" с)", " s)"))
        }
        if (luma >= 0) add("🎨 " + t("Видеоряд: ", "Footage: ") + StyleMath.lumaLabel(luma) + ", " + StyleMath.satLabel(sat) + " · " + palette.joinToString(" "))
        if (thumbLuma >= 0) add("🖼 " + t("Обложка: ", "Thumbnail: ") + StyleMath.lumaLabel(thumbLuma) + ", " + StyleMath.satLabel(thumbSat) + " · " + thumbPalette.joinToString(" "))
        if (words > 0) {
            add("🗣 " + wpm + t(" слов/мин · речь ", " words/min · speech ") + (speechShare * 100).roundToInt() + "%" +
                t(" времени · пауз >2 с: ", " of time · pauses >2 s: ") + longPauses + t(" · вопросов: ", " · questions: ") + questions +
                (if (subtitleSource.isNotEmpty()) " · $subtitleSource" else ""))
            if (firstSpeechMs >= 0) add(t("⚡ Речь с ", "⚡ Speech from ") + String.format("%.1f", firstSpeechMs / 1000.0) + t(" с", " s"))
        } else {
            add(t("🗣 Субтитров нет — темп речи не посчитан", "🗣 No subtitles — speech pace unknown"))
        }
        add("🔤 " + t("Заголовок: ", "Title: ") + titleLen + t(" симв.", " chars") + if (titleTags.isNotEmpty()) " · " + titleTags.joinToString(", ") { tagLabel(it) } else "")
        add("📄 " + t("Описание: таймкодов ", "Description: timecodes ") + timecodes + t(" · ссылок ", " · links ") + links + t(" · хэштегов ", " · hashtags ") + hashtags)
        if (chapters.isNotEmpty()) add("📑 " + chapters.take(12).joinToString(" · ") { Fmt.duration(it.first.toLong()) + " " + it.second.take(40) })
    }

    companion object {
        fun tagLabel(k: String) = when (k) {
            "number" -> t("число", "number")
            "question" -> t("вопрос", "question")
            "exclaim" -> "!"
            "brackets" -> t("скобки", "brackets")
            "caps" -> "CAPS"
            "emoji" -> "emoji"
            "how_why" -> t("как/почему", "how/why")
            "versus" -> "vs"
            else -> k
        }

        fun from(o: JSONObject) = StyleMetrics(
            videoId = o.optString("videoId"),
            duration = o.optLong("duration", -1),
            isShort = o.optBoolean("isShort"),
            chapters = o.optJSONArray("chapters")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { c -> c.optInt("s") to c.optString("t") } } } ?: emptyList(),
            wpm = o.optInt("wpm"), words = o.optInt("words"), speechShare = o.optDouble("speechShare", 0.0),
            longPauses = o.optInt("longPauses"), questions = o.optInt("questions"), firstSpeechMs = o.optLong("firstSpeechMs", -1),
            hook = o.optString("hook"), subtitleSource = o.optString("subtitleSource"),
            frames = o.optInt("frames"), frameStepMs = o.optLong("frameStepMs"), cuts = o.optInt("cuts"),
            luma = o.optInt("luma", -1), sat = o.optInt("sat", -1), palette = o.optJSONArray("palette").strings(),
            thumbLuma = o.optInt("thumbLuma", -1), thumbSat = o.optInt("thumbSat", -1), thumbPalette = o.optJSONArray("thumbPalette").strings(),
            titleLen = o.optInt("titleLen"), titleTags = o.optJSONArray("titleTags").strings(),
            timecodes = o.optInt("timecodes"), links = o.optInt("links"), hashtags = o.optInt("hashtags"), at = o.optLong("at")
        )

        fun load(pid: String, vid: String): StyleMetrics? = Store.readFile(pid, "style_$vid.json")?.let {
            try {
                from(JSONObject(it))
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Video style detection, run only on the user's request (Share → "Video style", pasted link, found video):
 * local metrics from the storyboard, chapters, subtitles, title and thumbnail, then one AI call.
 * With Gemini the model watches the video itself (low media resolution to save tokens).
 */
/** What [VideoStyle.measure] also keeps for a Studio template: some storyboard frames, the subtitles and the cut times. */
class StyleExtra(val want: Int = 12) {
    val frames = mutableListOf<Pair<Long, Bitmap>>()
    var vtt = ""
    var cutMs: List<Long> = emptyList()
}

object VideoStyle {
    private val yt get() = ServiceList.YouTube
    private const val MAX_PAGES = 8
    private const val MAX_FRAMES = 300

    fun latest(pid: String, vid: String): AiResult? = Store.latestAi(pid, "video_style", vid)

    /** Step 1, no AI: measures the video and saves style_<id>.json. */
    fun measure(pid: String?, v: Video, subLang: String = "auto", progress: (String) -> Unit = {}, extra: StyleExtra? = null): StyleMetrics {
        progress(t("Метаданные и главы…", "Metadata and chapters…"))
        val info = StreamInfo.getInfo(yt, v.url)
        YouTube.applyInfo(v, info)
        if (pid != null) Store.updateVideo(pid, v.id) { s ->
            if (v.title.isNotBlank()) s.title = v.title
            if (v.views >= 0) s.views = v.views
            if (v.duration > 0) s.duration = v.duration
            if (v.uploadMs > 0) s.uploadMs = v.uploadMs
            if (v.likes >= 0) s.likes = v.likes
            s.description = v.description
            s.tags = v.tags
            s.category = v.category
            if (v.subscribers >= 0) s.subscribers = v.subscribers
            if (s.thumb.isEmpty()) s.thumb = v.thumb
        }
        val chapters = (info.streamSegments ?: emptyList()).map { it.startTimeSeconds to (it.title ?: "") }

        progress(t("Субтитры с таймингами…", "Timed subtitles…"))
        var speech: StyleMath.Speech? = null
        var subSource = ""
        try {
            val sub = YouTube.subtitleFor(info.subtitles, subLang)
            if (sub != null) {
                val raw = NewPipe.getDownloader().get(sub.content).responseBody()
                if (raw.trimStart().startsWith("WEBVTT")) {
                    speech = StyleMath.speech(StyleMath.cues(raw), info.duration)
                    subSource = if (sub.isAutoGenerated) "auto" else "manual"
                    extra?.vtt = raw
                    if (pid != null && Store.transcript(pid, v.id).isNullOrBlank()) {
                        Vtt.toText(raw).takeIf { it.isNotBlank() }?.let {
                            Store.saveTranscript(pid, v.id, it)
                            Store.updateVideo(pid, v.id) { s ->
                                s.hasTranscript = true
                                s.transcriptSource = subSource
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            L.w("style", "subtitles ${v.id}: ${e.message}")
        }

        progress(t("Раскадровка: цвета и смена сцен…", "Storyboard: colours and scene changes…"))
        val frames = mutableListOf<StyleMath.Frame>()
        val framePx = mutableListOf<Int>()
        var step = 0L
        try {
            pickFrameset(info.previewFrames ?: emptyList())?.let { fs ->
                step = fs.durationPerFrame.toLong()
                val perPage = fs.framesPerPageX * fs.framesPerPageY
                val keepEvery = if (extra == null) 0 else maxOf(1, minOf(fs.totalCount, MAX_FRAMES) / extra.want)
                for ((pi, url) in fs.urls.take(MAX_PAGES).withIndex()) {
                    val bmp = bitmap(url, 1) ?: continue
                    for (k in 0 until perPage) {
                        if (pi * perPage + k >= fs.totalCount || frames.size >= MAX_FRAMES) break
                        val x = (k % fs.framesPerPageX) * fs.frameWidth
                        val y = (k / fs.framesPerPageX) * fs.frameHeight
                        if (x + fs.frameWidth > bmp.width || y + fs.frameHeight > bmp.height) continue
                        val px = IntArray(fs.frameWidth * fs.frameHeight)
                        bmp.getPixels(px, 0, fs.frameWidth, x, y, fs.frameWidth, fs.frameHeight)
                        if (extra != null && (pi * perPage + k) % keepEvery == 0 && extra.frames.size < extra.want) {
                            extra.frames += (pi * perPage + k) * step to Bitmap.createBitmap(bmp, x, y, fs.frameWidth, fs.frameHeight)
                        }
                        frames.add(StyleMath.frame(px, fs.frameWidth, fs.frameHeight))
                        if (frames.size % 3 == 0) for (j in px.indices step 7) framePx.add(px[j])
                    }
                    bmp.recycle()
                }
            }
        } catch (e: Exception) {
            L.w("style", "storyboard ${v.id}: ${e.message}")
        }

        progress(t("Обложка…", "Thumbnail…"))
        var thumb: StyleMath.Frame? = null
        var thumbPal = emptyList<String>()
        try {
            val url = ThumbUrl.mini(v.id, v.thumb).replace("mqdefault", "hqdefault")
            bitmap(url, 1)?.let { b ->
                val px = IntArray(b.width * b.height)
                b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                thumb = StyleMath.frame(px, b.width, b.height)
                thumbPal = StyleMath.palette(px).map(StyleMath::hex)
                b.recycle()
            }
        } catch (e: Exception) {
            L.w("style", "thumbnail ${v.id}: ${e.message}")
        }

        val desc = v.description
        val m = StyleMetrics(
            videoId = v.id,
            duration = if (info.duration > 0) info.duration else v.duration,
            isShort = v.isShort || info.url.contains("/shorts/"),
            chapters = chapters,
            wpm = speech?.wpm ?: 0, words = speech?.words ?: 0, speechShare = speech?.speechShare ?: 0.0,
            longPauses = speech?.longPauses ?: 0, questions = speech?.questions ?: 0, firstSpeechMs = speech?.firstSpeechMs ?: -1,
            hook = speech?.hook ?: "", subtitleSource = subSource,
            frames = frames.size, frameStepMs = step, cuts = StyleMath.cuts(frames),
            luma = if (frames.isEmpty()) -1 else frames.sumOf { it.luma } / frames.size,
            sat = if (frames.isEmpty()) -1 else frames.sumOf { it.sat } / frames.size,
            palette = StyleMath.palette(framePx.toIntArray()).map(StyleMath::hex),
            thumbLuma = thumb?.luma ?: -1, thumbSat = thumb?.sat ?: -1, thumbPalette = thumbPal,
            titleLen = v.title.length, titleTags = StyleMath.titleTags(v.title),
            timecodes = StyleMath.timecodes(desc), links = StyleMath.links(desc), hashtags = StyleMath.hashtags(desc)
        )
        extra?.cutMs = frames.zipWithNext().mapIndexedNotNull { i, (a, b) -> if (StyleMath.diff(a, b) > 28) (i + 1) * step else null }
        if (pid != null) Store.writeFile(pid, "style_${v.id}.json", m.toJson().toString())
        L.i("style", "measured ${v.id}: frames=${m.frames} cuts=${m.cuts} wpm=${m.wpm} chapters=${chapters.size}")
        return m
    }

    /** The storyboard with the fewest pages that still has frames of at least 80 px. */
    private fun pickFrameset(list: List<Frameset>): Frameset? {
        val ok = list.filter { it.urls.isNotEmpty() && it.framesPerPageX > 0 && it.framesPerPageY > 0 && it.durationPerFrame > 0 }
        return ok.filter { it.frameWidth >= 80 }.minByOrNull { it.frameWidth } ?: ok.maxByOrNull { it.frameWidth }
    }

    private fun bitmap(url: String, sample: Int): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        return try {
            c.inputStream.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        } finally {
            c.disconnect()
        }
    }

    /** Step 2: one AI call. Gemini watches the video; other providers work from the metrics and the transcript. */
    fun ai(p: Project, v: Video, m: StyleMetrics, force: Boolean = false): AiResult {
        val prov = AiTasks.requireProvider()
        val watch = prov == Provider.GEMINI && v.url.isNotBlank() && (m.duration in 1..3 * 3600)
        val data = buildString {
            append("Title: ").append(v.title).append('\n')
            append("Channel: ").append(v.channel).append(" · views ").append(v.views).append(" · ").append(Fmt.date(v.uploadMs)).append('\n')
            append("Measured metrics (approximate):\n")
            m.lines().forEach { append("- ").append(it).append('\n') }
            if (m.hook.isNotBlank()) append("First 30 s of speech: ").append(m.hook).append('\n')
            append("Description: ").append(v.description.take(1200)).append('\n')
            if (!watch) {
                Store.transcript(p.id, v.id)?.takeIf { it.isNotBlank() }?.let { append("Transcript with timecodes (start):\n").append(it.take(9000)).append('\n') }
            }
        }
        val mode = if (watch) {
            "You can watch the attached video: base the style on what you see and hear."
        } else {
            "You cannot see the video: rely on the metrics and transcript, write about visuals only what follows from them and mark guesses as guesses."
        }
        val prompt = Prompts.get("video_style").replace("{data}", data).replace("{mode}", mode)
            .replace("{lang}", if (Settings.lang == "en") "English" else "русский")
        val model = if (watch) prov.fastModel else prov.smartModel
        val key = sha1("video_style|${v.id}|$model|${prompt.hashCode()}")
        if (!force) Store.ai(p.id, key)?.let { return it }
        val r = Llm.complete(prov, model, Prompts.SYSTEM, prompt, fileUri = if (watch) v.url else null,
            maxTokens = 6000, task = "video_style", pid = p.id, lowMedia = watch)
        return AiResult(key, "video_style", v.id, r.model + if (watch) " · 👁" else "", r.text, r.inTokens, r.outTokens).also { Store.saveAi(p.id, it) }
    }

    /** Full run for one video (background service): metrics, then AI if connected. Returns a short note. */
    fun run(pid: String, vid: String, force: Boolean = true, progress: (String) -> Unit = {}): String {
        val p = Store.project(pid) ?: return ""
        val v = Store.videos(pid)[vid] ?: return ""
        val m = measure(pid, v, p.collect.subLang, progress)
        if (Provider.active() == null) return t("Метрики готовы. Подключите AI для описания стиля.", "Metrics ready. Connect AI for the style description.")
        progress(t("AI описывает стиль…", "AI describes the style…"))
        ai(p, Store.videos(pid)[vid] ?: v, m, force)
        return t("Стиль определён", "Style detected")
    }
}

/** Rough token estimate for the style call: Gemini at low media resolution ≈ 100 tokens per second of video. */
fun styleTokenEstimate(durationSec: Long, gemini: Boolean): Int =
    (if (gemini && durationSec > 0) durationSec * 100 else 3000L).toInt() + 3500

/**
 * "Video style" block: measured metrics, AI description and the run button. Used on the video screen and in the overview.
 * [full] = whole AI text; otherwise a preview that opens the full text on tap. [onFinish] re-renders the screen when the run ends.
 */
fun Activity.styleCard(c: Col, pid: String, v: Video, full: Boolean, onFinish: () -> Unit) {
    c.h2("🎨 " + t("Стиль видео", "Video style"))
    val m = StyleMetrics.load(pid, v.id)
    val ai = VideoStyle.latest(pid, v.id)
    val running = CollectState.running && CollectState.kind == "style" && CollectState.projectId == pid
    if (m == null && ai == null && !running) {
        c.muted(t("Определяется только по запросу: раскадровка, главы, темп речи, обложка и описание монтажа через AI.",
            "Runs only on request: storyboard, chapters, speech pace, thumbnail and an AI description of the editing."))
    }
    if (m != null) c.card { m.lines().forEach { add(text(ctx, it, 13f), 2) } }
    if (ai != null) {
        val body = if (full || ai.text.length <= 1500) ai.text else ai.text.take(1500) + "…"
        c.card {
            add(text(ctx, body, 14f).apply { setTextIsSelectable(full) }, 0)
            muted(ai.model + " · " + Fmt.dateTime(ai.at) + " · " + Fmt.num((ai.inTokens + ai.outTokens).toLong()) + t(" ток.", " tok"))
        }.also { card -> if (!full) card.setOnClickListener { showText(t("Стиль видео", "Video style"), ai.text) } }
        c.button(t("Поделиться шаблоном", "Share the template"), style = BtnStyle.TEXT) {
            shareText(v.title, v.title + "\n" + v.url + "\n\n" + (m?.lines()?.joinToString("\n")?.plus("\n\n") ?: "") + ai.text)
        }
    }
    if (running) {
        val (pv, bar) = c.working(CollectState.current, bold = false)
        val tick = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                if (CollectState.running && CollectState.kind == "style") {
                    pv.text = CollectState.current
                    bar.showProgress(CollectState.current)
                    pv.postDelayed(this, 1000)
                } else {
                    if (CollectState.lastError.isNotEmpty()) toast(CollectState.lastError) else if (CollectState.lastResult.isNotEmpty()) toast(CollectState.lastResult)
                    onFinish()
                }
            }
        }
        pv.postDelayed(tick, 1000)
        return
    }
    if (v.url.isBlank() || isLocalSource(v.id)) return
    c.button(if (m == null && ai == null) t("🎨 Определить стиль видео", "🎨 Detect video style") else t("Обновить стиль", "Refresh style"), style = BtnStyle.TONAL) {
        if (CollectState.running) return@button toast(t("Идёт другая задача — попробуйте позже", "Another task is running — try later"))
        val prov = Provider.active()
        val msg = when {
            prov == null -> t("AI не подключён: будут только метрики (раскадровка, речь, обложка), без описания стиля.",
                "AI is not connected: metrics only (storyboard, speech, thumbnail), no style description.")
            prov == Provider.GEMINI -> t("Gemini посмотрит видео. Примерно ", "Gemini will watch the video. About ") +
                Fmt.num(styleTokenEstimate(v.duration, true).toLong()) + t(" токенов.", " tokens.")
            else -> t("Модель не видит видео и опишет стиль по метрикам и расшифровке. Примерно ", "The model can't see the video and will work from the metrics and transcript. About ") +
                Fmt.num(styleTokenEstimate(v.duration, false).toLong()) + t(" токенов + расшифровка.", " tokens + transcript.")
        }
        confirm(t("Определить стиль видео?", "Detect the video style?"), msg, t("Запустить", "Start")) {
            CollectState.current = t("Подготовка…", "Preparing…")
            CollectService.startStyle(this, pid, v.id)
            c.root.postDelayed({ onFinish() }, 800)
        }
    }
}
