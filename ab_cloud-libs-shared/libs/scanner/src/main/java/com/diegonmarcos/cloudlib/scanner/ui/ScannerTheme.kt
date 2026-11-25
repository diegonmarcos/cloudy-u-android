package com.diegonmarcos.cloudlib.scanner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.superapp.uikit.KitDenseMaterial
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1908 the Scanner's colours in the app kit's roles, light and dark (follows the system). The kit's own components
 * (KitButton, KitSegmented, KitCard, KitChip ...) read [LocalKitPalette], so every control here is the kit's, not a private copy.
 */
object ScannerPalettes {
    val dark = KitPalette(
        surface = Color(0xFF16181D), surfaceSelected = Color(0xFF262B33), textPrimary = Color(0xFFECEFF4), textSecondary = Color(0xFF9AA3B2),
        accent = Color(0xFF4FC3F7), hairline = Color(0xFF2F353E), tileInk = Color(0xFF06131A),
    )
    val light = KitPalette(
        surface = Color(0xFFF4F6F9), surfaceSelected = Color(0xFFDCE6F2), textPrimary = Color(0xFF14181F), textSecondary = Color(0xFF566070),
        accent = Color(0xFF0277BD), hairline = Color(0xFFCDD5E0), tileInk = Color(0xFFFFFFFF),
    )
    /** The page behind everything (the kit leaves the background to the host). */
    val darkBackground = Color(0xFF0D0F12)
    val lightBackground = Color(0xFFFFFFFF)
}

@Composable
fun ScannerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) ScannerPalettes.dark else ScannerPalettes.light
    val scheme = if (dark) darkColorScheme(
        primary = p.accent, onPrimary = p.tileInk, surface = p.surface, onSurface = p.textPrimary, onSurfaceVariant = p.textSecondary,
        outline = p.hairline, background = ScannerPalettes.darkBackground, onBackground = p.textPrimary,
        secondaryContainer = p.surfaceSelected, onSecondaryContainer = p.textPrimary,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = p.tileInk, surface = p.surface, onSurface = p.textPrimary, onSurfaceVariant = p.textSecondary,
        outline = p.hairline, background = ScannerPalettes.lightBackground, onBackground = p.textPrimary,
        secondaryContainer = p.surfaceSelected, onSecondaryContainer = p.textPrimary,
    )
    KitDenseMaterial(scheme) {
        CompositionLocalProvider(LocalKitPalette provides p, LocalContentColor provides p.textPrimary, content = content)
    }
}

/** The page background for the current theme. */
@Composable
fun scannerBackground(): Color = if (isSystemInDarkTheme()) ScannerPalettes.darkBackground else ScannerPalettes.lightBackground
