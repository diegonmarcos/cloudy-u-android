package com.diegonmarcos.superapp.launcher.themes.minimalist

import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.lockOnDoubleTap

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitComposeFragment

/**
 * The Cloud Minimalist Black mode's home screen: every installed app as one
 * line of terminal output, with a live filter.
 *
 * ── What changed, and why ────────────────────────────────────────────────
 * This screen used to draw itself through the shared
 * `com.diegonmarcos.superapp.ui.LauncherPalette`, which meant its colours,
 * its type sizes and its paddings all came from the same place the default
 * launcher's did. The owner's rule — "all themes have their own folders, all
 * different, nothing can be equal" — makes that the wrong dependency: a mode
 * that shares a palette can only ever be a recolouring of whatever else uses
 * it. Everything visual now comes from [MinimalistDesign], which this folder
 * owns, and there is no palette in scope here to borrow from by accident.
 *
 * Behaviour is deliberately unchanged: same app source, same filter, same
 * double-tap-to-lock. This was a redesign, not a rewrite of what it does.
 * Compose since #773 (a LazyColumn of lines under the mode's own palette).
 */
class MinimalistBlackFragment : KitComposeFragment() {

    override fun palette() = MinimalistDesign.kit

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val view = LocalView.current
        // LauncherApps.getActivityList is fast on a populated device but not
        // free — it stays on the UI thread because the system already caches
        // it internally and a background hop would only add a frame of blank.
        val allApps = remember { loadApps(ctx) }
        var filter by rememberSaveable { mutableStateOf("") }
        val visible = allApps.filter { matches(it.label, filter) }

        // Edge-to-edge: pad the CONTENT so the black still reaches the screen
        // edges and only the text clears the status and navigation bars.
        MinimalistDesign.Page(Modifier
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            .padding(vertical = MinimalistDesign.GAP.dp)
            // Double-tap anywhere on the home root → lock the screen. App rows still receive
            // single taps via their own handlers; the root only sees events the rows did not claim.
            .lockOnDoubleTap(ctx)) {
            MinimalistDesign.Head("~/apps")
            MinimalistDesign.Space(MinimalistDesign.GAP)

            // The filter is the only input in this mode, so it is styled as a
            // continuation of the prompt rather than as a form field: no border,
            // no container, same face and same colour as everything above it.
            BasicTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true,
                textStyle = TextStyle(color = MinimalistDesign.INK, fontFamily = MinimalistDesign.FACE,
                    fontSize = MinimalistDesign.TYPE_BODY.sp),
                cursorBrush = SolidColor(MinimalistDesign.GLOW),
                modifier = Modifier.fillMaxWidth().padding(bottom = MinimalistDesign.GAP.dp).testTag(TAG_FILTER),
                decorationBox = { inner ->
                    Box {
                        if (filter.isEmpty()) MinimalistDesign.Body("${MinimalistDesign.PROMPT}filter", colour = MinimalistDesign.INK_DIM)
                        inner()
                    }
                },
            )
            MinimalistDesign.Rule()

            LazyColumn(Modifier.weight(1f).testTag(TAG_LIST)) {
                items(visible) { app ->
                    MinimalistDesign.Line(app.label, Modifier.testTag(lineTag(app.label))) {
                        Haptics.tap(view)
                        runCatching { app.launch() }
                    }
                }
                if (visible.isEmpty()) item {
                    Column {
                        MinimalistDesign.Space(MinimalistDesign.GAP)
                        MinimalistDesign.Hint("— no matches —", Modifier.testTag(TAG_NONE))
                    }
                }
            }

            MinimalistDesign.Rule()
            MinimalistDesign.Space(MinimalistDesign.GAP_TIGHT)
            MinimalistDesign.Hint("tap to launch  ·  double-tap to lock")
        }
    }

    private data class AppRow(
        val label: String,
        val launch: () -> Unit,
    )

    private fun loadApps(ctx: Context): List<AppRow> {
        val launcherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return emptyList()
        val user = Process.myUserHandle()
        return runCatching {
            launcherApps.getActivityList(null, user)
                .map { info ->
                    val label = info.label?.toString().orEmpty()
                    val component = info.componentName
                    AppRow(
                        label = label.ifBlank { component.packageName },
                        launch = {
                            if (!com.diegonmarcos.superapp.launcher.SelfTile.open(ctx, component.packageName))
                                launcherApps.startMainActivity(component, user, null, null)
                        },
                    )
                }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val TAG_FILTER = "minimalist:filter"
        const val TAG_LIST = "minimalist:list"
        const val TAG_NONE = "minimalist:none"
        fun lineTag(label: String) = "minimalist:line:$label"

        /** The filter: case-insensitive substring over the label; blank matches everything. */
        internal fun matches(label: String, filter: String): Boolean {
            val needle = filter.trim().lowercase()
            return needle.isBlank() || label.lowercase().contains(needle)
        }

        fun newInstance() = MinimalistBlackFragment()
    }
}
