package com.diegonmarcos.superapp.navbartop1

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.diegonmarcos.superapp.bottomnav.AppTopBarController
import com.diegonmarcos.superapp.bottomnav.AppTopBarLabels
import com.diegonmarcos.superapp.bottomnav.navTopRowFill
import com.diegonmarcos.superapp.themebg.ThemeColors

/**
 * Clean mode's first row: TRANSPARENT (the app's background shows through, [navTopRowFill]) and nothing but the top-left hamburger, which
 * opens the left drawer that holds the page dropdown, the AI / Profile / Settings icons and the child-page dropdown ([CleanDrawerContent]).
 * The Classic first row has no hamburger (ChromeScrollGuardTest): the only one in this lib is HERE, drawn in Clean mode alone.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun NavBarTopCleanRow(colors: ThemeColors, labels: AppTopBarLabels, controller: AppTopBarController, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    Row(
        modifier.semantics { testTagsAsResourceId = true }.fillMaxWidth().height(TopFirstRowSpec.HEIGHT).background(navTopRowFill()).testTag(TopFirstRowTags.ROW),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Box(
            Modifier.size(TopFirstRowSpec.ICON_TARGET).testTag(CleanDrawerTags.MENU)
                .clickable(role = Role.Button) { controller.openDrawer() }.semantics { contentDescription = labels.menu },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Menu, contentDescription = null, tint = colors.ink, modifier = Modifier.size(TopFirstRowSpec.ICON)) }
    }
}
