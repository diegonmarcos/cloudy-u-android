package com.diegonmarcos.superapp.launcher.folders

import android.app.Application
import android.os.Process
import com.diegonmarcos.superapp.apps.PhoneApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1553 The launcher-grid bridge: a Folders-page assignment moves the app in the launcher grid, while no assignment
 * leaves the default grid untouched (so the baked classification is unchanged until the user deliberately moves an app).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderGridBridgeTest {

    private fun app(pkg: String) = PhoneApp(pkg, null, pkg, null, Process.myUserHandle())
    private val grouped = mapOf(
        "dev" to listOf(app("com.x.ide"), app("com.x.term")),
        "media" to listOf(app("com.x.player")),
    )

    @Test fun `no override returns the exact same grouping`() {
        assertSame(grouped, FolderGridBridge.applyOverrides(grouped, emptyMap(), setOf("dev", "media")))
    }

    @Test fun `an override moves the app to the chosen folder`() {
        val out = FolderGridBridge.applyOverrides(grouped, mapOf("com.x.player" to "dev"), setOf("dev", "media"))
        assertEquals(listOf("com.x.ide", "com.x.term", "com.x.player"), out["dev"]!!.map { it.packageName })
        assertEquals(emptyList<String>(), out["media"]!!.map { it.packageName })
    }

    @Test fun `an override to an unknown folder is ignored`() {
        val out = FolderGridBridge.applyOverrides(grouped, mapOf("com.x.player" to "ghost"), setOf("dev", "media"))
        assertEquals(listOf("com.x.player"), out["media"]!!.map { it.packageName })
    }
}
