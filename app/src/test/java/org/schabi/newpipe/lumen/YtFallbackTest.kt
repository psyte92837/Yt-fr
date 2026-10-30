/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtFallbackTest {
    @Test
    fun detectsBotCheckInMessageAndCause() {
        val e = Exception("YouTube probably temporarily blocked anonymous watch access with this IP , got error LOGIN_REQUIRED: \"Sign in to confirm")
        assertTrue(YtBlock.isBotCheck(e))
        assertTrue(YtBlock.isBotCheck(RuntimeException("wrapped", e)))
        assertFalse(YtBlock.isBotCheck(Exception("timeout")))
        assertFalse(YtBlock.isBotCheck(null))
    }

    @Test
    fun messageUsesHintOnlyForBotCheck() {
        assertTrue(YtBlock.message(Exception("LOGIN_REQUIRED")).contains("VPN"))
        assertEquals("boom", YtBlock.message(Exception("boom")))
    }

    @Test
    fun parsesOEmbed() {
        val v = OEmbed.parse("Exp2_uVqwow", """{"title":" Song ","author_name":"Chan","author_url":"https://www.youtube.com/@chan","thumbnail_url":"https://i.ytimg.com/vi/x/hq.jpg"}""")
        assertEquals("Song", v.title)
        assertEquals("Chan", v.channel)
        assertEquals("https://www.youtube.com/@chan", v.channelUrl)
        assertEquals("https://www.youtube.com/watch?v=Exp2_uVqwow", v.url)
        assertTrue(OEmbed.url("a b").contains("watch%3Fv%3Da+b"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOEmbedWithoutTitle() {
        OEmbed.parse("x", """{"author_name":"a"}""")
    }
}
