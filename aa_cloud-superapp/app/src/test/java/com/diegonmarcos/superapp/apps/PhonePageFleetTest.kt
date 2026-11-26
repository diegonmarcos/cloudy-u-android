package com.diegonmarcos.superapp.apps

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.apps.SuitePhoneAppsFragment.Entry
import com.diegonmarcos.superapp.apps.SuitePhoneAppsFragment.Folder
import com.diegonmarcos.superapp.apps.SuitePhoneAppsFragment.Group
import com.diegonmarcos.superapp.launcher.Sections
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1936 Phone ▸ Apps shows no Cloud app, in any section. (Replaces the #1864 test that pinned the opposite rule, "a folder
 * opens with its Cloud app first": the owner's decision is that the Cloud entry leaves the folder.)
 *   - guard: every section's list, fed a fake package list holding fleet packages (hub, fork, lib APK, variant, the SuperApp),
 *     renders none; mutations (predicate removed from a section, a fleet package put in a folder) turn it red;
 *   - parity: every third-party entry, group and folder of the page before (test/data/phone-page-before-1936.json) is still
 *     there in the same order;
 *   - reachability: every Cloud package taken off the page is listed by Cloud ▸ Apps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhonePageFleetTest {
    private val ctx: Application get() = ApplicationProvider.getApplicationContext()
    private val fleet = PhonePageFleet.packages(
        "com.diegonmarcos.superapp",
        listOf("com.diegonmarcos.cloudcanvas", "cld.camera", "cld.termux", "cld.termux.nix"),
        listOf("com.diegonmarcos.lib.engine", "io.element.android.x"),
    )
    private val ours = listOf("com.diegonmarcos.cloudcanvas", "cld.termux", "cld.termux.nix", "com.diegonmarcos.lib.engine",
        "io.element.android.x", "com.diegonmarcos.superapp", "com.diegonmarcos.cloudcanvas.debug")
    private fun e(p: String) = Entry(p, p)

    /** A fake page: every shape a fleet package can arrive in. */
    private val fake = listOf(
        Group("Primary", listOf(e("org.third.a"), e("cld.termux"), e("org.third.b")), listOf(
            Folder("Canvas", listOf(e("com.diegonmarcos.cloudcanvas"), e("com.adsk.sketchbook"), e("com.diegonmarcos.cloudcanvas.debug"), e("org.third.c")), "com.diegonmarcos.cloudcanvas"),
            Folder("Terminal", listOf(e("cld.termux.nix"), e("cld.termux")), "cld.termux.nix"),
            Folder("Social", emptyList(), opensFolder = "media_social"),
            Folder("Msg", listOf(e("io.element.android.x"), e("org.third.d")), "io.element.android.x"),
        )),
        Group("Lib", listOf(e("com.diegonmarcos.lib.engine"), e("com.diegonmarcos.superapp")), emptyList()),
    )

    @Test fun `every section renders no fleet package from a fake list full of them`() {
        val page = PhonePageFleet.groups(fake, fleet)
        assertEquals(emptyList<String>(), PhonePageFleet.leaks(PhonePageFleet.renderedPackages(page), fleet))
        // enumerated sections (Active Apps, Last Apps) and the enumerators' exclusion set
        assertEquals(emptyList<String>(), PhonePageFleet.leaks(PhonePageFleet.apps(ours + "org.third.a", fleet), fleet))
        assertEquals(listOf("org.third.a"), PhonePageFleet.apps(ours + "org.third.a", fleet))
        for (p in ours) assertTrue("$p is not excluded from All Apps / Smart Folders", PhonePageFleet.isFleet(p, PhonePageFleet.exclude(fleet)))
        // search results: the same predicate
        assertEquals(listOf("org.third.a"), listOf("org.third.a") + ours.filter { !PhonePageFleet.isFleet(it, fleet) })
    }

    @Test fun `third party entries keep their order and a folder with no phone app is gone`() {
        val page = PhonePageFleet.groups(fake, fleet)
        assertEquals(listOf("org.third.a", "org.third.b"), page[0].entries.map { it.pkg })
        assertEquals(listOf("Canvas", "Social", "Msg"), page[0].folders.map { it.label })   // Terminal held only Cloud apps
        assertEquals(listOf("com.adsk.sketchbook", "org.third.c"), page[0].folders[0].entries.map { it.pkg })
        assertEquals(listOf("org.third.d"), page[0].folders[2].entries.map { it.pkg })
        assertTrue("a folder that opens an All Apps folder stays", page[0].folders[1].opensFolder.isNotEmpty())
    }

    @Test fun `mutation - a fleet package in a folder, or a removed predicate, is red`() {
        val clean = PhonePageFleet.renderedPackages(PhonePageFleet.groups(fake, fleet))
        assertTrue(PhonePageFleet.leaks(clean + "cld.termux.nix", fleet).isNotEmpty())                       // added to a folder
        val noPredicate = PhonePageFleet.renderedPackages(fake)                                                // predicate removed
        assertTrue(PhonePageFleet.leaks(noPredicate, fleet).size >= 6)
        assertTrue(PhonePageFleet.leaks(listOf("com.diegonmarcos.cloudcanvas.debug"), fleet).isNotEmpty()) // a variant
        assertTrue(PhonePageFleet.leaks(listOf("com.diegonmarcos.lib.engine"), fleet).isNotEmpty())        // a lib APK
    }

    @Test fun `every section of the page takes its list from PhonePageFleet, source guard with mutations`() {
        val src = File(System.getProperty("user.dir"), "src/main/java/com/diegonmarcos/superapp/apps/SuitePhoneAppsFragment.kt").readText()
        val search = File(System.getProperty("user.dir"), "src/main/java/com/diegonmarcos/superapp/search/SuperappSearchIndex.kt").readText()
        assertEquals(emptyList<String>(), sectionViolations(src, search))
        val mutations = mapOf(
            "Quickmarks unfiltered" to (src.replace("PhonePageFleet.groups(Quickmarks.apply(SuitePhoneAppsFragment.parseGroups(), Quickmarks.load(ctx)), ourApps)", "Quickmarks.apply(SuitePhoneAppsFragment.parseGroups(), Quickmarks.load(ctx))") to search),
            "All Apps unfiltered" to (src.replace("allApps(ctx, exclude = PhonePageFleet.exclude(ourApps))", "allApps(ctx)") to search),
            "Smart Folders unfiltered" to (src.replace("loadSmartFolders(ctx, PhonePageFleet.exclude(ourApps))", "loadSmartFolders(ctx, emptySet())") to search),
            "Active Apps unfiltered" to (src.replace("PhonePageFleet.apps(activeLoaded?.packages.orEmpty(), ourApps)", "activeLoaded?.packages.orEmpty()") to search),
            "Last Apps unfiltered" to (src.replace("PhonePageFleet.apps(lastLoaded, ourApps)", "lastLoaded") to search),
            "Active enumerator exclusion dropped" to (src.replace("RunningApps.load(ctx, PhonePageFleet.exclude(ourApps))", "RunningApps.load(ctx, emptySet())") to search),
            "Last enumerator exclusion dropped" to (src.replace("RunningApps.loadLastOpened(ctx, PhonePageFleet.exclude(ourApps))", "RunningApps.loadLastOpened(ctx, emptySet())") to search),
            "Cloud app prepended to folders again" to (src.replace("folder to folder.entries.map(::curated)", "folder to (listOf(Entry(folder.iconPkg, folder.label)) + folder.entries).map(::curated)") to search),
            "Cloud app is the folder face again" to (src.replace("contents.firstOrNull { it.installed }, \"\")", "folder.iconPkg.takeIf { it.isNotBlank() }?.let { resolve(it) }, folder.iconRes)") to search),
            "search unfiltered" to (src to search.replace("filterNot { PhonePageFleet.isFleet(it.packageName, fleet) }", "toList()")),
        )
        for ((name, pair) in mutations) {
            assertTrue("mutation '$name' did not change the source", pair.first != src || pair.second != search)
            assertTrue("mutation '$name' stayed green", sectionViolations(pair.first, pair.second).isNotEmpty())
        }
    }

    private fun sectionViolations(src: String, search: String): List<String> = buildList {
        if (!src.contains("PhonePageFleet.groups(Quickmarks.apply(")) add("Quickmarks tiles and folders are not filtered")
        if (!src.contains("allApps(ctx, exclude = PhonePageFleet.exclude(ourApps))")) add("All Apps is not filtered")
        if (!src.contains("loadSmartFolders(ctx, PhonePageFleet.exclude(ourApps))")) add("Smart Folders is not filtered")
        if (!src.contains("PhonePageFleet.apps(activeLoaded?.packages.orEmpty(), ourApps)")) add("Active Apps is not filtered")
        if (!src.contains("PhonePageFleet.apps(lastLoaded, ourApps)")) add("Last Apps is not filtered")
        if (!src.contains("RunningApps.load(ctx, PhonePageFleet.exclude(ourApps))")) add("Active Apps enumerator is not told the fleet")
        if (!src.contains("RunningApps.loadLastOpened(ctx, PhonePageFleet.exclude(ourApps))")) add("Last Apps enumerator is not told the fleet")
        if (src.contains("iconPkg.takeIf")) add("the Cloud app is the folder face")
        if (src.contains("listOf(Entry(folder.iconPkg")) add("the Cloud app is prepended to a folder")
        if (!search.contains("PhonePageFleet.isFleet(it.packageName, fleet)")) add("the Phone search scope is not filtered")
    }

    @Test fun `the real page, every section declared, shows no fleet package and loses no third party entry`() {
        val real = SuitePhoneAppsFragment.parseGroups()
        assertTrue("the real groups did not load", real.isNotEmpty())
        val realFleet = PhonePageFleet.forContext(ctx)
        val page = PhonePageFleet.groups(real, realFleet)
        assertEquals(emptyList<String>(), PhonePageFleet.leaks(PhonePageFleet.renderedPackages(page), realFleet))
        // parity against the before-1936 baseline: group order, folder order, every third-party package in the same order
        val before = JSONObject(File(System.getProperty("user.dir"), "../test/data/phone-page-before-1936.json").readText()).getJSONArray("groups")
        assertEquals((0 until before.length()).map { before.getJSONObject(it).getString("title") }, page.map { it.title })
        for (i in 0 until before.length()) {
            val bg = before.getJSONObject(i); val g = page[i]
            val bf = bg.getJSONArray("folders")
            assertEquals("${g.title}: folders and their order", (0 until bf.length()).map { bf.getJSONObject(it).getString("label") }, g.folders.map { it.label })
            for (k in 0 until bf.length()) {
                val want = bf.getJSONObject(k).getJSONArray("packages").let { a -> (0 until a.length()).map { a.getString(it) } }.filterNot { PhonePageFleet.isFleet(it, realFleet) }
                assertEquals("${g.title}/${g.folders[k].label}: members and their order", want, g.folders[k].entries.map { it.pkg })
            }
            val bp = bg.getJSONArray("packages").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals("${g.title}: loose packages", bp, g.entries.map { it.pkg })
        }
    }

    @Test fun `every Cloud package taken off the page is reachable in Cloud Apps`() {
        val cloudApps = PhonePageFleet.forContext(ctx)   // GroupedTilesFragment lists exactly this set (allApps(only = ...))
        val grouped = File(System.getProperty("user.dir"), "src/main/java/com/diegonmarcos/superapp/launcher/GroupedTilesFragment.kt").readText()
        assertTrue("Cloud Apps no longer lists the page's fleet set", grouped.contains("allApps(ctx, only = com.diegonmarcos.superapp.apps.PhonePageFleet.forContext(ctx))"))
        assertTrue("a Cloud package the page dropped is not in Cloud Apps", Sections.constellationPackages(ctx.packageName).all { it in cloudApps })
        val removed = SuitePhoneAppsFragment.parseGroups().flatMap { g -> g.folders.mapNotNull { f -> f.iconPkg.takeIf { it.isNotBlank() } } }.toSet()
        assertTrue(removed.isNotEmpty())
        for (p in removed) assertTrue("$p left Phone ▸ Apps but Cloud ▸ Apps does not list it", p in cloudApps)
        assertFalse(removed.any { it.isBlank() })
    }
}
