package com.diegonmarcos.cloudsearch

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.cloudsearch.ui.NAV
import com.diegonmarcos.cloudsearch.ui.SearchState
import com.diegonmarcos.cloudsearch.ui.Tags

/** #1472 a section by the island when it is one of the six tabs; a parked section (chat, news, media, shopping, brokers) is off the island and opens through the state. */
fun ComposeTestRule.goTo(state: SearchState, id: String) {
    if (id in NAV.bottomNav) onNodeWithTag(Tags.nav(id)).performClick() else runOnUiThread { state.openSection(id) }
}

/**
 * #1547 the composer declaration is a served dataset (Cloud Store provider), not an APK asset: with no provider under Robolectric the chat shows
 * libs:ai's default (Agent, Model) bar. This seeds the app's own last-known-good copy of `composer` from the served source of truth
 * (data/fleet/app/com.diegonmarcos.cloudsearch/composer.json), which is what FleetData falls back to, so the tests see the bar the phone shows once synced.
 */
fun seedServedComposer(app: android.content.Context) {
    val lkg = java.io.File(app.filesDir, "fleetdata-lkg").apply { mkdirs() }
    java.io.File(lkg, "app__${app.packageName}__composer.json")
        .writeBytes(java.io.File("../../data/fleet/app/com.diegonmarcos.cloudsearch/composer.json").readBytes())
}
