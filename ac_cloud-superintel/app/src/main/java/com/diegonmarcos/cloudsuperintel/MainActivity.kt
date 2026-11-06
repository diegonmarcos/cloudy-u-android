package com.diegonmarcos.cloudsuperintel

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diegonmarcos.cloudsuperintel.ui.SiShell
import com.diegonmarcos.cloudsuperintel.ui.SiTheme
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/** The one Activity: edge-to-edge through the fleet's FleetChrome; the bars clear their own insets. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        shared(intent)
        opened(intent)
        setContent { SiTheme { SiShell() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shared(intent)
        opened(intent)
    }

    /** #1543 OPEN with section/page (the SuperApp AI badge opens Cost): handed to the shell through [SiOpen]. */
    private fun opened(i: Intent?) {
        val l = com.diegonmarcos.superapp.notificationcenter.AiUsageModel.SuperIntelLink
        SiOpen.target(i?.action, i?.getStringExtra(l.EXTRA_SECTION), i?.getStringExtra(l.EXTRA_PAGE))?.let { SiOpen.pending = it }
    }

    /** Share > SI of plain text: into the chat box; nothing is sent until the user does. */
    private fun shared(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return
        SiAi.chat(applicationContext).draft = text
    }
}
