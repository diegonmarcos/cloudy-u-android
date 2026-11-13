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
        AppRow(pkg, pkg, false, app, data, cache, fleetIcon = icon)

    @Test fun cloudAndPhoneAppsAreSummedFromTheCachedRows() {
        val rows = listOf(row("cld.termux", 100, 50, 10), row("com.x", 20, 5, 1), row("com.y", 30, null, 4), row("com.diegonmarcos.t", 1, 1, 1, icon = "tasks"))
        val (cloud, phone) = OverviewAppsSummary.summarize(rows, setOf("cld.termux"))
        assertEquals(AppsSumRow("cloud", 163, 101, 51, 11, 2), cloud)
        assertEquals(AppsSumRow("phone", 60, 50, 5, 5, 2), phone)
    }

    @Test fun aGroupWithNoSizesIsNullNotZeroAndEmptyCacheNeverCrashes() {
        val (c, p) = OverviewAppsSummary.summarize(listOf(row("com.z", null, null, null)), emptySet())
        assertEquals(0, c.amount); assertNull(c.total)
        assertEquals(1, p.amount); assertNull(p.total); assertNull(p.app)
    }

    @Test fun cacheFileRoundTripFeedsTheSummary() {
        val f = File.createTempFile("apps", ".json")
        val c = LocalAppsCache(f)
        c.write(AppSnapshot.Snapshot(listOf(row("cld.termux", 7, 3, 1)), false, null, 5L))
        val s = OverviewAppsSummary.summarize(LocalAppsCache(f).read()!!.rows, setOf("cld.termux"))
        assertEquals(11L, s[0].total); f.delete()
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
