package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.fleetconfigs.FleetAboutPage
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigs
import com.diegonmarcos.superapp.fleetconfigs.FleetGeneralPage
import com.diegonmarcos.superapp.perms.AppPermsSection
import com.diegonmarcos.superapp.perms.PermsLink
import com.diegonmarcos.superapp.themebg.ThemeBackground
import kotlinx.coroutines.delay

/** #1406 Configs ▸ General | Permissions | About: the fleet's shared pages, as three modes of the Configs page. */

/** The mode id MainActivity (the app's PermsHost) opens for every "Open Permissions". */
const val CONFIGS_PERMISSIONS_MODE = "configs_permissions"

@Composable
fun FleetGeneralMode() {
    val state = LocalCalcState.current
    FleetGeneralPage(FleetConfigs.current, dark = true, c = ThemeBackground.colors(true), onAppConfigs = { state.show("jev_token") })
}

@Composable
fun FleetPermsMode() {
    // The rows a feature asked for (PermsLink.open → MainActivity.openPermsPage) are drawn first and bold.
    val asked = remember { PermsLink.consume() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    AppPermsSection(
        tick = tick,
        highlight = asked?.ids.orEmpty(),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag("calc:configs:perms"),
    )
}

@Composable
fun FleetAboutMode() {
    FleetAboutPage(FleetConfigs.current, ThemeBackground.colors(true))
}
