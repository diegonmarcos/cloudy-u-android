package com.diegonmarcos.superapp.fleetconfigs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

/**
 * Configs > General and Configs > About for an app drawn with Views or a WebView: the same [FleetConfigsScreen] the Compose scaffold
 * shows, in an activity of its own. Such an app links libs:ui-nav-bars and starts [intent] from its settings menu; the declaration is
 * the app's build.json::configs_pages, baked like everywhere else. "App" in General closes this and returns to the app's own settings.
 */
public class FleetConfigsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startId = intent.getStringExtra(EXTRA_PAGE)
        val page = FleetConfigsPage.entries.firstOrNull { it.id == startId } ?: FleetConfigsPage.General
        val dark = intent.getBooleanExtra(EXTRA_DARK, true)
        setContent { FleetConfigsScreen(page, dark, onDismiss = { finish() }, onAppConfigs = { finish() }, startId = startId) }
    }

    public companion object {
        public const val EXTRA_PAGE: String = "fleet_configs_page"
        public const val EXTRA_DARK: String = "fleet_configs_dark"
        /** #1630 the implicit action a WebView host (Tauri: no Kotlin) fires with `intent:#Intent;action=...;package=<own id>;S.fleet_configs_page=<tab id>;end`. */
        public const val ACTION_OPEN: String = "com.diegonmarcos.fleetconfigs.OPEN"

        public fun intent(context: Context, page: FleetConfigsPage, dark: Boolean = true): Intent =
            Intent(context, FleetConfigsActivity::class.java).putExtra(EXTRA_PAGE, page.id).putExtra(EXTRA_DARK, dark)
    }
}

/**
 * The two entries for an app drawn with Views (an options or popup menu): [addTo] adds "Configs ▸ General" and
 * "Configs ▸ About" to [android.view.Menu]; [handle] starts the page when the selected item is one of them. A fork wires
 * Configs with these two calls instead of writing its own menu items.
 */
public object FleetConfigsEntries {
    public const val ID_GENERAL: Int = 0x0F1C0001
    public const val ID_ABOUT: Int = 0x0F1C0002

    @JvmStatic @JvmOverloads
    public fun addTo(menu: android.view.Menu, order: Int = android.view.Menu.NONE) {
        if (menu.findItem(ID_GENERAL) == null) menu.add(android.view.Menu.NONE, ID_GENERAL, order, fleetConfigsEntryLabel(FleetConfigsPage.General))
        if (menu.findItem(ID_ABOUT) == null) menu.add(android.view.Menu.NONE, ID_ABOUT, order, fleetConfigsEntryLabel(FleetConfigsPage.About))
    }

    @JvmStatic
    public fun handle(context: Context, item: android.view.MenuItem): Boolean = when (item.itemId) {
        ID_GENERAL -> { open(context, FleetConfigsPage.General); true }
        ID_ABOUT -> { open(context, FleetConfigsPage.About); true }
        else -> false
    }

    /**
     * For a Views settings screen with no menu: inserts the two entries as buttons ([FleetConfigsButtons] in a
     * ComposeView) into [container] at [index] (default: first), so the app needs no Compose dependency of its own.
     */
    @JvmStatic @JvmOverloads
    public fun addButtons(container: android.view.ViewGroup, index: Int = 0): android.view.View {
        val v = androidx.compose.ui.platform.ComposeView(container.context).apply {
            setContent {
                androidx.compose.material3.MaterialTheme(
                    colorScheme = if ((container.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme(),
                ) {
                    FleetConfigsButtons(androidx.compose.ui.Modifier.padding(horizontal = androidx.compose.ui.unit.Dp(16f), vertical = androidx.compose.ui.unit.Dp(8f)))
                }
            }
        }
        container.addView(v, index.coerceIn(0, container.childCount))
        return v
    }

    @JvmStatic
    public fun open(context: Context, page: FleetConfigsPage) {
        val dark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        context.startActivity(FleetConfigsActivity.intent(context, page, dark).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * The two entries for a Compose shell that does not draw the shared first row: a Configs icon whose menu holds
 * "Configs ▸ General" and "Configs ▸ About" (test tags navtop1_configs_general / navtop1_configs_about, the same the
 * scaffold's dropdown uses, so the runtime nav walk opens both the same way).
 */
@androidx.compose.runtime.Composable
public fun FleetConfigsMenu(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    tint: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.material3.IconButton(
            onClick = { open = true },
            modifier = androidx.compose.ui.Modifier.testTagAsId("navtop1_dropdown_open"),
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Settings, contentDescription = "Configs",
                tint = if (tint == androidx.compose.ui.graphics.Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint,
            )
        }
        androidx.compose.material3.DropdownMenu(
            expanded = open, onDismissRequest = { open = false },
            modifier = androidx.compose.ui.Modifier.testTagAsId("navtop1_dropdown"),
        ) {
            for ((page, tag) in listOf(FleetConfigsPage.General to "navtop1_configs_general", FleetConfigsPage.About to "navtop1_configs_about")) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { androidx.compose.material3.Text(fleetConfigsEntryLabel(page)) },
                    onClick = { open = false; FleetConfigsEntries.open(ctx, page) },
                    modifier = androidx.compose.ui.Modifier.testTagAsId(tag),
                )
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun androidx.compose.ui.Modifier.testTagAsId(tag: String): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.semantics { testTagsAsResourceId = true }).testTag(tag)

/**
 * The two entries as buttons, for an app whose own settings page is Compose (Cloud Wallet's Config tab, a fork's
 * settings screen): "Configs ▸ General" and "Configs ▸ About", same test tags as the scaffold's dropdown.
 */
@androidx.compose.runtime.Composable
public fun FleetConfigsButtons(modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.foundation.layout.Row(
        modifier.then(androidx.compose.ui.Modifier.fillMaxWidth()),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(androidx.compose.ui.unit.Dp(8f)),
    ) {
        for ((page, tag) in listOf(FleetConfigsPage.General to "navtop1_configs_general", FleetConfigsPage.About to "navtop1_configs_about")) {
            androidx.compose.material3.FilledTonalButton(
                onClick = { FleetConfigsEntries.open(ctx, page) },
                modifier = androidx.compose.ui.Modifier.weight(1f).testTagAsId(tag),
            ) { androidx.compose.material3.Text(fleetConfigsEntryLabel(page)) }
        }
    }
}
