/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bots on the device: files/lumen/bots/<id>/ — bot.json, code.js, versions/, db.json, users.json, state.json, log.jsonl.
 * Tokens are encrypted in KeyVault ("tgbot_<id>").
 */
object Bots {
    private val lock = Any()
    private val root: File get() = File(Store.root, "bots").also { it.mkdirs() }

    private fun dir(id: String) = File(root, id).also { it.mkdirs() }

    private fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    fun list(): List<BotInfo> = synchronized(lock) {
        root.listFiles()?.mapNotNull { d ->
            val f = File(d, "bot.json")
            if (!f.isFile) null else try {
                BotInfo.from(JSONObject(f.readText()))
            } catch (e: Exception) {
                null
            }
        }?.sortedBy { it.createdAt } ?: emptyList()
    }

    fun get(id: String): BotInfo? = synchronized(lock) {
        val f = File(File(root, id), "bot.json")
        if (!f.isFile) null else try {
            BotInfo.from(JSONObject(f.readText()))
        } catch (e: Exception) {
            null
        }
    }

    fun save(b: BotInfo) = synchronized(lock) {
        writeAtomic(File(dir(b.id), "bot.json"), b.toJson().toString(1))
    }

    fun update(id: String, change: (BotInfo) -> Unit): BotInfo? = synchronized(lock) {
        val b = get(id) ?: return null
        change(b)
        b.updatedAt = System.currentTimeMillis()
        save(b)
        b
    }

    fun delete(id: String) = synchronized(lock) {
        KeyVault.put(tokenKey(id), "")
        File(root, id).deleteRecursively()
    }

    fun newId(): String = "b" + System.currentTimeMillis().toString(36) + (100..999).random()

    // ---- token ----
    private fun tokenKey(id: String) = "tgbot_$id"
    fun token(id: String): String? = KeyVault.get(tokenKey(id))
    fun setToken(id: String, token: String) = KeyVault.put(tokenKey(id), token.trim())

    // ---- code and versions ----
    fun code(id: String): String = synchronized(lock) { File(dir(id), "code.js").takeIf { it.isFile }?.readText().orEmpty() }

    /** Saves the code, keeps the previous one in versions/ (up to 20) and bumps codeVersion so running workers reload. */
    fun saveCode(id: String, code: String, note: String) = synchronized(lock) {
        val f = File(dir(id), "code.js")
        val vdir = File(dir(id), "versions").also { it.mkdirs() }
        val now = System.currentTimeMillis()
        writeAtomic(File(vdir, "$now.js"), code)
        writeAtomic(File(vdir, "$now.txt"), note)
        vdir.listFiles { x -> x.name.endsWith(".js") }?.sortedByDescending { it.name }?.drop(20)?.forEach {
            it.delete()
            File(vdir, it.name.removeSuffix(".js") + ".txt").delete()
        }
        writeAtomic(f, code)
        update(id) { it.codeVersion = now }
    }

    class Version(val at: Long, val note: String, val file: File)

    fun versions(id: String): List<Version> = synchronized(lock) {
        val vdir = File(dir(id), "versions")
        vdir.listFiles { x -> x.name.endsWith(".js") }?.mapNotNull { f ->
            val at = f.name.removeSuffix(".js").toLongOrNull() ?: return@mapNotNull null
            Version(at, File(vdir, "$at.txt").takeIf { it.isFile }?.readText().orEmpty(), f)
        }?.sortedByDescending { it.at } ?: emptyList()
    }

    // ---- mini app ----
    fun miniApp(id: String): String = synchronized(lock) { File(dir(id), "miniapp.html").takeIf { it.isFile }?.readText().orEmpty() }

    /** Saves the page; the previous one stays in miniapp.prev.html for "undo". */
    fun saveMiniApp(id: String, html: String) = synchronized(lock) {
        val f = File(dir(id), "miniapp.html")
        if (f.isFile) f.copyTo(File(dir(id), "miniapp.prev.html"), overwrite = true)
        writeAtomic(f, html)
    }

    fun deleteMiniApp(id: String) = synchronized(lock) {
        File(dir(id), "miniapp.html").delete()
        File(dir(id), "miniapp.prev.html").delete()
    }

    fun hasPrevMiniApp(id: String): Boolean = File(File(root, id), "miniapp.prev.html").isFile

    fun undoMiniApp(id: String) = synchronized(lock) {
        val prev = File(dir(id), "miniapp.prev.html")
        if (prev.isFile) {
            writeAtomic(File(dir(id), "miniapp.html"), prev.readText())
            prev.delete()
        }
    }

    // ---- journal ----
    fun log(id: String, kind: String, chat: Long, who: String, text: String) = synchronized(lock) {
        val f = File(dir(id), "log.jsonl")
        f.appendText(BotLogEntry(System.currentTimeMillis(), kind, chat, who, text.take(2000)).toJson().toString() + "\n")
        if (f.length() > 400_000) {
            val keep = f.readLines().takeLast(800)
            writeAtomic(f, keep.joinToString("\n") + "\n")
        }
    }

    fun logs(id: String): List<BotLogEntry> = synchronized(lock) {
        val f = File(dir(id), "log.jsonl")
        if (!f.isFile) emptyList() else BotLogEntry.parseLines(f.readText())
    }

    fun clearLogs(id: String) = synchronized(lock) { File(dir(id), "log.jsonl").delete() }

    // ---- polling offset ----
    fun offset(id: String): Long = synchronized(lock) {
        File(dir(id), "state.json").takeIf { it.isFile }?.let {
            try {
                JSONObject(it.readText()).optLong("offset")
            } catch (e: Exception) {
                0L
            }
        } ?: 0L
    }

    fun setOffset(id: String, offset: Long) = synchronized(lock) {
        writeAtomic(File(dir(id), "state.json"), JSONObject().put("offset", offset).toString())
    }

    // ---- users who wrote to the bot ----
    /** Remembers the user; returns true if it is the first message from them. */
    fun touchUser(id: String, u: TgUser): Boolean = synchronized(lock) {
        val f = File(dir(id), "users.json")
        val o = if (f.isFile) try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            JSONObject()
        } else JSONObject()
        val key = u.id.toString()
        val isNew = !o.has(key)
        val now = System.currentTimeMillis()
        val rec = o.optJSONObject(key) ?: JSONObject().put("first", now).put("n", 0)
        rec.put("name", u.name).put("username", u.username).put("last", now).put("n", rec.optInt("n") + 1)
        o.put(key, rec)
        writeAtomic(f, o.toString())
        isNew
    }

    fun users(id: String): JSONArray = synchronized(lock) {
        val f = File(dir(id), "users.json")
        val out = JSONArray()
        if (!f.isFile) return out
        val o = try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            return out
        }
        o.keys().forEach { k ->
            val r = o.optJSONObject(k) ?: return@forEach
            out.put(JSONObject().put("id", k.toLongOrNull() ?: 0).put("name", r.optString("name")).put("username", r.optString("username"))
                .put("n", r.optInt("n")).put("last", r.optLong("last")))
        }
        out
    }

    // ---- the bot's own data (db.get/db.set) ----
    fun db(id: String): BotDb = synchronized(dbs) { dbs.getOrPut(id) { BotDb(File(dir(id), "db.json")) } }

    fun dropDb(id: String) = synchronized(dbs) {
        dbs.remove(id)
        File(dir(id), "db.json").delete()
    }

    private val dbs = HashMap<String, BotDb>()
}

/** Key → JSON string, saved on every change (bots write little). */
class BotDb(private val file: File) {
    private val map: JSONObject by lazy {
        try {
            if (file.isFile) JSONObject(file.readText()) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    @Synchronized fun get(key: String): String? = if (map.has(key)) map.optString(key) else null

    @Synchronized fun set(key: String, json: String?) {
        if (json == null) map.remove(key) else map.put(key, json)
        val text = map.toString()
        if (text.length > 5_000_000) throw IllegalStateException("bot storage is over 5 MB")
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) file.writeText(text)
    }

    @Synchronized fun keys(prefix: String): List<String> = map.keys().asSequence().filter { it.startsWith(prefix) }.sorted().toList()

    @Synchronized fun size(): Int = map.length()
}
