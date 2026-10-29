/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln
import kotlin.math.log10

/*
 * "Algorithm audit": a black-box (sock-puppet) audit of YouTube's "Up next" recommendations, the method used in
 * academic studies (Ribeiro et al. 2020, Hussein et al. 2020, Haroon et al. 2023). A simulated viewer starts from a seed
 * video and follows recommendations step by step with a fixed behaviour (strategy); many such walks are recorded and
 * measured: how fast the topic drifts away, which channels and videos the algorithm converges to, whether it pushes
 * towards popular, fresh or short content. Everything runs without an account, so this is the non-personalised layer.
 */

/** Audit settings of a project, stored in audit_cfg.json. */
data class AuditConfig(
    var seeds: String = "marked", // marked | top | custom
    var seedCount: Int = 3,
    var customSeeds: String = "",
    var walks: Int = 3, // walks per seed
    var steps: Int = 10,
    var strategy: String = "random",
    var topK: Int = 5, // how many top recommendations the simulated viewer looks at
    var excludeShorts: Boolean = false,
    var budget: Int = 300
) {
    fun toJson(): JSONObject = JSONObject().put("seeds", seeds).put("seedCount", seedCount).put("customSeeds", customSeeds)
        .put("walks", walks).put("steps", steps).put("strategy", strategy).put("topK", topK)
        .put("excludeShorts", excludeShorts).put("budget", budget)

    /** Upper bound of YouTube requests (one watch page per step; repeated pages are cached). */
    fun estimate(seedCount: Int): Int = minOf(budget, seedCount * effectiveWalks() * steps)

    /** "first" is deterministic for an anonymous viewer: repeating it gives the same walk. */
    fun effectiveWalks(): Int = if (strategy == "first") 1 else walks

    companion object {
        val STRATEGIES = listOf("first", "random", "popular", "fresh", "away", "stay")

        fun from(o: JSONObject) = AuditConfig(
            seeds = o.optString("seeds", "marked"), seedCount = o.optInt("seedCount", 3), customSeeds = o.optString("customSeeds"),
            walks = o.optInt("walks", 3), steps = o.optInt("steps", 10), strategy = o.optString("strategy", "random"),
            topK = o.optInt("topK", 5), excludeShorts = o.optBoolean("excludeShorts"), budget = o.optInt("budget", 300)
        )

        fun load(pid: String): AuditConfig = try {
            Store.readFile(pid, "audit_cfg.json")?.let { from(JSONObject(it)) } ?: AuditConfig()
        } catch (e: Exception) {
            AuditConfig()
        }

        fun save(pid: String, c: AuditConfig) = Store.writeFile(pid, "audit_cfg.json", c.toJson().toString())

        fun strategyName(s: String) = when (s) {
            "first" -> t("Автовоспроизведение (всегда №1)", "Autoplay (always #1)")
            "random" -> t("Любопытный зритель (случайное из топа)", "Curious viewer (random from the top)")
            "popular" -> t("Самое популярное из топа", "Most popular from the top")
            "fresh" -> t("Самое свежее из топа", "Newest from the top")
            "away" -> t("Уход от темы (наименее похожее)", "Drifting away (least similar)")
            "stay" -> t("Верный теме (самое похожее)", "Loyal to the topic (most similar)")
            else -> s
        }
    }
}

data class AuditItem(
    val id: String,
    var title: String = "",
    var channel: String = "",
    var channelUrl: String = "",
    var views: Long = -1,
    var uploadMs: Long = -1,
    var isShort: Boolean = false,
    var thumb: String = ""
) {
    val url get() = "https://www.youtube.com/watch?v=$id"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("t", title).put("c", channel).put("cu", channelUrl)
        .put("v", views).put("u", uploadMs).put("s", isShort).put("th", thumb)

    companion object {
        fun from(o: JSONObject) = AuditItem(
            o.getString("id"), o.optString("t"), o.optString("c"), o.optString("cu"), o.optLong("v", -1),
            o.optLong("u", -1), o.optBoolean("s"), o.optString("th")
        )
    }
}

/** One step of a walk: the chosen video, its position in "Up next" and the top-K list the viewer saw. */
data class AuditStep(val item: AuditItem, val rank: Int, val shown: List<AuditItem>)

data class AuditWalk(val seed: AuditItem, val steps: MutableList<AuditStep> = mutableListOf(), var end: String = "done") {
    /** Seed (step 0) followed by the chosen videos. */
    val path: List<AuditItem> get() = listOf(seed) + steps.map { it.item }
}

data class AuditRun(
    val id: String = newId(),
    val at: Long = System.currentTimeMillis(),
    val cfg: AuditConfig = AuditConfig(),
    val topic: Set<String> = emptySet(),
    val walks: MutableList<AuditWalk> = mutableListOf(),
    var requests: Int = 0,
    var cancelled: Boolean = false
) {
    fun toJson(): JSONObject {
        val wa = JSONArray()
        walks.forEach { w ->
            val sa = JSONArray()
            w.steps.forEach { s ->
                sa.put(JSONObject().put("i", s.item.toJson()).put("r", s.rank).put("sh", JSONArray().also { a -> s.shown.forEach { a.put(it.toJson()) } }))
            }
            wa.put(JSONObject().put("seed", w.seed.toJson()).put("steps", sa).put("end", w.end))
        }
        return JSONObject().put("id", id).put("at", at).put("cfg", cfg.toJson()).put("topic", JSONArray(topic.toList()))
            .put("walks", wa).put("requests", requests).put("cancelled", cancelled)
    }

    companion object {
        fun from(o: JSONObject): AuditRun {
            val ta = o.optJSONArray("topic") ?: JSONArray()
            val run = AuditRun(
                id = o.optString("id", newId()), at = o.optLong("at"), cfg = AuditConfig.from(o.optJSONObject("cfg") ?: JSONObject()),
                topic = (0 until ta.length()).map { ta.getString(it) }.toSet(), requests = o.optInt("requests"),
                cancelled = o.optBoolean("cancelled")
            )
            val wa = o.optJSONArray("walks") ?: JSONArray()
            for (i in 0 until wa.length()) {
                val wo = wa.getJSONObject(i)
                val w = AuditWalk(AuditItem.from(wo.getJSONObject("seed")), end = wo.optString("end", "done"))
                val sa = wo.optJSONArray("steps") ?: JSONArray()
                for (j in 0 until sa.length()) {
                    val so = sa.getJSONObject(j)
                    val sh = so.optJSONArray("sh") ?: JSONArray()
                    w.steps.add(AuditStep(AuditItem.from(so.getJSONObject("i")), so.optInt("r"), (0 until sh.length()).map { AuditItem.from(sh.getJSONObject(it)) }))
                }
                run.walks.add(w)
            }
            return run
        }
    }
}

/** Saved audit runs of a project (audit.json), newest first. */
object AuditStore {
    private const val FILE = "audit.json"
    private const val KEEP = 30

    fun runs(pid: String): List<AuditRun> = parse(Store.readFile(pid, FILE))

    fun parse(text: String?): List<AuditRun> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONObject(text).optJSONArray("runs") ?: JSONArray()
            (0 until a.length()).mapNotNull { i ->
                try {
                    AuditRun.from(a.getJSONObject(i))
                } catch (e: Exception) {
                    null // a broken run never hides the others
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun get(pid: String, id: String) = runs(pid).firstOrNull { it.id == id }

    fun add(pid: String, run: AuditRun) = write(pid, (listOf(run) + runs(pid).filter { it.id != run.id }).take(KEEP))

    fun delete(pid: String, id: String) = write(pid, runs(pid).filter { it.id != id })

    private fun write(pid: String, list: List<AuditRun>) =
        Store.writeFile(pid, FILE, JSONObject().put("runs", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }).toString())
}

/** Measured statistics of all walks at one step (0 = seeds). */
data class AuditStepStat(
    val step: Int,
    val n: Int,
    val similarity: Double, // mean share of title words on topic, 0..1
    val onTopic: Double, // share of videos with at least one topic word
    val medianViews: Long,
    val medianAgeDays: Long,
    val shorts: Double,
    val newChannels: Double // share of videos whose channel was not seen earlier in the same walk
)

data class AuditCount(val key: String, val title: String, val channel: String, val count: Int, val share: Double)

data class AuditReport(
    val walks: Int,
    val steps: Int,
    val perStep: List<AuditStepStat>,
    val halfLife: Int?, // first step where topic similarity falls below half of the seeds' level
    val popularityLift: Double, // median views at the last step / at the first step
    val channelsDistinct: Int,
    val hhi: Double, // Herfindahl index of channel shares, 0..1 (higher = more concentrated)
    val entropy: Double, // normalised Shannon entropy of channels, 0..1 (higher = more diverse)
    val convergence: Double, // mean pairwise Jaccard of the channels of different walks
    val topChannels: List<AuditCount>,
    val attractors: List<AuditCount>, // videos reached by several walks
    val exposure: List<AuditCount>, // videos shown most often in the top-K
    val rankMean: Double,
    val deadEnds: Int,
    val findings: List<String>
)

/** Pure audit math: viewer strategies, metrics and the report (covered by AuditTest). */
object AuditMath {
    /** Topic profile: stems of the given texts. */
    fun topicOf(texts: List<String>): Set<String> = texts.flatMap { Relevance.keywords(it) }.toSet()

    /** Share (0..1) of the title's words that belong to the topic. */
    fun similarity(topic: Set<String>, text: String): Double {
        val toks = Words.tokens(text, 3)
        if (toks.isEmpty() || topic.isEmpty()) return 0.0
        return toks.count { tok -> topic.any { tok.startsWith(it) } }.toDouble() / toks.size
    }

    /** Index in [cands] that a viewer with [strategy] picks; [cands] must not be empty. */
    fun choose(strategy: String, cands: List<AuditItem>, topic: Set<String>, rnd: Random): Int = when (strategy) {
        "random" -> rnd.nextInt(cands.size)
        "popular" -> cands.indices.maxByOrNull { cands[it].views } ?: 0
        "fresh" -> cands.indices.maxByOrNull { cands[it].uploadMs } ?: 0
        "away" -> cands.indices.minByOrNull { similarity(topic, cands[it].title) } ?: 0
        "stay" -> cands.indices.maxByOrNull { similarity(topic, cands[it].title) } ?: 0
        else -> 0
    }

    fun median(values: List<Long>): Long {
        val v = values.filter { it >= 0 }.sorted()
        if (v.isEmpty()) return -1
        return if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
    }

    fun jaccard(a: Set<String>, b: Set<String>): Double = if (a.isEmpty() && b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    /** Herfindahl–Hirschman index of counts (sum of squared shares). */
    fun hhi(counts: Collection<Int>): Double {
        val total = counts.sum().toDouble()
        if (total <= 0) return 0.0
        return counts.sumOf { (it / total) * (it / total) }
    }

    /** Shannon entropy divided by its maximum ln(k); 0 for a single category. */
    fun entropy(counts: Collection<Int>): Double {
        val c = counts.filter { it > 0 }
        if (c.size < 2) return 0.0
        val total = c.sum().toDouble()
        return -c.sumOf { val p = it / total; p * ln(p) } / ln(c.size.toDouble())
    }

    fun report(run: AuditRun): AuditReport {
        val walks = run.walks.filter { it.steps.isNotEmpty() }
        val maxSteps = walks.maxOfOrNull { it.steps.size } ?: 0
        val perStep = (0..maxSteps).mapNotNull { s ->
            val items = walks.mapNotNull { it.path.getOrNull(s) }
            if (items.isEmpty()) return@mapNotNull null
            val newCh = if (s == 0) 0.0 else walks.mapNotNull { w ->
                val p = w.path
                if (s >= p.size) null else if (p.take(s).none { it.channel == p[s].channel }) 1.0 else 0.0
            }.average()
            AuditStepStat(
                step = s, n = items.size,
                similarity = items.map { similarity(run.topic, it.title) }.average(),
                onTopic = items.count { similarity(run.topic, it.title) > 0 }.toDouble() / items.size,
                medianViews = median(items.map { it.views }),
                medianAgeDays = median(items.map { if (it.uploadMs > 0) (run.at - it.uploadMs) / 86_400_000L else -1L }),
                shorts = items.count { it.isShort }.toDouble() / items.size,
                newChannels = newCh
            )
        }
        val base = perStep.firstOrNull()?.similarity ?: 0.0
        val halfLife = if (base <= 0) null else perStep.firstOrNull { it.step > 0 && it.similarity < base / 2 }?.step
        val firstViews = perStep.getOrNull(1)?.medianViews ?: -1
        val lastViews = perStep.lastOrNull()?.takeIf { it.step > 1 }?.medianViews ?: -1
        val lift = if (firstViews > 0 && lastViews >= 0) lastViews.toDouble() / firstViews else 1.0

        val chosen = walks.flatMap { w -> w.steps.map { it.item } }
        val chCount = chosen.groupingBy { it.channel.ifBlank { "?" } }.eachCount()
        val total = chosen.size.coerceAtLeast(1).toDouble()
        val topChannels = chCount.entries.sortedByDescending { it.value }.take(15).map { AuditCount(it.key, it.key, it.key, it.value, it.value / total) }

        val walkSets = walks.map { w -> w.steps.map { it.item.channel }.toSet() }
        val pairs = mutableListOf<Double>()
        for (i in walkSets.indices) for (j in i + 1 until walkSets.size) pairs.add(jaccard(walkSets[i], walkSets[j]))
        val convergence = if (pairs.isEmpty()) 0.0 else pairs.average()

        val byId = HashMap<String, AuditItem>()
        val reach = HashMap<String, Int>()
        walks.forEach { w -> w.steps.map { it.item }.distinctBy { it.id }.forEach { byId[it.id] = it; reach[it.id] = (reach[it.id] ?: 0) + 1 } }
        val attractors = reach.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.take(15)
            .map { e -> byId.getValue(e.key).let { AuditCount(it.id, it.title, it.channel, e.value, e.value.toDouble() / walks.size) } }

        val shownCount = HashMap<String, Int>()
        val shownTotal = walks.sumOf { w -> w.steps.size }.coerceAtLeast(1)
        walks.forEach { w -> w.steps.forEach { s -> s.shown.forEach { byId.putIfAbsent(it.id, it); shownCount[it.id] = (shownCount[it.id] ?: 0) + 1 } } }
        val exposure = shownCount.entries.sortedByDescending { it.value }.take(15)
            .map { e -> byId.getValue(e.key).let { AuditCount(it.id, it.title, it.channel, e.value, e.value.toDouble() / shownTotal) } }

        val rep = AuditReport(
            walks = walks.size, steps = maxSteps, perStep = perStep, halfLife = halfLife, popularityLift = lift,
            channelsDistinct = chCount.size, hhi = hhi(chCount.values), entropy = entropy(chCount.values),
            convergence = convergence, topChannels = topChannels, attractors = attractors, exposure = exposure,
            rankMean = walks.flatMap { w -> w.steps.map { it.rank } }.average().takeIf { !it.isNaN() } ?: 0.0,
            deadEnds = run.walks.count { it.end != "done" }, findings = emptyList()
        )
        return rep.copy(findings = findings(rep))
    }

    /** Plain-language conclusions from thresholds (shown without AI). */
    fun findings(r: AuditReport): List<String> {
        val out = mutableListOf<String>()
        if (r.walks == 0) return out
        val first = r.perStep.firstOrNull()
        val last = r.perStep.lastOrNull()
        if (first != null && last != null && last.step > 0) {
            val f = (first.onTopic * 100).toInt()
            val l = (last.onTopic * 100).toInt()
            out += when {
                r.halfLife != null -> t("Тема размывается: к шагу ${r.halfLife} схожесть с темой падает вдвое (по теме на старте $f%, в конце $l%).",
                    "The topic fades: by step ${r.halfLife} similarity halves (on topic at start $f%, at the end $l%).")
                l >= f * 0.8 -> t("Алгоритм держит тему: по теме в конце $l% (на старте $f%).", "The algorithm keeps the topic: $l% on topic at the end (start $f%).")
                else -> t("Тема уходит постепенно: по теме в конце $l% (на старте $f%).", "The topic drifts gradually: $l% on topic at the end (start $f%).")
            }
        }
        when {
            r.popularityLift >= 3 -> out += t("Сильный крен к популярному: медиана просмотров выросла в ${fmt1(r.popularityLift)} раза.",
                "Strong pull to popular content: median views grew ×${fmt1(r.popularityLift)}.")
            r.popularityLift in 0.0..0.34 -> out += t("Алгоритм уводит к нишевому: медиана просмотров упала в ${fmt1(1 / r.popularityLift)} раза.",
                "The algorithm leads to niche content: median views fell ×${fmt1(1 / r.popularityLift)}.")
        }
        if (r.hhi >= 0.15) {
            val top = r.topChannels.firstOrNull()
            out += t("Высокая концентрация каналов (HHI ${fmt2(r.hhi)})" + (top?.let { ": «${it.title}» — ${(it.share * 100).toInt()}% шагов." } ?: "."),
                "High channel concentration (HHI ${fmt2(r.hhi)})" + (top?.let { ": “${it.title}” — ${(it.share * 100).toInt()}% of steps." } ?: "."))
        } else if (r.entropy >= 0.85) {
            out += t("Каналы разнообразны (энтропия ${fmt2(r.entropy)}, каналов ${r.channelsDistinct}).", "Channels are diverse (entropy ${fmt2(r.entropy)}, ${r.channelsDistinct} channels).")
        }
        r.attractors.firstOrNull()?.takeIf { it.share >= 0.5 && r.walks >= 3 }?.let {
            out += t("Аттрактор: «${it.title}» (${it.channel}) встречается в ${it.count} из ${r.walks} прогулок.",
                "Attractor: “${it.title}” (${it.channel}) appears in ${it.count} of ${r.walks} walks.")
        }
        if (r.convergence >= 0.3) out += t("Прогулки сходятся к одним каналам (сходство ${fmt2(r.convergence)}).", "Walks converge to the same channels (similarity ${fmt2(r.convergence)}).")
        if (first != null && last != null && last.shorts - first.shorts >= 0.3) {
            out += t("Растёт доля Shorts: ${(first.shorts * 100).toInt()}% → ${(last.shorts * 100).toInt()}%.", "Shorts share grows: ${(first.shorts * 100).toInt()}% → ${(last.shorts * 100).toInt()}%.")
        }
        if (first != null && last != null && first.medianAgeDays > 0 && last.medianAgeDays >= 0) {
            if (last.medianAgeDays * 3 < first.medianAgeDays) out += t("Крен к свежему: медианный возраст ${first.medianAgeDays} → ${last.medianAgeDays} дн.",
                "Pull to fresh content: median age ${first.medianAgeDays} → ${last.medianAgeDays} days.")
            else if (last.medianAgeDays > first.medianAgeDays * 3) out += t("Крен к старому: медианный возраст ${first.medianAgeDays} → ${last.medianAgeDays} дн.",
                "Pull to older content: median age ${first.medianAgeDays} → ${last.medianAgeDays} days.")
        }
        if (r.deadEnds > 0) out += t("Тупиков и петель: ${r.deadEnds} (все рекомендации уже просмотрены или пусты).", "Dead ends and loops: ${r.deadEnds} (all recommendations seen or empty).")
        return out
    }

    fun fmt1(d: Double) = String.format(java.util.Locale.US, "%.1f", d)
    fun fmt2(d: Double) = String.format(java.util.Locale.US, "%.2f", d)
    fun pct(d: Double) = "${(d * 100).toInt()}%"

    /** Compact numbers for tables: 1.2K, 3.4M. */
    fun short(n: Long): String = when {
        n < 0 -> "—"
        n < 1000 -> n.toString()
        n < 1_000_000 -> fmt1(n / 1000.0) + "K"
        n < 1_000_000_000 -> fmt1(n / 1_000_000.0) + "M"
        else -> fmt1(n / 1_000_000_000.0) + "B"
    }

    /** Side-by-side key metrics of two runs. */
    fun compare(a: AuditReport, b: AuditReport, nameA: String, nameB: String): String {
        fun row(label: String, x: String, y: String) = "| $label | $x | $y |"
        val la = a.perStep.lastOrNull()
        val lb = b.perStep.lastOrNull()
        return listOf(
            "| ${t("Метрика", "Metric")} | $nameA | $nameB |", "|---|---|---|",
            row(t("Прогулок × шагов", "Walks × steps"), "${a.walks} × ${a.steps}", "${b.walks} × ${b.steps}"),
            row(t("Полураспад темы, шаг", "Topic half-life, step"), a.halfLife?.toString() ?: "—", b.halfLife?.toString() ?: "—"),
            row(t("По теме в конце", "On topic at the end"), la?.let { pct(it.onTopic) } ?: "—", lb?.let { pct(it.onTopic) } ?: "—"),
            row(t("Рост популярности", "Popularity lift"), "×" + fmt1(a.popularityLift), "×" + fmt1(b.popularityLift)),
            row(t("Каналов", "Channels"), a.channelsDistinct.toString(), b.channelsDistinct.toString()),
            row("HHI", fmt2(a.hhi), fmt2(b.hhi)),
            row(t("Энтропия", "Entropy"), fmt2(a.entropy), fmt2(b.entropy)),
            row(t("Сходимость", "Convergence"), fmt2(a.convergence), fmt2(b.convergence)),
            row("Shorts " + t("в конце", "at the end"), la?.let { pct(it.shorts) } ?: "—", lb?.let { pct(it.shorts) } ?: "—")
        ).joinToString("\n")
    }

    /** Markdown report (export and AI input). */
    fun markdown(run: AuditRun, r: AuditReport, title: String): String {
        val sb = StringBuilder()
        sb.append("# ").append(t("Аудит алгоритма YouTube", "YouTube algorithm audit")).append(": ").append(title).append("\n\n")
        sb.append(Fmt.dateTime(run.at)).append(" · ").append(AuditConfig.strategyName(run.cfg.strategy))
            .append(" · top-").append(run.cfg.topK).append(" · ").append(t("прогулок", "walks")).append(' ').append(r.walks)
            .append(" · ").append(t("запросов", "requests")).append(' ').append(run.requests)
        if (run.cancelled) sb.append(" · ").append(t("остановлен", "stopped"))
        sb.append("\n\n## ").append(t("Выводы", "Findings")).append("\n")
        r.findings.forEach { sb.append("- ").append(it).append('\n') }
        sb.append("\n## ").append(t("Метрики", "Metrics")).append("\n")
        sb.append("- ").append(t("Полураспад темы: ", "Topic half-life: ")).append(r.halfLife?.let { t("шаг $it", "step $it") } ?: t("не достигнут", "not reached")).append('\n')
        sb.append("- ").append(t("Рост популярности: ×", "Popularity lift: ×")).append(fmt1(r.popularityLift)).append('\n')
        sb.append("- ").append(t("Каналов: ", "Channels: ")).append(r.channelsDistinct).append(", HHI ").append(fmt2(r.hhi))
            .append(", ").append(t("энтропия ", "entropy ")).append(fmt2(r.entropy)).append('\n')
        sb.append("- ").append(t("Сходимость прогулок: ", "Walk convergence: ")).append(fmt2(r.convergence)).append('\n')
        sb.append("- ").append(t("Средняя позиция выбора: ", "Mean chosen position: ")).append(fmt1(r.rankMean + 1)).append('\n')
        sb.append("\n## ").append(t("По шагам", "By step")).append("\n")
        sb.append(t("| Шаг | N | Схожесть | По теме | Медиана просмотров | Возраст, дн | Shorts | Новые каналы |",
            "| Step | N | Similarity | On topic | Median views | Age, days | Shorts | New channels |")).append("\n|---|---|---|---|---|---|---|---|\n")
        r.perStep.forEach { s ->
            sb.append("| ${s.step} | ${s.n} | ${fmt2(s.similarity)} | ${pct(s.onTopic)} | ${short(s.medianViews)} | ${if (s.medianAgeDays < 0) "—" else s.medianAgeDays} | ${pct(s.shorts)} | ${pct(s.newChannels)} |\n")
        }
        fun list(h: String, l: List<AuditCount>, video: Boolean) {
            if (l.isEmpty()) return
            sb.append("\n## ").append(h).append("\n")
            l.forEach { sb.append("- ").append(if (video) "${it.title} — ${it.channel}" else it.title).append(" · ").append(it.count).append(" (").append(pct(it.share)).append(")\n") }
        }
        list(t("Каналы, куда ведёт алгоритм", "Channels the algorithm leads to"), r.topChannels, false)
        list(t("Аттракторы (видео в нескольких прогулках)", "Attractors (videos in several walks)"), r.attractors, true)
        list(t("Чаще всего показывались в топе", "Shown most often in the top"), r.exposure, true)
        sb.append("\n## ").append(t("Прогулки", "Walks")).append("\n")
        run.walks.forEachIndexed { i, w ->
            sb.append("\n").append(i + 1).append(". ").append(w.seed.title.ifBlank { w.seed.id }).append("\n")
            w.steps.forEachIndexed { j, s -> sb.append("   ${j + 1}. [#${s.rank + 1}] ${s.item.title} — ${s.item.channel}\n") }
            if (w.end != "done") sb.append("   — ").append(w.end).append('\n')
        }
        sb.append("\n").append(t("Метод: чёрный ящик (sock-puppet) без аккаунта, неперсонализированная выдача «Следующее». ",
            "Method: black-box (sock-puppet) without an account, non-personalised “Up next”. "))
            .append(t("Схожесть — доля слов названия из словаря темы.", "Similarity is the share of title words from the topic vocabulary.")).append('\n')
        return sb.toString()
    }

    /** Walks as a graph for GraphActivity (depth = step, seeds marked, relevance = on topic). */
    fun graph(run: AuditRun): RecGraph {
        val g = RecGraph()
        g.mode = "audit"
        g.updatedAt = run.at
        g.requests = run.requests
        fun node(it: AuditItem, depth: Int, seed: Boolean) {
            val n = g.nodes.getOrPut(it.id) {
                RecNode(it.id, title = it.title, channel = it.channel, channelUrl = it.channelUrl, views = it.views,
                    uploadMs = it.uploadMs, thumb = it.thumb, isShort = it.isShort, depth = depth, expanded = true,
                    relevant = if (similarity(run.topic, it.title) > 0) 1 else 0)
            }
            n.depth = minOf(n.depth, depth)
            if (seed) n.seed = true
        }
        run.walks.forEach { w ->
            node(w.seed, 0, true)
            var prev = w.seed.id
            w.steps.forEachIndexed { i, s ->
                node(s.item, i + 1, false)
                g.addEdge(prev, s.item.id, s.rank)
                prev = s.item.id
            }
        }
        return g
    }
}

/** Runs an audit for a project in the background service (kind "audit"). */
object AuditEngine {
    const val GRAPH_FILE = "audit_graph.json"
    private val yt get() = ServiceList.YouTube

    fun seeds(p: Project, cfg: AuditConfig): List<AuditItem> {
        val vids = Store.videos(p.id).values.filter { !isLocalSource(it.id) }
        fun of(v: Video) = AuditItem(v.id, v.title, v.channel, v.channelUrl, v.views, v.uploadMs, v.isShort, v.thumb)
        return when (cfg.seeds) {
            "custom" -> cfg.customSeeds.split('\n', ' ', ',').mapNotNull { YtQuery.videoIdFromUrl(it.trim()) }.distinct()
                .map { id -> vids.firstOrNull { it.id == id }?.let(::of) ?: AuditItem(id) }
            "top" -> vids.sortedByDescending { it.views }.map(::of).take(cfg.seedCount)
            else -> vids.filter { it.marked }.ifEmpty { vids.sortedByDescending { it.views } }.map(::of).take(cfg.seedCount)
        }
    }

    private fun item(it: StreamInfoItem): AuditItem? {
        val id = YtQuery.videoIdFromUrl(it.url) ?: return null
        return AuditItem(
            id, it.name ?: "", it.uploaderName ?: "", it.uploaderUrl ?: "", it.viewCount,
            it.uploadDate?.instant?.toEpochMilli() ?: -1L,
            it.isShortFormContent || it.url.contains("/shorts/") || it.duration in 1..60,
            it.thumbnails.maxByOrNull { img -> img.width }?.url ?: ""
        )
    }

    /** Opens the watch page of [cur], refreshes its metadata and returns "Up next". */
    private fun related(cur: AuditItem): List<AuditItem> {
        val d = Settings.requestDelayMs
        if (d > 0) Thread.sleep(d.toLong())
        val ex = yt.getStreamExtractor(cur.url)
        ex.fetchPage()
        fun <T> safe(f: () -> T): T? = try {
            f()
        } catch (e: Exception) {
            null
        }
        safe { ex.name }?.takeIf { it.isNotBlank() }?.let { cur.title = it }
        safe { ex.uploaderName }?.takeIf { it.isNotBlank() }?.let { cur.channel = it }
        safe { ex.uploaderUrl }?.let { cur.channelUrl = it }
        safe { ex.viewCount }?.takeIf { it >= 0 }?.let { cur.views = it }
        safe { ex.uploadDate?.instant?.toEpochMilli() }?.let { cur.uploadMs = it }
        if (cur.thumb.isEmpty()) safe { ex.thumbnails.maxByOrNull { it.width }?.url }?.let { cur.thumb = it }
        return (safe { ex.relatedItems?.items } ?: emptyList()).filterIsInstance<StreamInfoItem>().mapNotNull(::item)
    }

    fun run(pid: String, cancel: AtomicBoolean, progress: (String) -> Unit): String {
        val p = Store.project(pid) ?: return ""
        val cfg = AuditConfig.load(pid)
        val seeds = seeds(p, cfg)
        if (seeds.isEmpty()) return t("Нет стартовых видео", "No seed videos")
        val run = AuditRun(cfg = cfg, topic = TopicFilter.keywordsOf(p) + AuditMath.topicOf(seeds.map { it.title }))
        val cache = HashMap<String, List<AuditItem>>()
        val meta = HashMap<String, AuditItem>() // seeds refreshed from their watch page
        val walks = cfg.effectiveWalks()
        val total = seeds.size * walks
        var stop = false
        for ((si, seed) in seeds.withIndex()) {
            for (wi in 0 until walks) {
                if (cancel.get() || run.requests >= cfg.budget) {
                    stop = true
                    break
                }
                val rnd = Random(run.at + si * 1000L + wi)
                val walk = AuditWalk((meta[seed.id] ?: seed).copy())
                run.walks.add(walk)
                var cur = walk.seed
                val visited = hashSetOf(cur.id)
                for (step in 1..cfg.steps) {
                    if (cancel.get() || run.requests >= cfg.budget) {
                        walk.end = "stopped"
                        break
                    }
                    progress(t("Прогулка ${run.walks.size}/$total · шаг $step/${cfg.steps} · запросов ${run.requests}\n",
                        "Walk ${run.walks.size}/$total · step $step/${cfg.steps} · requests ${run.requests}\n") + cur.title.ifBlank { cur.id })
                    val rel = cache[cur.id] ?: try {
                        run.requests++
                        related(cur).also {
                            cache[cur.id] = it
                            if (step == 1) meta[cur.id] = cur.copy()
                        }
                    } catch (e: Exception) {
                        L.w("audit", "step from ${cur.id} failed: ${e.message}")
                        walk.end = "error"
                        break
                    }
                    val shown = rel.filter { !cfg.excludeShorts || !it.isShort }.filter { it.id != cur.id }.take(cfg.topK)
                    if (shown.isEmpty()) {
                        walk.end = "empty"
                        break
                    }
                    val avail = shown.filter { it.id !in visited }
                    if (avail.isEmpty()) {
                        walk.end = "loop"
                        break
                    }
                    val pick = avail[AuditMath.choose(cfg.strategy, avail, run.topic, rnd)].copy()
                    walk.steps.add(AuditStep(pick, shown.indexOfFirst { it.id == pick.id }, shown))
                    visited.add(pick.id)
                    cur = pick
                }
            }
            if (stop) break
        }
        run.cancelled = cancel.get()
        AuditStore.add(pid, run)
        RecGraph.save(pid, AuditMath.graph(run), GRAPH_FILE)
        val r = AuditMath.report(run)
        L.i("audit", "done: walks=${r.walks} steps=${r.steps} requests=${run.requests}")
        return t("Аудит готов: прогулок ${r.walks}, запросов ${run.requests}", "Audit done: ${r.walks} walks, ${run.requests} requests")
    }

    /** Optional AI interpretation of a finished run (through Llm.complete, task "audit"). */
    fun aiVerdict(p: Project, run: AuditRun): String {
        val prov = Provider.active() ?: throw IllegalStateException(t("AI не настроен", "AI is not configured"))
        val md = AuditMath.markdown(run, AuditMath.report(run), p.name).take(14_000)
        val prompt = Prompts.get("audit").replace("{phrase}", p.phrase).replace("{domain}", p.domain.ifBlank { "-" })
            .replace("{lang}", if (Settings.lang == "en") "English" else "русский").replace("{data}", md)
        return Llm.complete(prov, prov.smartModel.ifBlank { prov.fastModel }, Prompts.SYSTEM, prompt, maxTokens = 2500, task = "audit", pid = p.id).text
    }
}
