/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText

val DIAGRAM_PROMPTS = linkedMapOf(
    "diagram_flow" to (
        "Составь блок-схему процесса по тексту ниже. Тема: \"{phrase}\". Язык подписей: {lang}.\n" +
            "Правила: один блок START («Начало» или входное условие) и хотя бы один блок END; шаги — коротко, до 8 слов; " +
            "условия оформляй блоком DECISION с двумя исходящими связями, подписанными «Да» и «Нет»; не больше 25 блоков; " +
            "используй только то, что есть в тексте. Связи должны образовывать читаемый путь сверху вниз, возвраты допустимы.\n" +
            "Верни строго JSON: {\"title\":\"название схемы\",\"nodes\":[{\"id\":\"n1\",\"text\":\"...\",\"kind\":\"start|step|decision|end|note\"}]," +
            "\"edges\":[{\"from\":\"n1\",\"to\":\"n2\",\"label\":\"\"}]}\n\nТекст:\n{data}"
        ),
    "diagram_mind" to (
        "Составь карту идей (mind map) по тексту ниже. Тема: \"{phrase}\". Язык подписей: {lang}.\n" +
            "Правила: один корневой блок с главной мыслью, 3–6 ветвей, у каждой ветви до 4 подпунктов; подписи до 7 слов; " +
            "все блоки kind=topic, мелкие примеры и цифры — kind=note; не больше 40 блоков; только то, что есть в тексте. " +
            "Связи ведут от родителя к потомку.\n" +
            "Верни строго JSON: {\"title\":\"название карты\",\"nodes\":[{\"id\":\"n1\",\"text\":\"...\",\"kind\":\"topic|note\"}]," +
            "\"edges\":[{\"from\":\"n1\",\"to\":\"n2\",\"label\":\"\"}]}\n\nТекст:\n{data}"
        ),
    "diagram_video" to (
        "Разбери структуру YouTube-видео по расшифровке ниже. Тема проекта: \"{phrase}\". Язык подписей: {lang}.\n" +
            "Построй блок-схему сценария: START «Хук», затем смысловые блоки по порядку («Проблема», «Пример», «Вывод», «Призыв» и т.д.), " +
            "в каждом блоке 3–8 слов о том, что говорится; развилки (например «Зритель согласен?») только если они есть в видео; " +
            "END — концовка. От 6 до 18 блоков; ничего не выдумывай.\n" +
            "Верни строго JSON: {\"title\":\"название видео\",\"nodes\":[{\"id\":\"n1\",\"text\":\"...\",\"kind\":\"start|step|decision|end|note\"}]," +
            "\"edges\":[{\"from\":\"n1\",\"to\":\"n2\",\"label\":\"\"}]}\n\nРасшифровка:\n{data}"
        )
)

/** AI diagram generation; every call goes through Llm.complete with its own task name. */
object DiagramAi {
    fun generate(p: Project, kind: String, source: String, fallbackTitle: String): Diagram {
        val prov = AiTasks.requireProvider()
        val key = "diagram_$kind"
        val lang = if (Settings.lang == "en") "English" else "русский"
        val prompt = Prompts.fill(key, mapOf("phrase" to p.phrase, "lang" to lang, "data" to source.take(14_000), "domain" to p.domain.ifBlank { "-" }, "question" to ""))
        val r = Llm.complete(prov, prov.smartModel, Prompts.SYSTEM, prompt, json = true, maxTokens = 3500, task = key, pid = p.id)
        return Diagram.fromAi(r.text, if (kind == "mind") "mind" else "flow", fallbackTitle)
            ?: throw LlmException(t("AI вернул схему, которую не удалось прочитать. Попробуйте ещё раз.", "The AI returned a diagram that could not be read. Try again."), r.text)
    }
}

/** Editor and viewer of one diagram: pan, zoom, tap a block to edit, export as image / PDF / text. */
class DiagramActivity : LumenBase() {
    private lateinit var pid: String
    private lateinit var d: Diagram
    private lateinit var view: DiagramView
    private var pendingPdf = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id")
        val p = intent.getStringExtra("pid")
        val loaded = if (p != null && id != null) DiagramStore.load(p, id) else null
        if (p == null || loaded == null) {
            finish()
            return
        }
        pid = p
        d = loaded
        view = DiagramView(this).apply { onTap = { hit -> if (hit != null) editNode(hit) } }
        view.setDiagram(d, true)
        render()
    }

    private fun render() {
        page(
            d.title, view, scroll = false,
            actions = listOf("⤴" to { shareMenu() }, "⋮" to { moreMenu() }),
            fab = t("＋  Блок", "＋  Block") to { addBlock() }
        )
    }

    private fun persist(refit: Boolean = false) {
        DiagramStore.save(pid, d)
        view.setDiagram(d, refit)
    }

    private fun cut(s: String) = if (s.length <= 48) s else s.take(47) + "…"

    private fun editNode(id: String) {
        val n = d.node(id) ?: return
        var kind = n.kind
        var gone = false
        var field: EditText? = null
        bottomSheet(t("Блок", "Block"), onDismiss = {
            if (!gone) {
                val txt = field?.text?.toString()?.trim().orEmpty()
                if (txt.isNotEmpty()) n.text = txt.take(Diagram.MAX_TEXT)
                n.kind = kind
                persist()
            }
        }) { dismiss ->
            val e = edt(ctx, t("Текст блока", "Block text"), n.text, multiline = true)
            field = e
            add(e, 12)
            val kinds = if (d.isMind) listOf(DKind.TOPIC to t("Тема", "Topic"), DKind.NOTE to t("Заметка", "Note"))
            else listOf(
                DKind.START to t("Старт", "Start"), DKind.STEP to t("Шаг", "Step"), DKind.DECISION to t("Решение", "Decision"),
                DKind.END to t("Конец", "End"), DKind.NOTE to t("Заметка", "Note")
            )
            if (kinds.any { it.first == kind }) {
                add(text(ctx, t("Вид блока", "Block type"), Type.LABEL, bold = true, color = Palette.MUTED), 16)
                add(choiceChips(ctx, kinds, kind) { kind = it }, 8)
            }
            button(t("Добавить следующий блок", "Add next block"), style = BtnStyle.TONAL) {
                dismiss()
                addChild(id)
            }
            button(t("Соединить с другим блоком…", "Connect to another block…"), style = BtnStyle.OUTLINED) {
                dismiss()
                connectDialog(id)
            }
            button(t("Удалить блок", "Delete block"), style = BtnStyle.TEXT) {
                gone = true
                d.remove(id)
                dismiss()
                view.selected = null
                persist()
            }
        }
    }

    private fun addChild(parent: String) {
        prompt(t("Новый блок", "New block"), t("Текст блока", "Block text")) { s ->
            if (s.isBlank()) return@prompt
            val from = d.node(parent)
            val label = if (from?.kind == DKind.DECISION) {
                if (d.edges.none { it.from == parent }) t("Да", "Yes") else t("Нет", "No")
            } else ""
            d.add(s, parent = parent, label = label)
            persist()
        }
    }

    private fun addBlock() {
        val parent = view.selected?.takeIf { d.node(it) != null }
        prompt(t("Новый блок", "New block"), t("Текст блока", "Block text")) { s ->
            if (s.isBlank()) return@prompt
            d.add(s, parent = parent)
            persist()
        }
    }

    private fun connectDialog(from: String) {
        val others = d.nodes.filter { it.id != from }
        if (others.isEmpty()) return
        AlertDialog.Builder(this).setTitle(t("Соединить с…", "Connect to…"))
            .setItems(others.map { cut(it.text) }.toTypedArray()) { _, i ->
                if (d.connect(from, others[i].id)) persist() else toast(t("Такая связь уже есть", "That link already exists"))
            }.show()
    }

    private fun moreMenu() {
        val items = listOf(
            t("Переименовать схему", "Rename") to {
                prompt(t("Название схемы", "Diagram title"), "", d.title) {
                    if (it.isNotBlank()) {
                        d.title = it.trim().take(80)
                        persist()
                        render()
                    }
                }
            },
            (if (d.isMind) t("Показать как блок-схему", "Show as a flowchart") else t("Показать как карту идей", "Show as a mind map")) to {
                d.type = if (d.isMind) "flow" else "mind"
                persist(true)
            },
            t("Вписать в экран", "Fit to screen") to { view.fit() },
            t("Код Mermaid", "Mermaid code") to { showText("Mermaid", DiagramText.mermaid(d)) },
            t("Удалить схему", "Delete diagram") to {
                confirm(t("Удалить схему?", "Delete the diagram?"), d.title, t("Удалить", "Delete")) {
                    DiagramStore.delete(pid, d.id)
                    finish()
                }
            }
        )
        AlertDialog.Builder(this).setTitle(d.title).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
    }

    private fun fileName(pdf: Boolean) = ReportKit.safeName(d.title) + "-" + Fmt.isoDay(System.currentTimeMillis()) + if (pdf) ".pdf" else ".png"

    private fun footer() = "Lumen · " + (Store.project(pid)?.name ?: "")

    private fun shareMenu() {
        val items = listOf(
            t("Поделиться картинкой (PNG)", "Share image (PNG)"), t("Поделиться PDF", "Share PDF"),
            t("Сохранить картинку…", "Save image…"), t("Сохранить PDF…", "Save PDF…"),
            t("Копировать код Mermaid", "Copy Mermaid code"), t("Поделиться текстом (список)", "Share as text outline")
        )
        AlertDialog.Builder(this).setTitle(t("Экспорт схемы", "Export diagram")).setItems(items.toTypedArray()) { _, w ->
            when (w) {
                0, 1 -> {
                    val pdf = w == 1
                    toast(t("Готовлю файл…", "Preparing the file…"))
                    Lumen.async({
                        val f = ReportKit.shareFile(this, fileName(pdf))
                        f.outputStream().use { DiagramDraw.write(d, pdf, it, footer()) }
                        f
                    }, ::err) { f -> ReportKit.share(this, f, pdf, d.title) }
                }
                2, 3 -> {
                    pendingPdf = w == 3
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(if (pendingPdf) "application/pdf" else "image/png").putExtra(Intent.EXTRA_TITLE, fileName(pendingPdf))
                    @Suppress("DEPRECATION")
                    startActivityForResult(i, REQ_SAVE)
                }
                4 -> {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Mermaid", DiagramText.mermaid(d)))
                    toast(t("Скопировано", "Copied"))
                }
                else -> shareText(d.title, DiagramText.outline(d))
            }
        }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK) return
        val pdf = pendingPdf
        toast(t("Сохраняю…", "Saving…"))
        Lumen.async({ contentResolver.openOutputStream(uri)?.use { DiagramDraw.write(d, pdf, it, footer()) } }, ::err) {
            toast(t("Схема сохранена", "Diagram saved"))
        }
    }

    companion object {
        const val REQ_SAVE = 71

        fun open(ctx: Context, pid: String, id: String) {
            ctx.startActivity(Intent(ctx, DiagramActivity::class.java).putExtra("pid", pid).putExtra("id", id))
        }
    }
}

/** "Diagrams" section of a project: saved schemes plus ways to build a new one. */
fun ProjectActivity.schemesTab(c: Col) {
    val pid = project.id
    val saved = DiagramStore.list(pid)
    c.h2(t("Мои схемы", "My diagrams"))
    if (saved.isEmpty()) {
        c.empty(t("Схем пока нет", "No diagrams yet"),
            t("Соберите схему из данных проекта или попросите AI нарисовать блок-схему по тексту или видео.",
                "Build a diagram from the project data or let the AI draw a flowchart from a text or a video."))
    }
    for (e in saved) {
        val card = c.card {
            add(text(ctx, e.title.ifBlank { "—" }, Type.TITLE_M, bold = true), 0)
            muted((if (e.type == "mind") t("Карта идей", "Mind map") else t("Блок-схема", "Flowchart")) + " · " + t("блоков: ", "blocks: ") + e.nodes + " · " + Fmt.dateTime(e.at))
        }
        card.setOnClickListener { DiagramActivity.open(this, pid, e.id) }
        card.setOnLongClickListener {
            confirm(t("Удалить схему?", "Delete the diagram?"), e.title, t("Удалить", "Delete")) {
                DiagramStore.delete(pid, e.id)
                render()
            }
            true
        }
    }
    if (saved.isNotEmpty()) c.muted(t("Долгое нажатие на схему удаляет её.", "Long-press a diagram to delete it."))

    fun created(d: Diagram) {
        DiagramStore.save(pid, d)
        render()
        DiagramActivity.open(this, pid, d.id)
    }

    c.h2(t("Из данных проекта", "From project data"))
    c.muted(t("Без AI и без токенов: строится из собранных видео и каналов.", "No AI, no tokens: built from the collected videos and channels."))
    c.button(t("Воронка проекта (блок-схема)", "Project funnel (flowchart)"), style = BtnStyle.TONAL) {
        val vs = videos.values
        created(DiagramBuild.funnel(project.name.ifBlank { project.phrase }, DiagramBuild.Funnel(
            project.effectiveQueries().size, vs.size, vs.count { it.marked }, vs.count { it.collected },
            vs.count { it.hasTranscript }, vs.count { it.commentsCount > 0 }, vs.map { it.channel }.filter { it.isNotBlank() }.distinct().size,
            Settings.aiTranscriptionAllowed
        )))
    }
    c.button(t("Карта темы: каналы и видео", "Topic map: channels and videos"), style = BtnStyle.TONAL) {
        val list = videos.values.toList()
        if (list.isEmpty()) return@button toast(t("Сначала найдите видео", "Find videos first"))
        created(DiagramBuild.topicMap(project.name.ifBlank { project.phrase }, DiagramBuild.topChannels(list)))
    }
    c.button(t("Карта по меткам", "Map by labels"), style = BtnStyle.TONAL) {
        val labels = DiagramBuild.topLabels(videos.values.toList())
        if (labels.isEmpty()) return@button toast(t("У видео нет меток", "No video has labels"))
        created(DiagramBuild.labelMap(project.name.ifBlank { project.phrase }, labels))
    }

    c.h2(t("С помощью AI", "With AI"))
    c.muted(t("AI читает текст или расшифровку и рисует схему. Её можно поправить вручную.", "The AI reads a text or a transcript and draws the diagram. You can edit it by hand."))
    fun ai(kind: String, source: String, title: String) {
        if (Provider.active() == null) {
            confirm(t("AI не подключён", "AI not connected"), t("Подключить API-ключ?", "Connect an API key?")) {
                startActivity(Intent(this, SettingsActivity::class.java).putExtra("ai", true))
            }
            return
        }
        toast(t("Строю схему…", "Drawing the diagram…"))
        val p = project
        Lumen.async({ DiagramAi.generate(p, kind, source, title) }, { err(it) }) { created(it) }
    }
    c.button(t("Блок-схема процесса из текста…", "Flowchart from a text…"), primary = true) {
        prompt(t("Вставьте текст процесса", "Paste the process text"), t("Описание шагов, правил, инструкции", "Steps, rules, instructions"), multiline = true) {
            if (it.isNotBlank()) ai("flow", it, t("Блок-схема", "Flowchart"))
        }
    }
    c.button(t("Карта идей из текста…", "Mind map from a text…"), style = BtnStyle.TONAL) {
        prompt(t("Вставьте текст", "Paste the text"), t("Статья, конспект, заметки", "Article, notes"), multiline = true) {
            if (it.isNotBlank()) ai("mind", it, t("Карта идей", "Mind map"))
        }
    }
    c.button(t("Структура видео по расшифровке…", "Video structure from a transcript…"), style = BtnStyle.TONAL) {
        val withText = videos.values.filter { Store.transcript(pid, it.id)?.isNotBlank() == true }.sortedByDescending { it.views }.take(40)
        if (withText.isEmpty()) return@button toast(t("Нет видео с расшифровкой: сначала соберите её на вкладке «Сбор»", "No video has a transcript: collect it on the Collect tab first"))
        AlertDialog.Builder(this).setTitle(t("Какое видео разобрать?", "Which video?"))
            .setItems(withText.map { it.title.take(70) }.toTypedArray()) { _, i ->
                val v = withText[i]
                ai("video", Store.transcript(pid, v.id).orEmpty(), v.title)
            }.show()
    }
}
