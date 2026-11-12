package com.diegonmarcos.cloudtoolssmallllms

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.cloudtoolssmallllms.data.ToolsData
import com.diegonmarcos.cloudtoolssmallllms.ui.ToolsModel
import com.diegonmarcos.cloudtoolssmallllms.ui.ToolsShell
import com.diegonmarcos.cloudtoolssmallllms.ui.ToolsLoadingShell
import com.diegonmarcos.cloudtoolssmallllms.ui.ToolsTheme
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/**
 * The one Activity: edge-to-edge through the lib's FleetChrome, the island clears its own inset. The app is an ENGINE (#1547): its data is read
 * at runtime from Cloud Store, so it draws a loading state until the data is there and rebuilds its model when the data changes. No config
 * I/O happens in onCreate or a constructor.
 */
class MainActivity : ComponentActivity() {
    private var model: ToolsModel? = null
    private var pendingShare: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        shared(intent)
        setContent {
            ToolsTheme {
                val data by remember { ToolsData.observe(applicationContext) }.collectAsState(initial = null)
                val d = data
                if (d == null) {
                    // #1483 the shared bars stay up while the data is missing: every page is reachable and says why it is empty
                    ToolsLoadingShell()
                } else key(d.version) {
                    val m = remember { ToolsModel(applicationContext, lifecycleScope, d).also { model = it; applyShare(it) } }
                    ToolsShell(m)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared(intent)
    }

    /** Share > Tools of plain text: into the Chat input, on the Chat page; nothing is sent until the user does. */
    private fun shared(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        pendingShare = text
        model?.let { applyShare(it) }
    }

    private fun applyShare(m: ToolsModel) {
        val text = pendingShare ?: return
        pendingShare = null
        m.chat.draft = text
        m.section = Decl.SECTION_AI; m.page = null
    }
}
