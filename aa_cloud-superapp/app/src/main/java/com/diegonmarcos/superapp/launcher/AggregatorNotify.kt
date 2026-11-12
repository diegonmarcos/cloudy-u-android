package com.diegonmarcos.superapp.launcher

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.apps.InboxClasses
import com.diegonmarcos.superapp.apps.PhoneTaxonomy
import com.diegonmarcos.superapp.ui.AppIconCache
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/*
 * The notification half of the aggregator page, in Compose, matched to Samsung One UI 8's notification panel: one
 * rounded translucent container per app, folded when it holds several ("+N", the fold lives on
 * AggregatorState.collapsedGroups so a toggle tap does not spring it open again), a swipe either way files the item
 * left files the item away and a swipe right toggles read, a long-press opens the Mark read / Archive / Open app bottom
 * sheet, the header glyph is the app's own icon in monochrome, a phone row carries its sender's 40dp round avatar, and
 * the Archive is ONE collapsed section at the bottom of the page collecting every box the user filed away, whichever
 * card it came from.
 */

private const val TAG = "AggregatorStack"

/** Re-asks the stores a pull or a toggle changed, and drives the ntfy polls of every panel that has channels. */
@Composable
internal fun AggregatorEffects(state: AggregatorState) {
    LaunchedEffect(state.epoch) { state.housekeeping() }
    // #1672 no polling: a notification the listener records re-reads the page through the store's change callback.
    if (state.panels.any { it.kind == "notification_center" }) {
        // The bump runs on the composition's own scope, so it is cancelled with the page.
        val scope = rememberCoroutineScope()
        androidx.compose.runtime.DisposableEffect(state) {
            val l: () -> Unit = { scope.launch(Dispatchers.Main) { state.epoch++ } }
            com.diegonmarcos.superapp.notificationcenter.PhoneNotificationStore.addListener(l)
            onDispose { com.diegonmarcos.superapp.notificationcenter.PhoneNotificationStore.removeListener(l) }
        }
    }
    for ((i, panel) in state.panels.withIndex()) {
        if (!state.pollsNtfy(panel)) continue
        // Keyed on the taxonomy rows too: narrowing a row can bring channels into view that nothing has polled yet.
        LaunchedEffect(i, state.pollEpoch, state.toolsMode, state.servicesMode) { state.pollPanel(i, panel) }
    }
}

// ── kind=notification_center ─────────────────────────────────────────

@Composable
internal fun NotificationCenterBody(state: AggregatorState, panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    // #1672 Cloud Apps / Phone Apps Notifications: the real notifications of one AppSplit side, no ntfy channels.
    if (panel.split.isNotBlank()) { SplitCenter(state, panel, onOpen); return }
    when (panel.stream) {
        "phone" -> PhoneCenter(state, onOpen)
        "cloud" -> CloudCenter(state, panel, onOpen)
        // C3 Obsv's stream: the ntfy CHANNELS and nothing else — `cloud` minus the in-app feed.
        "channels" -> NtfyGroups(state, panel, onOpen)
        // A card that declares no stream cannot guess one, and it must not look like "you have no notifications".
        else -> {
            LaunchedEffect(panel) {
                android.util.Log.w(TAG, "notification panel '${panel.title}' declares no `stream`: add " +
                    "\"stream\": \"phone\", \"cloud\" or \"channels\" in build.json")
            }
            AggStateLine("no stream declared", Tone.UNKNOWN)
        }
    }
}

@Composable
private fun NotificationAccessOff(extra: String? = null, onOpen: ((String) -> Unit)? = null) {
    val ctx = LocalContext.current
    AggStateLine("unavailable · permission not granted", Tone.UNKNOWN)
    if (extra != null) AggCaption(extra)
    else {
        // #1672 the clear way to fix it: Configs > Perms, where every grant of this app lives.
        if (onOpen != null) com.diegonmarcos.superapp.uikit.KitTextButton(modifier = Modifier.testTag(AggTags.OPEN_PERMS),
            onClick = { runCatching { onOpen(AggregatorModel.PERMS_TARGET) } }) {
            Text("Open Configs ▸ Perms", color = LocalKitPalette.current.accent)
        }
        com.diegonmarcos.superapp.uikit.KitTextButton(modifier = Modifier.testTag(TAG_GRANT), onClick = {
            runCatching {
                ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text("Grant Notification Access", color = LocalKitPalette.current.accent) }
    }
}

/**
 * #1672 the notification blocks of the Notify sub-pages: the POSTED notifications (title, text, app icon, time, actions),
 * never a list of apps. `split` "all" (the All page) = every notification, cloudy and phone together, one expandable box
 * per app, newest first - Android's own notification centre. "cloudy" (Cloudy Apps) = the fleet's own apps plus the
 * in-app feed; "phone" (Phone Apps) = every other app; both carry the View switch By Categories (one section per app
 * category, A-Z, notifications by time) / By Time (one timeline). Without notification access the phone part cannot be
 * read: the block says so and opens Configs > Perms; the in-app feed still shows, it needs no grant.
 */
@Composable
private fun SplitCenter(state: AggregatorState, panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val feed = panel.split
    val all = feed == AggregatorModel.SPLIT_ALL
    Column(Modifier.fillMaxWidth().testTag(AggTags.feed(feed))) {
        if (!all) GroupModeSwitch(state, feed, VIEW_OPTIONS)
        val granted = remember(state.epoch) { runCatching { AggregatorModel.isNotificationAccessGranted(ctx) }.getOrDefault(false) }
        if (!granted) Column(Modifier.fillMaxWidth().testTag(AggTags.ACCESS_PROMPT)) { NotificationAccessOff(onOpen = onOpen) }
        if (!granted && feed == com.diegonmarcos.superapp.notificationcenter.AppSplit.PHONE) return@Column
        val views by remember(panel) { derivedStateOf { runCatching { state.splitViews(panel) }.getOrDefault(emptyList()) } }
        if (views.isEmpty()) {
            val total = remember(state.epoch) { runCatching { state.splitTotal(panel) }.getOrDefault(0) }
            if (total == 0) AggStateLine("silent · nothing captured", Tone.WARN) else AggCaption(AggregatorModel.filteredAwayNote(total))
            return@Column
        }
        when {
            all -> GroupList(state, views, onOpen)
            state.groupMode(feed) == AggregatorModel.GROUP_TIME -> Timeline(state, feed, views.map { it.group }, null, onOpen)
            else -> Categories(state, feed, views.map { it.group }, onOpen)
        }
    }
}

private val VIEW_OPTIONS = listOf(AggregatorModel.VIEW_CATEGORIES to "By Categories", AggregatorModel.GROUP_TIME to "By Time")
private val ARCHIVE_OPTIONS = listOf(AggregatorModel.GROUP_APP to "By app", AggregatorModel.GROUP_TIME to "By time")

/** #1672 By Categories: a header per app category (A-Z), then that category's notifications as one timeline. */
@Composable
private fun Categories(state: AggregatorState, feed: String, groups: List<NotifGroup>, onOpen: (String) -> Unit) {
    // archived boxes and swiped rows are in Archived, so a category left empty by them draws no header
    val live = groups.filterNot { state.isArchived(it.key) }
        .map { g -> g.copy(rows = g.rows.filterNot { state.isRowArchived(it) }) }.filter { it.rows.isNotEmpty() }
    val sections = AggregatorModel.byCategory(live) { state.categoryOf(it) }
    for ((cat, gs) in sections) androidx.compose.runtime.key(cat) {
        Text(cat, color = NotifCard.meta, fontSize = NotifCard.nameSp, fontWeight = FontWeight.Medium, fontFamily = NotifCard.font,
            maxLines = 1, modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 4.dp).testTag(AggTags.category(feed, cat)))
        Timeline(state, "$feed:$cat", gs, null, onOpen)
    }
}

/** #1672 a View switch: one segmented control per notification block, remembered per page and block. The first option
 *  is what a stored "app" (the old default) shows. */
@Composable
internal fun GroupModeSwitch(state: AggregatorState, feed: String, options: List<Pair<String, String>> = ARCHIVE_OPTIONS) {
    val hostView = LocalView.current
    val stored = state.groupMode(feed)
    val mode = if (options.any { it.first == stored }) stored else options.first().first
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(NotifCard.pillShape).background(NotifCard.pill.copy(alpha = 0.6f))
        .padding(3.dp)) {
        for ((id, label) in options) {
            val on = id == mode
            Text(label, color = if (on) Color.Black else NotifCard.title, fontSize = 12.sp,
                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.weight(1f).clip(NotifCard.pillShape).background(if (on) NotifCard.title else Color.Transparent)
                    .semantics { contentDescription = "$label${if (on) ", selected" else ""}" }
                    .clickable { Haptics.tap(hostView); state.setGroupMode(feed, id) }
                    .padding(vertical = 6.dp).testTag(AggTags.groupMode(feed, id)))
        }
    }
}

/**
 * #1672 the by-time layout: every notification of [groups] in ONE One UI container, newest first, each item headed by
 * its app (glyph, name, time). The rows are the same rows the boxes draw (swipe, long-press sheet, avatar). Outside the
 * Archive, archived boxes and swiped rows are left out. In the Archive ([archivedRowsOnly] != null) each item carries a
 * Restore: rows swiped off a live box go back to it, an archived box comes back whole.
 */
@Composable
internal fun Timeline(
    state: AggregatorState, feed: String, groups: List<NotifGroup>, archivedRowsOnly: Set<String>?, onOpen: (String) -> Unit,
) {
    val hostView = LocalView.current
    val inArchive = archivedRowsOnly != null
    val shown = if (inArchive) groups else groups.filterNot { state.isArchived(it.key) }
        .map { g -> g.copy(rows = g.rows.filterNot { state.isRowArchived(it) }) }
    val items = AggregatorModel.timeline(shown)
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = NotifCard.gap).clip(NotifCard.shape).background(NotifCard.surface)
        .testTag(AggTags.timeline(feed))) {
        for ((i, pair) in items.withIndex()) {
            val (g, r) = pair
            val rid = r.id.ifBlank { "${g.key}/${r.ts}/${r.title}" }
            androidx.compose.runtime.key(rid) {
                if (i > 0) Box(Modifier.padding(horizontal = NotifCard.pad).fillMaxWidth().height(0.7.dp).background(NotifCard.divider))
                val tint = appTint(g.key, g.label)
                Column(Modifier.fillMaxWidth().testTag(AggTags.timelineRow(rid))) {
                    Row(Modifier.fillMaxWidth().padding(start = NotifCard.pad, end = 12.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        GroupGlyph(g, tint)
                        Text(g.label, color = NotifCard.title, fontSize = NotifCard.nameSp, fontWeight = FontWeight.Medium,
                            fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp).widthIn(max = 200.dp))
                        Text(" • " + AggregatorModel.shortAgo(System.currentTimeMillis() - r.ts), color = NotifCard.meta,
                            fontSize = NotifCard.metaSp, fontFamily = NotifCard.font, maxLines = 1)
                        Box(Modifier.weight(1f))
                        if (inArchive) Text(AggregatorModel.ARCHIVE_RESTORE_LABEL, color = NotifCard.title, fontSize = 12.sp,
                            fontFamily = NotifCard.font,
                            modifier = Modifier.clip(NotifCard.pillShape).background(NotifCard.pill)
                                .clickable {
                                    Haptics.tap(hostView)
                                    if (r.id in archivedRowsOnly.orEmpty()) state.archiveRow(r.id, false) else state.setArchived(g.key, false)
                                }
                                .padding(horizontal = 10.dp, vertical = 3.dp).testTag(AggTags.timelineRestore(rid)))
                    }
                    NotifRowItem(state, r, g.launchPackage, tint, expanded = false, ownTime = false,
                        inArchive = inArchive && r.id in archivedRowsOnly.orEmpty())
                }
            }
        }
    }
}

/** Phone stream: one group per POSTING APP, keyed on `packageName` and labelled with `appLabel`. There is no package
 *  list here and there must never be one — the key rides on the notification itself. */
@Composable
private fun PhoneCenter(state: AggregatorState, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val granted = remember(state.epoch) { AggregatorModel.isNotificationAccessGranted(ctx) }
    if (!granted) {
        // A MISSING CAPABILITY, not an empty inbox: grey, with the way to fix it attached.
        NotificationAccessOff(); return
    }
    val total = remember(state.epoch) { state.phoneFeed().size }
    if (total == 0) {
        // Granted and empty is a REAL state and a different one: amber, so it cannot be mistaken for the grey case.
        AggStateLine("silent · nothing captured", Tone.WARN); return
    }
    val views by remember { derivedStateOf { state.phoneViews() } }
    if (views.isEmpty()) AggCaption(AggregatorModel.filteredAwayNote(total)) else GroupList(state, views, onOpen)
}

/** Cloud stream, grouped by PUBLISHER: the ntfy TOPIC for a fleet message, the `source` field for an in-app one. */
@Composable
private fun CloudCenter(state: AggregatorState, panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    AggShadeLabel("IN-APP FEED")
    val total = remember(state.epoch) { state.localFeedTotal() }
    val local by remember { derivedStateOf { state.localFeedViews() } }
    if (local.isEmpty()) {
        if (total == 0) AggStateLine("silent · no in-app events", Tone.WARN)
        else AggCaption(AggregatorModel.filteredAwayNote(total))
    } else GroupList(state, local, onOpen)
    AggShadeLabel("CHANNELS")
    NtfyGroups(state, panel, onOpen)
}

/**
 * One group per ntfy topic in this card's declared scopes, each with a live verdict: "N · 5m ago" (green), "silent 7d"
 * (amber: ntfy answers 200 for a topic nobody has published to, so a dead publisher looks like a quiet one) and the
 * poll's failure sentence (grey, never red and never absent: a failed fetch is a claim about THIS PHONE'S network).
 * The order is decided ONCE from the last measurement, so the poll landing never re-sorts boxes under the thumb.
 */
@Composable
internal fun NtfyGroups(state: AggregatorState, panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    val topics = state.ntfyTopics(panel)
    if (topics.isEmpty()) {
        LaunchedEffect(panel) {
            android.util.Log.w(TAG, "ntfy panel '${panel.title}' matched no channel: no build.json::ui.ntfy topic " +
                "falls in scopes (" + panel.scopes.joinToString(", ").ifBlank { "all" } + ")")
        }
        AggStateLine("no channels in scope", Tone.UNKNOWN); return
    }
    val order = remember(state.epoch, state.sortMode, topics) {
        AggregatorModel.orderTopics(topics, state.sortMode, state.ntfyCache.toMap())
    }
    GroupList(state, state.ntfyViews(panel, order), onOpen)
}

// ── groups ───────────────────────────────────────────────────────────

/**
 * Draw [views] as One UI notification cards, one per app. Archived boxes are not drawn here (they live in the page's
 * Archive), and neither is a box whose every row was swiped into it.
 */
@Composable
internal fun GroupList(state: AggregatorState, views: List<GroupView>, onOpen: (String) -> Unit) {
    for (v in views) {
        if (state.isArchived(v.group.key)) continue
        val rows = v.group.rows
        if (rows.isNotEmpty() && rows.all { state.isRowArchived(it) }) continue
        androidx.compose.runtime.key(v.group.key) { GroupBlock(state, v, archived = false, onOpen = onOpen) }
    }
}

/**
 * One UI 8 notification-panel metrics (Galaxy A37, Android 16). The container is a TRANSLUCENT DARK surface in either
 * theme (that is what the Samsung shade is), so its ink is fixed light ink rather than the page palette's.
 * Type: One UI Sans is not bundled, so Roboto (FontFamily.Default) at One UI's sizes.
 */
internal object NotifCard {
    val surface = Color(0xEB2A2A2E)          // #2A2A2E at ~92 %
    val title = Color(0xFFFFFFFF)
    val body = Color(0xFFB0B0B5)
    val meta = Color(0xFF9A9AA0)
    val pill = Color(0xFF3A3A3F)
    val divider = Color(0x24FFFFFF)
    val menu = Color(0xFF242428)
    val handle = Color(0x66FFFFFF)
    /** The dark, blurred-look shade the containers float on (One UI's panel dim over the wallpaper). */
    val panel = androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(Color(0xF2141418), Color(0xF01B1B21), Color(0xF5101013)),
    )
    val avatar = 40.dp
    // Style tokens: docs/oneui-notification-spec.md is their source and NotifStyleGuardTest holds them to it.
    val radius = 28.dp
    val shape = RoundedCornerShape(radius)
    val pillShape = RoundedCornerShape(20.dp)
    val gap = 8.dp
    val pad = 16.dp
    val headerIcon = 18.dp
    val nameSp = 12.sp
    val metaSp = 12.sp
    val titleSp = 16.sp
    val bodySp = 14.sp
    val actionHeight = 36.dp
    val actionRadius = 18.dp
    val actionShape = RoundedCornerShape(actionRadius)
    val actionSp = 14.sp
    val font = FontFamily.Default
}

/** The tint of an app's monochrome header glyph: the brand colour for apps everybody knows, a stable hue otherwise. */
internal fun appTint(key: String, label: String): Color {
    val k = (key + " " + label).lowercase()
    return when {
        "whatsapp" in k -> Color(0xFF25D366)
        "gmail" in k -> Color(0xFFEA4335)
        "telegram" in k -> Color(0xFF2AABEE)
        "signal" in k -> Color(0xFF3A76F0)
        "mail" in k -> Color(0xFF3E91FF)
        "system" in k || "android" in k -> Color(0xFFB0B0B5)
        "calendar" in k -> Color(0xFF4285F4)
        else -> Color(MONOGRAM_HUES[AggregatorModel.monogramHue(key, MONOGRAM_HUES.size)]).let {
            // The monogram hues are dark fills; lift them so a 16dp glyph reads on #2A2A2E.
            androidx.compose.ui.graphics.lerp(it, Color.White, 0.35f)
        }
    }
}

/**
 * One app / publisher as ONE One UI 8 notification container: a header (16dp tinted app glyph, app name • time, "+N"
 * pill when folded, chevron in a circle), then its notifications separated by thin dividers — 15sp semibold title, 14sp
 * body, two lines while collapsed. A group is folded by default (newest only, "+N"); a tap on the header or the pill
 * opens it and every item shows its own time. Long-press opens the action sheet (Mark read / Archive / Open app); swipe
 * either way archives. Inside the Archive the card carries a Restore pill, since that is the only thing to do there.
 *
 * [rowArchive]: this box is the Archive's view of rows swiped away from a box that is still on the page, so Restore
 * puts those rows back rather than un-archiving the box.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupBlock(
    state: AggregatorState,
    view: GroupView,
    archived: Boolean,
    rowArchive: Boolean = false,
    onOpen: (String) -> Unit,
) {
    val p = LocalKitPalette.current
    val g = view.group
    val ctx = LocalContext.current
    val hostView = LocalView.current
    val rows = if (archived) g.rows else g.rows.filterNot { state.isRowArchived(it) }
    val stacked = rows.size > 1
    // Absent = One UI's default (folded); false = the user opened it; true = the user folded it again.
    val open = state.collapsedGroups[g.key] == false
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(160), label = "chevron")
    val toggle: () -> Unit = { state.collapsedGroups[g.key] = open }
    var menu by remember { mutableStateOf(false) }
    val tint = appTint(g.key, g.label)
    val launch: (() -> Unit)? = when {
        g.launchPackage.isNotBlank() -> ({
            runCatching { ctx.packageManager.getLaunchIntentForPackage(g.launchPackage)?.let { ctx.startActivity(it) } }
            Unit
        })
        g.url.isNotBlank() -> ({ onOpen(g.url) })
        else -> null
    }
    Box(Modifier.fillMaxWidth().padding(bottom = NotifCard.gap).testTag(AggTags.group(g.key))) {
        Column(Modifier.fillMaxWidth().clip(NotifCard.shape).background(NotifCard.surface)) {
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(
                        onClick = { Haptics.tap(hostView); toggle() },
                        onLongClick = { Haptics.tap(hostView); menu = true },
                    )
                    .padding(start = NotifCard.pad, end = 12.dp, top = 12.dp, bottom = 2.dp)
                    .testTag(AggTags.groupHeader(g.key)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupGlyph(g, tint)
                Text(g.label, color = NotifCard.title, fontSize = NotifCard.nameSp, fontWeight = FontWeight.Medium,
                    fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).widthIn(max = 200.dp))
                if (g.newest > 0L) Text(" • " + AggregatorModel.shortAgo(System.currentTimeMillis() - g.newest),
                    color = NotifCard.meta, fontSize = NotifCard.metaSp, fontFamily = NotifCard.font, maxLines = 1)
                Box(Modifier.weight(1f))
                // Only a verdict that is NOT a plain count is drawn (an ntfy channel's "silent 7d" or poll failure):
                // the count is what the "+N" pill says.
                if (view.chip.tone != Tone.OK) Text(view.chip.text, color = toneColor(p, view.chip.tone),
                    fontSize = 12.sp, maxLines = 1,
                    modifier = Modifier.padding(end = 6.dp).testTag(AggTags.groupChip(g.key)))
                if (stacked && !open) Text(AggregatorModel.countPill(rows.size - 1), color = NotifCard.title,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = NotifCard.font,
                    modifier = Modifier.padding(end = 6.dp).clip(NotifCard.pillShape).background(NotifCard.pill)
                        .clickable { Haptics.tap(hostView); toggle() }
                        .padding(horizontal = 8.dp, vertical = 2.dp).testTag(AggTags.groupMore(g.key)))
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(NotifCard.pill).testTag(AggTags.groupChevron(g.key)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = NotifCard.title,
                        modifier = Modifier.size(14.dp).rotate(90f + turn))
                }
            }
            val shown = if (open || !stacked) rows else listOfNotNull(rows.maxByOrNull { it.ts })
            for ((i, r) in shown.withIndex()) androidx.compose.runtime.key(r.id.ifBlank { "${r.ts}/${r.title}" }) {
                if (i > 0) Box(Modifier.padding(horizontal = NotifCard.pad).fillMaxWidth().height(0.7.dp)
                    .background(NotifCard.divider))
                NotifRowItem(state, r, g.launchPackage, tint, expanded = open, ownTime = open && stacked,
                    inArchive = archived)
            }
            if (archived) Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.End,
            ) { ArchivePill(state, g, archived = true, rowArchive = rowArchive) }
            else if (rows.isEmpty()) Box(Modifier.height(6.dp))   // a quiet channel has nothing to read, archive or reply to
            else {
                // The box's own actions, back in One UI's action-pill row (#1068): Mark read, Archive, Open, Reply.
                val replyRow = rows.filter { it.id.startsWith(AggregatorModel.PHONE_NS) }.maxByOrNull { it.ts }
                val unreadAny = rows.any { state.isUnread(it) }
                NotifActionRow(
                    key = g.key,
                    onRead = { state.markGroupRead(g) },
                    onArchive = { state.setArchived(g.key, true) },
                    onOpen = launch,
                    replyKey = replyRow?.id?.removePrefix(AggregatorModel.PHONE_NS),
                    unread = unreadAny,
                )
            }
        }
        NotifSheet(menu, { menu = false }) {
            if (!archived && g.rows.isNotEmpty()) SheetItem(AggregatorModel.MARK_READ_LABEL, AggTags.groupCheck(g.key)) {
                menu = false; state.markGroupRead(g)
            }
            if (!archived) SheetItem(AggregatorModel.ARCHIVE_LABEL, AggTags.groupArchive(g.key)) {
                menu = false; state.setArchived(g.key, true)
            }
            if (launch != null) SheetItem("Open app", AggTags.groupOpen(g.key)) { menu = false; launch() }
        }
    }
}

/** One UI's long-press menu: a dark bottom sheet with 26dp top corners, a grab handle and full-width rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotifSheet(open: Boolean, dismiss: () -> Unit, items: @Composable () -> Unit) {
    if (!open) return
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = NotifCard.radius, topEnd = NotifCard.radius),
        containerColor = NotifCard.menu,
        scrimColor = Color(0x99000000),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp)
                .clip(CircleShape).background(NotifCard.handle))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 20.dp).testTag(AggTags.SHEET)) { items() }
    }
}

@Composable
private fun SheetItem(label: String, tag: String, onClick: () -> Unit) {
    Text(label, color = NotifCard.title, fontSize = 16.sp, fontFamily = NotifCard.font,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp).testTag(tag))
}

/** A One UI pill: 20dp radius, #3A3A3F fill, 14sp white text. */
@Composable
private fun OneUiPill(label: String, description: String, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        label, color = NotifCard.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = NotifCard.font,
        textAlign = TextAlign.Center,
        modifier = modifier.clip(NotifCard.pillShape).background(NotifCard.pill)
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp)
            .semantics { contentDescription = description }.testTag(tag),
    )
}

/** Restore, on a card inside the Archive. It MOVES THE BOX immediately — writing the preference alone would read as a
 *  dead button. */
@Composable
private fun ArchivePill(state: AggregatorState, g: NotifGroup, archived: Boolean, rowArchive: Boolean) {
    val hostView = LocalView.current
    val key = g.key
    OneUiPill(
        if (archived) AggregatorModel.ARCHIVE_RESTORE_LABEL else AggregatorModel.ARCHIVE_LABEL,
        if (archived) "Restore this app out of the Archive" else "Archive this app",
        AggTags.groupArchive(key),
    ) {
        Haptics.tap(hostView)
        if (rowArchive) state.restoreRows(g) else state.setArchived(key, !state.isArchived(key))
    }
}

private val MONOGRAM_HUES = longArrayOf(0xFF6D5AE6, 0xFF1F8A70, 0xFFB5556D, 0xFF2C6FB5, 0xFF9A6A2F, 0xFF4E7A2A)

/**
 * The header's 16dp app glyph, monochrome in the app's colour as One UI draws a notification's small icon. With a
 * package, its launcher icon is reduced to a tinted silhouette; without one (an ntfy topic, an in-app source) the app's
 * initial in the same tint. #498-ANR: the icon is never loaded on the main thread — a miss shows the initial at once and
 * ONE background load swaps the icon in.
 */
@Composable
private fun GroupGlyph(g: NotifGroup, tint: Color) {
    val ctx = LocalContext.current
    val pkg = g.launchPackage
    var icon by remember(pkg) { mutableStateOf(if (pkg.isBlank()) null else AppIconCache.cached(pkg)) }
    if (icon == null && pkg.isNotBlank() && !AppIconCache.knownMissing(pkg)) {
        LaunchedEffect(pkg) { icon = withContext(Dispatchers.IO) { AppIconCache.load(ctx, pkg) } }
    }
    val bmp = remember(icon) { icon?.let { runCatching { monochromeGlyph(it).asImageBitmap() }.getOrNull() } }
    if (bmp != null) Image(bmp, null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(NotifCard.headerIcon))
    else Box(Modifier.size(NotifCard.headerIcon), contentAlignment = Alignment.Center) {
        Text(g.label.trim().take(1).uppercase(), color = tint, fontSize = 13.sp, fontWeight = FontWeight.Black,
            fontFamily = NotifCard.font, lineHeight = 14.sp)
    }
}

/**
 * One UI's notification glyph is the app's own icon as a one-colour silhouette. An adaptive icon gives its themed
 * MONOCHROME layer when it has one (Android 13+), else its foreground (cropped to the 72/108 safe zone) — tinting the
 * whole adaptive icon would only paint its opaque background as a blob. A legacy icon is used as is.
 */
internal fun monochromeGlyph(d: android.graphics.drawable.Drawable, px: Int = 48): android.graphics.Bitmap {
    val layer: android.graphics.drawable.Drawable? =
        if (android.os.Build.VERSION.SDK_INT >= 26 && d is android.graphics.drawable.AdaptiveIconDrawable) {
            (if (android.os.Build.VERSION.SDK_INT >= 33) d.monochrome else null) ?: d.foreground
        } else null
    if (layer == null) return d.toBitmap(px, px)
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val inset = px / 4  // 18/72 of the visible edge on each side of a 108dp layer
    layer.setBounds(-inset, -inset, px + inset, px + inset)
    layer.draw(android.graphics.Canvas(bmp))
    return bmp
}

/** The sender's avatar the listener stored for a phone row, drawn as a 40dp circle on the right of the body. */
@Composable
private fun RowAvatar(r: NotifRow, rid: String) {
    if (!r.id.startsWith(AggregatorModel.PHONE_NS)) return
    val ctx = LocalContext.current
    val key = r.id.removePrefix(AggregatorModel.PHONE_NS)
    var img by remember(key) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(key) {
        img = withContext(Dispatchers.IO) {
            runCatching {
                val f = com.diegonmarcos.superapp.notificationcenter.NotifAvatars.file(ctx, key)
                if (f.isFile) android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() else null
            }.getOrNull()
        }
    }
    val b = img ?: return
    Image(b, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier.padding(start = 10.dp).size(NotifCard.avatar).clip(CircleShape).testTag(AggTags.rowAvatar(rid)))
}

// ── rows ─────────────────────────────────────────────────────────────

/**
 * One notification inside its app container: 15sp semibold title (with its own time once the group is open), 14sp
 * #B0B0B5 body, two lines collapsed. Unread is a small dot in the app's tint before the title; a read title dims.
 *
 * Swipe LEFT is One UI's dismiss: the item slides out and is filed into the Archive (inside the Archive any swipe
 * restores it). Swipe RIGHT toggles read/unread and springs back. Vertical scrolling is preserved because detectHorizontalDragGestures claims a drag only once it is
 * clearly sideways past the touch slop. Tap opens the app; long-press opens the sheet (Mark read/unread, Archive, Open).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotifRowItem(
    state: AggregatorState,
    r: NotifRow,
    launchPackage: String,
    tint: Color,
    expanded: Boolean,
    ownTime: Boolean,
    inArchive: Boolean = false,
) {
    val p = LocalKitPalette.current
    val ctx = LocalContext.current
    val hostView = LocalView.current
    val scope = rememberCoroutineScope()
    val unread = state.isUnread(r)
    val offset = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var width by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val rid = r.id.ifBlank { r.title }
    val openApp: (() -> Unit)? = if (launchPackage.isBlank()) null else ({
        runCatching { ctx.packageManager.getLaunchIntentForPackage(launchPackage)?.let { ctx.startActivity(it) } }
        Unit
    })
    val swipe = if (r.id.isBlank()) Modifier else Modifier.pointerInput(r.id, inArchive) {
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                val far = abs(total) > max(64.dp.toPx(), width * 0.22f)
                scope.launch {
                    if (!far) { settle(offset, fade); return@launch }
                    if (total > 0 && !inArchive) {
                        Haptics.tap(hostView)
                        state.markRead(r.id, state.isUnread(r))
                        settle(offset, fade); return@launch
                    }
                    val out = (if (total > 0) 1f else -1f) * width.coerceAtLeast(1)
                    offset.animateTo(out, tween(160)); fade.snapTo(0f)
                    state.archiveRow(r.id, !inArchive)
                }
            },
            onDragCancel = { scope.launch { settle(offset, fade) } },
            onHorizontalDrag = { change, dx ->
                change.consume()
                total += dx
                scope.launch {
                    offset.snapTo(total)
                    fade.snapTo(1f - (abs(total) / (width.coerceAtLeast(1) * 2f)).coerceIn(0f, 0.55f))
                }
            },
        )
    }
    val titleColor = when {
        r.severity == "error" -> p.bad
        r.severity == "warn" -> p.warn
        unread -> NotifCard.title
        else -> NotifCard.title.copy(alpha = 0.72f)
    }
    Box {
        Column(
            Modifier.fillMaxWidth().onSizeChanged { width = it.width }
                .graphicsLayer { translationX = offset.value; alpha = fade.value }
                .then(swipe)
                .combinedClickable(
                    onClick = { openApp?.invoke() },
                    onLongClick = { Haptics.tap(hostView); menu = true },
                )
                .padding(start = NotifCard.pad, end = NotifCard.pad, top = 8.dp, bottom = 8.dp)
                .testTag(AggTags.row(rid)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (unread) Box(Modifier.padding(end = 6.dp).size(6.dp).clip(CircleShape).background(tint)
                    .testTag(AggTags.rowUnread(rid)))
                Text(r.title, color = titleColor, fontSize = NotifCard.titleSp, fontWeight = FontWeight.SemiBold,
                    fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (ownTime) Text(AggregatorModel.shortAgo(System.currentTimeMillis() - r.ts), color = NotifCard.meta,
                    fontSize = NotifCard.metaSp, fontFamily = NotifCard.font, modifier = Modifier.padding(start = 8.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                if (r.text.isNotBlank()) Text(r.text, color = NotifCard.body, fontSize = NotifCard.bodySp, lineHeight = 19.sp,
                    fontFamily = NotifCard.font, maxLines = if (expanded) 8 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 2.dp))
                else Box(Modifier.weight(1f))
                RowAvatar(r, rid)
            }
        }
        NotifSheet(menu, { menu = false }) {
            if (!inArchive) SheetItem(if (unread) AggregatorModel.MARK_READ_LABEL else "Mark unread", AggTags.rowRead(rid)) {
                menu = false; state.markRead(r.id, unread)
            }
            if (r.id.isNotBlank()) SheetItem(
                if (inArchive) AggregatorModel.ARCHIVE_RESTORE_LABEL else AggregatorModel.ARCHIVE_LABEL, AggTags.rowArchive(rid),
            ) { menu = false; state.archiveRow(r.id, !inArchive) }
            if (openApp != null) SheetItem("Open app", AggTags.rowOpen(rid)) { menu = false; openApp() }
        }
    }
}

private suspend fun settle(offset: Animatable<Float, AnimationVector1D>, fade: Animatable<Float, AnimationVector1D>) {
    fade.snapTo(1f)
    offset.animateTo(0f, tween(140))
}

// ── page-level Clear ─────────────────────────────────────────────────

/**
 * The centred pill row at the bottom of the notification list, where One UI keeps "Notification settings" and "Clear":
 * here "Mark all read" and "Archive all". Below the cards, never inside the Filters row. Drawn only on a page that has a
 * notification to act on.
 */
@Composable
internal fun NotifyListActions(state: AggregatorState, always: Boolean = false) {
    val hostView = LocalView.current
    val any = remember(state.epoch, state.pollEpoch, state.ntfyCache.size) {
        state.panels.any { state.allViews(it).any { v -> v.group.rows.isNotEmpty() } }
    }
    if (!any && !always) return
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).testTag(AggTags.LIST_ACTIONS),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        OneUiPill(AggregatorModel.MARK_ALL_READ_LABEL, "Mark every notification on this page read", AggTags.MARK_ALL_READ) {
            Haptics.tap(hostView); state.markAllRead()
        }
        OneUiPill(AggregatorModel.ARCHIVE_ALL_LABEL, "Archive every notification on this page", AggTags.ARCHIVE_ALL) {
            Haptics.tap(hostView); state.archiveAll()
        }
    }
}

// ── Archive ──────────────────────────────────────────────────────────

/**
 * The page's Archive section: one collapsed line over the boxes the user filed away and the rows swiped off a box that
 * is still on the page, remembered per page like every other choice on it. Hidden entirely while it holds nothing.
 */
@Composable
internal fun ArchiveSection(state: AggregatorState, onOpen: (String) -> Unit) {
    val p = LocalKitPalette.current
    val hostView = LocalView.current
    val archived by remember {
        derivedStateOf {
            state.panels.withIndex().flatMap { (i, panel) ->
                state.allViews(panel).mapNotNull { v ->
                    when {
                        state.isArchived(v.group.key) -> Triple(i, v, false)
                        v.group.rows.any { state.isRowArchived(it) } -> Triple(i,
                            v.copy(group = v.group.copy(rows = v.group.rows.filter { state.isRowArchived(it) })), true)
                        else -> null
                    }
                }
            }
        }
    }
    if (archived.isEmpty()) return
    // #1672 on the Notify sub-pages Archived is an entry with the same two layouts as the feeds.
    val fixed = state.panels.any { it.kind == "notification_center" && it.split.isNotBlank() }
    val label = if (fixed) com.diegonmarcos.superapp.notificationcenter.NotifySections.blockLabel("archived", "Archived") else "Archive"
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("$label  ${if (state.archiveOpen) "▾" else "▸"}  ${archived.size}", color = p.textPrimary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().alpha(0.55f)
                .clickable { Haptics.tap(hostView); state.toggleArchiveOpen() }
                .padding(start = 14.dp, top = 4.dp, end = 14.dp, bottom = 6.dp).testTag(AggTags.ARCHIVE_HEADER))
        if (state.archiveOpen) Column(Modifier.fillMaxWidth().testTag(AggTags.ARCHIVE_BODY)) {
            // #1672 the same layout as the two notification blocks: one box per app, or one timeline by time.
            if (fixed) GroupModeSwitch(state, AggregatorModel.FEED_ARCHIVED)
            if (fixed && state.groupMode(AggregatorModel.FEED_ARCHIVED) == AggregatorModel.GROUP_TIME) {
                val rowsOnly = archived.filter { it.third }.flatMap { t -> t.second.group.rows.map { it.id } }.toSet()
                Timeline(state, AggregatorModel.FEED_ARCHIVED, archived.map { it.second.group }, rowsOnly, onOpen)
            } else for ((i, v, rowsOnly) in archived) androidx.compose.runtime.key("$i/${v.group.key}/$rowsOnly") {
                GroupBlock(state, v, archived = true, rowArchive = rowsOnly, onOpen = onOpen)
            }
        }
    }
}

/** The pull-down outcome line: ONE line, replaced by each refresh rather than appended (a note per pull would push the
 *  notifications the pull fetched off the top of the page). */
@Composable
internal fun RefreshNote(state: AggregatorState) {
    val (text, tone) = state.refreshNote ?: return
    Text(text, color = toneColor(LocalKitPalette.current, tone), fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp).testTag(AggTags.REFRESH_NOTE))
}

// ── Inbox cards: the summary, then what actually arrived ─────────────

/** The value a kind shows when nothing has been asked on its behalf — never "0", which is a measurement. */
internal const val NO_SOURCE = "no source"

/**
 * Between a card's summary and its list: how the notifications on this page divide by KIND. The kinds are the CENTRAL
 * classification's own folders (ui.phone_folders), and a kind with no source says "no source", never "0": a confident
 * zero beside a kind that was never queried is indistinguishable from a quiet inbox.
 */
@Composable
private fun KindSummary(state: AggregatorState) {
    val ctx = LocalContext.current
    AggShadeLabel("BY KIND")
    if (PhoneTaxonomy.folders.isEmpty()) {
        AggStateLine("$NO_SOURCE · classification unavailable", Tone.UNKNOWN)
        AggCaption("ui.phone_folders did not reach this build, so there are no kinds to divide the feed into. " +
            "Nothing here is empty; it is unasked.")
        return
    }
    val granted = remember(state.epoch) { AggregatorModel.isNotificationAccessGranted(ctx) }
    val counted = remember(state.epoch, granted) {
        if (!granted) emptyMap<String, Int>() else {
            val feed = state.phoneFeed()
            PhoneTaxonomy.prime(ctx, feed.associate { it.packageName to it.appLabel })
            feed.groupingBy { PhoneTaxonomy.folderIdOf(it.packageName, it.appLabel, ctx) }.eachCount()
        }
    }
    // The kinds this page is ABOUT stay listed even at zero; every other kind only when the feed carries it.
    val declared = state.panels.mapNotNull { pp -> AggregatorModel.inboxApp(pp)?.let { AggregatorModel.folderOfApp(ctx, it) } }
        .filter { it.isNotEmpty() }
    val ids = PhoneTaxonomy.folders.map { it.id }
        .filter { AggregatorModel.isSectionFolder(it) && (counted[it] != null || it in declared) }
    for (id in ids) {
        AggKindRow(AggregatorModel.kindLabel(id), if (granted) (counted[id] ?: 0).toString() else NO_SOURCE, granted)
    }
    // Notifications the classification could not place: the size of the gap in ui.phone_folders, reported rather than
    // folded into the counts above.
    val unplaced = counted.filterKeys { it.isNotEmpty() && !AggregatorModel.isSectionFolder(it) }.values.sum()
    if (unplaced > 0) AggKindRow("Unclassified", unplaced.toString(), true)
    if (!granted) {
        AggCaption("Notification Access is off, so no kind above has been queried. Those are not zeroes — grant " +
            "access and they become counts.")
    } else if (ids.isEmpty() && unplaced == 0) {
        AggStateLine("silent · the phone feed is empty", Tone.WARN)
    }
    AggCaption("Counted from the phone notification listener. The cloud stream is not counted here and cannot be: " +
        "ui.ntfy.taxon classifies a publisher by SECTION prefix, never by folder, so a cloud message has no kind to be " +
        "counted under. Notify ▸ Inboxes is where that stream is shown.")
}

/** An inbox card keeps its summary and draws its app's own notification boxes underneath: counts say how much is in
 *  there, the boxes say what arrived, and the two questions are asked in the same place. */
@Composable
internal fun InboxNotifications(state: AggregatorState, panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    KindSummary(state)
    val app = AggregatorModel.inboxApp(panel)
    if (app == null) {
        // Nothing is drawn, on purpose: a card whose inbox declares no app is a gap in build.json, and the person
        // holding the phone cannot close it. Only the inbox kinds are logged — `stats` is a generic dashboard card.
        if (panel.kind in AggregatorModel.INBOX_KINDS) LaunchedEffect(panel) {
            android.util.Log.w(TAG, "inbox panel '${panel.title}' (kind=${panel.kind}) declares no extapp: target, " +
                "so no notifications can be shown under it")
        }
        return
    }
    val folderId = remember(panel, state.panels) { AggregatorModel.inboxFolderId(ctx, panel, state.panels) }
    AggShadeLabel(if (folderId.isEmpty()) "NOTIFICATIONS" else "NOTIFICATIONS · ${AggregatorModel.kindLabel(folderId)}")
    val granted = remember(state.epoch) { AggregatorModel.isNotificationAccessGranted(ctx) }
    if (!granted) {
        // Drawing an empty list here would be a failure reporting success.
        NotificationAccessOff("Notification Access is off, so nothing this app posts is captured at all. This is " +
            "empty because we cannot read it, not because the inbox is quiet.")
        return
    }
    val empty = remember(state.epoch, folderId) {
        val feed = state.phoneFeed()
        PhoneTaxonomy.prime(ctx, feed.associate { it.packageName to it.appLabel })
        AggregatorModel.inboxEntries(ctx, feed, app, folderId).isEmpty()
    }
    if (empty) {
        // Granted and empty is a real, different state: amber, naming the KIND when this card speaks for one.
        val subject = if (folderId.isEmpty()) app.label else AggregatorModel.kindLabel(folderId)
        AggStateLine("silent · nothing captured from $subject", Tone.WARN); return
    }
    // Grouped by package, not folded into one box: a hub and its forks are separate apps on the device. No pruneRead
    // here, on purpose — this is ONE app's slice of the phone namespace and pruning against a partial view is what makes
    // read rows silently reappear.
    val views by remember(panel) { derivedStateOf { state.inboxViews(panel) } }
    GroupList(state, views, onOpen)
}

// ── kind=class_inbox: one card per CHANNEL CLASS ─────────────────────

@Composable
internal fun ClassInboxBody(state: AggregatorState, panel: Sections.StackPanel, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val name = panelTitle(ctx, panel)
    val cls = InboxClasses.byId(panel.classId)
    if (cls == null) {
        // The panel names a class ui.inbox_classes does not declare: "we cannot tell", not "nothing arrived".
        LaunchedEffect(panel) {
            android.util.Log.w(TAG, "class_inbox panel '${panel.title}' names class '${panel.classId}', which " +
                "build.json::ui.inbox_classes does not declare")
        }
        AggStateLine(stringResource(R.string.inbox_class_undeclared), Tone.UNKNOWN); return
    }
    // The catch-all is what stops this page losing a notification, so its absence is reported on every card.
    if (InboxClasses.catchAll == null) AggCaption(stringResource(R.string.inbox_class_no_catch_all))
    val granted = remember(state.epoch) { AggregatorModel.isNotificationAccessGranted(ctx) }
    if (!granted) {
        NotificationAccessOff("Notification Access is off, so nothing this class carries is captured at all. This is " +
            "empty because we cannot read it, not because the inbox is quiet.")
        return
    }
    val groups by remember(panel) { derivedStateOf { state.classGroups(panel) } }
    ClassSummary(cls, name, groups, state.ntfyTopics(panel).size)
    val note = remember(cls) { stringByName(ctx, cls.noteRes) }
    if (note.isNotBlank()) AggCaption(note)
    AggShadeLabel(stringResource(R.string.inbox_notifications_header, name))
    // A class whose material is the ntfy CHANNEL stream reuses the panel renderer the C3 Ntfy card already had; a class
    // may declare both, and RSS does: the same messages arrive by two roads and belong on one card.
    if (cls.source == AggregatorModel.C3NTFY_SOURCE) NtfyGroups(state, panel, onOpen)
    if (groups.isNotEmpty()) {
        GroupList(state, state.classViews(panel), onOpen)
    } else if (cls.source != AggregatorModel.C3NTFY_SOURCE) {
        // Granted and empty is a real, different state from unavailable, and the line names WHICH class came up empty.
        AggStateLine(stringResource(R.string.inbox_class_silent, name), Tone.WARN)
    }
}

/** The counted summary above a class card: counts from the same feed the list below is taken from — how many apps or
 *  channels the class holds, how many notifications, and how long ago the newest arrived. No model call. */
@Composable
private fun ClassSummary(cls: InboxClasses.InboxClass, name: String, groups: List<NotifGroup>, channels: Int) {
    AggShadeLabel(stringResource(R.string.inbox_summary_header, name))
    val notifications = groups.sumOf { it.rows.size }
    val newest = groups.maxOfOrNull { it.newest } ?: 0L
    // A c3ntfy class counts CHANNELS as well as apps, and the two are different things: channels are ntfy topics reached
    // over the cloud, apps are packages on this phone.
    if (cls.source == AggregatorModel.C3NTFY_SOURCE) {
        AggKindRow(stringResource(R.string.inbox_summary_channels), channels.toString(), true)
    }
    // Suppressed at zero ONLY for a channel class, where "0 apps" is noise beside the channel count.
    if (cls.source != AggregatorModel.C3NTFY_SOURCE || groups.isNotEmpty()) {
        AggKindRow(stringResource(R.string.inbox_summary_apps), groups.size.toString(), true)
    }
    AggKindRow(stringResource(R.string.inbox_summary_notifications), notifications.toString(), true)
    AggKindRow(stringResource(R.string.inbox_summary_latest),
        if (newest > 0L) AggregatorModel.ago(System.currentTimeMillis() - newest)
        else stringResource(R.string.inbox_summary_latest_none), newest > 0L)
    AggCaption(stringResource(R.string.inbox_summary_counted))
    // The channel boxes fetch their own messages after the card is drawn, so a zero there would be a measurement nobody took.
    if (cls.source == AggregatorModel.C3NTFY_SOURCE) AggCaption(stringResource(R.string.inbox_summary_channels_note))
}

internal const val TAG_GRANT = "agg:grant-access"
