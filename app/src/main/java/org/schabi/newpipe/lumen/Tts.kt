/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A Microsoft Edge "Read Aloud" neural voice. */
data class TtsVoice(
    val name: String,
    val shortName: String,
    val locale: String,
    val gender: String,
    val personalities: List<String> = emptyList(),
    val categories: List<String> = emptyList()
) {
    /** "Svetlana" out of "ru-RU-SvetlanaNeural". */
    val display: String get() = shortName.substringAfter(locale + "-").removeSuffix("Neural")
    val multilingual: Boolean get() = shortName.contains("Multilingual")
}

/** Prosody settings: rate and volume in percent (0 = normal), pitch in Hz. */
data class TtsOptions(val voice: String, val rate: Int = 0, val pitch: Int = 0, val volume: Int = 0)

/**
 * Online text-to-speech through the same free service the Edge browser uses for "Read aloud"
 * (protocol as in the python edge-tts project). No key, but needs internet; the text goes to Microsoft.
 * Pure helpers are separated from network code so they can be unit-tested.
 */
object EdgeTts {
    const val TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM = "143.0.3650.75"
    private val MAJOR = CHROMIUM.substringBefore('.')
    const val FORMAT = "audio-24khz-48kbitrate-mono-mp3"
    private const val BASE = "speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/$MAJOR.0.0.0 Safari/537.36 Edg/$MAJOR.0.0.0"
    /** Escaped text per request, in UTF-8 bytes (the service limit is 4096 incl. SSML). */
    const val CHUNK_BYTES = 3600
    private const val WIN_EPOCH = 11644473600L

    /** Server clock minus device clock, seconds; learned from a 403 response. */
    @Volatile var skewSec = 0L

    // ---------- pure helpers ----------

    /** Sec-MS-GEC: SHA-256 of Windows file time rounded down to 5 minutes + token, upper hex. */
    fun gecToken(unixSec: Long): String {
        var ticks = unixSec + WIN_EPOCH
        ticks -= ticks % 300
        val s = (ticks * 10_000_000L).toString() + TOKEN
        return MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02X".format(it) }
    }

    fun xmlEscape(s: String): String = buildString(s.length) {
        for (ch in s) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(ch)
        }
    }

    /** Control characters make the service drop the request: replace them with spaces. */
    fun clean(s: String): String = s.map { c ->
        val code = c.code
        if (code in 0..8 || code in 11..12 || code in 14..31) ' ' else c
    }.joinToString("")

    fun signed(v: Int, unit: String): String = (if (v >= 0) "+" else "") + v + unit

    /** "ru-RU-SvetlanaNeural" → "Microsoft Server Speech Text to Speech Voice (ru-RU, SvetlanaNeural)". */
    fun fullVoiceName(shortName: String): String {
        if (shortName.startsWith("Microsoft Server")) return shortName
        val parts = shortName.split('-')
        if (parts.size < 3) return shortName
        val locale = parts[0] + "-" + parts[1]
        val rest = parts.drop(2).joinToString("-")
        return "Microsoft Server Speech Text to Speech Voice ($locale, $rest)"
    }

    fun ssml(escapedText: String, o: TtsOptions): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='${fullVoiceName(o.voice)}'>" +
            "<prosody pitch='${signed(o.pitch, "Hz")}' rate='${signed(o.rate, "%")}' volume='${signed(o.volume, "%")}'>" +
            escapedText + "</prosody></voice></speak>"

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    /**
     * Splits text into escaped pieces of at most [limit] UTF-8 bytes, preferring paragraph,
     * sentence and word boundaries; never cuts inside an XML entity.
     */
    fun split(text: String, limit: Int = CHUNK_BYTES): List<String> {
        val src = clean(text).trim()
        if (src.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var rest = src
        while (rest.isNotEmpty()) {
            if (bytes(xmlEscape(rest)) <= limit) {
                out.add(xmlEscape(rest))
                break
            }
            // longest prefix that fits
            var lo = 1
            var hi = rest.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (bytes(xmlEscape(rest.substring(0, mid))) <= limit) lo = mid else hi = mid - 1
            }
            var cut = lo
            // don't split a surrogate pair
            if (cut < rest.length && Character.isLowSurrogate(rest[cut])) cut--
            val window = rest.substring(0, cut)
            val minCut = cut / 2
            val boundary = listOf(
                window.lastIndexOf('\n'),
                maxOf(window.lastIndexOf(". "), window.lastIndexOf("! "), window.lastIndexOf("? "), window.lastIndexOf("… ")).let { if (it >= 0) it + 1 else -1 },
                window.lastIndexOf(' ')
            ).firstOrNull { it >= minCut }
            if (boundary != null && boundary > 0) cut = boundary
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotEmpty()) out.add(xmlEscape(piece))
            rest = rest.substring(cut).trimStart()
        }
        return out
    }

    /** "Thu Oct 28 2026 12:00:00 GMT+0000 (Coordinated Universal Time)" */
    fun timestamp(ms: Long): String = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms)) + " GMT+0000 (Coordinated Universal Time)"

    fun configMessage(ts: String): String =
        "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\"," +
            "\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"$FORMAT\"}}}}\r\n"

    fun ssmlMessage(requestId: String, ts: String, ssml: String): String =
        "X-RequestId:$requestId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${ts}Z\r\nPath:ssml\r\n\r\n$ssml"

    /** Header block of a text frame ("Path:turn.end" → "turn.end"). */
    fun path(headers: String): String? = headers.lineSequence()
        .map { it.trim() }.firstOrNull { it.startsWith("Path:", true) }?.substringAfter(':')?.trim()

    /**
     * Binary frame: 2-byte big-endian header length, headers, then audio.
     * Returns the audio part or null if the frame is not audio.
     */
    fun audioPayload(frame: ByteArray): ByteArray? {
        if (frame.size < 2) return null
        val len = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        if (2 + len > frame.size) return null
        val headers = String(frame, 2, len, Charsets.UTF_8)
        if (path(headers) != "audio") return null
        if (!headers.contains("Content-Type:audio", true)) return null
        return frame.copyOfRange(2 + len, frame.size)
    }

    fun parseVoices(json: String): List<TtsVoice> {
        val a = JSONArray(json)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val short = o.optString("ShortName")
            if (short.isBlank()) return@mapNotNull null
            val tag = o.optJSONObject("VoiceTag")
            fun list(k: String) = tag?.optJSONArray(k)?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
            TtsVoice(o.optString("Name"), short, o.optString("Locale"), o.optString("Gender"), list("VoicePersonalities"), list("ContentCategories"))
        }.sortedWith(compareBy({ it.locale }, { it.shortName }))
    }

    /** Main language of the text by its alphabet, e.g. "ru", "uk", "en"; null if unclear. */
    fun detectLang(text: String): String? {
        var cyr = 0
        var lat = 0
        var uk = 0
        var other = 0
        for (c in text.take(4000)) {
            when {
                c in 'а'..'я' || c in 'А'..'Я' || c == 'ё' || c == 'Ё' -> cyr++
                c in "іїєґІЇЄҐ" -> { cyr++; uk++ }
                c in 'a'..'z' || c in 'A'..'Z' -> lat++
                Character.isLetter(c) -> other++
            }
        }
        val total = cyr + lat + other
        if (total < 3) return null
        return when {
            cyr >= lat && cyr >= other -> if (uk * 50 >= cyr && uk > 0) "uk" else "ru"
            lat >= other -> "en"
            else -> null
        }
    }

    /** Picks a voice for [lang]: the remembered one, else a female voice of the main country, else any. */
    fun pickVoice(voices: List<TtsVoice>, lang: String, remembered: String?): TtsVoice? {
        val ofLang = voices.filter { it.locale.substringBefore('-').equals(lang, true) }
        if (ofLang.isEmpty()) return null
        ofLang.firstOrNull { it.shortName == remembered }?.let { return it }
        val main = when (lang) {
            "en" -> "en-US"
            "ru" -> "ru-RU"
            "uk" -> "uk-UA"
            else -> null
        }
        val pool = ofLang.filter { it.locale == main }.ifEmpty { ofLang }
        return pool.firstOrNull { it.gender == "Female" && !it.multilingual } ?: pool.first()
    }

    /** Used when the voice list can't be loaded. */
    val builtIn: List<TtsVoice> = listOf(
        TtsVoice("", "ru-RU-SvetlanaNeural", "ru-RU", "Female"),
        TtsVoice("", "ru-RU-DmitryNeural", "ru-RU", "Male"),
        TtsVoice("", "uk-UA-PolinaNeural", "uk-UA", "Female"),
        TtsVoice("", "uk-UA-OstapNeural", "uk-UA", "Male"),
        TtsVoice("", "en-US-AriaNeural", "en-US", "Female"),
        TtsVoice("", "en-US-JennyNeural", "en-US", "Female"),
        TtsVoice("", "en-US-GuyNeural", "en-US", "Male"),
        TtsVoice("", "en-US-AndrewMultilingualNeural", "en-US", "Male"),
        TtsVoice("", "en-US-AvaMultilingualNeural", "en-US", "Female"),
        TtsVoice("", "en-GB-SoniaNeural", "en-GB", "Female"),
        TtsVoice("", "en-GB-RyanNeural", "en-GB", "Male"),
        TtsVoice("", "de-DE-KatjaNeural", "de-DE", "Female"),
        TtsVoice("", "de-DE-FlorianMultilingualNeural", "de-DE", "Male"),
        TtsVoice("", "fr-FR-DeniseNeural", "fr-FR", "Female"),
        TtsVoice("", "fr-FR-VivienneMultilingualNeural", "fr-FR", "Female"),
        TtsVoice("", "es-ES-ElviraNeural", "es-ES", "Female"),
        TtsVoice("", "it-IT-ElsaNeural", "it-IT", "Female"),
        TtsVoice("", "pt-BR-FranciscaNeural", "pt-BR", "Female"),
        TtsVoice("", "pl-PL-ZofiaNeural", "pl-PL", "Female"),
        TtsVoice("", "tr-TR-EmelNeural", "tr-TR", "Female"),
        TtsVoice("", "kk-KZ-AigulNeural", "kk-KZ", "Female"),
        TtsVoice("", "zh-CN-XiaoxiaoNeural", "zh-CN", "Female"),
        TtsVoice("", "ja-JP-NanamiNeural", "ja-JP", "Female")
    )

    // ---------- network ----------

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private fun now() = System.currentTimeMillis() / 1000 + skewSec
    private fun muid() = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)

    private fun voicesCache() = File(Lumen.app.filesDir, "lumen/tts_voices.json")

    /** Voice list: cached for a week; falls back to the cache, then to [builtIn]. */
    fun voices(force: Boolean = false): List<TtsVoice> {
        val f = voicesCache()
        if (!force && f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) {
            runCatching { parseVoices(f.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return try {
            val url = "https://$BASE/voices/list?trustedclienttoken=$TOKEN&Sec-MS-GEC=${gecToken(now())}&Sec-MS-GEC-Version=1-$CHROMIUM"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Sec-CH-UA", "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"$MAJOR\", \"Chromium\";v=\"$MAJOR\"")
                .header("Sec-CH-UA-Mobile", "?0")
                .header("Cookie", "muid=${muid()};")
                .build()
            http.newCall(req).execute().use { r ->
                if (r.code == 403) learnSkew(r)
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val body = r.body.string()
                val list = parseVoices(body)
                if (list.isEmpty()) error("empty voice list")
                f.parentFile?.mkdirs()
                f.writeText(body)
                list
            }
        } catch (t: Throwable) {
            L.w("tts", "voices: ${t.message}")
            runCatching { parseVoices(f.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: builtIn
        }
    }

    private fun learnSkew(r: Response) {
        val d = r.header("Date") ?: return
        val server = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(d)?.time
        }.getOrNull() ?: return
        skewSec = server / 1000 - System.currentTimeMillis() / 1000
        L.w("tts", "clock skew ${skewSec}s")
    }

    class TtsException(msg: String) : Exception(msg)

    /**
     * Synthesises [text] to MP3 bytes. Long text is sent in pieces (one connection each);
     * [onProgress] gets (done, total). [cancelled] is polled between pieces.
     */
    fun synthesize(text: String, o: TtsOptions, cancelled: () -> Boolean = { false }, onProgress: (Int, Int) -> Unit = { _, _ -> }): ByteArray {
        val chunks = split(text)
        if (chunks.isEmpty()) throw TtsException(t("Нет текста для озвучки", "No text to speak"))
        val out = ByteArrayOutputStream()
        chunks.forEachIndexed { i, c ->
            if (cancelled()) return out.toByteArray()
            onProgress(i, chunks.size)
            val audio = try {
                one(c, o)
            } catch (e: Forbidden) {
                one(c, o) // retried once with the corrected clock
            }
            out.write(audio)
        }
        onProgress(chunks.size, chunks.size)
        return out.toByteArray()
    }

    private class Forbidden : Exception("403")

    private fun one(escaped: String, o: TtsOptions): ByteArray {
        val url = "wss://$BASE/edge/v1?TrustedClientToken=$TOKEN&Sec-MS-GEC=${gecToken(now())}" +
            "&Sec-MS-GEC-Version=1-$CHROMIUM&ConnectionId=${UUID.randomUUID().toString().replace("-", "")}"
        val req = Request.Builder().url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", ORIGIN)
            .header("User-Agent", UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${muid()};")
            .build()
        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var error: Throwable? = null
        var finished = false
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ts = timestamp(System.currentTimeMillis())
                webSocket.send(configMessage(ts))
                webSocket.send(ssmlMessage(UUID.randomUUID().toString().replace("-", ""), ts, ssml(escaped, o)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (path(text.substringBefore("\r\n\r\n")) == "turn.end") {
                    finished = true
                    webSocket.close(1000, null)
                    done.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                audioPayload(bytes.toByteArray())?.let { synchronized(audio) { audio.write(it) } }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 403) {
                    learnSkew(response)
                    error = Forbidden()
                } else {
                    error = t
                }
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        })
        if (!done.await(90, TimeUnit.SECONDS)) {
            ws.cancel()
            throw TtsException(t("Сервис озвучки не ответил", "The speech service did not respond"))
        }
        error?.let { if (!finished) throw it }
        val bytes = synchronized(audio) { audio.toByteArray() }
        if (bytes.isEmpty()) throw TtsException(t("Сервис не вернул звук. Проверьте голос и интернет.", "No audio received. Check the voice and the connection."))
        return bytes
    }
}

/** Turns a picked file into plain text to read aloud (no extra libraries). */
object TtsText {
    /** Speed presets: label → rate percent. */
    val speeds = listOf("0.5×" to -50, "0.75×" to -25, "1×" to 0, "1.25×" to 25, "1.5×" to 50, "1.75×" to 75, "2×" to 100)

    /** UTF-8 / UTF-16 (BOM) / Windows-1251 fallback for old Russian .txt files. */
    fun decode(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        val start = if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) 3 else 0
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return try {
            dec.decode(java.nio.ByteBuffer.wrap(b, start, b.size - start)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(b, charset("windows-1251"))
        }
    }

    fun unescape(s: String): String = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> when (e.lowercase()) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                "mdash" -> "—"
                "ndash" -> "–"
                "laquo" -> "«"
                "raquo" -> "»"
                "hellip" -> "…"
                else -> m.value
            }
        }
    }

    private fun tidy(s: String): String = s.lines().joinToString("\n") { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        .replace(Regex("\n{3,}"), "\n\n").trim()

    /** HTML / XHTML / FB2 markup → text with paragraph breaks. */
    fun markup(src: String): String {
        var s = src
        s = s.replace(Regex("(?is)<(script|style|head|binary)\\b.*?</\\1>"), " ")
        s = s.replace(Regex("(?is)<!--.*?-->"), " ")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|title|subtitle|v|stanza|section|blockquote)>"), "\n")
        s = s.replace(Regex("(?i)<w:tab\\s*/>"), " ")
        s = s.replace(Regex("(?i)</w:p>"), "\n")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        return tidy(unescape(s))
    }

    /** SRT / VTT → spoken lines without numbers, timecodes and repeated lines. */
    fun subtitles(src: String): String {
        val time = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?[.,]\\d{1,3}\\s*-->.*$|^\\d{1,2}:\\d{2}[.,]\\d{1,3}\\s*-->.*$")
        val out = mutableListOf<String>()
        for (raw in src.lines()) {
            val l = raw.trim()
            if (l.isEmpty() || l == "WEBVTT" || l.matches(Regex("^\\d+$")) || time.matches(l)) continue
            if (l.startsWith("NOTE") || l.startsWith("Kind:") || l.startsWith("Language:") || l.startsWith("STYLE")) continue
            val clean = unescape(l.replace(Regex("<[^>]+>"), "")).trim()
            if (clean.isNotEmpty() && out.lastOrNull() != clean) out.add(clean)
        }
        return out.joinToString(" ")
    }

    private fun unzip(b: ByteArray): List<Pair<String, ByteArray>> {
        val out = mutableListOf<Pair<String, ByteArray>>()
        java.util.zip.ZipInputStream(b.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) out.add(e.name to z.readBytes())
            }
        }
        return out
    }

    private fun docx(b: ByteArray): String {
        val doc = unzip(b).firstOrNull { it.first == "word/document.xml" } ?: error("no word/document.xml")
        return markup(String(doc.second, Charsets.UTF_8))
    }

    private fun epub(b: ByteArray): String {
        val files = unzip(b)
        val byName = files.toMap()
        val opf = files.firstOrNull { it.first.endsWith(".opf") }
        val pages: List<ByteArray> = if (opf != null) {
            // reading order from the spine
            val x = String(opf.second, Charsets.UTF_8)
            val dir = opf.first.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val items = Regex("<item\\b[^>]*>").findAll(x).mapNotNull { m ->
                val id = Regex("\\bid=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)
                val href = Regex("\\bhref=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)
                if (id != null && href != null) id to href else null
            }.toMap()
            Regex("<itemref\\b[^>]*idref=\"([^\"]+)\"").findAll(x).mapNotNull { m ->
                items[m.groupValues[1]]?.let { byName[dir + java.net.URLDecoder.decode(it, "UTF-8")] }
            }.toList()
        } else {
            emptyList()
        }
        val list = pages.ifEmpty { files.filter { it.first.endsWith(".xhtml") || it.first.endsWith(".html") || it.first.endsWith(".htm") }.map { it.second } }
        return list.joinToString("\n\n") { markup(String(it, Charsets.UTF_8)) }.trim()
    }

    /** Extracts readable text from a file by its name / MIME type. */
    fun fromFile(name: String, mime: String?, b: ByteArray): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val m = mime?.lowercase() ?: ""
        return when {
            ext == "docx" || m.contains("wordprocessingml") -> docx(b)
            ext == "epub" || m.contains("epub") -> epub(b)
            ext == "fb2" || ext == "html" || ext == "htm" || ext == "xhtml" || ext == "xml" || m.contains("html") || m.contains("fictionbook") -> markup(decode(b))
            ext == "srt" || ext == "vtt" || m.contains("subrip") || m.contains("vtt") -> subtitles(decode(b))
            ext == "pdf" || m.contains("pdf") -> error(t("PDF не поддерживается: сохраните его как TXT или DOCX", "PDF is not supported: save it as TXT or DOCX"))
            ext == "md" || ext == "markdown" -> tidy(decode(b).replace(Regex("(?m)^#{1,6}\\s*"), "").replace(Regex("[*_`]{1,3}"), "")
                .replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1"))
            else -> tidy(decode(b))
        }
    }

    /** Short phrase to preview a voice. */
    fun sample(lang: String): String = when (lang) {
        "ru" -> "Привет! Так звучит этот голос. Скорость и тон можно настроить."
        "uk" -> "Привіт! Ось так звучить цей голос."
        "de" -> "Hallo! So klingt diese Stimme."
        "fr" -> "Bonjour ! Voici à quoi ressemble cette voix."
        "es" -> "¡Hola! Así suena esta voz."
        "it" -> "Ciao! Ecco come suona questa voce."
        "pt" -> "Olá! É assim que esta voz soa."
        "pl" -> "Cześć! Tak brzmi ten głos."
        "tr" -> "Merhaba! Bu ses böyle duyuluyor."
        "zh" -> "你好！这就是这个声音的效果。"
        "ja" -> "こんにちは。この声はこのように聞こえます。"
        else -> "Hello! This is how this voice sounds."
    }
}

/** Saved speech settings. */
object TtsPrefs {
    private val p get() = Lumen.prefs

    var voice: String
        get() = p.getString("tts_voice", "ru-RU-SvetlanaNeural") ?: "ru-RU-SvetlanaNeural"
        set(v) = p.edit().putString("tts_voice", v).putString("tts_voice_" + v.substringBefore('-'), v).apply()

    fun voiceFor(lang: String): String? = p.getString("tts_voice_$lang", null)

    var rate: Int
        get() = p.getInt("tts_rate", 0)
        set(v) = p.edit().putInt("tts_rate", v).apply()
    var pitch: Int
        get() = p.getInt("tts_pitch", 0)
        set(v) = p.edit().putInt("tts_pitch", v).apply()
    var volume: Int
        get() = p.getInt("tts_volume", 0)
        set(v) = p.edit().putInt("tts_volume", v).apply()
    var auto: Boolean
        get() = p.getBoolean("tts_auto", true)
        set(v) = p.edit().putBoolean("tts_auto", v).apply()
    var fromCursor: Boolean
        get() = p.getBoolean("tts_from_cursor", false)
        set(v) = p.edit().putBoolean("tts_from_cursor", v).apply()
    /** "all", "Female" or "Male". */
    var gender: String
        get() = p.getString("tts_gender", "all") ?: "all"
        set(v) = p.edit().putString("tts_gender", v).apply()
    var playSpeed: Float
        get() = p.getFloat("tts_play_speed", 1f)
        set(v) = p.edit().putFloat("tts_play_speed", v).apply()
}
