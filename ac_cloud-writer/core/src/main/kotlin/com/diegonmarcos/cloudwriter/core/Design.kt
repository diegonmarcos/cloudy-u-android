package com.diegonmarcos.cloudwriter.core

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * #1532 Cloud Writer's design tokens as pure data, so the contrast of every foreground/background pair is a
 * JVM test (DesignTest) rather than a thing to look at on a phone. ui.Theme paints from these; nothing in the app
 * picks a colour of its own. Colours are opaque ARGB ints.
 */
data class TokenSet(
    val background: Int,
    val surface: Int,
    val surfaceContainerLow: Int,
    val surfaceContainer: Int,
    val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val outline: Int,
    val error: Int,
    val onError: Int,
) {
    /** Every text pair the app draws: ink on the surface level it sits on. Each must reach WCAG AA for text. */
    fun textPairs(): List<Pair<String, Pair<Int, Int>>> = listOf(
        "onSurface/background" to (onSurface to background),
        "onSurface/surface" to (onSurface to surface),
        "onSurface/low" to (onSurface to surfaceContainerLow),
        "onSurface/container" to (onSurface to surfaceContainer),
        "onSurface/high" to (onSurface to surfaceContainerHigh),
        "onSurface/highest" to (onSurface to surfaceContainerHighest),
        "onSurfaceVariant/background" to (onSurfaceVariant to background),
        "onSurfaceVariant/low" to (onSurfaceVariant to surfaceContainerLow),
        "onSurfaceVariant/container" to (onSurfaceVariant to surfaceContainer),
        "onSurfaceVariant/high" to (onSurfaceVariant to surfaceContainerHigh),
        "onSurfaceVariant/highest" to (onSurfaceVariant to surfaceContainerHighest),
        "onPrimary/primary" to (onPrimary to primary),
        "onPrimaryContainer/primaryContainer" to (onPrimaryContainer to primaryContainer),
        "onSecondaryContainer/secondaryContainer" to (onSecondaryContainer to secondaryContainer),
        "primary/background" to (primary to background),
        "primary/low" to (primary to surfaceContainerLow),
        "primary/container" to (primary to surfaceContainer),
        "primary/high" to (primary to surfaceContainerHigh),
        "onError/error" to (onError to error),
        "error/background" to (error to background),
    )

    /** Non-text pairs (borders, icons): WCAG 1.4.11 asks 3:1. */
    fun graphicPairs(): List<Pair<String, Pair<Int, Int>>> = listOf(
        "outline/background" to (outline to background),
        "primary/background" to (primary to background),
    )

    companion object {
        private fun c(v: Long): Int = v.toInt()

        val DARK = TokenSet(
            background = c(0xFF0B0E14), surface = c(0xFF0B0E14),
            surfaceContainerLow = c(0xFF11151D), surfaceContainer = c(0xFF161B24),
            surfaceContainerHigh = c(0xFF1C222D), surfaceContainerHighest = c(0xFF232A36),
            onSurface = c(0xFFE6EDF3), onSurfaceVariant = c(0xFFB4C0CD),
            primary = c(0xFF78C8FF), onPrimary = c(0xFF00344F),
            primaryContainer = c(0xFF0B4A75), onPrimaryContainer = c(0xFFCDE7FF),
            secondaryContainer = c(0xFF2B3E4C), onSecondaryContainer = c(0xFFD0E6F5),
            outline = c(0xFF8A95A1), error = c(0xFFFFB4AB), onError = c(0xFF690005),
        )

        val LIGHT = TokenSet(
            background = c(0xFFF8FAFC), surface = c(0xFFF8FAFC),
            surfaceContainerLow = c(0xFFF1F4F8), surfaceContainer = c(0xFFEBEFF4),
            surfaceContainerHigh = c(0xFFE4E9EF), surfaceContainerHighest = c(0xFFDDE3EA),
            onSurface = c(0xFF14181F), onSurfaceVariant = c(0xFF454F5B),
            primary = c(0xFF135A83), onPrimary = c(0xFFFFFFFF),
            primaryContainer = c(0xFFCDE7FF), onPrimaryContainer = c(0xFF001E2F),
            secondaryContainer = c(0xFFD0E6F5), onSecondaryContainer = c(0xFF091E28),
            outline = c(0xFF6E7985), error = c(0xFFBA1A1A), onError = c(0xFFFFFFFF),
        )
    }
}

/** WCAG 2.x relative luminance and contrast ratio, and the opaque blend the pressed state is made with. */
object Contrast {
    const val AA_TEXT = 4.5
    const val AA_GRAPHIC = 3.0

    private fun linear(channel: Int): Double {
        val s = channel / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Int): Double =
        0.2126 * linear((argb shr 16) and 0xFF) + 0.7152 * linear((argb shr 8) and 0xFF) + 0.0722 * linear(argb and 0xFF)

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** [base] moved [t] (0..1) of the way to [over], per channel, opaque. */
    fun blend(base: Int, over: Int, t: Float): Int {
        var out = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val b = (base shr shift) and 0xFF
            val o = (over shr shift) and 0xFF
            out = out or (((b + (o - b) * t).roundToInt().coerceIn(0, 255)) shl shift)
        }
        return out
    }
}

/** The three weights of a control: filled primary, tonal secondary, text tertiary. */
enum class ControlKind { PRIMARY, TONAL, TEXT }

enum class ControlState { ENABLED, PRESSED, DISABLED }

/** What a control paints in a state: [container] and [content] colours and the alpha the whole control is drawn at. */
data class ControlColors(val container: Int, val content: Int, val alpha: Float)

object Controls {
    /** The Material 3 state layer: a pressed control is its container moved this far towards its content ink. */
    const val PRESSED_LAYER = 0.12f
    const val DISABLED_ALPHA = 0.38f

    fun colors(t: TokenSet, kind: ControlKind, state: ControlState): ControlColors {
        val (container, content) = when (kind) {
            ControlKind.PRIMARY -> t.primary to t.onPrimary
            ControlKind.TONAL -> t.secondaryContainer to t.onSecondaryContainer
            ControlKind.TEXT -> t.surfaceContainerHigh to t.primary
        }
        return when (state) {
            ControlState.ENABLED -> ControlColors(container, content, 1f)
            ControlState.PRESSED -> ControlColors(Contrast.blend(container, content, PRESSED_LAYER), content, 1f)
            ControlState.DISABLED -> ControlColors(container, content, DISABLED_ALPHA)
        }
    }
}

/**
 * The layout grid in dp, as ints so a test can hold them: spacing on the 8 dp grid (4 dp half-step), 12 to 16 dp corner
 * radii, 40 to 48 dp controls, a 48 dp touch floor and a comfortable line length. ui.WriterDims turns these into Dp.
 */
object WriterGrid {
    const val HALF = 4
    const val UNIT = 8
    val SPACING: List<Int> = listOf(4, 8, 12, 16, 24, 32, 48)
    const val GUTTER = 16
    const val RADIUS_CONTROL = 12
    const val RADIUS_CARD = 16
    const val CONTROL_HEIGHT = 40
    const val CONTROL_HEIGHT_LARGE = 48
    const val TOUCH = 48
    /** The editor's measure: about 66 characters of 16 sp prose. */
    const val MEASURE = 640
    /** Line height as a multiple of the font size (an em factor, not an sp literal). */
    const val LINE_HEIGHT_EM = 1.6f
    /** The dimmed ink of the paragraphs outside focus mode's current one. */
    const val DIM_ALPHA = 0.38f
}
