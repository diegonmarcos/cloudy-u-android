package com.diegonmarcos.superapp.appstore.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** #1405 the catalogue parse, the feed resolvers and the checksum rule (store-ventoy-mutation.yml proves each). */
class VentoyCatalogueTest {

    private fun asset(): String {
        val f = listOf("src/main/assets/appstore-ventoy.json", "../ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-ventoy.json")
            .map(::File).first { it.exists() }
        return f.readText()
    }

    private val sha = "a".repeat(64)
    private val shb = "b".repeat(64)

    @Test fun realAssetParsesIntoTheFourTablesAfterTinyMl() {
        val c = VentoyCatalogue.parse(asset())
        assertEquals("libs-t-ml", c.afterGroup)
        assertEquals(VentoyCatalogue.TABLES, c.tables.map { it.id })
        assertTrue(c.tables[0].rows.map { it.id }.containsAll(listOf("ubuntu", "fedora", "debian", "arch", "mint", "opensuse", "nixos", "popos", "kali", "tails")))
        assertTrue(c.tables[2].rows.all { it.kind == VentoyCatalogue.Kind.DEVICE && !it.installable })
        assertEquals(listOf("cubic", "packer", "nixos-custom", "cloud-init", "talos"), c.tables[3].rows.map { it.id })
        assertTrue(c.rows.all { VentoyCatalogue.isOfficialUrl(it.link) })
        assertEquals("persistence", c.rows.first { it.id == "debian" }.persistenceLabel)
        assertTrue(c.rows.first { it.id == "kali" }.persistenceConf)
    }

    private fun minimal(rowJson: String, table: String = "linux") = """
        {"after_group":"libs-t-ml","ventoy":{"source":{"type":"github","feed":"ventoy/Ventoy","asset":"v.*","sums_asset":"sha256\\.txt"},"installer_assets":["v.*"]},
         "tables":[${VentoyCatalogue.TABLES.joinToString(",") { t -> """{"id":"$t","label":"$t","rows":[${if (t == table) rowJson else ""}]}""" }}]}
    """.trimIndent()

    @Test fun parseRefusesHttpLinksDeviceInstallsUnknownTablesAndDuplicates() {
        val ok = """{"id":"x","name":"X","link":"https://x.org/","source":{"type":"sums","feed":"https://x.org/S","file":"x-(\\d+)\\.iso","base":"https://x.org/"}}"""
        assertEquals(1, VentoyCatalogue.parse(minimal(ok)).rows.size)
        assertThrows(IllegalArgumentException::class.java) { VentoyCatalogue.parse(minimal(ok.replace("https://x.org/\",\"source", "http://x.org/\",\"source"))) }
        assertThrows(IllegalArgumentException::class.java) { VentoyCatalogue.parse(minimal(ok.replace("\"feed\":\"https://x.org/S\"", "\"feed\":\"http://x.org/S\""))) }
        assertThrows(IllegalArgumentException::class.java) { VentoyCatalogue.parse(minimal(ok, "android-mobile")) }
        assertThrows(IllegalArgumentException::class.java) { VentoyCatalogue.parse(minimal(ok.replace("\"name\":\"X\"", "\"name\":\"X\",\"kind\":\"device\""))) }
        assertThrows(IllegalArgumentException::class.java) { VentoyCatalogue.parse(minimal("$ok,$ok")) }
        assertThrows(IllegalArgumentException::class.java) { VentoyCatalogue.parse(minimal(ok).replace("\"id\":\"customised\"", "\"id\":\"other\"")) }
        assertThrows(IllegalArgumentException::class.java) { VentoyCatalogue.parse(minimal(ok).replace(",\"sums_asset\":\"sha256\\\\.txt\"", "")) }
    }

    @Test fun versionsCompareAsNumbers() {
        assertTrue(VentoyCatalogue.compareVersions("24.04.10", "24.04.4") > 0)
        assertTrue(VentoyCatalogue.compareVersions("24.04", "24.04.1") < 0)
        assertEquals(0, VentoyCatalogue.compareVersions("2026.11.01", "2026.11.01"))
        assertTrue(VentoyCatalogue.compareVersions("45", "9") > 0)
    }

    @Test fun sumsPicksTheNewestFullMatchInGnuAndBsdForm() {
        val text = "$sha *ubuntu-24.04.3-desktop-amd64.iso\n$shb *ubuntu-24.04.4-desktop-amd64.iso\n${"c".repeat(64)} *ubuntu-24.04.4-desktop-amd64.iso.zsync\n"
        val src = VentoyCatalogue.Source("sums", "https://r/S", file = "ubuntu-(24\\.04(?:\\.\\d+)?)-desktop-amd64\\.iso", base = "https://r/")
        val r = VentoyCatalogue.fromSums(text, src)!!
        assertEquals("24.04.4", r.version)
        assertEquals(shb, r.sha256)
        assertEquals("https://r/ubuntu-24.04.4-desktop-amd64.iso", r.url)
        val bsd = VentoyCatalogue.fromSums("SHA256 (x-7.iso) = ${sha.uppercase()}", src.copy(file = "x-(\\d+)\\.iso"))!!
        assertEquals(sha, bsd.sha256)
        assertNull(VentoyCatalogue.fromSums("$sha  other.iso", src))
        val alias = VentoyCatalogue.fromSums("$sha  nixos-graphical-26.05.1-x86_64-linux.iso", src.copy(file = "nixos-graphical-([\\w.]+)-x86_64-linux\\.iso", download = "https://c/latest.iso"))!!
        assertEquals("https://c/latest.iso", alias.url)
    }

    @Test fun newestDirOfAnIndex() {
        assertEquals("22.3", VentoyCatalogue.newestDir("<a href=\"22/\">22</a><a href=\"22.3/\"><a href=\"22.10x/\"><a href=\"22.2/\">"))
    }

    @Test fun jsonFeedsSelectFilterAndPickTheNewest() {
        val fedora = """[{"version":"45 Beta","arch":"x86_64","variant":"Workstation","subvariant":"Workstation","link":"https://f/b.iso","sha256":"$shb","size":"1"},
            {"version":"45","arch":"x86_64","variant":"Workstation","subvariant":"Workstation","link":"https://f/45.iso","sha256":"$sha","size":"2925092864"},
            {"version":"44","arch":"x86_64","variant":"Workstation","subvariant":"Workstation","link":"https://f/44.iso","sha256":"$shb","size":"1"},
            {"version":"46","arch":"aarch64","variant":"Workstation","subvariant":"Workstation","link":"https://f/arm.iso","sha256":"$shb","size":"1"}]"""
        val src = VentoyCatalogue.Source("json", "https://f/r.json", select = mapOf("arch" to "x86_64", "variant" to "Workstation"), versionRe = "^\\d+$", fields = mapOf("url" to "link"))
        val r = VentoyCatalogue.fromJson(fedora, src)!!
        assertEquals("45", r.version); assertEquals("https://f/45.iso", r.url); assertEquals(sha, r.sha256); assertEquals(2925092864L, r.size)
        val tails = """{"installations":[{"installation-paths":[{"type":"img","target-files":[{"url":"https://t/tails-amd64-7.16/t.img","sha256":"$shb","size":1}]},
            {"type":"iso","target-files":[{"url":"https://t/tails-amd64-7.16/t.iso","sha256":"$sha","size":5}]}]}]}"""
        val t = VentoyCatalogue.fromJson(tails, VentoyCatalogue.Source("json", "https://t/l.json", path = "installations/0/installation-paths", select = mapOf("type" to "iso"), item = "target-files/0", versionFromUrl = "tails-amd64-([\\d.]+)/"))!!
        assertEquals("7.16", t.version); assertEquals(sha, t.sha256); assertEquals("t.iso", t.fileName)
        val bad = """{"url":"http://p/x.iso","sha_sum":"$sha","version":"24.04"}"""
        assertNull(VentoyCatalogue.fromJson(bad, VentoyCatalogue.Source("json", "https://p/", fields = mapOf("sha256" to "sha_sum"))))
        assertNull(VentoyCatalogue.fromJson("""{"url":"https://p/x.iso","sha_sum":"nothex","version":"1"}""", VentoyCatalogue.Source("json", "https://p/", fields = mapOf("sha256" to "sha_sum"))))
    }

    @Test fun githubTakesTheChecksumFromTheReleaseSumsFile() {
        val rel = """{"tag_name":"v1.11.5","assets":[{"name":"metal-amd64.iso","browser_download_url":"https://github.com/siderolabs/talos/releases/download/v1.11.5/metal-amd64.iso","size":100},
            {"name":"sha256sum.txt","browser_download_url":"https://github.com/s/t/sha256sum.txt","size":1,"digest":"sha256:$shb"}]}"""
        val r = VentoyCatalogue.fromGithub(rel, "metal-amd64\\.iso", "$sha  metal-amd64.iso\n$shb  other.iso")!!
        assertEquals("1.11.5", r.version); assertEquals(sha, r.sha256); assertEquals(100L, r.size)
        assertNull(VentoyCatalogue.fromGithub(rel, "metal-amd64\\.iso", "$shb  other.iso"))
        assertNull(VentoyCatalogue.fromGithub(rel, "nothing", null))
    }

    @Test fun checksumMatchesOnlyTwoEqualWellFormedHashes() {
        assertTrue(VentoyCatalogue.matches(sha, sha.uppercase()))
        assertFalse(VentoyCatalogue.matches(sha, shb))
        assertFalse(VentoyCatalogue.matches(sha, ""))
        assertFalse(VentoyCatalogue.matches(sha, null))
        assertFalse(VentoyCatalogue.matches("abc", "abc"))
        val out = ByteArrayOutputStream()
        val h = VentoyCatalogue.copyHashing(ByteArrayInputStream("abc".toByteArray()), out)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h)
        assertEquals("abc", out.toString())
        assertNotNull(h)
    }
}
