package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.diegonmarcos.cloudc3.cloud.MeshView
import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.GraphState
import com.diegonmarcos.cloudc3.pages.ModeStore
import com.diegonmarcos.cloudc3.pages.NetGraph
import com.diegonmarcos.cloudc3.pages.NetGraphScreen
import com.diegonmarcos.cloudc3.pages.NetLive
import com.diegonmarcos.cloudc3.pages.NetPages
import com.diegonmarcos.cloudc3.pages.NetTags
import com.diegonmarcos.cloudc3.pages.NetViewerScreen
import com.diegonmarcos.cloudc3.pages.OpsMapScreen
import com.diegonmarcos.cloudc3.pages.OpsMaps
import com.diegonmarcos.cloudc3.pages.OpsTags
import com.diegonmarcos.cloudc3.pages.TopologyModel
import com.diegonmarcos.cloudc3.pages.TopologyScreen
import com.diegonmarcos.cloudc3.pages.TopologyTags
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1955 A Every Network page has [Table|Graph]: Table is the old body with its old tags, Graph the canvas; a node opens its sheet; the mode is remembered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetViewerComposeTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val dir = FleetFixtures.moduleDir()
    private fun json(f: String): JsonObject = Json.parseToJsonElement(File(dir, "data/$f").readText()).jsonObject
    private val opsMaps = json("ops_maps.json")
    private val catalog = ContainersModel.parse(File(dir, "data/containers_declared.json").readText())!!
    private val infra = TopologyModel.parseInfra(json("infra_slice.json"))
    private val slice = FleetFixtures.slice()
    private val src = OpsMaps.Sources(opsMaps, json("mesh.json"), infra, json("infra_slice.json"), catalog, slice.domains)
    private val topo = TopologyModel.Inputs(catalog, slice, infra)
    private val inputs = NetGraph.Inputs(src, slice, topo, json("mesh.json"))
    private val v = NetGraph.parseViewers(opsMaps)
    private val palette = KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Cyan, Color.Gray, Color.Black)
    private val hub = slice.vms.first { it.id == infra.subnets.first().hub }

    private class MapStore : ModeStore {
        val m = mutableMapOf<String, String>()
        override fun get(page: String, default: String) = m[page] ?: default
        override fun set(page: String, mode: String) { m[page] = mode }
    }

    private fun state(page: String, hubDown: Boolean = false): GraphState {
        val g = NetGraph.page(v, page, inputs)!!.second
        val s = if (!hubDown) NetLive.Snapshot() else NetLive.Snapshot(
            deployed = slice.vms.filter { it.wgIp != null }.associate { vm -> vm.alias to if (vm.alias == hub.alias) TopologyModel.Deployed.Blind("timeout") else TopologyModel.Deployed.Seen(emptyList()) },
            api = true, at = 1L)
        val r = NetLive.applyLive(g, s, v)
        return GraphState(g, r, NetLive.rootCause(g, r, s), s.anyRead, "", "")
    }

    @androidx.compose.runtime.Composable
    private fun table(view: String, focus: String?) {
        val map = NetPages.mapOf(view)
        when {
            map != null -> OpsMapScreen(map, OpsMaps.summary(opsMaps, map), OpsMaps.build(map, src), focus)
            view == "network" -> TopologyScreen(TopologyModel.NETWORK, topo, null, emptyMap(), {}, { _, _ -> })
            else -> MeshView.Render()
        }
    }

    @Test fun `the switch is on all six pages, Table shows the old body's tags and Graph the canvas`() {
        var page by mutableStateOf("net_wg")
        val store = MapStore()
        compose.setContent {
            CloudKitTheme(palette) {
                key(page) {
                    NetViewerScreen(page, v, store, table = { f -> table(page.removePrefix("net_"), f) },
                        graph = { open -> NetGraphScreen(page, state(page), v, open, {}, {}, {}) })
                }
            }
        }
        NetPages.VIEWS.forEach { view ->
            val id = "net_$view"
            compose.runOnIdle { page = id }
            compose.waitForIdle()
            compose.onNodeWithTag(NetTags.view(id, "table")).assertExists()
            compose.onNodeWithTag(NetTags.view(id, "graph")).assertExists()
            when (view) {
                "network" -> compose.onNodeWithTag(TopologyTags.screen(TopologyModel.NETWORK)).assertExists()
                "mesh" -> compose.onNodeWithTag("c3:mesh:wg0").assertExists()
                else -> compose.onNodeWithTag(OpsTags.map(NetPages.mapOf(view)!!)).assertExists()
            }
            compose.onNodeWithTag(NetTags.view(id, "graph")).performClick()
            compose.onNodeWithTag(NetTags.graph(id)).assertExists()
            assertEquals("graph", store.m[id])
        }
    }

    @Test fun `a search hit opens the node sheet with its edges and three actions, and Open in Table returns to Table on that row`() {
        val store = MapStore().apply { set("net_auth", "graph") }
        compose.setContent {
            CloudKitTheme(palette) {
                NetViewerScreen("net_auth", v, store, table = { f -> table("auth", f) },
                    graph = { open -> NetGraphScreen("net_auth", state("net_auth"), v, open, {}, {}, {}) })
            }
        }
        compose.onNodeWithTag(NetTags.SEARCH).performTextInput("Authelia")
        compose.onNodeWithTag(NetTags.hit("gate:Authelia")).performClick()
        compose.onNodeWithTag(NetTags.sheet("gate:Authelia")).assertExists()
        compose.onNodeWithText("out-edges").assertExists()
        compose.onNodeWithText("in-edges").assertExists()
        compose.onNodeWithTag(NetTags.OPEN_CONTAINER).assertExists()
        compose.onNodeWithTag(NetTags.REPROBE).assertExists()
        compose.onNodeWithTag(NetTags.OPEN_TABLE).performClick()
        compose.onNodeWithTag(OpsTags.map("auth")).assertExists()
        compose.onNodeWithTag(OpsTags.row("auth", "Authelia")).assertExists()
        assertEquals("table", store.m["net_auth"])
    }

    @Test fun `the root-cause banner shows with the hub down, and the not-read banner with nothing read`() {
        var down by mutableStateOf(true)
        compose.setContent { CloudKitTheme(palette) { key(down) { NetGraphScreen("net_wg", state("net_wg", hubDown = down), v, {}, {}, {}, {}) } } }
        compose.onNodeWithTag(NetTags.BANNER).assertExists()
        compose.runOnIdle { down = false }
        compose.onNodeWithTag(NetTags.BANNER).assertDoesNotExist()
        compose.onNodeWithTag(NetTags.NOT_READ).assertExists()
    }

    @Test fun `the mode survives recreation`() {
        val store = MapStore()
        var gen by mutableStateOf(0)
        compose.setContent {
            CloudKitTheme(palette) {
                key(gen) {
                    NetViewerScreen("net_dns", v, store, table = { f -> table("dns", f) },
                        graph = { open -> NetGraphScreen("net_dns", state("net_dns"), v, open, {}, {}, {}) })
                }
            }
        }
        compose.onNodeWithTag(NetTags.view("net_dns", "graph")).performClick()
        compose.runOnIdle { gen += 1 }
        compose.onNodeWithTag(NetTags.graph("net_dns")).assertExists()
    }
}
