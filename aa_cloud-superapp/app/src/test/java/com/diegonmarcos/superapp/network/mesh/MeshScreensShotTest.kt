package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.network.MeshIntent
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1120 The evidence: Cloud Mesh > Status and > Profile drawn by the real MeshScreen at Galaxy A37 size/density, one PNG per state, uploaded by
 * configs-screens.yml as mesh-*.png. Each shot is also an assertion that the thing it shows is on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-night-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeshScreensShotTest : KitPageHarness() {
    private val decl = MeshDecl.baked

    private fun show(port: FakePort, tab: String): MeshStore {
        val store = MeshStore(port, decl, FakeHost(), exec = { it.run() })
        store.open(tab)   // #1928 "controls" opens the Profile tab on Connection
        store.poll()
        showKit { MeshScreen(store, ticker = false) }
        return store
    }

    @Test fun `Status connected, Always-on read through a privileged channel`() {
        show(FakePort(up = true).also { it.channel = true; it.aoOn = true; it.note = "connected" }, "status")
        compose.onNodeWithTag(MeshTags.control("vpn_disconnect")).assertExists()
        snap("mesh-status-connected.png")
    }

    @Test fun `Status disconnected by the owner, Always-on unavailable with its reason`() {
        val port = FakePort(up = false).also { it.intentP = MeshIntent.Policy(wanted = false); it.note = "disconnected by you - stays down until you tap Connect" }
        show(port, "status")
        compose.onNodeWithTag("mesh:vpn:settings").assertExists()
        snap("mesh-status-disconnected.png")
    }

    @Test fun `Status without the engine`() {
        show(FakePort(engine = false), "status")
        compose.onNodeWithTag(MeshTags.control("vpn_connect")).assertExists()
        snap("mesh-status-no-engine.png")
    }

    @Test fun `Profile with the Start-up switches usable`() {
        show(FakePort(up = true).also { it.channel = true }, "controls")
        compose.onNodeWithTag(MeshTags.control("auto_boot")).performScrollTo().assertExists()
        snap("mesh-profile-startup.png")
    }

    @Test fun `Profile start-up with no privileged channel - kill switch says why`() {
        show(FakePort(up = true), "controls")
        compose.onNodeWithTag(MeshTags.control("kill_switch")).performScrollTo().assertExists()
        snap("mesh-profile-startup-no-channel.png")
    }

    private fun snap(name: String) {
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bmp = compose.runOnUiThread {
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } }
        }
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(File(out, name).length() > 1000)
    }
}
