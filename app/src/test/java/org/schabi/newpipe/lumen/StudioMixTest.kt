/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioMixTest {

    @Test
    fun cutsClipsIntoEqualPieces() {
        val p = StudioMix.pieces("c1", 10_000, 2_000)
        assertEquals(5, p.size)
        assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L), p.map { it.fromMs })
        assertTrue(p.all { it.lenMs == 2_000L })
        // 7 s by 2 s → 3 or 4 equal parts, never past the end
        val q = StudioMix.pieces("c2", 7_000, 2_000)
        assertTrue(q.last().fromMs + q.last().lenMs <= 7_000)
        assertEquals(1, StudioMix.pieces("c3", 900, 2_000).size)
        assertTrue(StudioMix.pieces("c4", 100, 2_000).isEmpty())
    }

    @Test
    fun mixHasNoNeighboursFromOneClip() {
        val clips = listOf("c1" to 10_000L, "c2" to 6_000L, "c3" to 4_000L, "c4" to 2_000L)
        for (seed in 1L..50L) {
            val m = StudioMix.mix(clips, 2_000, seed)
            assertEquals(5 + 3 + 2 + 1, m.size)
            assertEquals(0, StudioMix.repeats(m))
            // every piece once
            assertEquals(m.size, m.map { it.key }.toSet().size)
        }
    }

    @Test
    fun mixDependsOnSeed() {
        val clips = listOf("c1" to 10_000L, "c2" to 10_000L, "c3" to 10_000L)
        val a = StudioMix.mix(clips, 1_000, 1)
        val b = StudioMix.mix(clips, 1_000, 2)
        assertTrue(a.map { it.key } != b.map { it.key })
        assertEquals(a.map { it.key }, StudioMix.mix(clips, 1_000, 1).map { it.key })
    }

    @Test
    fun oneBigClipStillMixesAsWellAsPossible() {
        val m = StudioMix.mix(listOf("c1" to 10_000L, "c2" to 2_000L), 2_000, 7)
        assertEquals(6, m.size)
        // 5 pieces of c1 and 1 of c2: at most 3 neighbours can be avoided
        assertTrue(StudioMix.repeats(m) <= 3)
    }

    @Test
    fun movesAndRemoves() {
        val items = listOf(MixItem("a", 0, 1000), MixItem("b", 0, 1000, marked = true), MixItem("c", 0, 1000))
        assertEquals(listOf("b", "c", "a"), StudioMix.move(items, 0, 2).map { it.clipId })
        assertEquals(listOf("c", "a", "b"), StudioMix.move(items, 2, 0).map { it.clipId })
        assertEquals(items, StudioMix.move(items, 0, 5))
        assertEquals(listOf("a", "c"), StudioMix.removeMarked(items).map { it.clipId })
        assertEquals(listOf(1), StudioMix.chosen(items, true))
        assertEquals(listOf(0, 1, 2), StudioMix.chosen(items, false))
        assertEquals(listOf("a", "c"), StudioMix.keepClips(items, setOf("a", "c")).map { it.clipId })
    }

    @Test
    fun timesAndSlotsGoOneAfterAnother() {
        val items = listOf(MixItem("c1", 2_000, 1_500), MixItem("c2", 0, 2_000), MixItem("c1", 0, 1_000))
        val t = StudioMix.times(items)
        assertEquals(listOf(0L, 1_500L, 3_500L), t.map { it.startMs })
        assertEquals(4_500L, t.last().endMs)
        assertEquals(4_500L, StudioMix.totalMs(items))
        val clips = mapOf("c1" to SClip("c1", "u", 0, 5_000, file = "c1.mp4", leadMs = 300, lenMs = 5_000), "c2" to SClip("c2", "u", 0, 3_000, file = "c2.mp4", leadMs = 0, lenMs = 3_000))
        val s = StudioMix.slots(items, clips)
        assertEquals(3, s.size)
        assertEquals(2_000L, s[0].offsetMs)
        // the file time at the start of the first element is the lead plus its start in the clip
        assertEquals(2_300L, StudioCuts.fileTime(s[0], 0))
        assertEquals(300L, StudioCuts.fileTime(s[2], 3_500))
        // a clip that is not downloaded gets no slot
        assertEquals(2, StudioMix.slots(items, clips - "c2").size)
    }

    @Test
    fun wordsGoToTheirElements() {
        val items = listOf(MixItem("a", 0, 1_000), MixItem("b", 0, 1_000), MixItem("c", 0, 1_000))
        val words = listOf(SWord("раз", 100, 400), SWord("два", 500, 900), SWord("три", 1_200, 1_500), SWord("четыре", 3_200, 3_600))
        val r = StudioMix.wordsIn(words, StudioMix.times(items))
        assertEquals(0..1, r[0])
        assertEquals(2..2, r[1])
        // the last element takes the words after the end of the mix
        assertEquals(3..3, r[2])
        assertTrue(StudioMix.wordsIn(emptyList(), StudioMix.times(items)).all { it.isEmpty() })
    }

    @Test
    fun savesAndReads() {
        val s = SScheme("chain", "Как", listOf(SchNode("a", label = "Один"), SchNode("b", label = "Два")))
        val items = listOf(MixItem("c1", 500, 2_000, marked = true, scheme = s), MixItem("c2", 0, 1_000))
        val back = StudioMix.fromJson(StudioMix.toJson(items))
        assertEquals(2, back.size)
        assertEquals("c1", back[0].clipId)
        assertEquals(500L, back[0].fromMs)
        assertTrue(back[0].marked)
        assertNotNull(back[0].scheme)
        assertEquals("chain", back[0].scheme!!.layout)
        assertEquals(null, back[1].scheme)
        assertTrue(StudioMix.fromJson("nonsense").isEmpty())
        val w = listOf(SWord("привет", 10, 300))
        assertEquals(w, StudioMix.wordsFromJson(StudioMix.wordsJson(w)))
    }

    @Test
    fun promptHasFormatAndTimeline() {
        val items = listOf(MixItem("c1", 0, 2_000), MixItem("c2", 0, 1_500))
        val p = StudioMix.montagePrompt("9:16", items, mapOf("c1" to "кот прыгает"), 3_500, ru = true)
        assertTrue(p.contains("9:16"))
        assertTrue(p.contains("0:00–0:02 c1 «кот прыгает»"))
        assertTrue(p.contains("0:02–0:03.5 c2"))
        assertTrue(StudioMix.montagePrompt("16:9", items, emptyMap(), 3_500, ru = false).contains("16:9"))
    }

    @Test
    fun signatureFollowsOrderAndFormat() {
        val a = listOf(MixItem("c1", 0, 1_000), MixItem("c2", 0, 1_000))
        assertTrue(StudioMix.signature(a, "9:16") != StudioMix.signature(a.reversed(), "9:16"))
        assertTrue(StudioMix.signature(a, "9:16") != StudioMix.signature(a, "16:9"))
        // marks and schemes do not change the picture order
        assertEquals(StudioMix.signature(a, "9:16"), StudioMix.signature(a.map { it.copy(marked = true) }, "9:16"))
    }

    @Test
    fun ownPieceLength() {
        assertEquals(1_500L, StudioMix.parsePiece("1,5"))
        assertEquals(800L, StudioMix.parsePiece("0.8 с"))
        assertEquals(StudioMix.MIN_PIECE_MS, StudioMix.parsePiece("0.1"))
        assertEquals(StudioCuts.MAX_CLIP_MS, StudioMix.parsePiece("90"))
        assertEquals(null, StudioMix.parsePiece("abc"))
        assertEquals(null, StudioMix.parsePiece("0"))
        assertEquals("0.5 с", StudioMix.pieceLabel(500))
        assertEquals("1.5 с", StudioMix.pieceLabel(1_500))
        assertEquals("2 с", StudioMix.pieceLabel(2_000))
    }

    @Test
    fun knowsHandEdits() {
        val clips = listOf("c1" to 6_000L, "c2" to 4_000L)
        val m = StudioMix.mix(clips, 2_000, 3)
        assertTrue(!StudioMix.edited(m, clips, 2_000, 3))
        assertTrue(StudioMix.edited(StudioMix.move(m, 0, 2), clips, 2_000, 3))
        assertTrue(StudioMix.edited(m.drop(1), clips, 2_000, 3))
        assertTrue(StudioMix.edited(m.mapIndexed { i, it -> if (i == 0) it.copy(marked = true) else it }, clips, 2_000, 3))
        // checked against the mix of another length it does not match
        assertTrue(StudioMix.edited(m, clips, 1_000, 3))
    }

    @Test
    fun storyboardStrip() {
        assertEquals(listOf(5, 15, 25, 35, 45, 55, 65, 75, 85, 95), StudioMix.stripFrames(100, 10))
        assertEquals(listOf(0, 1, 2), StudioMix.stripFrames(3, 10))
        assertTrue(StudioMix.stripFrames(0, 10).isEmpty())
        // pages of 5×5 frames of 80×45: frame 26 is the second one on page 1, frame 31 starts its second row + 1
        assertEquals(Triple(1, 80, 0), StudioMix.frameAt(26, 5, 5, 80, 45))
        assertEquals(Triple(1, 80, 45), StudioMix.frameAt(31, 5, 5, 80, 45))
        assertEquals(Triple(0, 0, 0), StudioMix.frameAt(0, 5, 5, 80, 45))
    }

    @Test
    fun randomPiecesStayInsideAndFollowTheSeed() {
        for (seed in 1L..40L) {
            val p = StudioMix.randomPieces("c1", 12_000, 2_000, seed)
            assertTrue(p.isNotEmpty())
            assertTrue(p.all { it.fromMs >= 0 && it.fromMs + it.lenMs <= 12_000 && it.lenMs >= StudioMix.MIN_PIECE_MS })
            // in time order and never overlapping
            assertTrue(p.zipWithNext().all { (a, b) -> a.fromMs + a.lenMs <= b.fromMs })
        }
        assertEquals(StudioMix.randomPieces("c1", 12_000, 2_000, 5), StudioMix.randomPieces("c1", 12_000, 2_000, 5))
        assertTrue(StudioMix.randomPieces("c1", 12_000, 2_000, 5) != StudioMix.randomPieces("c1", 12_000, 2_000, 6))
        assertEquals(1, StudioMix.randomPieces("c1", 400, 2_000, 1).size)
        assertTrue(StudioMix.randomPieces("c1", 100, 2_000, 1).isEmpty())
    }

    @Test
    fun ownMomentsCutUpToTheNextOne() {
        val p = StudioMix.markPieces("c1", 10_000, 2_000, listOf(7_000, 1_000, 2_000, 9_900, 1_000))
        assertEquals(listOf(1_000L, 2_000L, 7_000L), p.map { it.fromMs })
        assertEquals(listOf(1_000L, 2_000L, 2_000L), p.map { it.lenMs })
        assertEquals(listOf(3_000L, 7_500L, 12_000L), StudioMix.parseMarks("0:12, 3; 7.5"))
        assertEquals(null, StudioMix.parseMarks("0:03, abc"))
        assertEquals("0:03, 0:07.5", StudioMix.marksText(listOf(3_000, 7_500)))
        // own cutting of one clip wins over the common one; no moments → equal parts
        val spec = MixSpec("c1", 10_000, ClipCut(MixCut.MARKS, 1_000, listOf(0, 5_000)))
        assertEquals(listOf(0L, 5_000L), StudioMix.cut(spec, 2_000, MixCut.EVEN, 1).map { it.fromMs })
        assertEquals(5, StudioMix.cut(MixSpec("c1", 10_000, ClipCut(MixCut.MARKS)), 2_000, MixCut.EVEN, 1).size)
        assertEquals(10, StudioMix.cut(MixSpec("c1", 10_000, ClipCut(pieceMs = 1_000)), 2_000, MixCut.EVEN, 1).size)
    }

    @Test
    fun pinnedStayInPlaceWhenShuffled() {
        val m = StudioMix.mix(listOf("c1" to 8_000L, "c2" to 8_000L, "c3" to 8_000L), 1_000, 4)
        val pinned = m.mapIndexed { i, it -> if (i == 0 || i == 10) it.copy(locked = true) else it }
        for (seed in 1L..30L) {
            val r = StudioMix.rearrange(pinned, MixOrder.SHUFFLE, seed)
            assertEquals(pinned[0], r[0])
            assertEquals(pinned[10], r[10])
            assertEquals(pinned.map { it.key }.toSet(), r.map { it.key }.toSet())
            assertEquals(0, StudioMix.repeats(r))
        }
        // only the ticked ones swap among their places
        val ticked = m.mapIndexed { i, it -> if (i % 3 == 0) it.copy(marked = true) else it }
        val s = StudioMix.shuffleMarked(ticked, 9)
        ticked.forEachIndexed { i, it -> if (!it.marked) assertEquals(it, s[i]) }
        assertEquals(ticked.filter { it.marked }.map { it.key }.toSet(), s.filter { it.marked }.map { it.key }.toSet())
    }

    @Test
    fun orderAndRound() {
        val pool = StudioMix.mix(listOf("c2" to 3_000L, "c1" to 2_000L, "c10" to 1_000L), 1_000, 1)
        assertEquals(listOf("c1@0", "c1@1000", "c2@0", "c2@1000", "c2@2000", "c10@0"),
            StudioMix.arrange(pool, MixOrder.ORDER, 1).map { it.key })
        assertEquals(listOf("c1", "c2", "c10", "c1", "c2", "c2"), StudioMix.arrange(pool, MixOrder.ROUND, 1).map { it.clipId })
    }

    @Test
    fun newPiecesGoBetweenOthers() {
        val items = listOf(MixItem("a", 0, 1000), MixItem("b", 0, 1000), MixItem("a", 1000, 1000), MixItem("b", 1000, 1000))
        val r = StudioMix.addPieces(items, listOf(MixItem("c", 0, 1000), MixItem("c", 1000, 1000)), 3)
        assertEquals(6, r.size)
        assertEquals(0, StudioMix.repeats(r))
        assertEquals(items.map { it.key }, r.filter { it.clipId != "c" }.map { it.key })
        assertEquals(listOf("c"), StudioMix.missing(items, listOf("a", "c")))
        val re = StudioMix.replaceClip(r, "c", listOf(MixItem("c", 500, 700)), 1)
        assertEquals(listOf(500L), re.filter { it.clipId == "c" }.map { it.fromMs })
    }

    @Test
    fun editsOfOneElement() {
        val it = MixItem("c1", 1_000, 2_000)
        assertEquals(3_000L, StudioMix.shift(it, 5_000, 5_000).fromMs)
        assertEquals(0L, StudioMix.shift(it, -5_000, 5_000).fromMs)
        val longer = StudioMix.withLen(it, 4_500, 5_000)
        assertEquals(4_500L, longer.lenMs)
        assertEquals(500L, longer.fromMs)
        val other = StudioMix.moment(it, 20_000, 2)
        assertTrue(other.fromMs + other.lenMs <= 20_000)
        val items = listOf(it, MixItem("c2", 0, 1_000))
        val d = StudioMix.duplicate(items, 0)
        assertEquals(3, d.size)
        assertEquals(3, d.map { x -> x.key }.toSet().size)
        val h = StudioMix.halves(items, 0)!!
        assertEquals(listOf(1_000L, 2_000L), h.take(2).map { x -> x.fromMs })
        assertEquals(null, StudioMix.halves(listOf(MixItem("c", 0, 500)), 0))
        val ticked = listOf(MixItem("a", 0, 1), MixItem("b", 0, 1, marked = true), MixItem("c", 0, 1))
        assertEquals(listOf("b", "a", "c"), StudioMix.markedTo(ticked, true).map { x -> x.clipId })
        assertEquals(listOf("a", "c", "b"), StudioMix.markedTo(ticked, false).map { x -> x.clipId })
        assertEquals(4, StudioMix.duplicateMarked(ticked).size)
    }

    @Test
    fun trimAndRandomStart() {
        val items = List(10) { k -> MixItem("c$k", 0, 2_000) }
        val t = StudioMix.trimTo(items, 5_000)
        assertEquals(5_000L, StudioMix.totalMs(t))
        assertEquals(3, t.size)
        assertEquals(4_000L, StudioMix.totalMs(StudioMix.trimTo(items, 4_100)))
        for (seed in 1L..20L) {
            val a = StudioMix.randomStart(600_000, 5_000, 10_000, seed)
            assertTrue(a in 0..595_000 && a % 100 == 0L)
        }
        assertEquals(0L, StudioMix.randomStart(3_000, 5_000, 0, 1))
    }

    @Test
    fun keysStayUniqueAndPinsAreSaved() {
        val items = listOf(MixItem("c1", 0, 1000, locked = true), MixItem("c1", 0, 1000))
        val u = StudioMix.unique(items)
        assertEquals(2, u.map { it.key }.toSet().size)
        val back = StudioMix.fromJson(StudioMix.toJson(u))
        assertEquals(u, back)
        val cuts = mapOf("c1" to ClipCut(MixCut.RANDOM, 1_500), "c2" to ClipCut(MixCut.MARKS, 0, listOf(1_000, 3_000)), "c3" to ClipCut())
        assertEquals(cuts - "c3", StudioMix.cutsFromJson(StudioMix.cutsJson(cuts)))
    }

    @Test
    fun fitsTheVoiceOver() {
        val items = listOf(MixItem("a", 0, 2_000), MixItem("b", 0, 2_000), MixItem("a", 2_000, 2_000))
        val caps = mapOf("a" to 10_000L, "b" to 3_000L)
        val longer = StudioMix.scaleTo(items, 9_000, caps)
        assertEquals(9_000L, StudioMix.totalMs(longer))
        // b cannot be longer than its clip, the others take the rest
        assertEquals(3_000L, longer[1].lenMs)
        assertTrue(longer.all { it.fromMs + it.lenMs <= caps.getValue(it.clipId) })
        val shorter = StudioMix.scaleTo(items, 3_000, caps)
        assertEquals(3_000L, StudioMix.totalMs(shorter))
        assertEquals(items.map { it.clipId }, shorter.map { it.clipId })
        val looped = StudioMix.loopTo(items, 10_000)
        assertEquals(10_000L, StudioMix.totalMs(looped))
        assertEquals(looped.size, looped.map { it.key }.toSet().size)
        assertEquals(0, StudioMix.repeats(looped.take(4)))
        assertEquals(4_000L, StudioMix.totalMs(StudioMix.loopTo(items, 4_000)))
        val h = byteArrayOf(*"RIFF".toByteArray(), 0, 0, 0, 0, *ByteArray(14), 1, 0, 0x44, 0xAC.toByte(), 0, 0, *ByteArray(16))
        assertEquals(1_000L, StudioMix.wavMs(h, 44 + 88_200))
    }

    @Test
    fun seamsMoveToWords() {
        val items = listOf(MixItem("a", 0, 2_000), MixItem("b", 0, 2_000), MixItem("a", 3_000, 2_000))
        val caps = mapOf("a" to 10_000L, "b" to 10_000L)
        val words = listOf(SWord("раз", 0, 300), SWord("два", 2_250, 2_500), SWord("три", 3_900, 4_200), SWord("далеко", 5_000, 5_300))
        val s = StudioMix.snapToWords(items, words, caps)
        assertEquals(listOf(2_250L, 1_650L, 2_100L), s.map { it.lenMs })
        assertEquals(6_000L, StudioMix.totalMs(s))
        assertEquals(2, StudioMix.seamsOnWords(s, words))
        assertEquals(0, StudioMix.seamsOnWords(items, words))
    }

    @Test
    fun fitsToTargetLength() {
        val specs = listOf(MixSpec("c1", 20_000), MixSpec("c2", 20_000), MixSpec("c3", 6_000))
        val items = StudioMix.build(specs, 1_000, MixCut.EVEN, 1, 7)
        val caps = mapOf("c1" to 20_000L, "c2" to 20_000L, "c3" to 6_000L)
        assertEquals(46_000L, StudioMix.totalMs(items))
        // shorter: pieces dropped (not squeezed into flicker), every clip stays, exact length
        val short = StudioMix.fitTo(items, 15_000, caps)
        assertEquals(15_000L, StudioMix.totalMs(short))
        assertEquals(setOf("c1", "c2", "c3"), short.map { it.clipId }.toSet())
        assertTrue(short.all { it.lenMs >= 700 })
        // longer than the clips allow: stretched and continued by repeats
        val long = StudioMix.fitTo(items, 90_000, caps)
        assertEquals(90_000L, StudioMix.totalMs(long))
        // pinned pieces stay
        val pinned = items.mapIndexed { i, it -> if (i == 3) it.copy(locked = true) else it }
        assertTrue(StudioMix.thinTo(pinned, 10_000).any { it.locked })
        assertEquals(items, StudioMix.fitTo(items, 0, caps))
    }

    @Test
    fun newPiecesAvoidIntroEndAndOldParts() {
        val used = listOf(10_000L to 15_000L, 40_000L to 45_000L)
        repeat(50) { k ->
            val at = StudioMix.freshStart(100_000, 5_000, used, k.toLong())
            assertTrue(at >= 5_000 && at + 5_000 <= 90_000)
            assertTrue(used.none { (a, b) -> at < b && at + 5_000 > a })
        }
        assertEquals(0L, StudioMix.freshStart(4_000, 5_000, emptyList(), 1))
        val back = StudioMix.usedFromJson(StudioMix.usedJson(mapOf("u1" to used), keep = 1))
        assertEquals(listOf(40_000L to 45_000L), back["u1"])
        assertTrue(StudioMix.usedFromJson("bad").isEmpty())
    }

    @Test
    fun voiceOverScriptShotsAndCleaning() {
        val items = listOf(MixItem("a", 0, 2_000), MixItem("a", 2_000, 2_000), MixItem("b", 0, 3_000), MixItem("a", 9_000, 1_000))
        val shots = StudioMix.scriptShots(items, mapOf("a" to "Кот  на\nкрыше", "b" to ""))
        assertEquals("1. 0:00–0:04 Кот на крыше\n2. 0:04–0:07 —\n3. 0:07–0:08 Кот на крыше", shots)
        val clean = StudioMix.cleanScript("```text\nТекст озвучки:\n**Вот** кот [пауза] гуляет.\n\n0:04–0:07 — А тут **ничего**.\n```")
        assertEquals("Вот кот гуляет.\n\nА тут ничего.", clean)
        assertEquals(listOf(listOf("Вот", "кот", "гуляет."), listOf("А", "тут", "ничего.")), StudioMix.scriptLines(clean))
    }

    @Test
    fun fillsTheSetLengthWithWholePieces() {
        val specs = listOf(MixSpec("c1", 10_000), MixSpec("c2", 10_000))
        // a thinned storyboard: the unused places of the clips fill it back, nothing is stretched
        val board = StudioMix.fillTo(StudioMix.build(specs, 2_000, MixCut.EVEN, 0, 7), 6_000, emptyList(), 1)
        assertEquals(6_000L, StudioMix.totalMs(board))
        val spare = StudioMix.spare(board, specs, 2_000)
        assertTrue(spare.all { p -> board.none { it.clipId == p.clipId && p.fromMs < it.fromMs + it.lenMs && it.fromMs < p.fromMs + p.lenMs } })
        val full = StudioMix.fillTo(board, 16_000, spare, 1)
        assertEquals(16_000L, StudioMix.totalMs(full))
        assertTrue(full.all { it.lenMs <= 2_000 })
        assertEquals(full.size, full.map { it.key }.toSet().size)
        assertTrue(StudioMix.spare(StudioMix.build(specs, 2_000, MixCut.EVEN, 0, 7), specs, 2_000).isEmpty())
        // too long: pieces dropped and the end cut, every clip stays
        val short = StudioMix.fillTo(StudioMix.mix(listOf("c1" to 10_000L, "c2" to 10_000L), 2_000, 3), 7_000, emptyList(), 1)
        assertEquals(7_000L, StudioMix.totalMs(short))
        assertEquals(setOf("c1", "c2"), short.map { it.clipId }.toSet())
        // material ends: shorter, and lackMs says by how much
        val lack = StudioMix.fillTo(StudioMix.mix(listOf("c1" to 4_000L), 2_000, 3), 10_000, emptyList(), 1)
        assertEquals(6_000L, StudioMix.lackMs(lack, 10_000))
    }

    @Test
    fun extraPartsOfTheSameVideosCoverTheLack() {
        val clips = listOf(SClip("c1", "u1", 0, 10_000, durMs = 600_000), SClip("c2", "u2", 5_000, 15_000, durMs = 0))
        val extra = StudioMix.extraParts(clips, 20_000, 10)
        assertTrue(extra.sumOf { it.ms } >= 20_000)
        assertEquals(setOf("u1", "u2"), extra.map { it.url }.toSet())
        assertEquals(extra.size, (clips + extra).map { it.id }.toSet().size - clips.size)
        assertTrue(StudioMix.extraParts(clips, 20_000, 0).isEmpty())
        // a short video already used up gives nothing
        assertTrue(StudioMix.extraParts(listOf(SClip("c1", "u1", 0, 10_000, durMs = 12_000)), 20_000, 10).isEmpty())
    }

    @Test
    fun backgroundClipsCutIntoPiecesFillTheVoiceOver() {
        val clips = listOf("a.mp4" to 10_000L, "b.mp4" to 10_000L)
        val slots = StudioMix.bgSlots(clips, 12_000, 2_000, 5)
        assertEquals(0L, slots.first().startMs)
        assertEquals(12_000L, slots.last().endMs)
        assertTrue(slots.zipWithNext().all { (x, y) -> x.endMs == y.startMs && x.id != y.id })
        // every piece is a different moment: nothing repeats while the clips last
        assertEquals(slots.size, slots.map { it.id to it.offsetMs }.toSet().size)
        assertTrue(slots.all { it.offsetMs + (it.endMs - it.startMs) <= 10_000 })
        // too short: repeats fill the rest
        assertEquals(30_000L, StudioMix.bgSlots(clips, 30_000, 2_000, 5).last().endMs)
    }
}
