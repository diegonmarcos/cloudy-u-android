package cld.camera

import com.diegonmarcos.superapp.vision.ColorLut
import com.diegonmarcos.superapp.vision.FilterAnchors
import com.diegonmarcos.superapp.vision.FilterCatalog
import com.diegonmarcos.superapp.vision.FilterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The declared filters load from data (assets/vr-filters.json), validated; the colour LUT applies. */
class FilterCatalogTest {
    private val real by lazy { FilterCatalog.parse(File("src/main/assets/vr-filters.json").readText()) }

    @Test fun theDeclaredFiltersLoad() {
        assertTrue(real.size >= 10)
        assertEquals("ids are unique", real.size, real.map { it.id }.toSet().size)
    }

    @Test fun everyDeclaredKindHasFilters() {
        for (k in FilterKind.entries) assertTrue("no $k filter is declared", real.any { it.kind == k })
    }

    @Test fun hatsAreOnTheHeadAndGlassesOnTheEyes() {
        real.filter { it.kind == FilterKind.HAT }.forEach { assertEquals(it.id, "head_top", it.anchor!!.landmark) }
        real.filter { it.kind == FilterKind.GLASSES }.forEach { assertEquals(it.id, "eyes_center", it.anchor!!.landmark) }
        real.filter { it.kind == FilterKind.GLASSES }.forEach { assertTrue(it.id, it.anchor!!.rotate) }
    }

    @Test fun overlaysDeclareAnchorAndArtAndStickersFollowObjects() {
        for (f in real.filter { it.kind in setOf(FilterKind.HAT, FilterKind.GLASSES, FilterKind.MASK, FilterKind.STICKER) }) {
            val anchor = f.anchor
            val art = f.art
            assertNotNull("${f.id} anchor", anchor); assertNotNull("${f.id} art", art)
            assertTrue("${f.id} scale", anchor!!.scale > 0f)
            assertTrue("${f.id} art is vector or png", art!!.paths.isNotEmpty() || art.png != null)
            val valid = if (f.kind == FilterKind.STICKER) FilterAnchors.OBJECT_LANDMARKS else FilterAnchors.FACE_LANDMARKS
            assertTrue("${f.id} landmark ${anchor.landmark}", anchor.landmark in valid)
        }
    }

    @Test fun backgroundsAndLooksCarryTheirBlock() {
        real.filter { it.kind == FilterKind.BACKGROUND }.forEach { assertNotNull(it.id, it.background) }
        real.filter { it.kind == FilterKind.LUT }.forEach { assertNotNull(it.id, it.lut) }
        assertTrue(real.any { it.kind == FilterKind.BACKGROUND && it.background!!.mode == "blur" })
        assertTrue(real.any { it.kind == FilterKind.BACKGROUND && it.background!!.mode != "blur" })
    }

    @Test fun colours() {
        assertEquals(0xFFE91E63.toInt(), FilterCatalog.color("#E91E63"))
        assertEquals(0x33112233, FilterCatalog.color("#33112233"))
    }

    private fun rejects(why: String, json: String) {
        try { FilterCatalog.parse(json); fail("accepted: $why") } catch (e: IllegalArgumentException) { }
    }

    private fun filterJson(body: String) = """{"filters":[$body]}"""
    private val art = """"art":{"viewport":[10,10],"paths":[{"d":"M0,0 L10,10 Z","fill":"#FF0000"}]}"""

    @Test fun invalidDeclarationsAreRejected() {
        rejects("an unknown kind", filterJson("""{"id":"a","kind":"cape","anchor":{"landmark":"head_top","scale":1},$art}"""))
        rejects("an unknown face landmark", filterJson("""{"id":"a","kind":"hat","anchor":{"landmark":"elbow","scale":1},$art}"""))
        rejects("a sticker on a face landmark", filterJson("""{"id":"a","kind":"sticker","anchor":{"landmark":"head_top","scale":1},$art}"""))
        rejects("a hat without an anchor", filterJson("""{"id":"a","kind":"hat",$art}"""))
        rejects("a hat without art", filterJson("""{"id":"a","kind":"hat","anchor":{"landmark":"head_top","scale":1}}"""))
        rejects("a zero scale", filterJson("""{"id":"a","kind":"hat","anchor":{"landmark":"head_top","scale":0},$art}"""))
        rejects("an unknown unit", filterJson("""{"id":"a","kind":"hat","anchor":{"landmark":"head_top","scale":1,"unit":"cm"},$art}"""))
        rejects("an unknown background mode", filterJson("""{"id":"a","kind":"background","background":{"mode":"sparkle"}}"""))
        rejects("a duplicate id", filterJson("""{"id":"a","kind":"lut","lut":{}},{"id":"a","kind":"lut","lut":{}}"""))
        rejects("an empty catalogue", filterJson(""))
        rejects("a bad gamma", filterJson("""{"id":"a","kind":"lut","lut":{"gamma":0}}"""))
    }

    @Test fun aValidMinimalCatalogueParses() {
        val f = FilterCatalog.parse(filterJson("""{"id":"h","kind":"hat","anchor":{"landmark":"head_top","scale":0.5,"offset":[0.1,-0.2],"pivot":[0.5,1]},$art}""")).single()
        val a = f.anchor!!
        assertEquals("h", f.id); assertEquals(0.1f, a.offsetX, 0f); assertEquals(-0.2f, a.offsetY, 0f)
        assertEquals(1f, a.pivotY, 0f); assertEquals("face", a.unit); assertTrue(a.rotate)
    }

    @Test fun identityLutLeavesPixelsAndWarmAddsRed() {
        val px = intArrayOf(0xFF102030.toInt(), 0x80FFFFFF.toInt(), 0xFF000000.toInt())
        val id = px.copyOf(); ColorLut.from(ColorLut.IDENTITY).apply(id)
        assertEquals(px.toList(), id.toList())
        val warm = real.first { it.id == "lut_warm" }.lut!!
        val w = intArrayOf(0xFF808080.toInt()); ColorLut.from(warm).apply(w)
        val r = (w[0] shr 16) and 255; val b = w[0] and 255
        assertTrue("warm look: red above blue ($r vs $b)", r > b)
        assertEquals("alpha is untouched", 0xFF, (w[0] ushr 24) and 255)
    }

    @Test fun monoLutMakesGrey() {
        val mono = real.first { it.id == "lut_mono" }.lut!!
        val p = intArrayOf(0xFFFF0000.toInt()); ColorLut.from(mono).apply(p)
        assertEquals((p[0] shr 16) and 255, (p[0] shr 8) and 255); assertEquals((p[0] shr 8) and 255, p[0] and 255)
        assertFalse(((p[0] shr 16) and 255) == 255)
    }
}
