/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Free AI on the device: the catalog (bundled asset, then a newer copy from the repo, rebuilt from FreeLLMAPI by
 * .github/workflows/freeai-sync.yml), keys in [KeyVault] as free_<provider>, providers switched off, today's use.
 */
object FreeAiStore {
    const val URL = "https://raw.githubusercontent.com/psyte92837/Yt-fr/main/app/src/main/assets/lumen_freeai.json"
    private const val ASSET = "lumen_freeai.json"
    private const val CHECK_EVERY = 3 * 24 * 3600_000L
    private val file get() = File(Store.root, "freeai/catalog.json")
    private val lock = Any()

    @Volatile private var cached: FreeCatalog? = null
    private var use: FreeUse? = null

    fun catalog(): FreeCatalog = cached ?: synchronized(lock) {
        cached ?: run {
            val asset = runCatching { Lumen.app.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull()?.let(FreeAi::parse)
            val saved = runCatching { file.readText() }.getOrNull()?.let(FreeAi::parse)
            // a newer app ships a newer catalog than the one downloaded earlier
            val pick = listOfNotNull(asset, saved).maxByOrNull { it.version } ?: FreeCatalog("", "", "", emptyList(), emptyList())
            pick.also { cached = it }
        }
    }

    fun keyName(provId: String) = "free_$provId"
    fun has(provId: String) = KeyVault.has(keyName(provId))
    fun key(provId: String): String? = KeyVault.get(keyName(provId))
    fun setKey(provId: String, key: String) = KeyVault.put(keyName(provId), key)

    fun endpoint(p: FreeProv) = LlmEndpoint(if (p.api == "gemini") ApiKind.GEMINI else ApiKind.OPENAI_COMPAT, p.base, key(p.id).orEmpty(),
        extra = if (p.id == "openrouter") mapOf("HTTP-Referer" to "https://github.com/lumen-analyzer", "X-Title" to "Lumen") else emptyMap())

    var off: Set<String>
        get() = Lumen.prefs.getStringSet("freeai_off", emptySet()) ?: emptySet()
        set(v) = Lumen.prefs.edit().putStringSet("freeai_off", v).apply()

    val lastCheck: Long get() = Lumen.prefs.getLong("freeai_checked", 0)

    /** Providers the manager can call now: a key, or keyless and not switched off. */
    fun usable(): List<FreeProv> = catalog().providers.filter { (it.keyless || has(it.id)) && it.id !in off }

    private fun state(now: Long): FreeUse {
        val u = use ?: FreeAi.useOf(Lumen.prefs.getString("freeai_use", null)).also { use = it }
        FreeAi.roll(u, now)
        return u
    }

    private fun save(u: FreeUse) = Lumen.prefs.edit().putString("freeai_use", FreeAi.useJson(u)).apply()

    fun candidates(cat: FreeCatalog, need: FreeAi.Need): List<FreeModel> = synchronized(lock) {
        val now = System.currentTimeMillis()
        FreeAi.candidates(cat, ::has, state(now), now, need, off)
    }

    fun ok(m: FreeModel, tokens: Int) = synchronized(lock) {
        val u = state(System.currentTimeMillis())
        FreeAi.succeeded(u, m, tokens)
        save(u)
    }

    fun fail(m: FreeModel, f: FreeAi.Fail, msg: String) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val u = state(now)
        FreeAi.failed(u, m, f, msg, now)
        save(u)
    }

    /** Copy of today's use for the screen. */
    fun snapshot(): FreeUse = synchronized(lock) {
        val u = state(System.currentTimeMillis())
        FreeUse(u.day, u.slots.mapValues { it.value.copy() }.toMutableMap())
    }

    fun clearCooldowns() = synchronized(lock) {
        val u = state(System.currentTimeMillis())
        u.slots.values.forEach { it.coolUntil = 0; it.fails = 0; it.err = "" }
        save(u)
    }

    /** Called before a free call: at most every 3 days, failures are ignored (the current catalog keeps working). */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - lastCheck < CHECK_EVERY) return
        runCatching { update() }.onFailure { L.w("freeai", "catalog check failed: ${it.message}") }
    }

    /** Downloads the catalog; true when a newer one was saved. */
    fun update(): Boolean {
        Lumen.prefs.edit().putLong("freeai_checked", System.currentTimeMillis()).apply()
        val c = URL(URL).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        val text = try {
            if (c.responseCode != 200) throw LlmException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val next = FreeAi.parse(text) ?: throw LlmException(t("каталог не прочитан", "catalog not readable"))
        synchronized(lock) {
            if (!FreeAi.newer(catalog(), next)) return false
            file.parentFile?.mkdirs()
            file.writeText(text)
            cached = next
        }
        L.i("freeai", "catalog ${next.version} ${next.hash}: ${next.providers.size} providers, ${next.models.size} models")
        return true
    }
}
