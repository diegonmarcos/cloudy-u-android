package com.diegonmarcos.cloudcalc.ui

import android.content.Context
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudcalc.BuildConfig
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.decide.JevStore
import com.diegonmarcos.cloudcalc.sheet.A1
import com.diegonmarcos.cloudcalc.sheet.Area
import com.diegonmarcos.cloudcalc.sheetui.SheetHub
import com.diegonmarcos.superapp.ai.AiRelay
import com.diegonmarcos.superapp.ai.AiRelayBackend
import com.diegonmarcos.superapp.ai.AiRelayed
import com.diegonmarcos.superapp.ai.AiAgents
import com.diegonmarcos.superapp.ai.AiChatPage
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiFileSessions
import com.diegonmarcos.superapp.ai.AiGatewayBackend
import com.diegonmarcos.superapp.ai.AiMemoryPrefs
import com.diegonmarcos.superapp.ai.AiStandardHost
import com.diegonmarcos.superapp.ai.AiTool
import com.diegonmarcos.superapp.ai.materialAiTheme
import org.json.JSONObject
import java.io.File

// #1893 the AI page itself (AiMode) is deleted on the owner's order and Home takes its slot; the chat state below stays, as does Ask Jev.

/** The one chat of the process. */
object CalcAi {
    @Volatile private var chat: AiChatState? = null

    fun chat(ctx: Context): AiChatState = chat ?: synchronized(this) { chat ?: build(ctx).also { chat = it } }

    private fun build(ctx: Context): AiChatState {
        val cfg = AiConfig.load(ctx)
        // #1801 no key here: the chat body goes to the AI-key owner engine (use calc_jev, endpoint chat), which adds it.
        val relay = AiRelay { body -> JevStore.relay(ctx.applicationContext, "chat", body, false).let { r -> if (r.ok) AiRelayed(r.code, r.body) else AiRelayed(0, "", r.why) } }
        return AiChatState(AiStandardHost(
            cfg, AiFileSessions(File(ctx.filesDir, "ai-sessions.json")), AiMemoryPrefs(cfg.defaultModel),
            direct = AiRelayBackend(cfg, relay),
            gateway = AiGatewayBackend(cfg, BuildConfig.AI_GATEWAY_URL),
            agents = AiAgents.load(ctx),
            tools = SheetTools.all(ctx),
        ))
    }
}

/** The AI's hands on the Sheet page: read the selection (or a range), write cells. Both run on the main thread through [SheetHubState.onMain]. */
object SheetTools {
    fun all(ctx: Context): List<AiTool> = listOf(Read(ctx), Write(ctx))

    private fun area(arg: String?, fallback: Area): Area = arg?.takeIf { it.isNotBlank() }?.let { A1.parseArea(it) } ?: fallback

    class Read(private val ctx: Context) : AiTool {
        override val name = "sheet_read"
        override val description = "Read the Cloud Calc spreadsheet: the user's current selection, or the range given. Returns the values as shown and the formulas, tab-separated."
        override val schema = """{"type":"object","properties":{"range":{"type":"string","description":"A1-style range such as A1:C5; default is the current selection"},"sheet":{"type":"string","description":"sheet name; default is the open sheet"}}}"""
        override fun run(args: JSONObject): String {
            val st = SheetHub.get(ctx)
            return st.onMain {
                val sheet = args.optString("sheet").ifBlank { st.sheet }
                if (st.wb.find(sheet) == null) return@onMain "error: no sheet called $sheet"
                val (ec, er) = st.wb.extent(sheet)
                val a0 = area(args.optString("range"), st.area)
                val a = Area(a0.c1, a0.r1, minOf(a0.c2, maxOf(ec, a0.c1)), minOf(a0.r2, maxOf(er, a0.r1)))
                "sheet: $sheet\nrange: ${a.label()}\nvalues:\n${st.wb.toTsv(sheet, a)}formulas:\n${st.wb.toTsv(sheet, a, formulas = true)}"
            }
        }
    }

    class Write(private val ctx: Context) : AiTool {
        override val name = "sheet_write"
        override val description = "Write cells into the Cloud Calc spreadsheet from a start cell. Each value is typed as a user would: =formula, number, or text."
        override val schema = """{"type":"object","properties":{"start":{"type":"string","description":"top-left cell such as B2; default is the selection's top-left"},"sheet":{"type":"string"},"rows":{"type":"array","items":{"type":"array","items":{"type":"string"}},"description":"rows of cell inputs"}},"required":["rows"]}"""
        override val mutating = true
        override fun sentence(args: JSONObject): String {
            val rows = args.optJSONArray("rows")
            val n = (0 until (rows?.length() ?: 0)).sumOf { rows!!.optJSONArray(it)?.length() ?: 0 }
            return "Write $n cell(s) into the spreadsheet starting at ${args.optString("start").ifBlank { "the selected cell" }}"
        }
        override fun run(args: JSONObject): String {
            val st = SheetHub.get(ctx)
            return st.onMain {
                val sheet = args.optString("sheet").ifBlank { st.sheet }
                if (st.wb.find(sheet) == null) return@onMain "error: no sheet called $sheet"
                val rows = args.optJSONArray("rows") ?: return@onMain "error: rows is required"
                val start = args.optString("start").ifBlank { null }?.let { A1.parseCell(it) } ?: (st.area.c1 to st.area.r1)
                var count = 0
                st.wb.batch {
                    for (r in 0 until rows.length()) {
                        val row = rows.optJSONArray(r) ?: continue
                        for (c in 0 until row.length()) {
                            st.wb.set(sheet, start.first + c, start.second + r, row.optString(c))
                            count++
                        }
                    }
                }
                st.bump()
                "wrote $count cell(s) from ${A1.cell(start.first, start.second)} on $sheet"
            }
        }
    }
}
