package com.diegonmarcos.superapp.apps

import com.diegonmarcos.superapp.apps.SuitePhoneAppsFragment.Entry
import com.diegonmarcos.superapp.apps.SuitePhoneAppsFragment.Folder
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1864 a folder with an icon_pkg opens with its Cloud app first, then the declared phone apps in order. */
class PhoneFolderCloudAppFirstTest {
    private val fleet = mapOf("com.diegonmarcos.cloudcanvas" to "Cloud Canvas", "cld.camera" to "Cloud Camera")
    private val lookup = { p: String -> fleet[p] }
    private val declared = listOf(Entry("com.adsk.sketchbook", "SketchBook"), Entry("com.imageline.FLM", "FL Studio Mobile"))
    private fun canvas(entries: List<Entry> = declared, ip: String = "com.diegonmarcos.cloudcanvas") = Folder("Canvas", entries, ip)

    @Test fun firstEntryIsTheCloudApp() {
        val r = SuitePhoneAppsFragment.withCloudApp(canvas(), lookup)
        assertEquals(Entry("com.diegonmarcos.cloudcanvas", "Cloud Canvas"), r.first())
    }
    @Test fun declaredOrderIsKept() {
        assertEquals(declared, SuitePhoneAppsFragment.withCloudApp(canvas(), lookup).drop(1))
    }
    @Test fun noDuplicateWhenAlreadyAMember() {
        val f = canvas(listOf(declared[0], Entry("com.diegonmarcos.cloudcanvas", "X"), declared[1]))
        assertEquals(f.entries, SuitePhoneAppsFragment.withCloudApp(f, lookup))
    }
    @Test fun folderWithoutIconPkgIsUnchanged() {
        assertEquals(declared, SuitePhoneAppsFragment.withCloudApp(canvas(ip = ""), lookup))
        assertEquals(declared, SuitePhoneAppsFragment.withCloudApp(canvas(ip = "  "), lookup))
    }
    @Test fun aliasNotInTheFleetIsSkipped() {
        assertEquals(declared, SuitePhoneAppsFragment.withCloudApp(canvas(ip = "cld.nothing"), lookup))
    }
    @Test fun fleetPackageWithBlankLabelFallsBackToFolderLabel() {
        val r = SuitePhoneAppsFragment.withCloudApp(canvas(), { "" })
        assertEquals("Canvas", r.first().label)
    }
}
