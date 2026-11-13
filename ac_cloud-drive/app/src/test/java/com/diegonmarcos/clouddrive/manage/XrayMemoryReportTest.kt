package com.diegonmarcos.clouddrive.manage

import com.diegonmarcos.clouddrive.manage.XrayMemoryReport.Src
import com.diegonmarcos.superapp.shellclient.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** X-Ray Memory: the report's sections, its Markdown, and the "not available: <reason>" label of every source that cannot answer. */
class XrayMemoryReportTest {
    private val device = XrayMemoryReport.Device("Samsung", "SM-A376B", "15", 35, "1.2.3 (sha-abc)", "2026-11-13T10:00:00+0100")
    private fun apps() = listOf(
        AppRow("com.a", "Alpha | pipe", false, 10_000_000, 5_000_000, 1_000_000, null, null, "1.0"),
        AppRow("com.b", "Beta", true, null, null, null, null, null, null))

    fun full() = XrayMemoryReport.Inputs(
        device,
        volumes = Src.Ok(listOf(XrayMemoryReport.Volume("Internal storage", "/mnt/sample-volume", 128_000_000_000, 40_000_000_000, "mounted"))),
        apps = Src.Ok(apps()),
        folders = Src.Ok(listOf(XrayMemoryReport.Sized("/mnt/sample-volume/DCIM", 9_000_000_000))),
        files = Src.Ok(listOf(XrayMemoryReport.Sized("/mnt/sample-volume/Download/big.iso", 4_000_000_000), XrayMemoryReport.Sized("/mnt/sample-volume/.ssh/id_rsa", 1700))),
        media = Src.Ok(listOf(XrayMemoryReport.MediaCat("videos", 12, 5_000_000_000))),
        mem = Src.Ok(XrayMemoryReport.Mem(8_000_000_000, 3_000_000_000, 400_000_000, false)),
        procs = Src.Ok(listOf(XrayMemoryReport.Proc("com.a", 120_000))),
        adbMeminfo = Src.Ok("Total PSS by process:\n   120,000K: com.a (pid 1 / activities)\n```evil```"),
        adbDf = Src.Ok("Filesystem Size Used Avail\n/dev/block 100G 60G 40G"),
        adbDu = Src.Ok("100\t/sdcard/DCIM"),
    )

    private fun titles(md: String) = md.lines().filter { it.startsWith("## ") }.map { it.removePrefix("## ") }

    @Test fun everySectionIsPresentInOrderAndTheHeaderNamesTheDevice() {
        val md = XrayMemoryReport.markdown(full())
        assertEquals(XrayMemoryReport.SECTIONS, titles(md))
        assertTrue(md.startsWith("# X-Ray Memory: Samsung SM-A376B"))
        listOf("Device: Samsung SM-A376B", "Android: 15 (API 35)", "Generated: 2026-11-13T10:00:00+0100", "Generator: Cloud Drive 1.2.3").forEach { assertTrue(it, md.contains(it)) }
        assertTrue(md.contains("Total RAM")); assertTrue(md.contains("Low-memory threshold")); assertTrue(md.contains("Used"))
    }

    @Test fun markdownIsWellFormed() {
        assertEquals(emptyList<String>(), XrayMemoryReport.lint(XrayMemoryReport.markdown(full())))
        // the pipe in an app name did not add a column, the embedded fence did not close the block early
        val md = XrayMemoryReport.markdown(full())
        assertTrue(md.contains("Alpha \\| pipe")); assertFalse(md.contains("```evil```"))
        val allNa = XrayMemoryReport.markdown(full().copy(volumes = Src.NA("x"), apps = Src.NA("y"), adbDu = Src.NA("z")))
        assertEquals(emptyList<String>(), XrayMemoryReport.lint(allNa))
    }

    @Test fun lintCatchesBrokenMarkdown() {
        assertTrue(XrayMemoryReport.lint("# a\n```\nopen\n").isNotEmpty())
        assertTrue(XrayMemoryReport.lint("# a\n\n| a | b |\n|---|---|\n| 1 |\n").isNotEmpty())
        assertTrue(XrayMemoryReport.lint("no heading\n").isNotEmpty())
    }

    @Test fun everyUnavailableSourceIsLabelledWithItsReason() {
        val i = full().copy(
            volumes = Src.NA("StorageManager refused"), apps = Src.NA("usage access not granted"), folders = Src.NA("all-files access not granted"),
            files = Src.NA("all-files access not granted"), media = Src.NA("all-files access not granted"), mem = Src.NA("ActivityManager refused"),
            procs = Src.NA("own processes only"), adbMeminfo = Src.NA("ADB owner channel not armed"), adbDf = Src.NA("ADB owner channel not armed"), adbDu = Src.NA("denied"))
        val md = XrayMemoryReport.markdown(i)
        assertEquals(10, Regex("not available: ").findAll(md).count())
        assertTrue(md.contains("not available: usage access not granted"))
        assertEquals(XrayMemoryReport.SECTIONS, titles(md))
        // a blank reason is still a reason
        assertTrue(XrayMemoryReport.na("  ").endsWith("unknown reason"))
    }

    @Test fun noSecretsAndNoContents() {
        val md = XrayMemoryReport.markdown(full())
        assertFalse("credential file name withheld", md.contains("id_rsa"))
        assertTrue(md.contains("name withheld"))
        assertEquals("/a/.ssh/(name withheld: looks like a credential file)", XrayMemoryReport.withhold("/a/.ssh/config"))
        assertEquals("/a/b/photo.jpg", XrayMemoryReport.withhold("/a/b/photo.jpg"))
        assertFalse(XrayMemoryReport.scrubLines("1\t/sdcard/Documents\n2\t/sdcard/.aws").contains(".aws"))
    }

    @Test fun meminfoIsTrimmedToTheProcessTableAndTheRamSummary() {
        val text = "noise\nTotal PSS by process:\n  10K: a (pid 1)\n  9K: b (pid 2)\n\nTotal PSS by OOM adjustment:\nx\nTotal RAM: 8,000K\n Free RAM: 1K\n"
        val t = XrayMemoryReport.trimMeminfo(text, pssLines = 3, tailLines = 2)
        assertTrue(t.startsWith("Total PSS by process:")); assertTrue(t.contains("Total RAM: 8,000K")); assertFalse(t.contains("noise"))
    }

    @Test fun appSizesAreCappedAndUnreadableOnesCounted() {
        val many = (1..500).map { AppRow("p.$it", "app$it", false, it.toLong(), 1, 1, null, null) } + AppRow("p.none", "none", false, null, null, null, null, null)
        val md = XrayMemoryReport.markdown(full().copy(apps = Src.Ok(many)))
        assertTrue(md.contains("501 apps, ${XrayMemoryReport.MAX_APPS} listed; 1 without a readable size"))
    }

    @Test fun shellAnswersBecomeDataOrAReason() {
        val shape = { s: String -> s.trim() }
        assertEquals(Src.Ok("out"), XrayMemoryCollector.shell(ShellResult.Ok(0, "out\n", "", "adb"), "df", shape))
        fun reason(r: ShellResult?) = (XrayMemoryCollector.shell(r, "df", shape) as Src.NA).reason
        assertTrue(reason(ShellResult.NeedsArm).contains("not armed"))
        assertTrue(reason(ShellResult.Denied("no rule")).contains("denied") && reason(ShellResult.Denied("no rule")).contains("no rule"))
        assertTrue(reason(ShellResult.Failed("SuperApp absent")).contains("SuperApp absent"))
        assertTrue(reason(ShellResult.Ok(1, "", "boom", "adb")).contains("exited 1"))
        assertTrue(reason(null).contains("no answer"))
    }

    @Test fun appsAndProcessSourcesSayWhyTheyAreEmpty() {
        assertTrue(XrayMemoryCollector.appsSource(null) is Src.NA)
        val noUsage = AppSnapshot.Snapshot(listOf(AppRow("a", "a", false, null, null, null, null, null)), false, null)
        assertTrue((XrayMemoryCollector.appsSource(noUsage) as Src.NA).reason.contains("usage access"))
        assertTrue((XrayMemoryCollector.procs(noUsage) as Src.NA).reason.contains("own processes"))
        val ok = AppSnapshot.Snapshot(listOf(AppRow("a", "a", false, 1, 1, 1, true, 500)), true, null)
        assertTrue(XrayMemoryCollector.appsSource(ok) is Src.Ok); assertEquals(1, (XrayMemoryCollector.procs(ok) as Src.Ok).value.size)
    }
}
