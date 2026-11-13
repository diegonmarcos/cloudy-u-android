package com.diegonmarcos.clouddrive.manage

import com.diegonmarcos.clouddrive.Declarations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OverviewAppsDataTest {
    private fun row(pkg: String, app: Long?, data: Long?, cache: Long?, icon: String? = null) =
        AppRow(pkg, pkg, false, app, data, cache, null, null, fleetIcon = icon)

    @Test fun cloudAndPhoneAppsComeFromTheCachedAggregate() {
        val f = File.createTempFile("apps", ".json")
        LocalAppsCache(f).write(AppSnapshot.Snapshot(listOf(row("a", 100, 50, 10, "x"), row("b", 20, 5, 1), row("c", 30, 0, 4)), false, null, 5L))
        val agg = LocalAppsApi.aggregate(LocalAppsCache(f).read()!!.rows)
        assertEquals(1, agg[AppGroup.CLOUD]!!.count); assertEquals(160L, agg[AppGroup.CLOUD]!!.totalBytes)
        assertEquals(2, agg[AppGroup.PHONE]!!.count); assertEquals(60L, agg[AppGroup.PHONE]!!.totalBytes)
        // a Local Apps cache write publishes the aggregate the Overview observes
        assertEquals(2, LocalAppsApi.state.value!![AppGroup.PHONE]!!.count)
        f.delete()
    }

    @Test fun emptyCacheNeverCrashes() {
        val agg = LocalAppsApi.aggregate(emptyList())
        assertEquals(0, agg[AppGroup.CLOUD]!!.count); assertEquals(0L, agg[AppGroup.PHONE]!!.totalBytes)
    }

    @Test fun stripSelectionFiltersByRelativePathAndImagesOnly() {
        val shots = Declarations.DataStripDecl("s", "S", listOf("DCIM/Screenshots/", "Pictures/Screenshots"), true, 20)
        val (sel, args) = RecentMedia.selection(shots)!!
        assertEquals(listOf("DCIM/Screenshots/%", "Pictures/Screenshots/%"), args.toList())
        assertTrue("relative_path LIKE ? OR relative_path LIKE ?" in sel && "image/%" in sel)
        val dl = RecentMedia.selection(Declarations.DataStripDecl("d", "D", listOf("Download/"), false, 20))!!
        assertTrue("image/%" !in dl.first)
        assertNull(RecentMedia.selection(Declarations.DataStripDecl("e", "E", emptyList(), false, 20)))
    }

    @Test fun buildJsonDeclaresTheThreeStripsAndTheFiveSectionsKeepEveryOldSource() {
        val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
        val d = Declarations.parseManage(org.json.JSONObject(File(root, "build.json").readText()).getJSONObject("ui").getJSONObject("manage").toString())
        assertEquals(listOf("screenshots", "downloads", "camera"), d.dataStrips.map { it.id })
        assertEquals(listOf("DCIM/Screenshots/", "Pictures/Screenshots/"), d.dataStrips[0].paths)
        assertEquals(listOf("Download/"), d.dataStrips[1].paths)
        assertEquals(listOf("DCIM/Camera/"), d.dataStrips[2].paths)
        assertTrue(d.dataStrips[0].imagesOnly && !d.dataStrips[1].imagesOnly && d.dataStrips[2].imagesOnly)
        assertNotNull(d.terminals); assertEquals(4, d.local.size); assertEquals(3, d.cloud.size)
    }
}
