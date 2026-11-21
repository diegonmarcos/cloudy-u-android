package com.diegonmarcos.superapp.browser

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.io.File

/**
 * #886 the strip of small tab icons under the address bar: the CURRENT tab's group (or just the tab,
 * when it is in none), a favicon each, the current one ringed, and a + that opens a new tab inside the
 * same group. Tapping an icon switches to that tab. Pure of decisions: [BrowserStripRules] says what is
 * shown; the host owns every action.
 */
object BrowserStripRules {
    /** The tabs the strip draws: [current]'s group in draw order, or [current] alone. */
    fun visible(all: List<BrowserTab>, current: BrowserTab?): List<BrowserTab> {
        current ?: return emptyList()
        if (current.group.isBlank()) return listOf(current)
        return BrowserTabOrder.sort(all).filter { it.group == current.group }
    }

    /** The one letter drawn when a tab has no favicon (yet): the site's first letter, else the title's. */
    fun letter(tab: BrowserTab): String {
        val s = BrowserTabGroups.siteLabel(tab.url).ifEmpty { tab.displayTitle }.trim()
        return s.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
    }
}

@Composable
fun BrowserTabStrip(
    tabs: List<BrowserTab>,
    activeKey: String,
    groupName: String,
    groupColor: Int,
    onSelect: (BrowserTab) -> Unit,
    onNew: () -> Unit,
    /** #1401 Page-Tools: the icon at the far right of the tab row, after the tabs (null = none). */
    onTools: (() -> Unit)? = null,
) {
    val p = LocalKitPalette.current
    Row(Modifier.fillMaxWidth().height(TAB_ROW_ICON_TARGET_DP.dp).background(p.surface), verticalAlignment = Alignment.CenterVertically) {
    Row(
        Modifier.weight(1f).horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp).testTag("browser:strip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (groupName.isNotBlank()) {
            Text(groupName, color = Color(groupColor), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                modifier = Modifier.padding(end = 8.dp).testTag("browser:strip:group"))
        }
        tabs.forEach { t ->
            val active = t.key == activeKey
            val bmp = remember(t.iconPath) {
                t.iconPath.takeIf { it.isNotBlank() && File(it).isFile }
                    ?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
            }
            Box(
                Modifier.padding(end = 6.dp).size(TAB_ROW_ICON_TARGET_DP.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (active) p.surfaceSelected else Color.Transparent)
                    .border(if (active) 2.dp else 1.dp, if (!active) p.hairline else if (groupName.isNotBlank()) Color(groupColor) else p.accent, RoundedCornerShape(8.dp))
                    .clickable { onSelect(t) }.testTag("browser:strip:tab"),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) Image(bmp.asImageBitmap(), t.title, Modifier.size(TAB_ROW_ICON_GLYPH_DP.dp), contentScale = ContentScale.Fit)
                else Text(BrowserStripRules.letter(t), color = p.textPrimary, style = MaterialTheme.typography.labelLarge)
            }
        }
        Box(
            Modifier.size(TAB_ROW_ICON_TARGET_DP.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, p.hairline, RoundedCornerShape(8.dp))
                .clickable(onClick = onNew).testTag("browser:strip:new"),
            contentAlignment = Alignment.Center,
        ) { Text("+", color = p.textPrimary, style = MaterialTheme.typography.titleLarge) }
    }
    if (onTools != null) BrowserTabRowToolsIcon(onTools)
    }
}

/**
 * #1401 the Page-Tools icon of the tab row (far right, after the tabs). It replaced the page-level ⋮: the app bar's
 * ⋮ opens the app's Configs, like every fleet app. Drawn alone at the row's end when the tab strip is switched off.
 * #1840 the size of the first row's icons ([TAB_ROW_ICON_TARGET_DP] target, [TAB_ROW_ICON_GLYPH_DP] glyph), and a grey,
 * theme-tinted wrench vector instead of the former colourful emoji glyph (an emoji ignores the theme; a vector takes the palette's secondary ink).
 */
@Composable
fun BrowserTabRowToolsIcon(onTap: () -> Unit) {
    val p = LocalKitPalette.current
    Box(
        Modifier.padding(horizontal = 2.dp).size(TAB_ROW_ICON_TARGET_DP.dp).clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = PAGE_TOOLS_LABEL, onClick = onTap)
            .semantics { contentDescription = PAGE_TOOLS_LABEL }.testTag(TAB_ROW_TOOLS_TAG),
        contentAlignment = Alignment.Center,
    ) { Icon(PageToolsIcon, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(TAB_ROW_ICON_GLYPH_DP.dp)) }
}

/** #1840 the entry's one name: the tab row's icon, its content description, the sheet's title. */
const val PAGE_TOOLS_LABEL = "Page-Tools"

/** #1840 the tab row's icons are the first row's icons: the touch target and the glyph of libs:ui-nav-bars TopFirstRowSpec (ICON_TARGET 44 dp, ICON 24 dp; a test holds them equal). */
const val TAB_ROW_ICON_TARGET_DP = 44
const val TAB_ROW_ICON_GLYPH_DP = 24

/** #1840 a monochrome wrench (the Material "build" glyph, 24 x 24), a vector so the theme tints it. */
val PageToolsIcon: ImageVector by lazy {
    ImageVector.Builder("PageTools", 24.dp, 24.dp, 24f, 24f).addPath(
        PathParser().parsePathString(
            "M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0l2.3-2.3c.5-.4.5-1.1.1-1.4z",
        ).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()
}

/** #1401 the test tag of the tab row's Page-Tools icon (UI tests and the emulator walk find it by this). */
const val TAB_ROW_TOOLS_TAG = "browser:tabrow:tools"
