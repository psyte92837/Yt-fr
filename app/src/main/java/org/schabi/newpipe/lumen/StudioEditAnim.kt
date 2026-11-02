/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.TreeSet
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/**
 * Animation blocks of the editor (kind "anim", lumen.anim v1): the code is kept in the project folder as
 * anim/<code hash>/code.js, its frames in anim/<code hash>/r_<render key>/f0000.webp… with meta.json; [EBlock.file]
 * names the render folder ("anim/<hash>/r_<key>") or, before the first render, the code folder.
 */
object AnimRender {
    const val DIR = "anim"
    private const val CODE = "code.js"
    private const val META = "meta.json"
    private const val PART = "partial.json"

    fun codeRel(b: EBlock): String = b.file.substringBefore("/r_")

    fun renderRel(codeRel: String, key: String): String = "$codeRel/r_$key"

    fun keyOf(b: EBlock): String = b.file.substringAfter("/r_", "")

    fun frameName(i: Int): String = String.format(Locale.US, "f%04d.webp", i)

    /** Writes [code] into the project folder [dir]; returns its folder (relative) — the same code, the same folder. */
    fun saveCode(dir: File, code: String): String {
        val rel = DIR + "/" + EditInsert.codeHash(code)
        val d = File(dir, rel).apply { mkdirs() }
        val f = File(d, CODE)
        if (!f.exists() || f.readText() != code) f.writeText(code)
        return rel
    }

    fun code(dir: File, b: EBlock): String? = if (b.kind != EditInsert.KIND || b.file.isEmpty()) null
        else File(File(dir, codeRel(b)), CODE).takeIf { it.exists() }?.readText()

    /** Meta of the frames the block shows now, null before its first render. */
    fun meta(dir: File, b: EBlock): AnimMeta? = if (!b.file.contains("/r_")) null else AnimFrames.meta(File(dir, b.file))

    fun metaOf(f: File): AnimMeta? = File(f, META).takeIf { it.exists() }?.let { runCatching { AnimMeta.of(JSONObject(it.readText())) }.getOrNull() }

    /**
     * Renders [code] with [data] into [out]: every frame as WebP with transparency (an empty frame — no file), then meta.json.
     * Continues an interrupted render of the same [key] from its first missing frame. Worker thread; [host] in the window.
     */
    fun run(host: CanvasHost, code: String, data: JSONObject, out: File, key: String, durMs: Long, fps: Int, loopMs: Long,
            cancel: () -> Boolean, progress: (Int, Int) -> Unit): AnimMeta {
        val w = data.optInt("w")
        val h = data.optInt("h")
        val count = EditInsert.frameCount(durMs, fps, loopMs)
        out.mkdirs()
        val part = File(out, PART)
        val empty = TreeSet<Int>()
        var start = 0
        runCatching { JSONObject(part.readText()) }.getOrNull()?.takeIf { it.optString("key") == key }?.let { o ->
            start = o.optInt("next").coerceIn(0, count)
            o.optJSONArray("empty")?.let { a -> for (i in 0 until a.length()) empty += a.optInt(i) }
        }
        fun savePart(next: Int) = runCatching {
            part.writeText(JSONObject().put("key", key).put("next", next).put("empty", JSONArray().apply { empty.forEach { put(it) } }).toString())
        }
        host.load(code, data)
        var next = host.frame(start.toDouble() / fps)
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        for (f in start until count) {
            if (cancel()) {
                savePart(f)
                throw CancellationException()
            }
            BgTasks.checkpoint()
            val res = next()
            // the WebView draws the next frame while this one is being saved
            if (f + 1 < count) next = host.frame((f + 1).toDouble() / fps)
            val bmp = try {
                host.bitmap(res)
            } catch (e: CanvasHost.LayerError) {
                savePart(f)
                throw CanvasHost.LayerError(e.message.orEmpty() + String.format(Locale.US, t(" (кадр %d, %.2f с)", " (frame %d, %.2f s)"), f, f.toDouble() / fps))
            }
            val file = File(out, frameName(f))
            if (bmp == null) {
                empty += f
                file.delete()
            } else {
                file.outputStream().use { bmp.compress(fmt, 90, it) }
                bmp.recycle()
            }
            if (f % 10 == 9) savePart(f + 1)
            progress(f + 1, count)
        }
        val meta = AnimMeta(key, fps, w, h, count, durMs, loopMs, empty)
        File(out, META).writeText(meta.json().toString())
        part.delete()
        AnimFrames.forget(out)
        return meta
    }

    /** A trial run: frames at [times] seconds, the slowest frame, JS errors, all empty, the whole block opaque. */
    class Trial(val frames: List<Bitmap>, val msPerFrame: Double, val errors: List<String>, val allEmpty: Boolean, val opaque: Boolean)

    fun trial(host: CanvasHost, code: String, data: JSONObject, times: List<Double>): Trial {
        val errors = mutableListOf<String>()
        try {
            host.load(code, data)
        } catch (e: CanvasHost.LayerError) {
            return Trial(emptyList(), 0.0, listOf(e.message.orEmpty()), false, false)
        }
        val frames = mutableListOf<Bitmap>()
        var slow = 0.0
        var empty = 0
        var opaque = 0
        for (s in times) {
            BgTasks.checkpoint()
            val t0 = System.nanoTime()
            val res = host.frame(s)()
            slow = maxOf(slow, (System.nanoTime() - t0) / 1e6)
            val b = try {
                host.bitmap(res)
            } catch (e: CanvasHost.LayerError) {
                errors += e.message.orEmpty()
                null
            }
            if (b == null) empty++ else {
                val pts = listOf(2 to 2, b.width - 3 to 2, 2 to b.height - 3, b.width - 3 to b.height - 3, b.width / 2 to b.height / 2)
                if (pts.all { (x, y) -> x in 0 until b.width && y in 0 until b.height && (b.getPixel(x, y) ushr 24) >= 250 }) opaque++
                frames += b
            }
        }
        return Trial(frames, slow, errors, empty == times.size, opaque == times.size && times.isNotEmpty())
    }

    /** Removes render folders no block of [blocks] shows (the code stays: undo may bring it back). */
    fun clean(dir: File, blocks: List<EBlock>) {
        val keep = blocks.filter { it.kind == EditInsert.KIND }.map { it.file }.toSet()
        File(dir, DIR).listFiles().orEmpty().forEach { code ->
            code.listFiles { f -> f.isDirectory && f.name.startsWith("r_") }.orEmpty().forEach { r ->
                val rel = DIR + "/" + code.name + "/" + r.name
                // an unfinished render of a block whose frames are being made stays for the next run
                if (rel !in keep && !File(r, PART).exists()) r.deleteRecursively()
            }
        }
    }

    /** Size of all frames of the project, bytes. */
    fun size(dir: File): Long = File(dir, DIR).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/**
 * Decoded frames of animation blocks for the preview and the export: a small LRU of bitmaps, metas by folder, and the next
 * frames decoded in the background while playing.
 */
object AnimFrames {
    private const val CAP = 14
    private val metas = HashMap<String, Pair<Long, AnimMeta?>>()
    private val cache = object : LinkedHashMap<String, Bitmap>(CAP + 2, 0.75f, true) {
        // evicted bitmaps are not recycled: the export may still be drawing one of them
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > CAP
    }
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "lumen-anim-frames").apply { isDaemon = true } }
    private val asked = HashSet<String>()

    @Synchronized
    fun meta(dir: File): AnimMeta? {
        val f = File(dir, "meta.json")
        val stamp = f.lastModified()
        metas[dir.path]?.takeIf { it.first == stamp }?.let { return it.second }
        val m = if (stamp == 0L) null else AnimRender.metaOf(dir)
        metas[dir.path] = stamp to m
        return m
    }

    private fun decode(dir: File, i: Int): Bitmap? = File(dir, AnimRender.frameName(i)).takeIf { it.exists() }
        ?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }

    /** Frame [i] of the render in [dir]; null when it is empty or missing (then the nearest earlier one that is there). */
    fun frame(dir: File, i: Int, meta: AnimMeta): Bitmap? {
        if (i in meta.empty) return null
        val key = dir.path + "#" + i
        synchronized(this) { cache[key]?.let { return it } }
        val b = decode(dir, i) ?: (i - 1 downTo maxOf(0, i - 6)).firstNotNullOfOrNull { j -> if (j in meta.empty) null else synchronized(this) { cache[dir.path + "#" + j] } }
        if (b != null) synchronized(this) { cache[key] = b }
        return b
    }

    /** Decodes the next [n] frames after [i] in the background (playback). */
    fun prefetch(dir: File, i: Int, meta: AnimMeta, n: Int = 3) {
        for (k in 1..n) {
            val j = if (meta.loopMs > 0) (i + k) % meta.count else i + k
            if (j >= meta.count || j in meta.empty) continue
            val key = dir.path + "#" + j
            synchronized(this) {
                if (cache.containsKey(key) || !asked.add(key)) return@synchronized
                pool.execute {
                    val b = decode(dir, j)
                    synchronized(this) {
                        asked.remove(key)
                        if (b != null) cache[key] = b
                    }
                }
            }
        }
    }

    @Synchronized
    fun forget(dir: File) {
        metas.remove(dir.path)
        cache.keys.filter { it.startsWith(dir.path + "#") }.forEach { cache.remove(it) }
    }
}

/** A saved scheme or animation of the insert wizard: «📚 Моя библиотека». */
data class SavedInsert(
    val id: String, val name: String, val kind: String, val body: String, val ask: String, val source: String, val created: Long, val updated: Long
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("body", body).put("ask", ask)
        .put("source", source).put("created", created).put("updated", updated)

    companion object {
        fun of(o: JSONObject): SavedInsert? {
            val body = o.optString("body")
            val kind = o.optString("kind")
            if (body.isBlank() || kind !in setOf("scheme", EditInsert.KIND)) return null
            return SavedInsert(o.optString("id"), o.optString("name").ifBlank { "?" }, kind, body, o.optString("ask"), o.optString("source"),
                o.optLong("created"), o.optLong("updated"))
        }
    }
}

/** files/lumen/studio/edit/anims/<id>.json: every scheme or animation once (the same body only goes to the top). */
class InsertLibrary(private val dir: File) {
    private fun file(id: String) = File(dir, "$id.json")

    fun list(): List<SavedInsert> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { SavedInsert.of(JSONObject(f.readText())) }.getOrNull() }.sortedByDescending { it.updated }

    fun get(id: String): SavedInsert? = runCatching { SavedInsert.of(JSONObject(file(id).readText())) }.getOrNull()

    private fun put(s: SavedInsert): SavedInsert {
        dir.mkdirs()
        file(s.id).writeText(s.json().toString())
        return s
    }

    fun add(kind: String, body: String, name: String, ask: String, source: String, now: Long): SavedInsert {
        val b = body.trim()
        list().firstOrNull { it.kind == kind && it.body.trim() == b }?.let { return put(it.copy(updated = now)) }
        val id = "i" + java.lang.Long.toString(now, 36) + Integer.toHexString(b.hashCode() and 0xFFF)
        return put(SavedInsert(id, name.trim().take(40).ifBlank { if (kind == "scheme") t("Схема", "Scheme") else t("Анимация", "Animation") }, kind, b, ask, source, now, now))
    }

    fun rename(id: String, name: String): SavedInsert? = get(id)?.let { put(it.copy(name = name.trim().take(40).ifBlank { it.name })) }

    fun delete(id: String) = file(id).delete()
}
