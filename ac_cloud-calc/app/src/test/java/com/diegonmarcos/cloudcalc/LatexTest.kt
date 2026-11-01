package com.diegonmarcos.cloudcalc

import com.diegonmarcos.cloudcalc.ui.LatexModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** #1329 the LaTeX page: its declaration, the owner's catalog, Evaluate's conversion, My formulas and the palette. Runs from app/. */
@RunWith(RobolectricTestRunner::class)
class LatexTest {
    private val build = JSONObject(File("../build.json").readText())
    private val cfg = Latex.parseConfig(build.getJSONObject("latex").toString())
    private val catalog = Latex.parseCatalog(File("../data/assets/" + cfg.catalog).readText())

    private fun expr(tex: String): Latex.Conversion = Latex.toExpression(tex, cfg.eval)
    private fun ok(tex: String) = (expr(tex) as? Latex.Conversion.Ok)?.expr ?: error("not converted: $tex -> ${expr(tex)}")
    private fun why(tex: String) = (expr(tex) as? Latex.Conversion.Unsupported)?.reason ?: error("converted: $tex -> ${expr(tex)}")

    // ── the declaration ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the latex declaration is what gradle baked`() {
        assertEquals(cfg, Latex.config)
    }

    @Test fun `the tab order is Calc, LaTeX, Convert and the page holds Library, Edit, then Graph`() {
        val pages = Declarations.tabsOf("calculator").map { it.id }
        assertEquals(listOf("calc", "latex", "convert", "history", "datetime", "sound", "finance", "graph"), pages)
        assertEquals(listOf("latex_library", "latex_editor", "latex_graph"), Declarations.modesOf("latex").map { it.id })
        assertEquals(listOf("latex_library", "latex_editor", "latex_graph"), Declarations.modesOf("latex").map { it.kind })
        assertEquals(listOf("Library", "Edit", "Graph"), Declarations.modesOf("latex").map { it.short })
        assertEquals("LaTeX", Declarations.tabs.first { it.id == "latex" }.label)
    }

    @Test fun `the palette and the renderer assets are declared`() {
        assertTrue(cfg.palette.size >= 3)
        cfg.palette.forEach { g -> assertTrue(g.id, g.symbols.isNotEmpty()) }
        assertTrue(cfg.palette.flatMap { it.symbols }.any { it.insert.startsWith("\\frac") })
        listOf(cfg.viewer, cfg.script, cfg.autoRender, cfg.style).forEach { assertTrue(it, File("../data/assets/$it").isFile) }
        assertTrue(File("../data/assets/${cfg.fontsDir}").listFiles()!!.any { it.name.endsWith(".woff2") })
    }

    // ── the catalog ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `the catalog is the owner's 100 formulas in 9 categories in his order, then the pi category`() {
        assertEquals(125, catalog.formulas.size)
        assertEquals(10, catalog.categories.size)
        assertEquals("Pure Mathematics, Geometry & Number Theory", catalog.categories.first().label)
        assertEquals("Fluid Dynamics, Chemistry, Cosmology & Nonlinear Systems", catalog.categories[8].label)
        assertEquals("π (Pi)", catalog.categories.last().label)
        assertEquals((1..125).toList(), catalog.formulas.map { it.number })
        assertEquals(catalog.categories.map { it.id }, catalog.formulas.map { it.category }.distinct())
        assertEquals("1. The Pythagorean Theorem", catalog.formulas.first().heading)
    }

    @Test fun `every entry has LaTeX, a category and a description, and ids are unique`() {
        assertEquals(emptyList<String>(), Latex.problems(catalog))
        assertEquals(catalog.formulas.size, catalog.formulas.map { it.id }.toSet().size)
        catalog.formulas.forEach { f ->
            assertTrue(f.id, f.tex.isNotBlank() && f.category.isNotBlank() && f.description.isNotBlank())
            assertTrue(f.id, catalog.categories.any { it.id == f.category })
        }
    }

    @Test fun `problems names a blank formula, an unknown category and a duplicate id`() {
        val f = catalog.formulas.first()
        assertTrue(Latex.problems(catalog.copy(formulas = listOf(f.copy(tex = " ")))).any { it.contains("empty LaTeX") })
        assertTrue(Latex.problems(catalog.copy(formulas = listOf(f.copy(category = "zz")))).any { it.contains("unknown category") })
        assertTrue(Latex.problems(catalog.copy(formulas = listOf(f, f))).any { it.contains("duplicate") })
    }

    @Test fun `the chips are All, Favourites, the categories, then Mine`() {
        val chips = Latex.chips(catalog, cfg)
        assertEquals(listOf("All", "Favourites"), chips.take(2).map { it.label })
        assertEquals(catalog.categories, chips.subList(2, 12))
        assertEquals("π (Pi)", chips[chips.size - 2].label)
        assertEquals("Mine", chips.last().label)
        assertEquals(13, chips.size)
    }

    @Test fun `search and chips filter the library`() {
        val none = emptyList<Latex.Formula>()
        assertEquals(125, Latex.filter(catalog, none, emptySet(), "", "", cfg).size)
        val cat = catalog.categories[3]
        assertEquals(catalog.formulas.count { it.category == cat.id }, Latex.filter(catalog, none, emptySet(), cat.id, "", cfg).size)
        val hits = Latex.filter(catalog, none, emptySet(), "", "pythagorean", cfg)
        assertTrue(hits.any { it.title == "The Pythagorean Theorem" })
        assertTrue(Latex.filter(catalog, none, emptySet(), "", "PYTHAGOREAN theorem", cfg).isNotEmpty())
        assertEquals(emptyList<Latex.Formula>(), Latex.filter(catalog, none, emptySet(), "", "zzzzqq", cfg))
        val fav = catalog.formulas[4].id
        assertEquals(listOf(fav), Latex.filter(catalog, none, setOf(fav), cfg.favourites.id, "", cfg).map { it.id })
        val mine = Latex.Formula("mine-1", 0, "Mine one", cfg.mine.id, "x^2", "", mine = true)
        assertEquals(listOf("mine-1"), Latex.filter(catalog, listOf(mine), emptySet(), cfg.mine.id, "", cfg).map { it.id })
        assertTrue(Latex.filter(catalog, listOf(mine), emptySet(), "", "mine one", cfg).any { it.id == "mine-1" })
    }

    // ── Evaluate ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `numeric LaTeX becomes the engine's syntax`() {
        assertEquals("((1)/(2))", ok("\\frac{1}{2}"))
        assertEquals("((1)/(2))", ok("\\frac12"))
        assertEquals("sqrt(16)", ok("\\sqrt{16}"))
        assertEquals("root(27, 3)", ok("\\sqrt[3]{27}"))
        assertEquals("3^(2)*4", ok("3^{2} \\cdot 4"))
        assertEquals("2^(10)", ok("2^{10}"))
        assertEquals("2^(3)", ok("2^3"))
        assertEquals("2 pi", ok("2\\pi"))
        assertEquals("(1+2)*(3-1)/4", ok("\\left(1+2\\right)\\times(3-1)\\div 4"))
        assertEquals("sin(pi/2)", ok("\\sin(\\pi/2)"))
        assertEquals("ln(e)", ok("\\ln(e)"))
        assertEquals("5!", ok("5!"))
        assertEquals("2+2", ok("2+2="))
        assertEquals("((sqrt(9))/(3))+1", ok("\\frac{\\sqrt{9}}{3}+1"))
        assertEquals("12.5", ok("12.5"))
    }

    @Test fun `what cannot be a number is reported, not guessed`() {
        assertTrue(why("x + 1").contains("variable x"))
        assertTrue(why("a_1 + 2").isNotEmpty())
        assertTrue(why("\\sum_{k=1}^{n} k").isNotEmpty())
        assertTrue(why("\\int_0^1 x\\,dx").isNotEmpty())
        assertTrue(why("x = 3 + 1").isNotEmpty())
        assertTrue(why("\\foo{1}").contains("\\foo"))
        assertTrue(why("\\frac{1}{2").contains("unbalanced"))
        assertTrue(why("1 + 2 }").contains("unbalanced"))
        assertTrue(why("").contains("nothing"))
        assertTrue(why("\\frac{1}").contains("missing"))
        assertTrue(why("a < b").isNotEmpty())
    }

    // ── My formulas and favourites ──────────────────────────────────────────────────────────────────────────────

    @Test fun `my formulas round-trip through their stored JSON`() {
        val mine = listOf(
            Latex.Formula("mine-2", 0, "Second \"quoted\"", cfg.mine.id, "\\frac{a}{b}\\\\ \\text{é}", "", mine = true),
            Latex.Formula("mine-1", 0, "First", cfg.mine.id, "x^{2}", "note", mine = true),
        )
        assertEquals(mine, Latex.decodeMine(Latex.encodeMine(mine), cfg))
        assertEquals(emptyList<Latex.Formula>(), Latex.decodeMine("not json", cfg))
        assertEquals(emptyList<Latex.Formula>(), Latex.decodeMine(null, cfg))
        assertEquals(setOf("a", "b"), Latex.decodeFavourites(Latex.encodeFavourites(setOf("b", "a"))))
    }

    @Test fun `the model saves, edits, deletes and favourites, and a new model reads it back`() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("latex-test", 0)
        prefs.edit().clear().commit()
        var t = 1000L
        val m = LatexModel(prefs, { t++ }, cfg)
        val a = m.saveMine("Area", "\\pi r^{2}")
        val b = m.saveMine("", "E = mc^{2}")
        assertEquals("E = mc^{2}", b.title)
        assertNotEquals(a.id, b.id)
        assertEquals(listOf(b.id, a.id), m.mine.map { it.id })
        // editing a saved formula replaces it in place
        val a2 = m.saveMine("Circle area", "A = \\pi r^{2}", a.id)
        assertEquals(a.id, a2.id)
        assertEquals(2, m.mine.size)
        m.toggleFavourite(a.id)
        assertTrue(m.isFavourite(a.id))
        val again = LatexModel(prefs, { t++ }, cfg)
        assertEquals(m.mine.toList(), again.mine.toList())
        assertEquals("Circle area", again.mine.first { it.id == a.id }.title)
        assertTrue(again.isFavourite(a.id))
        again.deleteMine(a.id)
        assertFalse(again.isFavourite(a.id))
        assertEquals(listOf(b.id), LatexModel(prefs, { t++ }, cfg).mine.map { it.id })
    }

    @Test fun `a new id never collides`() {
        assertEquals("mine-5", Latex.newMineId(emptyList(), 5))
        assertEquals("mine-6", Latex.newMineId(listOf("mine-5"), 5))
    }

    // ── History and the palette ─────────────────────────────────────────────────────────────────────────────────

    @Test fun `a latex history entry keeps its kind through the stored JSON`() {
        val e = Latex.entry("latex_editor", "\\frac{1}{2}", "0.5", cfg)
        assertTrue(Latex.isLatexEntry(e, cfg))
        val back = Logic.decode(Logic.encode(listOf(e, Logic.Entry("standard", "2+2", "4"))))
        assertTrue(Latex.isLatexEntry(back[0], cfg))
        assertFalse(Latex.isLatexEntry(back[1], cfg))
        assertEquals("", back[1].kind)
    }

    @Test fun `the palette inserts at the cursor and wraps a selection`() {
        val frac = "\\frac{\$0}{}"
        assertEquals(Latex.Edited("\\frac{}{}", 6), Latex.insertAt("", 0, 0, frac))
        assertEquals(Latex.Edited("a+\\frac{}{}b", 8), Latex.insertAt("a+b", 2, 2, frac))
        assertEquals(Latex.Edited("\\frac{xy}{}", 8), Latex.insertAt("xy", 0, 2, frac))
        assertEquals(Latex.Edited("x\\pi ", 5), Latex.insertAt("x", 1, 1, "\\pi "))
        assertEquals(Latex.Edited("ab\\alpha ", 9), Latex.insertAt("ab", 99, 99, "\\alpha "))
    }
}
