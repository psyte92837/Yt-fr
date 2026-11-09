package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditFxTest {
    private fun text(id: String, s: Long, d: Long) = EBlock(id, "text", s, d, text = "hi")

    @Test
    fun layersOfEachKindAndTheirNames() {
        val snd = EditFx.make("b1", EditFx.soundLook("radio_old"), 1000, 2000, 10_000)
        val flt = EditFx.make("b2", EditFx.filterLook("noir"), 0, 3000, 10_000, 0.5f)
        val adj = EditFx.make("b3", EditFx.adjustLook(EditFx.Adjust(bright = 0.4f, warm = -0.25f)), 0, 3000, 10_000)
        assertEquals(EditFx.Type.SOUND, EditFx.type(snd))
        assertEquals("radio_old", EditFx.soundId(snd))
        assertEquals(EditFx.Type.FILTER, EditFx.type(flt))
        assertEquals("noir", EditFx.filterId(flt))
        assertEquals(0.4f, EditFx.adjust(adj).bright, 1e-4f)
        assertEquals(-0.25f, EditFx.adjust(adj).warm, 1e-4f)
        assertTrue(EditFx.name(snd).contains("📻"))
        assertTrue(EditFx.name(flt).endsWith("50%"))
        // nothing chosen yet
        assertTrue(EditFx.isEmpty(EditFx.make("b4", EditFx.soundLook(""), 0, 1000, 10_000)))
        assertTrue(EditFx.isEmpty(EditFx.make("b5", EditFx.adjustLook(EditFx.Adjust()), 0, 1000, 10_000)))
        assertFalse(EditFx.isEmpty(adj))
    }

    @Test
    fun newLayerStaysInsideTheVideo() {
        val b = EditFx.make("b1", EditFx.filterLook("noir"), 9000, 3000, 10_000)
        assertEquals(7000, b.startMs)
        assertEquals(10_000, b.endMs)
        assertEquals(7000L to 3000L, EditFx.spanAt(9500, 10_000))
        assertEquals(0L to 2000L, EditFx.spanAt(500, 2000))
    }

    @Test
    fun soundAndColourAtATime() {
        val blocks = listOf(
            text("b1", 0, 10_000),
            EditFx.make("b2", EditFx.soundLook("phone"), 2000, 2000, 10_000, 0.7f),
            EditFx.make("b3", EditFx.filterLook("noir"), 3000, 4000, 10_000)
        )
        assertNull(EditFx.soundAt(blocks, 1000))
        assertEquals("phone" to 0.7f, EditFx.soundAt(blocks, 2500))
        assertNull(EditFx.soundAt(blocks, 4000))
        assertTrue(EditFx.mixes(blocks))
        assertNull(EditFx.matrix(null, blocks, 1000))
        assertNotNull(EditFx.matrix(null, blocks, 3500))
        assertEquals("", EditFx.colorKey(blocks, 2500))
        assertTrue(EditFx.colorKey(blocks, 3500).startsWith("b3"))
        // ramps at the edges of a sound layer, full strength inside
        val s = blocks[1]
        assertEquals(0f, EditFx.weight(s, 2000), 1e-4f)
        assertEquals(0.7f, EditFx.weight(s, 3000), 1e-4f)
        assertEquals(0f, EditFx.weight(s, 4000), 1e-4f)
    }

    @Test
    fun layersStackOverThePieceColour() {
        val piece = VClip("c1", 0, 5000, bright = 0.3f)
        val own = EditColor.matrix(piece)!!
        val blocks = listOf(EditFx.make("b1", EditFx.filterLook("noir"), 0, 5000, 5000))
        val both = EditFx.matrix(piece, blocks, 100)!!
        assertFalse(own.contentEquals(both))
        assertTrue(own.contentEquals(EditFx.matrix(piece, blocks, 6000)!!))
    }

    @Test
    fun effectTracksComeRightUnderTheVideo() {
        val blocks = listOf(
            text("b1", 0, 4000),
            text("b2", 1000, 4000),
            EditFx.make("b3", EditFx.soundLook("echo"), 0, 3000, 10_000),
            EditFx.make("b4", EditFx.filterLook("noir"), 1000, 3000, 10_000)
        )
        val lanes = StudioEdit.lanes(blocks)
        assertEquals(0, lanes["b3"])
        assertEquals(1, lanes["b4"])
        assertEquals(2, lanes["b1"])
        assertEquals(3, lanes["b2"])
        // without effects nothing changes
        assertEquals(0, StudioEdit.lanes(blocks.take(2))["b1"])
    }

    @Test
    fun layersKeepTheirEffectInJsonAndStayOffTheFrame() {
        val b = EditFx.make("b1", EditFx.soundLook("church"), 500, 2500, 10_000, 0.6f)
        val back = StudioEdit.blockFrom(StudioEdit.blockJson(b))!!
        assertEquals(EditFx.KIND, back.kind)
        assertEquals("church", EditFx.soundId(back))
        assertEquals(0.6f, back.opacity, 1e-4f)
        assertEquals(2500, back.durMs)
        assertTrue(EditSplit.overlays(listOf(b, text("b2", 0, 1000))).none { it.kind == EditFx.KIND })
    }

    @Test
    fun olderProjectsGetTheirPieceEffectsAsTracks() {
        val clips = listOf(
            VClip("c1", 0, 4000, filter = "noir", filterK = 0.8f, sfx = "radio_old"),
            VClip("c2", 4000, 6000),
            VClip("c3", 6000, 9000, contrast = 0.5f)
        )
        val p = EProject("p", "", 1080, 1920, 9000, clips = clips)
        val up = EditFx.upgrade(p)!!
        assertTrue(EditTrack.of(up).none { it.hasColor || it.sfx.isNotEmpty() })
        val fx = up.blocks.filter { it.kind == EditFx.KIND }
        assertEquals(3, fx.size)
        val flt = fx.first { EditFx.type(it) == EditFx.Type.FILTER }
        assertEquals(0L, flt.startMs)
        assertEquals(4000L, flt.durMs)
        assertEquals(0.8f, flt.opacity, 1e-4f)
        val adj = fx.first { EditFx.type(it) == EditFx.Type.ADJUST }
        assertEquals(6000L, adj.startMs)
        assertEquals(0.5f, EditFx.adjust(adj).contrast, 1e-4f)
        assertEquals("radio_old", EditFx.soundId(fx.first { EditFx.type(it) == EditFx.Type.SOUND }))
        assertNull(EditFx.upgrade(up))
    }
}
