package com.diegonmarcos.hardtechcad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diegonmarcos.hardtechcad.ui.AppShell
import com.diegonmarcos.hardtechcad.ui.AppTheme
import com.diegonmarcos.hardtechcad.ui.CadXrayPage
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/** The one Activity: edge-to-edge through the fleet's FleetChrome; the bars clear their own insets. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        // #1631 the Configs > X-Ray App tab of the fleet tail draws this app's render-pipeline diagnostics (GPU, WebGL, assets, first failing stage), not the generic profiler.
        FleetConfigsExtensions.registerXrayPage { dark -> CadXrayPage(dark) }
        setContent { AppTheme { AppShell() } }
    }
}
