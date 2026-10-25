/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.LinearLayout
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Interactive recommendation graph: pinch to zoom, drag to pan, tap a node, double tap to fit. */
class GraphView(ctx: Context) : View(ctx) {
    var nodes: List<RecNode> = emptyList()
        private set
    private var edges: List<Pair<Int, Int>> = emptyList()
    private var px = DoubleArray(0)
    private var py = DoubleArray(0)
    private var colors = IntArray(0)
    private var radius = FloatArray(0)
    private var labelled = BooleanArray(0)
    var selected = -1
        set(v) {
            field = v
            invalidate()
        }
    var colorBy = "depth"
        set(v) {
            field = v
            recolor()
            invalidate()
        }
    var onTap: (RecNode) -> Unit = {}
    var legend: List<Pair<Int, String>> = emptyList()
        private set

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f
    private val d = resources.displayMetrics.density

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f * d }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG)

    fun setGraph(list: List<RecNode>, e: List<Pair<Int, Int>>, x: DoubleArray, y: DoubleArray) {
        nodes = list
        edges = e
        px = x
        py = y
        val maxHits = max(1, list.maxOfOrNull { it.hits } ?: 1)
        radius = FloatArray(list.size) { i -> (4f + 10f * sqrt(list[i].hits.toFloat() / maxHits)) * d }
        val top = list.indices.sortedByDescending { list[it].pr }.take(12).toSet()
        labelled = BooleanArray(list.size) { it in top || list[it].seed }
        recolor()
        fit()
    }

    private fun hsv(h: Float, s: Float = 0.55f, v: Float = 0.9f) = Color.HSVToColor(floatArrayOf(h, s, v))

    private fun recolor() {
        val n = nodes.size
        colors = IntArray(n)
        val leg = mutableListOf<Pair<Int, String>>()
        when (colorBy) {
            "relevance" -> {
                for (i in 0 until n) {
                    colors[i] = when (nodes[i].relevant) {
                        1 -> Palette.OK
                        0 -> Palette.OUTLINE
                        else -> Palette.WARN
                    }
                }
                leg += Palette.OK to t("по теме", "on topic")
                leg += Palette.OUTLINE to t("не по теме", "off topic")
                leg += Palette.WARN to t("не проверено", "unknown")
            }
            "channel" -> {
                val top = nodes.groupingBy { it.channel }.eachCount().entries.sortedByDescending { it.value }.take(8).map { it.key }
                val map = HashMap<String, Int>()
                top.forEachIndexed { i, ch -> map[ch] = hsv(i * 45f, 0.6f, 0.85f) }
                for (i in 0 until n) colors[i] = map[nodes[i].channel] ?: Palette.OUTLINE
                top.forEach { leg += (map[it] ?: 0) to it.take(18) }
                leg += Palette.OUTLINE to t("другие", "other")
            }
            else -> {
                val maxDepth = max(1, nodes.maxOfOrNull { it.depth } ?: 1)
                for (i in 0 until n) colors[i] = hsv(265f - 245f * nodes[i].depth / maxDepth)
                for (dd in 0..maxDepth step max(1, maxDepth / 5)) {
                    leg += hsv(265f - 245f * dd / maxDepth) to (if (dd == 0) t("старт", "seed") else "$dd")
                }
            }
        }
        legend = leg
    }

    /** Fits the whole graph into the view. */
    fun fit() {
        scale = 1f
        tx = 0f
        ty = 0f
        invalidate()
    }

    private val margin get() = 28f * d
    private fun sx(i: Int) = ((margin + px[i] * (width - 2 * margin)) * scale + tx).toFloat()
    private fun sy(i: Int) = ((margin + py[i] * (height - 2 * margin)) * scale + ty).toFloat()

    override fun onDraw(c: Canvas) {
        c.drawColor(Palette.SURFACE)
        if (nodes.isEmpty()) return
        val focus = selected
        val neighbours = HashSet<Int>()
        if (focus >= 0) edges.forEach { (a, b) -> if (a == focus) neighbours.add(b); if (b == focus) neighbours.add(a) }
        val base = Palette.ON_SURFACE and 0x00FFFFFF
        for ((a, b) in edges) {
            val hot = focus >= 0 && (a == focus || b == focus)
            edgePaint.color = if (hot) Palette.ACCENT else base or (if (focus >= 0) 0x14000000 else 0x30000000)
            edgePaint.strokeWidth = (if (hot) 2f else 1f) * d
            c.drawLine(sx(a), sy(a), sx(b), sy(b), edgePaint)
        }
        val r = sqrt(scale.toDouble()).toFloat()
        for (i in nodes.indices) {
            val dim = focus >= 0 && i != focus && i !in neighbours
            nodePaint.color = if (dim) (colors[i] and 0x00FFFFFF) or 0x55000000 else colors[i]
            c.drawCircle(sx(i), sy(i), radius[i] * r, nodePaint)
            if (nodes[i].seed || i == focus) {
                ringPaint.color = if (i == focus) Palette.ACCENT else Palette.ON_SURFACE
                c.drawCircle(sx(i), sy(i), radius[i] * r + 3 * d, ringPaint)
            }
        }
        labelPaint.color = Palette.ON_SURFACE
        labelBg.color = (Palette.SURFACE and 0x00FFFFFF) or 0xCC000000.toInt()
        for (i in nodes.indices) {
            val show = labelled[i] || i == focus || i in neighbours || scale > 2.5f
            if (!show) continue
            val s = nodes[i].title.ifBlank { nodes[i].id }.let { if (it.length > 28) it.take(27) + "…" else it }
            val x = sx(i) + radius[i] * r + 4 * d
            val y = sy(i) + 4 * d
            val w = labelPaint.measureText(s)
            c.drawRoundRect(x - 2 * d, y - 12 * d, x + w + 2 * d, y + 3 * d, 4 * d, 4 * d, labelBg)
            c.drawText(s, x, y, labelPaint)
        }
    }

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val f = det.scaleFactor
            val ns = min(12f, max(0.4f, scale * f))
            val k = ns / scale
            tx = det.focusX - (det.focusX - tx) * k
            ty = det.focusY - (det.focusY - ty) * k
            scale = ns
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            tx -= dx
            ty -= dy
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            var best = -1
            var bestD = 28f * d
            for (i in nodes.indices) {
                val dist = hypot(sx(i) - e.x, sy(i) - e.y)
                if (dist < bestD) {
                    bestD = dist
                    best = i
                }
            }
            selected = best
            if (best >= 0) onTap(nodes[best])
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            selected = -1
            fit()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }
}

/** Full-screen recommendation graph of a project. */
class GraphActivity : LumenBase() {
    private lateinit var pid: String
    private lateinit var graph: RecGraph
    private var file = RecGraph.FILE
    private val channels get() = file == RecGraph.CHANNELS_FILE
    private lateinit var view: GraphView
    private var colorBy = "depth"
    private var onlyOnTopic = true
    private val layoutCache = HashMap<Boolean, Pair<DoubleArray, DoubleArray>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pid = intent.getStringExtra("pid") ?: return finish()
        file = intent.getStringExtra("file") ?: RecGraph.FILE
        graph = RecGraph.load(pid, file)
        view = GraphView(this)
        view.onTap = { showNode(it) }
        build()
    }

    private fun build() {
        val chips = listOf("depth" to t("Глубина", "Depth"), "relevance" to t("Релевантность", "Relevance"), "channel" to t("Каналы", "Channels"))
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = chips.map { (k, l) ->
            chip(this, l, colorBy == k) {
                colorBy = k
                build()
            }
        } + chip(this, t("Только по теме", "On topic only"), onlyOnTopic) {
            onlyOnTopic = !onlyOnTopic
            build()
        }
        header.addView(chipRow(this, row).apply { setPadding(dp(16), dp(4), dp(16), dp(4)) })
        val legend = text(this, t("Загрузка раскладки…", "Computing layout…"), 12f, color = Palette.MUTED).apply {
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        header.addView(legend)
        val pr = Store.project(pid)
        page(
            (if (channels) t("Каналы · ", "Channels · ") else "") + (pr?.name ?: t("Граф", "Graph")), view, header = header, scroll = false,
            actions = listOf("⤢" to {
                view.selected = -1
                view.fit()
            })
        )
        val all = graph.nodes.values.toList()
        val list = if (onlyOnTopic) all.filter { it.relevant != 0 || it.seed } else all
        val index = HashMap<String, Int>()
        list.forEachIndexed { i, n -> index[n.id] = i }
        val e = graph.edges.mapNotNull { ed -> val a = index[ed.from]; val b = index[ed.to]; if (a != null && b != null) a to b else null }
        val key = onlyOnTopic
        Lumen.async({ layoutCache[key] ?: GraphAlgo.layout(list.size, e).also { layoutCache[key] = it } }, ::err) { (x, y) ->
            view.colorBy = colorBy
            view.setGraph(list, e, x, y)
            view.colorBy = colorBy
            legend.text = t("Узлов ${list.size} · связей ${e.size} · размер = как часто рекомендуют. ",
                "Nodes ${list.size} · edges ${e.size} · size = how often recommended. ") +
                view.legend.joinToString("   ") { "● " + it.second }
            val sb = android.text.SpannableStringBuilder(legend.text)
            var pos = sb.indexOf("●")
            var i = 0
            while (pos >= 0 && i < view.legend.size) {
                sb.setSpan(android.text.style.ForegroundColorSpan(view.legend[i].first), pos, pos + 1, 0)
                pos = sb.indexOf("●", pos + 1)
                i++
            }
            legend.text = sb
        }
    }

    private fun showNode(n: RecNode) {
        if (channels) {
            ChannelActivity.open(this, pid, n.url)
            return
        }
        val parents = graph.parents(n.id)
        val children = graph.children(n.id)
        val msg = buildString {
            append(n.channel).append('\n')
            append("👁 ").append(Fmt.num(n.views)).append(" · ⏱ ").append(Fmt.duration(n.duration)).append(" · ")
            append(if (n.uploadMs > 0) Fmt.date(n.uploadMs) else n.uploadText).append("\n\n")
            append(t("Глубина: ", "Depth: ")).append(n.depth)
            append(t(" · рекомендуют: ", " · recommended by: ")).append(n.hits)
            append(" · PageRank: ").append(String.format("%.2f", n.pr * 1000)).append('\n')
            append(t("Релевантность: ", "Relevance: ")).append(
                when (n.relevant) {
                    1 -> t("по теме", "on topic")
                    0 -> t("не по теме", "off topic")
                    else -> "?"
                }
            )
            if (n.seed) append(t("\nСтартовое видео", "\nSeed video"))
            if (parents.isNotEmpty()) append(t("\n\nОткуда: ", "\n\nFrom: ")).append(parents.take(5).joinToString("; ") { it.title.take(40) })
            if (children.isNotEmpty()) append(t("\nВедёт к: ", "\nLeads to: ")).append(children.take(5).joinToString("; ") { it.title.take(40) })
        }
        AlertDialog.Builder(this).setTitle(n.title.ifBlank { n.id }).setMessage(msg)
            .setPositiveButton(t("Открыть", "Open")) { _, _ -> openVideo(n.url) }
            .setNeutralButton(t("В проект", "Add to project")) { _, _ ->
                val added = Store.mergeVideos(pid, listOf(n.toVideo("rec")))
                toast(if (added > 0) t("Добавлено в проект", "Added to project") else t("Уже в проекте", "Already in project"))
            }
            .setNegativeButton(t("Закрыть", "Close"), null).show()
    }
}
