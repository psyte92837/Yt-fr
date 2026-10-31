/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONObject
import java.io.File

/**
 * Templates of Lumen Studio invented by the built-in AI: motion templates (lumen.motion) and ways of editing (lumen.edit).
 * New from a description, edits by a prompt (JSON Patch), previews on sample data, an AI look at the preview,
 * copying the code and pasting an answer of any other AI.
 */
class StudioTemplatesActivity : LumenBase() {
    companion object {
        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, StudioTemplatesActivity::class.java)
                .addFlags(if (ctx is android.app.Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun store(ctx: Context) = StudioTplStore(File(ctx.filesDir, "lumen/studio/templates"))
    }

    private val store by lazy { store(this) }
    private var tab = 0
    private var busy = ""
    private var status: android.widget.TextView? = null
    @Volatile private var cancel = false
    private val previews = HashMap<String, List<Bitmap>>()

    private val motion get() = tab == 0
    private val kind get() = if (motion) "motion" else "edit"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onDestroy() {
        cancel = true
        super.onDestroy()
    }

    private fun render() {
        val c = Col(this)
        c.add(segBar(this, listOf(t("✨ Моушн", "✨ Motion"), t("🎬 Монтаж", "🎬 Editing")), tab) {
            tab = it
            render()
        }, 0)
        c.muted(if (motion) t(
            "Моушн-шаблон — анимированная графика сцены: слои, фигуры, тексты и эмодзи на ключевых кадрах, пункты появляются под слова. AI-режиссёр ставит его в сцены как вид «tpl:…».",
            "A motion template is the animated graphic of a scene: layers, shapes, texts and emoji on key frames, items appear with their words. The AI director puts it into scenes as kind \"tpl:…\"")
        else t(
            "Подход к монтажу — правила для всего ролика: темп сцен, зумы, переходы, звуки, плашки и какие виды графики когда. Работает без AI и токенов: кнопка появится в шаге 3 Studio.",
            "A way of editing is the rules for the whole video: scene pace, zooms, transitions, sounds, stickers and which graphics when. It runs without AI or tokens: its button shows in step 3 of the Studio."))
        if (busy.isNotEmpty()) {
            status = c.working(busy).first
            c.button(t("Отмена", "Cancel"), style = BtnStyle.TEXT) { cancel = true }
        } else {
            c.row(
                btn(this, t("✨ Новый по описанию", "✨ New from a description"), style = BtnStyle.TONAL) { ask(null) },
                btn(this, t("🤖 Через другой ИИ", "🤖 With another AI"), style = BtnStyle.TEXT) { askOther() }
            )
            c.button(t("📥 Вставить код из буфера", "📥 Paste code from the clipboard"), style = BtnStyle.TEXT) { paste() }
        }
        val list: List<Pair<String, String>> = if (motion) store.motions().map { it.id to it.name } else store.edits().map { it.id to it.name }
        if (list.isEmpty()) c.muted(t("Пока нет шаблонов. Опишите словами, что нужно: например «список, где пункты вылетают из-за края с подчёркиванием».",
            "No templates yet. Describe what you need, e.g. \"a list whose items fly in from the edge with an underline\"."))
        if (motion) store.motions().forEach { t0 -> motionCard(c, t0) } else store.edits().forEach { t0 -> editCard(c, t0) }
        page(t("Шаблоны Studio", "Studio templates"), c.root, keepScroll = true)
    }

    private fun motionCard(c: Col, m: MotionTpl) = c.card {
        add(text(ctx, m.name, Type.TITLE_M, bold = true), 0)
        muted("tpl:${m.id}" + if (m.use.isNotBlank()) " · ${m.use}" else "")
        previews[m.id]?.let { frames ->
            val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            frames.forEach { b ->
                strip.addView(ImageView(ctx).apply {
                    setImageBitmap(b)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = roundBg(0x00000000, 6f, ctx, Palette.OUTLINE)
                }, LinearLayout.LayoutParams(dp(72), dp(128)).apply { rightMargin = dp(4) })
            }
            add(strip, 8)
        }
        if (busy.isEmpty()) {
            row(
                btn(ctx, t("🎞 Превью", "🎞 Preview"), style = BtnStyle.TONAL) { preview(m) },
                btn(ctx, t("✏️ Править", "✏️ Edit"), style = BtnStyle.TEXT) { ask(m.id) }
            )
            row(
                btn(ctx, t("👁 AI-проверка", "👁 AI look"), style = BtnStyle.TEXT) { look(m) },
                btn(ctx, t("⧉ Код", "⧉ Code"), style = BtnStyle.TEXT) { copy(JSONObject(m.json).toString(2)) },
                btn(ctx, "🗑", style = BtnStyle.TEXT) { remove(m.id, m.name) }
            )
        }
    }

    private fun editCard(c: Col, e: EditTpl) = c.card {
        add(text(ctx, e.name, Type.TITLE_M, bold = true), 0)
        if (e.use.isNotBlank()) muted(e.use)
        muted(t("Сцены ${e.minWords}–${e.maxWords} слов · пауза ${e.pauseMs} мс · зум каждой ${e.punchEvery}-й", "Scenes of ${e.minWords}–${e.maxWords} words · pause ${e.pauseMs} ms · zoom every ${e.punchEvery}"))
        muted(StudioEditTpl.summary(e))
        if (busy.isEmpty()) row(
            btn(ctx, t("✏️ Править", "✏️ Edit"), style = BtnStyle.TONAL) { ask(e.id) },
            btn(ctx, t("⧉ Код", "⧉ Code"), style = BtnStyle.TEXT) { copy(JSONObject(e.json).toString(2)) },
            btn(ctx, "🗑", style = BtnStyle.TEXT) { remove(e.id, e.name) }
        )
    }

    // ---------- AI ----------

    /** One line per motion template, for prompts that may use them. */
    private fun tplLine() = store.motions().joinToString("; ") { StudioTplCatalog.motionLine(it) }.ifBlank { t("пока нет", "none yet") }

    private fun say(s: String) = Lumen.ui { status?.text = s }

    private fun ai(key: String, prompt: String, limit: Int, image: String? = null): String {
        if (cancel) throw java.util.concurrent.CancellationException()
        val prov = AiTasks.requireProvider()
        val r = Llm.complete(prov, prov.smartModel, "You design motion graphics and video editing templates. Answer with JSON only.", prompt,
            json = image == null, maxTokens = limit, task = key, pid = "studio", image = image)
        return r.text
    }

    /** Checks [text] as a template of the current kind; asks the AI to fix up to twice; saves it (as [own] when editing). */
    private fun checkAndSave(text0: String, own: String?, useAi: Boolean): String {
        var text = text0
        repeat(3) { attempt ->
            val errors: List<String>
            val ok: Boolean
            if (motion) {
                val r = StudioMotionTpl.parse(text)
                errors = r.errors
                ok = r.value != null
                if (r.value != null && (errors.isEmpty() || attempt == 2 || !useAi)) {
                    val t0 = r.value.copy(id = store.freeId("motion", own ?: r.value.id, own))
                    store.saveMotion(t0)
                    previews.remove(t0.id)
                    return t0.id
                }
            } else {
                val r = StudioEditTpl.parse(text)
                errors = r.errors
                ok = r.value != null
                if (r.value != null && (errors.isEmpty() || attempt == 2 || !useAi)) {
                    val t0 = r.value.copy(id = store.freeId("edit", own ?: r.value.id, own))
                    store.saveEdit(t0)
                    return t0.id
                }
            }
            if (!useAi || attempt == 2) throw IllegalStateException(t("Шаблон не прошёл проверку:\n", "The template failed the check:\n") + errors.take(8).joinToString("\n"))
            say(t("Исправление ошибок (${attempt + 1})…", "Fixing errors (${attempt + 1})…"))
            L.i("studio", "template fix ${attempt + 1}: ${errors.take(3)} ok=$ok")
            text = ai("studio_tpl_fix", Prompts.fill("studio_tpl_fix", mapOf(
                "kind" to "lumen.$kind", "errors" to errors.take(12).joinToString("\n"), "data" to (JsonText.firstObject(text) ?: text).take(12_000)
            )), 6000)
        }
        error("unreachable")
    }

    /** New template ([id] null) or an edit of template [id], from words typed in a sheet. */
    private fun ask(id: String?) {
        bottomSheet(if (id == null) t("Новый шаблон", "New template") else t("Что изменить?", "What to change?")) { dismiss ->
            val e = edit(if (id == null) (if (motion) t("Например: цифра вырастает из точки, вокруг рисуется кольцо, снизу подпись", "E.g.: a number grows from a dot, a ring draws around it, a label below")
            else t("Например: спокойный научпоп — длинные сцены, мягкие переходы, цифры диаграммами, редкие зумы", "E.g.: calm science explainer — long scenes, soft transitions, numbers as charts, rare zooms"))
            else t("Например: быстрее, стрелки жёлтые, пункты слева", "E.g.: faster, yellow arrows, items on the left"), multiline = true)
            button(if (id == null) t("✨ Создать", "✨ Create") else t("✏️ Изменить", "✏️ Change"), primary = true) {
                val s = e.text.toString().trim()
                if (s.isEmpty()) return@button
                dismiss()
                if (id == null) create(s) else change(id, s)
            }
        }
    }

    private fun run(msg: String, work: () -> String, done: (String) -> Unit = {}) {
        busy = msg
        cancel = false
        render()
        Lumen.async({ work() }, { e ->
            busy = ""
            render()
            if (e !is java.util.concurrent.CancellationException) err(e)
        }) { r ->
            busy = ""
            render()
            done(r)
        }
    }

    private fun create(askText: String) = run(t("AI придумывает шаблон…", "AI is designing the template…"), {
        val key = if (motion) "studio_tpl_motion" else "studio_tpl_edit"
        val reply = ai(key, Prompts.fill(key, mapOf("ask" to askText, "tpls" to tplLine())), 6000)
        say(t("Проверка…", "Checking…"))
        checkAndSave(reply, null, true)
    }) { id ->
        toast(t("Шаблон сохранён: $id", "Template saved: $id"))
        if (motion) store.motions().firstOrNull { it.id == id }?.let { preview(it) }
    }

    private fun currentJson(id: String): String? = if (motion) store.motions().firstOrNull { it.id == id }?.json else store.edits().firstOrNull { it.id == id }?.json

    /** Edit by a prompt: the AI sends a JSON Patch (or the whole template). */
    private fun change(id: String, askText: String) = run(t("AI меняет шаблон…", "AI is changing the template…"), {
        val cur = currentJson(id) ?: error("no template")
        val reply = ai("studio_tpl_patch", Prompts.fill("studio_tpl_patch", mapOf("kind" to "lumen.$kind", "ask" to askText, "data" to cur)), 3000)
        applyReply(id, cur, reply)
    }) { done ->
        toast(t("Изменено: $done", "Changed: $done"))
        if (motion) store.motions().firstOrNull { it.id == id }?.let { preview(it) }
    }

    /** A patch reply applied to [cur], or a whole template; then checked and saved as [id]. */
    private fun applyReply(id: String, cur: String, reply: String): String {
        val ops = JsonPatch.ops(reply)
        val text = if (ops != null) {
            if (ops.length() == 0) return id
            JsonPatch.apply(JSONObject(cur), ops)?.toString() ?: throw IllegalStateException(t("AI прислал изменения, которые не подходят к шаблону", "The AI sent changes that do not fit the template"))
        } else reply
        return checkAndSave(text, id, true)
    }

    /** Frames of a motion template on sample data (9:16, dark). */
    private fun frames(m: MotionTpl): List<Bitmap> {
        val sc = StudioMotionTpl.sample().copy(kind = "tpl:" + m.id)
        val toks = Studio.tokens(sc.say)
        val words = toks.mapIndexed { i, w -> SWord(w, 200L + i * 330L, 200L + i * 330L + 280) }
        val total = words.last().endMs + 1200
        val plan = StudioPlan.build(SScript("", listOf(sc)), words, total, StudioStyle.DARK, StudioPrefs.accent, listOf(words.indices),
            templates = mapOf(m.id to m))
        return listOf(0.12, 0.35, 0.6, 0.9).map { f -> StudioVideo.preview(plan, (total * f).toLong(), 180, 320) }
    }

    private fun preview(m: MotionTpl) = run(t("Превью…", "Preview…"), {
        previews[m.id] = frames(m)
        m.id
    })

    /** The AI looks at the preview frames and sends fixes (needs a model that sees images). */
    private fun look(m: MotionTpl) = run(t("AI смотрит на превью…", "AI is looking at the preview…"), {
        val fr = previews[m.id] ?: frames(m).also { previews[m.id] = it }
        val strip = Bitmap.createBitmap(fr.sumOf { it.width }, fr.maxOf { it.height }, Bitmap.Config.ARGB_8888)
        val cv = Canvas(strip)
        var x = 0f
        fr.forEach {
            cv.drawBitmap(it, x, 0f, null)
            x += it.width
        }
        val bytes = java.io.ByteArrayOutputStream().also { strip.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        strip.recycle()
        val img = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val reply = ai("studio_tpl_look", Prompts.fill("studio_tpl_look", mapOf("name" to m.name, "use" to m.use, "data" to m.json)), 3000, img)
        val ops = JsonPatch.ops(reply)
        if (ops == null || ops.length() == 0) "ok" else {
            applyReply(m.id, m.json, reply)
            previews[m.id] = store.motions().firstOrNull { it.id == m.id }?.let { frames(it) } ?: fr
            "fixed"
        }
    }) { r -> toast(if (r == "ok") t("AI: всё хорошо", "AI: looks good") else t("AI поправил шаблон", "AI fixed the template")) }

    // ---------- other AI, code ----------

    /** Copies a ready request for any AI; its answer is pasted with "📥 Paste". */
    private fun askOther() {
        bottomSheet(t("Запрос для другого ИИ", "Request for another AI")) { dismiss ->
            val e = edit(t("Что за шаблон нужен?", "What template do you need?"), multiline = true)
            button(t("⧉ Скопировать запрос", "⧉ Copy the request"), primary = true) {
                val s = e.text.toString().trim()
                if (s.isEmpty()) return@button
                dismiss()
                val key = if (motion) "studio_tpl_motion" else "studio_tpl_edit"
                copy(Prompts.fill(key, mapOf("ask" to s, "tpls" to tplLine())))
                toast(t("Вставьте запрос в ChatGPT, Gemini или другой ИИ, скопируйте ответ и нажмите «📥 Вставить код»",
                    "Paste the request into ChatGPT, Gemini or any AI, copy the answer and tap \"📥 Paste code\""))
            }
        }
    }

    private fun paste() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            toast(t("Буфер пуст", "The clipboard is empty"))
            return
        }
        val o = JsonText.firstObject(text)?.let { runCatching { JSONObject(it) }.getOrNull() }
        when {
            o?.optString("lumen") == "edit" || (o?.has("rules") == true && !o.has("layers")) -> tab = 1
            o?.optString("lumen") == "motion" || o?.has("layers") == true -> tab = 0
        }
        val useAi = Provider.active() != null
        run(t("Проверка шаблона…", "Checking the template…"), { checkAndSave(text, null, useAi) }) { id ->
            toast(t("Шаблон сохранён: $id", "Template saved: $id"))
        }
    }

    private fun copy(s: String) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("lumen", s))
        toast(t("Скопировано", "Copied"))
    }

    private fun remove(id: String, name: String) = confirm(t("Удалить «$name»?", "Delete \"$name\"?"), "", t("Удалить", "Delete")) {
        store.delete(kind, id)
        previews.remove(id)
        render()
    }
}
