package com.diegonmarcos.cloudcanvas

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diegonmarcos.cloudcanvas.ui.AiConfigsPage
import com.diegonmarcos.cloudcanvas.ui.AppTheme
import com.diegonmarcos.cloudcanvas.ui.CanvasShell
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsActivity
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsTab

/**
 * Where the app opens, from the launch intent's string extras (the fleet's deep-link shape): `section` draw | play, `panel` layers (Draw),
 * `view` instruments | mixer (Play), `demo` true to fill the mixer with a sample beat, `configs` ai to open Configs > AI. All optional.
 */
data class CanvasStart(val section: String? = null, val panel: String? = null, val view: String? = null, val demo: Boolean = false, val configs: String? = null) {
    companion object {
        fun from(i: Intent?): CanvasStart = CanvasStart(
            i?.getStringExtra("section")?.takeIf { it.isNotBlank() },
            i?.getStringExtra("panel")?.takeIf { it.isNotBlank() },
            i?.getStringExtra("view")?.takeIf { it.isNotBlank() },
            i?.getStringExtra("demo") == "true" || i?.getBooleanExtra("demo", false) == true,
            i?.getStringExtra("configs")?.takeIf { it.isNotBlank() },
        )
    }
}

/** The one Activity: edge-to-edge through the fleet's FleetChrome; the bars clear their own insets. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        // Configs > AI: an own tab of the fleet Configs strip, after General
        FleetConfigsExtensions.register(FleetConfigsTab("ai", "AI", "auto_awesome") { AiConfigsPage() })
        val start = CanvasStart.from(intent)
        start.configs?.let { startActivity(Intent(this, FleetConfigsActivity::class.java).putExtra(FleetConfigsActivity.EXTRA_PAGE, it)) }
        setContent { AppTheme { CanvasShell(start) } }
    }
}
