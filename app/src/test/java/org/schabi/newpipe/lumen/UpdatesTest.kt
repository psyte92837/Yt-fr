/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import java.io.IOException
import java.net.UnknownHostException

class UpdatesTest {
    private val feed = """{"build": 412, "commit": "0123456789abcdef0123456789abcdef01234567",
        "extractor": "e8f89145a830b4d6cae32412007f24ea8b36c73a", "application_id": "org.lumen.analyzer.debug",
        "apk": "https://github.com/psyte92837/Yt-fr/releases/download/lumen-latest/lumen-debug.apk",
        "sha256": "ABCDEF", "size": 31457280, "notes": "Extractor: bump", "date": "2026-10-31T03:00:00Z"}"""

    @Test
    fun parsesFeed() {
        val u = UpdateLogic.parse(feed)
        assertNotNull(u)
        u!!
        assertEquals(412, u.build)
        assertEquals("abcdef", u.sha256)
        assertEquals("org.lumen.analyzer.debug", u.appId)
        assertEquals(31457280L, u.size)
        // round trip through prefs
        val again = UpdateLogic.parse(u.toJson())!!
        assertEquals(u.build, again.build)
        assertEquals(u.apk, again.apk)
        assertEquals(u.extractor, again.extractor)
    }

    @Test
    fun rejectsBrokenFeed() {
        assertNull(UpdateLogic.parse(""))
        assertNull(UpdateLogic.parse("<html>404</html>"))
        assertNull(UpdateLogic.parse("""{"build": 5}"""))
        assertNull(UpdateLogic.parse("""{"build": 0, "apk": "https://x/y.apk"}"""))
        assertNull(UpdateLogic.parse("""{"build": 5, "apk": "http://insecure/y.apk"}"""))
    }

    @Test
    fun newerOnlyForSameAppAndHigherBuild() {
        val u = UpdateLogic.parse(feed)
        assertTrue(UpdateLogic.isNewer(0, "org.lumen.analyzer.debug", u))
        assertTrue(UpdateLogic.isNewer(411, "org.lumen.analyzer.debug", u))
        assertFalse(UpdateLogic.isNewer(412, "org.lumen.analyzer.debug", u))
        assertFalse(UpdateLogic.isNewer(500, "org.lumen.analyzer.debug", u))
        assertFalse(UpdateLogic.isNewer(1, "org.lumen.analyzer", u))
        assertFalse(UpdateLogic.isNewer(1, "org.lumen.analyzer.debug", null))
    }

    @Test
    fun labels() {
        assertEquals("e8f89145a8", UpdateLogic.extractorLabel("e8f89145a830b4d6cae32412007f24ea8b36c73a"))
        assertEquals("v0.25.2", UpdateLogic.extractorLabel("v0.25.2"))
        assertEquals("?", UpdateLogic.extractorLabel(""))
        assertEquals("00ff10", UpdateLogic.hex(byteArrayOf(0, -1, 16)))
        assertEquals("30.0 MB", UpdateLogic.mb(31457280))
        assertEquals("", UpdateLogic.mb(0))
    }

    @Test
    fun classifiesExtractorFailures() {
        assertEquals(ExtractorHealth.Kind.BROKEN, ExtractorHealth.classify(ParsingException("Could not get initial data")))
        assertEquals(ExtractorHealth.Kind.BROKEN, ExtractorHealth.classify(RuntimeException("wrap", ParsingException("x"))))
        assertEquals(ExtractorHealth.Kind.CONTENT, ExtractorHealth.classify(AgeRestrictedContentException("18+")))
        assertEquals(ExtractorHealth.Kind.NETWORK, ExtractorHealth.classify(IOException("io", UnknownHostException("youtube.com"))))
        assertEquals(ExtractorHealth.Kind.BOT, ExtractorHealth.classify(ParsingException("LOGIN_REQUIRED: Sign in to confirm you're not a bot")))
        assertEquals(ExtractorHealth.Kind.OTHER, ExtractorHealth.classify(IllegalStateException("no key")))
        assertEquals(ExtractorHealth.Kind.OTHER, ExtractorHealth.classify(null))
    }

    @Test
    fun alarmsAfterRepeatedFailures() {
        val now = 10_000_000_000L
        val h = 3_600_000L
        assertFalse(ExtractorHealth.shouldAlarm(listOf(now, now - h), now))
        assertTrue(ExtractorHealth.shouldAlarm(listOf(now, now - h, now - 2 * h), now))
        assertFalse(ExtractorHealth.shouldAlarm(listOf(now, now - 7 * h, now - 8 * h), now))
    }

    @Test
    fun probeVerdict() {
        fun r(ok: Boolean, k: ExtractorHealth.Kind? = null) = ProbeResult("x", ok, k, "", 1)
        assertEquals("ok", YtProbe.verdict(listOf(r(true), r(true))))
        assertEquals("blocked", YtProbe.verdict(listOf(r(true), r(false, ExtractorHealth.Kind.BOT))))
        assertEquals("blocked", YtProbe.verdict(listOf(r(false, ExtractorHealth.Kind.NETWORK))))
        assertEquals("broken", YtProbe.verdict(listOf(r(false, ExtractorHealth.Kind.BOT), r(false, ExtractorHealth.Kind.BROKEN))))
        assertTrue(YtProbe.report(listOf(r(false, ExtractorHealth.Kind.BROKEN))).startsWith("✗ x"))
    }
}
