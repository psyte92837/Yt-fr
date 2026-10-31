/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioTemplatesTest {
    private fun words(text: String) = text.split(' ').filter { it.isNotBlank() }.mapIndexed { i, w -> SWord(w, i * 500L, i * 500L + 400) }

    @Test
    fun formulas() {
        assertEquals(0.52, StudioExpr.eval("0.3+i*0.11", mapOf("i" to 2.0))!!, 1e-9)
        assertEquals(0.16, StudioExpr.eval("top")!!, 1e-9)
        assertEquals(0.5, StudioExpr.eval(0.5)!!, 1e-9)
        assertEquals(-2.0, StudioExpr.eval("-(1+1)")!!, 1e-9)
        assertEquals(4.0, StudioExpr.eval("max(1, min(n, 4))", mapOf("n" to 7.0))!!, 1e-9)
        assertEquals(1.0, StudioExpr.eval("clamp(5, 0, 1)")!!, 1e-9)
        assertEquals(0.0, StudioExpr.eval("1/0")!!, 1e-9)
        assertEquals(1.0, StudioExpr.eval("odd(3)")!!, 1e-9)
        assertNull(StudioExpr.eval("x+1"))
        assertNull(StudioExpr.eval("1+"))
        assertNull(StudioExpr.eval("exec(1)"))
        assertNull(StudioExpr.eval("1 2"))
        assertNull(StudioExpr.eval("1".repeat(200)))
    }

    private val listTpl = """{"lumen":"motion","v":1,"id":"Kinetic List","name":"Кинетический список","use":"перечень, выезжают по очереди",
        "layers":[
         {"el":"text","text":"${'$'}title","h":0.07,"keys":[{"t":0,"x":"center","y":0.3,"o":0,"s":0.8},{"t":250,"o":1,"s":1,"ease":"back"}]},
         {"each":"items","sync":"word","el":"group","keys":[{"t":0,"x":0.1,"y":"0.4+i*0.1","o":0},{"t":300,"x":0.18,"o":1,"ease":"out"}],
          "children":[{"el":"emoji","text":"${'$'}it.icon","h":0.06},{"el":"text","text":"${'$'}it.label","dx":0.08,"align":"left","h":0.05}]},
         {"el":"underline","w":0.4,"at":"key","keys":[{"at":"key","x":0.5,"y":0.6,"draw":0},{"at":"key","t":400,"draw":1}]}
        ]}"""

    @Test
    fun parsesMotionTemplate() {
        val r = StudioMotionTpl.parse("Готово:\n$listTpl")
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        val t = r.value!!
        assertEquals("kinetic_list", t.id)
        assertEquals(3, t.layers.size)
        assertEquals(2, t.layers[1].children.size)
        assertEquals("left", t.layers[1].children[1].align)
        assertTrue(JSONObject(t.json).optString("lumen") == "motion")
    }

    @Test
    fun motionErrorsForTheAi() {
        val r = StudioMotionTpl.parse("""{"layers":[{"el":"star","keys":[{"t":0}]},{"el":"ring","keys":[{"t":0,"x":"oops*"}]},{"el":"group","keys":[{"t":0}],"children":[{"el":"group"}]}]}""")
        assertTrue(r.errors.any { it.contains("layers[0].el") })
        assertTrue(r.errors.any { it.contains("layers[1].keys[0].x") })
        assertTrue(r.errors.any { it.contains("группа внутри группы") })
        assertNull(StudioMotionTpl.parse("нет json").value)
    }

    @Test
    fun compilesWithWordsAndItems() {
        val t = StudioMotionTpl.parse(listTpl).value!!
        val w = words("Нужны три вещи: план, дело и отдых")
        val sc = SScene(w.joinToString(" ") { it.text }, "tpl:kinetic_list", "Три вещи", listOf(SItem("🗺", "план"), SItem("⚙️", "дело"), SItem("😴", "отдых")))
        val shapes = StudioMotionTpl.compile(t, sc, w, w.indices, 0, 4000, 5)
        // title + 3 items × (emoji + label) + underline
        assertEquals(8, shapes.size)
        assertEquals("Три вещи", shapes[0].first.text)
        assertEquals(listOf(0L, 250L), shapes[0].second)
        // "план" is word 3 → its item appears when it is said
        val plan = shapes.first { it.first.text == "план" }
        assertEquals(1500L, plan.second[0])
        assertEquals("left", plan.first.align)
        // label sits 0.08 to the right of its emoji, rows go down
        val emoji0 = shapes.first { it.first.text == "🗺" }
        assertEquals(emoji0.first.keys[0].x!! + 0.08f, plan.first.keys[0].x!!, 1e-5f)
        val emoji1 = shapes.first { it.first.text == "⚙️" }
        assertEquals(0.1f, emoji1.first.keys[0].y!! - emoji0.first.keys[0].y!!, 1e-5f)
        // the underline follows the key word (word 5 starts at 2500)
        assertEquals(listOf(2500L, 2900L), shapes.last().second)
        // no items: repeated layers vanish, empty texts are skipped
        val bare = StudioMotionTpl.compile(t, SScene("x", "tpl:kinetic_list"), w, w.indices, 0, 4000, null)
        assertEquals(1, bare.size)
    }

    @Test
    fun alignSurvivesSaving() {
        val s = SShape("text", "A", keys = listOf(SKey(0, x = 0.2f)), align = "left")
        assertEquals(s, StudioKeys.shapeOf(StudioKeys.toJson(s)))
        assertEquals(SScene("x", "tpl:abc"), Studio.parseScript(Studio.toJson(SScript("", listOf(SScene("x", "tpl:abc")))))!!.scenes[0])
    }

    private val editTpl = """{"lumen":"edit","v":1,"id":"fast","name":"Быстрый Shorts","use":"резко и громко",
        "pace":{"sceneWords":[3,8],"cutOnPause":400},"camera":{"punchEvery":0},"style":"dark",
        "rules":[
          {"if":"first","do":{"transition":"none","cues":["zoom@key","sfx:whoosh@start"]}},
          {"if":"number","do":{"kind":"stat","transition":"zoom","cues":["sfx:ding@key"]}},
          {"if":"keyword:деньги|money","do":{"cues":["pop:💰 ДЕНЬГИ:💸@key"]}},
          {"if":"!first & short","do":{"tpl":"kinetic_list"}},
          {"if":"else","do":{"kind":"text","transition":"whip"}}
        ]}"""

    @Test
    fun parsesEditTemplate() {
        val r = StudioEditTpl.parse(editTpl)
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        val t = r.value!!
        assertEquals(3, t.minWords)
        assertEquals(8, t.maxWords)
        assertEquals(400L, t.pauseMs)
        assertEquals(0, t.punchEvery)
        assertEquals(listOf("!first", "short"), t.rules[3].cond)
        assertTrue(StudioEditTpl.summary(t).contains("tpl:kinetic_list"))
        val bad = StudioEditTpl.parse("""{"rules":[{"if":"magic","do":{"kind":"wow","cues":["laser@key"]}}]}""")
        assertTrue(bad.errors.any { it.contains("magic") })
        assertTrue(bad.errors.any { it.contains("wow") })
        assertTrue(bad.errors.any { it.contains("laser") })
        assertNull(StudioEditTpl.parse("""{"rules":[]}""").value)
    }

    @Test
    fun cueStrings() {
        assertEquals(Triple("zoom", emptyList<String>(), "key"), StudioEditTpl.cue("zoom"))
        assertEquals(Triple("sfx", listOf("ding"), "start"), StudioEditTpl.cue("sfx:ding@start"))
        assertEquals(Triple("pop", listOf("ВАЖНО", "⚠️"), "key"), StudioEditTpl.cue("pop:ВАЖНО:⚠️@key"))
        assertNull(StudioEditTpl.cue("sfx:laser"))
        assertNull(StudioEditTpl.cue("zoom@middle"))
    }

    @Test
    fun editTemplateDirects() {
        val t = StudioEditTpl.parse(editTpl).value!!
        val w = words("Почти 80% людей ошибаются в этом. Нам нужны деньги и время прямо сейчас сегодня. Да.")
        val d = StudioEditTpl.direct(t, w)
        val s0 = d.scenes[0]
        // first + number: both rules, the later wins the transition
        assertEquals("stat", s0.kind)
        assertEquals("zoom", s0.transition)
        assertTrue(s0.cues.contains(SCue("sfx", 0, "whoosh")))
        assertTrue(s0.cues.any { it.type == "sfx" && it.text == "ding" })
        val money = d.scenes.first { sc -> (sc.from..sc.to).any { w[it].text == "деньги" } }
        assertTrue(money.cues.any { it.type == "pop" && it.text == "💰 ДЕНЬГИ" })
        val last = d.scenes.last()
        assertEquals("tpl:kinetic_list", last.kind) // "Да." is short and not first
        assertEquals(0, d.scenes.first().from)
        assertEquals(w.lastIndex, d.scenes.last().to)
    }

    @Test
    fun jsonPatch() {
        val doc = JSONObject("""{"name":"A","layers":[{"el":"ring","keys":[{"t":0}]},{"el":"line"}]}""")
        val ops = JSONArray("""[{"op":"replace","path":"/name","value":"B"},{"op":"add","path":"/layers/1","value":{"el":"arrow"}},
            {"op":"remove","path":"/layers/2"},{"op":"add","path":"/layers/0/keys/-","value":{"t":300}},{"op":"add","path":"/use","value":"x"}]""")
        val r = JsonPatch.apply(doc, ops)!!
        assertEquals("B", r.getString("name"))
        assertEquals(listOf("ring", "arrow"), (0 until r.getJSONArray("layers").length()).map { r.getJSONArray("layers").getJSONObject(it).getString("el") })
        assertEquals(2, r.getJSONArray("layers").getJSONObject(0).getJSONArray("keys").length())
        assertEquals("x", r.getString("use"))
        assertEquals("A", doc.getString("name")) // the original stays
        assertNull(JsonPatch.apply(doc, JSONArray("""[{"op":"replace","path":"/nope/x","value":1}]""")))
        assertNull(JsonPatch.apply(doc, JSONArray("""[{"op":"move","path":"/name"}]""")))
        assertNotNull(JsonPatch.ops("Вот: [{\"op\":\"remove\",\"path\":\"/use\"}]"))
        assertNotNull(JsonPatch.ops("{\"patch\":[]}"))
        assertNull(JsonPatch.ops("{\"layers\":[]}"))
    }

    @Test
    fun catalogLines() {
        val t = StudioMotionTpl.parse(listTpl).value!!
        assertTrue(StudioTplCatalog.visuals(listOf(t)).contains("tpl:kinetic_list — перечень"))
        assertEquals(" tpl:kinetic_list", StudioTplCatalog.kinds(listOf(t)))
        assertEquals("", StudioTplCatalog.visuals(emptyList()))
    }

    @Test
    fun videoBriefAndReply() {
        assertEquals(listOf(2.0, 1.5, 6.5), StudioVideoTpl.shots(listOf(2000, 3500), 10_000))
        assertEquals(listOf(10.0), StudioVideoTpl.shots(emptyList(), 10_000))
        val b = StudioVideoTpl.brief("Как это работает", "Канал", 65_000, true, listOf("темп 160 сл/мин"), listOf(2000, 3500),
            listOf(0L to "Привет", 900L to "Привет", 2000L to "Сегодня о главном"), maxSpeech = 40)
        assertTrue(b.contains("1:05 · Shorts"))
        assertTrue(b.contains("- темп 160 сл/мин"))
        assertTrue(b.contains("2.0 1.5 61.5"))
        assertTrue(b.contains("[0:00] Привет\n[0:02] Сегодня о главном"))
        assertEquals(1, Regex("Привет").findAll(b).count()) // repeated cues once
        val parts = StudioVideoTpl.parse("{\"edit\":{\"rules\":[]},\"motions\":[{\"layers\":[]},{\"layers\":[]}],\"notes\":\"быстро\"}")!!
        assertNotNull(parts.edit)
        assertEquals(2, parts.motions.size)
        assertEquals("быстро", parts.notes)
        assertNull(StudioVideoTpl.parse("{\"x\":1}"))
        assertEquals(4 to 3, StudioVideoTpl.grid(12, true))
        assertEquals(6 to 2, StudioVideoTpl.grid(12, false))
    }
}
