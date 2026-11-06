package com.diegonmarcos.superapp.launcher

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The source tree mirrors the declared page hierarchy (build.json ui.sections -> ui.page_packages), and the two cannot
 * drift apart silently:
 *
 *   P1 every declared page has an implementation: it is drawn from data (facet / tabs / url / upstream / action / mode / hub_sections (#1545, #1548: verified routed and non-empty) /
 *      webapps_base), delegated to a lib (mail, chat), routed by a branch in launcher/SectionPages.kt, or it says so with
 *      `placeholder: true`. A page with none of these would open the "Coming soon" stub unannounced.
 *   P2 a `placeholder: true` page really has no implementation (the flag goes stale the day one lands).
 *   P3 every page SectionPages.kt routes is declared (an implementation for an undeclared page is stale code).
 *   P4 every routed page class that lives in this app sits in one of its section's declared packages.
 *   P5 every Fragment in a package dedicated to one section (its only package) is reached from SectionPages.kt or AggregatorPanels.kt
 *      (no orphaned page class).
 *   P6 ui.page_packages names real sections and real source folders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PageImplementationsGuardTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "aa_cloud-superapp/app/src/main/AndroidManifest.xml").exists() }
    private val app = File(root, "aa_cloud-superapp")
    private val main = File(app, "app/src/main/java/com/diegonmarcos/superapp")
    private val ui = JSONObject(File(app, "build.json").readText()).getJSONObject("ui")
    private val routing = File(main, "launcher/SectionPages.kt").readText()
    private val panels = File(main, "launcher/AggregatorPanels.kt").readText()

    private val delegatedSections = setOf("mail", "chat", "apptabs", "recentapps")
    private val dataKeys = listOf("facet", "url", "upstream", "action", "is_action", "mode", "webapps_base")

    private data class Decl(val section: String, val page: String, val o: JSONObject)

    private fun declared(): List<Decl> = buildList {
        val secs = ui.getJSONArray("sections")
        for (i in 0 until secs.length()) {
            val s = secs.getJSONObject(i)
            val pages = s.optJSONArray("pages") ?: continue
            for (k in 0 until pages.length()) add(Decl(s.getString("id"), pages.getJSONObject(k).getString("id"), pages.getJSONObject(k)))
        }
    }

    /** (section, page) pairs routed by `sectionId == "s" && pageId == "p" -> <class>` with the class named on the branch. */
    private fun routed(): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        val re = Regex("""sectionId == "([a-z0-9]+)"\s*&&\s*\(?pageId == "([a-z0-9-]+)"[^\n]*?->\s*(?=([^\n]*(?:\n[^\n]*)?))""")
        for (m in re.findAll(routing)) out += Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        return out
    }

    private fun hasData(d: Decl) =
        dataKeys.any { d.o.has(it) && d.o.opt(it).toString().let { v -> v.isNotBlank() && v != "false" } } ||
            (d.o.optJSONArray("tabs")?.length() ?: 0) > 0 ||
            (hubSectionsOf(d).isNotEmpty() && hubRouted && hubSectionsHavePages(d))

    /** `hub_sections` of a page (#1545, #1548): the subgroups a hub page shows, one titled grid section each. */
    private fun hubSectionsOf(d: Decl): List<String> =
        d.o.optJSONArray("hub_sections")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    /** A hub page is implemented only while SectionPages really routes the key to SubgroupHubFragment... */
    private val hubRouted = Regex("""hubSections\.isNotEmpty\(\)\s*->\s*SubgroupHubFragment\.newInstance""").containsMatchIn(routing)

    /** ...and EVERY subgroup it lists holds at least one other visible page, so no section it draws is empty. */
    private fun hubSectionsHavePages(d: Decl): Boolean = hubSectionsOf(d).all { sub ->
        declared().any { it.section == d.section && it.page != d.page && it.o.optString("subgroup") == sub && !it.o.optBoolean("hidden") }
    }

    @Test fun `P1 every declared page is implemented, data-drawn, lib-hosted or declared a placeholder`() {
        val routedPairs = routed().map { it.first to it.second }.toSet()
        val missing = declared().filter { d ->
            d.section !in delegatedSections && !hasData(d) && (d.section to d.page) !in routedPairs && !d.o.optBoolean("placeholder")
        }.map { "${it.section}/${it.page}" }
        assertTrue("declared pages with no implementation (implement them or declare placeholder:true): $missing", missing.isEmpty())
    }

    @Test fun `P2 a placeholder page has no implementation`() {
        val routedPairs = routed().map { it.first to it.second }.toSet()
        val stale = declared().filter { it.o.optBoolean("placeholder") && ((it.section to it.page) in routedPairs || hasData(it)) }
            .map { "${it.section}/${it.page}" }
        assertTrue("placeholder:true on pages that have an implementation: $stale", stale.isEmpty())
    }

    @Test fun `P3 every routed page is declared`() {
        val ids = declared().map { it.section to it.page }.toSet()
        val stale = routed().filter { (it.first to it.second) !in ids }.map { "${it.first}/${it.second}" }
        assertTrue("SectionPages.kt routes pages no section declares (stale): $stale", stale.isEmpty())
        assertTrue("the guard parsed no routes", routed().size >= 20)
    }

    private fun packagesOf(section: String): List<String> {
        val a = ui.getJSONObject("page_packages").optJSONArray(section) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun fileFor(simple: String): File? =
        main.walkTopDown().firstOrNull { it.isFile && it.name == "$simple.kt" }

    private fun packageOfFile(f: File) = Regex("""^package ([\w.]+)""", RegexOption.MULTILINE).find(f.readText())!!.groupValues[1]

    @Test fun `P4 a routed page class sits in its section's declared packages`() {
        val wrong = mutableListOf<String>()
        for ((section, page, rhs) in routed()) {
            val cls = Regex("""(?:[a-z][a-z0-9_]*\.)*([A-Z][A-Za-z0-9_]*)""").find(rhs)?.groupValues?.get(1) ?: continue
            val f = fileFor(cls) ?: continue            // hosted by a lib module, not this app
            val pkg = packageOfFile(f).removePrefix("com.diegonmarcos.superapp.")
            if (pkg == "launcher") continue             // the generic page shells (tabs strip, web page, placeholder)
            if (pkg !in packagesOf(section)) wrong += "$section/$page -> $cls in '$pkg', expected one of ${packagesOf(section)}"
        }
        assertTrue("page classes outside their section's packages: $wrong", wrong.isEmpty())
    }

    @Test fun `P5 a dedicated section package holds no orphaned Fragment`() {
        val pm = ui.getJSONObject("page_packages")
        val users = pm.keys().asSequence().flatMap { s -> packagesOf(s).map { it to s } }.groupBy({ it.first }, { it.second })
        val orphans = mutableListOf<String>()
        for ((pkg, secs) in users) {
            if (secs.size != 1 || packagesOf(secs[0]).size != 1) continue   // shared feature packages (network, apps, ...) hold more than pages
            val dir = File(main, pkg)
            for (f in dir.listFiles { x -> x.name.endsWith("Fragment.kt") }.orEmpty()) {
                val n = f.name.removeSuffix(".kt")
                if (!Regex("""\b$n\b""").containsMatchIn(routing) && !Regex("""\b$n\b""").containsMatchIn(panels)) orphans += "$pkg/${f.name}"
            }
        }
        assertTrue("Fragments no page routes to: $orphans", orphans.isEmpty())
    }

    @Test fun `P6 page_packages names declared sections and existing folders`() {
        val sections = declared().map { it.section }.toSet() + (0 until ui.getJSONArray("sections").length()).map { ui.getJSONArray("sections").getJSONObject(it).getString("id") }
        val pm = ui.getJSONObject("page_packages")
        for (s in pm.keys()) {
            assertTrue("page_packages names an undeclared section: $s", s in sections)
            for (p in packagesOf(s)) assertTrue("page_packages[$s] names a missing folder: $p", File(main, p).isDirectory)
        }
        assertEquals("the stale Home Apps sheet is back", null, fileFor("AppDrawerSheetFragment"))
    }
}
