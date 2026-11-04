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
