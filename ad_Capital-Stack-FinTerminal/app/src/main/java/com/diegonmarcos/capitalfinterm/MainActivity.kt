package com.diegonmarcos.capitalfinterm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diegonmarcos.capitalfinterm.ui.AppShell
import com.diegonmarcos.capitalfinterm.ui.AppTheme
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/** The one Activity: edge-to-edge through the fleet's FleetChrome; the bars clear their own insets. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        // `--es dashboard <id>` opens that dashboard (a deep link for the CI emulator walk and the launcher); unknown ids are ignored
        // `finterm://dashboard/<id>` (ACTION_VIEW) is the same deep link for other apps and the launcher
        val start = intent?.getStringExtra(EXTRA_DASHBOARD) ?: intent?.data?.takeIf { it.scheme == "finterm" }?.lastPathSegment
        setContent { AppTheme { AppShell(startDashboard = start) } }
    }

    companion object {
        const val EXTRA_DASHBOARD = "dashboard"
    }
}
