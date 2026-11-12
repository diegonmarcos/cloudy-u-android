package com.diegonmarcos.cloudnav.tracker

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import kotlinx.coroutines.launch

/** Timeline > Steps ([StepsScreen]): Health Connect steps, with the on-device counter as a labelled fallback. */
class StepsFragment : KitComposeFragment() {
    private var ui by mutableStateOf(StepsUi(loading = true))
    private var period by mutableStateOf(Period.DAY)

    /** #1515 the Health Connect read grant lives in Configs > Perms (row health_connect); [onResume] reloads on return. */
    private fun askHealth() { runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(requireContext(), "Steps", listOf("health_connect")) } }

    override fun palette(): KitPalette = TrackerInk.palette()

    override fun onResume() { super.onResume(); load() }

    @Composable
    override fun Content() {
        StepsScreen(ui, period, { period = it },
            onGrant = { askHealth() },
            onInstall = {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"))
                        .setPackage("com.android.vending"))
                }
            })
    }

    private fun load() {
        val repo = StepsRepository(requireContext().applicationContext)
        viewLifecycleOwner.lifecycleScope.launch { ui = runCatching { repo.load() }.getOrElse { ui.copy(loading = false) } }
    }
}
