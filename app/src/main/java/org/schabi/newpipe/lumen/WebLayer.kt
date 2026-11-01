/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * The second renderer (protocol lumen.canvas v1): an AI — the built-in one or any chat through [Relay] — writes
 * JavaScript `frame(t, ctx, W, H, data)` that draws one transparent layer for time t; [WebLayerActivity] runs it in a
 * WebView without network (CSP + blocked loads, no timers, seeded Math.random, Date bound to t), takes every frame as
 * PNG and lays it over a finished video. Pure text helpers here (unit-tested in WebLayerTest) and [FrameEncoder].
 */
object WebLayer {
    const val MAX_CODE = 60_000
    /** Short side of the layer canvas by default: sharp enough on a phone, ~2× faster than 1080. */
    const val FAST_SHORT = 720

    val PROTOCOL = """
lumen.canvas v1 — слой анимации поверх готового видео, который рисует твой JavaScript.
Lumen вызывает frame(t, ctx, W, H, data) для каждого кадра (30 в секунду) на прозрачном canvas W×H и накладывает его на видео.
• t — секунды от начала ролика. Функция ЧИСТАЯ: картинка зависит только от t (никакого накопленного состояния между вызовами).
• Canvas очищается перед каждым кадром, трансформации и прозрачность сброшены. Рисуй только слой: плашки, титры, стрелки, рамки, счётчики, прогресс, подсветку слов. Видео под слоем уже есть — не заливай весь кадр непрозрачным.
• data = {duration, w, h, title, lang, accent, words:[{w,s,e}] — слова речи с секундами начала/конца, scenes:[{title,s,e}]}. Слов может не быть.
• Помощники (уже объявлены): clamp(x,a,b), lerp(a,b,x), prog(t,a,b) → 0..1, ease.in/out/inOut/back/elastic(x), rand(i) → 0..1 для числа i, roundRect(ctx,x,y,w,h,r) (только путь — потом fill/stroke), wordAt(t) → индекс слова или -1, fitFont(ctx,text,maxW,size,weight) → размер шрифта, чтобы текст влез.
• Необязательно: setup(data) — вызывается один раз до первого кадра (подготовить массивы, цвета). Верни из frame false, если в этом кадре рисовать нечего — так быстрее.
• Нельзя: сеть, внешние скрипты, картинки и шрифты по URL, DOM кроме данного ctx; таймеры и requestAnimationFrame не работают. Math.random детерминирован в пределах кадра, Date.now() = t·1000.
• Шрифты системные: 'sans-serif', 'serif', 'monospace', 'cursive'; жирность 400–900; эмодзи можно.
• Безопасные зоны: в вертикальном кадре не рисуй в нижних 18% и правых 12% (интерфейс Shorts/Reels/TikTok). Субтитры могут уже быть в видео — это уточняется в задании.
• Скорость: до 15 мс на кадр; без getImageData, без shadowBlur больше 30 на больших площадях, без тысяч фигур.
• Лучше всего — шаблон: тексты и моменты бери из data (words, scenes, title, duration, accent), а не вписывай руками — тогда слой подойдёт к любому ролику. Надписи под конкретный ролик — только если об этом просит автор.
• Ответ: один блок ```javascript; первая строка — комментарий // name: короткое название шаблона (2–4 слова); дальше function frame(t, ctx, W, H, data) { … } (и при желании function setup(data) { … }). Без HTML и пояснений.
""".trim()

    /** A ready layer to try the renderer without AI: progress, hook title, stickers on numbers and names, subscribe call. */
    val EXAMPLE = """
// name: Пример: драйв-плашки
// прогресс, хук-титр, плашки на числах и именах, финальный призыв
function setup(d) {
  var ws = d.words || [], keys = [], last = -9;
  for (var i = 0; i < ws.length; i++) {
    var w = ws[i].w.replace(/[.,!?:;«»"()…—]/g, '');
    var cap = i > 0 && w.length > 3 && w[0] !== w[0].toLowerCase();
    if ((/\d/.test(w) || cap) && ws[i].s >= 3.2 && ws[i].s - last >= 2.2) { keys.push({ w: w.toUpperCase(), s: ws[i].s }); last = ws[i].s; }
  }
  d.keys = keys;
  d.hook = (d.title || ws.slice(0, 4).map(function (x) { return x.w; }).join(' ')).toUpperCase();
}
function pill(ctx, text, cx, cy, size, bg, fg, s, a) {
  ctx.save(); ctx.globalAlpha = a; ctx.translate(cx, cy); ctx.scale(s, s);
  ctx.font = '900 ' + size + 'px sans-serif';
  var w = Math.max(ctx.measureText(text).width + size * 1.2, size * 2.4), h = size * 1.6;
  roundRect(ctx, -w / 2, -h / 2, w, h, h / 2); ctx.fillStyle = bg; ctx.fill();
  ctx.fillStyle = fg; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillText(text, 0, size * 0.05);
  ctx.restore();
}
function frame(t, ctx, W, H, d) {
  var u = Math.min(W, H) / 1080, A = d.accent || '#FFD60A', dur = d.duration || 1;
  ctx.fillStyle = A; ctx.fillRect(0, 0, W * clamp(t / dur, 0, 1), Math.max(4, 9 * u));
  if (t < 3.2 && d.hook) {
    var a = ease.out(prog(t, 0.05, 0.45)) * (1 - prog(t, 2.8, 3.2));
    var size = fitFont(ctx, d.hook, W * 0.8, 84 * u, 900);
    ctx.save(); ctx.globalAlpha = a; ctx.translate(-(1 - a) * W * 0.3, 0);
    var y = H * 0.2, bw = W * 0.88, bh = size * 2.2;
    roundRect(ctx, (W - bw) / 2, y - bh / 2, bw, bh, 36 * u); ctx.fillStyle = 'rgba(14,14,20,0.85)'; ctx.fill();
    ctx.fillStyle = A; ctx.fillRect((W - bw) / 2, y - bh * 0.35, 12 * u, bh * 0.7);
    ctx.fillStyle = '#fff'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.font = '900 ' + size + 'px sans-serif';
    ctx.fillText(d.hook, W / 2, y); ctx.restore();
  }
  for (var i = 0; i < d.keys.length; i++) {
    var k = d.keys[i], p = t - k.s;
    if (p < 0 || p > 1.8) continue;
    var s = ease.back(prog(p, 0, 0.25)), o = 1 - prog(p, 1.5, 1.8);
    pill(ctx, k.w, W / 2, H * 0.2, 80 * u, i % 2 ? '#7C5CFF' : A, i % 2 ? '#fff' : '#111', s, o);
  }
  if (t > dur - 2.2) {
    var e = ease.back(prog(t, dur - 2.2, dur - 1.8));
    pill(ctx, 'ПОДПИШИСЬ ▸', W / 2, H * 0.62, 70 * u, '#FF3B30', '#fff', e, 1);
  } else if (d.keys.length === 0 && t > 3.2) return false;
}
""".trim()

    /** Runs before the author's code: no network, no timers, deterministic randomness and clock, helpers. */
    val PRELUDE = """
(function(){
var W=window;W.__t=0;W.__err='';
W.addEventListener('error',function(e){W.__err=(e.message||'ошибка')+' (строка '+Math.max(1,(e.lineno||2)-1)+')'});
W.fetch=undefined;W.XMLHttpRequest=undefined;W.WebSocket=undefined;W.EventSource=undefined;W.Worker=undefined;W.importScripts=undefined;
W.setTimeout=W.setInterval=W.requestAnimationFrame=W.setImmediate=function(){return 0};
var seed=1;Math.random=function(){seed=(seed*16807)%2147483647;return (seed-1)/2147483646};W.__seed=function(s){seed=s|0||1};
Date.now=function(){return Math.round(W.__t*1000)};if(W.performance){performance.now=function(){return W.__t*1000}}
W.clamp=function(x,a,b){return Math.min(b,Math.max(a,x))};
W.lerp=function(a,b,x){return a+(b-a)*x};
W.prog=function(t,a,b){return clamp((t-a)/((b-a)||1e-6),0,1)};
W.ease={in:function(x){return x*x*x},out:function(x){return 1-Math.pow(1-x,3)},inOut:function(x){return x<.5?4*x*x*x:1-Math.pow(-2*x+2,3)/2},
back:function(x){var c1=1.70158,c3=c1+1;return 1+c3*Math.pow(x-1,3)+c1*Math.pow(x-1,2)},
elastic:function(x){return x<=0?0:x>=1?1:Math.pow(2,-10*x)*Math.sin((x*10-.75)*2.0944)+1}};
W.rand=function(i){var s=Math.sin(i*12.9898+78.233)*43758.5453;return s-Math.floor(s)};
W.roundRect=function(c,x,y,w,h,r){r=Math.max(0,Math.min(r,w/2,h/2));c.beginPath();c.moveTo(x+r,y);c.arcTo(x+w,y,x+w,y+h,r);c.arcTo(x+w,y+h,x,y+h,r);c.arcTo(x,y+h,x,y,r);c.arcTo(x,y,x+w,y,r);c.closePath()};
W.fitFont=function(c,text,maxW,size,weight){weight=weight||800;var s=size;for(var k=0;k<30&&s>6;k++){c.font=weight+' '+s+'px sans-serif';if(c.measureText(text).width<=maxW)break;s*=0.92}return s};
W.wordAt=function(t){var w=(W.data&&W.data.words)||[];for(var i=0;i<w.length;i++){if(t>=w[i].s&&t<w[i].e)return i}return -1};
})();
""".trim()

    /** Called by Lumen: init(data) once, frame(t) for every frame → "" (nothing drawn), PNG data URL or "ERR:…". */
    val HARNESS = """
(function(){
var C=document.getElementById('c'),X=C.getContext('2d');
window.__lumen={
init:function(d){window.data=d;window.words=d.words||[];C.width=d.w;C.height=d.h;
 if(window.__err)return 'ERR:'+window.__err;
 try{if(typeof setup==='function')setup(d);if(typeof frame!=='function')return 'ERR:нет функции frame(t, ctx, W, H, data)';return 'OK'}catch(e){return 'ERR:setup: '+(e&&e.message||e)}},
frame:function(t){window.__t=t;__seed(1+Math.round(t*30));
 X.setTransform(1,0,0,1,0,0);X.globalAlpha=1;X.globalCompositeOperation='source-over';X.shadowBlur=0;X.shadowColor='rgba(0,0,0,0)';X.filter='none';
 X.clearRect(0,0,C.width,C.height);X.save();var r;
 try{r=frame(t,X,C.width,C.height,window.data)}catch(e){try{X.restore()}catch(_){}return 'ERR:'+(e&&e.message||e)+' (t='+t.toFixed(2)+' с)'}
 X.restore();if(r===false)return '';return C.toDataURL('image/png')}
};
})();
""".trim()

    private val FENCE = Regex("```([A-Za-z]*)\\s*\\n([\\s\\S]*?)```")
    private val SCRIPT = Regex("<script(\\s[^>]*)?>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE)

    /** The JavaScript of an AI answer: a ```javascript block, the inline scripts of an HTML answer, or the text itself. */
    fun extract(answer: String): String {
        val a = answer.replace("\r\n", "\n").trim()
        val blocks = FENCE.findAll(a).map { it.groupValues[1].lowercase() to it.groupValues[2] }.toList()
        val pick = blocks.firstOrNull { it.first in setOf("javascript", "js") }
            ?: blocks.firstOrNull { it.first == "html" }
            ?: blocks.firstOrNull { it.second.contains("frame") }
        val body = pick?.second ?: a
        val scripts = SCRIPT.findAll(body).filter { !(it.groupValues[1].contains("src", true)) }.map { it.groupValues[2].trim() }.toList()
        return (if (scripts.isNotEmpty()) scripts.joinToString("\n\n") else body).trim()
    }

    /** Problems worth fixing before a render (empty = looks fine); the WebView catches the rest. */
    fun problems(code: String): List<String> {
        val p = mutableListOf<String>()
        if (code.isBlank()) return listOf(t("код пуст", "the code is empty"))
        if (code.length > MAX_CODE) p += t("код длиннее $MAX_CODE символов", "the code is longer than $MAX_CODE chars")
        if (!Regex("""function\s+frame\s*\(|(?:const|let|var)\s+frame\s*=|window\.frame\s*=""").containsMatchIn(code))
            p += t("нет function frame(t, ctx, W, H, data)", "no function frame(t, ctx, W, H, data)")
        if (Regex("""\bfetch\s*\(|XMLHttpRequest|WebSocket|import\s*\(|https?://""").containsMatchIn(code))
            p += t("обращение к сети или URL — в слое не работает", "network or URL access — does not work in the layer")
        if (Regex("""requestAnimationFrame|setInterval|setTimeout""").containsMatchIn(code))
            p += t("таймеры не работают: кадр должен зависеть только от t", "timers do not run: a frame must depend on t only")
        if (Regex("""getElementById|querySelector|createElement\(\s*['"]canvas""").containsMatchIn(code))
            p += t("рисовать нужно в переданный ctx, а не в свой canvas", "draw into the given ctx, not an own canvas")
        if (code.count { it == '{' } != code.count { it == '}' }) p += t("не сходятся фигурные скобки — код обрезан?", "curly braces do not match — is the code cut?")
        return p
    }

    /** The page the WebView loads: the canvas, [PRELUDE], the author's code, [HARNESS]. */
    fun page(code: String, w: Int, h: Int): String {
        val safe = code.replace(Regex("</(script)", RegexOption.IGNORE_CASE), "<\\\\/$1")
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; font-src data:\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden}canvas{width:100%;height:auto;display:block}</style>" +
            "</head><body><canvas id=\"c\" width=\"$w\" height=\"$h\"></canvas>" +
            // everything before the author's code stays on line 1, so the error line numbers are the code's own (+1)
            "<script>" + PRELUDE.replace('\n', ' ') + "</script><script>\n$safe\n</script><script>" + HARNESS.replace('\n', ' ') + "</script></body></html>"
    }

    /** Size of the layer canvas: the video size, or with [full] = false at most [FAST_SHORT] on the short side. */
    fun layerSize(w: Int, h: Int, full: Boolean): Pair<Int, Int> {
        val k = if (full) 1.0 else minOf(1.0, FAST_SHORT.toDouble() / minOf(w, h))
        return maxOf(2, Math.round(w * k).toInt() / 2 * 2) to maxOf(2, Math.round(h * k).toInt() / 2 * 2)
    }

    /** Speech and scenes of the video for the layer, times in seconds. */
    fun dataJson(words: List<Triple<String, Long, Long>>, scenes: List<Triple<String, Long, Long>>, title: String, lang: String): String {
        fun s(ms: Long) = Math.round(ms / 10.0) / 100.0
        val o = JSONObject().put("title", title).put("lang", lang)
        o.put("words", JSONArray().apply { words.forEach { (w, a, b) -> put(JSONObject().put("w", w).put("s", s(a)).put("e", s(b))) } })
        o.put("scenes", JSONArray().apply { scenes.forEach { (w, a, b) -> put(JSONObject().put("title", w).put("s", s(a)).put("e", s(b))) } })
        return o.toString()
    }

    /** [data] completed with the canvas size, the length and the accent colour, as passed to init(). */
    fun withVideo(data: String?, w: Int, h: Int, durationMs: Long, accent: String): JSONObject {
        val o = data?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        if (!o.has("words")) o.put("words", JSONArray())
        if (!o.has("scenes")) o.put("scenes", JSONArray())
        if (!o.has("title")) o.put("title", "")
        o.remove("video")
        return o.put("w", w).put("h", h).put("duration", Math.round(durationMs / 10.0) / 100.0).put("accent", accent)
    }

    /** Words as "секунда слово" lines for the prompt, cut at [maxChars]. */
    fun wordsText(data: JSONObject, maxChars: Int = 6000): String = buildString {
        val wa = data.optJSONArray("words") ?: return@buildString
        for (i in 0 until wa.length()) {
            val w = wa.optJSONObject(i) ?: continue
            val line = String.format(Locale.US, "%.2f %s\n", w.optDouble("s"), w.optString("w"))
            if (length + line.length > maxChars) {
                append("…\n")
                break
            }
            append(line)
        }
    }

    /** The request for the AI: protocol, video facts, speech, the author's wish. */
    fun brief(data: JSONObject, ask: String, subtitlesInVideo: Boolean): String = buildString {
        append(PROTOCOL).append("\n\n")
        append("ЗАДАНИЕ. Видео ").append(data.optInt("w")).append('×').append(data.optInt("h"))
            .append(" (canvas слоя такого же размера), длина ").append(data.optDouble("duration")).append(" с")
        data.optString("title").takeIf { it.isNotBlank() }?.let { append(", тема «").append(it).append('»') }
        append(".\n")
        append(if (subtitlesInVideo) "В видео уже есть субтитры примерно на 55–80% высоты кадра — не перекрывай их.\n"
        else "Субтитров в видео нет — можешь сделать свои по словам.\n")
        append("Акцентный цвет: ").append(data.optString("accent")).append(". Язык надписей: ").append(data.optString("lang").ifBlank { "как в речи" }).append(".\n")
        append("Что нарисовать: ").append(ask.trim().ifEmpty { "современный моушн-дизайн с драйвом: хук-титр в начале, плашки с ключевыми словами и числами в момент, когда они звучат, прогресс сверху, финальный призыв подписаться" }).append('\n')
        val sc = data.optJSONArray("scenes")
        if (sc != null && sc.length() > 0) {
            append("\nСцены (секунды):\n")
            for (i in 0 until minOf(sc.length(), 60)) sc.optJSONObject(i)?.let {
                append(String.format(Locale.US, "%.2f–%.2f %s\n", it.optDouble("s"), it.optDouble("e"), it.optString("title")))
            }
        }
        val words = wordsText(data)
        if (words.isNotBlank()) append("\nРечь (секунда начала и слово):\n").append(words)
    }

    /** The request to fix the layer: the error (or the wish) and the whole current code. */
    fun fixBrief(code: String, error: String, ask: String): String = buildString {
        append(PROTOCOL).append("\n\n")
        append("Исправь код слоя lumen.canvas и верни ПОЛНЫЙ исправленный код одним блоком ```javascript.\n")
        if (error.isNotBlank()) append("Ошибка: ").append(error.trim()).append('\n')
        if (ask.isNotBlank()) append("Что изменить: ").append(ask.trim()).append('\n')
        append("\nТекущий код:\n```javascript\n").append(code.trim()).append("\n```")
    }

    private val NAME = Regex("""^\s*//\s*(?:name|название|имя)\s*[:：]\s*(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /** Name of a layer: its "// name: …" line, else the first comment line, else [fallback]. */
    fun nameOf(code: String, fallback: String): String {
        NAME.find(code.take(2000))?.let { return it.groupValues[1].trim().trimEnd('*', '/').trim().take(40) }
        val first = code.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (first.startsWith("//")) first.removePrefix("//").trim().takeIf { it.length >= 3 }?.let { return it.take(40) }
        return fallback.trim().take(40).ifBlank { "Слой" }
    }

    /** A request for a reusable template without the data of a video: protocol, sample data, the wish. */
    fun templateBrief(ask: String, vertical: Boolean): String = buildString {
        append(PROTOCOL).append("\n\n")
        append("ЗАДАНИЕ. Сделай УНИВЕРСАЛЬНЫЙ шаблон монтажа: всё бери из data, без надписей под конкретный ролик. ")
        append(if (vertical) "Кадр вертикальный 9:16 (Shorts/Reels).\n" else "Кадр любой: опирайся на W и H.\n")
        append("Что за стиль: ").append(ask.trim().ifEmpty { "современный моушн-дизайн с драйвом: хук-титр, плашки с ключевыми словами и числами в момент, когда они звучат, прогресс, финальный призыв" }).append('\n')
        append("\nПример data: {\"duration\":42.5,\"w\":720,\"h\":1280,\"title\":\"Как снять ролик за час\",\"lang\":\"Russian\",\"accent\":\"#FFD60A\",")
        append("\"words\":[{\"w\":\"Итак\",\"s\":0.12,\"e\":0.4},{\"w\":\"20\",\"s\":3.1,\"e\":3.4}],\"scenes\":[{\"title\":\"Хук\",\"s\":0,\"e\":4.2}]}\n")
    }

    /** Clipboard text that looks like layer code (answer of another AI), not our own request. */
    fun looksLikeLayer(s: String?): Boolean {
        val v = s?.trim().orEmpty()
        return v.length in 30..MAX_CODE * 2 && !RelayText.isOwnRequest(v) && !v.startsWith("lumen.canvas v1") &&
            Regex("""function\s+frame\s*\(|(?:const|let|var)\s+frame\s*=""").containsMatchIn(v)
    }

    /** What frame() gave back through evaluateJavascript (a JSON string): PNG bytes, nothing to draw, or an error. */
    sealed class Frame {
        object Empty : Frame()
        class Png(val base64: String) : Frame()
        class Error(val message: String) : Frame()
    }

    fun frameOf(jsResult: String?): Frame {
        val s = when {
            jsResult == null || jsResult == "null" -> return Frame.Error(t("WebView не ответил", "The WebView gave no answer"))
            jsResult.startsWith("\"") -> runCatching { JSONArray("[$jsResult]").getString(0) }.getOrElse { jsResult.trim('"') }
            else -> jsResult
        }
        return when {
            s.isEmpty() -> Frame.Empty
            s.startsWith("ERR:") -> Frame.Error(s.removePrefix("ERR:").trim())
            s.startsWith("data:image/") && s.contains(",") -> Frame.Png(s.substringAfter(','))
            else -> Frame.Error(t("неожиданный ответ: ", "unexpected answer: ") + s.take(80))
        }
    }

    /** Frame times of the preview: four moments spread over the video. */
    fun previewTimes(durationMs: Long): List<Double> = listOf(0.08, 0.33, 0.6, 0.86).map { Math.round(durationMs * it / 10.0) / 100.0 }
}

/** A saved montage template: the layer code and where it came from (ai, paste, example, edit). */
data class SavedLayer(val id: String, val name: String, val code: String, val ask: String, val source: String, val created: Long, val updated: Long) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("code", code).put("ask", ask).put("source", source)
        .put("created", created).put("updated", updated).put("protocol", "lumen.canvas v1")

    companion object {
        fun of(o: JSONObject): SavedLayer? {
            val code = o.optString("code")
            if (code.isBlank()) return null
            val id = o.optString("id").ifBlank { return null }
            return SavedLayer(id, o.optString("name").ifBlank { "Слой" }, code, o.optString("ask"), o.optString("source"),
                o.optLong("created"), o.optLong("updated", o.optLong("created")))
        }
    }
}

/** Every layer the user tried, one JSON file each in [dir]; the same code is never stored twice. */
class LayerStore(private val dir: File) {
    private fun file(id: String) = File(dir, "$id.json")

    fun list(): List<SavedLayer> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { SavedLayer.of(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.updated }

    fun get(id: String): SavedLayer? = runCatching { SavedLayer.of(JSONObject(file(id).readText())) }.getOrNull()

    private fun put(l: SavedLayer): SavedLayer {
        dir.mkdirs()
        file(l.id).writeText(l.json().toString(1))
        return l
    }

    /** Stores [code] as a new template (or brings the identical one to the top); the name comes from the code. */
    fun add(code: String, ask: String, source: String, now: Long): SavedLayer {
        val c = code.trim()
        list().firstOrNull { it.code.trim() == c }?.let { return put(it.copy(updated = now)) }
        val n = list().size + 1
        var id = "l" + now.toString(36)
        var k = 1
        while (file(id).exists()) id = "l" + now.toString(36) + "_" + k++
        return put(SavedLayer(id, WebLayer.nameOf(c, ask.ifBlank { "Слой $n" }), c, ask, source, now, now))
    }

    /** New code for the same template (a fix or a hand edit). */
    fun update(id: String, code: String, now: Long): SavedLayer? = get(id)?.let { put(it.copy(code = code.trim(), updated = now)) }

    fun rename(id: String, name: String): SavedLayer? = get(id)?.let { put(it.copy(name = name.trim().take(40).ifBlank { it.name })) }

    fun delete(id: String) = file(id).delete()
}

/** H.264 + AAC writer fed with ARGB frames in order (the [StudioVideo] pipeline without the Studio painter). */
class FrameEncoder(
    private val out: File, private val w: Int, private val h: Int, private val fps: Int,
    private val audio: Pair<MediaFormat, List<StudioAudio.Sample>>?
) {
    private val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private val aInfo = MediaCodec.BufferInfo()
    private var started = false
    private var vTrack = -1
    private var aTrack = -1
    private var aNext = 0
    private val y = ByteArray(w * h)
    private val u = ByteArray(w * h / 4)
    private val v = ByteArray(w * h / 4)

    init {
        out.parentFile?.mkdirs()
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, Studio.bitrate(w, h, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun writeAudio(untilUs: Long) {
        val a = audio ?: return
        while (started && aNext < a.second.size && a.second[aNext].ptsUs <= untilUs) {
            val s = a.second[aNext++]
            aInfo.set(0, s.data.size, s.ptsUs, s.flags)
            muxer.writeSampleData(aTrack, ByteBuffer.wrap(s.data), aInfo)
        }
    }

    private fun drain(end: Boolean) {
        var idle = 0
        while (true) {
            val o = enc.dequeueOutputBuffer(info, if (end) 10_000L else 0L)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    if (++idle > 300) error("video encoder stalled")
                }
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    vTrack = muxer.addTrack(enc.outputFormat)
                    audio?.let { aTrack = muxer.addTrack(it.first) }
                    muxer.start()
                    started = true
                }
                o >= 0 -> {
                    val b = enc.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        writeAudio(info.presentationTimeUs)
                        b.position(info.offset)
                        b.limit(info.offset + info.size)
                        muxer.writeSampleData(vTrack, b, info)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Frame number [f] (ARGB, w×h) to the encoder; [cancelled] is checked while the encoder is busy. */
    fun frame(f: Int, argb: IntArray, cancelled: () -> Boolean) {
        Studio.argbToYuv420(argb, w, h, y, u, v)
        var idx: Int
        while (true) {
            idx = enc.dequeueInputBuffer(10_000L)
            if (idx >= 0) break
            drain(false)
            if (cancelled()) throw java.util.concurrent.CancellationException()
        }
        val img = enc.getInputImage(idx)
        if (img != null) StudioVideo.writeImage(img, w, h, y, u, v) else StudioVideo.writeNv12(enc.getInputBuffer(idx)!!, w, h, y, u, v)
        enc.queueInputBuffer(idx, 0, w * h * 3 / 2, Studio.frameTimeUs(f, fps), 0)
        drain(false)
    }

    /** Ends the stream after [frames] frames and closes the file. */
    fun finish(frames: Int) {
        while (true) {
            val idx = enc.dequeueInputBuffer(10_000L)
            if (idx >= 0) {
                enc.queueInputBuffer(idx, 0, 0, Studio.frameTimeUs(frames, fps), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(false)
        }
        drain(true)
        writeAudio(Long.MAX_VALUE)
    }

    fun close(ok: Boolean) {
        runCatching { enc.stop() }
        runCatching { enc.release() }
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        if (!ok) out.delete()
    }
}
