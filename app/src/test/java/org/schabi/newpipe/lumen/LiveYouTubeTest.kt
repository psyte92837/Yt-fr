/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live check of NewPipeExtractor against real YouTube. Skipped in normal builds; the extractor-watch workflow runs it
 * with -DlumenLive=1 before and after bumping the extractor. Writes build/lumen-live/result.txt:
 * first line ok / blocked / broken, then the per-check report.
 */
class LiveYouTubeTest {
    private class JvmDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val c = URL(request.url()).openConnection() as HttpURLConnection
            c.requestMethod = request.httpMethod()
            c.connectTimeout = 30_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0")
            request.headers().forEach { (k, vs) -> vs.forEach { c.addRequestProperty(k, it) } }
            request.dataToSend()?.let { data ->
                c.doOutput = true
                c.outputStream.use { it.write(data) }
            }
            val code = c.responseCode
            if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val headers: Map<String, List<String>> = c.headerFields.entries.filter { it.key != null }.associate { it.key!! to it.value }
            return Response(code, c.responseMessage, headers, body, c.url.toString())
        }
    }

    @Test
    fun youtubeWorksThroughExtractor() {
        val on = System.getProperty("lumenLive").orEmpty().ifEmpty { System.getenv("LUMEN_LIVE").orEmpty() }
        assumeTrue("live YouTube check is off (-DlumenLive=1)", on.isNotEmpty())
        NewPipe.init(JvmDownloader())
        var results = YtProbe.run()
        // one retry of the failed checks: YouTube A/B tests and flaky runners
        val failed = results.filter { !it.ok }.map { it.id }.toSet()
        if (failed.isNotEmpty()) {
            Thread.sleep(5_000)
            val again = YtProbe.run(failed + if ("streams" in failed) setOf("video") else emptySet()).associateBy { it.id }
            results = results.map { r -> if (!r.ok) again[r.id] ?: r else r }
        }
        val verdict = YtProbe.verdict(results)
        val report = YtProbe.report(results)
        println("LIVE $verdict\n$report")
        File("build/lumen-live").mkdirs()
        File("build/lumen-live/result.txt").writeText("$verdict\n$report\n")
        assumeTrue("YouTube blocked this IP, result is inconclusive:\n$report", verdict != "blocked")
        assertNotEquals("NewPipeExtractor is broken against live YouTube:\n$report", "broken", verdict)
    }
}
