package com.diegonmarcos.superapp.fleetagents

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.fleetconfigs.FleetAgentRow
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigs
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsTab
import com.diegonmarcos.superapp.themebg.ThemeBackground

/**
 * Registers the Agents tab on the shared Configs strip for every app that links this lib (#1630). The strip order is
 * [General + own tabs] | Agents, Perms, Logcat, X-Ray App, About; this tab is the first after the `|`.
 */
public object FleetAgentsTab {
    public const val ID: String = FleetConfigsExtensions.AGENTS_ID
    public const val TAG: String = "fleetcfg_agents"

    public fun register() {
        FleetConfigsExtensions.register(FleetConfigsTab(ID, "Agents", "smart_toy") { dark -> FleetAgentsPage(FleetConfigs.current.agents, dark) })
    }
}

/** The Agents page: one card per agent this app declares in build.json::fleet_agents, or a plain statement when it declares none. */
@Composable
public fun FleetAgentsPage(agents: List<FleetAgentRow>, dark: Boolean) {
    val c = ThemeBackground.colors(dark)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp).testTag(FleetAgentsTab.TAG)) {
        if (agents.isEmpty()) {
            Text("This app declares no agents (build.json::fleet_agents is empty).", color = c.inkDim, fontSize = 13.sp)
        }
        agents.forEach { a ->
            Column(
                Modifier.fillMaxWidth().padding(bottom = 10.dp).border(1.dp, c.islandBorder, RoundedCornerShape(12.dp)).padding(12.dp)
                    .testTag(FleetAgentsTab.TAG + "_" + a.id),
            ) {
                Text(a.name, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (a.kind.isNotBlank()) Text(a.kind, color = c.inkDim, fontSize = 11.sp)
                if (a.summary.isNotBlank()) Text(a.summary, color = c.ink, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                if (a.trigger.isNotBlank()) Text("Runs: " + a.trigger, color = c.inkDim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** Registers [FleetAgentsTab] once per process at start (merged from this lib's manifest; not exported, answers no query). */
public class FleetAgentsInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        FleetAgentsTab.register()
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
