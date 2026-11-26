package com.diegonmarcos.cloudcalc.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudcalc.R

/**
 * Dark by default (#336), from res/values/colors.xml — the fleet palette (the cloud-c3 values),
 * one declaration the View theme and this Compose scheme both read. No Kotlin file names a
 * colour literal.
 */
@Composable
fun CalcTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    fun col(id: Int) = Color(ContextCompat.getColor(ctx, id))
    val scheme = darkColorScheme(
        primary = col(R.color.calc_accent),
        onPrimary = col(R.color.calc_on_accent),
        secondary = col(R.color.calc_accent_on_card),
        onSecondary = col(R.color.calc_on_accent),
        background = col(R.color.calc_background),
        onBackground = col(R.color.calc_text),
        surface = col(R.color.calc_surface),
        onSurface = col(R.color.calc_text),
        surfaceVariant = col(R.color.calc_surface_raised),
        onSurfaceVariant = col(R.color.calc_text_secondary),
        outline = col(R.color.calc_outline),
        error = col(R.color.calc_error),
    )
    // Dense, like the fleet's kit: MaterialTheme's default bodyLarge forces a fixed 24.sp line + 0.5.sp tracking on
    // every Text and Material controls get a 48dp floor; the stock scale keeps its sizes and weights only.
    MaterialTheme(colorScheme = scheme, typography = Typography().dense()) {
        CompositionLocalProvider(
            LocalContentColor provides scheme.onBackground,
            LocalMinimumInteractiveComponentSize provides CalcMetrics.compactHeight,
            content = content,
        )
    }
}

/** The app's metrics, declared once: no screen holds a dp literal. */
object CalcMetrics {
    val hairline: Dp = 1.dp
    val small: Dp = 4.dp
    val gap: Dp = 8.dp
    val gutter: Dp = 12.dp
    val keyHeight: Dp = 52.dp
    /** The calculator pad's round key (its diameter, or the column width when narrower). */
    val padKey: Dp = 56.dp
    val padElevation: Dp = 3.dp
    /** The mode row's icons: small, so the row stays about two lines of text tall. */
    val modeIcon: Dp = 16.dp
    /** Data-dense controls (converter pickers, swap, =, update): never the 40dp Material default. */
    val compactHeight: Dp = 30.dp
    val corner: Dp = 12.dp
    /** The calculator display takes whatever height the keypad leaves (never less than this), so nothing a result changes can move the keypad. */
    val displayMinHeight: Dp = 96.dp
    val plotHeight: Dp = 280.dp
    val spectrumHeight: Dp = 180.dp
    val stroke: Dp = 2.dp
    /** The round Configs button at the top-right of the top row. */
    val roundButton: Dp = 40.dp
    /** The Sheet grid: default cell, row-header column, and the drag strip that resizes a column or row. */
    val cellWidth: Dp = 88.dp
    val cellHeight: Dp = 32.dp
    val headerWidth: Dp = 44.dp
    val resizeHandle: Dp = 14.dp
    /** #1945 Finance > Exchange: the rounded cards' corner and the round flag's diameter. */
    val exchangeCorner: Dp = 28.dp
    val exchangeFlag: Dp = 36.dp
    val exchangeIcon: Dp = 24.dp
}

private fun TextStyle.noFixedLine(): TextStyle =
    copy(lineHeight = TextUnit.Unspecified, letterSpacing = TextUnit.Unspecified, lineHeightStyle = null)

private fun Typography.dense(): Typography = copy(
    displayLarge = displayLarge.noFixedLine(), displayMedium = displayMedium.noFixedLine(), displaySmall = displaySmall.noFixedLine(),
    headlineLarge = headlineLarge.noFixedLine(), headlineMedium = headlineMedium.noFixedLine(), headlineSmall = headlineSmall.noFixedLine(),
    titleLarge = titleLarge.noFixedLine(), titleMedium = titleMedium.noFixedLine(), titleSmall = titleSmall.noFixedLine(),
    bodyLarge = bodyLarge.noFixedLine(), bodyMedium = bodyMedium.noFixedLine(), bodySmall = bodySmall.noFixedLine(),
    labelLarge = labelLarge.noFixedLine(), labelMedium = labelMedium.noFixedLine(), labelSmall = labelSmall.noFixedLine(),
)
