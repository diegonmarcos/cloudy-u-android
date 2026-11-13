package com.diegonmarcos.superapp.navbartop2

import com.diegonmarcos.superapp.bottomnav.NavDecl
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1157 guard: EVERY declared nav item of every app on the shared nav bars carries an icon, and every sub-page's icon is one the
 * second row can draw (SecondRowIcons), so a tab can never render without a glyph. Reads each app's own build.json (the repo is
 * checked out whole in CI; a missing app file FAILS, it is never skipped).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubPageIconGuardTest {
    private val apps = listOf(
        "ac_cloud-mail", "ac_cloud-me", "ac_cloud-drive", "ac_cloud-nav", "ac_cloud-account", "ac_cloud-store", "ac_cloud-c3", "ac_cloud-tasks", "ac_cloud-contacts", "ac_cloud-news", "ac_cloud-search", "ac_cloud-browser", "ac_cloud-dialer", "ac_cloud-kg-store", "ac_cloud-notes", "ac_cloud-tools-small-llms", "ac_cloud-gallery",
    )

    /** Apps whose bottom bar draws from the catalog (#1160) but that declare few or no sub-pages: held on their section icons only. */
    private val barOnlyApps = listOf("ac_cloud-calc", "ac_cloud-superintel")

    private fun buildJson(app: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "$app/build.json")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("$app/build.json not found above ${File("").absolutePath}")
    }

    private fun decl(app: String): NavDecl {
        val ui = JSONObject(buildJson(app).readText()).getJSONObject("ui")
        return NavDecl.parse(ui.getJSONArray("sections"), ui.optString("bottom_nav"), ui.optString("default_section"))
    }

    @Test fun `every section and sub-page of every app declares an icon`() {
        for (app in apps) assertEquals("$app: icon-less declarations", emptyList<String>(), decl(app).iconProblems())
    }

    @Test fun `every sub-page icon of every app is one the catalog holds (the second row draws it from there)`() {
        for (app in apps) {
            val unknown = decl(app).sections.flatMap { s -> s.allPages().filter { !SecondRowIcons.has(it.icon) }.map { "${s.id}/${it.id}='${it.icon}'" } }
            assertEquals("$app: sub-pages whose icon SecondRowIcons does not hold", emptyList<String>(), unknown)
        }
    }

    @Test fun `every bottom-bar section icon of every app is in the shared catalog (#1160)`() {
        for (app in apps + barOnlyApps) {
            assertEquals("$app: icon-less declarations", emptyList<String>(), decl(app).iconProblems())
            val unknown = decl(app).sections.filterNot { com.diegonmarcos.superapp.uiicons.UiIcons.has(it.icon) }.map { "${it.id}='${it.icon}'" }
            assertEquals("$app: sections whose icon libs:ui-icons does not hold", emptyList<String>(), unknown)
        }
    }

    @Test fun `the guard sees pages at all (a vacuous pass is a failure)`() {
        for (app in apps) assertTrue("$app declares no pages", decl(app).sections.sumOf { it.allPages().size } > 0)
    }

    @Test fun `Cloud Drive Mounts declares its two sub-pages and Fsync two (#1173, #1491), icons the shared catalog draws`() {
        val d = decl("ac_cloud-drive")
        // #1491: Fsync = Folders, Agents (Projects moved out to the bottom nav, right after Explorer).
        val want = mapOf("fsync" to listOf("folders", "agents"), "mounts" to listOf("rclone", "fuse"))
        for ((section, ids) in want) {
            val s = d.sections.firstOrNull { it.id == section } ?: throw AssertionError("ac_cloud-drive declares no $section section")
            assertEquals("$section pages", ids, s.allPages().map { it.id })
            for (p in s.allPages()) {
                assertTrue("$section/${p.id} icon '${p.icon}' is not in libs:ui-icons", com.diegonmarcos.superapp.uiicons.UiIcons.has(p.icon))
                assertTrue("$section/${p.id} icon '${p.icon}' is not one the second row draws", SecondRowIcons.has(p.icon))
                assertTrue("$section/${p.id} has no label", p.label.isNotBlank())
            }
        }
        assertEquals(listOf("Rclone", "FUSE"), d.sections.first { it.id == "mounts" }.allPages().map { it.label })
        // #1491 Projects is a bottom-nav section of its own, right after Explorer, with no sub-pages and a catalog icon.
        val projects = d.sections.firstOrNull { it.id == "projects" } ?: throw AssertionError("ac_cloud-drive declares no projects section")
        assertTrue("Projects declares no sub-pages", projects.allPages().isEmpty())
        assertTrue("projects icon '${projects.icon}' is not in libs:ui-icons", com.diegonmarcos.superapp.uiicons.UiIcons.has(projects.icon))
        // #1537 Home is deleted; Manage is a bottom-nav page right after Projects, with Overview | Local-Apps | Local-Data.
        assertEquals(listOf("explorer", "projects", "manage", "editor", "git", "fsync", "mounts"), d.bottomSections().map { it.id })
        assertTrue("Home is deleted", d.sections.none { it.id == "home" })
        val manage = d.sections.firstOrNull { it.id == "manage" } ?: throw AssertionError("ac_cloud-drive declares no manage section")
        assertEquals(listOf("overview", "local_apps", "local_data"), manage.allPages().map { it.id })
        assertEquals(listOf("Overview", "Local Apps", "Local-Data"), manage.allPages().map { it.label })
        assertTrue("manage icon '${manage.icon}' is not in libs:ui-icons", com.diegonmarcos.superapp.uiicons.UiIcons.has(manage.icon))
        for (p in manage.allPages()) {
            assertTrue("manage/${p.id} icon '${p.icon}' is not in libs:ui-icons", com.diegonmarcos.superapp.uiicons.UiIcons.has(p.icon))
            assertTrue("manage/${p.id} icon '${p.icon}' is not one the second row draws", SecondRowIcons.has(p.icon))
        }
        assertTrue("the renamed page keeps no rsync section", d.sections.none { it.id == "rsync" })
    }
}
