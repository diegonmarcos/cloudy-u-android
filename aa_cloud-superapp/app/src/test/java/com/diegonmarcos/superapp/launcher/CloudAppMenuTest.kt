package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.appstore.RetiredApps
import com.diegonmarcos.superapp.updater.Fleet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1736/#1739 Cloud ▸ Apps long-press menu: groups and items per app, URL resolution, the retired-app guard.
 *
 *   A1 every Cloud app tile resolves to a menu: Actions (Open first = the tile's own target), Configs, Urls, in order
 *   A2 Configs is exactly Uninstall, Update, App Info, About; Urls is Repo/Release, each with a Copy row
 *   A3 an app's own Actions are the declared ones plus its deep-link tiles (Me ▸ Health..., Keyboard ▸ Writer), never a copy
 *   A4 rule 16: every target a Cloud tile had is still reachable (as its Open or an Action)
 *   U1 repo / release URLs are the fleet row's repo_url / release_url; a missing one is a disabled row with a reason
 *   U2 only http(s) is ever offered
 *   R1 no Cloud tile opens a retired app; the cloud-writer tile is gone
 *   R2 the guard drops a tile whose app is retired, keeps separators and the other children of a folder
 *   G1 (#1739) the menu opens on long-press only; a tap never opens it and launches the app
 *   R3 the retired list is readable at runtime and names Cloud Writer
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CloudAppMenuTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val ext get() = Sections.externalApps()
    private val cloudTiles: List<Sections.AggTile> get() {
        val s = Sections.byId("cloud")!!
        fun flat(l: List<Sections.AggTile>): List<Sections.AggTile> = l.flatMap { listOf(it) + flat(it.children) }
        return flat(s.tileGroups.flatMap { it.tiles }).filter { !it.separator && it.target.isNotBlank() }
    }
    private val fleet get() = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)

    private fun menuOf(target: String, installed: Boolean = true): List<CloudAppMenu.Item> {
        val ref = CloudAppMenu.resolve(target, ext)!!
        val (repo, rel) = CloudAppMenu.urlsOf(fleet, ref.pkg)
        return CloudAppMenu.menu(ref, CloudAppMenu.actionsOf(ref, ext, cloudTiles), installed, repo, rel)
    }

    @Test
    fun `A1 every Cloud app tile has the three groups in order and Open first`() {
        val apps = cloudTiles.filter { CloudAppMenu.resolve(it.target, ext) != null }
        assertTrue("no Cloud app tile resolves: ${cloudTiles.size} tiles", apps.size >= 20)
        for (t in apps) {
            val m = menuOf(t.target)
            assertEquals("${t.target}: group order", listOf("Actions", "Configs", "Urls"),
                m.map { it.group.title }.distinct())
            assertEquals("${t.target}: first row is Open", "Open", m.first().label)
            assertEquals("${t.target}: Open is the tile's own target", t.target, m.first().arg)
            assertEquals(CloudAppMenu.Verb.OPEN, m.first().verb)
        }
    }

    @Test
    fun `A2 Configs and Urls rows`() {
        val m = menuOf("extapp:cloud-calc")
        assertEquals(listOf("Uninstall", "Update", "App Info", "About"), m.filter { it.group == CloudAppMenu.Group.CONFIGS }.map { it.label })
        assertEquals(listOf("Repo URL", "Copy Repo URL", "Release APK URL", "Copy Release APK URL"),
            m.filter { it.group == CloudAppMenu.Group.URLS }.map { it.label })
        assertEquals("com.diegonmarcos.cloudcalc", m.first { it.verb == CloudAppMenu.Verb.UPDATE }.arg)
        val gone = menuOf("extapp:cloud-calc", installed = false)
        assertFalse(gone.first { it.verb == CloudAppMenu.Verb.UNINSTALL }.enabled)
        assertFalse(gone.first { it.verb == CloudAppMenu.Verb.APP_INFO }.enabled)
        assertTrue("Update still installs a missing app", gone.first { it.verb == CloudAppMenu.Verb.UPDATE }.enabled)
        assertTrue(gone.first { it.verb == CloudAppMenu.Verb.OPEN }.enabled)
    }

    @Test
    fun `A3 per-app Actions come from the declarations`() {
        val kb = menuOf("extapp:cloud-keyboard").filter { it.verb == CloudAppMenu.Verb.ACTION }
        assertEquals(listOf("Writer" to "extapp:cloud-keyboard#page:writer"), kb.map { it.label to it.arg })
        val me = menuOf("extapp:cloud-me").filter { it.verb == CloudAppMenu.Verb.ACTION }.map { it.arg }
        assertTrue("Me deep links: $me", "extapp:cloud-me#page:health/home" in me && "extapp:cloud-me#page:tasks/backlogs" in me)
        assertEquals("no duplicate action target", me.distinct(), me)
        assertTrue("an app with no extra actions has only Open", menuOf("extapp:cloud-calc").count { it.group == CloudAppMenu.Group.ACTIONS } == 1)
        // a deep-link tile's own menu does not list itself twice
        val deep = menuOf("extapp:cloud-me#page:health/home")
        assertEquals(1, deep.count { it.arg == "extapp:cloud-me#page:health/home" })
    }

    @Test
    fun `A4 every Cloud tile target is still reachable`() {
        val reach = mutableSetOf<String>()
        for (t in cloudTiles) {
            if (CloudAppMenu.resolve(t.target, ext) == null) reach += t.target
            else menuOf(t.target).filter { it.group == CloudAppMenu.Group.ACTIONS }.forEach { reach += it.arg }
        }
        for (t in cloudTiles) assertTrue("${t.target} unreachable", t.target in reach)
        assertTrue("Writer stays reachable", "extapp:cloud-keyboard#page:writer" in reach)
    }

    @Test
    fun `U1 URLs come from the fleet row`() {
        val app = fleet.first { it.pkg == "com.diegonmarcos.cloudcalc" }
        val (repo, rel) = CloudAppMenu.urlsOf(fleet, app.pkg)
        assertEquals(app.repoUrl, repo)
        assertNotNull(rel); assertTrue(rel!!.startsWith(app.releaseUrl.substringBeforeLast('/')))
        val m = menuOf("extapp:cloud-calc")
        assertEquals(repo, m.first { it.label == "Repo URL" }.arg)
        assertEquals(rel, m.first { it.label == "Release APK URL" }.arg)
        assertEquals(repo, m.first { it.label == "Copy Repo URL" }.arg)
        assertEquals(null to null, CloudAppMenu.urlsOf(fleet, "com.nobody.nothing"))
        val none = CloudAppMenu.menu(CloudAppMenu.Ref("x", "p", "extapp:x"), emptyList(), true, null, null)
        for (r in none.filter { it.group == CloudAppMenu.Group.URLS }) {
            assertFalse(r.enabled); assertTrue(r.note.isNotEmpty())
        }
        for (a in fleet.filter { it.pkg.isNotBlank() }) {
            val (r, l) = CloudAppMenu.urlsOf(fleet, a.pkg)
            assertEquals("${a.id} repo", CloudAppMenu.webUrl(a.repoUrl), r)
            assertTrue("${a.id} release", l == null || l.startsWith("https://"))
        }
    }

    @Test
    fun `G1 long-press opens the menu and a tap does not`() {
        assertTrue(CloudAppMenu.opensMenu(longPress = true, hasMenu = true))
        assertFalse("a plain tap launches the app", CloudAppMenu.opensMenu(longPress = false, hasMenu = true))
        assertFalse("a non-app tile has no menu", CloudAppMenu.opensMenu(longPress = true, hasMenu = false))
        assertEquals("Open (the tap target) stays first in the menu", CloudAppMenu.Verb.OPEN, menuOf("extapp:cloud-calc").first().verb)
    }

    @Test
    fun `U2 only web URLs`() {
        assertNull(CloudAppMenu.webUrl(null)); assertNull(CloudAppMenu.webUrl("")); assertNull(CloudAppMenu.webUrl("javascript:alert(1)"))
        assertNull(CloudAppMenu.webUrl("file:///x")); assertEquals("https://a.b/c", CloudAppMenu.webUrl(" https://a.b/c "))
    }

    @Test
    fun `R1 no Cloud tile opens a retired app`() {
        val retired = RetiredApps.load(ctx).map { it.pkg }.toSet()
        assertTrue(retired.isNotEmpty())
        for (t in cloudTiles) assertTrue("${t.target} opens a retired app", CloudAppMenu.packagesOf(t.target, ext).none { it in retired })
        for (e in ext) assertTrue("external_apps ${e.id} is a retired app", setOf(e.hubPackage, e.installPackage, e.altPackage).none { it in retired })
        assertTrue("the Writer tile is gone", cloudTiles.none { it.id == "writer" || it.label == "Writer" })
        assertTrue("no fleet row is retired", fleet.none { it.pkg in retired })
        assertTrue(Sections.byId("cloud")!!.tileGroups.flatMap { it.tiles }.none { it.target.contains("cloudwriter") || it.target.contains("cloud-writer") })
    }

    @Test
    fun `R2 the guard drops a retired app and nothing else`() {
        val e = listOf(Sections.ExternalApp("cloud-writer", "W", "com.x.writer", "", "", "com.x.writer", emptyMap()),
            Sections.ExternalApp("cloud-ok", "O", "com.x.ok", "", "", "com.x.ok", emptyMap()))
        fun t(id: String, target: String, sep: Boolean = false, kids: List<Sections.AggTile> = emptyList()) =
            Sections.AggTile(id, id, "ic", target, separator = sep, children = kids)
        val tiles = listOf(t("w", "extapp:cloud-writer"), t("s", "", sep = true), t("o", "extapp:cloud-ok"), t("p", "page:a/b"),
            t("f", "", kids = listOf(t("w2", "app://com.x.writer"), t("o2", "extapp:cloud-ok"))))
        val out = CloudAppMenu.dropRetired(tiles, e, setOf("com.x.writer"))
        assertEquals(listOf("s", "o", "p", "f"), out.map { it.id })
        assertEquals(listOf("o2"), out.last().children.map { it.id })
        assertEquals(tiles, CloudAppMenu.dropRetired(tiles, e, emptySet()))
    }

    @Test
    fun `R3 the retired list is readable at runtime`() {
        assertTrue(RetiredApps.load(ctx).any { it.pkg == "com.diegonmarcos.cloudwriter" })
    }
}
