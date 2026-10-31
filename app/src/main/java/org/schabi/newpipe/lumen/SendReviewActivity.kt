/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.os.Bundle
import android.widget.TextView

/**
 * "What we send to AI": every piece of collected data in the outgoing request, with switches.
 * The worker thread waits in [SendGate.review] until a button here answers.
 */
class SendReviewActivity : LumenBase() {
    private var pending: SendGate.Pending? = null
    private val sel = Outgoing.Selection()
    private var withFile = true
    private var edited: String? = null
    private val openBlocks = mutableSetOf<Int>()
    private var showSystem = false
    private var summary: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attach()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (pending !== SendGate.current) attach()
    }

    private fun attach() {
        val p = SendGate.current
        if (p == null) {
            finish()
            return
        }
        pending = p
        sel.offBlocks.clear()
        sel.offLines.clear()
        SendGate.previous(p.req)?.let {
            sel.offBlocks.addAll(it.offBlocks)
            sel.offLines.addAll(it.offLines)
        }
        withFile = true
        edited = null
        openBlocks.clear()
        render()
    }

    private fun outgoing(req: SendGate.Request) = edited ?: Outgoing.assemble(req.blocks, sel)

    private fun answer(send: Boolean) {
        val p = pending ?: return finish()
        val req = p.req
        if (send) SendGate.remember(req, sel)
        p.answer(if (send) SendGate.Result(outgoing(req), if (withFile) req.fileUri else null) else null)
        pending = null
        // the next call in line may already be waiting
        window.decorView.postDelayed({
            val next = SendGate.current
            if (next != null && !isFinishing) attach() else finish()
        }, 250)
    }

    override fun navigateUp() = answer(false)

    override fun onBackPressed() = answer(false)

    override fun onDestroy() {
        // leaving the screen without a choice means "do not send"
        if (!isChangingConfigurations) pending?.answer(null)
        super.onDestroy()
    }

    private fun refreshSummary(req: SendGate.Request) {
        val s = Outgoing.stats(req.blocks, sel)
        val out = outgoing(req)
        val tok = Llm.estimateTokens(req.system + out)
        val cost = Llm.estimateCost(tok, req.maxTokens)
        summary?.text = t("Уйдёт: ", "Will send: ") + Fmt.num(out.length.toLong()) + t(" из ", " of ") + Fmt.num(s.charsTotal.toLong()) +
            t(" симв. · ≈", " chars · ≈") + Fmt.num(tok.toLong()) + t(" ток.", " tok.") +
            (if (cost > 0) String.format(java.util.Locale.US, " · ≤ $%.4f", cost) else "") +
            (if (edited == null) t(" · строк ", " · lines ") + s.lines + "/" + s.linesTotal else t(" · текст изменён", " · text edited")) +
            (if (req.fileUri != null && withFile) t(" · + видео", " · + video") else "")
    }

    private fun render() {
        val p = pending ?: return
        val req = p.req
        val c = Col(this)
        c.card {
            add(text(ctx, req.task, Type.TITLE_M, bold = true), 0)
            val project = if (req.pid.isNotBlank()) Store.project(req.pid)?.name else null
            muted(listOfNotNull(project, req.provider.title + " · " + req.model, hostOf(req.provider.baseUrl)).joinToString(" · "))
            muted(t("Ниже всё, что уйдёт провайдеру. Снимите галочки с лишнего — эти данные не отправятся. Ключ API в текст не входит.",
                "Below is everything the provider receives. Untick what should not go out. The API key is not part of the text."))
            if (!sel.isEmpty) muted(t("Применён ваш прошлый выбор для этой задачи.", "Your previous choice for this task is applied."))
            if (SendGate.queued > 0) muted(t("Ещё ждут в очереди: ", "Waiting in line: ") + SendGate.queued)
        }

        c.section(t("Системная инструкция", "System instruction"), req.system.take(80), showSystem, {
            showSystem = it
            render()
        }) {
            add(text(ctx, req.system, 13f).apply { setTextIsSelectable(true) }, 4)
        }

        req.fileUri?.let { uri ->
            c.card {
                add(chk(ctx, t("Видео по ссылке (модель посмотрит его): ", "Video by link (the model will watch it): ") + uri, withFile) {
                    withFile = it
                    refreshSummary(req)
                }, 0)
            }
        }

        val e = edited
        if (e != null) {
            c.h2(t("Изменённый текст", "Edited text"))
            c.card {
                add(text(ctx, e.take(4000) + if (e.length > 4000) "\n…" else "", 13f).apply { setTextIsSelectable(true) }, 0)
            }
            c.button(t("Вернуть разбивку по блокам", "Back to blocks"), style = BtnStyle.TEXT) {
                edited = null
                render()
            }
        } else {
            c.h2(t("Данные запроса: ", "Request data: ") + req.blocks.size + t(" блок(ов)", " block(s)"))
            c.row(
                btn(this, t("Все", "All"), style = BtnStyle.TONAL) {
                    sel.offBlocks.clear()
                    sel.offLines.clear()
                    render()
                },
                btn(this, t("Только инструкция", "Instruction only"), style = BtnStyle.TONAL) {
                    sel.offLines.clear()
                    sel.offBlocks.clear()
                    sel.offBlocks.addAll(1 until req.blocks.size)
                    render()
                }
            )
            req.blocks.forEachIndexed { b, block -> blockCard(c, req, b, block) }
        }

        val foot = Col(this, 12)
        foot.root.setBackgroundColor(Palette.SURFACE)
        summary = foot.add(text(this, "", 13f, color = Palette.MUTED), 0) as TextView
        foot.row(
            btn(this, t("Не отправлять", "Don't send"), style = BtnStyle.OUTLINED) { answer(false) },
            btn(this, t("Отправить", "Send"), primary = true) { answer(true) }
        )
        refreshSummary(req)

        val actions = listOf(
            "✎" to {
                prompt(t("Текст запроса", "Request text"), "", outgoing(req), multiline = true) {
                    edited = it
                    render()
                }
            },
            "⤴" to { shareText("Lumen → AI: " + req.task, artifact(req)) },
            "⋮" to { rules(req) }
        )
        page(t("Что отправляем в AI", "What goes to AI"), c.root, actions = actions, footer = foot.root, keepScroll = true)
    }

    private fun blockCard(c: Col, req: SendGate.Request, b: Int, block: Outgoing.Block) {
        val on = sel.blockOn(b)
        val kept = block.lines.indices.count { sel.lineOn(b, it) }
        c.card {
            val label = (if (b == 0) t("Инструкция", "Instruction") else t("Блок ", "Block ") + (b + 1)) +
                " · " + (if (block.lines.size > 1) t("строк ", "lines ") + kept + "/" + block.lines.size + " · " else "") +
                Fmt.num(block.text.length.toLong()) + t(" симв.", " chars")
            add(chk(ctx, label, on) {
                if (it) sel.offBlocks.remove(b) else sel.offBlocks.add(b)
                render()
            }, 0)
            val open = b in openBlocks
            if (!open || block.lines.size == 1) {
                val s = block.text
                val lim = if (open) 6000 else 400
                add(text(ctx, s.take(lim) + if (s.length > lim) "…" else "", 13f, color = if (on) Palette.ON_SURFACE else Palette.MUTED).apply {
                    setTextIsSelectable(open)
                }, 4)
            } else {
                block.lines.take(MAX_LINES).forEachIndexed { l, line ->
                    add(chk(ctx, line.take(220) + if (line.length > 220) "…" else "", sel.lineOn(b, l)) {
                        if (it) {
                            sel.offLines.remove("$b.$l")
                            sel.offBlocks.remove(b)
                        } else {
                            sel.offLines.add("$b.$l")
                        }
                        render()
                    }.apply { textSize = 13f }, 0)
                }
                if (block.lines.size > MAX_LINES) muted(t("Показаны первые $MAX_LINES строк; остальные отправятся, если блок включён.",
                    "First $MAX_LINES lines shown; the rest go out while the block is on."))
                row(
                    btn(ctx, t("Все строки", "All lines"), style = BtnStyle.TEXT) {
                        sel.offLines.removeAll { it.startsWith("$b.") }
                        sel.offBlocks.remove(b)
                        render()
                    },
                    btn(ctx, t("Снять все", "None"), style = BtnStyle.TEXT) {
                        block.lines.indices.forEach { sel.offLines.add("$b.$it") }
                        render()
                    }
                )
            }
            if (block.lines.size > 1 || block.text.length > 400) {
                add(btn(ctx, if (open) t("Свернуть", "Collapse") else if (block.lines.size > 1) t("Выбрать строки", "Pick lines") else t("Показать целиком", "Show all"),
                    style = BtnStyle.TEXT) {
                    if (open) openBlocks.remove(b) else openBlocks.add(b)
                    render()
                }, 4)
            }
        }
    }

    /** The full request as text: metadata, system instruction, user text, attached video. */
    private fun artifact(req: SendGate.Request): String = buildString {
        append("Lumen → AI\n")
        append("task: ").append(req.task).append('\n')
        append("provider: ").append(req.provider.title).append(" · ").append(req.model).append(" · ").append(hostOf(req.provider.baseUrl)).append('\n')
        if (req.fileUri != null && withFile) append("video: ").append(req.fileUri).append('\n')
        append(t("\n=== Системная инструкция ===\n", "\n=== System instruction ===\n")).append(req.system).append('\n')
        append(t("\n=== Текст запроса ===\n", "\n=== Request text ===\n")).append(outgoing(req)).append('\n')
    }

    private fun rules(req: SendGate.Request) = bottomSheet(t("Правило для «", "Rule for \"") + req.task + t("»", "\"")) { dismiss ->
        muted(t("Можно поменять в Настройки → AI → Что отправляется.", "Can be changed in Settings → AI → What is sent."))
        button(t("Отправить и больше не спрашивать для этой задачи", "Send and stop asking for this task"), style = BtnStyle.TONAL) {
            SendGate.setStored(req.task, Outgoing.Rule.SEND)
            dismiss()
            answer(true)
        }
        button(t("Отправить и не спрашивать до перезапуска", "Send and don't ask until restart"), style = BtnStyle.TONAL) {
            SendGate.setSession(req.task, Outgoing.Rule.SEND)
            dismiss()
            answer(true)
        }
        button(t("Не отправлять эту задачу до перезапуска", "Don't send this task until restart"), style = BtnStyle.OUTLINED) {
            SendGate.setSession(req.task, Outgoing.Rule.BLOCK)
            dismiss()
            answer(false)
        }
        button(t("Запретить эту задачу навсегда", "Block this task for good"), style = BtnStyle.OUTLINED) {
            SendGate.setStored(req.task, Outgoing.Rule.BLOCK)
            dismiss()
            answer(false)
        }
    }

    companion object {
        private const val MAX_LINES = 300

        fun hostOf(url: String): String = try {
            java.net.URL(url).host
        } catch (e: Exception) {
            url
        }
    }
}
