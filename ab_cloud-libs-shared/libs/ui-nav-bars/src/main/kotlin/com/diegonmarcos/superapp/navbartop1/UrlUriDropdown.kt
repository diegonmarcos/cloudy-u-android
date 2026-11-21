package com.diegonmarcos.superapp.navbartop1

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.NavTokens
import com.diegonmarcos.superapp.bottomnav.UrlUriDecl
import com.diegonmarcos.superapp.bottomnav.UrlUriEntry
import com.diegonmarcos.superapp.bottomnav.UrlUriIds
import com.diegonmarcos.superapp.bottomnav.UrlUriSystem
import com.diegonmarcos.superapp.bottomnav.urlUriModel
import com.diegonmarcos.superapp.themebg.ThemeColors
import com.diegonmarcos.superapp.uiicons.UiIcons

/** The Url-Uri dropdown's test tags (#1842). */
public object UrlUriTags {
    public const val BUTTON: String = "urluri_button"
    public const val DROPDOWN: String = "urluri_dropdown"
    public const val HEADER_PAGES: String = "urluri_header_pages"
    public const val HEADER_TOOLS: String = "urluri_header_tools"
    public const val SEPARATOR: String = "urluri_separator"
    public fun entry(id: String): String = "urluri_entry_$id"
}

/**
 * #1842 the far-left control of every app's first row: a link glyph with a caret; a tap opens the Url-Uri dropdown, Pages then Tools
 * ([urlUriModel]). Pages navigate through [onNavigate] (a bottom-nav page, or the page an app's slot names), AI / Profile / Configs call
 * their handlers; Tools run the platform actions ([UrlUriSystem]) or, for an app's own slot entry, [onAction].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun UrlUriDropdown(
    colors: ThemeColors,
    hierarchy: List<HierarchyEntry>,
    sections: List<NavSection>,
    decl: UrlUriDecl,
    defaultSection: String,
    onNavigate: (HierarchyEntry) -> Unit,
    onAi: () -> Unit,
    onProfile: () -> Unit,
    onConfigs: () -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val here = hierarchy.firstOrNull { it.depth == 0 }
    val currentDepth = hierarchy.filter { it.selected }.maxOfOrNull { it.depth } ?: 0
    val model = remember(sections, here?.sectionId, decl, currentDepth) { urlUriModel(sections, here?.sectionId, decl, canUp = currentDepth > 0 || sections.size > 1) }
    val anchor = rememberDropdownAnchor()
    fun go(id: String, label: String) {
        val section = id.substringBefore('/')
        onNavigate(HierarchyEntry(id.substringAfter('/'), label, if ('/' in id) 1 else 0, section, selected = false))
    }
    fun runEntry(e: UrlUriEntry) {
        open = false
        val id = e.id
        when {
            id.startsWith(UrlUriIds.SECTION_PREFIX) -> go(id.removePrefix(UrlUriIds.SECTION_PREFIX), e.label)
            id.startsWith(UrlUriIds.PAGE_SLOT_PREFIX) -> decl.pages.firstOrNull { UrlUriIds.pageSlot(it.id) == id }?.let { go(it.page, e.label) }
            id.startsWith(UrlUriIds.TOOL_SLOT_PREFIX) -> onAction(id.removePrefix(UrlUriIds.TOOL_SLOT_PREFIX))
            id == UrlUriIds.AI -> onAi()
            id == UrlUriIds.PROFILE -> onProfile()
            id == UrlUriIds.CONFIGS -> onConfigs()
            id == UrlUriIds.BACK -> UrlUriSystem.back(ctx)
            // Up: the parent of the page shown (the section when it is a sub-page), else the first bottom page.
            id == UrlUriIds.UP -> {
                val sel = hierarchy.lastOrNull { it.selected && it.depth > 0 }
                val parent = sel?.let { s -> hierarchy.lastOrNull { it.depth == s.depth - 1 && hierarchy.indexOf(it) < hierarchy.indexOf(s) } }
                if (parent != null) onNavigate(parent) else sections.firstOrNull()?.let { onNavigate(HierarchyEntry(it.id, it.label, 0, it.id, selected = false)) }
            }
            id == UrlUriIds.HOME -> (sections.firstOrNull { it.id == defaultSection } ?: sections.firstOrNull())?.let { onNavigate(HierarchyEntry(it.id, it.label, 0, it.id, selected = false)) }
            id == UrlUriIds.BACKGROUND -> UrlUriSystem.toBackground(ctx)
            id == UrlUriIds.FORCE_CLOSE -> UrlUriSystem.forceClose(ctx)
            id == UrlUriIds.RESTART -> UrlUriSystem.restart(ctx)
        }
    }
    Box(modifier.dropdownAnchor(anchor)) {
        Row(
            Modifier.size(width = TopFirstRowSpec.ICON_TARGET, height = TopFirstRowSpec.ICON_TARGET).testTag(UrlUriTags.BUTTON)
                .clickable(role = Role.Button) { open = true }.semantics { contentDescription = "Url-Uri" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(TopFirstRowSpec.ICON_TARGET), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Link, contentDescription = null, tint = colors.ink, modifier = Modifier.size(TopFirstRowSpec.ICON_RIGHT))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = colors.ink, modifier = Modifier.align(Alignment.BottomEnd).size(TopFirstRowSpec.ICON_RIGHT))
            }
        }
        if (open) Popup(
            popupPositionProvider = dropdownBottomReservePx().let { r -> remember(r) { BelowAnchorFit(r) } },
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true),
        ) {
            Box(Modifier.semantics { testTagsAsResourceId = true }) {
                Surface(
                    color = colors.island, contentColor = NavTokens().dropdownOnSurface, shape = RoundedCornerShape(12.dp), shadowElevation = 8.dp,
                    modifier = Modifier.width(TopFirstRowSpec.DROPDOWN_WIDTH).heightIn(max = dropdownMaxHeight(anchor)).testTag(UrlUriTags.DROPDOWN),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Header("Pages", UrlUriTags.HEADER_PAGES, colors)
                        model.pages.forEach { UrlUriRow(it) { e -> runEntry(e) } }
                        HorizontalDivider(Modifier.testTag(UrlUriTags.SEPARATOR), color = colors.islandBorder)
                        Header("Tools", UrlUriTags.HEADER_TOOLS, colors)
                        model.tools.forEach { UrlUriRow(it) { e -> runEntry(e) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String, tag: String, colors: ThemeColors) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag(tag), color = colors.accentOnIsland, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun UrlUriRow(e: UrlUriEntry, onClick: (UrlUriEntry) -> Unit) {
    DropdownMenuItem(
        text = { Text(e.label, fontWeight = if (e.selected) FontWeight.Bold else FontWeight.Normal) },
        leadingIcon = { Icon(UiIcons.painter(e.icon), contentDescription = null, modifier = Modifier.size(20.dp)) },
        enabled = e.enabled,
        onClick = { onClick(e) },
        modifier = Modifier.testTag(UrlUriTags.entry(e.id)),
    )
}
