package com.diegonmarcos.superapp.fleetconfigs

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.navbartop2.NavUiConfigCard
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.themebg.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The two shared Configs pages. */
public enum class FleetConfigsPage(public val id: String, public val label: String) {
    General("general", "General"),
    About("about", "About"),
}

/** Test tags (also UiAutomator resource ids: the runtime nav walk opens and photographs both pages by them). */
public object FleetConfigsTags {
    public const val GENERAL: String = "fleetcfg_general"
    public const val ABOUT: String = "fleetcfg_about"
    public const val BACK: String = "fleetcfg_back"
    /** The page title: a tap on General's title scrolls back up to the anchor grid. */
    public const val TITLE: String = "fleetcfg_title"
    public const val ANCHORS: String = "fleetcfg_anchors"
    public const val OPEN_STORE: String = "fleetcfg_open_store"
    public const val OPEN_SUPERAPP: String = "fleetcfg_open_superapp"
    public const val OPEN_APP_CONFIGS: String = "fleetcfg_open_app_configs"
    public fun anchor(id: String): String = "fleetcfg_anchor_$id"
    public fun section(id: String): String = "fleetcfg_section_$id"
    public fun about(field: String): String = "fleetcfg_about_$field"
    public fun page(p: FleetConfigsPage): String = if (p == FleetConfigsPage.General) GENERAL else ABOUT
}

/** The label of the first row's and the Clean drawer's entry for [p]. */
public fun fleetConfigsEntryLabel(p: FleetConfigsPage): String = "Configs ▸ " + p.label

internal fun sectionIcon(id: String): ImageVector = when (id) {
    "perms" -> Icons.Filled.Security
    "battery" -> Icons.Filled.BatteryChargingFull
    "storage" -> Icons.Filled.Storage
    "notifications" -> Icons.Filled.Notifications
    "ui" -> Icons.Filled.Dashboard
    "language" -> Icons.Filled.Language
    else -> Icons.Filled.Tune
}

/**
 * Configs > General or Configs > About, full screen over the app (a dialog window, so every app on the shared scaffold gets them
 * with no route of its own). [onAppConfigs] opens the app's own Configs (General's "App" section).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun FleetConfigsScreen(
    page: FleetConfigsPage,
    dark: Boolean,
    onDismiss: () -> Unit,
    onAppConfigs: () -> Unit,
    decl: FleetConfigsDecl = FleetConfigs.current,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val c = ThemeBackground.colors(dark)
        val generalScroll = rememberScrollState()
        val titleScope = rememberCoroutineScope()
        Column(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.top, c.bottom)))
                .semantics { testTagsAsResourceId = true }.testTag(FleetConfigsTags.page(page))
                .statusBarsPadding().navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss)
                        .semantics { contentDescription = "Back" }.testTag(FleetConfigsTags.BACK),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = c.ink) }
                Text(
                    fleetConfigsEntryLabel(page),
                    Modifier.padding(start = 4.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = page == FleetConfigsPage.General) { titleScope.launch { generalScroll.scrollTo(0) } }
                        .semantics { heading() }.testTag(FleetConfigsTags.TITLE),
                    color = c.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            when (page) {
                FleetConfigsPage.General -> FleetGeneralPage(decl, dark, c, onAppConfigs = { onDismiss(); onAppConfigs() }, scroll = generalScroll)
                FleetConfigsPage.About -> FleetAboutPage(decl, c)
            }
        }
    }
}

/**
 * Configs > General: ONE page. At the top, a grid of anchors laid out like a launcher's app grid (an icon tile with the
 * section's name under it, four per row); a tap scrolls this same page to that section. The sections are the app's
 * `configs_pages.general.sections` (Perms, Battery, Storage/backup, Notifications, UI, Language, App) plus Update, which every
 * app gets by default. Each section reads the device's live state for THIS app and opens the system screen that changes it,
 * always through libs:perms (the one place that builds a settings intent).
 */
@Composable
public fun FleetGeneralPage(
    decl: FleetConfigsDecl, dark: Boolean, c: ThemeColors, onAppConfigs: () -> Unit,
    scroll: ScrollState = rememberScrollState(),
) {
    val scope = rememberCoroutineScope()
    val tops = remember { mutableStateMapOf<String, Int>() }
    // The system screens these sections open change state outside the app: re-read it while the page is up (cheap getters only).
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }
    val sections = decl.general
    Column(
        Modifier.fillMaxWidth().verticalScroll(scroll).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // #1352 a jump, not an animation: the live sections (Perms re-reads every 1.5 s) relayout mid-animation and
        // stopped it half way (Calc's Update/Battery/Storage anchors never reached their section on the emulator).
        AnchorGrid(sections, c) { id -> scope.launch { scroll.scrollTo(tops[id] ?: 0) } }
        sections.forEach { s ->
            SectionCard(s.label, sectionIcon(s.id), c, Modifier.testTag(FleetConfigsTags.section(s.id)).onGloballyPositioned { tops[s.id] = it.positionInParent().y.toInt() }) {
                if (s.note.isNotBlank()) Body(s.note, c)
                when (s.id) {
                    "perms" -> PermsSection(c, tick)
                    FleetConfigs.UPDATE_SECTION -> UpdateSection(decl, c)
                    "battery" -> BatterySection(c, tick)
                    "storage" -> StorageSection(c, tick)
                    "notifications" -> NotificationsSection(c, tick)
                    "ui" -> NavUiConfigCard(dark = dark)
                    "language" -> LanguageSection(c, tick)
                    FleetConfigs.APP_SECTION -> ActionButton("Open ${decl.name.ifBlank { "app" }} configs", c, FleetConfigsTags.OPEN_APP_CONFIGS, onAppConfigs)
                }
            }
        }
        // room so the last section can scroll to the top under the grid
        Spacer(Modifier.height(320.dp))
    }
}

/** The anchors: a launcher-style grid, [COLUMNS] tiles per row, each an icon tile with the section's name under it. */
@Composable
private fun AnchorGrid(sections: List<GeneralSection>, c: ThemeColors, onAnchor: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp).testTag(FleetConfigsTags.ANCHORS),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        sections.chunked(COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { s ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { onAnchor(s.id) }
                            .semantics { contentDescription = s.label }.padding(vertical = 6.dp).testTag(FleetConfigsTags.anchor(s.id)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val tile = RoundedCornerShape(18.dp)
                        Box(
                            Modifier.size(56.dp).clip(tile).background(c.tabSelected, tile).border(1.dp, c.islandBorder, tile),
                            contentAlignment = Alignment.Center,
                        ) { Icon(sectionIcon(s.id), contentDescription = null, tint = c.onTabSelected, modifier = Modifier.size(28.dp)) }
                        Text(
                            s.label, Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp), color = c.ink, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium, maxLines = 2, textAlign = TextAlign.Center, lineHeight = 14.sp,
                        )
                    }
                }
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val COLUMNS = 4

// ── sections of General ─────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PermsSection(c: ThemeColors, tick: Int) {
    val ctx = LocalContext.current
    // #1360 the ONE shared section (libs:perms): every grant this app declares in build.json::perms (its own and its
    // helper apps'), live state, Grant / Open per row and Grant all. Never a second list here.
    // #1372 the lib draws the install-time grants too (Granted at install), so no count line here.
    com.diegonmarcos.superapp.perms.AppPermsSection(tick, com.diegonmarcos.superapp.perms.PermsStyle(ink = c.ink, dim = c.inkDim, accent = c.accent))
    ActionButton("App info", c, "fleetcfg_open_perms") { com.diegonmarcos.superapp.perms.PermsPages.openAppInfo(ctx, ctx.packageName) }
}

@Composable
private fun BatterySection(c: ThemeColors, tick: Int) {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val unrestricted = remember(tick) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
    StatusRow(if (unrestricted) "Unrestricted: not battery optimised" else "Battery optimised: Android may stop background work", unrestricted, c)
    if (Build.VERSION.SDK_INT >= 28) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val restricted = remember(tick) { am.isBackgroundRestricted }
        StatusRow(if (restricted) "Background use restricted" else "Background use allowed", !restricted, c)
    }
    ActionButton(if (unrestricted) "Battery settings" else "Allow unrestricted battery", c, "fleetcfg_open_battery") {
        // #1360 the exemption request (package-scoped when the manifest allows it) and its fallbacks live in libs:perms.
        com.diegonmarcos.superapp.perms.Perms.catalogue(ctx).byId("battery_opt")?.let { com.diegonmarcos.superapp.perms.PermsSettings.open(ctx, it) }
    }
}

@Composable
private fun StorageSection(c: ThemeColors, tick: Int) {
    val ctx = LocalContext.current
    var cleared by remember { mutableIntStateOf(0) }
    val sizes by produceState(Pair(-1L, -1L), cleared) {
        value = withContext(Dispatchers.IO) { Pair(dirSize(File(ctx.applicationInfo.dataDir)), dirSize(ctx.cacheDir) + dirSize(ctx.externalCacheDir)) }
    }
    KeyValue("App data", if (sizes.first < 0) "…" else human(sizes.first), c)
    KeyValue("Cache", if (sizes.second < 0) "…" else human(sizes.second), c)
    val backup = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP) != 0
    StatusRow(if (backup) "Android backup allowed" else "Android backup off for this app", backup, c)
    Text("Settings and app state are part of the phone profile Cloud Account backs up to, and restores from, the private vault repository.",
        color = c.inkDim, fontSize = 11.sp)
    if (requested(ctx).contains("android.permission.MANAGE_EXTERNAL_STORAGE") && Build.VERSION.SDK_INT >= 30) {
        val all = remember(tick) { Environment.isExternalStorageManager() }
        StatusRow("All-files access", all, c)
        if (!all) ActionButton("Allow all-files access", c, "fleetcfg_open_allfiles") {
            com.diegonmarcos.superapp.perms.Perms.catalogue(ctx).byId("all_files")?.let { com.diegonmarcos.superapp.perms.PermsSettings.open(ctx, it) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Clear cache", c, "fleetcfg_clear_cache") {
            ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            ctx.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            cleared++
        }
        ActionButton("Storage settings", c, "fleetcfg_open_storage") { com.diegonmarcos.superapp.perms.PermsPages.openAppInfo(ctx, ctx.packageName) }
    }
}

@Composable
private fun NotificationsSection(c: ThemeColors, tick: Int) {
    val ctx = LocalContext.current
    val on = remember(tick) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val channels = remember(tick) { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notificationChannels }
    StatusRow(if (on) "Notifications allowed" else "Notifications blocked", on, c)
    if (channels.isEmpty()) Text("No notification channel created yet.", color = c.inkDim, fontSize = 11.sp)
    channels.forEach { ch -> StatusRow(ch.name.toString(), ch.importance != NotificationManager.IMPORTANCE_NONE, c) }
    ActionButton("Notification settings", c, "fleetcfg_open_notifications") {
        com.diegonmarcos.superapp.perms.PermsPages.open(ctx, com.diegonmarcos.superapp.perms.PermsPages.notificationsIntent(ctx.packageName))
    }
}

@Composable
private fun LanguageSection(c: ThemeColors, tick: Int) {
    val ctx = LocalContext.current
    val current = remember(tick) {
        val app = if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales?.toLanguageTags().orEmpty() else ""
        if (app.isNotBlank()) app else "System default (" + ctx.resources.configuration.locales[0].displayName + ")"
    }
    KeyValue("App language", current, c)
    ActionButton("Change language", c, "fleetcfg_open_language") {
        com.diegonmarcos.superapp.perms.PermsPages.open(ctx, com.diegonmarcos.superapp.perms.PermsPages.languageIntent(ctx.packageName))
    }
}


/** Update: what is installed, Check for updates against the app's Cloud Store release, the latest build, Update, release notes. */
@Composable
private fun UpdateSection(decl: FleetConfigsDecl, c: ThemeColors) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(FleetReleases.cached()) }
    var checking by remember { mutableStateOf(false) }
    val installed = remember(ctx) { installedVersion(ctx, decl) }
    KeyValue("Installed", installed, c)
    KeyValue("Build", decl.sha.ifBlank { "local" } + if (decl.buildDate.isNotBlank()) "  ·  ${decl.buildDate}" else "", c)
    val st = state
    when {
        checking -> Text("Checking the Cloud Store release…", color = c.inkDim, fontSize = 12.sp, modifier = Modifier.testTag("fleetcfg_update_checking"))
        st == null -> Text("Not checked yet.", color = c.inkDim, fontSize = 12.sp)
        st.latestSha.isBlank() -> Text(st.error.ifBlank { "No published build found." }, color = c.inkDim, fontSize = 12.sp)
        st.upToDate(decl.sha) -> StatusRow("Up to date: the published build is this one", true, c)
        else -> {
            KeyValue("Latest", st.latestSha.take(12) + if (st.latestDate.isNotBlank()) "  ·  ${st.latestDate}" else "", c)
            StatusRow("A newer build is published", false, c)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Check for updates", c, "fleetcfg_update_check") {
            if (!checking) { checking = true; scope.launch { state = withContext(Dispatchers.IO) { FleetReleases.load(decl, force = true) }; checking = false } }
        }
        ActionButton("Update", c, "fleetcfg_update_open_store") { FleetReleases.openStore(ctx, decl) }
    }
    if (st != null && st.notes.isNotEmpty()) {
        Text("Release notes", color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        ReleaseNotes(st.notes.take(5), c)
    }
    Text("Cloud Store installs the update from the signed APK CI publishes on this app's release; nothing is downloaded here.",
        color = c.inkDim, fontSize = 11.sp)
}
