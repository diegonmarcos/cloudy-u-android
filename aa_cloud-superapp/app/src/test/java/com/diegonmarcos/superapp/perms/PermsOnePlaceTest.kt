package com.diegonmarcos.superapp.perms

import com.diegonmarcos.superapp.wifi.ScanThrottle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1274 the Permissions page is the ONE place that asks: no lib of ab_cloud-libs-shared/libs (all of them, only libs:perms
 * allowlisted) requests a permission, starts a permission settings page or writes a pm grant line itself; the feature
 * banners go through PermsLink, every app that embeds such a lib hosts the page (PermsHost or libs:perms PermsActivity),
 * the page is in the nav walk, and Wi-Fi Auto mode stays inside Android's scan budget.
 */
class PermsOnePlaceTest {
    private val root = File("../..")
    private val libs = File(root, "ab_cloud-libs-shared/libs")
    private val catalogue = JSONObject(File(root, "fleet-private-data/perms-catalogue.json").readText())
    private val app = File(root, "aa_cloud-superapp")

    private val forbidden = listOf(
        "RequestMultiplePermissions", "ActivityResultContracts.RequestPermission", "requestPermissions(",
        "ACTION_APPLICATION_DETAILS_SETTINGS", "APPLICATION_DETAILS_SETTINGS", "MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
        "MANAGE_ALL_FILES_ACCESS_PERMISSION", "ACTION_MANAGE_OVERLAY_PERMISSION", "MANAGE_OVERLAY_PERMISSION",
        "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "IGNORE_BATTERY_OPTIMIZATION_SETTINGS", "USAGE_ACCESS_SETTINGS",
        "NOTIFICATION_LISTENER_SETTINGS", "ACTION_ACCESSIBILITY_SETTINGS", "MANAGE_WRITE_SETTINGS", "MANAGE_UNKNOWN_APP_SOURCES",
        "REQUEST_SCHEDULE_EXACT_ALARM", "NOTIFICATION_POLICY_ACCESS_SETTINGS", "pm grant", "ACTION_ADD_DEVICE_ADMIN",
    )
    private val comments = Regex("//[^\\n]*|/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

    /** The forbidden calls in one source text, comments stripped. */
    private fun violations(text: String): List<String> { val code = comments.replace(text, ""); return forbidden.filter { code.contains(it) } }

    private fun allowlist(): Set<String> = catalogue.getJSONArray("contract_allowlist").let { a -> (0 until a.length()).map { a.getString(it) } }.toSet()

    /** Every Gradle lib of the shared tree (a directory with a build.gradle), the allowlist (libs:perms) left out. */
    private fun contractLibs(): List<String> =
        libs.listFiles().orEmpty().filter { it.isDirectory && (File(it, "build.gradle").isFile || File(it, "build.gradle.kts").isFile) }
            .map { it.name }.filter { it !in allowlist() }.sorted()

    @Test fun `no lib asks for a permission or opens a permission settings page`() {
        assertEquals("only libs:perms may ask", setOf("perms"), allowlist())
        val libsList = contractLibs()
        assertTrue("the guard lost the fleet: ${libsList.size} libs", libsList.size > 60)
        assertTrue(libsList.containsAll(listOf("wifi", "bluetooth", "nfc", "mounts", "light", "battery", "browser", "maps", "kde-connect",
            "appstore", "firewall", "voice", "disk", "datamanager", "keyboard", "account", "shizuku-adb-debug-tools")))
        val hits = ArrayList<String>()
        var scanned = 0
        for (l in libsList) {
            val dir = File(libs, "$l/src/main"); if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { f ->
                scanned++
                violations(f.readText()).forEach { hits += "${f.relativeTo(libs)}: $it" }
            }
        }
        assertTrue("guard scanned nothing", scanned > 1000)
        assertEquals("libs must go through PermsNeeds + PermsLink (libs:perms owns asking)", emptyList<String>(), hits)
    }

    /** The libs migrated off their own asking (#1274): each opens the page on its catalogue feature, which declares entries. */
    @Test fun `each migrated lib declares its needs and opens the Permissions page`() {
        val features = HashSet<String>()
        val entries = catalogue.getJSONArray("entries")
        for (i in 0 until entries.length()) entries.getJSONObject(i).getJSONArray("features").let { a -> (0 until a.length()).forEach { features += a.getString(it) } }
        for (lib in listOf("browser", "maps", "kde-connect", "appstore", "firewall", "voice", "disk", "datamanager", "keyboard")) {
            assertTrue("$lib: no catalogue entry declares it", lib in features)
            val src = File(libs, "$lib/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
            assertTrue("$lib never opens the Permissions page", src.contains("PermsLink.open("))
            assertTrue("$lib does not name its feature \"$lib\"", src.contains("\"$lib\""))
            assertTrue("$lib does not compile libs:perms", File(libs, "$lib/build.gradle").readText().contains("':libs:perms'"))
        }
        assertFalse("the voice bouncer activity is back", File(libs, "voice/src/main/java/com/diegonmarcos/superapp/voice/VoicePermissionActivity.kt").exists())
    }

    /** Permissions are per package: an app without a PermsHost page gets libs:perms' PermsActivity, so it must embed libs:perms. */
    @Test fun `every app that embeds a perms lib hosts the Permissions page`() {
        val manifest = File(libs, "perms/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".PermsActivity\"") && manifest.contains("android:exported=\"false\""))
        val perms = File(libs, "perms/src/main/java/com/diegonmarcos/superapp/perms/Perms.kt").readText()
        assertTrue("PermsLink no longer falls back to the lib's page", perms.contains("ctx.startActivity(PermsActivity.intent(ctx, feature, ids))"))
        val needsPerms = libs.listFiles().orEmpty().filter { File(it, "build.gradle").isFile && File(it, "build.gradle").readText().contains("':libs:perms'") }
            .map { "libs:${it.name}" }.toSet()
        assertTrue(needsPerms.containsAll(listOf("libs:wifi", "libs:browser", "libs:maps", "libs:voice", "libs:disk", "libs:appstore")))
        val missing = ArrayList<String>()
        var apps = 0
        // Every Gradle root (an app, keyboard-engines, ...): its settings.gradle includes plus its build.json modules.
        val include = Regex("['\"]:libs:([\\w-]+)['\"]")
        root.walkTopDown().maxDepth(3).filter { it.name == "settings.gradle" || it.name == "settings.gradle.kts" }.forEach { sg ->
            val dir = sg.parentFile
            val text = sg.readText()
            // build.json modules count only where settings.gradle builds its graph from them (a hardcoded root ignores them).
            val modules = File(dir, "build.json").takeIf { it.isFile && text.contains(".modules") }
                ?.let { runCatching { JSONObject(it.readText()).optJSONObject("modules") }.getOrNull() }
            val libsIn = include.findAll(text).map { "libs:" + it.groupValues[1] }.toSet() +
                (modules?.keys()?.asSequence()?.toSet() ?: emptySet())
            val embeds = libsIn.filter { it in needsPerms }
            if (embeds.isEmpty()) return@forEach
            apps++
            if ("libs:perms" !in libsIn) missing += "${dir.relativeTo(root)} embeds $embeds without libs:perms"
        }
        assertTrue("found no app embedding a perms lib", apps >= 10)
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun `the guard sees each kind of violation (planted)`() {
        for (f in forbidden) assertEquals(f, listOf(f).take(1), violations("fun x() { val a = \"$f\" }").filter { it == f }.take(1))
        assertTrue(violations("// requestPermissions( in a comment\n/* pm grant */").isEmpty())
        assertFalse(violations("ask.launch(x); rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions())").isEmpty())
    }

    @Test fun `wifi and bluetooth show the banner that deep-links to the Permissions page`() {
        val wifi = File(libs, "wifi/src/main/java/com/diegonmarcos/superapp/wifi/WifiUi.kt").readText()
        assertTrue(wifi.contains("PermsNeeds.missing(ctx, \"wifi\")") && wifi.contains("PermsLink.open(") && wifi.contains("wifi:perms-banner"))
        assertFalse(wifi.contains("wifi:permissions"))
        val bt = File(libs, "bluetooth/src/main/java/com/diegonmarcos/superapp/bluetooth/BluetoothUi.kt").readText()
        assertTrue(bt.contains("PermsLink.open(") && bt.contains("bluetooth:perms-banner"))
    }

    @Test fun `the shell hosts the page and the nav walk reaches it`() {
        val shell = File(app, "app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt").readText()
        assertTrue(shell.contains("perms.PermsHost") && shell.contains("override fun openPermsPage(): Boolean { openSectionPage(\"config\", \"perms\")"))
        val pages = File(app, "app/src/main/java/com/diegonmarcos/superapp/launcher/SectionPages.kt").readText()
        assertTrue(Regex("pageId == \"perms\"\\s*->\\s*\\n?\\s*com.diegonmarcos.superapp.configs.PermissionsFragment").containsMatchIn(pages))
        // The nav walk drives every ui.sections[].pages id of build.json (cloud-android-nav-walk.py pages_of).
        val ui = JSONObject(File(app, "build.json").readText()).getJSONObject("ui")
        fun has(o: Any?): Boolean = when (o) {
            is JSONObject -> o.keys().asSequence().any { k -> (k == "pages" && o.get(k).toString().contains("\"perms\"")) || has(o.get(k)) }
            is org.json.JSONArray -> (0 until o.length()).any { has(o.get(it)) }
            else -> false
        }
        assertTrue("config/perms is not a declared page, the nav walk would skip it", has(ui.opt("sections")))
        val frag = File(app, "app/src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt").readText()
        assertTrue(frag.contains("CatalogueHead()") && frag.contains("PermsLink.consume()") && frag.contains("AutoRequestPlanner.batches"))
    }

    // ---- Wi-Fi scan budget ----

    @Test fun `auto mode never drains the 4-per-2-minute budget`() {
        val t = ScanThrottle(4, 120_000)
        var last = 0L; var scans = 0
        for (now in 0L until 600_000L step 1_000L) {
            if (t.autoDue(now, last, 5_000)) { t.record(now); last = now; scans++ }
            assertTrue("auto left no scan for the button at $now", t.remaining(now) >= 1)
        }
        assertTrue(scans in 15..20)   // one slot every 30 s, minus the reserve
    }

    @Test fun `a refused scan is not counted and backs off one slot`() {
        val t = ScanThrottle(4, 120_000)
        t.refuse(1_000)
        assertEquals(4, t.remaining(1_000))
        assertFalse(t.canScan(20_000)); assertTrue(t.canScan(31_000))
        assertEquals(31_000, t.nextAllowedAt(2_000))
    }

    @Test fun `budget 0 blocks every scan until the window frees a slot`() {
        val t = ScanThrottle(4, 120_000)
        for (i in 0 until 4) t.record(i * 1_000L)
        assertFalse(t.canScan(10_000)); assertFalse(t.autoDue(10_000, 0, 5_000))
        assertTrue(t.canScan(120_000))
        val ctl = File(libs, "wifi/src/main/java/com/diegonmarcos/superapp/wifi/WifiController.kt").readText()
        assertTrue(ctl.contains("throttle.autoDue(") && ctl.contains("if (ok) throttle.record(now) else throttle.refuse(now)"))
        assertTrue("no startScan while a permission is missing", ctl.indexOf("s.missing.isNotEmpty() || !s.locationOn || !s.enabled") in 1 until ctl.indexOf("src.startScan()"))
    }
}
