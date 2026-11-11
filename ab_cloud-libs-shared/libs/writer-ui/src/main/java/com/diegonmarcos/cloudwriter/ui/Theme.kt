package com.diegonmarcos.cloudwriter.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import com.diegonmarcos.superapp.uikit.KitDenseMaterial
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.diegonmarcos.cloudwriter.WriterPrefs
import com.diegonmarcos.cloudwriter.core.Contrast
import com.diegonmarcos.cloudwriter.core.TokenSet

/**
 * cloud-writer's Material 3 theme — the design system this application did not have.
 *
 * WHY THIS FILE EXISTS. Every screen here used to be assembled at run time out of LinearLayout and
 * addView, against four hardcoded hex constants copy-pasted into two files, with no theme, no type
 * scale and no layout resource anywhere in the module. That is not a style choice, it is an
 * inheritance: these screens were copied from cloud-keyboard under task 272, and cloud-keyboard is
 * an IME. An IME draws inside a window it does not own and cannot apply an application theme, so it
 * has no layout files and no MaterialTheme by necessity. cloud-writer is a standalone application
 * and never had that constraint — it was only wearing the constraint's clothes.
 *
 * MATCHES ac_cloud-mail, DELIBERATELY, RATHER THAN INVENTING A THIRD FLEET PATTERN. cloud-mail is
 * the fleet's Compose + Material 3 reference and the most heavily developed application here; the
 * structure below — dynamic colour above S, a hand-built scheme below it, the OLED pull kept in a
 * separate pure function, and the system bars told which way the theme went — is its structure, in
 * cloud-writer's own colours. Conventions reused; no file cloned.
 *
 * THE OLED PULL IS NOT A DETAIL HERE. The owner runs a Samsung black Power Saving theme (tasks 186
 * and 229), so "dark" on his phone means true black, and a dark grey surface next to it reads as a
 * lit rectangle rather than as depth. [pulledToBlack] takes background and surface to #000 and pulls
 * the container tiers most of the way there, which keeps the Material elevation ORDER intact — a
 * card still separates from the page — while removing the grey halo. Kept out of the composable so
 * it is an ordinary function that can be reasoned about and called from a test, exactly as
 * cloud-mail keeps `applyPureBlack` out of `SternaTheme`.
 */

/**
 * How far the raised surfaces move towards black when the OLED pull is on.
 *
 * NOT 1.0, and that is the whole point. At 1.0 every container tier collapses onto the background
 * and the interface becomes one flat black sheet in which a card, a text field and the page behind
 * them are indistinguishable — which is a worse screen than the grey one, not a blacker one. At
 * 0.8 the page is true black and the tiers keep about a fifth of their separation, which is enough
 * for an edge to read on an OLED panel and not enough to glow.
 */
private const val PureBlackPull = 0.8f

/**
 * #1532 the schemes are painted from core's [TokenSet] (DARK and LIGHT), the one place the palette is declared and the one
 * the AA contrast test (core DesignTest) holds. [WriterBlue] stays the accent the hand-drawn screens started with (#78c8ff);
 * the container tiers step up (dark) or down (light) from the page so a card, a toolbar and a field each read as their own
 * tonal level.
 */
private val WriterBlue = Color(TokenSet.DARK.primary)

private fun TokenSet.toScheme(dark: Boolean): ColorScheme {
    val outlineVariant = Color(Contrast.blend(outline, background, 0.5f))
    return if (dark) {
        darkColorScheme(
            primary = Color(primary), onPrimary = Color(onPrimary),
            primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
            secondary = Color(onSecondaryContainer), onSecondary = Color(secondaryContainer),
            secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onSecondaryContainer),
            background = Color(background), onBackground = Color(onSurface),
            surface = Color(surface), onSurface = Color(onSurface), onSurfaceVariant = Color(onSurfaceVariant),
            surfaceVariant = Color(surfaceContainerHighest),
            surfaceContainerLowest = Color(background), surfaceContainerLow = Color(surfaceContainerLow),
            surfaceContainer = Color(surfaceContainer), surfaceContainerHigh = Color(surfaceContainerHigh),
            surfaceContainerHighest = Color(surfaceContainerHighest),
            outline = Color(outline), outlineVariant = outlineVariant,
            error = Color(error), onError = Color(onError),
        )
    } else {
        lightColorScheme(
            primary = Color(primary), onPrimary = Color(onPrimary),
            primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(onPrimaryContainer),
            secondary = Color(onSecondaryContainer), onSecondary = Color(secondaryContainer),
            secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(onSecondaryContainer),
            background = Color(background), onBackground = Color(onSurface),
            surface = Color(surface), onSurface = Color(onSurface), onSurfaceVariant = Color(onSurfaceVariant),
            surfaceVariant = Color(surfaceContainerHighest),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(surfaceContainerLow),
            surfaceContainer = Color(surfaceContainer), surfaceContainerHigh = Color(surfaceContainerHigh),
            surfaceContainerHighest = Color(surfaceContainerHighest),
            outline = Color(outline), outlineVariant = outlineVariant,
            error = Color(error), onError = Color(onError),
        )
    }
}

private val WriterDarkScheme = TokenSet.DARK.toScheme(dark = true)

private val WriterLightScheme = TokenSet.LIGHT.toScheme(dark = false)

/**
 * The OLED decision, kept out of the composable so it can be executed and reasoned about on its own.
 *
 * Pulls ONLY when the theme is actually dark: applying it in light mode would produce a black page
 * under black-on-white text, which is the kind of defect a palette swap ships when nobody looks at
 * the other mode.
 */
internal fun applyPureBlack(scheme: ColorScheme, darkTheme: Boolean, pureBlack: Boolean): ColorScheme =
    if (darkTheme && pureBlack) scheme.pulledToBlack() else scheme

internal fun ColorScheme.pulledToBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = lerp(surfaceContainerLowest, Color.Black, PureBlackPull),
    surfaceContainerLow = lerp(surfaceContainerLow, Color.Black, PureBlackPull),
    surfaceContainer = lerp(surfaceContainer, Color.Black, PureBlackPull),
    surfaceContainerHigh = lerp(surfaceContainerHigh, Color.Black, PureBlackPull),
    surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, PureBlackPull),
    surfaceBright = lerp(surfaceBright, Color.Black, PureBlackPull),
)

/**
 * @param dynamicColor Material You, only consulted on Android 12+. OFF by default since #1532: the designed palette (the
 *   accent for primary actions, the tonal surfaces, the AA-tested pairs) is what the owner asked to see, and a wallpaper
 *   palette would replace all of it. Pass true to follow the wallpaper.
 * @param pureBlack the OLED pull described above. ON by default because the owner's phone is a
 *   Samsung on a black Power Saving theme; it changes nothing in light mode.
 */
/**
 * #800 the owner's theme choice: follow the system, or always light, or always dark. One state,
 * read by [CloudWriterTheme]'s default, so the editor, the list and every settings page switch
 * together; stored in WriterPrefs (KEY_THEME) so it survives a restart.
 */
object WriterTheme {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private val mode = mutableStateOf<String?>(null)

    fun current(context: Context): String = mode.value ?: WriterPrefs.string(context, WriterPrefs.KEY_THEME, SYSTEM)

    fun set(context: Context, value: String) {
        WriterPrefs.put(context, WriterPrefs.KEY_THEME, value)
        mode.value = value
    }

    @Composable
    fun isDark(): Boolean = when (current(LocalContext.current)) {
        LIGHT -> false
        DARK -> true
        else -> isSystemInDarkTheme()
    }
}

@Composable
fun CloudWriterTheme(
    darkTheme: Boolean = WriterTheme.isDark(),
    dynamicColor: Boolean = false,
    pureBlack: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> WriterDarkScheme
        else -> WriterLightScheme
    }.let { applyPureBlack(it, darkTheme, pureBlack) }

    // The status and navigation bar ICONS. Compose drives light/dark here, not the XML theme, so
    // the platform has to be told explicitly which way it went — otherwise the light theme draws
    // white status icons onto a white surface and the clock disappears.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    // The stock Material 3 sizes and weights (the designed set of relationships), through the kit's dense
    // wrapper: no fixed 24.sp line / 0.5.sp tracking on every Text and a dense touch floor, so a 12sp row is
    // not 24sp tall.
    KitDenseMaterial(colorScheme, content)
}
