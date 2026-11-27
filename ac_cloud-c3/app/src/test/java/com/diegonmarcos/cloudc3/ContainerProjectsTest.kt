package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ContainerProjects
import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.RoutesTableModel
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
import org.junit.Test

/**
 * #1454 / #1941 the container -> project mapping is data/container_projects.json, held against the Containers document, build.json and
 * the Cloud Me Proj data (ac_cloud-me/build.json): the projects ARE the Cloud Me Proj entries (plus Lib), each container has a project,
 * a Cat1 / Cat2 (its taxonomy group / category) and a Type, and the Table keeps every value the previous column set showed.
 */
class ContainerProjectsTest {

    private val dir = FleetFixtures.moduleDir()
    private val decl = ContainerProjects.parse(File(dir, "data/container_projects.json").readText(), File(dir, "data/repos_declared.json").readText())
    private val catalog = ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!

    private fun containersPages() = Json.parseToJsonElement(File(dir, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sections"]!!.jsonArray
        .map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "containers" }["pages"]!!.jsonArray.map { it.jsonObject }

    private val slice = com.diegonmarcos.cloudc3.pages.FleetModel.parse(File(dir, "data/fleet_slice.json").readText())
    private val hosts = com.diegonmarcos.cloudc3.pages.ContainerHosts.derive(slice, catalog, decl.vm.hostOrder)
    private val vdecl = decl.copy(hostIds = hosts.map { it.id })
    private fun vmPageIds() = listOf("${decl.vm.prefix}all") + hosts.map { decl.vm.prefix + it.id } + "${decl.vm.prefix}unknown"

    private fun navTopIds() = containersPages().map { it["id"]!!.jsonPrimitive.content }

    /** The Cloud Me Proj tree: (category label, [(page id, label)]), emoji stripped from the labels. */
    private fun cloudMeProj(): List<Pair<String, List<Pair<String, String>>>> {
        val root = Json.parseToJsonElement(File(dir.parentFile, "ac_cloud-me/build.json").readText()).jsonObject
        val proj = root["ui"]!!.jsonObject["sections"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "proj" }
        fun plain(o: JsonObject) = o["label"]!!.jsonPrimitive.content.dropWhile { !it.isLetterOrDigit() }.trim()
        return proj["pages"]!!.jsonArray.map { it.jsonObject }.filter { it["pages"]?.jsonArray?.isNotEmpty() == true }
            .map { c -> plain(c) to c["pages"]!!.jsonArray.map { it.jsonObject }.map { it["id"]!!.jsonPrimitive.content to plain(it) } }
    }

    @Test
    fun `every project page of the Containers strip is All or a declared project, in the declared order`() {
        val declared = decl.projects.map { it.id }
        assertEquals("All first, then every declared project in order, then the VM pages (#1971)", listOf("all") + declared + vmPageIds(), navTopIds())
        assertNotNull("the default project is declared", decl.project(decl.default))
    }

    @Test
    fun `the projects are exactly the Cloud Me Proj entries, named as that data names them`() {
        val want = cloudMeProj().flatMap { (cat, ps) -> ps.map { (id, label) -> Triple(id, cat, "${cat}_$label") } }
        assertEquals(want, decl.projects.map { Triple(it.id, it.category, it.label) })
        // the page labels of build.json are the Cloud Me names without the category prefix
        val pageLabels = containersPages().drop(1).filterNot { it["id"]!!.jsonPrimitive.content.startsWith("vm-") }.map { it["label"]!!.jsonPrimitive.content }
        assertEquals(decl.projects.map { it.label.removePrefix("${it.category}_") }, pageLabels)
        // the owner's names, as the Cloud Me data spells them (DeepXTech, FinTerm, ModernSc: the data wins over a typed list)
        listOf("Unix_SuperApp", "Unix_Cloud-me", "Unix_Cloudy", "CG_Omniverse", "CG_Zirkusverse", "Capital_CB", "Capital_PM", "Capital_FinTerm",
            "Hardtech_BioMed", "Hardtech_CAD", "Hardtech_DeepXTech", "Wiki_ModernSc", "Wiki_Arcanum", "Wiki_Projects").forEach { n ->
            assertTrue("project $n is missing", decl.projects.any { it.label == n })
        }
        assertTrue("the 42 projects are there", decl.projects.count { it.category == "42" } >= 10)
    }

    @Test
    fun `the first row is the Cloud Me categories (no Lib, it is Wiki) and the second row lists the projects of the category`() {
        assertEquals(cloudMeProj().map { it.first }, decl.categories())
        assertFalse("Lib is gone from the row", "Lib" in decl.categories())
        assertEquals(listOf("Unix_SuperApp", "Unix_Cloud-me", "Unix_Cloudy"), decl.projectsOf("Unix").map { it.label })
    }

    @Test
    fun `every container and every repo belongs to exactly one project and the projects partition the document`() {
        val seen = decl.projects.associate { p -> p.id to ContainerProjects.scope(decl, catalog, p.id).entries.map { it.name } }
        val all = (catalog.names + decl.repos.map { it.name }).sorted()
        assertEquals("every container and repo is reachable under some project", all, seen.values.flatten().toSet().sorted())
        // #1953 a container is in exactly one project; a repo is in exactly its attached projects (the primary first), so the primaries partition the document
        val containers = catalog.names.toSet()
        assertEquals("no container is in two projects", containers.size, seen.values.flatten().filter { it in containers }.size)
        decl.repos.forEach { r -> assertEquals(r.name, r.attached.map { it.project }.toSet(), seen.filterValues { r.name in it }.keys) }
        assertTrue("the All view has them all", ContainerProjects.rows(decl, catalog, null).map { it.container }.sorted() == all)
    }

    @Test
    fun `a scoped catalog keeps only its project in the document's order and drops empty categories`() {
        decl.projects.forEach { p ->
            val c = ContainerProjects.scope(decl, catalog, p.id)
            val names = c.entries.map { it.name }.toSet()
            c.groups.flatMap { g -> g.categories }.forEach { cat ->
                assertTrue("empty category ${cat.label}", cat.names.isNotEmpty())
                assertTrue(cat.names.all { it in names })
            }
        }
    }

    @Test
    fun `the All table clusters rows by project in the declared order and a project table holds only its own`() {
        val all = ContainerProjects.rows(decl, catalog, null)
        assertEquals(catalog.entries.size + decl.repos.size, all.size)
        val labels = decl.projects.map { it.label }
        val seq = all.map { r -> labels.indexOf(r.project) }
        assertTrue("every All row names its project", seq.all { it >= 0 })
        assertEquals("rows are clustered in the declared project order", seq.sorted(), seq)
        val one = decl.projects.first { p -> ContainerProjects.scope(decl, catalog, p.id).entries.isNotEmpty() }
        assertTrue(ContainerProjects.rows(decl, catalog, one.id).all { it.project == one.label })
    }

    @Test
    fun `every declared container resolves to a project and the named ones land where declared`() {
        catalog.entries.forEach { assertNotNull("${it.name} has no project", decl.projectOf(it)) }
        assertEquals("pj-unix-cloudme", decl.projectOf(catalog.entry("cloud-me-api")!!)?.id)
        assertEquals("pj-unix-superapp", decl.projectOf(catalog.entry("cloud-superapp-mcp")!!)?.id)
        assertEquals("pj-capital-finterm", decl.projectOf(catalog.entry("news-gdelt")!!)?.id)
        assertEquals("pj-unix-cloudy", decl.projectOf(catalog.entry("vaultwarden")!!)?.id)
    }

    @Test
    fun `a name override beats the prefixes and an unknown container falls to the declared default`() {
        val e = catalog.entry("cloud-me-api")!!
        assertEquals("pj-unix-cloudme", decl.projectOf(e)?.id)
        assertEquals(decl.default, decl.projectOf(e.copy(name = "brand-new", folder = "zzz"))?.id)
        assertEquals("pj-capital-finterm", decl.projectOf(e.copy(name = "capital-new", folder = "zzz"))?.id)
    }

    @Test
    fun `Wiki holds the former Lib repos and the empty projects say so, none is invented`() {
        // #1968 Lib is Wiki: lib-chronos (primary Wiki_ModernSc), the hub Projects-___ (Wiki_ModernSc) and the root _git-repos-master (Wiki_Projects); Wiki Arcanum holds only its declared lib-chronos path
        val wiki = decl.projects.filter { it.category == "Wiki" }.flatMap { ContainerProjects.scope(decl, catalog, it.id).entries }
        assertTrue(wiki.all { it.role == "repo" } && wiki.any { it.name == "lib-chronos" })
        assertEquals(listOf("lib-chronos"), wiki.filter { decl.repoOf(it)?.role == "repo" }.map { it.name }.distinct())
        assertEquals(listOf("lib-chronos"), ContainerProjects.scope(decl, catalog, "pj-wiki-arcanum").entries.map { it.name })
        assertTrue("none is invented", decl.projects.all { p -> ContainerProjects.scope(decl, catalog, p.id).entries.all { e -> e.role != "repo" || decl.repoOf(e)!!.attached.any { it.project == p.id } } })
    }

    @Test
    fun `the kind rules classify MCPs APIs DBs Repos and leave the rest Apps`() {
        fun k(n: String) = decl.kindOf(catalog.entry(n)!!)?.id
        assertEquals("mcp", k("cloud-infra-mcp")); assertEquals("api", k("c3-infra-api")); assertEquals("api", k("cloud-me-api"))
        assertEquals("db", k("umami-db")); assertEquals("db", k("postlite-npm")); assertEquals("db", k("authelia-redis"))
        assertEquals("apps", k("caddy")); assertEquals("apps", k("dbgate"))
        assertEquals(listOf("mcp", "api", "apps", "db", "repo"), decl.kinds.map { it.id })
        assertTrue(ContainerProjects.scope(decl, catalog, "pj-unix-cloudy", "repo").entries.all { it.role == "repo" })
    }

    @Test
    fun `Type is App Db MCP API Repo or Other, declared, and every container has one`() {
        assertEquals(listOf("App", "Db", "MCP", "API", "Repo", "Other"), decl.types)
        fun t(n: String) = decl.typeOf(catalog.entry(n)!!)
        assertEquals("MCP", t("cloud-infra-mcp")); assertEquals("API", t("c3-infra-api")); assertEquals("Db", t("umami-db"))
        assertEquals("App", t("dbgate")); assertEquals("Other", t("caddy"))
        // #1953 E an LFS repo is a Db (the declared lfs.type_kind), every other repo a Repo
        decl.repos.forEach { assertEquals(it.name, if (it.lfs) "Db" else "Repo", decl.typeOf(ContainerProjects.entryOf(decl, it))) }
        val rows = ContainerProjects.rows(decl, catalog, null)
        assertTrue("a row has no type", rows.all { it.type in decl.types })
        decl.types.forEach { ty -> assertTrue("no container of type $ty", rows.any { it.type == ty }) }
        // the type filter keeps exactly that type
        decl.types.forEach { ty -> assertTrue(ContainerProjects.scope(decl, catalog, null, null, setOf(ty)).entries.all { decl.typeOf(it) == ty }) }
    }

    @Test
    fun `the vm filter keeps the containers of that host`() {
        val host = catalog.entries.first { it.host.isNotBlank() }.host
        val c = ContainerProjects.scope(decl, catalog, null, null, emptySet(), setOf(host))
        assertTrue(c.entries.isNotEmpty() && c.entries.all { it.host == host })
    }

    @Test
    fun `a git repo is both a Repo and a DB kind, so it is in the Db and Repo scopes of its project`() {
        decl.repos.forEach { r ->
            val e = ContainerProjects.entryOf(decl, r)
            assertEquals(listOf("db", "repo"), decl.kindsOf(e).map { it.id }.sorted())
            assertEquals(if (r.lfs) "git-lfs" else "git", e.dbEngine)
            listOf("db", "repo").forEach { k -> assertTrue(ContainerProjects.scope(decl, catalog, r.project, k).entries.any { it.name == r.name }) }
        }
    }

    @Test
    fun `Infra Apps and Cloud Apps belong to Cloudy only and Project Apps is gone`() {
        val macro = listOf(ContainersModel.INFRA, ContainersModel.CLOUD, ContainersModel.PROJECT)
        decl.projects.filter { it.repoGroup.isBlank() }.forEach { p ->
            val c = ContainerProjects.scope(decl, catalog, p.id)
            assertTrue("${p.id} repeats a Cloudy macro group", c.entries.none { it.group in macro })
            val fams = decl.families[p.id].orEmpty()
            assertTrue("${p.id} groups are not its families", c.groups.filter { g -> g.categories.isNotEmpty() }.all { g -> g.label in fams || g.label == decl.containerGroup || g.label == "Hub" ||
                decl.repos.any { r -> r.group == g.label && r.attached.any { it.project == p.id && it.via != "code" } } })
        }
        val cloudy = ContainerProjects.scope(decl, catalog, "pj-unix-cloudy")
        assertTrue(cloudy.groups.none { it.label == "Project Apps" } && cloudy.entries.none { it.group == "Project Apps" })
        assertEquals("Cloud Apps", cloudy.entry("send_app")!!.group)
        assertEquals(listOf("Infra Apps", "Cloud Apps", "Repos", "Hub"), cloudy.groups.filter { it.categories.isNotEmpty() }.map { it.label })
    }

    @Test
    fun `every owner repo is under its project with its facts, none archived`() {
        assertTrue(decl.repos.isNotEmpty())
        assertTrue(decl.repos.none { it.name.startsWith("z-archived_") })
        val r = decl.repos.first { it.name == "CG-galaxy" }
        assertEquals("pj-cg-omniverse", r.project); assertEquals("https://github.com/diegonmarcos/CG-galaxy", r.githubUrl)
        assertEquals("pj-cg-zirkusverse", decl.repos.first { it.name == "CG-circus" }.project)
        assertTrue(decl.repos.all { it.visibility in listOf("pub", "pvt") && it.githubUrl.endsWith("/${it.github}") })
        assertTrue(decl.repos.filter { it.name.startsWith("cloud-me-p") }.all { it.project == "pj-unix-cloudme" })
        assertTrue(decl.repos.filter { it.name.startsWith("cloudy-") }.all { it.project == "pj-unix-cloudy" })
        val e = ContainerProjects.scope(decl, catalog, "pj-cg-omniverse", "repo").entry("CG-galaxy")!!
        assertEquals("repo", e.role)
        assertTrue(e.fields().any { it.first == "github_url" })
    }

    // ── #1953 hubs, LFS, attached repos ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an LFS repo is Type Db with the git-lfs engine, and is not a Repo`() {
        val lfs = decl.repos.first { it.name == "cloudy_db_LFS" }
        assertTrue(lfs.lfs)
        val e = ContainerProjects.entryOf(decl, lfs)
        assertEquals("Db", decl.typeOf(e)); assertEquals("git-lfs", e.dbEngine)
        assertTrue(e.fields().contains("lfs" to "true"))
        val dbs = ContainerProjects.scope(decl, catalog, "pj-unix-cloudy", null, setOf("Db")).entries.map { it.name }
        assertTrue("the Type=Db filter of Cloudy lists cloudy_db_LFS", "cloudy_db_LFS" in dbs)
        assertFalse("cloudy_db_LFS" in ContainerProjects.scope(decl, catalog, "pj-unix-cloudy", null, setOf("Repo")).entries.map { it.name })
    }

    @Test
    fun `a hub is attached to every project under its code and drawn there`() {
        val hub = decl.repos.first { it.name == "lecole-42-___" }
        assertEquals("hub", hub.role)
        val ids = decl.projects.filter { it.code.startsWith("5.") }.map { it.id }
        assertTrue(ids.size >= 10)
        ids.forEach { id -> assertTrue("$id lost the 42 hub", ContainerProjects.scope(decl, catalog, id).entries.any { it.name == hub.name }) }
        assertEquals(ids, decl.projectsOf(ContainerProjects.entryOf(decl, hub)).map { it.id }.filter { it in ids })
    }

    @Test
    fun `projects the registry cannot place get their declared repos, Wiki with its lib-chronos path`() {
        val chronos = decl.repos.first { it.name == "lib-chronos" }
        mapOf("pj-wiki-modernsc" to "1.Modern-science", "pj-wiki-arcanum" to "2.Arcanum-science", "pj-wiki-projects" to "3.Projects").forEach { (id, path) ->
            assertEquals(path, chronos.attached.first { it.project == id }.path)
            assertTrue("$id draws lib-chronos", ContainerProjects.scope(decl, catalog, id).entries.any { it.name == "lib-chronos" })
        }
        listOf("pj-unix-superapp" to "cloudy-u-android", "pj-hardtech-biomed" to "HardTech-___", "pj-projects-duckpeng" to "DuckPeng-___").forEach { (id, n) ->
            assertTrue("$id draws $n", ContainerProjects.scope(decl, catalog, id).entries.any { it.name == n })
        }
        assertEquals("the primary project is the registry one, Wiki_ModernSc (code 6.3)", "pj-wiki-modernsc", decl.projectOf(ContainerProjects.entryOf(decl, chronos))?.id)
    }

    @Test
    fun `scope keeps a repo on every attached project, not only its primary one`() {
        val multi = decl.repos.filter { it.attached.size > 1 }
        assertTrue(multi.isNotEmpty())
        multi.forEach { r -> r.attached.forEach { a ->
            assertTrue("${r.name} missing on ${a.project}", ContainerProjects.scope(decl, catalog, a.project).entries.any { it.name == r.name })
        } }
    }

    @Test
    fun `every project and All draw at least the entities they drew before #1953`() {
        // the previous rule: the primary project only, no hubs
        val before = { id: String? -> ContainerProjects.full(decl, catalog).entries.filter { e ->
            (id == null || decl.projectOf(e)?.id == id) && (decl.repoOf(e)?.role ?: "repo") == "repo" }.map { it.name }.toSet() }
        (decl.projects.map { it.id } + listOf<String?>(null)).forEach { id ->
            val now = ContainerProjects.scope(decl, catalog, id).entries.map { it.name }.toSet()
            assertTrue("$id lost ${before(id) - now}", now.containsAll(before(id)))
        }
    }

    @Test
    fun `hubs get no GHCR cgc-db row, every registry repo does`() {
        val src = File(dir, "app/src/main/java/com/diegonmarcos/cloudc3/pages/OrchRegistries.kt").readText()
        assertTrue(src.contains("repos.filter { it.role == \"repo\" }.map { it.name })"))
    }

    // ── #1941 field parity: the previous column set against the new one ───────────────────────────────────────

    /** What the previous table showed per row (Group, Category, Container, Public URL, Auth Type, Private URL, VM Name, VM IP:PORT) that the new row no longer carries under the same or a mapped column; empty = nothing lost. */
    private fun parityGaps(old: List<RoutesTableModel.Row>, new: List<RoutesTableModel.Row>): List<String> {
        val by = new.associateBy { it.container }
        val gaps = mutableListOf<String>()
        old.forEach { o ->
            val n = by[o.container] ?: run { gaps += "${o.container}: row gone"; return@forEach }
            // Group -> Cat1 (column GROUP), Category -> Cat2 (column CATEGORY): the values are the old ones, unchanged
            if (n.text(RoutesTableModel.Column.GROUP) != o.group) gaps += "${o.container}: Group/Cat1 ${o.group} -> ${n.group}"
            if (n.text(RoutesTableModel.Column.CATEGORY) != o.category) gaps += "${o.container}: Category/Cat2"
            if (n.publicUrl != o.publicUrl || n.authType != o.authType || n.privateUrl != o.privateUrl || n.vmName != o.vmName || n.vmAddr != o.vmAddr) gaps += "${o.container}: a cell"
            if (n.meta.toMap().entries.containsAll(o.meta.toMap().entries).not()) gaps += "${o.container}: More fields"
        }
        return gaps
    }

    @Test
    fun `field parity - every value the previous Table showed is still reachable in the new columns`() {
        // the previous rows: the document plus the repos, through the previous (project-free) row builder
        val old = ContainersModel.rows(ContainerProjects.scope(decl, catalog, null))
        val new = ContainerProjects.rows(decl, catalog, null)
        assertEquals(old.map { it.container }.sorted(), new.map { it.container }.sorted())
        assertEquals("nothing lost", emptyList<String>(), parityGaps(old, new))
        // the previous column set: each is reachable, the Group column is carried by Cat1
        val cols = RoutesTableModel.Column.entries
        val headers = com.diegonmarcos.cloudc3.pages.RoutesExport.HEADERS
        listOf("Status", "Category", "Container", "Public URL", "Auth Type", "Private URL", "VM Name", "VM IP:PORT").forEach { h ->
            assertTrue("$h is gone", h in headers.values || (h == "Category" && "Cat2" in headers.values))
        }
        assertTrue("Group is carried by Cat1", "Cat1" in headers.values && "Group" !in headers.values)
        assertEquals(listOf("Project", "Cat1", "Cat2", "Type"), listOf(cols[1], cols[2], cols[3], cols[4]).map { headers.getValue(it) })
        // MUTATION: drop a value and the parity check must go red
        assertTrue("mutation (Cat1 blanked) is caught", parityGaps(old, new.map { it.copy(group = "") }).isNotEmpty())
        assertTrue("mutation (Cat2 blanked) is caught", parityGaps(old, new.map { it.copy(category = "") }).isNotEmpty())
        assertTrue("mutation (a row dropped) is caught", parityGaps(old, new.drop(1)).isNotEmpty())
        assertTrue("mutation (a More field dropped) is caught", parityGaps(old, new.map { it.copy(meta = emptyList()) }).isNotEmpty())
    }

    @Test
    fun `Cat1 and Cat2 are the container's taxonomy group and category`() {
        val rows = ContainerProjects.rows(decl, catalog, "pj-unix-cloudy")
        val e = catalog.entry("caddy")!!
        val r = rows.first { it.container == "caddy" }
        assertEquals(e.group, r.group); assertEquals(e.category, r.category)
        assertEquals("Unix_Cloudy", r.project); assertEquals("Other", r.type)
    }

    @Test
    fun `#1968 parity every entity under Lib before is under Wiki now, the All total is unchanged and the old ids resolve to Wiki`() {
        val base = Json.parseToJsonElement(File(dir, "data/lib_members_1968.json").readText()).jsonObject
        val wiki = decl.projects.filter { it.category == "Wiki" }.flatMap { ContainerProjects.scope(decl, catalog, it.id).entries }.map { it.name }.toSet()
        val before = base["members"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("an entity left Lib and is not under Wiki: ${before - wiki}", wiki.containsAll(before))
        assertEquals("the All total of repos is unchanged", base["repos_total"]!!.jsonPrimitive.content.toInt(), decl.repos.size)
        assertNull("the lib project is gone", decl.project("lib"))
        val al = Json.parseToJsonElement(File(dir, "data/container_projects.json").readText()).jsonObject["aliases"]!!.jsonObject
        assertEquals("Wiki", decl.project(al["lib"]!!.jsonPrimitive.content)?.category)
        assertEquals("Wiki", decl.project(al["pj-lib"]!!.jsonPrimitive.content)?.category)
    }

    @Test
    fun `#1971 parity Projects keeps every entity reachable, VM lists every container under exactly one host or the unknown host with a reason`() {
        // Projects: every entity of All is under at least one project page (nothing lost by the mode switch)
        val all = ContainerProjects.scope(vdecl, catalog, null).entries.map { it.name }.toSet()
        val underProjects = vdecl.projects.flatMap { p -> ContainerProjects.scope(vdecl, catalog, p.id).entries.map { it.name } }.toSet()
        val homeless = all - underProjects
        assertTrue("entities reachable through no project page: $homeless", homeless.all { n -> vdecl.projectOf(ContainerProjects.scope(vdecl, catalog, null).entries.first { it.name == n })?.id == vdecl.default })
        // the hosts are derived from the fleet, with the PC and the phones
        assertEquals("the VM row reads as declared: the VMs in the owner's order, then the devices by their declared short label",
            listOf("oci-apps", "oci-analytics", "oci-mail", "gcp-proxy", "surface-8-pro", "samsung-a37", "samsung-s21plus"), hosts.map { it.id })
        assertEquals(listOf("oci-apps", "oci-analytics", "oci-mail", "gcp-proxy", "SurfacePro8", "SamsungA37", "SamsungS21"), hosts.map { it.label })
        assertEquals("the unknown host stays last", "${decl.vm.prefix}unknown", vmPageIds().last())
        // VM: every container is on exactly one VM page (a host or unknown); repos on none but All
        val pages = vmPageIds().filter { it != "${vdecl.vm.prefix}all" }
        val vmAll = ContainerProjects.scope(vdecl, catalog, "${vdecl.vm.prefix}all").entries
        assertEquals("VM All lists every entity", all, vmAll.map { it.name }.toSet())
        vmAll.filter { it.role != "repo" }.forEach { e ->
            val on = pages.filter { p -> ContainerProjects.scope(vdecl, catalog, p).entries.any { it.name == e.name } }
            assertEquals("${e.name} (host '${e.host}') is on exactly one VM page: $on", 1, on.size)
        }
        vmAll.filter { it.role == "repo" }.forEach { e -> assertTrue("${e.name} is a repo and is on a host page", pages.none { p -> ContainerProjects.scope(vdecl, catalog, p).entries.any { it.name == e.name } }) }
        // the unknown host and the repos band carry their reason
        val plan = com.diegonmarcos.cloudc3.pages.ContainerTopology.plan(vdecl, ContainerProjects.scope(vdecl, catalog, "${vdecl.vm.prefix}unknown"), "${vdecl.vm.prefix}unknown")
        assertTrue("the unknown host has a reason", plan.bands.first { it.id != vdecl.vm.reposBand }.note.contains("is not a declared fleet host"))
        val allPlan = com.diegonmarcos.cloudc3.pages.ContainerTopology.plan(vdecl, ContainerProjects.scope(vdecl, catalog, "${vdecl.vm.prefix}all"), "${vdecl.vm.prefix}all")
        assertEquals("Not deployed (repos)", allPlan.bands.first { it.id == vdecl.vm.reposBand }.label)
        assertTrue(allPlan.bands.first { it.id == vdecl.vm.reposBand }.note.isNotBlank())
        val oci = com.diegonmarcos.cloudc3.pages.ContainerTopology.plan(vdecl, ContainerProjects.scope(vdecl, catalog, "${vdecl.vm.prefix}oci-apps"), "${vdecl.vm.prefix}oci-apps")
        assertEquals("a host page draws no repo", 0, oci.bands.first { it.id == vdecl.vm.reposBand }.count)
    }
}
