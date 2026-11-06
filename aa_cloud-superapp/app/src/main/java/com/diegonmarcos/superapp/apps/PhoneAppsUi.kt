package com.diegonmarcos.superapp.apps

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.launcher.AppLongPressMenu
import com.diegonmarcos.superapp.settings.GridColumns
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * The Compose parts both Phone surfaces share (PhoneAppsFragment's Phone tab, SuitePhoneAppsFragment's
 * merged page, and Cloud ▸ Apps' All Apps): the folder card, the folder grid, the expanded-folder
 * sheet and the small chrome. They replace the LinearLayout renderers that lived in PhoneAppsFragment's
 * companion (renderAllApps, renderFolderGrid, makeFolderCard, showFolderDialog ...).
 */

/** A folder the user opened: its label and the apps inside. */
internal class OpenFolder(val label: String, val apps: List<PhoneApp>)

internal fun folderTag(id: String) = "phone:folder:$id"
internal fun appTag(pkg: String) = "phone:app:$pkg"
internal fun phoneSubheadTag(t: String) = "phone:subhead:$t"
internal const val TAG_PHONE_REFRESH = "phone:refresh"

/** An app icon drawn from its Drawable (decoded once per drawable). */
@Composable
internal fun AppIcon(icon: Drawable?, size: Dp, modifier: Modifier = Modifier) {
    val bmp = remember(icon) { icon?.let { runCatching { it.toBitmap().asImageBitmap() }.getOrNull() } }
    if (bmp != null) Image(bmp, contentDescription = null, modifier = modifier.size(size))
    else Spacer(modifier.size(size))
}

@Composable
internal fun PhoneSubhead(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalKitPalette.current.textPrimary, style = MaterialTheme.typography.titleSmall,
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 4.dp).testTag(phoneSubheadTag(text)))
}

/** Thin separator line between the sections of a merged page. */
@Composable
internal fun PhoneSectionDivider() {
    Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp).height(1.dp)
        .background(LocalKitPalette.current.hairline))
}

/** Centered "refresh app list" icon, pinned at the very bottom of a page. */
@Composable
internal fun PhoneRefreshBar(onRefresh: () -> Unit) {
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh app list",
            tint = LocalKitPalette.current.textPrimary.copy(alpha = 0.8f),
            modifier = Modifier.clickable { Haptics.tap(view); onRefresh() }.padding(8.dp).size(22.dp)
                .testTag(TAG_PHONE_REFRESH))
    }
}

/** The folder square's edge in dp: the wanted cell, but never wider than its column (60 dp is right at
 *  4-5 columns and wider than the column from 6 on, at 360 dp). */
internal fun folderCellDp(ctx: Context, wantDp: Int, columns: Int): Dp {
    val d = ctx.resources.displayMetrics.density
    val px = GridColumns.iconPx((wantDp * d).toInt(),
        GridColumns.cellPx(ctx.resources.displayMetrics.widthPixels - (16 * d).toInt(), columns),
        (4 * d).toInt(), (28 * d).toInt())
    return (px / d).dp
}

/** A single folder tile: glass square with a 2×2 mini-icon preview, label underneath. */
@Composable
internal fun FolderCard(label: String, apps: List<PhoneApp>, cell: Dp, labelSp: Float, modifier: Modifier = Modifier,
                        tag: String, onOpen: () -> Unit) {
    val p = LocalKitPalette.current
    val view = LocalView.current
    val mini = (cell - 18.dp) / 2
    Column(modifier.clickable { Haptics.tap(view); onOpen() }.padding(horizontal = 2.dp, vertical = 6.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(cell).clip(RoundedCornerShape(16.dp)).background(p.surface)
            .border(1.dp, p.hairline, RoundedCornerShape(16.dp)).padding(6.dp)) {
            Column {
                for (r in 0..1) Row {
                    for (c in 0..1) {
                        Box(Modifier.size(mini).padding(1.dp)) { AppIcon(apps.getOrNull(r * 2 + c)?.icon, mini - 2.dp) }
                    }
                }
            }
        }
        Text(label, color = p.textPrimary, fontSize = (labelSp - 1f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.width(cell).padding(top = 4.dp))
    }
}

/** Lay [folders] out as a fixed-column grid of folder cards, one lazy item per row. A short last row is
 *  padded with weighted spacers so it stays left-aligned (folders flow left to right). */
internal fun LazyListScope.folderGridItems(
    ctx: Context,
    keyPrefix: String,
    folders: List<PhoneFolders.Folder>,
    grouped: Map<String, List<PhoneApp>>,
    columns: Int,
    cellDp: Int,
    onOpen: (OpenFolder) -> Unit,
) {
    val cell = folderCellDp(ctx, cellDp, columns)
    val labelSp = GridColumns.labelSp(GridColumns.phone(ctx))
    folders.chunked(columns).forEachIndexed { i, row ->
        item(key = "$keyPrefix:$i") {
            Row(Modifier.fillMaxWidth()) {
                for (f in row) {
                    val apps = grouped[f.id].orEmpty()
                    FolderCard(f.label, apps, cell, labelSp, Modifier.weight(1f), folderTag(f.id)) { onOpen(OpenFolder(f.label, apps)) }
                }
                for (k in row.size until columns) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** What "All Apps by purpose" draws: category sections (by label prefix) each with its folders. */
internal class AllAppsSection(val title: String, val folders: List<PhoneFolders.Folder>)
internal class AllApps(val sections: List<AllAppsSection>, val grouped: Map<String, List<PhoneApp>>)

/**
 * "All Apps by purpose" as data — category folder grid bucketed by label prefix (System _, Services -,
 * Tools A ., Tools B >), each under its own subhead, plus a trailing "Other" bucket. Shared between the
 * Phone tab, the merged Suite→Phone page and Cloud ▸ Apps. [only] is the mirror of [exclude] (#563).
 */
internal fun allApps(ctx: Context, exclude: Set<String> = emptySet(), only: Set<String>? = null): AllApps {
    val folders = PhoneAppsFragment.cachedFolders()
    val apps = PhoneAppsFragment.snapshot(ctx).filter { it.packageName !in exclude && (only == null || it.packageName in only) }
    // #1553 The Folders page is the source of truth: a Phone-scope assignment the user made there wins over the baked
    // keyword classification. No override set ⇒ the grouping is unchanged, so the default grid is exactly as before.
    val grouped = com.diegonmarcos.superapp.launcher.folders.FolderGridBridge.applyOverrides(
        PhoneAppsFragment.groupedFor(apps, folders, unfiltered = exclude.isEmpty() && only == null),
        com.diegonmarcos.superapp.launcher.folders.FolderGridBridge.launcherOverrides(ctx),
        folders.map { it.id }.toSet(),
    )
    // Skip empty folders — One UI hides them too — EXCEPT those marked `pin: true` (today: _Misc).
    val visible = folders.filter { it.pinned || (grouped[it.id]?.size ?: 0) > 0 }
    val sections = PhoneSections.loadFromBuildConfig().map { it.title to it.prefix }
    return AllApps(
        PhoneTaxonomyLayout.bucket(sections, visible) { it.label }.map { AllAppsSection(it.title ?: "Other", it.folders) },
        grouped,
    )
}

internal fun LazyListScope.allAppsItems(ctx: Context, data: AllApps, onOpen: (OpenFolder) -> Unit) {
    val columns = GridColumns.phone(ctx)
    for (s in data.sections) {
        item(key = "allapps:head:${s.title}") { PhoneSubhead(s.title) }
        folderGridItems(ctx, "allapps:${s.title}", s.folders, data.grouped, columns, 60, onOpen)
    }
}

/** The Smart Folders body: one subhead per `group`, then smaller "subtile" folder cards. */
internal fun LazyListScope.smartFolderItems(ctx: Context, rendered: List<PhoneAppsFragment.SmartRendered>, onOpen: (OpenFolder) -> Unit) {
    val columns = GridColumns.phone(ctx)
    val subtileColumns = (columns + 1).coerceAtMost(GridColumns.MAX + 1)
    rendered.groupBy { it.spec.group ?: "Other" }.forEach { (group, inGroup) ->
        item(key = "smart:head:$group") { PhoneSubhead(group) }
        // A synthetic Folder per Smart Folder, id prefixed "smart:" so it cannot collide with a real one.
        val synthetic = inGroup.map { PhoneFolders.Folder("smart:${it.spec.id}", "zz", it.spec.title, emptyList()) }
        val grouped = inGroup.associate { "smart:${it.spec.id}" to it.apps }
        folderGridItems(ctx, "smart:$group", synthetic, grouped, subtileColumns, 44, onOpen)
    }
}

/**
 * In-app open of Store ▸ Cloud Constellation — where every lib's entry lives. A library has no launcher
 * activity to fire, and #156 forbids showing it as "opening" while leaving the app or doing nothing.
 */
internal fun openStore(ctx: Context) {
    (ctx as? android.app.Activity)?.let { (it as? ShellActivity)?.nav?.openSectionPage("config", "store-cloud", null) }
}

/** The expanded-folder icon edge: screen - 2 x 20 dp margin - 2 x 16 dp sheet padding, split [cols] ways. */
internal fun expandedIconDp(ctx: Context, cols: Int): Dp {
    val d = ctx.resources.displayMetrics.density
    val px = GridColumns.iconPx((48 * d).toInt(),
        GridColumns.cellPx(ctx.resources.displayMetrics.widthPixels - (72 * d).toInt(), cols),
        (2 * GridColumns.cellPadDp(cols) * d).toInt(), (24 * d).toInt())
    return (px / d).dp
}

/**
 * The folder card's fill: near-black at 95%, exactly the GradientDrawable the View dialog gave it (0xF21A1A24, 28 dp corners).
 * NOT the theme's `surface`: under the Cloud theme that is 13%-alpha white, and the Compose port painted the card with it, so an
 * opened folder showed its apps straight over the page behind (owner, Phone > Apps). A card that floats over a dimmed page
 * has to hide it, so this is its own constant, shared with the launcher's tile folders.
 */
internal val FolderSheetFill = androidx.compose.ui.graphics.Color(0xF21A1A24)

/** The folder look: a rounded card floating over the dimmed page, dismissed from the scrim. */
@Composable
internal fun FolderSheetFrame(label: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FolderSheetCard(label, content)
    }
}

/** The card itself, split from the dialog window so it can be drawn (and tested) without one. */
@Composable
internal fun FolderSheetCard(label: String, content: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    Column(Modifier.padding(20.dp).clip(RoundedCornerShape(28.dp)).background(FolderSheetFill).padding(16.dp)
        .testTag(TAG_FOLDER_SHEET)) {
        Text(label, color = p.textPrimary, style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 16.dp))
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) { content() }
    }
}

/** One full-size app inside an expanded folder: icon over a (max two line) label. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ExpandedAppTile(
    tag: String, label: String, icon: Drawable?, installed: Boolean, cols: Int, iconDp: Dp,
    desc: String?, modifier: Modifier = Modifier, onClick: () -> Unit, onLong: (() -> Unit)?,
) {
    val p = LocalKitPalette.current
    Column(modifier
        .combinedClickable(onClick = onClick, onLongClick = onLong)
        .padding(GridColumns.cellPadDp(cols).dp)
        .semantics { if (desc != null) contentDescription = desc }
        .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (installed) AppIcon(icon, iconDp)
        else Icon(painterResource(R.drawable.ic_app_not_installed), contentDescription = null, tint = p.textSecondary,
            modifier = Modifier.size(iconDp))
        Text(label, color = if (installed) p.textPrimary else p.textSecondary, fontSize = GridColumns.labelSp(cols).sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** The expanded folder of the Phone tab: every app in the phone column count. */
@Composable
internal fun PhoneFolderSheet(open: OpenFolder, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val launcherApps = remember { ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps }
    val cols = GridColumns.phone(ctx)
    val iconDp = remember(cols) { expandedIconDp(ctx, cols) }
    FolderSheetFrame(open.label, onDismiss) {
        for (row in open.apps.chunked(cols)) {
            Row(Modifier.fillMaxWidth()) {
                for (app in row) {
                    ExpandedAppTile(appTag(app.packageName), app.label, app.icon, true, cols, iconDp, null, Modifier.weight(1f),
                        onClick = {
                            Haptics.tap(view)
                            // A launchable app fires normally. A LIBRARY has no launcher component and cannot be
                            // launched (#156); its tap opens that lib's entry in the in-app Constellation
                            // AppStore instead (#474).
                            val comp = app.activityComponent
                            if (com.diegonmarcos.superapp.launcher.SelfTile.open(ctx, app.packageName)) Unit
                            else if (comp != null) runCatching { launcherApps.startMainActivity(comp, app.user, null, null) }
                            else openStore(ctx)
                            onDismiss()
                        },
                        onLong = {
                            // Long-press → stock options menu; dismiss the folder so the menu sits on its own backdrop.
                            Haptics.tap(view); onDismiss(); AppLongPressMenu.show(ctx, app.packageName)
                        })
                }
                for (k in row.size until cols) Spacer(Modifier.weight(1f))
            }
        }
    }
}

internal const val TAG_FOLDER_SHEET = "phone:sheet"
