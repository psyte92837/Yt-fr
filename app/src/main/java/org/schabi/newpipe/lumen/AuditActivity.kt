/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/**
 * "Algorithm audit" screen of a project: settings of the simulated viewer, runs, and the report of a run
 * (findings, drift by step, channels, attractors, exposure, walks, graph, AI verdict, comparison of two runs).
 */
class AuditActivity : LumenBase() {
    companion object {
        fun open(ctx: Context, pid: String) = ctx.startActivity(Intent(ctx, AuditActivity::class.java).putExtra("pid", pid))
    }

    private lateinit var pid: String
    private lateinit var project: Project
    private lateinit var cfg: AuditConfig
    private var runId = "" // report being shown; empty = start screen
    private var progressView: TextView? = null
    private var wasRunning = false
    private var showWalks = false
    private val handler = Handler(Looper.getMainLooper())

    private val running get() = CollectState.running && CollectState.projectId == pid && CollectState.kind == "audit"

    private val ticker = object : Runnable {
        override fun run() {
            if (running) {
                if (progressView == null) render() else progressView?.text = CollectState.summary()
                wasRunning = true
            } else if (wasRunning) {
                wasRunning = false
                if (CollectState.lastError.isNotEmpty()) err(IllegalStateException(CollectState.lastError)) else toast(CollectState.lastResult)
                runId = AuditStore.runs(pid).firstOrNull()?.id ?: ""
                render()
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra("pid") ?: return finish()
        project = Store.project(pid) ?: return finish()
        cfg = AuditConfig.load(pid)
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (runId.isNotEmpty()) {
            runId = ""
            render()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun render() {
        progressView = null
        val run = runId.takeIf { it.isNotEmpty() }?.let { AuditStore.get(pid, it) }
        if (run != null) report(run) else home()
    }

    // ---------------------------------------------------------------- start screen

    private fun home() {
        val c = Col(this)
        val save = { AuditConfig.save(pid, cfg) }
        if (running) {
            c.card {
                val pv = text(ctx, CollectState.summary(), 14f, bold = true)
                progressView = pv
                add(pv, 0)
                button(t("Остановить (результат сохранится)", "Stop (the result is kept)"), style = BtnStyle.OUTLINED) { CollectService.stop(this@AuditActivity) }
            }
        }
        c.card {
            add(text(ctx, t("Как YouTube уводит зрителя", "Where YouTube leads a viewer"), Type.TITLE_M, bold = true), 0)
            muted(t("Аудит методом «sock-puppet», как в исследованиях рекомендаций (Ribeiro 2020, Hussein 2020, Haroon 2023): " +
                "виртуальный зритель без аккаунта стартует с видео темы и много раз идёт по «Следующему» с заданным поведением. " +
                "Lumen измеряет, как быстро уходит тема, к каким каналам и видео сходится алгоритм, тянет ли он к популярному, свежему, Shorts.",
                "A sock-puppet audit, as in recommendation studies (Ribeiro 2020, Hussein 2020, Haroon 2023): a viewer without an account " +
                    "starts from topic videos and follows “Up next” many times with a fixed behaviour. Lumen measures how fast the topic fades, " +
                    "which channels and videos the algorithm converges to, and whether it pulls to popular, fresh or Shorts."))
        }

        c.h2(t("Поведение зрителя", "Viewer behaviour"))
        c.spinner(t("Стратегия", "Strategy"), AuditConfig.STRATEGIES.map { it to AuditConfig.strategyName(it) }, cfg.strategy) {
            if (it != cfg.strategy) {
                cfg.strategy = it
                save()
                render()
            }
        }
        c.muted(when (cfg.strategy) {
            "first" -> t("Детерминирована: без аккаунта одна и та же цепочка, поэтому одна прогулка на видео.",
                "Deterministic without an account: one walk per seed.")
            "random" -> t("Базовая линия исследований: случайный выбор из топа показывает «типичный» путь.", "Research baseline: a random pick from the top shows a typical path.")
            "away", "stay" -> t("Похожесть считается по словам темы проекта и названиям стартовых видео.", "Similarity uses the project topic words and seed titles.")
            else -> t("Показывает, куда ведёт выбор по одному признаку.", "Shows where picking by one signal leads.")
        })
        c.spinner(t("Смотрит первые N рекомендаций", "Looks at the first N recommendations"), listOf(1, 3, 5, 8, 10, 20).map { it to "top-$it" }, cfg.topK) {
            cfg.topK = it
            save()
        }
        c.check(t("Пропускать Shorts", "Skip Shorts"), cfg.excludeShorts) {
            cfg.excludeShorts = it
            save()
        }

        c.h2(t("Выборка", "Sample"))
        c.spinner(t("Стартовые видео", "Seed videos"), listOf(
            "marked" to t("Отмеченные (иначе топ по просмотрам)", "Marked (else top by views)"),
            "top" to t("Топ проекта по просмотрам", "Project top by views"),
            "custom" to t("Свои ссылки", "Custom links")
        ), cfg.seeds) {
            if (it != cfg.seeds) {
                cfg.seeds = it
                save()
                render()
            }
        }
        if (cfg.seeds == "custom") {
            c.edit(t("Ссылки на видео, по одной в строке", "Video links, one per line"), cfg.customSeeds, multiline = true)
                .addTextChangedListener(SimpleWatcher {
                    cfg.customSeeds = it
                    save()
                })
        } else {
            c.spinner(t("Сколько стартовых видео", "How many seeds"), listOf(1, 2, 3, 5, 10, 20).map { it to it.toString() }, cfg.seedCount) {
                cfg.seedCount = it
                save()
            }
        }
        if (cfg.strategy != "first") {
            c.spinner(t("Прогулок с каждого видео", "Walks per seed"), listOf(1, 2, 3, 5, 10, 20).map { it to it.toString() }, cfg.walks) {
                cfg.walks = it
                save()
            }
        }
        c.spinner(t("Шагов в прогулке", "Steps per walk"), listOf(3, 5, 8, 10, 15, 20, 30).map { it to it.toString() }, cfg.steps) {
            cfg.steps = it
            save()
        }
        c.spinner(t("Бюджет запросов к YouTube", "YouTube request budget"), listOf(50, 100, 200, 300, 500, 1000).map { it to it.toString() }, cfg.budget) {
            cfg.budget = it
            save()
        }

        if (!running) {
            c.button(t("▶ Запустить аудит", "▶ Run audit"), primary = true) { start() }
            c.muted(t("Совет: запустите аудит с двумя стратегиями и сравните их в отчёте (A/B).", "Tip: run two strategies and compare them in the report (A/B)."))
        }

        val runs = AuditStore.runs(pid)
        c.h2(t("Прогоны", "Runs") + if (runs.isEmpty()) "" else " (${runs.size})")
        if (runs.isEmpty()) c.muted(t("Пока нет. Результаты появятся здесь.", "None yet. Results will appear here."))
        runs.forEach { r ->
            val rep = AuditMath.report(r)
            val card = c.card {
                add(text(ctx, Fmt.dateTime(r.at) + " · " + AuditConfig.strategyName(r.cfg.strategy), 15f, bold = true), 0)
                muted(t("прогулок ${rep.walks} · шагов до ${rep.steps} · каналов ${rep.channelsDistinct} · запросов ${r.requests}",
                    "${rep.walks} walks · up to ${rep.steps} steps · ${rep.channelsDistinct} channels · ${r.requests} requests") +
                    if (r.cancelled) t(" · остановлен", " · stopped") else "")
                rep.findings.firstOrNull()?.let { p(it, 14f) }
            }
            card.setOnClickListener {
                runId = r.id
                render()
            }
        }
        page(t("Аудит алгоритма", "Algorithm audit"), c.root)
    }

    private fun start() {
        if (CollectState.running) return toast(t("Уже идёт другая задача", "Another task is running"))
        project = Store.project(pid) ?: return
        val seeds = AuditEngine.seeds(project, cfg)
        if (seeds.isEmpty()) return toast(t("Нет стартовых видео: найдите или отметьте видео, либо укажите ссылки.", "No seed videos: search or mark videos, or add links."))
        val req = cfg.estimate(seeds.size)
        val minutes = maxOf(1, req * (Settings.requestDelayMs + 1500) / 60000)
        confirm(
            t("Запустить аудит?", "Run the audit?"),
            t("Стартовых видео: ${seeds.size}, прогулок: ${seeds.size * cfg.effectiveWalks()} по ${cfg.steps} шагов.\nДо $req запросов к YouTube, ≈ $minutes мин. Работает в фоне.",
                "Seeds: ${seeds.size}, walks: ${seeds.size * cfg.effectiveWalks()} × ${cfg.steps} steps.\nUp to $req YouTube requests, ≈ $minutes min. Runs in the background."),
            t("Запустить", "Run")
        ) {
            AuditConfig.save(pid, cfg)
            CollectService.startKind(this, pid, "audit")
            wasRunning = true
            handler.postDelayed({ render() }, 300)
        }
    }

    // ---------------------------------------------------------------- report

    private fun report(run: AuditRun) {
        val r = AuditMath.report(run)
        val c = Col(this)
        c.card {
            add(text(ctx, AuditConfig.strategyName(run.cfg.strategy), Type.TITLE_M, bold = true), 0)
            muted(Fmt.dateTime(run.at) + " · top-${run.cfg.topK} · " + t("прогулок ${r.walks} · запросов ${run.requests}", "${r.walks} walks · ${run.requests} requests") +
                if (run.cancelled) t(" · остановлен", " · stopped") else "")
            if (r.findings.isEmpty()) p(t("Данных мало для выводов.", "Not enough data for conclusions."))
            r.findings.forEach { p("• $it", 14f) }
        }
        c.row(
            btn(this, t("Граф", "Graph"), primary = true) {
                RecGraph.save(pid, AuditMath.graph(run), AuditEngine.GRAPH_FILE)
                startActivity(Intent(this, GraphActivity::class.java).putExtra("pid", pid).putExtra("file", AuditEngine.GRAPH_FILE))
            },
            btn(this, t("AI-вывод", "AI verdict"), style = BtnStyle.TONAL) { ai(run) },
            btn(this, t("Сравнить", "Compare"), style = BtnStyle.OUTLINED) { compare(run, r) }
        )
        c.row(
            btn(this, t("Поделиться", "Share"), style = BtnStyle.TEXT) {
                shareText(t("Аудит алгоритма", "Algorithm audit"), AuditMath.markdown(run, r, project.name))
            },
            btn(this, t("Удалить", "Delete"), style = BtnStyle.TEXT) {
                confirm(t("Удалить прогон?", "Delete the run?"), Fmt.dateTime(run.at)) {
                    AuditStore.delete(pid, run.id)
                    runId = ""
                    render()
                }
            }
        )

        c.h2(t("Ключевые метрики", "Key metrics"))
        c.card {
            fun m(label: String, value: String, hint: String) {
                add(text(ctx, "$label: $value", 15f, bold = true), 6)
                muted(hint)
            }
            m(t("Полураспад темы", "Topic half-life"), r.halfLife?.let { t("шаг $it", "step $it") } ?: t("не достигнут", "not reached"),
                t("Шаг, на котором схожесть с темой падает вдвое.", "Step where topic similarity halves."))
            m(t("Рост популярности", "Popularity lift"), "×" + AuditMath.fmt1(r.popularityLift),
                t("Медиана просмотров в конце / на первом шаге.", "Median views at the end / at the first step."))
            m(t("Концентрация каналов (HHI)", "Channel concentration (HHI)"), AuditMath.fmt2(r.hhi),
                t("0 — много разных каналов, 1 — один канал. Выше 0,15 — высокая.", "0 = many channels, 1 = a single one. Above 0.15 is high."))
            m(t("Разнообразие (энтропия)", "Diversity (entropy)"), AuditMath.fmt2(r.entropy) + " · " + t("каналов ${r.channelsDistinct}", "${r.channelsDistinct} channels"),
                t("1 — каналы встречаются равномерно.", "1 = channels appear evenly."))
            m(t("Сходимость прогулок", "Walk convergence"), AuditMath.fmt2(r.convergence),
                t("Насколько разные прогулки приходят к одним каналам.", "How much different walks end up on the same channels."))
            m(t("Средняя позиция выбора", "Mean chosen position"), "#" + AuditMath.fmt1(r.rankMean + 1), t("Позиция в «Следующем».", "Position in “Up next”."))
        }

        if (r.perStep.size > 1) {
            c.h2(t("Дрейф темы по шагам", "Topic drift by step"))
            c.muted(t("Доля видео по теме, %. Шаг 0 — стартовые видео.", "Share of on-topic videos, %. Step 0 = seeds."))
            c.add(BarChartView(this).apply {
                values = r.perStep.map { it.onTopic * 100 }
                labels = r.perStep.map { it.step.toString() }
            })
            c.h2(t("Медиана просмотров по шагам", "Median views by step"))
            c.add(BarChartView(this).apply {
                values = r.perStep.map { maxOf(0L, it.medianViews).toDouble() }
                labels = r.perStep.map { it.step.toString() }
            })
            c.muted(r.perStep.joinToString("\n") { s ->
                t("Шаг ${s.step}: N=${s.n} · схожесть ${AuditMath.fmt2(s.similarity)} · возраст ${if (s.medianAgeDays < 0) "—" else s.medianAgeDays} дн · Shorts ${AuditMath.pct(s.shorts)} · новые каналы ${AuditMath.pct(s.newChannels)}",
                    "Step ${s.step}: N=${s.n} · similarity ${AuditMath.fmt2(s.similarity)} · age ${if (s.medianAgeDays < 0) "—" else s.medianAgeDays} d · Shorts ${AuditMath.pct(s.shorts)} · new channels ${AuditMath.pct(s.newChannels)}")
            })
        }

        if (r.topChannels.isNotEmpty()) {
            c.h2(t("Куда ведёт алгоритм: каналы", "Where it leads: channels"))
            c.add(BarChartView(this).apply { horizontal = r.topChannels.take(10).map { it.title.take(28) to it.share * 100 } })
        }
        videos(c, t("Аттракторы", "Attractors"), t("Видео, к которым приходят разные прогулки.", "Videos different walks arrive at."), r.attractors, run, t("прогулок", "walks"))
        videos(c, t("Чаще всего в топе", "Most exposed"), t("Сколько раз видео было среди рекомендаций, которые видел зритель.", "How often a video was among the recommendations the viewer saw."), r.exposure, run, t("показов", "shown"))

        c.section(t("Прогулки", "Walks"), "${run.walks.size}", showWalks, { showWalks = it; render() }) {
            run.walks.forEachIndexed { i, w ->
                add(text(ctx, "${i + 1}. " + w.seed.title.ifBlank { w.seed.id }, 14f, bold = true), 12)
                muted(w.steps.mapIndexed { j, s -> "${j + 1}. [#${s.rank + 1}] ${s.item.title} — ${s.item.channel}" }.joinToString("\n") +
                    if (w.end == "done") "" else "\n— " + endName(w.end))
            }
        }
        page(t("Отчёт аудита", "Audit report"), c.root)
    }

    private fun videos(c: Col, title: String, hint: String, list: List<AuditCount>, run: AuditRun, unit: String) {
        if (list.isEmpty()) return
        c.h2(title)
        c.muted(hint)
        val thumbs = HashMap<String, String>()
        run.walks.forEach { w -> w.steps.forEach { s -> s.shown.forEach { thumbs[it.id] = it.thumb } } }
        list.take(10).forEach { a ->
            c.add(videoListRow(this, thumbs[a.key] ?: "", a.title, a.channel, "${a.count} $unit · ${AuditMath.pct(a.share)}") {
                openVideo("https://www.youtube.com/watch?v=${a.key}")
            })
        }
    }

    private fun endName(e: String) = when (e) {
        "loop" -> t("петля: все рекомендации уже были", "loop: all recommendations seen")
        "empty" -> t("нет рекомендаций", "no recommendations")
        "error" -> t("ошибка загрузки", "loading error")
        "stopped" -> t("остановлено", "stopped")
        else -> e
    }

    private fun ai(run: AuditRun) {
        if (Provider.active() == null) return toast(t("Настройте AI-провайдера в настройках", "Configure an AI provider in Settings"))
        toast(t("AI анализирует…", "AI is analysing…"))
        Lumen.async({ AuditEngine.aiVerdict(project, run) }, { err(it) }) { showText(t("AI-вывод аудита", "Audit AI verdict"), it) }
    }

    private fun compare(run: AuditRun, r: AuditReport) {
        val others = AuditStore.runs(pid).filter { it.id != run.id }
        if (others.isEmpty()) return toast(t("Нужен ещё один прогон", "Another run is needed"))
        val names = others.map { Fmt.dateTime(it.at) + " · " + AuditConfig.strategyName(it.cfg.strategy) }.toTypedArray()
        AlertDialog.Builder(this).setTitle(t("Сравнить с", "Compare with")).setItems(names) { _, i ->
            val o = others[i]
            showText(t("Сравнение прогонов", "Run comparison"), AuditMath.compare(r, AuditMath.report(o), "A: " + run.cfg.strategy, "B: " + o.cfg.strategy) +
                "\n\nA: " + Fmt.dateTime(run.at) + "\nB: " + Fmt.dateTime(o.at))
        }.show()
    }
}
