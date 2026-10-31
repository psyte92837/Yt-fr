/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTest {
    private val watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test
    fun playableNormalisesVideoLinks() {
        assertEquals(watch, MediaLink.playable("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s&si=abc"))
        assertEquals(watch, MediaLink.playable("https://youtu.be/dQw4w9WgXcQ?si=x"))
        assertEquals(watch, MediaLink.playable("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals(watch, MediaLink.playable("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDxyz"))
        assertEquals(watch, MediaLink.playable("  dQw4w9WgXcQ "))
    }

    @Test
    fun playableRejectsNonVideos() {
        assertNull(MediaLink.playable(""))
        assertNull(MediaLink.playable("https://www.youtube.com/@somechannel"))
        assertNull(MediaLink.playable("https://www.youtube.com/playlist?list=PL1234567890"))
        assertNull(MediaLink.playable("https://example.com/video.mp4"))
        assertNull(MediaLink.playable("local:file-123"))
        assertNull(MediaLink.playable("short"))
    }

    @Test
    fun menuOrderAndKeys() {
        assertEquals(listOf(null, MediaAction.BACKGROUND, MediaAction.AUDIO, MediaAction.VIDEO), MediaLink.menu(true))
        assertEquals(listOf(MediaAction.BACKGROUND, MediaAction.AUDIO, MediaAction.VIDEO), MediaLink.menu(false))
        MediaAction.entries.forEach { assertEquals(it, MediaAction.of(it.key)) }
        assertNull(MediaAction.of("nope"))
        assertNull(MediaAction.of(null))
    }
}
