package com.diegonmarcos.superapp.bottomnav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * THE first row of a fleet app (the owner asked for it in Cloud Tasks, Cloud Calc and Cloud Store, so it is built
 * ONCE, here): a hamburger at the left, the app's title centred, and two circular buttons at the right, AI and
 * Settings. What it shows is DECLARED (`build.json::ui.top_bar`, [TopBarDecl]); an app draws no copy of it
 * (cloud-android-top-bar-guard).
 *
 *   hamburger  opens a side drawer. The drawer's CONTENT is the current page's: a page calls [TopBarDrawer] while it
 *              is shown and the drawer holds what it said (a page that says nothing leaves an empty drawer).
 *   AI         opens an anchored panel under the button, filled by the host's [aiPanel] slot. Hosts fill it with libs:ai's
 *              chat (AiChatPage over the app's AiChatState: the same persistent sessions Cloud Code's AI page has).
 *   Settings   opens a dropdown of the declared entries: Account launches Cloud Account ([onAccount], use
 *              [rememberAccountLauncher]); Configs calls [onConfigs], the host's own Configs page.
 *
 * It sits in the host's window ABOVE its page-tab strip ([PageTabs]) and the bottom island ([BottomNavHost]); the
 * host places it below the status bar. Colours are read from the host's MaterialTheme roles (CloudKitTheme maps the
 * launcher palette onto them), so a theme switch recolours it with the page; nothing here names a colour literal
 * but the scrim. Every button is a 48dp target announced as a button with its label.
 */
@Composable
public fun AppTopBarScaffold(
    decl: TopBarDecl,
    /** The app's name (build.json::name, the launcher label), centred in the bar. */
    title: String,
    onAccount: () -> Unit,
    onConfigs: () -> Unit,
    modifier: Modifier = Modifier,
    controller: AppTopBarController = rememberAppTopBarController(),
    labels: AppTopBarLabels = appTopBarLabels(),
    colors: AppTopBarColors = appTopBarColors(),
    onAi: (() -> Unit)? = null,
    aiPanel: @Composable (close: () -> Unit) -> Unit = {},
    /** False slides the row away (a host hides it while its page scrolls down, with [BottomNavCollapse]); true shows it. */
    barVisible: Boolean = true,
    /**
     * #1154 the row seam: when set, THIS draws the first row instead of [AppTopBar] (the hamburger/AI/panel state stays the
     * [controller]'s, the drawer overlay and the AI popup stay this scaffold's). libs:ui-nav-bars fills it; an app
     * calls that lib's scaffold, never this parameter.
     */
    topRow: (@Composable (AppTopBarController) -> Unit)? = null,
    /**
     * Clean mode (NavUiMode): the left drawer's own content, drawn above whatever the page said through [TopBarDrawer]. libs:ui-nav-bars
     * fills it with the page dropdown, the AI / Profile / Settings icons and the child-page dropdown; an app never passes it.
     */
    drawerOverride: TopBarDrawerContent? = null,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAppTopBar provides controller) {
        Box(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(barVisible, Modifier.testTag(TopBarTags.BAR_SLOT)) {
                    if (topRow != null) {
                        Box {
                            topRow(controller)
                            if (controller.aiOpen) AiPopup(controller, labels, colors, aiPanel)
                        }
                    } else AppTopBar(decl, title, controller, onAccount, onConfigs, labels = labels, colors = colors, onAi = onAi, aiPanel = aiPanel)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
            if (decl.drawer && controller.drawerOpen) DrawerOverlay(controller, labels, colors, drawerOverride)
        }
    }
}

/** The row alone, for a host that lays its page out itself; the drawer overlay is [AppTopBarScaffold]'s. */
@Composable
public fun AppTopBar(
    decl: TopBarDecl,
    title: String,
    controller: AppTopBarController,
    onAccount: () -> Unit,
    onConfigs: () -> Unit,
    modifier: Modifier = Modifier,
    labels: AppTopBarLabels = appTopBarLabels(),
    colors: AppTopBarColors = appTopBarColors(),
    onAi: (() -> Unit)? = null,
    aiPanel: @Composable (close: () -> Unit) -> Unit = {},
) {
    Box(modifier.fillMaxWidth().height(BAR_HEIGHT).background(colors.container).testTag(TopBarTags.BAR)) {
        if (decl.drawer) {
            CircleButton(Icons.Filled.Menu, labels.menu, TopBarTags.MENU, colors, Modifier.align(Alignment.CenterStart).padding(start = EDGE)) {
                controller.openDrawer()
            }
        }
        val inline = decl.inlineActions
        Text(
            title,
            (if (inline) Modifier.align(Alignment.CenterStart).padding(start = INLINE_TITLE_START, end = INLINE_TITLE_END)
            else Modifier.align(Alignment.Center).padding(horizontal = TITLE_CLEARANCE)).testTag(TopBarTags.TITLE).semantics { heading() },
            color = colors.onContainer,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (inline) TextAlign.Start else TextAlign.Center,
        )
        if (inline) {
            // #1149 AI, Profile and Configs as three round buttons; no Settings dropdown.
            Row(Modifier.align(Alignment.CenterEnd).padding(end = EDGE), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    CircleButton(Icons.Filled.AutoAwesome, labels.ai, TopBarTags.AI, colors) { if (onAi != null) onAi() else controller.toggleAi() }
                    if (onAi == null && controller.aiOpen) AiPopup(controller, labels, colors, aiPanel)
                }
                if (TopBarSettings.ACCOUNT in decl.settings) CircleButton(Icons.Filled.AccountCircle, labels.account, TopBarTags.ACCOUNT, colors) { onAccount() }
                if (TopBarSettings.CONFIGS in decl.settings) CircleButton(Icons.Filled.MoreVert, labels.configs, TopBarTags.CONFIGS, colors) { onConfigs() }
            }
        } else Row(Modifier.align(Alignment.CenterEnd).padding(end = EDGE), verticalAlignment = Alignment.CenterVertically) {
            if (decl.ai) {
                Box {
                    CircleButton(Icons.Filled.AutoAwesome, labels.ai, TopBarTags.AI, colors) { controller.toggleAi() }
                    if (controller.aiOpen) AiPopup(controller, labels, colors, aiPanel)
                }
            }
            val entries = decl.settings.filter { it in TopBarSettings.KNOWN }
            if (entries.isNotEmpty()) {
                Box {
                    CircleButton(Icons.Filled.Settings, labels.settings, TopBarTags.SETTINGS, colors) { controller.toggleSettings() }
                    DropdownMenu(expanded = controller.settingsOpen, onDismissRequest = { controller.closeSettings() }) {
                        entries.forEach { id ->
                            DropdownMenuItem(
                                text = { Text(labels.entry(id)) },
                                onClick = {
                                    controller.closeSettings()
                                    if (id == TopBarSettings.ACCOUNT) onAccount() else onConfigs()
                                },
                                modifier = Modifier.testTag(TopBarTags.settingsItem(id)),
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(colors.hairline))
    }
}

/**
 * The current page's say in the drawer. Call it from the page, while the page is shown: the drawer shows [content]
 * and it is gone when the page leaves the composition (the next page's call takes its place). [content] gets a
 * `close` to dismiss the drawer after a tap. Outside an [AppTopBarScaffold] it does nothing.
 */
@Composable
public fun TopBarDrawer(content: TopBarDrawerContent) {
    val controller = LocalAppTopBar.current ?: return
    val latest by rememberUpdatedState(content)
    DisposableEffect(controller) {
        val slot: TopBarDrawerContent = { close -> latest(this, close) }
        controller.drawerContent = slot
        onDispose { if (controller.drawerContent === slot) controller.drawerContent = null }
    }
}

/**
 * #1850 a page's own NON-navigation actions (reload, new tab, sheets, saved items, projects...), listed in the merged path dropdown under an
 * "Actions" header. The drawer slot ([TopBarDrawer]) is no longer listed there: it is the generic page list, which Parent Pages and Siblings
 * already carry. Call it from the page, next to [TopBarDrawer] when the hamburger drawer should show the same rows.
 */
@Composable
public fun TopBarActions(content: TopBarDrawerContent) {
    val controller = LocalAppTopBar.current ?: return
    val latest by rememberUpdatedState(content)
    DisposableEffect(controller) {
        val slot: TopBarDrawerContent = { close -> latest(this, close) }
        controller.actionsContent = slot
        onDispose { if (controller.actionsContent === slot) controller.actionsContent = null }
    }
}

/** [TopBarDrawer] and [TopBarActions] with the same rows: for a page whose drawer holds real actions, not only page navigation (#1850). */
@Composable
public fun TopBarDrawerWithActions(content: TopBarDrawerContent) {
    TopBarDrawer(content)
    TopBarActions(content)
}

/** What a page puts in the drawer: a column, and a way to dismiss the drawer. */
public typealias TopBarDrawerContent = @Composable ColumnScope.(close: () -> Unit) -> Unit

/** The bar's open/closed state and the drawer slot; hoisted so a host can open the drawer or the AI panel itself. */
public class AppTopBarController {
    public var drawerOpen: Boolean by mutableStateOf(false)
        private set
    public var aiOpen: Boolean by mutableStateOf(false)
        private set
    public var settingsOpen: Boolean by mutableStateOf(false)
        private set

    /** What the page said through [TopBarActions]: its non-navigation actions, listed in the title dropdown (#1850). */
    public var actionsContent: TopBarDrawerContent? by mutableStateOf<TopBarDrawerContent?>(null)
        internal set

    /** What the page said through [TopBarDrawer]; public so libs:ui-nav-bars can list it in the title dropdown (no hamburger there). */
    public var drawerContent: TopBarDrawerContent? by mutableStateOf<TopBarDrawerContent?>(null)
        internal set

    public fun openDrawer() { drawerOpen = true; aiOpen = false; settingsOpen = false }
    public fun closeDrawer() { drawerOpen = false }
    public fun toggleAi() { aiOpen = !aiOpen; settingsOpen = false }
    public fun closeAi() { aiOpen = false }
    public fun toggleSettings() { settingsOpen = !settingsOpen; aiOpen = false }
    public fun closeSettings() { settingsOpen = false }
}

@Composable
public fun rememberAppTopBarController(): AppTopBarController = remember { AppTopBarController() }

/** The controller of the enclosing [AppTopBarScaffold]; null outside one. */
public val LocalAppTopBar = staticCompositionLocalOf<AppTopBarController?> { null }

/** The words the bar speaks: the accessibility labels of its buttons and the Settings entries. */
@Immutable
public data class AppTopBarLabels(
    val menu: String,
    val closeMenu: String,
    val drawer: String,
    val ai: String,
    val aiPanel: String,
    val settings: String,
    val account: String,
    val configs: String,
) {
    /** The dropdown label of a declared Settings id. */
    public fun entry(id: String): String = if (id == TopBarSettings.ACCOUNT) account else configs
}

@Composable
public fun appTopBarLabels(): AppTopBarLabels = AppTopBarLabels(
    menu = stringResource(R.string.app_top_bar_menu),
    closeMenu = stringResource(R.string.app_top_bar_menu_close),
    drawer = stringResource(R.string.app_top_bar_drawer),
    ai = stringResource(R.string.app_top_bar_ai),
    aiPanel = stringResource(R.string.app_top_bar_ai_panel),
    settings = stringResource(R.string.app_top_bar_settings),
    account = stringResource(R.string.app_top_bar_account),
    configs = stringResource(R.string.app_top_bar_configs),
)

/** The colour ROLES the bar paints with, resolved from the host's theme ([appTopBarColors]). */
@Immutable
public data class AppTopBarColors(
    val container: Color,
    val onContainer: Color,
    val button: Color,
    val onButton: Color,
    val hairline: Color,
    val scrim: Color,
    val drawer: Color,
    val onDrawer: Color,
    val panel: Color,
    val onPanel: Color,
)

/** The bar's colours from the MaterialTheme roles CloudKitTheme maps the palette onto (surface, onSurface, outline). */
@Composable
public fun appTopBarColors(): AppTopBarColors {
    val c = MaterialTheme.colorScheme
    return AppTopBarColors(
        container = c.surface,
        onContainer = c.onSurface,
        button = c.onSurface.copy(alpha = 0.14f),
        onButton = c.onSurface,
        hairline = c.outline.copy(alpha = 0.5f),
        scrim = Color.Black.copy(alpha = 0.5f),
        drawer = c.surface,
        onDrawer = c.onSurface,
        panel = c.surface,
        onPanel = c.onSurface,
    )
}

/** Test tags of the bar's parts, for a host's compose tests. */
public object TopBarTags {
    public const val BAR: String = "topbar"
    /** The slot [AppTopBarScaffold] folds away when `barVisible` is false. */
    public const val BAR_SLOT: String = "topbar_slot"
    public const val MENU: String = "topbar_menu"
    public const val TITLE: String = "topbar_title"
    public const val AI: String = "topbar_ai"
    public const val SETTINGS: String = "topbar_settings"
    /** #1149 the inline Profile and Configs buttons ([TopBarDecl.inlineActions]). */
    public const val ACCOUNT: String = "topbar_account"
    public const val CONFIGS: String = "topbar_configs"
    public const val DRAWER: String = "topbar_drawer"
    public const val SCRIM: String = "topbar_scrim"
    public const val AI_PANEL: String = "topbar_ai_panel"
    public fun settingsItem(id: String): String = "topbar_settings_$id"
}

private val BAR_HEIGHT: Dp = 56.dp
private val EDGE: Dp = 4.dp
/** Room kept clear on BOTH sides of the title: the hamburger on the left, the two buttons on the right. */
private val TITLE_CLEARANCE: Dp = 108.dp
/** #1149 inline actions: the title starts after the hamburger and stops before the three buttons. */
private val INLINE_TITLE_START: Dp = 56.dp
private val INLINE_TITLE_END: Dp = 152.dp

/** A 48dp target holding a 36dp circle: the touch floor without a heavier look. */
@Composable
private fun CircleButton(
    icon: ImageVector,
    label: String,
    tag: String,
    colors: AppTopBarColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(48.dp).clip(CircleShape).testTag(tag)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(colors.button), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.onButton, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun DrawerOverlay(controller: AppTopBarController, labels: AppTopBarLabels, colors: AppTopBarColors, lead: TopBarDrawerContent? = null) {
    BackHandler { controller.closeDrawer() }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(colors.scrim).testTag(TopBarTags.SCRIM)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { controller.closeDrawer() }
                .semantics { contentDescription = labels.closeMenu },
        )
        Surface(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(0.82f).widthIn(max = 320.dp)
                .testTag(TopBarTags.DRAWER).semantics { paneTitle = labels.drawer },
            color = colors.drawer,
            contentColor = colors.onDrawer,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                if (lead != null) lead(this) { controller.closeDrawer() }
                val slot = controller.drawerContent
                if (slot != null) slot(this) { controller.closeDrawer() }
            }
        }
    }
}

@Composable
private fun AiPopup(
    controller: AppTopBarController,
    labels: AppTopBarLabels,
    colors: AppTopBarColors,
    aiPanel: @Composable (close: () -> Unit) -> Unit,
) {
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val gutter = with(LocalDensity.current) { 8.dp.roundToPx() }
    Popup(
        popupPositionProvider = remember(gap, gutter) { BelowAnchor(gap, gutter) },
        onDismissRequest = { controller.closeAi() },
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            Modifier.fillMaxWidth(0.94f).widthIn(max = 440.dp).fillMaxHeight(0.6f)
                .testTag(TopBarTags.AI_PANEL).semantics { paneTitle = labels.aiPanel },
            shape = RoundedCornerShape(16.dp),
            color = colors.panel,
            contentColor = colors.onPanel,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, colors.hairline),
        ) { aiPanel { controller.closeAi() } }
    }
}

/** Under the anchor (the AI button), right-aligned to the window with a [gutter], kept on screen. */
private class BelowAnchor(private val gap: Int, private val gutter: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (windowSize.width - popupContentSize.width - gutter).coerceAtLeast(0)
        val y = anchorBounds.bottom + gap
        return IntOffset(x, y)
    }
}
