package com.diegonmarcos.cloudcanvas

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.diegonmarcos.cloudcanvas.ui.AppTheme
import com.diegonmarcos.cloudcanvas.ui.CanvasShell
import com.diegonmarcos.superapp.bottomnav.FleetChrome

/**
 * Where the app opens, from the launch intent's string extras (the fleet's deep-link shape): `section` draw | play, `panel` layers (Draw),
 * `view` instruments | mixer (Play), `demo` true to fill the mixer with a sample beat. All optional.
 */
data class CanvasStart(val section: String? = null, val panel: String? = null, val view: String? = null, val demo: Boolean = false) {
    companion object {
        fun from(i: Intent?): CanvasStart = CanvasStart(
            i?.getStringExtra("section")?.takeIf { it.isNotBlank() },
            i?.getStringExtra("panel")?.takeIf { it.isNotBlank() },
            i?.getStringExtra("view")?.takeIf { it.isNotBlank() },
            i?.getStringExtra("demo") == "true" || i?.getBooleanExtra("demo", false) == true,
        )
    }
}

/** The one Activity: edge-to-edge through the fleet's FleetChrome; the bars clear their own insets. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        val start = CanvasStart.from(intent)
        setContent { AppTheme { CanvasShell(start) } }
    }
}
