package com.diegonmarcos.clouddrive

import com.diegonmarcos.clouddrive.files.FileOps
import com.diegonmarcos.clouddrive.files.FilesController
import com.diegonmarcos.clouddrive.files.Location
import com.diegonmarcos.clouddrive.files.PaneState
import com.diegonmarcos.clouddrive.files.PaneTab
import com.diegonmarcos.clouddrive.ui.IconCatalog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * #579 the declarations, parsed by the EXACT parser the phone runs, against THIS
 * repository's build.json and data files (the module dir is the working directory of
 * a Gradle unit test, so ../build.json is the app's own). A declared icon name the
 * catalog does not know, a filter with no rule, a tab without an id, a data file that
 * lost its shape — all fail here before they fail on a device. #868 the navigation is
 * libs:bottomnav's NavDecl, parsed from the same ui.sections / ui.bottom_nav the gradle bake
 * reads (org.json needs Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
class DeclarationsTest {

    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val buildJson: JsonObject = Json { ignoreUnknownKeys = true }.parseToJsonElement(File(root, "build.json").readText()).jsonObject
    private val ui: JsonObject = buildJson["ui"]!!.jsonObject
    private fun section(key: String): String = ui[key].toString()
    private fun nav(): NavDecl = NavDecl.parse(
        JSONArray(section("sections")),
        ui["bottom_nav"]!!.jsonArray.joinToString(",") { it.jsonPrimitive.content },
        ui["default_section"]!!.jsonPrimitive.content,
    )
    private fun configs() = Declarations.ConfigsDecl(Declarations.pagesOf(nav(), "configs"))
    private fun sync() = Declarations.parseSync(section("sync"), nav().sections.filter { it.id in Declarations.SYNC_SECTIONS }.map { Declarations.PageDecl(it.id, it.label, it.icon) })
    private fun volumes() = Declarations.parseVolumes(section("volumes"), Declarations.classesOf(nav()))

    @Test fun tabsDeclareTheOwnersNavOrder() {
        val tabs = Declarations.tabsOf(nav())
        // #1484 the owner's bottom nav: Explorer, Editor, Home, Git, Fsync, Mounts. Manage moved into Home and Agents into Fsync; AI and Configs stay on the top bar.
        assertEquals(listOf("explorer", "editor", "home", "git", "fsync", "mounts"), tabs.map { it.id })
        assertEquals(listOf("Explorer", "Editor", "Home", "Git", "Fsync", "Mounts"), tabs.map { it.label })
        assertTrue("Manage and Agents are no sections of their own", nav().section("manage") == null && nav().section("agents") == null)
        assertTrue("AI and Configs are sections the top bar opens, not island items", tabs.none { it.id == "ai" || it.id == "configs" } && nav().section("ai") != null && nav().section("configs") != null)
        assertEquals(emptyList<String>(), nav().problems())
        assertTrue(tabs.all { it.label.isNotBlank() && it.icon.isNotBlank() })
        assertEquals("#1300 the app opens on the Git page", "git", nav().default()?.id)
        assertTrue(tabs.map { it.id }.contains(nav().default()?.id))
    }

    @Test fun everyDeclaredIconIsInTheCatalog() {
        val tabs = Declarations.tabsOf(nav())
        val configs = configs()
        val files = Declarations.parseFiles(section("files"))
        val volumes = volumes()
        val sync = sync()
        val names = Declarations.iconNames(tabs, configs, files, volumes, sync)
        assertTrue(names.size >= 10)
        val unknown = names.filterNot { IconCatalog.knows(it) }
        assertEquals("icons declared but unknown to IconCatalog: $unknown", emptyList<String>(), unknown)
        assertTrue(IconCatalog.knows(ui["icons"]!!.jsonObject["_default"].toString().trim('"')))
        assertFalse(IconCatalog.knows("no-such-glyph"))
    }

    @Test fun configsPages() {
        val configs = configs()
        // #1216 the owner's split: General, Git (auths), Fsync (servers/APIs/SFTPs), Mounts (rclone setup), then Webserver
        // (opens cloud-c3-webserver). The Backups and Others pages are gone.
        assertEquals(listOf("general", "perms", "about", "drive", "git", "fsync", "mounts", "webserver"), configs.pages.map { it.id })
        assertTrue(configs.pages.all { it.label.isNotBlank() && it.icon.isNotBlank() })
    }

    @Test fun webserverAndTopBarDeclarations() {
        val ui = buildJson["ui"]!!.jsonObject
        val ws = ui["webserver"]!!.jsonObject
        assertEquals("c3-webserver", ws["fleet"]!!.jsonPrimitive.content)
        assertTrue(ws["fallback_url"]!!.jsonPrimitive.content.startsWith("http://localhost:"))
        // The bake carries the package resolved from the fleet manifest; it parses to a typed decl, a blank bake to none.
        val baked = Declarations.parseWebserver("""{"package":"com.diegonmarcos.cloudwebserver","activity":".MainActivity","fallback_url":"http://localhost:8002"}""")
        assertEquals(Declarations.WebserverDecl("com.diegonmarcos.cloudwebserver", ".MainActivity", "http://localhost:8002"), baked)
        assertNull(Declarations.parseWebserver(""))
        assertEquals(listOf("account", "configs"), ui["top_bar"]!!.jsonObject["settings"]!!.jsonArray.map { it.jsonPrimitive.content })
        // #1149 hamburger at the left (drawer), AI / Profile / Configs as three inline buttons at the right.
        val bar = com.diegonmarcos.superapp.bottomnav.TopBarDecl.parse(ui["top_bar"].toString())!!
        assertTrue(bar.drawer && bar.inlineActions)
        assertEquals(emptyList<String>(), bar.problems())
    }

    @Test fun mountProvidersAreDeclaredNotCoded() {
        // #1216 Mounts > FUSE and Configs > Mounts: the five providers and the free Others line come from ui.sync.mounts.providers.
        val lines = Declarations.parseMountProviders(section("sync"))
        assertEquals(listOf("r2", "cf-ia", "oracle-s3", "hetzner-s3", "gh-lfs", "others"), lines.map { it.id })
        assertEquals(listOf("Cloudflare R2", "Cloudflare IA", "Oracle S3", "Hetzner S3", "GitHub LFS", "Others"), lines.map { it.label })
        assertEquals("only the last line is the free one", listOf(false, false, false, false, false, true), lines.map { it.others })
        assertTrue("every real line names its rclone remote and backend", lines.filter { !it.others }.all { it.remote.isNotBlank() && it.rcloneType.isNotBlank() })
        assertEquals(listOf("s3", "s3", "s3", "s3", "http"), lines.filter { !it.others }.map { it.rcloneType })
        assertTrue("GitHub LFS is read only", lines.first { it.id == "gh-lfs" }.readOnly)
        assertEquals("STANDARD_IA", lines.first { it.id == "cf-ia" }.storageClass)
        val unknown = lines.map { it.icon }.filter { it.isNotBlank() }.filterNot { IconCatalog.knows(it) }
        assertEquals("provider icons unknown to IconCatalog: $unknown", emptyList<String>(), unknown)
        // no secret in the declaration: the APK is public
        val text = section("sync")
        val mounts = Json.parseToJsonElement(text).jsonObject["mounts"].toString().lowercase()
        listOf("secret", "password", "token", "access_key", "private_key").forEach { assertFalse("mounts block mentions $it", mounts.contains("\"$it")) }
        assertEquals(emptyList<Declarations.MountProviderDecl>(), Declarations.parseMountProviders(""))
        assertEquals(emptyList<String>(), Declarations.parseFsyncKinds(""))
        assertTrue("Configs > Fsync lists declared connection kinds", Declarations.parseFsyncKinds(section("sync")).containsAll(listOf("ssh", "borg")))
    }

    @Test fun syncPagesAndPeriods() {
        val sync = sync()
        // #1149 the former Sync pages are bottom-nav sections: Git (Me, Cloud), Fsync (folder sync rules, #1216), Mounts (Rclone, FUSE) (#1173).
        assertEquals(listOf("git", "fsync", "mounts"), sync.pages.map { it.id })
        assertEquals(listOf("me", "cloud"), nav().section("git")!!.pages.map { it.id })
        assertEquals("#1484 Fsync = Folders (the folder sync rules), Agents, Projects", listOf("folders", "agents", "projects"), nav().section("fsync")!!.pages.map { it.id })
        assertEquals(listOf("rclone", "fuse"), nav().section("mounts")!!.pages.map { it.id })
        assertEquals(listOf("Rclone", "FUSE"), nav().section("mounts")!!.pages.map { it.label })
        assertNull("the old Rsync id is gone, not kept as a second name", nav().section("rsync"))
        assertNull(nav().section("sync"))
        assertTrue(sync.pages.all { it.label.isNotBlank() && it.icon.isNotBlank() })
        assertTrue(sync.gitPeriodsMinutes.isNotEmpty())
        assertTrue("every period is at or above WorkManager's floor", sync.gitPeriodsMinutes.all { it >= 15 })
        assertEquals(sync.gitPeriodsMinutes.sorted(), sync.gitPeriodsMinutes)
    }

    @Test fun cloudListAndGitHostsAreDeclared() {
        val text = section("sync")
        val cloud = com.diegonmarcos.clouddrive.sync.GitHostsDecl.parseCloud(text)
        assertEquals("diegonmarcos", cloud.owner)
        assertEquals("cloud", cloud.namePrefix)
        assertEquals("https://api.github.com/users/diegonmarcos/repos?type=owner&sort=full_name&per_page=100&page=2", cloud.url("https://api.github.com", 2))
        val hosts = com.diegonmarcos.clouddrive.sync.GitHostsDecl.parseHosts(text)
        assertEquals(listOf("gitlab", "bitbucket", "codeberg", "azure"), hosts.map { it.id })
        assertTrue(hosts.all { it.kind in com.diegonmarcos.clouddrive.sync.GitHosts.KINDS && it.apiBase.startsWith("https://") })
        assertEquals("https://dev.azure.com/acme/_apis/git/repositories?api-version=7.1&page=1", hosts.single { it.id == "azure" }.reposUrl("acme", 1))
        assertTrue(hosts.all { IconCatalog.knows(it.icon) })
    }

    @Test fun volumesAreReachableUnderExplorerCloud() {
        val explorer = requireNotNull(nav().section(Declarations.SECTION_EXPLORER))
        assertEquals(listOf("local", "cloud"), explorer.pages.map { it.id })
        assertEquals(listOf("Local", "Cloud"), explorer.pages.map { it.label })
        val cloud = explorer.pages.first { it.id == Declarations.PAGE_CLOUD }
        assertEquals(listOf("constellation", "containers", "machines", "s3"), cloud.pages.map { it.id })
        assertEquals(cloud.pages.map { it.id }, Declarations.classesOf(nav()).map { it.id })
        assertNull("Volumes is no longer a tab of its own", nav().section("volumes"))
    }

    @Test fun homeAndFsyncDeclareTheirSubPages() {
        // #1484 Home = Apps | Manage (Manage keeps its Apps / Data), Fsync = Folders | Agents | Projects, Editor has no strip.
        assertEquals(listOf("home_apps", "manage"), Declarations.navPages(Declarations.SECTION_HOME).map { it.id })
        assertEquals(listOf("Apps", "Manage"), Declarations.navPages(Declarations.SECTION_HOME).map { it.label })
        assertEquals(listOf("apps", "data"), nav().section(Declarations.SECTION_HOME)?.pages?.first { it.id == Declarations.PAGE_MANAGE }?.pages?.map { it.id })
        assertEquals(listOf("folders", "agents", "projects"), nav().section(Declarations.SECTION_FSYNC)?.pages?.map { it.id })
        assertEquals(listOf("Folders", "Agents", "Projects"), nav().section(Declarations.SECTION_FSYNC)?.pages?.map { it.label })
        assertTrue(nav().section(Declarations.SECTION_EDITOR)?.pages.orEmpty().isEmpty())
        assertEquals(emptyList<String>(), nav().problems())
    }

    @Test fun volumesDeclaresTheFourClassesAndTheirRules() {
        val volumes = volumes()
        // #604 the four classes of volume, in declared order.
        assertEquals(listOf("constellation", "containers", "machines", "s3"), volumes.classes.map { it.id })
        assertTrue(volumes.classes.all { it.label.isNotBlank() && it.icon.isNotBlank() })
        assertTrue(volumes.machineKinds.isNotEmpty())
        assertFalse("container is the default, not a machine kind", Declarations.MACHINE_CONTAINER in volumes.machineKinds)
        assertTrue(volumes.s3RemoteTypes.isNotEmpty())
        assertTrue("the fleet-app path carries the package placeholder", volumes.constellationPath.contains("<package>"))
        // The path itself is the declaration's, never restated here (test-drive-shared-store.sh
        // fails the build on a device-absolute path written down in a source file).
        val resolved = volumes.constellationPathOf("com.x")
        assertFalse("the placeholder is substituted", resolved.contains("<package>"))
        assertTrue("the package lands in the path", resolved.contains("com.x"))
        assertEquals("", volumes.constellationPathOf(""))
        // #613 the four classes regroup into two sections, in declared order, nothing lost.
        assertEquals(listOf("constellation", "personal"), volumes.sections.map { it.id })
        assertTrue("Personal Mounts carries the auth affordance", volumes.sections.first { it.id == "personal" }.auth)
        assertFalse("Cloud Constellation carries no auth affordance", volumes.sections.first { it.id == "constellation" }.auth)
        assertEquals(volumes.classes.map { it.id }.toSet(), volumes.sections.flatMap { it.classIds }.toSet())
        assertTrue("every declared class is claimed by a section", volumes.unsectioned().isEmpty())
        // #630 the fleet-app leg is RELATIVE to the store, never the owner-only Android/data tree,
        // and the wall itself is declared so a row can state it instead of offering a dead Open.
        assertFalse("the openable leg must not be an Android/data path", volumes.constellationPath.contains("Android/data"))
        assertFalse("the openable leg is relative to the store", volumes.constellationPath.startsWith("/"))
        val wall = requireNotNull(volumes.ownerOnly) { "ui.volumes.constellation_owner_only is not declared" }
        assertFalse("the owner-only tree must never be declared openable", wall.openable)
        assertTrue("the wall names the tree it is about", wall.path.contains("Android/data"))
        assertTrue("the carve-out landed in API 30", wall.blockedSinceSdk >= 30)
        assertTrue("a stated wall must offer the handoff that works", wall.handoff)
        assertTrue("the wall's path names the app", volumes.ownerOnlyPathOf("com.x").contains("com.x"))
        assertEquals("", volumes.ownerOnlyPathOf(""))
    }

    @Test fun noFolderRuleIsDeclaredAndARuleStillMapsOntoOneRcloneJob() {
        val data = File(root, "data")
        val declared = Declarations.parseSyncRules(Json.parseToJsonElement(File(data, "drive-sync-rules.json").readText()).jsonObject["rules"].toString())
        // #1173 the Fsync page draws real state only: the APK ships no rule (a sample rule would be mock data).
        assertEquals("no declared sync rule: the page shows what the user made", emptyList<Declarations.SyncRuleDecl>(), declared)
        // the direction -> verb mapping stays proven on a rule built here: both one-way legs and the two-way one.
        val first = Declarations.SyncRuleDecl("t1", "/data/local", "remote1", "folder", Declarations.DIRECTION_BIDIRECTIONAL, null, true, "")
        assertEquals("remote1:folder", first.remoteLeg)
        assertEquals("bisync", com.diegonmarcos.clouddrive.sync.SyncRules.job(first).op)
        val up = first.copy(direction = Declarations.DIRECTION_UPLOAD)
        assertEquals("copy", com.diegonmarcos.clouddrive.sync.SyncRules.job(up).op)
        assertEquals(first.localPath, com.diegonmarcos.clouddrive.sync.SyncRules.job(up).source)
        assertEquals(first.remoteLeg, com.diegonmarcos.clouddrive.sync.SyncRules.job(up).destination)
        val down = first.copy(direction = Declarations.DIRECTION_DOWNLOAD)
        assertEquals(first.remoteLeg, com.diegonmarcos.clouddrive.sync.SyncRules.job(down).source)
        assertEquals(first.localPath, com.diegonmarcos.clouddrive.sync.SyncRules.job(down).destination)
    }

    @Test fun filesDeclaresTwoSectionsAndEveryPlaceNamesOne() {
        val files = Declarations.parseFiles(section("files"))
        assertEquals(3, files.sections.size)
        assertTrue("Emulated is a declared section", files.sections.any { it.id == "emulated" })
        assertTrue(files.sections.all { it.label.isNotBlank() && it.icon.isNotBlank() })
        val ids = files.sections.map { it.id }.toSet()
        assertTrue("every place names a declared section", files.places.all { it.section in ids })
        // The #575 shared store is a section of its own, not one place among the emulated ones.
        val store = files.places.single { it.kind == "shared_root" }
        assertTrue(store.section != "emulated" && store.section in ids)
        assertEquals(1, files.places.count { it.section == store.section })
    }

    @Test fun theTerminalFilesystemIsADeclaredRootBesideEmulated() {
        val files = Declarations.parseFiles(section("files"))
        assertTrue(files.sections.any { it.id == "terminal" })
        val terminal = files.places.filter { it.section == "terminal" }
        assertTrue("home is a root", terminal.any { it.kind == "terminal_home" })
        assertTrue("the clones folder is a root", terminal.any { it.kind == "store_path" && it.path == "git" })
        assertTrue("emulated stays", files.places.any { it.section == "emulated" })
    }

    @Test fun filesPlacesFiltersAndSort() {
        val files = Declarations.parseFiles(section("files"))
        assertEquals("the shared store is the first, hero place", "shared_root", files.places.first().kind)
        assertTrue(files.places.first().hero)
        assertEquals(1, files.places.count { it.kind == "shared_root" })
        assertTrue(files.places.all { it.kind in setOf("shared_root", "external_root", "public_dir", "external_path", "terminal_home", "store_path") })
        assertTrue(files.places.filter { it.kind == "public_dir" }.all { it.dir.isNotBlank() })
        assertTrue(files.places.filter { it.kind == "external_path" }.all { it.path.isNotBlank() && !it.path.startsWith("/") })
        assertTrue(files.sortKeys.containsAll(listOf("name", "size", "modified", "type")))
        assertTrue(files.defaultSort in files.sortKeys)
        assertEquals("all", files.filters.first().id)
        files.filters.drop(1).forEach { f -> assertTrue("filter ${f.id} has a rule", f.mime != null || f.mimePrefix != null || f.extensions.isNotEmpty()) }
        assertTrue("zip" in files.archiveExtensions)
        assertTrue(files.tabsPerPaneMax >= 2)
    }

    @Test fun filesDeclaresTheCopyPathRowAction() {
        val files = Declarations.parseFiles(section("files"))
        val a = files.rowAction("copy_path")!!
        assertEquals("Copy path", a.label)
        assertEquals("Path copied", a.snack)
        assertTrue("the snack never carries the value", !a.snack.contains("/") && !a.snack.contains(":"))
        assertTrue(IconCatalog.knows(a.icon))
    }

    @Test fun filterRulesMatchAsDeclared() {
        val files = Declarations.parseFiles(section("files"))
        val images = files.filters.first { it.id == "images" }
        assertTrue(images.matches(false, "image/jpeg", "jpg"))
        assertFalse(images.matches(false, "video/mp4", "mp4"))
        assertTrue("folders always pass", images.matches(true, "inode/directory", ""))
        val docs = files.filters.first { it.id == "documents" }
        assertTrue(docs.matches(false, "application/octet-stream", "PDF"))
        val all = files.filters.first { it.id == "all" }
        assertTrue(all.matches(false, "anything/else", "xyz"))
        val folders = files.filters.first { it.id == "folders" }
        assertTrue(folders.matches(true, "inode/directory", ""))
        assertFalse(folders.matches(false, "text/plain", "txt"))
    }

    @Test fun visibleEntriesApplyHiddenFilterAndSort() {
        val files = Declarations.parseFiles(section("files"))
        fun e(name: String, dir: Boolean = false, mime: String = "text/plain", size: Long = 1) =
            FileOps.Entry(Location.Local("/x/$name"), name, dir, size, 0, if (dir) "inode/directory" else mime, FileOps.extensionOf(name), name.startsWith("."))
        val entries = listOf(e("b.txt", size = 5), e("a.jpg", mime = "image/jpeg", size = 9), e(".secret"), e("dir", dir = true))
        val pane = PaneState(listOf(PaneTab(Location.Local("/x"))), filterId = "images")
        assertEquals(listOf("dir", "a.jpg"), FilesController.visibleEntries(pane, entries, files.filters).map { it.name })
        val all = pane.copy(filterId = "all", showHidden = true, sort = "size", descending = true)
        assertEquals(listOf("dir", "a.jpg", "b.txt", ".secret"), FilesController.visibleEntries(all, entries, files.filters).map { it.name })
    }

    @Test fun dataFilesKeepTheirShape() {
        val data = File(root, "data")
        val apps = Declarations.parseApps(Json.parseToJsonElement(File(data, "drive-apps.json").readText()).jsonObject["apps"].toString())
        assertTrue(apps.size >= 5); assertTrue(apps.all { it.label.isNotBlank() && it.icon.isNotBlank() })
        // #609 GitSync and Fsync (#1173: the old RSync tile) are IN-APP routes into declared Sync sub-pages, never packages.
        val sync = sync()
        val pageIds = sync.pages.map { it.id }
        assertTrue("no RSync tile: the page is Fsync now", apps.none { it.label == "RSync" })
        // #1356 the Git page's terminal icon moved here: one Termux tile that leaves the app (no route).
        assertEquals("the Termux tile routes nowhere", "", apps.single { it.label == "Termux" }.routeTab)
        listOf("GitSync", "Fsync").forEach { label ->
            val tile = apps.single { it.label == label }
            assertEquals("a routed tile carries no package", "", tile.packageName)
            assertTrue("$label routes to a declared Sync section (${tile.routeTab})", tile.routeTab in pageIds)
            val sectionPages = nav().section(tile.routeTab)!!.pages.map { it.id }
            assertTrue("$label routes to a declared sub-page or none (${tile.routePage})", tile.routePage.isBlank() || tile.routePage in sectionPages)
        }
        assertTrue("an ordinary tile has no route", apps.filter { it.routeTab.isBlank() }.size >= 5)
        val connections = Declarations.parseConnections(File(data, "drive-connections.json").readText())
        assertTrue(connections.size >= 10); assertTrue(connections.all { it.status.isNotBlank() })
        val family = Declarations.parseGitFamily(File(data, "drive-git-repos.json").readText())
        assertNotNull(family.upstream)
        assertTrue(family.repos.any { it.name == "cloud-me-pvt-my-ai" && it.private })
        // #603 the store's first-run seed set: marked, public, and never a private repository —
        // an anonymous clone of one fails every time, so seeding it would bake a permanent red.
        val seeded = family.repos.filter { it.seed }
        assertTrue("the store would start empty", seeded.isNotEmpty())
        assertEquals("private repositories must never be seeded", emptyList<String>(), seeded.filter { it.private }.map { it.name })
        assertTrue(family.repos.filter { it.private }.none { it.seed })
        assertEquals("https://github.com/diegonmarcos/cloudy-t-infra.git", family.cloneUrl(family.repos.first { it.name == "cloudy-t-infra" }))
        val mirrors = Declarations.parseMirrorJobs(Json.parseToJsonElement(File(data, "drive-mirror-jobs.json").readText()).jsonObject["jobs"].toString())
        assertTrue(mirrors.isNotEmpty()); assertTrue("declared mirror paths are relative", mirrors.all { !it.source.startsWith("/") && !it.destination.startsWith("/") })
        val remotes = Declarations.parseRemotes(Json.parseToJsonElement(File(data, "drive-remotes.json").readText()).jsonObject["remotes"].toString())
        assertTrue(remotes.any { it.declaredToPhone })
        assertTrue(remotes.filter { it.status == "unreachable" }.all { it.reason.isNotBlank() })
        val jobs = Declarations.parseRcloneJobs(Json.parseToJsonElement(File(data, "drive-rclone-jobs.json").readText()).jsonObject["jobs"].toString())
        assertTrue(jobs.isNotEmpty())
    }

    @Test fun blankAndBrokenInputParseToEmpty() {
        assertTrue(Declarations.tabsOf(NavDecl.fromBuildConfig("")).isEmpty())
        assertTrue(Declarations.tabsOf(NavDecl.fromBuildConfig("!!!not base64!!!")).isEmpty())
        assertTrue(Declarations.pagesOf(NavDecl.EMPTY, "configs").isEmpty())
        assertTrue(Declarations.parseSync("").pages.isEmpty())
        assertTrue(Declarations.parseFiles("").sections.isEmpty())
        assertEquals("name", Declarations.parseFiles("").defaultSort)
        assertEquals("", Declarations.decode(""))
        assertEquals("[1]", Declarations.decode(java.util.Base64.getEncoder().encodeToString("[1]".toByteArray())))
    }
}
