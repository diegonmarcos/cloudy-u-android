package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.notificationcenter.BadgeCardData
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.AppSplit
import com.diegonmarcos.superapp.notificationcenter.AppSwitches
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.notificationcenter.NotifySections
import com.diegonmarcos.superapp.notificationcenter.NotifyTitle
import com.diegonmarcos.superapp.notificationcenter.NotifyGroups
import com.diegonmarcos.superapp.ui.Haptics

/**
 * #1068 — the top of the Notify page: every declared badge as a One UI live / ongoing card, in the order build.json
 * ranks them (`card.rank` in `ui.notification_center.producers`; the shade's own group order is untouched). One list, one source: [BadgeDeclaration.badges] over [BadgeServices.declared]; a card exists for
 * every entry, drawn from [BadgeCardData] (the badge providers' real data), so a badge added to build.json is a card
 * here with no edit. No control lives on a card except the actions the badge's own notification carries (BadgeModel: every one, as an icon button): the switches, Launch and customisation are in the page's Settings
 * section at the very bottom.
 */
internal object BadgeCardTags {
    const val LIST = "badge-cards"
    /** The "City - Sun 08.11.2026 - 12:17" line on top of the page. */
    const val TITLE = "badge-title"
    /** One Cloudy / Phone sub-group of an app section. */
    fun sub(sectionId: String, subId: String) = "badge-sub:$sectionId:$subId"
    fun appSwitch(sectionId: String, pkg: String) = "badge-app:$sectionId:$pkg"
    fun card(id: String) = "badge-card:$id"
    /** The subtitle of a section (a shade group id). */
    fun section(groupId: String) = "badge-section:$groupId"
    fun progress(id: String) = "badge-card-progress:$id"
    /** One action button of a card (an icon button); [actionId] is the id the badge's own notification action carries. */
    fun action(id: String, actionId: String) = "badge-card-action:$id:$actionId"
    fun actions(id: String) = "badge-card-actions:$id"
    /** The sub-text line (the notification's sub text). */
    fun subText(id: String) = "badge-card-subtext:$id"
    /** #1672 the collapse / expand control of a card, and the one-line collapsed body. */
    fun toggle(id: String) = "badge-card-toggle:$id"
    fun collapsed(id: String) = "badge-card-collapsed:$id"
    fun expanded(id: String) = "badge-card-expanded:$id"
    /** #1672 one ongoing line of a badge in the All page's summary. */
    fun summary(id: String) = "badge-summary:$id"
    const val SUMMARY = "badge-summary"
    /** #1672 the Media badge's MediaHub session line. */
    fun mediaHub(id: String) = "badge-media-hub:$id"
}

/**
 * #1672 every badge card has a COLLAPSED view (one line: icon, name, key value) and an EXPANDED one (everything). The
 * choice is the owner's, per badge, and survives the launcher restarts (committed, not applied). Default: expanded,
 * which is what the page drew before #1672.
 */
internal object BadgeCollapse {
    private const val PREFS = "notify_badge_collapse"
    private fun p(ctx: android.content.Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    fun isCollapsed(ctx: android.content.Context, id: String): Boolean =
        runCatching { p(ctx).getBoolean(id, false) }.getOrDefault(false)
    fun set(ctx: android.content.Context, id: String, collapsed: Boolean) {
        runCatching { p(ctx).edit().putBoolean(id, collapsed).commit() }
    }
}

/** One section of the Notify page: a shade group (build.json `groups`) and its badges, in the group's member order. */
internal data class BadgeSection(val id: String, val title: String, val badges: List<BadgeDeclaration.Badge>)

/**
 * The page's sections: the SAME groups the shade bundles badges into ([NotifyGroups.ordered], the owner's order, default =
 * build.json order), each with its members in declared order. A badge in no group goes last under no title. One list
 * decides both the shade grouping and the page, so they cannot disagree.
 */
internal fun badgeSections(ctx: android.content.Context): List<BadgeSection> {
    val all = BadgeDeclaration.badges(BadgeServices.declared)
    val byId = all.associateBy { it.id }
    val seen = mutableSetOf<String>()
    val out = mutableListOf<BadgeSection>()
    // #1672 the PAGE order (Media, Me Infos, Device Infos, Actions) is build.json's page_order, not the shade order the
    // owner may have moved: the page must read the same on every phone. A group page_order omits still comes, after.
    val shade = NotifyGroups.ordered(ctx)
    val groups = NotifySections.pageGroupOrder(NotifySections.pageOrder, shade.map { it.id })
        .mapNotNull { id -> shade.firstOrNull { it.id == id } }
    for (g in groups) {
        val m = BadgeDeclaration.rankOrder(all, g.members.mapNotNull { byId[it] }).filter { seen.add(it.id) }
        if (m.isNotEmpty()) out += BadgeSection(g.id, g.label, m)
    }
    val rest = BadgeDeclaration.rankOrder(all, all.filter { it.id !in seen })
    if (rest.isNotEmpty()) out += BadgeSection("", "", rest)
    return out
}

/** The declared badges in display order: section by section. */
internal fun orderedBadges(ctx: android.content.Context): List<BadgeDeclaration.Badge> =
    badgeSections(ctx).flatMap { it.badges }

@Composable
internal fun BadgeCards(summary: Boolean = false) {
    val ctx = LocalContext.current
    val sections = remember { badgeSections(ctx).map { s -> s to s.badges.map { BadgeCardData.read(ctx, it) } } }
    val city = remember { NotifyTitle.cityOf(ctx) }
    val title by androidx.compose.runtime.produceState(NotifyTitle.format(city, System.currentTimeMillis())) {
        while (true) {
            value = NotifyTitle.format(NotifyTitle.cityOf(ctx), System.currentTimeMillis())
            kotlinx.coroutines.delay(com.diegonmarcos.superapp.system.LowPowerMode.scaled(30_000L)) // #1687
        }
    }
    Column(Modifier.fillMaxWidth().testTag(BadgeCardTags.LIST), verticalArrangement = Arrangement.spacedBy(NotifCard.gap)) {
        // "City - Sun 08.11.2026 - 12:17": the city is the Weather badge's resolved place.
        Text(title, color = NotifCard.title, fontSize = NotifCard.titleSp, fontWeight = FontWeight.SemiBold,
            fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp).testTag(BadgeCardTags.TITLE))
        if (summary) {
            // #1672 the All page: the persistent badges as Android shows ongoing items - one line each, on top, in
            // section order; the full cards are the Persistent Badges sub-page.
            Column(Modifier.fillMaxWidth().clip(NotifCard.shape).background(NotifCard.surface).testTag(BadgeCardTags.SUMMARY)) {
                for ((i, d) in sections.flatMap { it.second }.withIndex()) androidx.compose.runtime.key(d.badge.id) {
                    if (i > 0) Box(Modifier.padding(horizontal = NotifCard.pad).fillMaxWidth().height(0.7.dp).background(NotifCard.divider))
                    SummaryBadgeLine(d)
                }
            }
            Box(Modifier.height(NotifCard.gap))
            return@Column
        }
        // #1672 only the four badge sections are drawn here; the app lists of `app_sections` (Cloud / Phone Publisher,
        // Apps Keep Alive) moved into the page's Configs, and the notifications themselves follow as page blocks.
        for ((s, cards) in sections) androidx.compose.runtime.key(s.id) {
            // A plain subtitle, not a control: sections do not collapse; each CARD does (#1672).
            if (s.title.isNotBlank()) SectionHeader(s.title, BadgeCardTags.section(s.id))
            for (d in cards) androidx.compose.runtime.key(d.badge.id) { BadgeCard(d) }
        }
        Box(Modifier.height(NotifCard.gap))
    }
}

@Composable
private fun SectionHeader(text: String, tag: String) =
    Text(text, color = NotifCard.meta, fontSize = NotifCard.nameSp, fontWeight = FontWeight.Medium,
        fontFamily = NotifCard.font, maxLines = 1, modifier = Modifier.padding(start = 4.dp, top = 6.dp).testTag(tag))

/**
 * Cloud Publisher / Phone Publisher / Apps Keep Alive (#1603): its own header, then its app rows — READ-ONLY on the
 * viewer (#1603a: the Notify page the user reads carries NO switches on any card or section). A push section carries a
 * `filter` (cloudy | phone) and draws the one matching AppSplit side as a flat list — no inner sub-header, the section
 * label already names the side. The keep-alive section has no filter and keeps its two labelled sub-groups (Cloudy,
 * Phone). The owner's per-app on/off lives in Settings' flat declared-badge list (BadgePanes), never here.
 */
@Composable
internal fun AppSectionBlock(sec: NotifySections.AppSection) {
    val ctx = LocalContext.current
    val apps = remember { AppSwitches.knownApps(ctx) }
    val (cloudy, phone) = remember(apps) { AppSplit.split(apps) }
    SectionHeader(sec.label, BadgeCardTags.section(sec.id))
    // A filtered push section is ONE flat block of its side; otherwise the declared sub-groups, each a block.
    val blocks: List<Triple<String, String?, List<Pair<String, String>>>> =
        if (sec.filter.isNotBlank())
            listOf(Triple(sec.filter, null, if (sec.filter == AppSplit.CLOUDY) cloudy else phone))
        else sec.subs.map { Triple(it.id, it.label, if (it.id == AppSplit.CLOUDY) cloudy else phone) }
    for ((subId, subLabel, rows) in blocks) {
        Column(Modifier.fillMaxWidth().clip(NotifCard.shape).background(NotifCard.surface)
            .padding(horizontal = NotifCard.pad, vertical = 10.dp).testTag(BadgeCardTags.sub(sec.id, subId))) {
            if (subLabel != null) Text(subLabel, color = NotifCard.meta, fontSize = NotifCard.nameSp, fontWeight = FontWeight.Medium, fontFamily = NotifCard.font)
            if (rows.isEmpty()) Text("No apps yet", color = NotifCard.body, fontSize = NotifCard.bodySp, fontFamily = NotifCard.font,
                modifier = Modifier.padding(top = 4.dp))
            // Read-only: the app name only. No Switch, no mutable state — the viewer shows WHAT the shade groups, it does not control it.
            for ((pkg, label) in rows) androidx.compose.runtime.key(pkg) {
                Text(label, color = NotifCard.title, fontSize = NotifCard.bodySp, fontFamily = NotifCard.font, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

/** #1543 the red of a badge line flagged alert (the AI badge's "runs out before reset"). */
private val BADGE_ALERT = Color(0xFFFF5252)

private fun accentOf(d: BadgeCardData.Data): Color =
    runCatching { Color(android.graphics.Color.parseColor(d.badge.card.accent)) }.getOrDefault(Color(0xFF8AB4F8))

@Composable
internal fun BadgeCard(d: BadgeCardData.Data) {
    val ctx = LocalContext.current
    val hostView = androidx.compose.ui.platform.LocalView.current
    val accent = accentOf(d)
    val icon = remember(d.badge.icon) { Sections.iconResFor(ctx, d.badge.icon) }
    val tap = d.open
    var collapsed by androidx.compose.runtime.remember(d.badge.id) {
        androidx.compose.runtime.mutableStateOf(BadgeCollapse.isCollapsed(ctx, d.badge.id))
    }
    val flip: () -> Unit = {
        Haptics.tap(hostView); collapsed = !collapsed; BadgeCollapse.set(ctx, d.badge.id, collapsed)
    }
    if (collapsed) { CollapsedBadgeCard(d, accent, icon, flip); return }
    // #1603 Samsung-UI media card: the album art, dimmed, fills the card behind the transport; a scrim keeps the text
    // readable. Only for the media kind, and only when the posted notification carried art — otherwise a plain surface.
    val art = if (d.badge.card.kind == "media") d.albumArt?.asImageBitmap() else null
    Box(
        Modifier.fillMaxWidth().clip(NotifCard.shape).background(if (art != null) Color(0xFF15151A) else NotifCard.surface)
            .then(if (tap != null) Modifier.clickable {
                Haptics.tap(hostView); runCatching { tap.send() }
            } else Modifier)
            .testTag(BadgeCardTags.card(d.badge.id)),
    ) {
        if (art != null) {
            androidx.compose.foundation.Image(art, null, Modifier.matchParentSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop, alpha = 0.45f)
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.38f)))
        }
        Column(Modifier.fillMaxWidth().padding(start = NotifCard.pad, end = NotifCard.pad, top = 12.dp, bottom = 14.dp)
            .testTag(BadgeCardTags.expanded(d.badge.id))) {
        // The app-name row of a One UI notification: small tinted icon, name, state, no controls.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), null, tint = accent, modifier = Modifier.size(NotifCard.headerIcon))
            Text(d.appName, color = NotifCard.title, fontSize = NotifCard.nameSp, fontWeight = FontWeight.Medium,
                fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp))
            Text(" • " + (if (d.live) "live" else "not posted"), color = NotifCard.meta, fontSize = NotifCard.metaSp,
                fontFamily = NotifCard.font, maxLines = 1)
            Box(Modifier.weight(1f))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (d.live) accent else NotifCard.pill))
            CollapseChevron(d.badge.id, expanded = true, onClick = flip)
        }
        // The live value, large; the supporting line under it.
        Text(d.headline, color = NotifCard.title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
        val sub = d.sub.ifBlank { d.note }
        if (sub.isNotBlank()) Text(sub, color = NotifCard.body, fontSize = NotifCard.bodySp, fontFamily = NotifCard.font,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        d.progress?.let { p ->
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(NotifCard.pill)
                .testTag(BadgeCardTags.progress(d.badge.id))) {
                Box(Modifier.fillMaxWidth(p.coerceIn(0.02f, 1f)).height(6.dp).clip(CircleShape).background(accent))
            }
        }
        // The notification's sub text: the info line a badge such as KDE carries under its text. A bare "Cloud SA - X"
        // is only the app name the header already shows.
        if (d.subText.isNotBlank() && !d.subText.startsWith("Cloud SA - ")) Text(d.subText, color = NotifCard.meta,
            fontSize = NotifCard.metaSp, fontFamily = NotifCard.font, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp).testTag(BadgeCardTags.subText(d.badge.id)))
        if (d.badge.card.kind == "quotes") {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (l in d.lines) Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(NotifCard.pill)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(l.label, color = NotifCard.meta, fontSize = NotifCard.metaSp, fontFamily = NotifCard.font, maxLines = 1)
                    Text(l.value, color = NotifCard.title, fontSize = NotifCard.bodySp, fontWeight = FontWeight.SemiBold,
                        fontFamily = NotifCard.font, maxLines = 1)
                }
            }
        } else for (l in d.lines) if (l.mono) {
            // #1543 a preformatted line (the AI badge's weekly block): monospaced so its columns line up, red when the
            // week runs out before its reset.
            Text(l.value, color = if (l.alert) BADGE_ALERT else NotifCard.body, fontSize = NotifCard.metaSp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 2, softWrap = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        } else Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (l.label.isNotBlank()) Text(l.label, color = NotifCard.meta, fontSize = NotifCard.bodySp,
                fontFamily = NotifCard.font, maxLines = 1, modifier = Modifier.padding(end = 10.dp))
            Text(l.value, color = if (l.alert) BADGE_ALERT else NotifCard.body, fontSize = NotifCard.bodySp, fontFamily = NotifCard.font, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (d.badge.card.kind == "media") MediaHubLine(d.badge.id)
        // #1734 the Weather badge's expanded view also carries the day timelines (the same composable the date dropdown draws).
        if (d.badge.id == com.diegonmarcos.superapp.notificationcenter.WeatherBadgeService.BADGE_ID)
            com.diegonmarcos.superapp.daytimelines.DayTimelinesCard(Modifier.padding(top = 10.dp), NotifCard.title, NotifCard.meta)
        ActionRow(d, accent, hostView)
        }
    }
}

/** #1672 one ongoing line: tinted icon, name, key value, live dot. Tapping it does what the badge's notification does. */
@Composable
private fun SummaryBadgeLine(d: BadgeCardData.Data) {
    val ctx = LocalContext.current
    val hostView = androidx.compose.ui.platform.LocalView.current
    val accent = accentOf(d)
    val icon = remember(d.badge.icon) { Sections.iconResFor(ctx, d.badge.icon) }
    val tap = d.open
    Row(
        Modifier.fillMaxWidth().then(if (tap != null) Modifier.clickable { Haptics.tap(hostView); runCatching { tap.send() } } else Modifier)
            .padding(start = NotifCard.pad, end = NotifCard.pad, top = 9.dp, bottom = 9.dp).testTag(BadgeCardTags.summary(d.badge.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = accent, modifier = Modifier.size(NotifCard.headerIcon))
        Text(d.appName, color = NotifCard.meta, fontSize = NotifCard.nameSp, fontFamily = NotifCard.font, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).widthIn(max = 120.dp))
        Text(d.headline.ifBlank { if (d.live) "live" else "not posted" }, color = NotifCard.title, fontSize = NotifCard.bodySp,
            fontWeight = FontWeight.Medium, fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp))
        Box(Modifier.padding(start = 6.dp).size(8.dp).clip(CircleShape).background(if (d.live) accent else NotifCard.pill))
    }
}

/** #1672 a Media badge button id -> the MediaHub action it is, or null for one MediaHub does not model (left to its intent). */
internal fun mediaActionOf(id: String): com.diegonmarcos.superapp.media.MediaHub.Action? {
    val k = id.lowercase()
    return when {
        "prev" in k || "back" in k -> com.diegonmarcos.superapp.media.MediaHub.Action.PREV
        "next" in k || "skip" in k || "forward" in k -> com.diegonmarcos.superapp.media.MediaHub.Action.NEXT
        "pause" in k && "play" in k -> com.diegonmarcos.superapp.media.MediaHub.Action.PLAY_PAUSE
        "pause" in k -> com.diegonmarcos.superapp.media.MediaHub.Action.PAUSE
        "play" in k -> com.diegonmarcos.superapp.media.MediaHub.Action.PLAY
        else -> null
    }
}

/** #1672 the Media badge's live line from [com.diegonmarcos.superapp.media.MediaHub.state]: the session the island and the
 *  badge both control, or the reason nothing can be controlled. Collected, not polled. */
@Composable
private fun MediaHubLine(id: String) {
    val st by com.diegonmarcos.superapp.media.MediaHub.state.collectAsState()
    val t = st.target
    val text = when {
        t != null -> listOf(t.title, t.artist).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { t.pkg } +
            if (t.playing) " · playing" else " · paused"
        else -> st.reason.orEmpty()
    }
    if (text.isNotBlank()) Text(text, color = NotifCard.meta, fontSize = NotifCard.metaSp, fontFamily = NotifCard.font,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp).testTag(BadgeCardTags.mediaHub(id)))
}

/** #1672 the fold control at the end of a card's header: a chevron in a circle, like the notification groups'. */
@Composable
private fun CollapseChevron(id: String, expanded: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 8.dp).size(28.dp).clip(CircleShape).background(NotifCard.pill)
            .semantics { role = Role.Button; contentDescription = if (expanded) "Collapse" else "Expand" }
            .clickable(onClick = onClick).testTag(BadgeCardTags.toggle(id)),
        contentAlignment = Alignment.Center,
    ) {
        // A text glyph: this file draws no drawable of its own (NotifyCardGuardTest).
        Text(if (expanded) "▴" else "▾", color = NotifCard.title, fontSize = 14.sp, fontFamily = NotifCard.font)
    }
}

/**
 * #1672 the COLLAPSED card: one compact line - the badge's tinted icon, its name, its key value (the headline), the live
 * dot and the expand chevron. A tap anywhere on the line expands it; nothing the expanded card shows is lost, it is
 * one tap away.
 */
@Composable
private fun CollapsedBadgeCard(d: BadgeCardData.Data, accent: Color, icon: Int, flip: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(NotifCard.shape).background(NotifCard.surface).clickable(onClick = flip)
            .padding(start = NotifCard.pad, end = 12.dp, top = 10.dp, bottom = 10.dp)
            .testTag(BadgeCardTags.card(d.badge.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = accent, modifier = Modifier.size(NotifCard.headerIcon))
        Text(d.appName, color = NotifCard.meta, fontSize = NotifCard.nameSp, fontWeight = FontWeight.Medium,
            fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).widthIn(max = 120.dp))
        Text(d.headline.ifBlank { if (d.live) "live" else "not posted" }, color = NotifCard.title, fontSize = NotifCard.bodySp,
            fontWeight = FontWeight.SemiBold, fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp).testTag(BadgeCardTags.collapsed(d.badge.id)))
        Box(Modifier.padding(start = 6.dp).size(8.dp).clip(CircleShape).background(if (d.live) accent else NotifCard.pill))
        CollapseChevron(d.badge.id, expanded = false, onClick = flip)
    }
}

/**
 * Every action the badge's notification carries, as a tappable icon button (icon, label under it, same PendingIntent).
 * The list is [BadgeCardData.Data.actions], read from the notification itself: this composable has no list of its own.
 */
@Composable
private fun ActionRow(d: BadgeCardData.Data, accent: Color, hostView: android.view.View) {
    if (d.actions.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).testTag(BadgeCardTags.actions(d.badge.id)),
        horizontalArrangement = Arrangement.SpaceEvenly) {
        for (a in d.actions) Column(
            Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = a.label }
                .clickable {
                    Haptics.tap(hostView)
                    // #1672 the Media badge drives the SAME session as the Media Island: MediaHub picks the target at tap
                    // time (#1648 Tidal / Spotify rule) and falls back to a media key; it never throws.
                    val m = if (d.badge.card.kind == "media") mediaActionOf(a.id) else null
                    if (m != null) runCatching { com.diegonmarcos.superapp.media.MediaHub.send(hostView.context, m) }
                    else runCatching { a.intent.send() }
                }
                .padding(vertical = 4.dp)
                .testTag(BadgeCardTags.action(d.badge.id, a.id)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(a.icon), null, tint = accent, modifier = Modifier.size(22.dp))
            }
            Text(a.label, color = NotifCard.meta, fontSize = NotifCard.metaSp, fontFamily = NotifCard.font,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}
