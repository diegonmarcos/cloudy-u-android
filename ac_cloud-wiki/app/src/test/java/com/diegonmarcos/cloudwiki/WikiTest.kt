package com.diegonmarcos.cloudwiki

import com.diegonmarcos.superapp.graphview.GEdge
import com.diegonmarcos.superapp.graphview.GNode
import com.diegonmarcos.superapp.graphview.GraphBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The topic tree, the tags and the tag filter, the Markdown, against the declared pages and the real dataset. */
class WikiTest {
    private val d = Data.wiki
    private val topics = listOf(
        "anthropology", "math", "cs", "physics", "astronomy", "eng", "medicine", "beliefs", "chronos", "art", "theater", "music", "movies",
    )

    @Test fun thereIsNoBottomNavAndOneSideMenuWithFourGroups() {
        assertEquals("vertical_side_menu", Data.navMode())
        assertEquals(emptyList<String>(), Data.bottomNav())
        assertEquals(
            listOf(
                "sciences" to listOf("math", "cs", "physics", "astronomy", "eng", "medicine"),
                "humanities" to listOf("anthropology", "beliefs", "chronos"),
                "arts" to listOf("art", "theater", "music", "movies"),
                "explore" to listOf("graph"),
            ),
            Data.groups(),
        )
    }

    @Test fun theTopicTreeIsTheDeclaredTopicsPlusTheGraphAndEveryTopicHasSubpages() {
        assertEquals((topics + "graph").toSet(), Data.pages().toSet())
        assertEquals(topics.toSet(), d.topics.map { it.id }.toSet())
        for (t in d.topics) {
            assertTrue("${t.id} has subpages", t.pages.size >= 4)
            assertTrue("${t.id} has an intro", t.intro.length > 40)
            assertTrue("${t.id} page ids are prefixed by the topic", t.pages.all { it.id.startsWith(t.id + ".") && it.topic == t.id })
        }
    }

    @Test fun engineeringHasEachMajorFieldAsASubpage() {
        val eng = d.topic("eng")!!.pages.map { it.id.substringAfter('.') }.toSet()
        assertTrue(eng.containsAll(setOf("civil", "mechanical", "electrical", "chemical", "software", "aerospace", "materials", "biomedical", "environmental")))
    }

    @Test fun everyPageIsShortMarkdownWithTagsAndUniqueIds() {
        assertEquals(d.pages.size, d.pages.map { it.id }.toSet().size)
        for (p in d.pages) {
            assertTrue("${p.id} has tags", p.tags.isNotEmpty() && p.tags.all { it.isNotBlank() && it == it.lowercase() && ' ' !in it })
            assertTrue("${p.id} is a short explanation", p.md.length in 80..700)
            assertTrue("${p.id} has a title", p.title.isNotBlank())
        }
    }

    @Test fun everyLinkResolvesToAPage() {
        val ids = d.pages.map { it.id }.toSet()
        for (t in d.topics) for (l in Wiki.links(t.intro)) assertTrue("${t.id} intro links to unknown $l", l in ids)
        for (p in d.pages) for (l in p.links) assertTrue("${p.id} links to unknown $l", l in ids && l != p.id)
    }

    @Test fun linksAreReadFromMarkdownOncePerTarget() {
        assertEquals(listOf("a.b", "c.d"), Wiki.links("see [[a.b]] and [[c.d]] and [[a.b]] again"))
        assertEquals(emptyList<String>(), Wiki.links("no links, [single] brackets"))
    }

    @Test fun theTagCloudCountsPagesAndTheFilterRequiresEveryTag() {
        val cloud = Wiki.tagCloud(d.pages)
        assertTrue(cloud.zipWithNext().all { (a, b) -> a.second > b.second || (a.second == b.second && a.first <= b.first) })
        val top = cloud.first().first
        val some = Wiki.filter(d.pages, listOf(top))
        assertEquals(cloud.first().second, some.size)
        assertTrue(some.all { top in it.tags })
        assertEquals(d.pages, Wiki.filter(d.pages, emptyList()))
        val two = Wiki.filter(d.pages, listOf("history", "society"))
        assertTrue(two.isNotEmpty() && two.all { "history" in it.tags && "society" in it.tags })
        assertTrue(two.size < Wiki.filter(d.pages, listOf("history")).size)
        assertTrue(Wiki.filter(d.pages, listOf("history", "no-such-tag")).isEmpty())
    }

    @Test fun chipsKeepTheSelectedFirstAndOfferOnlyTagsThatStillMatch() {
        val chips = Wiki.chips(d.pages, listOf("history"))
        assertEquals("history", chips.first())
        assertEquals(chips.size, chips.toSet().size)
        val still = Wiki.filter(d.pages, listOf("history")).flatMap { it.tags }.toSet()
        assertTrue(chips.all { it in still })
        assertEquals(listOf("a", "b"), Wiki.toggle(listOf("a"), "b"))
        assertEquals(listOf("a"), Wiki.toggle(listOf("a", "b"), "b"))
    }

    @Test fun markdownHasHeadingsParagraphsBulletsAndInlineLinks() {
        val blocks = Md.parse("# Title\n\nOne **bold** and *soft* line\ncontinues.\n\n- item [[x.y]]\n- second")
        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is Md.Block.Heading)
        val para = blocks[1] as Md.Block.Para
        assertTrue(para.spans.contains(Md.Span.Text("bold", bold = true)) && para.spans.contains(Md.Span.Text("soft", italic = true)))
        assertEquals("One ", (para.spans.first() as Md.Span.Text).text)
        assertTrue((para.spans.last() as Md.Span.Text).text.endsWith("line continues."))
        assertEquals(Md.Span.Link("x.y"), (blocks[2] as Md.Block.Bullet).spans.last())
    }

    @Test fun anEmptyOrBrokenDatasetParsesToNoTopics() {
        assertTrue(Wiki.parse("not json").topics.isEmpty())
        assertTrue(Wiki.parse("{}").topics.isEmpty())
    }

    @Test fun graphEdgesComeFromLinksAndSharedTags() {
        val edges = Wiki.edges(d)
        val ids = d.pages.map { it.id }.toSet()
        assertTrue(edges.all { it.a in ids && it.b in ids && it.a < it.b })
        assertEquals("no pair twice", edges.size, edges.map { it.a to it.b }.toSet().size)
        // every link of every page is an edge
        for (p in d.pages) for (l in p.links) {
            val k = if (p.id < l) p.id to l else l to p.id
            assertEquals("${p.id} -> $l is a LINK edge", GEdge.Kind.LINK, edges.first { it.a == k.first && it.b == k.second }.kind)
        }
        // pages sharing a tag are joined (by a link or a tag edge)
        val a = d.page("math.logic")!!
        val b = d.page("cs.theory")!!
        assertTrue(a.tags.intersect(b.tags.toSet()).isNotEmpty())
        assertTrue(edges.any { it.a == "cs.theory" && it.b == "math.logic" })
        assertTrue(edges.any { it.kind == GEdge.Kind.TAG } && edges.any { it.kind == GEdge.Kind.LINK })
        // every page is reachable: no isolated node
        assertTrue(GraphBuilder.degrees(Wiki.nodes(d), edges).values.all { it > 0 })
    }

    @Test fun theGraphBuilderLinksTagsAndIgnoresWhatItShould() {
        val n = listOf(
            GNode("a", "A", tags = listOf("x", "y")), GNode("b", "B", tags = listOf("x", "y")),
            GNode("c", "C", tags = listOf("y")), GNode("d", "D", tags = emptyList()),
        )
        val e = GraphBuilder.build(n, mapOf("a" to listOf("d", "a", "zzz"), "d" to listOf("a"), "ghost" to listOf("a")))
        assertTrue("a-d link once, both directions", e.count { it.a == "a" && it.b == "d" } == 1)
        assertEquals(GEdge.Kind.LINK, e.first { it.a == "a" && it.b == "d" }.kind)
        assertFalse("no self link, no unknown target", e.any { it.a == it.b || it.a == "zzz" || it.b == "zzz" || it.a == "ghost" })
        assertEquals(2, e.first { it.a == "a" && it.b == "b" }.weight)
        assertEquals(1, e.first { it.a == "a" && it.b == "c" }.weight)
        assertEquals(GEdge.Kind.TAG, e.first { it.a == "b" && it.b == "c" }.kind)
        assertEquals("a link wins over a shared tag", GEdge.Kind.LINK, GraphBuilder.build(n, mapOf("a" to listOf("b"))).first { it.a == "a" && it.b == "b" }.kind)
        assertTrue("minShared 2 keeps only a-b", GraphBuilder.build(n, emptyMap(), minShared = 2).map { it.a to it.b } == listOf("a" to "b"))
        assertTrue("a tag held by too many pages relates nothing", GraphBuilder.build(n, emptyMap(), maxFanout = 2).none { it.b == "c" })
    }
}
