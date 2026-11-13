package com.diegonmarcos.cloudc3

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.diegonmarcos.superapp.bottomnav.NavDecl

/**
 * #648 the declaration parser, run on the JVM against THIS repository's own build.json.
 *
 * The parse functions take JSON TEXT rather than BuildConfig precisely so this suite can
 * exercise the exact code the phone runs (#868: the navigation is libs:bottomnav's NavDecl,
 * parsed here from the same ui.sections / ui.bottom_nav the gradle bake reads; org.json needs
 * Robolectric). A shell tester can compare two files; only this
 * can prove the parser turns the declaration into the models the screens receive — so a
 * declaration that parses to something subtly different (a dropped tab, a blank label)
 * fails here rather than on a device.
 */
@RunWith(RobolectricTestRunner::class)
class DeclarationsTest {

    /** The repository's real build.json, found by walking up to the app directory. */
    private fun buildJson(): JsonObject {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        val f = File(dir, "build.json")
        assertTrue("could not locate ac_cloud-c3/build.json from ${File("").absolutePath}", f.isFile)
        return Json.parseToJsonElement(f.readText()).jsonObject
    }

    private fun ui(): JsonObject = buildJson()["ui"]!!.jsonObject

    private fun uiText(key: String): String = ui()[key]!!.toString()

    /** The nav exactly as the phone builds it: ui.sections + ui.bottom_nav + ui.default_section. */
    private fun nav(): NavDecl = NavDecl.parse(
        JSONArray(uiText("sections")),
        ui()["bottom_nav"]!!.jsonArray.joinToString(",") { it.jsonPrimitive.content },
        ui()["default_section"]!!.jsonPrimitive.content,
    )

    private fun pageIds(section: String): List<String> =
        nav().section(section)!!.pages.map { it.id }

    @Test
    fun `the five declared tabs parse in declared order with Home centre`() {
        val tabs = nav().bottomSections()
        assertEquals("every declared tab must parse; a dropped one is an invisible missing tab",
            5, tabs.size)
        assertEquals("owner order, 2026-10-16: Infra | Containers | Home | Ops | Observ; Configs is off the bar",
            listOf("Infra", "Containers", "Home", "Ops", "Observ"), tabs.map { it.label })
        assertEquals("Home must be the centre item", "home", tabs[2].id)
        assertTrue("no tab may parse with a blank label or icon",
            tabs.all { it.label.isNotBlank() && it.icon.isNotBlank() })
    }

    @Test
    fun `the declared default tab is one of the declared tabs`() {
        val tabs = nav().bottomNav
        val default = nav().default()!!.id
        assertEquals("home", default)
        assertTrue("ui.default_section '$default' is not among $tabs, so the app would open blank",
            default in tabs)
    }

    @Test
    fun `the four Apps tiles parse with the sibling packages they declare`() {
        val apps = Declarations.parseExternalApps(uiText("external_apps"))
        assertEquals("every declared Apps tile must parse", 4, apps.size)
        assertEquals(listOf("c3-watchdog", "c3-morpheus", "c3-watchtower", "cloud-c3-webserver"), apps.map { it.id })
        assertEquals(
            listOf(
                "com.diegonmarcos.watchdog",
                "com.diegonmarcos.morpheus",
                "com.diegonmarcos.watchtower",
                "com.diegonmarcos.cloudwebserver",
            ),
            apps.map { it.packageName },
        )
    }

    @Test
    fun `the tile's printed name is DERIVED from the fleet name, not declared`() {
        // The owner asked for Watchdog / Morpheus / Watchtower; the fleet spells these
        // c3-watchdog / c3-morpheus / c3-watchtower. Both are satisfied by deriving the
        // printed name, which is why no `label` is stored: #351's one name, #224's revert.
        val apps = Declarations.parseExternalApps(uiText("external_apps"))
        assertEquals(listOf("Watchdog", "Morpheus", "Watchtower", "Webserver"), apps.map { it.display })
    }

    @Test
    fun `no Apps tile declares a label, because the name would then be stated twice`() {
        val raw = ui()["external_apps"]!!.jsonArray
        assertTrue("a label beside a fleet package is a second statement of the app's one name",
            raw.none { "label" in it.jsonObject })
    }

    @Test
    fun `an id with no family prefix still prints something`() {
        val one = Declarations.parseExternalApps(
            """[{"id":"morpheus","icon":"robot","package":"p"}]""",
        ).single()
        assertEquals("Morpheus", one.display)
    }

    @Test
    fun `an Apps tile with no package is dropped rather than rendered unopenable`() {
        val text = """[{"id":"a","label":"A","icon":"robot"}]"""
        assertTrue("a tile that cannot name the app it opens must not be rendered",
            Declarations.parseExternalApps(text).isEmpty())
    }

    @Test
    fun `each content tab's declared sub-pages parse`() {
        // DECLARED means IMPLEMENTED (#648): these are exactly the pages that have a
        // fragment today. The `topology`/`observability` pages ARE the SuperApp's two
        // stacks (feed cards, ntfy centre, container dashboards — C3StackFragment over
        // ui.carried_c3.stack_*), carried whole per the owner's escalation; dagu and the
        // WG mesh arrived with them. The SuperApp's sample-stub page ids stay undeclared,
        // which is what keeps "no placeholder in a shipped tab" assertable.
        assertEquals("Infra = Providers | VMs | Storage | Electronics | Domains (2026-10-20); no Stack, Dbs or API page",
            listOf("providers", "machines", "serverless", "node_python", "storage", "api_ai", "api_scraper", "api_endpoints", "api_mcp", "desktop", "mobile", "gadgets", "control", "domains", "domains_public"), pageIds("infra"))
        assertEquals("#1454 one page per project, in the owner's order", listOf("all", "cloudy", "cloud_me", "cg", "capital", "hardtech", "others"), pageIds("containers"))
        assertEquals("#1454 every project page keeps the Topology | Table switch",
            pageIds("containers").map { listOf("${it}_topology", "${it}_table") },
            nav().section("containers")!!.pages.map { p -> p.pages.map { it.id } })
        assertEquals(listOf("observability", "health", "dagu", "mesh"), pageIds("observ"))
        assertEquals("#1443 Configs starts General | Perms | About, then the app's own Build card", listOf("general", "perms", "about", "build", "plugins"), pageIds("configs"))
    }

    @Test
    fun `every declared icon is a drawable name (ic_) or a libs ui-icons catalog name`() {
        // The shell resolves an ic_ name with getIdentifier, so it must be a real resource name ("health" or "robot",
        // the old Compose keys, resolve to 0 and draw a blank square). The shared second row (Configs General | Perms |
        // About, #1443) draws any other name from the libs:ui-icons catalog, so it must be one the catalog holds.
        val names = nav().sections.flatMap { s -> listOf(s.icon) + s.allPages().map { it.icon } }
        assertTrue("no declared icon may be blank", names.none { it.isBlank() })
        assertTrue("every declared icon must be an ic_ drawable name or a catalog name: $names",
            names.all { it.startsWith("ic_") || com.diegonmarcos.superapp.uiicons.UiIcons.has(it) })
    }

    @Test
    fun `a malformed or empty declaration parses to empty rather than throwing`() {
        // A crash here would be a blank app; an empty declaration is a stated empty state.
        assertEquals(emptyList<String>(), NavDecl.fromBuildConfig("!!!not base64!!!").bottomNav)
        assertEquals(emptyList<String>(), NavDecl.fromBuildConfig("").sections.map { it.id })
        assertEquals("", Declarations.decode("!!!not base64!!!"))
    }

    @Test
    fun `reordering ui_bottom_nav reorders the bar and nothing else`() {
        // The property the bottom nav rests on: order is carried, never sorted.
        val s = JSONArray("""[{"id":"one","label":"One","icon":"home"},{"id":"two","label":"Two","icon":"stack"}]""")
        assertEquals(listOf("one", "two"), NavDecl.parse(s, "one,two").bottomSections().map { it.id })
        assertEquals(listOf("two", "one"), NavDecl.parse(s, "two,one").bottomSections().map { it.id })
    }

    @Test
    fun `every icon name the declaration uses is a non-blank short name`() {
        val ui = ui()
        val names = mutableListOf<String>()
        ui["sections"]!!.jsonArray.forEach { names += it.jsonObject["icon"]!!.jsonPrimitive.content }
        ui["external_apps"]!!.jsonArray.forEach { names += it.jsonObject["icon"]!!.jsonPrimitive.content }
        names += ui["icon_default"]!!.jsonPrimitive.content
        assertTrue("an icon name is a bare drawable name, never a path or an @reference",
            names.all { it.isNotBlank() && !it.contains('/') && !it.startsWith("@") })
    }

    @Test
    fun `every declared service view renders rows from the derived slice`() {
        val decl = AppsCatalog.parse(ui()["apps_page"]!!.toString())
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "data/infra_slice.json").isFile) dir = dir.parentFile
        val slice = Json.parseToJsonElement(File(dir, "data/infra_slice.json").readText()).jsonObject
        assertEquals(listOf("security_network", "dns_routing", "mail"), decl.services.map { it.id })
        for (s in decl.services) for (v in s.views) {
            val blocks = AppsCatalog.view(slice, v)
            assertTrue("view $v of ${s.id} rendered nothing", blocks.isNotEmpty() && blocks.any { it.rows.isNotEmpty() })
        }
        for (t in decl.tools) assertTrue("tool ${t.id} has no detail", AppsCatalog.toolBlocks(slice, t.id).isNotEmpty())
        assertTrue("caddy is used by more than one service", decl.usedBy("caddy").size >= 2)
        assertTrue("no secret value may be carried", !slice.toString().contains("wg_public_key"))
    }

    @Test
    fun `the Ops tab reads Network Mail, Orchestration, Health Observability with a divider before each sub-group, and Agents stays`() {
        assertEquals(listOf("net", "mail", "orch", "ops_health", "obs"), pageIds("ops"))
        assertEquals(listOf("orch", "ops_health"), nav().section("ops")!!.pages.filter { it.separatorBefore }.map { it.id })
        assertTrue("Agents is an Ops page", "orch_agents" in nav().section("ops")!!.allPages().map { it.id })
        assertTrue("Services and Tools are gone from Ops", pageIds("ops").none { it == "services" || it == "tools" })
        assertTrue("Agents left Containers", "agents" !in nav().section("containers")!!.allPages().map { it.id })
    }

    @Test
    fun `every topology view has an Ops page, and every Ops page has an icon from the shared catalog`() {
        val topo = com.diegonmarcos.cloudc3.pages.TopologyModel.parseDecl(ui()["topology"]!!.toString())
        val leaves = nav().section("ops")!!.pages.flatMap { it.pages }.map { it.id }
        topo.views.forEach {
            assertTrue("${it.id} lacks grouping/metric/question/missing", it.grouping.isNotBlank() && it.metric.isNotBlank() && it.question.isNotBlank() && it.missing.isNotEmpty())
            assertEquals("${it.id} is shown by exactly one Ops page", 1, leaves.count { id -> id.endsWith("_${it.id}") })
        }
        assertEquals("Ship", topo.release?.workflow)
        nav().section("ops")!!.allPages().forEach {
            assertTrue("${it.id} icon ${it.icon} is not in libs:ui-icons", com.diegonmarcos.superapp.uiicons.UiIcons.has(it.icon))
        }
    }
}
