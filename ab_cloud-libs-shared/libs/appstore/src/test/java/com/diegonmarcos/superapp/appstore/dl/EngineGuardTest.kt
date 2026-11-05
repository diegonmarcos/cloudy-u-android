package com.diegonmarcos.superapp.appstore.dl

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * #1517 THE GUARD: no UI or fragment code can cancel or pause engine jobs except through an explicit user action.
 *
 *  G1 a [UserAction] is minted only by the two files that draw the user's buttons (DownloadsPanel, StoreProgressPanel)
 *  G2 nothing else calls the engine's stopping verbs (cancel, pause, cancelAll, clearFinished, setPriority)
 *  G3 no lifecycle or check path (onResume, onPause, onStop, onDestroyView, DisposableEffect, LaunchedEffect, reload, checkAll) mints a
 *     UserAction or calls a stopping verb, even in the files that may
 *  G4 the engine package is pure JVM (no android.*, fragment, lifecycle or compose import): it cannot see a screen
 *  G5 the engine never reads the process-wide cancel flag (a stale or foreign flag must not stop a download)
 *  G6 the update-check path (StoreChecks) does not reference the engine at all
 * The guard is itself tested: each rule is shown RED on a mutated source and GREEN on a clean one.
 */
class EngineGuardTest {

    private val mayMint = setOf("DownloadsPanel.kt", "StoreProgressPanel.kt")
    private val verbs = Regex("""\.(cancel|pause|cancelAll|clearFinished|setPriority)\(""")
    private val engineRef = Regex("""\b(StoreEngine|DownloadEngine|engineOrNull\(\))\b|\bengine\b""")
    private val lifecycle = listOf("onResume", "onPause", "onStop", "onDestroyView", "onDestroy", "DisposableEffect", "LaunchedEffect", "fun reload", "fun checkAll")

    /** Source without comments and string literals' line comments: a rule must not trip on prose. */
    private fun code(s: String) = s.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.replace(Regex("""(?<![:"])//.*"""), "") }

    /** The text of the brace block that follows [head] in [s], or null. */
    private fun block(s: String, head: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val i = s.indexOf(head, from); if (i < 0) return out
            val open = s.indexOf('{', i); if (open < 0) return out
            var depth = 0; var j = open
            while (j < s.length) { if (s[j] == '{') depth++ else if (s[j] == '}') { depth--; if (depth == 0) break }; j++ }
            out += s.substring(open, minOf(j + 1, s.length)); from = j.coerceAtLeast(i + head.length)
        }
    }

    /** All violations in [files] (relative path to text). Pure: the mutations below drive it. */
    fun violations(files: Map<String, String>): List<String> {
        val v = ArrayList<String>()
        for ((path, raw) in files) {
            val name = path.substringAfterLast('/')
            val inEngine = "/appstore/dl/" in path || path.endsWith("/StoreEngine.kt")
            val c = code(raw)
            if (!inEngine && name !in mayMint && "UserAction.tap(" in c) v += "G1 $path mints a UserAction"
            if (!inEngine && name !in mayMint) c.lines().filter { verbs.containsMatchIn(it) && engineRef.containsMatchIn(it) }.forEach { v += "G2 $path: ${it.trim()}" }
            if (!inEngine) for (h in lifecycle) for (b in block(c, h)) {
                if ("UserAction.tap(" in b) v += "G3 $path: $h mints a UserAction"
                if (b.lines().any { verbs.containsMatchIn(it) && engineRef.containsMatchIn(it) }) v += "G3 $path: $h stops engine work"
            }
            if ("/appstore/dl/" in path && Regex("""(?m)^import (android\.|androidx\.fragment|androidx\.lifecycle|androidx\.compose)""").containsMatchIn(c)) v += "G4 $path imports android/UI"
            if (inEngine && ("cancelRequested" in c || "requestCancel" in c || "cancelNow" in c)) v += "G5 $path reads the process-wide cancel flag"
            if (path.endsWith("/StoreEngine.kt")) {
                val checks = block(c, "object StoreChecks").firstOrNull().orEmpty()
                if (Regex("""\b(StoreEngine|DownloadEngine|engineOrNull)\b""").containsMatchIn(checks)) v += "G6 StoreChecks references the engine"
            }
        }
        return v
    }

    // ── the real tree ─────────────────────────────────────────────────────────────

    private fun root(): File? {
        var d: File? = File("").absoluteFile
        while (d != null) { if (File(d, "ab_cloud-libs-shared").isDirectory) return d; d = d.parentFile }
        return null
    }

    @Test fun theRealTreeIsClean() {
        val r = root(); assumeTrue(r != null)
        val skip = setOf("build", ".git", "z_archive", "node_modules", "test", "androidTest")
        val files = r!!.walkTopDown().onEnter { it.name !in skip }
            .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path }
            // The Store's own code: the shared appstore and updater libs and the Store app (other libs have engines of their own).
            .filter { "/libs/appstore/" in it.path || "/libs/updater/" in it.path || "/ac_cloud-store/app/" in it.path }
            .associate { it.path to it.readText() }
        assertTrue("scanned the UI sources", files.keys.any { it.endsWith("/StoreCloudFragment.kt") } && files.keys.any { it.endsWith("/dl/DownloadEngine.kt") })
        assertEquals(emptyList<String>(), violations(files))
    }

    @Test fun theTabSwitchEffectAsksAndNeverTouchesTheEngine() {
        val r = root(); assumeTrue(r != null)
        val f = r!!.walkTopDown().first { it.name == "StoreCloudFragment.kt" && "/appstore/" in it.path }
        val c = code(f.readText())
        val tab = block(c, "LaunchedEffect(tab)").first()
        assertTrue(tab.contains("checkAll(ctx, rows, force = false)"))
        assertTrue("a tab switch never mints or calls the engine", !tab.contains("StoreEngine") && !tab.contains("UserAction") && !verbs.containsMatchIn(tab))
        val resume = block(c, "override fun onResume").first()
        assertTrue(!resume.contains("UserAction") && !resume.contains("StoreEngine"))
    }

    @Test fun theEngineBacksOffByTheFloorAndNeverSleepsInALoop() {
        val r = root(); assumeTrue(r != null)
        val e = code(r!!.walkTopDown().first { it.name == "DownloadEngine.kt" }.readText())
        assertTrue("the default backoff is #1502's RetryBackoff", e.contains("RetryBackoff.afterFailures"))
        assertTrue("no sleeping retry loop: a retry is a scheduled return to the queue", !e.contains("Thread.sleep"))
        assertTrue(com.diegonmarcos.superapp.devtools.RetryBackoff.afterFailures(1) >= 30_000L)
    }

    // ── the guard proven red on mutations ────────────────────────────────────────────

    private val ui = "/x/appstore/StoreCloudFragment.kt"

    @Test fun mutationAUiFileMintingAUserActionIsCaught() {
        val bad = violations(mapOf(ui to "class F { fun x() { StoreEngine.get(c).cancelAll(UserAction.tap(\"a\")) } }"))
        assertTrue(bad.toString(), bad.any { it.startsWith("G1") } && bad.any { it.startsWith("G2") })
    }

    @Test fun mutationALifecycleCallbackStoppingAJobIsCaughtEvenInAnAllowedFile() {
        val bad = violations(mapOf("/x/appstore/DownloadsPanel.kt" to "fun P() { DisposableEffect(Unit) { onDispose { engine.cancel(id, UserAction.tap(\"x\")) } } }"))
        assertTrue(bad.toString(), bad.any { it.startsWith("G3") })
        val bad2 = violations(mapOf(ui to "override fun onResume() { StoreEngine.engineOrNull()?.pause(id, u) }"))
        assertTrue(bad2.toString(), bad2.any { it.startsWith("G3") })
    }

    @Test fun mutationTheEngineImportingAScreenOrTheGlobalCancelFlagIsCaught() {
        assertTrue(violations(mapOf("/x/appstore/dl/DownloadEngine.kt" to "import androidx.fragment.app.Fragment\nclass E")).any { it.startsWith("G4") })
        assertTrue(violations(mapOf("/x/appstore/dl/DownloadEngine.kt" to "class E { val s = { UpdateProgress.cancelRequested } }")).any { it.startsWith("G5") })
        assertTrue(violations(mapOf("/x/appstore/StoreEngine.kt" to "object StoreChecks { fun f() = StoreEngine.get(c) }")).any { it.startsWith("G6") })
    }

    @Test fun cleanSourcesPass() {
        val ok = violations(mapOf(
            "/x/appstore/DownloadsPanel.kt" to "fun P() { Button({ e.cancel(id, UserAction.tap(\"c\")) }) ; LaunchedEffect(Unit) { engine = StoreEngine.get(app) } }",
            ui to "override fun onResume() { reload() }\n// engine.cancel( in a comment is prose\nfun other() { jobs.cancel(1) }",
            "/x/appstore/dl/DownloadEngine.kt" to "import java.io.File\nclass E",
            "/x/appstore/StoreEngine.kt" to "object StoreEngine { fun f() {} }\nobject StoreChecks { fun f() = 1 }",
        ))
        assertEquals(emptyList<String>(), ok)
    }
}
