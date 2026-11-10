/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture rules of Lumen checked on the sources (ArchUnit-style fitness functions, without a dependency):
 * the decisions of the project that the compiler cannot enforce and that a new screen can silently break.
 * Each rule names the file and line that breaks it.
 */
class ArchitectureRulesTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "studio/shared-files.txt").exists() }
    private val lumen = File(root, "app/src/main/java/org/schabi/newpipe/lumen")
    private val studio = File(root, "studio/src/main/java/org/schabi/newpipe/lumen")

    private class Hit(val file: String, val line: Int, val text: String) {
        override fun toString() = "$file:$line  ${text.trim().take(120)}"
    }

    private fun sources(vararg dirs: File) = dirs.flatMap { d -> d.walk().filter { it.extension == "kt" }.toList() }

    /** Lines of [files] matching [re], outside comments. */
    private fun grep(files: List<File>, re: Regex): List<Hit> = files.flatMap { f ->
        f.readLines().withIndex().filter { (_, l) ->
            val t = l.trimStart()
            !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*") && re.containsMatchIn(l)
        }.map { (i, l) -> Hit(f.name, i + 1, l) }
    }

    private fun rule(name: String, hits: List<Hit>) = assertTrue("$name:\n" + hits.take(15).joinToString("\n"), hits.isEmpty())

    @Test
    fun sourcesAreFound() {
        assertTrue(sources(lumen).size > 150)
    }

    @Test
    fun uiIsBuiltInCodeOnly() {
        // CLAUDE.md: UI only in code (DSL in Ui.kt), no Compose, no XML layouts of our own
        rule("Compose in Lumen", grep(sources(lumen, studio), Regex("""^\s*import androidx\.compose""")))
        rule("XML layout in Lumen", grep(sources(lumen, studio), Regex("""(?<!android\.)\bR\.layout\.""")))
    }

    @Test
    fun aiProvidersAreCalledOnlyThroughLlm() {
        // CLAUDE.md: every AI call goes through Llm.complete(task, pid) — journal, limits, Relay, free-AI failover live there
        val hosts = Regex("""generativelanguage\.googleapis\.com|api\.openai\.com|api\.anthropic\.com|api\.groq\.com|openrouter\.ai/api""")
        val allowed = setOf("Llm.kt", "LlmMedia.kt", "FreeAi.kt", "FreeAiStore.kt")
        rule("AI endpoint outside Llm", grep(sources(lumen, studio).filter { it.name !in allowed }, hosts))
    }

    @Test
    fun noSecretsInTheRepository() {
        // keys live only on the phone (KeyVault); the repository is public
        val key = Regex("""AIza[0-9A-Za-z_\-]{35}|sk-ant-[A-Za-z0-9_\-]{20,}|\bsk-(?:proj-)?[A-Za-z0-9]{32,}|\bghp_[A-Za-z0-9]{36}|\bxox[bp]-[0-9A-Za-z\-]{20,}|\b\d{8,10}:AA[0-9A-Za-z_\-]{33}\b""")
        val files = sources(lumen, studio) + File(root, "app/src/main/assets").walk().filter { it.isFile && it.extension in setOf("json", "txt", "html") } +
            File(root, "studio/src/main/assets").walk().filter { it.isFile && it.extension == "json" }
        rule("something that looks like an API key or bot token", files.flatMap { f ->
            f.readLines().withIndex().filter { key.containsMatchIn(it.value) }.map { Hit(f.name, it.index + 1, key.find(it.value)!!.value.take(12) + "…") }
        })
    }

    @Test
    fun noTrackersOrAnalyticsSdks() {
        // CLAUDE.md: libraries without network trackers; data stays on the device
        val sdk = Regex("""firebase|crashlytics|google-analytics|play-services-(ads|analytics|measurement)|appsflyer|amplitude|mixpanel|sentry|bugsnag|flurry|adjust-android|onesignal|appmetrica|facebook-(android|core|login)""", RegexOption.IGNORE_CASE)
        val build = listOf("gradle/libs.versions.toml", "app/build.gradle.kts", "studio/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts")
            .map { File(root, it) }.filter { it.isFile }
        rule("tracking SDK in the build", build.flatMap { f -> f.readLines().withIndex().filter { sdk.containsMatchIn(it.value) }.map { Hit(f.name, it.index + 1, it.value) } })
    }

    @Test
    fun noCleartextTrafficToTheInternet() {
        // http:// only for the local network (own Ollama/LM Studio server) and XML namespaces
        val local = Regex("""http://(localhost|127\.|10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|\[?::1|gexf\.net|www\.w3\.org|schemas\.android\.com|purl\.org|ns\.adobe\.com)""")
        rule("plain http:// to a public host", grep(sources(lumen, studio), Regex("\"http://[A-Za-z0-9\\[]")).filter { !local.containsMatchIn(it.text) })
    }

    @Test
    fun errorsGoThroughTheLumenLog() {
        // printStackTrace goes to logcat only: the user's «Log of failures» (CrashLog, L) never sees it
        rule("printStackTrace instead of L.e/L.w", grep(sources(lumen, studio).filter { it.name != "Log.kt" }, Regex("""\.printStackTrace\(""")))
    }

    @Test
    fun pureLogicDoesNotTouchAndroid() {
        // files whose logic is unit-tested on the JVM: an Android import here breaks their tests in CI only
        val pure = listOf("AudioEdit.kt", "Board.kt", "LlmCore.kt", "EditSound.kt", "AlgoCore.kt", "Bite.kt", "StudioMix.kt",
            "StudioMixAssist.kt", "StudioScheme.kt", "GeminiVoice.kt", "VoiceTone.kt", "UxLogic.kt", "EditTrack.kt", "EditFx.kt", "PlayerCore.kt", "AgentCore.kt", "Predict.kt")
        val files = pure.map { File(lumen, it) }.filter { it.isFile }
        assertTrue("the pure list is outdated", files.size >= pure.size - 2)
        rule("Android import in pure logic", grep(files, Regex("""^import (android|androidx)\.""")))
    }

    @Test
    fun everyFileCarriesTheLicenceHeader() {
        // GPL-3.0 fork: each Lumen file states its licence (REUSE / SPDX)
        val bad = sources(lumen, studio, File(root, "app/src/test/java/org/schabi/newpipe/lumen"))
            .filter { !it.readText().take(400).contains("SPDX-License-Identifier: GPL-3.0") }.map { it.name }
        assertTrue("files without the SPDX header: $bad", bad.isEmpty())
    }

    @Test
    fun noDebugLeftovers() {
        rule("System.out / println in app code", grep(sources(lumen, studio), Regex("""^\s*(System\.(out|err)\.print|println\()""")))
        rule("Thread.sleep on a Handler/UI path", grep(sources(lumen, studio), Regex("""runOnUiThread\s*\{[^}]*Thread\.sleep""")))
    }
}
