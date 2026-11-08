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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    for (g in NotifyGroups.ordered(ctx)) {
        val m = g.members.mapNotNull { byId[it] }.filter { seen.add(it.id) }
        if (m.isNotEmpty()) out += BadgeSection(g.id, g.label, m)
    }
    val rest = all.filter { it.id !in seen }
    if (rest.isNotEmpty()) out += BadgeSection("", "", rest)
    return out
}

/** The declared badges in display order: section by section. */
internal fun orderedBadges(ctx: android.content.Context): List<BadgeDeclaration.Badge> =
    badgeSections(ctx).flatMap { it.badges }

@Composable
internal fun BadgeCards() {
    val ctx = LocalContext.current
    val sections = remember { badgeSections(ctx).map { s -> s to s.badges.map { BadgeCardData.read(ctx, it) } } }
    val apps = remember { NotifySections.appSections }
    val layout = remember {
        NotifySections.layout(NotifySections.pageOrder, sections.map { it.first.id }, apps.map { it.id })
    }
    val city = remember { NotifyTitle.cityOf(ctx) }
    val title by androidx.compose.runtime.produceState(NotifyTitle.format(city, System.currentTimeMillis())) {
        while (true) {
            value = NotifyTitle.format(NotifyTitle.cityOf(ctx), System.currentTimeMillis())
            kotlinx.coroutines.delay(30_000L)
        }
    }
    Column(Modifier.fillMaxWidth().testTag(BadgeCardTags.LIST), verticalArrangement = Arrangement.spacedBy(NotifCard.gap)) {
        // "City - Sun 08.11.2026 - 12:17": the city is the Weather badge's resolved place.
        Text(title, color = NotifCard.title, fontSize = NotifCard.titleSp, fontWeight = FontWeight.SemiBold,
            fontFamily = NotifCard.font, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp).testTag(BadgeCardTags.TITLE))
        for (id in layout) androidx.compose.runtime.key(id) {
            val app = apps.firstOrNull { it.id == id }
            val sec = sections.firstOrNull { it.first.id == id }
            if (app != null) AppSectionBlock(app)
            else if (sec != null) {
                val (s, cards) = sec
                // A plain subtitle, not a control: sections do not collapse.
                if (s.title.isNotBlank()) SectionHeader(s.title, BadgeCardTags.section(s.id))
                for (d in cards) androidx.compose.runtime.key(d.badge.id) { BadgeCard(d) }
            }
        }
        Box(Modifier.height(NotifCard.gap))
    }
}

@Composable
private fun SectionHeader(text: String, tag: String) =
    Text(text, color = NotifCard.meta, fontSize = NotifCard.nameSp, fontWeight = FontWeight.Medium,
        fontFamily = NotifCard.font, maxLines = 1, modifier = Modifier.padding(start = 4.dp, top = 6.dp).testTag(tag))

/**
 * Apps Push Notifications / Apps Keep Alive: its own header, then two labelled sub-groups (Cloudy, Phone) of app rows with a
 * switch each. Push = the app may land in the notification history; Keep Alive = the owner's keep-alive list.
 */
@Composable
private fun AppSectionBlock(sec: NotifySections.AppSection) {
    val ctx = LocalContext.current
    val apps = remember { AppSwitches.knownApps(ctx) }
    val (cloudy, phone) = remember(apps) { AppSplit.split(apps) }
    SectionHeader(sec.label, BadgeCardTags.section(sec.id))
    for (sub in sec.subs) {
        val rows = if (sub.id == AppSplit.CLOUDY) cloudy else phone
        Column(Modifier.fillMaxWidth().clip(NotifCard.shape).background(NotifCard.surface)
            .padding(horizontal = NotifCard.pad, vertical = 10.dp).testTag(BadgeCardTags.sub(sec.id, sub.id))) {
            Text(sub.label, color = NotifCard.meta, fontSize = NotifCard.nameSp, fontWeight = FontWeight.Medium, fontFamily = NotifCard.font)
            if (rows.isEmpty()) Text("No apps yet", color = NotifCard.body, fontSize = NotifCard.bodySp, fontFamily = NotifCard.font,
                modifier = Modifier.padding(top = 4.dp))
            for ((pkg, label) in rows) androidx.compose.runtime.key(pkg) {
                var on by remember { androidx.compose.runtime.mutableStateOf(
                    if (sec.kind == "push") AppSwitches.pushAllowed(ctx, pkg) else AppSwitches.keepAlive(ctx, pkg)) }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = NotifCard.title, fontSize = NotifCard.bodySp, fontFamily = NotifCard.font, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = on, onCheckedChange = { v ->
                        on = v
                        if (sec.kind == "push") AppSwitches.setPushAllowed(ctx, pkg, v) else AppSwitches.setKeepAlive(ctx, pkg, v)
                    }, modifier = Modifier.testTag(BadgeCardTags.appSwitch(sec.id, pkg)))
                }
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
    Column(
        Modifier.fillMaxWidth().clip(NotifCard.shape).background(NotifCard.surface)
            .then(if (tap != null) Modifier.clickable {
                Haptics.tap(hostView); runCatching { tap.send() }
            } else Modifier)
            .padding(start = NotifCard.pad, end = NotifCard.pad, top = 12.dp, bottom = 14.dp)
            .testTag(BadgeCardTags.card(d.badge.id)),
    ) {
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
        ActionRow(d, accent, hostView)
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
                .clickable { Haptics.tap(hostView); runCatching { a.intent.send() } }
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
