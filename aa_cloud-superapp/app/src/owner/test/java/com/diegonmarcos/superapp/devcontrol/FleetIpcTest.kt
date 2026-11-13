package com.diegonmarcos.superapp.devcontrol

import android.app.Application
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.devtools.FleetIpc
import com.diegonmarcos.superapp.devtools.FleetIpcWire
import com.diegonmarcos.superapp.devtools.FleetIpcWire.Via
import com.diegonmarcos.superapp.devtools.FleetTokenProvider
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

/**
 * #ipc the fleet IPC layer end to end, on the REAL provider (libs:devtools FleetTokenProvider,
 * every member's `<pkg>.fleet` door) and the real client (FleetIpc). a0_docs/eng-specs/fleet-ipc.md.
 *
 *   I1 the IPC call works: a provider call reaches the callee's route table, the universal ops
 *      and an app's own registered group alike, query and all
 *   I2 a caller that is not a fleet app (another uid, no CONSTELLATION_DATA) is refused 403,
 *      through the client and on the provider itself — call() is not permission-checked by
 *      the framework, so FleetIpc.serve's own check is the one that holds
 *   I3 the fallback works: a member with no IPC door (a build from before the layer) is asked
 *      on its old loopback debug port, through a real socket
 *   I4 the SuperApp bridge's route (adb/exec) is served over the door
 *   G1 a request through the mesh gateway (MeshGateway, the SuperApp's one fleet door) reaches
 *      the app over IPC, a large POST body included (it rides a pipe)
 *   G2 the gateway refuses a request without the fleet token, names an unknown app, and leaves
 *      every other path to DevControlServer; a non-fleet IPC caller is refused behind it too
 *   G3 there are no per-app listening ports: starting the debug API binds nothing
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FleetIpcTest {

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()
    private val peer = "com.example.fleetpeer"

    @After fun reset() = ShadowBinder.reset()

    @Test
    fun `I1 an IPC call reaches the callee's route table`() {
        Robolectric.setupContentProvider(FleetTokenProvider::class.java, FleetIpc.authority(peer))
        val ping = FleetIpc.call(ctx, peer, "system/ping", httpFallback = false)
        assertEquals(200, ping.status)
        assertEquals(Via.IPC, ping.via)
        assertEquals("pong ${ctx.packageName}\n", ping.body)

        AppDebugServer.route("ipctest") { op, q -> """{"op":"$op","x":"${q["x"]}"}""" }
        val echo = FleetIpc.call(ctx, peer, "ipctest/echo", mapOf("x" to "1"), httpFallback = false)
        assertEquals(200, echo.status)
        assertEquals("""{"op":"echo","x":"1"}""", echo.body)
        assertEquals(404, FleetIpc.call(ctx, peer, "ipctest-missing/x", httpFallback = false).status)
    }

    @Test
    fun `I2 a non-fleet caller is refused`() {
        val provider = Robolectric.setupContentProvider(FleetTokenProvider::class.java, FleetIpc.authority(peer))
        ShadowBinder.setCallingUid(Process.myUid() + 4242)
        val viaClient = FleetIpc.call(ctx, peer, "system/info", httpFallback = false)
        assertEquals(403, viaClient.status)
        assertTrue(viaClient.body.contains("CONSTELLATION_DATA"))
        val direct = provider.call(FleetIpcWire.METHOD, "diagnostics/logcat", null)!!
        assertEquals(403, direct.getInt(FleetIpcWire.K_STATUS))
        assertEquals("an unknown method is not the door's", null, provider.call("delete_everything", null, null))
    }

    @Test
    fun `I3 a member with no IPC door is asked on its old debug port`() {
        val pkg = "com.diegonmarcos.clouddrive"
        val port = AppDebugServer.portOf(pkg)!!
        val server = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
        val t = Thread {
            runCatching {
                server.accept().use { s ->
                    s.getInputStream().bufferedReader().readLine()
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\npong $pkg\n".toByteArray())
                }
            }
        }.apply { start() }
        try {
            val r = FleetIpc.call(ctx, pkg, "system/ping")
            assertEquals(200, r.status)
            assertEquals(Via.HTTP, r.via)
            assertEquals("pong $pkg\n", r.body)
        } finally { t.join(3000); server.close() }
        assertEquals("no door and nothing listening", 503, FleetIpc.call(ctx, "com.example.nothing", "system/ping").status)
    }

    private val roster = listOf(MeshGateway.Member("peer", peer, "Peer"))

    @Test
    fun `G1 a request through the gateway reaches the app over IPC`() {
        Robolectric.setupContentProvider(FleetTokenProvider::class.java, FleetIpc.authority(peer))
        val r = MeshGateway.handle(ctx, "/app/peer/api/system/ping", emptyMap(), null, authed = true, roster = roster)!!
        assertEquals(200, r.status)
        assertEquals(Via.IPC, r.via)
        assertEquals("pong ${ctx.packageName}\n", r.body)
        assertEquals("by package too", 200, MeshGateway.handle(ctx, "/app/$peer/api/docs", emptyMap(), null, true, roster)!!.status)

        AppDebugServer.route("ipcbody", listOf(AppDebugServer.Op("echo", "", "", maxBody = 1024 * 1024))) { _, q -> q["_body"]?.length?.toString() }
        val big = "x".repeat(300_000)
        val post = MeshGateway.handle(ctx, "/app/peer/api/ipcbody/echo", emptyMap(), big, true, roster)!!
        assertEquals(200, post.status)
        assertEquals("300000", post.body)
        assertEquals("over the op's own limit", 413,
            MeshGateway.handle(ctx, "/app/peer/api/system/info", emptyMap(), big, true, roster)!!.status)
    }

    @Test
    fun `G2 the gateway refuses without the token and leaves other paths alone`() {
        Robolectric.setupContentProvider(FleetTokenProvider::class.java, FleetIpc.authority(peer))
        assertEquals(401, MeshGateway.handle(ctx, "/app/peer/api/system/ping", emptyMap(), null, authed = false, roster = roster)!!.status)
        assertEquals(401, MeshGateway.handle(ctx, "/api/fleet/apps", emptyMap(), null, authed = false, roster = roster)!!.status)
        assertEquals(404, MeshGateway.handle(ctx, "/app/nobody/api/system/ping", emptyMap(), null, true, roster)!!.status)
        assertEquals(400, MeshGateway.handle(ctx, "/app/peer", emptyMap(), null, true, roster)!!.status)
        assertEquals("not the gateway's", null, MeshGateway.handle(ctx, "/api/system/ping", emptyMap(), null, true, roster))
        assertEquals(Pair("peer", "net/dns"), MeshGateway.split("/app/peer/api/net/dns"))
        ShadowBinder.setCallingUid(Process.myUid() + 4242)
        assertEquals("a non-fleet IPC caller is refused behind the gateway", 403,
            MeshGateway.handle(ctx, "/app/peer/api/system/info", emptyMap(), null, true, roster)!!.status)
    }

    @Test
    fun `G3 no app listens on a port of its own`() {
        AppDebugServer.start(ctx)
        assertTrue(AppDebugServer.isRunning())
        assertEquals(-1, AppDebugServer.boundPort())
        val own = AppDebugServer.portOf(ctx.packageName)!!
        val refused = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", own), 300) } }.isFailure
        assertTrue("something listens on ${ctx.packageName}'s old port $own", refused)
    }

    @Test
    fun `I4 the SuperApp bridge's adb exec is served over the door`() {
        Robolectric.setupContentProvider(FleetTokenProvider::class.java, FleetIpc.authority(peer))
        AdbBridgeApi.register(ctx)
        val r = FleetIpc.call(ctx, peer, "adb/exec", mapOf("cmd" to ""), httpFallback = false)
        assertEquals(200, r.status)
        assertTrue(r.body, r.body.contains("\"ok\":false"))
    }
}
