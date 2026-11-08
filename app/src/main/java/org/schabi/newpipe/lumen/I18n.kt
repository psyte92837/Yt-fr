/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.Activity
import android.app.AlertDialog
import org.json.JSONObject
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

    /** The catalogue of [code] (empty when the app has none: Lumen itself ships only ru/en). */
    fun catalog(code: String): Map<String, String> {
        synchronized(cats) { cats[code]?.let { return it } }
        val m = HashMap<String, String>()
        try {
            val text = Lumen.app.assets.open("lumen_i18n/$code.json").use { it.readBytes().toString(Charsets.UTF_8) }
            val o = JSONObject(text)
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.optString(k)
                if (v.isNotEmpty() && samePlaces(k, v)) m[k] = v
            }
        } catch (_: Exception) {
        }
        synchronized(cats) { cats[code] = m }
        return m
    }

    /** Languages this build can show: ru, en and those with a catalogue. */
    val available: List<Lang> by lazy {
        LANGS.filter { it.code == "ru" || it.code == "en" || hasCatalog(it.code) }
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
            else -> catalog(l)[en] ?: en
        }
    }

    fun pickF(ru: String, key: String, args: Array<out Any?>): String {
        if (!LumenEnv.ready) return ru
        return when (val l = Settings.lang) {
            "ru" -> ru
            "en" -> format(key, args)
            else -> format(catalog(l)[key] ?: key, args)
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
}
