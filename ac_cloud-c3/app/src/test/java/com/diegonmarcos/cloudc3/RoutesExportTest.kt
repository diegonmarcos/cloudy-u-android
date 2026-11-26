package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ProbeModel
import com.diegonmarcos.cloudc3.pages.RoutesExport as X
import com.diegonmarcos.cloudc3.pages.RoutesTableModel as M
import org.junit.Assert.assertEquals
import org.junit.Test

/** Containers > Table export: CSV escaping, TSV / Markdown, and that exactly the visible rows are exported. */
class RoutesExportTest {

    private fun row(name: String, auth: String = "woauth", vm: String = "vm-a", pub: String = "$name.example.com", cat: String = "app") =
        M.Row(cat, name, pub, auth, "$name.app:80", vm, "10.0.0.6:80")

    @Test
    fun `csv fields are quoted only when needed and quotes are doubled`() {
        assertEquals("plain", X.csvField("plain"))
        assertEquals("\"a,b\"", X.csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", X.csvField("say \"hi\""))
        assertEquals("\"l1\nl2\"", X.csvField("l1\nl2"))
        assertEquals("\"l1\rl2\"", X.csvField("l1\rl2"))
        assertEquals("\" pad\"", X.csvField(" pad"))
        assertEquals("", X.csvField(""))
        assertEquals("a,\"b,c\",\"d\"\"\"\r\n", X.csv(listOf(listOf("a", "b,c", "d\""))))
    }

    @Test
    fun `tsv and markdown format header and cells`() {
        val g = listOf(listOf("A", "B"), listOf("x\ty", "p|q"), listOf("l1\nl2", ""))
        assertEquals("A\tB\nx y\tp|q\nl1 l2\t", X.tsv(g))
        assertEquals("| A | B |\n| --- | --- |\n| x\ty | p\\|q |\n| l1 l2 |  |", X.markdown(g))
        assertEquals("", X.markdown(emptyList()))
    }

    @Test
    fun `export is exactly the visible rows and columns under filter and sort`() {
        val rows = listOf(
            row("charlie", "bearer", "vm-b"), row("alpha", "woauth", "vm-a"),
            row("bravo", "mesh", "vm-a", pub = ""), row("delta", "bearer", "vm-a"),
        )
        val state = M.State(sort = M.Column.CONTAINER, ascending = false, auth = setOf("bearer"))
        val shown = M.view(rows, state)
        val g = X.grid(shown) { ProbeModel.Light.GREEN }
        assertEquals(listOf("Status", "Project", "Cat1", "Cat2", "Type", "Container", "Public URL", "Auth Type", "Private URL", "VM Name", "VM IP:PORT"), g.first())
        assertEquals(listOf("delta", "charlie"), g.drop(1).map { it[5] })
        assertEquals(List(2) { "Green" }, g.drop(1).map { it[0] })
        assertEquals(listOf("Bearer", "Bearer"), g.drop(1).map { it[7] })
        assertEquals(3, X.tsv(g).lines().size)
    }
}
