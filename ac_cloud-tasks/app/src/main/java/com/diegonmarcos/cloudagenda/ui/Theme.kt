package com.diegonmarcos.cloudagenda.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.cloudagenda.data.ThemeMode
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitDenseMaterial
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitPalette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified

/** Whether the app is drawing dark right now (the chrome reads it for the nav strips and the system-bar glyphs). */
val LocalDarkTheme = staticCompositionLocalOf { true }

/** The kit's seven colour ROLES, once for each mode. Every page paints with these and names no colour of its own. */
object TasksPalettes {
    val light = KitPalette(
        surface = Color(0xFFFFFFFF),
        surfaceSelected = Color(0xFFEDE7F6),
        textPrimary = Color(0xFF1B1B1F),
        textSecondary = Color(0xFF5F6368),
        accent = Color(0xFF6750A4),
        hairline = Color(0xFFCAC4D0),
        tileInk = Color(0xFFFFFFFF),
        ok = Color(0xFF2E7D32),
        warn = Color(0xFFB26A00),
        bad = Color(0xFFB3261E),
    )
    val dark = KitPalette(
        surface = Color(0xFF121214),
        surfaceSelected = Color(0xFF2A2A31),
        textPrimary = Color(0xFFECECF1),
        textSecondary = Color(0xFFA0A0AB),
        accent = Color(0xFFB69DF8),
        hairline = Color(0xFF3A3A42),
        tileInk = Color(0xFF1B1B1F),
        ok = KitPalette.DEFAULT_OK,
        warn = KitPalette.DEFAULT_WARN,
        bad = KitPalette.DEFAULT_BAD,
    )

    fun of(dark: Boolean): KitPalette = if (dark) this.dark else this.light
}

/**
 * The Material roles the kit's own scheme leaves at their defaults (CloudKitTheme always builds a DARK scheme),
 * derived from the same palette so Material surfaces -- menus, dialogs, text fields, the AI page libs:ai draws
 * from MaterialTheme -- follow Settings > Appearance too.
 */
fun materialSchemeFor(p: KitPalette, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent, onPrimary = p.tileInk,
        primaryContainer = p.surfaceSelected, onPrimaryContainer = p.textPrimary,
        secondary = p.accent, onSecondary = p.tileInk,
        secondaryContainer = p.surfaceSelected, onSecondaryContainer = p.textPrimary,
        background = p.surface, onBackground = p.textPrimary,
        surface = p.surface, onSurface = p.textPrimary,
        surfaceVariant = p.surfaceSelected, onSurfaceVariant = p.textSecondary,
        surfaceContainer = p.surfaceSelected, surfaceContainerHigh = p.surfaceSelected,
        surfaceContainerHighest = p.surfaceSelected, surfaceContainerLow = p.surface, surfaceContainerLowest = p.surface,
        surfaceTint = p.accent, outline = p.hairline, outlineVariant = p.hairline,
        error = p.bad,
    )
}

/**
 * #1461 the fleet's dense type ramp: every Material text role times the Store's TEXT_SCALE ([KitDensity.TEXT_SCALE], 0.78), so a Text that
 * takes its size from MaterialTheme (dialogs, fields, the AI panel, the editor) is as small as the Store's rows. A screen's own sizes
 * come from KitStoreRow / KitDensity, never from an sp literal (DensityGuardTest).
 */
fun Typography.dense(f: Float = KitDensity.TEXT_SCALE): Typography {
    fun TextStyle.s() = if (fontSize.isSpecified) copy(fontSize = fontSize * f) else this
    return copy(
        displayLarge = displayLarge.s(), displayMedium = displayMedium.s(), displaySmall = displaySmall.s(),
        headlineLarge = headlineLarge.s(), headlineMedium = headlineMedium.s(), headlineSmall = headlineSmall.s(),
        titleLarge = titleLarge.s(), titleMedium = titleMedium.s(), titleSmall = titleSmall.s(),
        bodyLarge = bodyLarge.s(), bodyMedium = bodyMedium.s(), bodySmall = bodySmall.s(),
        labelLarge = labelLarge.s(), labelMedium = labelMedium.s(), labelSmall = labelSmall.s(),
    )
}

/**
 * THE theme of the app: Settings > Appearance (Light / Dark / System) picks the palette, CloudKitTheme applies it
 * app-wide, and the Material scheme under it is derived from the same palette. [systemDark] is injectable so a test
 * can render either mode without a device setting.
 */
@Composable
fun TasksTheme(mode: ThemeMode, systemDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val dark = mode.isDark(systemDark)
    val palette = TasksPalettes.of(dark)
    CloudKitTheme(palette) {
        KitDenseMaterial(materialSchemeFor(palette, dark)) {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = MaterialTheme.typography.dense()) {
                // KitDenseMaterial set the default text style to bodySmall before this scaling: re-read it from the scaled ramp.
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodySmall,
                ) {
                    CompositionLocalProvider(LocalDarkTheme provides dark) {
                        // The kit leaves the page background transparent; the app paints it so a dark window never shows a light one.
                        Box(Modifier.fillMaxSize().background(palette.surface)) { content() }
                    }
                }
            }
        }
    }
}
