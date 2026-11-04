package com.diegonmarcos.cloudshopping.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import com.diegonmarcos.cloudshopping.R
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette

/** Dark is the fleet default (#336): libs:ui-kit's CloudKitTheme over the palette of res/values/colors.xml, the one place it is spelled. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val palette = KitPalette(
        surface = colorResource(R.color.app_surface),
        surfaceSelected = colorResource(R.color.app_surface_raised),
        textPrimary = colorResource(R.color.app_text),
        textSecondary = colorResource(R.color.app_text_secondary),
        accent = colorResource(R.color.app_accent),
        hairline = colorResource(R.color.app_outline),
        tileInk = colorResource(R.color.app_on_accent),
    )
    CloudKitTheme(palette, content)
}
