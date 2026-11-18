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
 * Where the app opens, from the launch intent's string extras (the fleet's deep-link shape): `section` draw | play, `page` draw | board (the
 * Draw section's sibling pages, #1787), `tool` the Board's starting tool (draw, geo, text, note...), `panel` layers (Draw),
 * `view` instruments | mixer (Play), `demo` true to fill the mixer with a sample beat, `configs` ai to open Configs > AI. #1808 `section painter`
 * opens Painter; `shape` hex | square | triangle | octagon, `layout` 2d | 3d, `mode` paint | rubber | view, `color` #rrggbb and `scale` 15..100
 * are handed to the Painter page as launch overrides (the screenshot job uses them). #1808 nav: `section board` opens the Board entry,
 * `section enhance` opens Enhancers (page enhance | music), and the old `section music` opens Enhancers > Music Enhancer. All optional.
 */
data class CanvasStart(val section: String? = null, val panel: String? = null, val view: String? = null, val demo: Boolean = false, val configs: String? = null, val page: String? = null, val tool: String? = null, val painter: Map<String, String> = emptyMap()) {
    companion object {
        fun from(i: Intent?): CanvasStart = of(
            i?.getStringExtra("section")?.takeIf { it.isNotBlank() },
            i?.getStringExtra("panel")?.takeIf { it.isNotBlank() },
            i?.getStringExtra("view")?.takeIf { it.isNotBlank() },
            i?.getStringExtra("demo") == "true" || i?.getBooleanExtra("demo", false) == true,
            i?.getStringExtra("configs")?.takeIf { it.isNotBlank() },
            i?.getStringExtra("page")?.takeIf { it.isNotBlank() },
            i?.getStringExtra("tool")?.takeIf { it.isNotBlank() },
            PAINTER_EXTRAS.mapNotNull { k -> i?.getStringExtra(k)?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap(),
        )

        /** #1808 nav: a legacy section id (`section music`, now Enhancers > Music Enhancer) is rewritten to its section and page. */
        fun of(section: String?, panel: String?, view: String?, demo: Boolean, configs: String?, page: String?, tool: String?, painter: Map<String, String>): CanvasStart {
            val (s, p) = Decl.resolve(section, page)
            return CanvasStart(s, panel, view, demo, configs, p, tool, painter)
        }

        /** The launch extras handed to the Painter page (its query string). */
        val PAINTER_EXTRAS = listOf("shape", "layout", "mode", "color", "scale")
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
