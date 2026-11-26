package com.diegonmarcos.cloudsuperintel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.diegonmarcos.cloudsuperintel.AgentDecl
import com.diegonmarcos.cloudsuperintel.AgentLive
import com.diegonmarcos.cloudsuperintel.AgentState
import com.diegonmarcos.cloudsuperintel.Agents
import com.diegonmarcos.cloudsuperintel.R
import com.diegonmarcos.superapp.ai.AiAgents
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiSettingsPickers
import com.diegonmarcos.superapp.ai.AiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The test tags of Models > Agents. */
object AgentTags {
    const val PAGE = "agents_page"
    const val ROW = "agent_row_"
    const val STATUS = "agent_status_"
    const val SELECT = "agent_select_"
}

/**
 * #1939 Models > Agents: one row per agent of the declared list (assets/agents/agents.json, the composer picker's own source): icon, name,
 * what it is, where it runs, its models, the live status from the gateway's /health and Select (the composer's choice). The list never
 * waits for the gateway: the rows are the declaration, the status is layered on top (checking, then reachable / down / not deployed).
 * The composer's own Agent and Model pickers stay on top of the list.
 */
@Composable
fun AgentsPage(chat: AiChatState, theme: AiTheme, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current.applicationContext
    val rows = remember { Agents.rows(Agents.declared(ctx), AiAgents.load(ctx)) }
    // null = not asked yet, then the gateway's /health agents object, or an empty-handed answer (the gateway does not answer).
    val health by produceState<Pair<Boolean, JSONObject?>>(false to null, ctx) {
        value = true to Agents.healthAgents(withContext(Dispatchers.IO) { Agents.fetchHealth(ctx) })
    }
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(m.gutter).testTag(AgentTags.PAGE), verticalArrangement = Arrangement.spacedBy(m.gap)) {
        Text(stringResource(R.string.tools_pickers_title), color = c.text, style = t.style(t.greetingSub, FontWeight.Bold))
        Text(stringResource(R.string.tools_pickers_body), color = c.text2, style = t.style(t.small))
        AiSettingsPickers(chat, theme)
        Text(stringResource(R.string.agents_status_note, if (health.first) (if (health.second == null) stringResource(R.string.agents_gateway_down) else stringResource(R.string.agents_gateway_up)) else stringResource(R.string.agents_checking)), color = c.text2, style = t.style(t.label))
        // The "no agent" choice (the model directly) is a row too: it is the composer's first menu item.
        AgentCard(
            AgentDecl("", theme.strings.agentNone, "-", stringResource(R.string.agents_none_what), stringResource(R.string.agents_none_where), "", "", true),
            AgentLive(AgentState.REACHABLE), chat.agent.isEmpty(), true, theme, showStatus = false,
        ) { chat.chooseAgent("") }
        rows.forEach { d ->
            val live = if (!health.first && d.gateway) AgentLive(AgentState.CHECKING) else Agents.state(d, health.second)
            AgentCard(d, live, chat.agent == d.id, d.gateway, theme, showStatus = true) { chat.chooseAgent(d.id) }
        }
    }
}

@Composable
private fun AgentCard(d: AgentDecl, live: AgentLive, selected: Boolean, selectable: Boolean, theme: AiTheme, showStatus: Boolean, onSelect: () -> Unit) {
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val shape = RoundedCornerShape(m.cardRadius)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.field).border(m.hairline, if (selected) c.accent else c.tileBorder, shape).padding(m.cardPad).testTag(AgentTags.ROW + d.id.ifEmpty { "none" }),
        horizontalArrangement = Arrangement.spacedBy(m.gap), verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(m.aiAvatar).clip(RoundedCornerShape(m.cardRadius)).background(c.tile).border(m.hairline, c.tileBorder, RoundedCornerShape(m.cardRadius)), contentAlignment = Alignment.Center) {
            Text(d.icon, color = c.accent, style = t.style(t.chat, FontWeight.Bold), textAlign = TextAlign.Center)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.tiny)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.gap)) {
                Text(d.label, color = c.text, style = t.style(t.chat, FontWeight.SemiBold))
                if (showStatus) {
                    val (word, color) = when (live.state) {
                        AgentState.CHECKING -> stringResource(R.string.agents_state_checking) to c.text2
                        AgentState.REACHABLE -> stringResource(R.string.agents_state_reachable) to c.accent
                        AgentState.DOWN -> stringResource(R.string.agents_state_down) to c.negative
                        AgentState.NOT_DEPLOYED -> stringResource(R.string.agents_state_not_deployed) to c.text2
                    }
                    Text("● $word", color = color, style = t.style(t.small, FontWeight.Bold), modifier = Modifier.testTag(AgentTags.STATUS + d.id))
                }
            }
            if (d.what.isNotEmpty()) Text(d.what, color = c.text2, style = t.style(t.small))
            if (d.runsOn.isNotEmpty()) Text(stringResource(R.string.agents_where, d.runsOn), color = c.text2, style = t.style(t.small))
            if (d.models.isNotEmpty()) Text(stringResource(R.string.agents_models, d.models + if (live.model.isNotEmpty()) " · " + live.model else ""), color = c.text2, style = t.style(t.small))
        }
        Text(
            if (selected) stringResource(R.string.agents_selected) else if (selectable) stringResource(R.string.agents_select) else stringResource(R.string.agents_not_offered),
            color = if (selectable) c.accent else c.text2, style = t.style(t.small, FontWeight.Bold),
            modifier = Modifier.clip(shape).border(m.hairline, c.tileBorder, shape).clickable(enabled = selectable && !selected, onClick = onSelect)
                .padding(horizontal = m.cardPad, vertical = m.gap).testTag(AgentTags.SELECT + d.id.ifEmpty { "none" }),
        )
    }
}
