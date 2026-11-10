/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioStockTest {
    @Test
    fun ladderWidensTheSearch() {
        assertEquals(listOf("busy city street at night", "street night", "street"), StudioStock.ladder("busy city street at night"))
        assertEquals(listOf("ocean"), StudioStock.ladder(" ocean "))
        assertEquals(emptyList<String>(), StudioStock.ladder("  "))
    }

    @Test
    fun pexelsTakesTheBiggestMp4UpToFullHd() {
        val json = """{"videos":[{"id":7,"width":3840,"height":2160,"duration":12,"url":"https://www.pexels.com/video/aerial-view-of-city-3571264/",
            "image":"https://i/p.jpg","user":{"name":"Ann"},"video_files":[
            {"file_type":"video/mp4","width":3840,"height":2160,"link":"https://v/4k.mp4"},
            {"file_type":"video/mp4","width":1920,"height":1080,"link":"https://v/fhd.mp4"},
            {"file_type":"video/mp4","width":1280,"height":720,"link":"https://v/hd.mp4"}]}]}"""
        val c = StudioStock.parsePexels(json).single()
        assertEquals(listOf("https://v/fhd.mp4", "https://v/hd.mp4"), c.urls)
        assertEquals(12_000L, c.durMs)
        assertEquals("aerial view of city", c.title)
        assertTrue(StudioStock.credit(c).contains("by Ann (Pexels)"))
    }

    @Test
    fun pixabayTiers() {
        val json = """{"hits":[{"id":5,"pageURL":"https://pixabay.com/videos/x-5/","tags":"sea, waves","duration":20,"user":"bob",
            "videos":{"large":{"url":"","width":0,"height":0},"medium":{"url":"https://p/m.mp4","width":1280,"height":720,"thumbnail":"https://p/t.jpg"},
            "small":{"url":"https://p/s.mp4","width":960,"height":540}}}]}"""
        val c = StudioStock.parsePixabay(json).single()
        assertEquals(listOf("https://p/m.mp4", "https://p/s.mp4"), c.urls)
        assertEquals(20_000L, c.durMs)
    }

    @Test
    fun commonsUsesVp9Transcodes() {
        val json = """{"query":{"pages":{"42":{"pageid":42,"title":"File:Waves.ogv","imageinfo":[{"url":"https://upload.wikimedia.org/wikipedia/commons/a/ab/Waves.ogv",
            "descriptionurl":"https://commons.wikimedia.org/wiki/File:Waves.ogv","width":1280,"height":720,"duration":31.5,"mime":"video/ogg",
            "extmetadata":{"Artist":{"value":"<a href=\"x\">Jo</a>"},"LicenseShortName":{"value":"CC BY-SA 4.0"}}}]},
            "43":{"pageid":43,"title":"File:Pic.jpg","imageinfo":[{"url":"https://upload.wikimedia.org/x.jpg","mime":"image/jpeg"}]}}}}"""
        val c = StudioStock.parseCommons(json).single()
        assertEquals(listOf(
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/a/ab/Waves.ogv/Waves.ogv.720p.vp9.webm",
            "https://upload.wikimedia.org/wikipedia/commons/transcoded/a/ab/Waves.ogv/Waves.ogv.480p.vp9.webm"), c.urls)
        assertEquals(31_500L, c.durMs)
        assertEquals("Jo", c.creator)
        assertEquals("Waves", c.title)
        assertTrue(StudioStock.credit(c).contains("CC BY-SA 4.0, Wikimedia Commons"))
    }

    @Test
    fun rankPrefersShapeLengthAndFreshClips() {
        fun c(id: String, w: Int, h: Int, ms: Long) = StockClip("pexels", id, listOf("u"), "", w, h, ms, "", "", "", "")
        val tall = c("a", 1080, 1920, 10_000)
        val wide = c("b", 1920, 1080, 10_000)
        val short = c("c", 1080, 1920, 2_000)
        assertEquals(listOf("a", "c", "b"), StudioStock.rank(listOf(wide, short, tall), StudioAspect.PORTRAIT, 5_000).map { it.id })
        assertEquals("c", StudioStock.rank(listOf(tall, short), StudioAspect.PORTRAIT, 5_000, setOf("pexels:a")).first().id)
        assertEquals("b", StudioStock.rank(listOf(tall, wide), StudioAspect.LANDSCAPE, 5_000).first().id)
    }

    @Test
    fun videoFiles() {
        assertTrue(StudioStock.isVideo("/x/b_1.webm"))
        assertTrue(StudioStock.isVideo("/x/b_1.MP4"))
        assertFalse(StudioStock.isVideo("/x/b_1.jpg"))
        assertFalse(StudioStock.isVideo(""))
    }
}
