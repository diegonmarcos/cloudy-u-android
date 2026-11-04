package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.cloud.Stacks
import com.diegonmarcos.cloudc3.pages.ContainersModel as M
import com.diegonmarcos.cloudc3.pages.ContainersSource
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1148 the Containers page has ONE source. Topology (Stacks.cloudServices) and Table (ContainersModel.rows) both derive
 * from ContainersSource; the document has every container once, the Infra categories in the owner's order, and every
 * container carries its metadata.
 */
class ContainersModelTest {

    private val catalog = ContainersSource.bundled

    private fun moduleFile(rel: String): File {
        var dir = File("").absoluteFile
        while (dir.parentFile != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        return File(dir, rel)
    }

    @Test
    fun `the baked document parses and is sound`() {
        assertTrue("the bundled document is empty", catalog.entries.size > 50)
        assertEquals(emptyList<String>(), M.problems(catalog))
    }

    @Test
    fun `the baked document is the file in data`() {
        assertEquals(M.parse(moduleFile("data/containers_declared.json").readText()), catalog)
    }

    @Test
    fun `topology and table read the same source`() {
        val dash = Stacks.dashOf(catalog)
        val dashNames = dash.groups.flatMap { g -> g.subgroups.flatMap { s -> s.containers.map { it.name } } }
        val tableNames = M.rows(catalog).map { it.container }
        assertEquals("both views list the same containers", dashNames.sorted(), tableNames.sorted())
        assertEquals("the live accessor serves the same document plus the declared repos", (dashNames + com.diegonmarcos.cloudc3.Declarations.containerProjects.repos.map { it.name }).sorted(), Stacks.cloudServices().groups.flatMap { g -> g.subgroups.flatMap { s -> s.containers.map { it.name } } }.sorted())
        // neither view may keep a second, older list
        val stacks = moduleFile("app/src/main/java/com/diegonmarcos/cloudc3/cloud/Stacks.kt").readText()
        val table = moduleFile("app/src/main/java/com/diegonmarcos/cloudc3/pages/RoutesTable.kt").readText()
        assertTrue("Topology must not read the old snapshot", !stacks.contains("BuildConfig.CLOUD_SERVICES_B64"))
        assertTrue("the Table must not read the old routes list", !table.contains("Declarations.routes"))
        assertTrue("Topology reads ContainersSource", stacks.contains("ContainersSource.current()"))
        assertTrue("Table reads ContainersSource", table.contains("ContainersSource.current()"))
    }

    @Test
    fun `no container is in two groups or two categories`() {
        val placed = catalog.groups.flatMap { g -> g.categories.flatMap { c -> c.names.map { it to "${g.label}/${c.label}" } } }
        val dup = placed.groupBy({ it.first }, { it.second }).filter { it.value.size > 1 }
        assertEquals("overlaps: $dup", emptyMap<String, List<String>>(), dup)
        assertEquals("every container is placed exactly once", catalog.names.sorted(), placed.map { it.first }.sorted())
    }

    @Test
    fun `the three groups and the infra categories come in the declared order`() {
        assertEquals(listOf("Infra Apps", "Cloud Apps", "Project Apps"), catalog.groups.map { it.label })
        assertEquals(M.INFRA_ORDER, catalog.groups.first { it.label == M.INFRA }.categories.map { it.label })
        assertEquals("Net-Routing & Security", M.INFRA_ORDER.first())
        assertEquals(listOf("Inboxes", "Tools Primary", "Data Apps", "Me", "AGI", "Projects W", "Configs"),
            catalog.groups.first { it.label == M.CLOUD }.categories.map { it.label })
    }

    @Test
    fun `every container carries its metadata`() {
        catalog.entries.forEach { e ->
            assertTrue("${e.name}: group", e.group.isNotBlank())
            assertTrue("${e.name}: category", e.category.isNotBlank())
            assertTrue("${e.name}: service", e.service.isNotBlank())
            assertTrue("${e.name}: description", e.description.isNotBlank())
            assertTrue("${e.name}: image", e.image.isNotBlank())
            assertTrue("${e.name}: host", e.host.isNotBlank())
            assertEquals("${e.name}: the More view shows every field", 18, e.fields().size)
        }
        val row = M.rows(catalog).first()
        assertEquals(catalog.entries.first().fields(), row.meta)
        assertEquals(catalog.entries.first().group, row.group)
    }

    @Test
    fun `an unsound document is refused and a sound one is parsed`() {
        assertEquals(null, M.parse("{}"))
        assertEquals(null, M.parse("not json"))
        val twice = M.Catalog(catalog.groups, catalog.entries + catalog.entries.first())
        assertTrue(M.problems(twice).any { it.contains("listed twice") })
        val noGroup = M.Catalog(catalog.groups, listOf(catalog.entries.first().copy(group = "")) + catalog.entries.drop(1))
        assertTrue(M.problems(noGroup).any { it.contains("no group") })
        val swapped = M.Catalog(catalog.groups.map { g -> if (g.label == M.INFRA) g.copy(categories = g.categories.reversed()) else g }, catalog.entries)
        assertTrue(M.problems(swapped).any { it.contains("categories are") })
    }
}
