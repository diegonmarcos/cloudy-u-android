package com.diegonmarcos.cgomniverse

import com.diegonmarcos.superapp.cgc.CgcSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Avatar editor's declaration (data/avatar.json) against what it drives: the owner's model (every target, blendshape and the
 * glasses it names exist in owner.glb), the lib's clips, the card thumbnails, the MetaPerson category order, the AI connector.
 * The rules themselves (configuration to scene ops, the OpenRouter mapping) are libs:cgc-3d's suite, run by this unit task too.
 */
class AvatarTest {
    private fun file(vararg c: String) = c.map { File(it) }.first { it.exists() }
    private val spec = Avatar.spec(file("../data/avatar.json", "data/avatar.json").readText())
    private val assets = file("src/main/assets/avatar", "app/src/main/assets/avatar")
    private val clips = JSONObject(file("../../ab_cloud-libs-shared/libs/cgc-3d/src/main/assets/cgc/clips.json",
        "../ab_cloud-libs-shared/libs/cgc-3d/src/main/assets/cgc/clips.json").readText()).getJSONArray("clips")

    /** owner.glb's JSON chunk: mesh names, material names, node names and every blendshape name (extras.targetNames). */
    private val glb: JSONObject by lazy {
        val bytes = File(assets, JSONObject(spec.scene).getJSONObject("model").getString("url")).readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("glTF", String(bytes, 0, 4))
        val len = b.getInt(12)
        JSONObject(String(bytes, 20, len, Charsets.UTF_8))
    }
    private fun names(key: String) = (0 until glb.getJSONArray(key).length()).map { glb.getJSONArray(key).getJSONObject(it).optString("name") }.toSet()
    private val morphs by lazy {
        val m = glb.getJSONArray("meshes")
        (0 until m.length()).flatMap { i ->
            val t = m.getJSONObject(i).optJSONObject("extras")?.optJSONArray("targetNames")
            if (t == null) emptyList() else (0 until t.length()).map { t.getString(it) }
        }.toSet()
    }

    @Test fun theCategoriesAreMetaPersonsInOrder() {
        assertEquals(listOf("outfit", "body", "skin", "features", "head", "hair", "glasses", "presets", "background", "animations"), spec.categories.map { it.id })
        val wired = spec.categories.filter { !Avatar.soon(spec, it.id) }.map { it.id }
        assertEquals(listOf("outfit", "skin", "features", "glasses", "background", "animations"), wired)
        assertEquals(listOf("solid", "stars", "gradient", "studio"), spec.category("background")!!.options.map { it.id })
        spec.categories.forEach { c -> assertTrue(c.id, c.options.isNotEmpty() && c.options.any { it.id == spec.defaults[c.id] }) }
    }

    @Test fun theDefaultAvatarIsTheOwnersModelAndItAnimates() {
        val model = JSONObject(spec.scene).getJSONObject("model")
        assertEquals("models/owner.glb", model.getString("url"))
        assertTrue("meshopt-compressed", glb.getJSONArray("extensionsRequired").toString().contains("EXT_meshopt_compression"))
        assertTrue("the rig is kept", glb.getJSONArray("skins").length() > 0 && names("nodes").containsAll(listOf("Hips", "Spine", "Head", "LeftArm", "RightUpLeg")))
        val clipIds = (0 until clips.length()).map { clips.getJSONObject(it).getString("id") }.toSet()
        assertEquals("idle", JSONObject(spec.scene).getJSONObject("clips").getString("default"))
        spec.category("animations")!!.options.forEach { assertTrue(it.id, it.clip in clipIds) }
    }

    @Test fun everyTargetBlendshapeAndThumbnailExists() {
        val targets = names("meshes") + names("materials") + names("nodes")
        spec.categories.filter { !Avatar.soon(spec, it.id) }.forEach { c ->
            c.targets.forEach { assertTrue("${c.id} target $it", it in targets) }
            c.options.forEach { o ->
                o.visible.keys.forEach { assertTrue("${c.id}/${o.id} shows $it", it in targets) }
                o.morphs.keys.forEach { assertTrue("${c.id}/${o.id} blendshape $it", it in morphs) }
                if (c.kind == CgcSpec.KIND_COLOR || c.kind == CgcSpec.KIND_TINT) assertTrue(o.id, o.color == null || o.color!!.matches(Regex("#[0-9a-f]{6}")))
            }
        }
        spec.categories.flatMap { it.options }.mapNotNull { it.thumb }.forEach { assertTrue(it, File(assets, it).isFile) }
    }

    @Test fun nothingSavedIsTheDefaultAndASaveRoundTrips() {
        assertEquals(spec.defaults, Avatar.load(spec, null))
        val cfg = Avatar.set(spec, Avatar.set(spec, spec.defaults, "outfit", "navy"), "glasses", "off")
        assertEquals(cfg, Avatar.load(spec, Avatar.save(cfg)))
        assertEquals("a coming-soon category keeps its default", spec.defaults, Avatar.set(spec, spec.defaults, "hair", "bun"))
        val ops = JSONObject(Avatar.sceneOps(spec, cfg))
        assertEquals("#22345e", ops.getJSONObject("colors").getString("outfit_top"))
        assertFalse(ops.getJSONObject("visible").getBoolean("glasses"))
        assertEquals("idle", ops.getString("clip"))
    }

    @Test fun theSceneConfigCarriesTheInsets() {
        val c = JSONObject(Avatar.sceneConfig(spec, 80, 300))
        assertEquals(300, c.getJSONObject("insets").getInt("bottom"))
        assertEquals("#121212", c.getString("background"))
    }

    @Test fun theAiConnectorIsDeclaredAndHoldsNoKey() {
        val ai = spec.ai!!
        assertEquals("openrouter", ai.id)
        assertTrue(ai.endpoint.startsWith("https://openrouter.ai/api/v1/"))
        assertTrue(ai.model.isNotBlank() && ai.keySlot.isNotBlank())
        val raw = file("../data/avatar.json", "data/avatar.json").readText()
        assertFalse("no key in the declaration", Regex("sk-or-[A-Za-z0-9]").containsMatchIn(raw))
    }
}
