package com.diegonmarcos.hardtechbiomed

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * #1777 the served content of Hardtech-BioMed. Part 1: the parser and the page calls on a small document. Part 2: the REAL files of
 * data/fleet/biomed (found from the repo root): every model's sha256 and size is the one the content declares, every tab's model exists,
 * every topic names at least one part the model really has (so no chip lights nothing), the seven tabs of the nav are all there, and
 * every body system the Anatomy layers name has parts.
 */
class BioContentTest {
    private val sha = "a".repeat(64)
    private val doc = """{"schemaVersion":1,"notice":"n","models":{"m":{"file":"m.glb","sha256":"$sha","size":5,"licence":"CC0-1.0","credit":"c"},
        "bad":{"file":"b.glb","sha256":"short"}},
        "tabs":{"t":{"model":"m","title":"T","intro":"i","hidden":["system:x"],"layers":[{"id":"l","label":"L","match":["system:x"]}],
          "groups":[{"id":"g","label":"G","topics":[{"id":"a","title":"A","text":"x\ny","highlight":["heart","system:s"],"isolate":true},{"id":"","title":"no id"}]}]},
        "orphan":{"model":"bad","title":"O"}}}"""

    @Test fun parsesModelsTabsGroupsTopicsAndLayers() {
        val c = BioParse.parse(doc)!!
        assertEquals(setOf("m"), c.models.keys)   // a model with a short sha is dropped
        assertEquals(setOf("t"), c.tabs.keys)     // a tab whose model is not declared is dropped
        val t = c.tabs.getValue("t")
        assertEquals(listOf("a"), BioParse.topicIds(t))   // a topic without an id is dropped
        assertEquals(listOf("heart", "system:s"), t.groups[0].topics[0].highlight)
        assertTrue(t.groups[0].topics[0].isolate)
        assertEquals(listOf("system:x"), t.hidden)
        assertEquals("system:x", t.layers[0].match[0])
        assertEquals("n", c.notice)
    }

    @Test fun anotherSchemaOrBrokenJsonIsNull() {
        assertNull(BioParse.parse("""{"schemaVersion":2,"models":{},"tabs":{}}"""))
        assertNull(BioParse.parse("not json"))
        assertNull(BioParse.parse("""{"schemaVersion":1}"""))
    }

    @Test fun pageCallsCarryTheSpecAndTheMode() {
        assertEquals("window.cadHighlight&&window.cadHighlight([\"a\",\"system:b\"],'dim')", BioParse.highlightJs(listOf("a", "system:b"), false))
        assertTrue(BioParse.highlightJs(listOf("a"), true).endsWith(",'isolate')"))
        assertEquals("window.cadHighlight&&window.cadHighlight([],'dim')", BioParse.highlightJs(emptyList(), false))
        assertEquals("window.cadFocus&&window.cadFocus([\"heart\"])", BioParse.focusJs(listOf("heart")))
        assertEquals("window.cadShow&&window.cadShow([\"system:muscular\"],false)", BioParse.showJs(listOf("system:muscular"), false))
    }

    // ---- the real files ------------------------------------------------------------------------------------------------------------
    private fun dataDir(): File? {
        var d: File? = File("").absoluteFile
        while (d != null) { File(d, "data/fleet/biomed/content.json").takeIf { it.isFile }?.let { return it.parentFile }; d = d.parentFile }
        return null
    }

    private fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    /** The node names and body systems of a GLB (its JSON chunk). */
    private fun nodes(f: File): List<Pair<String, String>> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, b.getInt(0))
        val len = b.getInt(12)
        val json = JSONObject(String(f.readBytes(), 20, len, Charsets.UTF_8))
        val arr = json.getJSONArray("nodes")
        return (0 until arr.length()).map { i -> val n = arr.getJSONObject(i); n.optString("name") to (n.optJSONObject("extras")?.optString("system") ?: "") }
    }

    private fun hits(spec: String, nodes: List<Pair<String, String>>): Int =
        if (spec.startsWith("system:")) nodes.count { it.second == spec.removePrefix("system:") } else nodes.count { it.first.contains(spec) }

    @Test fun theServedFilesMatchTheirDeclaredHashAndEveryTopicLightsPartsOfItsModel() {
        val dir = dataDir() ?: return   // run from a checkout without data/fleet (a partial copy): the shell tester holds this case
        val c = BioParse.parse(File(dir, "content.json").readText())
        assertNotNull(c)
        c!!
        val nodeMap = c.models.mapValues { (_, m) ->
            val f = File(dir, m.file)
            assertTrue(m.file + " is served", f.isFile)
            assertEquals(m.file + " sha256", m.sha256, sha256(f))
            assertEquals(m.file + " size", m.size, f.length())
            assertTrue(m.file + " names its licence", m.licence.isNotBlank() && m.credit.isNotBlank())
            nodes(f)
        }
        assertEquals(setOf("physiology", "biochem", "biomech", "anatomy", "genetic", "neuro", "immuno"), c.tabs.keys)
        val seen = HashSet<String>()
        for (t in c.tabs.values) {
            val ns = nodeMap.getValue(t.model)
            assertTrue(t.id + " has topics", BioParse.topicIds(t).isNotEmpty())
            for (g in t.groups) for (tp in g.topics) {
                assertTrue("duplicate topic id " + tp.id, seen.add(t.id + "/" + tp.id))
                assertTrue(t.id + "/" + tp.id + " has a text", tp.text.length >= 30)
                for (s in tp.highlight) assertTrue(t.id + "/" + tp.id + ": '" + s + "' matches no part of " + t.model, hits(s, ns) > 0)
            }
            for (l in t.layers) for (s in l.match) assertTrue(t.id + " layer " + l.id + ": '" + s + "' matches no part", hits(s, ns) > 0)
            for (h in t.hidden) assertTrue(t.id + " hidden '" + h + "' matches no part", hits(h, ns) > 0)
        }
    }

    @Test fun theBrainHasEveryMacroRegionTheNeuroTabNames() {
        val dir = dataDir() ?: return
        val ns = nodes(File(dir, "brain.glb")).map { it.first }
        for (r in listOf("frontal_lobe", "parietal_lobe", "temporal_lobe", "occipital_lobe", "limbic_lobe", "insula", "cerebellum", "midbrain", "pons", "medulla", "thalamus", "hypothalamus", "hippocampus", "amygdala", "basal_ganglia", "corpus_callosum", "ventricles"))
            assertTrue("brain.glb has $r", ns.any { it.contains(r) })
        assertFalse(ns.isEmpty())
    }
}
