package com.diegonmarcos.cloudbrowser.search

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.ai.AiChatPage
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiEngine
import com.diegonmarcos.superapp.ai.AiTags
import com.diegonmarcos.superapp.browser.BrowserSearchPageHost
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #823 the Search add-on's on-screen page: the fleet's AI page (libs:ai) over the browser, in the browser's kit
 * palette. Its engines are cloud-search's declaration (baked SEARCH_CONFIG_B64, never restated), a result opens
 * as a tab, and the chat is libs:ai's: OpenRouter through the fleet's AI-key owner engine, which adds the key
 * (#1801, [SearchAddon.host]); "AI key" opens that engine's AI keys screen. The conversation outlives closing the page.
 */
object SearchPageScreen {
    @Volatile private var chat: AiChatState? = null
    /** Where a URL goes: the screen's "open as a tab", refreshed each time the page is shown. */
    @Volatile private var open: (String) -> Unit = {}

    /** Called once at start-up when the Search add-on is declared. */
    fun install(app: Context, search: SearchAddon) {
        BrowserSearchPageHost.page = { openTab, close ->
            open = openTab
            val c = chat ?: AiChatState(search.host(app.applicationContext) { url -> open(url) }).also { chat = it }
            val p = LocalKitPalette.current
            Box(Modifier.fillMaxSize().background(p.surface).testTag(AiTags.PAGE)) {
                AiChatPage(
                    c,
                    theme = kitAiTheme(p),
                    engines = search.cfg.engines.map { AiEngine(it.id, it.label, it.icon, it.accent) },
                    query = { e, q -> search.searchUrl(q, e.id) },
                    sessionsList = true,
                ) {
                    Row {
                        // #1801 the key is set in the AI-key owner engine's own screen, never in this app.
                        TextButton({ SearchAddon.openKeySettings(app, search.ai.accountProvider) }, Modifier.testTag("browser:search:ai_key")) { Text("AI key") }
                        TextButton(close, Modifier.testTag("browser:search:close")) { Text("✕ Close") }
                    }
                }
            }
        }
    }
}
