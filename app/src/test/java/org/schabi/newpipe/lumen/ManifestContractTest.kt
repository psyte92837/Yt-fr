/*
 * SPDX-FileCopyrightText: 2026 Lumen contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.lumen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Contract of both manifests (Lumen = :app, Lumen Studio = :studio) — mistakes here are not caught by the compiler
 * and show up only on a phone: ActivityNotFoundException, a crash on Android 12+, or Play Protect / Google Play
 * refusing the APK. Pure JVM: the manifests are read as XML.
 */
class ManifestContractTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "studio/shared-files.txt").exists() }
    private val lumenDir = File(root, "app/src/main/java/org/schabi/newpipe/lumen")
    private val studioDir = File(root, "studio/src/main/java/org/schabi/newpipe/lumen")
    private val appManifest = File(root, "app/src/main/AndroidManifest.xml")
    private val studioManifest = File(root, "studio/src/main/AndroidManifest.xml")

    private class Component(val tag: String, val name: String, val el: Element)

    private fun components(f: File, pkg: String): List<Component> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(f)
        val app = doc.getElementsByTagName("application").item(0) as Element
        val out = ArrayList<Component>()
        val kids = app.childNodes
        for (i in 0 until kids.length) {
            val e = kids.item(i) as? Element ?: continue
            if (e.tagName !in setOf("activity", "activity-alias", "service", "receiver", "provider")) continue
            val n = e.getAttribute("android:name")
            out += Component(e.tagName, if (n.startsWith(".")) pkg + n else n, e)
        }
        return out
    }

    private fun permissions(f: File): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val list = doc.getElementsByTagName("uses-permission")
        return (0 until list.length).map { (list.item(it) as Element).getAttribute("android:name") }
    }

    private fun Element.actions(): List<String> {
        val l = getElementsByTagName("action")
        return (0 until l.length).map { (l.item(it) as Element).getAttribute("android:name") }
    }

    private val appPkg = "org.schabi.newpipe"
    private val app by lazy { components(appManifest, appPkg) }
    private val studio by lazy { components(studioManifest, "org.lumen.studio") }

    /** Kotlin classes of Android components declared in [dirs]: name → base class. */
    private fun declaredComponents(vararg dirs: File): Map<String, String> {
        val re = Regex("""^(?:internal |open )?class (\w+)\s*(?:\([^)]*\))?\s*:\s*(LumenBase|Activity|AppCompatActivity|Service|BroadcastReceiver|JobService)\(""", RegexOption.MULTILINE)
        return dirs.flatMap { it.walk().filter { f -> f.extension == "kt" }.toList() }
            .flatMap { f -> re.findAll(f.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList() }.toMap()
    }

    private fun classExists(simple: String, vararg dirs: File): Boolean {
        val re = Regex("""\bclass $simple\b""")
        return dirs.any { d -> d.walk().any { it.extension == "kt" && re.containsMatchIn(it.readText()) } }
    }

    // ---------- Play Protect / Google Play policy (CLAUDE.md: such APKs are blocked from installing) ----------

    private val forbiddenEverywhere = setOf(
        "android.permission.BIND_ACCESSIBILITY_SERVICE", "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
        "android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.RECEIVE_MMS",
        "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG", "android.permission.PROCESS_OUTGOING_CALLS",
        "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.QUERY_ALL_PACKAGES", "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.READ_CONTACTS", "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION",
    )
    private val forbiddenServices = setOf(
        "android.accessibilityservice.AccessibilityService", "android.service.notification.NotificationListenerService",
    )

    @Test
    fun noPermissionThatPlayProtectBlocks() {
        for ((f, comps) in listOf(appManifest to app, studioManifest to studio)) {
            val bad = permissions(f).filter { it in forbiddenEverywhere }
            assertTrue("${f.path}: forbidden permissions $bad", bad.isEmpty())
            for (c in comps) {
                assertTrue("${c.name} is bound by ${c.el.getAttribute("android:permission")}", c.el.getAttribute("android:permission") !in forbiddenEverywhere)
                val acts = c.el.actions().filter { it in forbiddenServices }
                assertTrue("${c.name} is an ${acts.firstOrNull()}", acts.isEmpty())
            }
        }
    }

    @Test
    fun studioForPlayAsksForLessThanLumen() {
        val extra = setOf("android.permission.SYSTEM_ALERT_WINDOW", "android.permission.FOREGROUND_SERVICE_SPECIAL_USE", "android.permission.RECEIVE_BOOT_COMPLETED")
        val bad = permissions(studioManifest).filter { it in extra }
        assertTrue("Lumen Studio (Google Play) must not ask for $bad", bad.isEmpty())
        val newPipe = studio.filter { it.name.startsWith("org.schabi.newpipe.") && !it.name.startsWith("org.schabi.newpipe.lumen.") }
        assertTrue("Lumen Studio declares NewPipe components ${newPipe.map { it.name }}", newPipe.isEmpty())
    }

    // ---------- every declared component exists, every component class is declared ----------

    @Test
    fun declaredLumenComponentsHaveAClass() {
        val generated = mapOf("LumenAgentService" to "LumenAgentServiceBase") // KSP generates it from the base
        for (c in app.filter { ".lumen." in it.name }) {
            val simple = c.name.substringAfterLast('.')
            if (c.tag == "activity-alias") {
                val target = c.el.getAttribute("android:targetActivity").substringAfterLast('.')
                assertTrue("alias ${c.name} points to a missing ${c.el.getAttribute("android:targetActivity")}", classExists(target, lumenDir))
                assertTrue("alias ${c.name} must come after its target in the manifest", app.indexOfFirst { it.name.endsWith(".$target") } in 0 until app.indexOf(c))
                continue
            }
            assertTrue("manifest declares ${c.name}, no such class", classExists(generated[simple] ?: simple, lumenDir))
        }
        for (c in studio.filter { ".lumen." in it.name }) {
            val simple = c.name.substringAfterLast('.')
            assertTrue("studio manifest declares ${c.name}, no such class in shared files or studio/src", classExists(simple, lumenDir, studioDir))
        }
    }

    @Test
    fun everyLumenComponentClassIsInTheManifest() {
        // a screen missing from the manifest compiles fine and crashes with ActivityNotFoundException when opened
        val declared = app.map { it.name.substringAfterLast('.') }.toSet()
        val all = declaredComponents(lumenDir)
        assertTrue("the class scan found only ${all.size} components — the regex is broken", all.size > 30)
        val missing = all.filterKeys { it !in declared && it != "LumenBase" }
        assertTrue("not in app/src/main/AndroidManifest.xml (block ===== Lumen =====): $missing", missing.isEmpty())
    }

    @Test
    fun studioDeclaresEveryScreenOfItsOwnSources() {
        val declared = studio.map { it.name.substringAfterLast('.') }.toSet()
        val missing = declaredComponents(studioDir).filterKeys { it !in declared }
        // studio/src stubs (e.g. PlayerActivity, ProjectActivity) are never started in Studio: only real screens count
        val real = missing.filterKeys { name -> File(studioDir, "StudioStubs.kt").readText().let { !Regex("""\bclass $name\b""").containsMatchIn(it) } }
        assertTrue("not in studio/src/main/AndroidManifest.xml: $real", real.isEmpty())
    }

    // ---------- Android 12+ and structure ----------

    @Test
    fun componentsWithIntentFiltersSayIfTheyAreExported() {
        // targetSdk 31+: a component with an intent-filter and no android:exported fails to install
        for (c in app + studio) {
            if (c.el.getElementsByTagName("intent-filter").length == 0) continue
            assertTrue("${c.name} has an intent-filter but no android:exported", c.el.hasAttribute("android:exported"))
        }
    }

    @Test
    fun noComponentIsDeclaredTwice() {
        for (list in listOf(app, studio)) {
            val dup = list.groupBy { it.name }.filterValues { it.size > 1 }.keys
            assertEquals("declared twice: $dup", emptySet<String>(), dup)
        }
    }

    @Test
    fun lumenComponentsStayInsideTheLumenBlock() {
        // NewPipe's part of the manifest is not ours to change (CLAUDE.md): Lumen entries live between the markers
        val s = appManifest.readText()
        val a = s.indexOf("===== Lumen")
        val b = s.indexOf("===== /Lumen =====")
        assertTrue("markers of the Lumen block are missing", a in 0 until b)
        assertTrue(app.count { ".lumen." in it.name } > 30)
        val outside = Regex("""android:name="([^"]*\.lumen\.[^"]*)"""").findAll(s).filter { it.range.first !in a..b }.map { it.groupValues[1] }.toList()
        assertTrue("Lumen components outside the block: $outside", outside.isEmpty())
    }

    @Test
    fun exportedShareTargetsOnlyTakeWhatTheyHandle() {
        // an exported SEND/VIEW target without a MIME type would be offered for every file in the share sheet
        for (c in app + studio) {
            if (c.el.getAttribute("android:exported") != "true") continue
            val filters = c.el.getElementsByTagName("intent-filter")
            for (i in 0 until filters.length) {
                val f = filters.item(i) as Element
                val acts = f.actions()
                if (acts.none { it == "android.intent.action.SEND" || it == "android.intent.action.SEND_MULTIPLE" }) continue
                val data = f.getElementsByTagName("data")
                val types = (0 until data.length).map { (data.item(it) as Element).getAttribute("android:mimeType") }.filter { it.isNotEmpty() }
                assertTrue("${c.name}: share target without mimeType", types.isNotEmpty())
                assertTrue("${c.name}: share target takes everything (*/*)", types.none { it == "*/*" })
            }
        }
    }
}
