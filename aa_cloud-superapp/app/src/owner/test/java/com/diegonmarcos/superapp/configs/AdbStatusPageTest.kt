package com.diegonmarcos.superapp.configs

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.adbdebug.ChannelFacts
import com.diegonmarcos.superapp.adbdebug.ChannelLog
import com.diegonmarcos.superapp.adbdebug.ChannelMode
import com.diegonmarcos.superapp.adbdebug.ChannelModePrefs
import com.diegonmarcos.superapp.adbdebug.ConnectExecutor
import com.diegonmarcos.superapp.adbdebug.LaunchVia
import com.diegonmarcos.superapp.adbdebug.StepId
import com.diegonmarcos.superapp.adbdebug.StepResult
import com.diegonmarcos.superapp.configs.AdbCockpitModel.Do
import com.diegonmarcos.superapp.configs.AdbCockpitModel.Light
import com.diegonmarcos.superapp.ui.KitPageHarness
import com.diegonmarcos.superapp.uikit.KitStoreRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1647 the ADB Shell STATUS page, redesigned to the specified layout. Each rule of the ticket is one test:
 *   S1 the mode defaults to Local Server, and a chosen mode sticks
 *   S2 Connect runs the WHOLE chain of the selected mode (perms, every prerequisite, the round trip): Connect is the auto-fix, and
 *      there is no second fix control on the page
 *   S3 a Connect that stopped for the person goes on by itself once that step is done, and never while it is still open
 *   S4 the cards are Perms, Setup, Embedded, Local Server, Shizuku, each row (light) (step name): (message) (action icons)
 *   S5 Perms is ONE light
 *   S6 Setup is the two flips: Developer Options Flipped, Wireless Debugging Flipped
 *   S7 Local Server carries ALL the actions
 *   S8 the logs sit below the cards
 *   S9 a mode's Connected light is green only on a real round trip over its own channel
 *   S10 nothing the previous page showed was dropped (every action and sub-light of the old chain is on a row)
 *   S11 the page is roomy, not dense
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h3200dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdbStatusPageTest : KitPageHarness() {
    private fun facts(mode: ChannelMode = ChannelMode.LOCAL_SERVER, dev: Boolean? = true, wd: Boolean? = true, paired: Boolean = false, connected: Boolean = false,
                      server: Boolean = false, shz: Triple<Boolean, Boolean, Boolean> = Triple(false, false, false), answers: Boolean? = null) =
        ChannelFacts(mode = mode, probedAt = 1L, devOptions = dev, wirelessDebug = wd, onWifi = true, adbPaired = paired || connected, adbConnected = connected,
            serverPort = 38099, serverRunning = server, serverUid = if (server) "uid 2000" else null,
            shizukuInstalled = shz.first, shizukuRunning = shz.second, shizukuGranted = shz.third, shizukuAnswers = answers)

    private fun inputs(f: ChannelFacts) = AdbCockpitModel.Inputs(f, AdbCockpitModel.PermsDigest(2, emptyList()))
    private fun group(f: ChannelFacts, id: String) = AdbCockpitModel.groups(inputs(f)).first { it.id == id }
    private fun connected(f: ChannelFacts, id: String) = group(f, id).rows.first { it.id == "$id:connected" }
    private val up = Triple(true, true, true)

    private fun box(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun has(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun px(d: androidx.compose.ui.unit.Dp) = d.value * compose.density.density

    /** A device where each step's success flips the fact the next steps read; [waitAt] hands one step to the person. */
    private class FakeDevice(var f: ChannelFacts, val waitAt: StepId? = null) : ConnectExecutor {
        val ran = ArrayList<String>()
        override fun facts() = f
        override fun run(step: StepId): StepResult {
            ran += step.name
            if (step == waitAt) return StepResult(false, "your turn", needsUser = true)
            f = when (step) {
                StepId.DEV_OPTIONS -> f.copy(devOptions = true)
                StepId.WIRELESS_DEBUG -> f.copy(wirelessDebug = true)
                StepId.PAIR -> f.copy(adbPaired = true)
                StepId.ADB_CONNECT -> f.copy(adbConnected = true)
                StepId.SHIZUKU_RUNNING -> f.copy(shizukuInstalled = true, shizukuRunning = true)
                StepId.SHIZUKU_PERMISSION -> f.copy(shizukuGranted = true, shizukuAnswers = true)
                StepId.VERIFY -> f
                StepId.SHIZUKU_START -> f.copy(shizukuRunning = true)
                StepId.LAUNCH_SERVER -> f.copy(serverRunning = true)
                StepId.CLOSE_EMBEDDED -> f.copy(adbConnected = false)
            }
            return StepResult(true, "ok")
        }
    }

    @Test fun `S1 the mode defaults to Local Server and a chosen mode sticks`() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        ctx.getSharedPreferences("adb_shell", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(ChannelMode.LOCAL_SERVER, ChannelModePrefs.default())
        assertEquals(ChannelMode.LOCAL_SERVER, ChannelModePrefs.current(ctx))
        ChannelModePrefs.set(ctx, ChannelMode.SHIZUKU)
        assertEquals(ChannelMode.SHIZUKU, ChannelModePrefs.current(ctx))
        ctx.getSharedPreferences("adb_shell", Context.MODE_PRIVATE).edit().clear().commit()
        assertEquals(ChannelMode.LOCAL_SERVER, ChannelModePrefs.current(ctx))
        // and the page draws Local Server as the selected, live mode
        assertFalse(group(facts(), "local").dim); assertEquals("active mode", group(facts(), "local").badge)
    }

    @Test fun `S2 Connect runs the whole chain of the selected mode`() {
        // nothing is set up: one Connect grants the perms, then walks every prerequisite to the round trip
        val dev = FakeDevice(facts(dev = false, wd = false))
        val order = ArrayList<String>()
        val r = AdbCockpitOps.connect(ChannelMode.LOCAL_SERVER, LaunchVia.ADB, true, object : ConnectExecutor {
            override fun facts() = dev.facts()
            override fun run(step: StepId) = dev.run(step).also { order += step.name }
        }) { order += "PERMS"; "perms ok" }
        assertEquals(listOf("PERMS", "DEV_OPTIONS", "WIRELESS_DEBUG", "PAIR", "ADB_CONNECT", "LAUNCH_SERVER", "VERIFY", "CLOSE_EMBEDDED"), order)
        assertEquals(order, AdbCockpitModel.autoFixOrder(ChannelMode.LOCAL_SERVER, LaunchVia.ADB, true))
        assertEquals("Connected.", r.note); assertNull(r.failure); assertNull(r.waiting)
        assertEquals(Light.GREEN, connected(dev.f, "local").light)
        // Shizuku mode (#1709) pairs/connects embedded adb, starts the service through it (which makes "running" true), asks the grant, verifies, closes adb
        val shz = FakeDevice(facts(ChannelMode.SHIZUKU))
        AdbCockpitOps.connect(ChannelMode.SHIZUKU, LaunchVia.ADB, true, shz) { "perms ok" }
        assertEquals(listOf("PAIR", "ADB_CONNECT", "SHIZUKU_START", "SHIZUKU_PERMISSION", "VERIFY", "CLOSE_EMBEDDED"), shz.ran)
        // a step that is already true is not run again, the rest still are
        val half = FakeDevice(facts(paired = true))
        AdbCockpitOps.connect(ChannelMode.EMBEDDED_ONLY, LaunchVia.ADB, true, half) { "perms ok" }
        assertEquals(listOf("ADB_CONNECT", "VERIFY"), half.ran)
        // the page: the top Connect IS that chain; there is no separate fix control, and the page says what one tap walks
        val seen = ArrayList<Do>()
        showKit { AdbCockpitContent(inputs(facts(dev = false, wd = false)), "", "", emptyList(), CockpitActions(act = { seen += it })) }
        compose.onNodeWithTag("adbc:toggle").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Do.CONNECT), seen)
        assertFalse("a separate Auto-fix control is back", has("adbc:autofix"))
        val chain = compose.onNodeWithTag("adbc:chain").fetchSemanticsNode().config.toString()
        for (w in listOf("Perms", "Developer options on", "Wireless debugging on", "Pair with code", "Round trip")) assertTrue(w, chain.contains(w))
    }

    @Test fun `S3 a Connect that stopped for the person goes on once that step is done`() {
        val dev = FakeDevice(facts(), waitAt = StepId.PAIR)
        val r = AdbCockpitOps.connect(ChannelMode.LOCAL_SERVER, LaunchVia.ADB, true, dev) { "perms ok" }
        assertEquals(StepId.PAIR, r.waiting); assertNull("waiting for the person is not a failure", r.failure)
        // still open: nothing to go on with (it would only reopen the same screen)
        assertFalse(AdbCockpitModel.resumes(r.waiting, dev.f))
        // the code was typed: the chain goes on, and ends connected
        val paired = dev.f.copy(adbPaired = true)
        assertTrue(AdbCockpitModel.resumes(r.waiting, paired))
        val rest = FakeDevice(paired)
        assertEquals("Connected.", AdbCockpitOps.connect(ChannelMode.LOCAL_SERVER, LaunchVia.ADB, true, rest) { "perms ok" }.note)
        assertEquals(listOf("ADB_CONNECT", "LAUNCH_SERVER", "VERIFY", "CLOSE_EMBEDDED"), rest.ran)
        assertFalse(AdbCockpitModel.resumes(null, paired))
        // #1709: the Local-server chain closes the embedded session on purpose, so what is left is the server (running); with the adb session up it is connected
        assertTrue("the chain left the local server running", rest.f.serverRunning)
        assertFalse("already connected", AdbCockpitModel.resumes(StepId.PAIR, rest.f.copy(adbConnected = true)))
    }

    @Test fun `S4 every group is a card of light, step name, message, action icons rows`() {
        val f = facts(paired = true)
        val groups = AdbCockpitModel.groups(inputs(f))
        assertEquals(listOf("perms", "setup", "embedded", "local", "shizuku"), groups.map { it.id })
        assertEquals(listOf("Perms", "Setup", "Embedded", "Local Server", "Shizuku"), groups.map { it.title })
        assertEquals(listOf("pair", "connected"), group(f, "embedded").rows.map { it.id.substringAfter(':') })
        assertEquals(listOf("pair", "adbd", "server", "connected"), group(f, "local").rows.map { it.id.substringAfter(':') })
        assertEquals(listOf("service", "auth", "connected"), group(f, "shizuku").rows.map { it.id.substringAfter(':') })
        for (g in groups) for (r in g.rows) {
            assertTrue(r.id, r.name.isNotBlank() && r.message.isNotBlank())
            for (x in r.acts) assertTrue("${r.id}:${x.what}", x.what.icon.isNotBlank() && x.what.label.isNotBlank())
        }
        showKit { AdbCockpitContent(inputs(f), "", "", emptyList(), CockpitActions()) }
        for (g in groups) for (r in g.rows) {
            val light = box("adbc:light:${r.id}"); val name = box("adbc:name:${r.id}"); val msg = box("adbc:msg:${r.id}")
            assertTrue("${r.id}: the light leads the row", light.right <= name.left)
            assertTrue("${r.id}: the message follows the name", msg.left >= name.left && msg.top >= name.top)
            val text = compose.onNodeWithTag("adbc:name:${r.id}").fetchSemanticsNode().config.toString()
            assertTrue("${r.id}: name then a colon", text.contains(r.name + ":"))
            if (r.acts.isNotEmpty()) assertTrue("${r.id}: the action icons close the row", box("adbc:acts:${r.id}").left >= name.right && box("adbc:acts:${r.id}").left >= msg.right)
            else assertFalse(has("adbc:acts:${r.id}"))
        }
    }

    @Test fun `S5 Perms is one light`() {
        for (d in listOf(AdbCockpitModel.PermsDigest(2, emptyList()), AdbCockpitModel.PermsDigest(2, listOf("Write secure settings", "Notifications")), null)) {
            val g = AdbCockpitModel.groups(AdbCockpitModel.Inputs(facts(), d)).first { it.id == "perms" }
            assertEquals(1, g.rows.size)
            assertEquals(listOf(Do.OPEN_PERMS), g.rows[0].acts.map { it.what })
        }
        assertEquals(Light.GREEN to "all granted", group(facts(), "perms").rows[0].let { it.light to it.message })
        val miss = AdbCockpitModel.groups(AdbCockpitModel.Inputs(facts(paired = true), AdbCockpitModel.PermsDigest(2, listOf("Notifications")))).first().rows[0]
        assertEquals(Light.AMBER, miss.light); assertTrue(miss.message.startsWith("1 missing"))
        showKit { AdbCockpitContent(inputs(facts()), "", "", emptyList(), CockpitActions()) }
        val card = box("adbc:step:perms")
        val lights = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("a light") { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("adbc:light:") == true
        }).fetchSemanticsNodes().filter { it.boundsInRoot.top >= card.top && it.boundsInRoot.bottom <= card.bottom }
        assertEquals("the Perms card holds exactly one light", 1, lights.size)
    }

    @Test fun `S6 Setup is the two flips`() {
        val g = group(facts(wd = false), "setup")
        assertEquals(listOf("Developer Options Flipped", "Wireless Debugging Flipped"), g.rows.map { it.name })
        assertEquals(listOf(Light.GREEN to "on", Light.AMBER to "off"), g.rows.map { it.light to it.message })
        assertEquals(listOf(listOf(Do.OPEN_DEV), listOf(Do.OPEN_WD)), g.rows.map { r -> r.acts.map { it.what } })
        assertTrue(g.rows[1].acts[0].primary)
        assertEquals(listOf(Light.AMBER, Light.GREY), group(facts(dev = false, wd = null), "setup").rows.map { it.light })
        assertEquals("state unknown", group(facts(dev = false, wd = null), "setup").rows[1].message)
        // Shizuku mode does not use them: the real switch state stays on the row, and the row says so
        assertEquals("off · not used in Shizuku mode", group(facts(ChannelMode.SHIZUKU, wd = false), "setup").rows[1].message)
    }

    @Test fun `S7 Local Server carries all the actions`() {
        val all = listOf(Do.LOCAL_PAIR, Do.EMB_INIT, Do.EMB_RESET, Do.LOCAL_START, Do.LOCAL_STOP, Do.LOCAL_RESTART)
        for (f in listOf(facts(), facts(paired = true, connected = true, server = true), facts(ChannelMode.SHIZUKU))) {
            val local = group(f, "local").rows.flatMap { it.acts }.map { it.what }
            assertEquals(all, local)
            // every action of the Embedded card is on Local Server too
            assertTrue(local.containsAll(group(f, "embedded").rows.flatMap { it.acts }.map { it.what }))
        }
        // each is live exactly when it applies (a stopped server cannot be stopped), and says why when it is not
        val down = group(facts(), "local").rows.flatMap { it.acts }.associateBy { it.what }
        assertTrue(down.getValue(Do.LOCAL_PAIR).enabled); assertTrue(down.getValue(Do.EMB_INIT).enabled)
        assertFalse(down.getValue(Do.LOCAL_STOP).enabled); assertEquals("the local server is not running", down.getValue(Do.LOCAL_STOP).why)
        val live = group(facts(paired = true, connected = true, server = true), "local").rows.flatMap { it.acts }.associateBy { it.what }
        for (d in listOf(Do.LOCAL_PAIR, Do.EMB_INIT, Do.EMB_RESET, Do.LOCAL_STOP, Do.LOCAL_RESTART)) assertTrue(d.name, live.getValue(d).enabled)
        val seen = ArrayList<Do>()
        showKit { AdbCockpitContent(inputs(facts(paired = true, connected = true, server = true)), "", "", emptyList(), CockpitActions(act = { seen += it })) }
        for (d in all) compose.onNodeWithTag("adbc:act:local:${d.name.lowercase()}").assertIsDisplayed()
        for (d in listOf(Do.EMB_RESET, Do.LOCAL_STOP, Do.LOCAL_RESTART)) compose.onNodeWithTag("adbc:act:local:${d.name.lowercase()}").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Do.EMB_RESET, Do.LOCAL_STOP, Do.LOCAL_RESTART), seen)
    }

    @Test fun `S8 the logs sit below the cards`() {
        val log = listOf(ChannelLog.Entry(1_000L, "connect", "Pair with code: FAILED"))
        showKit { AdbCockpitContent(inputs(facts()), "", "", log, CockpitActions()) }
        compose.onNodeWithTag("adbc:logs").assertIsDisplayed()
        for (t in listOf("adbc:log-filter", "adbc:log-copy", "adbc:log-clear", "adbc:log-body", "adbc:log:err")) assertTrue(t, has(t))
        for (id in AdbCockpitModel.GROUP_ORDER) assertTrue(id, box("adbc:logs").top >= box("adbc:step:$id").bottom)
        assertTrue(box("adbc:control").bottom <= box("adbc:step:perms").top)
    }

    @Test fun `S9 a mode is green only on a real round trip over its own channel`() {
        // adb modes: paired is not connected; only the echo over the session turns the light green
        for ((mode, id) in listOf(ChannelMode.LOCAL_SERVER to "local", ChannelMode.EMBEDDED_ONLY to "embedded")) {
            assertEquals(Light.AMBER, connected(facts(mode), id).light)
            assertEquals(Light.AMBER, connected(facts(mode, paired = true), id).light)
            assertEquals("no round trip yet", connected(facts(mode, paired = true), id).message.substringBefore(" · "))
            assertEquals(Light.GREEN, connected(facts(mode, connected = true), id).light)
            // Shizuku fully up and answering never turns an adb mode green
            assertEquals(Light.AMBER, connected(facts(mode, paired = true, shz = up, answers = true), id).light)
            assertFalse(AdbCockpitModel.master(inputs(facts(mode, paired = true, shz = up, answers = true))).light == Light.GREEN)
        }
        // a loopback server answering as the wrong uid is not a shell
        assertEquals(Light.AMBER, connected(facts(server = true).copy(serverUid = "uid 10234"), "local").light)
        assertEquals("round trip answered over the loopback server", connected(facts(server = true), "local").message)
        // Shizuku: running and authorized is the binder's word, not a round trip
        for (a in listOf<Boolean?>(null, false)) {
            val f = facts(ChannelMode.SHIZUKU, shz = up, answers = a)
            assertEquals("answers=$a", Light.AMBER, connected(f, "shizuku").light)
            assertEquals(Light.GREEN, group(f, "shizuku").rows.first { it.id == "shizuku:auth" }.light)
        }
        val noAnswer = inputs(facts(ChannelMode.SHIZUKU, shz = up, answers = false))
        assertFalse(AdbCockpitModel.master(noAnswer).light == Light.GREEN); assertEquals(Do.CONNECT, AdbCockpitModel.toggle(noAnswer.facts).what)
        val answered = inputs(facts(ChannelMode.SHIZUKU, shz = up, answers = true))
        assertEquals(Light.GREEN, connected(answered.facts, "shizuku").light)
        assertEquals("Connected", AdbCockpitModel.master(answered).label); assertEquals(Do.DISCONNECT, AdbCockpitModel.toggle(answered.facts).what)
        // adb fully up never turns Shizuku green, and a mode the selector is not on is not shown connected
        val adbUp = facts(ChannelMode.SHIZUKU, paired = true, connected = true, server = true)
        assertEquals(Light.AMBER, connected(adbUp, "shizuku").light)
        for (id in listOf("local", "embedded")) assertEquals(id, Light.GREY, connected(adbUp, id).light)
    }

    @Test fun `S10 nothing the previous page showed was dropped`() {
        val cases = listOf(facts(), facts(wd = false), facts(paired = true, connected = true, server = true), facts(ChannelMode.EMBEDDED_ONLY, paired = true),
            facts(ChannelMode.SHIZUKU, shz = up, answers = true), facts(ChannelMode.SHIZUKU), facts(ChannelMode.AUTO, paired = true, shz = up, answers = true))
        for (f in cases) {
            val i = inputs(f)
            val steps = AdbCockpitModel.steps(i)
            val groups = AdbCockpitModel.groups(i).associateBy { it.id }
            for (s in steps.filter { it.id != "connection" }) {
                val g = groups.getValue(s.id)
                assertEquals(s.name, g.title); assertEquals(s.dim, g.dim); assertEquals(s.badge, g.badge)
                // every action, with the enablement and the reason it had
                val acts = g.rows.flatMap { it.acts }.associateBy { it.what }
                for (x in s.acts) { val y = acts.getValue(x.what); assertEquals("${s.id}:${x.what}", x.enabled to x.why, y.enabled to y.why) }
                // every sub-light is a row of its own, same light
                for (x in s.subs) assertEquals("${s.id}:${x.label}", x.light, g.rows.first { it.name.contains(x.label, ignoreCase = true) }.light)
                // the step's own light is on a row (a mode's is its Connected row)
                if (s.id != "setup") assertTrue("${s.id} light ${s.light}", g.rows.any { it.light == s.light })
            }
            // the old Connection row is the control card: its two verbs are the top bar, its message the line under them
            val conn = steps.last()
            assertEquals(listOf(Do.CONNECT, Do.RECONNECT), conn.acts.map { it.what })
            assertTrue(AdbCockpitModel.toggle(f).what in setOf(Do.CONNECT, Do.DISCONNECT))
        }
        // the three modes, the keep-alive panel and the log tools are still on the page
        val ka = AdbCockpitModel.keepAlive(on = true, armed = true, serviceRunning = true, canWrite = true, batteryExempt = true, lastRearm = "2m ago")
        showKit { AdbCockpitContent(inputs(facts()), "", "", emptyList(), CockpitActions(), keepAlive = ka) }
        for (t in listOf("adbc:toggle", "adbc:reconnect", "adbc:mode:shizuku", "adbc:mode:local", "adbc:mode:embedded", "adbc:master", "adbc:keepalive", "adbc:keepalive-toggle",
            "adbc:ka-detail", "adbc:log-filter", "adbc:log-copy", "adbc:log-clear") + ka.subs.map { "adbc:ka-sub:${it.label}" }) assertTrue(t, has(t))
        // a red step upstream: the connection's own message and what blocks it are both on the control card
        val blocked = inputs(facts(ChannelMode.SHIZUKU))
        showKit { AdbCockpitContent(blocked, "", "", emptyList(), CockpitActions()) }
        fun text(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.toString()
        assertTrue(text("adbc:msg:connection").contains(AdbCockpitModel.steps(blocked).last().message))
        assertTrue(text("adbc:detail").contains(AdbCockpitModel.master(blocked).detail)); assertTrue(text("adbc:master").contains("Blocked"))
    }

    @Test fun `S11 the page is roomy, not dense`() {
        // the measures: well above the dense Store row every other page is drawn at
        assertTrue(CockpitRoom.page >= 16.dp && CockpitRoom.page > KitStoreRow.padH)
        assertTrue(CockpitRoom.card >= 16.dp); assertTrue(CockpitRoom.row >= 16.dp && CockpitRoom.row > KitStoreRow.padV * 2)
        assertTrue(CockpitRoom.light >= 16.dp); assertTrue(CockpitRoom.icon >= 40.dp)
        assertTrue(CockpitRoom.name.value > KitStoreRow.title.value && CockpitRoom.message.value > KitStoreRow.body.value)
        // and what is drawn keeps to them
        showKit { AdbCockpitContent(inputs(facts(paired = true)), "", "", emptyList(), CockpitActions()) }
        val ids = AdbCockpitModel.GROUP_ORDER
        assertTrue(box("adbc:step:perms").top - box("adbc:control").bottom >= px(CockpitRoom.page) - 1f)
        for ((a, b) in ids.zip(ids.drop(1))) assertTrue("$a > $b", box("adbc:step:$b").top - box("adbc:step:$a").bottom >= px(CockpitRoom.page) - 1f)
        assertTrue(box("adbc:step:perms").left >= px(CockpitRoom.page) - 1f)
        val light = box("adbc:light:local:pair")
        assertTrue(light.width >= px(16.dp) - 1f && light.height >= px(16.dp) - 1f)
        assertTrue(light.left - box("adbc:step:local").left >= px(CockpitRoom.card) - 1f)
        val rows = AdbCockpitModel.groups(inputs(facts(paired = true))).first { it.id == "local" }.rows.map { box("adbc:row:${it.id}") }
        for ((a, b) in rows.zip(rows.drop(1))) assertTrue("rows are spaced", b.top - a.bottom >= px(CockpitRoom.row) - 1f)
        assertTrue(box("adbc:act:local:local_pair").height >= px(CockpitRoom.icon) - 1f)
        assertTrue(box("adbc:toggle").height >= px(40.dp))
    }
}
