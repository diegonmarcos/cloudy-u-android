package com.diegonmarcos.cloudc3

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.cloudc3.pages.PrivateData
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1454 the owner's private email identities: parsing and the fallbacks, with FAKE addresses only; none is in this repo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrivateDataTest {

    private val fake = """{"emails":[{"address":"a@example.test","label":"A","provider":"example"},{"label":"no address"},{"address":"b@example.test"}]}"""

    @Test
    fun `identities parse, an entry without an address is dropped`() {
        assertEquals(listOf("a@example.test", "b@example.test"), PrivateData.parseIdentities(fake).map { it.address })
        assertTrue(PrivateData.parseIdentities("not json").isEmpty())
    }

    @Test
    fun `sources parse from the declaration and an incomplete one is dropped`() {
        val s = PrivateData.parseSources("""{"identities":{"owner":"o","repo":"r","path":"p.json"},"bad":{"repo":"r"}}""")
        assertEquals(setOf("identities"), s.keys)
        assertEquals("main", s["identities"]!!.ref)
    }

    @Test
    fun `the shipped declaration names a private repo and a path, and the repo carries no email address`() {
        assertTrue(Declarations.privateSources["identities"] != null)
        val tree = FleetFixtures.moduleDir().parentFile
        val rx = Regex("[A-Za-z0-9._-]+@(gmail|live|hotmail|outlook|proton)\\.[a-z]+")
        val hits = listOf("build.json", "data/platforms.json", "data/container_projects.json", "data/fleet_slice.json")
            .filter { rx.containsMatchIn(File(FleetFixtures.moduleDir(), it).readText()) }
        assertEquals("a personal address is baked into: $hits", emptyList<String>(), hits)
        assertTrue(tree.isDirectory)
    }

    @Test
    fun `with no token a side-loaded copy is used, then the cached one, else the reason is named`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val src = PrivateData.Source("identities-test", "o", "r", "main", "p.json")
        val side = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "private/identities-test.json").apply { parentFile!!.mkdirs() }
        side.delete()
        File(ctx.filesDir, "private/identities-test.json").delete()
        val none = runCatching { PrivateData.load(ctx, src) { _, _ -> error("offline") } }
        assertTrue(none.isFailure)
        assertTrue(none.exceptionOrNull()!!.message!!.contains("token"))
        side.writeText(fake)
        val got = PrivateData.load(ctx, src) { _, _ -> error("offline") }
        assertEquals(PrivateData.From.SIDELOADED, got.from)
        assertEquals(2, got.identities.size)
        assertFalse(got.identities.any { it.address.isBlank() })
        side.delete()
    }
}
