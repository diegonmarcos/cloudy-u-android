package com.diegonmarcos.cloudme

import com.diegonmarcos.superapp.jsoncanvas.Canvas
import com.diegonmarcos.superapp.jsoncanvas.CanvasColors
import com.diegonmarcos.superapp.jsoncanvas.CanvasEdge
import com.diegonmarcos.superapp.jsoncanvas.CanvasError
import com.diegonmarcos.superapp.jsoncanvas.CanvasGeometry
import com.diegonmarcos.superapp.jsoncanvas.CanvasNode
import com.diegonmarcos.superapp.jsoncanvas.CanvasParser
import com.diegonmarcos.superapp.jsoncanvas.CanvasResult
import com.diegonmarcos.superapp.jsoncanvas.EndShape
import com.diegonmarcos.superapp.jsoncanvas.MarkdownLite
import com.diegonmarcos.superapp.jsoncanvas.MdBlock
import com.diegonmarcos.superapp.jsoncanvas.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1932 libs:json-canvas on the JVM (pure Kotlin, no Android): the JSON Canvas 1.0 parser, the geometry and Markdown-light. */
class JsonCanvasTest {
    private fun ok(text: String): Canvas = (CanvasParser.parse(text) as? CanvasResult.Ok)?.canvas ?: throw AssertionError("expected a canvas for: $text")
    private fun err(text: String): CanvasError = (CanvasParser.parse(text) as? CanvasResult.Err)?.error ?: throw AssertionError("expected an error for: $text")
    private val spec by lazy { ok(BuroLlcTestData.text("test/fixtures/json-canvas-spec-1932.canvas")) }

    @Test fun `every node type parses with its fields`() {
        assertEquals(5, spec.nodes.size)
        val g = spec.node("g1") as CanvasNode.Group; assertEquals("Hub", g.label); assertEquals("4", g.color); assertEquals(-40.0, g.x, 0.0)
        val t = spec.node("t1") as CanvasNode.Text; assertTrue(t.text.startsWith("# Title")); assertEquals(260.0, t.width, 0.0)
        val f = spec.node("f1") as CanvasNode.File; assertEquals("docs/README.md", f.file); assertEquals("#intro", f.subpath); assertEquals("#ff8800", f.color)
        val l = spec.node("l1") as CanvasNode.Link; assertEquals("https://github.com/diegonmarcos/cloudy-u-android", l.url)
        assertTrue(spec.node("t2") is CanvasNode.Text)
    }

    @Test fun `edge options parse and default to none then arrow`() {
        val e1 = spec.edges.first { it.id == "e1" }
        assertEquals(Side.Right, e1.fromSide); assertEquals(Side.Left, e1.toSide); assertEquals("reads", e1.label); assertEquals("5", e1.color)
        assertEquals(EndShape.None, e1.fromEnd); assertEquals(EndShape.Arrow, e1.toEnd)
        val e2 = spec.edges.first { it.id == "e2" }
        assertNull(e2.fromSide); assertEquals(EndShape.Arrow, e2.fromEnd); assertEquals(EndShape.None, e2.toEnd)
    }

    @Test fun `unknown fields are ignored`() {
        val c = ok("""{"nodes":[{"id":"a","type":"text","x":0,"y":0,"width":1,"height":1,"text":"t","zzz":[1,2,{"q":null}]}],"edges":[],"extra":"x"}""")
        assertEquals(1, c.nodes.size)
    }

    @Test fun `missing nodes and edges mean an empty canvas`() { assertEquals(0, ok("{}").nodes.size) }

    @Test fun `malformed input is a typed error, never an exception`() {
        assertTrue(err("") is CanvasError.Syntax)
        assertTrue(err("{") is CanvasError.Syntax)
        assertTrue(err("""{"nodes":[}""") is CanvasError.Syntax)
        assertTrue(err("[]") is CanvasError.NotAnObject)
        assertTrue(err("\"x\"") is CanvasError.NotAnObject)
        assertTrue(err("""{"nodes":{}}""") is CanvasError.BadField)
        assertTrue(err("""{"nodes":[1]}""") is CanvasError.BadField)
        assertTrue(err("""{"nodes":[{"id":"a","type":"video","x":0,"y":0,"width":1,"height":1}]}""") is CanvasError.UnknownNodeType)
        assertTrue(err("""{"nodes":[{"id":"a","type":"text","x":"0","y":0,"width":1,"height":1,"text":"t"}]}""") is CanvasError.BadField)
        assertTrue(err("""{"nodes":[{"id":"a","type":"text","x":0,"y":0,"width":1,"height":1}]}""") is CanvasError.BadField)
        assertTrue(err("""{"nodes":[{"id":"a","type":"text","x":0,"y":0,"width":1,"height":1,"text":"t","color":"9"}]}""") is CanvasError.BadField)
        val n = """{"id":"a","type":"text","x":0,"y":0,"width":1,"height":1,"text":"t"}"""
        assertTrue(err("""{"nodes":[$n,$n]}""") is CanvasError.DuplicateId)
        assertTrue(err("""{"nodes":[$n],"edges":[{"id":"e","fromNode":"a","toNode":"zz"}]}""") is CanvasError.DanglingEdge)
        assertTrue(err("""{"nodes":[$n],"edges":[{"id":"e","fromNode":"a","toNode":"a","fromSide":"middle"}]}""") is CanvasError.BadField)
        assertTrue(err("""{"nodes":[$n],"edges":[{"id":"e","fromNode":"a","toNode":"a","toEnd":"dot"}]}""") is CanvasError.BadField)
        assertTrue(err("[" .repeat(200)) is CanvasError.Syntax)   // nesting guard
    }

    @Test fun `colours resolve presets and hex`() {
        assertEquals(0xFFFB464CL, CanvasColors.resolve("1")); assertEquals(0xFFA882FFL, CanvasColors.resolve("6"))
        assertEquals(0xFFFF8800L, CanvasColors.resolve("#ff8800")); assertEquals(0xFFAABBCCL, CanvasColors.resolve("#abc"))
        assertNull(CanvasColors.resolve("7")); assertNull(CanvasColors.resolve("#12")); assertNull(CanvasColors.resolve(null))
    }

    @Test fun `bounds cover every node and an empty canvas has none`() {
        val b = CanvasGeometry.bounds(spec)!!
        assertEquals(-40.0, b.minX, 0.0); assertEquals(-40.0, b.minY, 0.0); assertEquals(720.0, b.maxX, 0.0); assertEquals(380.0, b.maxY, 0.0)
        assertNull(CanvasGeometry.bounds(ok("{}")))
    }

    @Test fun `fit shows the whole canvas centred inside the view`() {
        val b = CanvasGeometry.bounds(spec)!!
        val t = CanvasGeometry.fit(b, 400.0, 800.0, padding = 20.0)
        assertEquals(360.0 / 760.0, t.scale, 1e-9)                       // width is the limit
        val tl = t.toScreen(com.diegonmarcos.superapp.jsoncanvas.Pt(b.minX, b.minY)); val br = t.toScreen(com.diegonmarcos.superapp.jsoncanvas.Pt(b.maxX, b.maxY))
        assertEquals(20.0, tl.x, 1e-6); assertEquals(380.0, br.x, 1e-6)
        assertEquals(800.0, tl.y + br.y, 1e-6)                            // centred vertically
        val tall = CanvasGeometry.fit(b, 2000.0, 100.0, padding = 10.0)  // height is the limit
        assertEquals(80.0 / 420.0, tall.scale, 1e-9)
        assertEquals(1.0, CanvasGeometry.fit(null, 100.0, 100.0).scale, 0.0)
        assertTrue(CanvasGeometry.fit(com.diegonmarcos.superapp.jsoncanvas.Bounds(0.0, 0.0, 1.0, 1.0), 1000.0, 1000.0).scale <= 1.5)   // a tiny canvas is not blown up
    }

    @Test fun `zoom keeps the point under the finger fixed and is clamped`() {
        val t0 = com.diegonmarcos.superapp.jsoncanvas.ViewTransform(1.0, 10.0, 20.0)
        val before = t0.toWorld(150.0, 90.0); val t1 = CanvasGeometry.zoom(t0, 2.0, 150.0, 90.0); val after = t1.toWorld(150.0, 90.0)
        assertEquals(before.x, after.x, 1e-9); assertEquals(before.y, after.y, 1e-9); assertEquals(2.0, t1.scale, 0.0)
        assertEquals(CanvasGeometry.MAX_SCALE, CanvasGeometry.zoom(t0, 1000.0, 0.0, 0.0).scale, 0.0)
        assertEquals(CanvasGeometry.MIN_SCALE, CanvasGeometry.zoom(t0, 0.0001, 0.0, 0.0).scale, 0.0)
    }

    @Test fun `hit test returns the topmost node, a group only on empty group space, null outside`() {
        assertEquals("t1", CanvasGeometry.hitTest(spec, 10.0, 10.0)?.id)       // text node over its group
        assertEquals("g1", CanvasGeometry.hitTest(spec, -20.0, -20.0)?.id)     // group margin
        assertEquals("f1", CanvasGeometry.hitTest(spec, 400.0, 50.0)?.id)
        assertNull(CanvasGeometry.hitTest(spec, 5000.0, 5000.0))
        assertNull(CanvasGeometry.hitTest(ok("{}"), 0.0, 0.0))
    }

    @Test fun `groups are drawn behind their children, largest first`() {
        val order = CanvasGeometry.drawOrder(spec).map { it.id }
        assertEquals("g1", order.first()); assertEquals(listOf("t1", "f1", "l1", "t2"), order.drop(1))
    }

    @Test fun `edge anchors, auto sides and curve ends`() {
        val a = spec.node("t1")!!; val b = spec.node("f1")!!
        assertEquals(com.diegonmarcos.superapp.jsoncanvas.Pt(260.0, 60.0), CanvasGeometry.anchor(a, Side.Right))
        assertEquals(com.diegonmarcos.superapp.jsoncanvas.Pt(130.0, 0.0), CanvasGeometry.anchor(a, Side.Top))
        assertEquals(Side.Right to Side.Left, CanvasGeometry.autoSide(a, b))
        assertEquals(Side.Bottom to Side.Top, CanvasGeometry.autoSide(a, spec.node("l1")!!))
        val c = CanvasGeometry.curve(spec, spec.edges.first { it.id == "e1" })!!
        assertEquals(CanvasGeometry.anchor(a, Side.Right), c.p0); assertEquals(CanvasGeometry.anchor(b, Side.Left), c.p3)
        assertEquals(c.p0, c.at(0.0)); assertEquals(c.p3, c.at(1.0))
        assertEquals(1.0, c.endDir.x, 1e-9)                                     // arrives travelling right
        assertNull(CanvasGeometry.curve(Canvas(emptyList(), emptyList()), CanvasEdge("e", "x", "y", null, null, EndShape.None, EndShape.Arrow, null, null)))
    }

    @Test fun `markdown-light handles headings, bold, links, lists`() {
        val b = MarkdownLite.parse("# Title\n\n**bold** and [a link](https://example.com) plus https://x.y/z\n\n- one\n- two\n\n1. first\n\n```\ncode\n```\n---")
        assertTrue(b[0] is MdBlock.Heading && (b[0] as MdBlock.Heading).level == 1)
        val p = (b[1] as MdBlock.Para).spans
        assertTrue(p.any { it.bold && it.text == "bold" }); assertTrue(p.any { it.link == "https://example.com" && it.text == "a link" }); assertTrue(p.any { it.link == "https://x.y/z" })
        assertEquals(2, b.count { it is MdBlock.Item && !it.ordered }); assertTrue(b.any { it is MdBlock.Item && it.ordered })
        assertTrue(b.any { it is MdBlock.Code && it.text == "code" }); assertTrue(b.last() === MdBlock.Rule)
        assertNotNull(MarkdownLite.parse("\u0000 ** [ ( ` unbalanced"))
    }
}
