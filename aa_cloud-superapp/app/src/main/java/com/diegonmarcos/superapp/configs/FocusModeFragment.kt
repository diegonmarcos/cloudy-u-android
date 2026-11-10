package com.diegonmarcos.superapp.configs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.settings.FocusMode
import com.diegonmarcos.superapp.settings.LauncherModes
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSelectableTile
import com.diegonmarcos.superapp.uikit.KitTags

/**
 * Configs ▸ Focus Mode (#1596), page `config/focus`.
 *
 * One switch: turn Focus on or off. On = [FocusMode.setActive] true, which other
 * parts read to quieten themselves (AlertsNotifier keeps non-urgent alerts
 * silent). The heading words are NOT written here: they come from the SAME
 * declaration the Presets ▸ Modes "Focus Mode" tile reads
 * (build.json::ui.launcher_modes[id=focus]), so the page and the tile can never
 * drift. This page owns no engine — only the one boolean.
 */
class FocusModeFragment : KitComposeFragment() {

    private var on by mutableStateOf(false)

    override fun palette() = LauncherPalette.kit(requireContext())

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        on = FocusMode.active(requireContext())
        return super.onCreateView(inf, c, s)
    }

    @Composable
    override fun Content() {
        val view = LocalView.current
        val ctx = requireContext()
        val row = LauncherModes.load().firstOrNull { it.id == FocusMode.MODE_ID }
        val label = row?.label ?: "Focus Mode"
        val subtitle = row?.subtitle ?: "Quiet mode — non-essential interruptions are held back."
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            KitSectionHeader(label, subtitle, Modifier.testTag(KitTags.header("focus")))
            KitSelectableTile(
                if (on) "On" else "Off",
                if (on) "Focus is on — non-essential alerts stay silent. Tap to turn off."
                else "Tap to turn Focus on and hold back non-essential interruptions.",
                on,
                onClick = {
                    Haptics.tap(view)
                    on = !on
                    FocusMode.setActive(ctx, on)
                },
                modifier = Modifier.testTag(KitTags.tile("focus:toggle")),
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    companion object { fun newInstance() = FocusModeFragment() }
}
