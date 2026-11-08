/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.app.AlertDialog
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Interface languages beyond Russian and English (protocol lumen.i18n v1, used by Lumen Studio).
 *
 * Sources keep t("русский", "English"). For the other languages the English text is the key of
 * assets/lumen_i18n/<code>.json ({"English": "перевод"}); a templated call is rewritten at build time
 * into tf(ru, "English {0}", x) (buildSrc/LumenI18n.kt), and the catalogue keeps the {N} places.
 * A missing translation falls back to English. Catalogues are made by tools/i18n (see README there).
 *
 * Language choice ([Settings.lang]): "auto" (Studio default) follows the system language each time the
 * app opens; when the system language is not in [LANGS], [needsAsk] makes the Studio ask once
 * and keeps the answer in [fallback].
 */
object I18n {
    class Lang(val code: String, val native: String, val english: String, val rtl: Boolean = false)

    /** Order of the picker. Codes are BCP-47 language subtags; zh = Simplified Chinese. */
    val LANGS = listOf(
        Lang("en", "English", "English"),
        Lang("ru", "Русский", "Russian"),
        Lang("es", "Español", "Spanish"),
        Lang("pt", "Português", "Portuguese"),
        Lang("fr", "Français", "French"),
        Lang("de", "Deutsch", "German"),
        Lang("it", "Italiano", "Italian"),
        Lang("tr", "Türkçe", "Turkish"),
        Lang("pl", "Polski", "Polish"),
        Lang("uk", "Українська", "Ukrainian"),
        Lang("ar", "العربية", "Arabic", rtl = true),
        Lang("hi", "हिन्दी", "Hindi"),
        Lang("id", "Bahasa Indonesia", "Indonesian"),
        Lang("vi", "Tiếng Việt", "Vietnamese"),
        Lang("ja", "日本語", "Japanese"),
        Lang("ko", "한국어", "Korean"),
        Lang("zh", "简体中文", "Chinese (Simplified)"),
        Lang("bn", "বাংলা", "Bengali"),
        Lang("ur", "اردو", "Urdu", rtl = true),
        Lang("th", "ไทย", "Thai"),
    )

    fun lang(code: String): Lang? = LANGS.firstOrNull { it.code == code }

    /** Our language for a system locale tag ("pt-BR" → pt, "in" → id), or null when we have none. */
    fun match(tag: String): String? {
        val parts = tag.replace('_', '-').split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val l = when (val x = parts[0].lowercase(Locale.ROOT)) {
            "in" -> "id"
            "iw" -> "he"
            "nb", "nn" -> "no"
            else -> x
        }
        if (l == "zh") {
            // Traditional Chinese (Taiwan, Hong Kong, Macau) is a different written language
            val rest = parts.drop(1).map { it.lowercase(Locale.ROOT) }
            if ("hant" in rest || rest.any { it == "tw" || it == "hk" || it == "mo" }) return null
        }
        return lang(l)?.code
    }

    /** Replaces {0}, {1}… of [tpl]; other braces stay as they are. */
    fun format(tpl: String, args: Array<out Any?>): String {
        if (args.isEmpty() || tpl.indexOf('{') < 0) return tpl
        val sb = StringBuilder(tpl.length + 16)
        var i = 0
        while (i < tpl.length) {
            val c = tpl[i]
            if (c == '{') {
                val end = tpl.indexOf('}', i + 1)
                val n = if (end in i + 2..i + 3) tpl.substring(i + 1, end).toIntOrNull() else null
                if (n != null && n in args.indices) {
                    sb.append(args[n].toString())
                    i = end + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /** True when [tr] keeps exactly the placeholders of [key] (each {N} once or more, no new ones). */
    fun samePlaces(key: String, tr: String): Boolean = places(key) == places(tr)

    private val PLACE = Regex("\\{(\\d{1,2})\\}")
    private fun places(s: String): Set<String> = PLACE.findAll(s).map { it.groupValues[1] }.toSet()

    // ---- runtime ----

    private val cats = HashMap<String, Map<String, String>>()

    /** Hand translations (assets) over machine ones (cache on the device): the catalogue of [code]. */
    fun catalog(code: String): Map<String, String> {
        synchronized(cats) { cats[code]?.let { return it } }
        val m = HashMap<String, String>()
        try {
            readInto(m, File(cacheDir(), "$code.json").takeIf { it.isFile }?.readText())
        } catch (_: Exception) {
        }
        try {
            readInto(m, Lumen.app.assets.open("lumen_i18n/$code.json").use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (_: Exception) {
        }
        synchronized(cats) { cats[code] = m }
        return m
    }

    private fun readInto(m: MutableMap<String, String>, text: String?) {
        if (text == null) return
        val o = JSONObject(text)
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.optString(k)
            if (v.isNotEmpty() && samePlaces(k, v)) m[k] = v
        }
    }

    private fun cacheDir() = File(Lumen.app.filesDir, "lumen_i18n").also { it.mkdirs() }

    /**
     * Translation of [key] from [m]: the whole text, else its words without the signs around them
     * ("🎬 Save video…" → "Save video"), so one translation serves all spellings.
     */
    fun lookup(m: Map<String, String>, key: String): String? {
        m[key]?.let { return it }
        var a = 0
        while (a < key.length && !key[a].isLetterOrDigit()) a++
        var b = key.length
        while (b > a && key[b - 1] in "…:. ") b--
        if (a == 0 && b == key.length) return null
        val head = key.substring(0, a)
        if (a >= key.length || '{' in head || b - a < 2) return null
        val core = m[key.substring(a, b)] ?: return null
        return head + core + key.substring(b)
    }

    /** Languages this build can show: ru, en, those with a catalogue and, with the machine translator, all. */
    val available: List<Lang> by lazy {
        LANGS.filter { it.code == "ru" || it.code == "en" || hasCatalog(it.code) || machine?.supports(it.code) == true }
    }

    private fun hasCatalog(code: String): Boolean = try {
        Lumen.app.assets.list("lumen_i18n")?.contains("$code.json") == true
    } catch (_: Exception) {
        false
    }

    fun pick(ru: String, en: String): String {
        if (!LumenEnv.ready) return ru
        return when (val l = Settings.lang) {
            "ru" -> ru
            "en" -> en
            else -> lookup(catalog(l), en) ?: en
        }
    }

    fun pickF(ru: String, key: String, args: Array<out Any?>): String {
        if (!LumenEnv.ready) return ru
        return when (val l = Settings.lang) {
            "ru" -> ru
            "en" -> format(key, args)
            else -> format(lookup(catalog(l), key) ?: key, args)
        }
    }

    /** The interface is in Russian (lists of names that exist only in ru/en use it). */
    val ru: Boolean get() = !LumenEnv.ready || Settings.lang == "ru"

    /** English name of the interface language, for AI prompts ("answer in …"). */
    val aiName: String get() = if (ru) "русский" else lang(Settings.lang)?.english ?: "English"

    val rtl: Boolean get() = LumenEnv.ready && lang(Settings.lang)?.rtl == true

    /** System locale: on Android 13+ also the per-app language chosen in the system settings. */
    fun systemTag(): String = Locale.getDefault().toLanguageTag()

    @Volatile private var lastTag: String? = null
    @Volatile private var lastCode: String? = null

    /** Language of the "auto" choice: the system one if we have it, else the one the user picked. */
    fun auto(): String {
        val tag = systemTag()
        if (tag == lastTag) lastCode?.let { return it }
        val code = match(tag)?.takeIf { c -> available.any { it.code == c } }
        lastTag = tag
        lastCode = code
        return code ?: fallback
    }

    /** Answer to "your system language is not translated yet": prefs lang_fallback (English). */
    var fallback: String
        get() = Lumen.prefs.getString("lang_fallback", "en") ?: "en"
        set(v) = Lumen.prefs.edit().putString("lang_fallback", v).apply()

    /** "auto" and the system language is not ours, and we have not asked for this system language yet. */
    fun needsAsk(): Boolean {
        if (Settings.langChoice != "auto") return false
        val tag = systemTag()
        if (match(tag)?.let { c -> available.any { it.code == c } } == true) return false
        return Lumen.prefs.getString("lang_asked", null) != tag
    }

    fun asked() = Lumen.prefs.edit().putString("lang_asked", systemTag()).apply()

    /** Display name of the system language in itself ("Svenska"). */
    fun systemName(): String = Locale.getDefault().let { it.getDisplayName(it) }.replaceFirstChar { it.uppercase() }

    /**
     * "Your system language is not translated yet": asked once per system language, in English and
     * in the native names. The answer becomes [fallback]; the choice stays "auto", so a later system
     * language that we have is used by itself.
     */
    fun ask(a: Activity) {
        val langs = available
        val names = langs.map { it.native }.toTypedArray()
        val cur = langs.indexOfFirst { it.code == fallback }
        try {
            AlertDialog.Builder(a)
                .setTitle("${systemName()} — not available yet")
                .setSingleChoiceItems(names, cur) { d, i ->
                    asked()
                    d.dismiss()
                    if (langs[i].code != fallback) {
                        fallback = langs[i].code
                        lastTag = null
                        a.recreate()
                    }
                }
                .setOnCancelListener { asked() }
                .setNegativeButton("OK") { _, _ -> asked() }
                .show()
        } catch (e: Exception) {
            L.w("i18n", "ask: ${e.message}")
        }
    }

    // ---- machine translation of the rest of the interface (Lumen Studio: ML Kit, on the phone) ----

    /** On-device translator; :studio provides MlKitTranslator, the other build has none. */
    interface Machine {
        fun supports(code: String): Boolean

        /** Blocking, background thread only. Downloads the model once. Null in the result = not translated. */
        fun translate(code: String, texts: List<String>, progress: (Int) -> Unit): List<String?>
    }

    val machine: Machine? by lazy {
        try {
            Class.forName("org.schabi.newpipe.lumen.MlKitTranslator").getField("INSTANCE").get(null) as? Machine
        } catch (_: Throwable) {
            null
        }
    }

    /** English texts of the interface that need a translation (assets/lumen_i18n/_keys.json). */
    private val allKeys: List<String> by lazy {
        try {
            val a = org.json.JSONArray(Lumen.app.assets.open("lumen_i18n/_keys.json").use { it.readBytes().toString(Charsets.UTF_8) })
            List(a.length()) { a.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Keys without a translation in the current catalogue of [code]. */
    fun missing(code: String): List<String> {
        val m = catalog(code)
        return allKeys.filter { lookup(m, it) == null }
    }

    private fun mark(code: String, suffix: String) = "ml_${suffix}_$code"

    /** Not ru/en, the phone can translate it, many texts are missing and the user has not answered for these texts. */
    fun needsMachine(): Boolean {
        val code = Settings.lang
        if (code == "ru" || code == "en" || machine?.supports(code) != true) return false
        if (Lumen.prefs.getInt(mark(code, "done"), -1) == allKeys.size || Lumen.prefs.getInt(mark(code, "skip"), -1) == allKeys.size) return false
        return missing(code).size > 20
    }

    @Volatile var translating = false
        private set

    /** Translates the missing texts of the current language into the cache; [onDone] on the main thread. */
    fun translateRest(onProgress: (Int, Int) -> Unit, onDone: (ok: Boolean) -> Unit) {
        val code = Settings.lang
        val mt = machine
        if (mt == null || translating) return onDone(false)
        translating = true
        Lumen.io.execute {
            var ok = false
            try {
                val todo = missing(code)
                val out = HashMap<String, String>()
                val total = todo.size
                var done = 0
                for (chunk in todo.chunked(60)) {
                    val tr = mt.translate(code, chunk) { }
                    chunk.forEachIndexed { i, k ->
                        val v = tr.getOrNull(i)
                        if (v != null && v.isNotBlank() && samePlaces(k, v)) out[k] = v
                    }
                    done += chunk.size
                    Lumen.ui { onProgress(done, total) }
                }
                val f = File(cacheDir(), "$code.json")
                val m = HashMap<String, String>()
                try {
                    if (f.isFile) readInto(m, f.readText())
                } catch (_: Exception) {
                }
                m.putAll(out)
                val o = JSONObject()
                m.forEach { (k, v) -> o.put(k, v) }
                f.writeText(o.toString())
                synchronized(cats) { cats.remove(code) }
                Lumen.prefs.edit().putInt(mark(code, "done"), allKeys.size).apply()
                ok = out.isNotEmpty()
            } catch (e: Throwable) {
                L.w("i18n", "machine translation: ${e.message}")
            }
            translating = false
            Lumen.ui { onDone(ok) }
        }
    }

    /** "Translate the interface into <language> on this phone?" (once for these texts); [then] after a finished translation. */
    fun offerMachine(a: Activity, then: () -> Unit) {
        val code = Settings.lang
        val name = lang(code)?.native ?: code
        try {
            AlertDialog.Builder(a)
                .setTitle(t("Перевести интерфейс?", "Translate the interface?") + " · $name")
                .setMessage(t(
                    "Часть текстов ещё не переведена. Lumen Studio может перевести их на этом телефоне: один раз скачается модель языка (около 30 МБ), сами тексты никуда не отправляются.",
                    "Some texts are not translated yet. Lumen Studio can translate them on this phone: a language model (about 30 MB) is downloaded once, the texts themselves never leave the device."))
                .setPositiveButton(t("Перевести", "Translate")) { _, _ -> runMachine(a, then) }
                .setNegativeButton(t("Не сейчас", "Not now")) { _, _ ->
                    Lumen.prefs.edit().putInt(mark(code, "skip"), allKeys.size).apply()
                }
                .setCancelable(false)
                .show()
        } catch (e: Exception) {
            L.w("i18n", "offer: ${e.message}")
        }
    }

    /** Runs the translation with a progress dialog, then [then] (normally recreate). */
    fun runMachine(a: Activity, then: () -> Unit) {
        val dlg = try {
            AlertDialog.Builder(a).setTitle(t("Перевожу интерфейс…", "Translating the interface…"))
                .setMessage(t("Скачивание модели и перевод…", "Downloading the model and translating…"))
                .setCancelable(false).show()
        } catch (_: Exception) {
            null
        }
        translateRest({ n, total -> dlg?.setMessage("$n / $total") }) { ok ->
            try {
                dlg?.dismiss()
            } catch (_: Exception) {
            }
            if (ok) then() else Lumen.ui { android.widget.Toast.makeText(a, t("Не удалось: нужен интернет для загрузки модели", "Failed: the model needs a download, check the internet"), android.widget.Toast.LENGTH_LONG).show() }
        }
    }
}
