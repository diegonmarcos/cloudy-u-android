package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** Test tags of the aggregator page: one place, so the tests and the page cannot drift apart. */
internal object AggTags {
    const val PAGE = "agg:page"
    const val SHEET = "agg:sheet"
    const val ARCHIVE_HEADER = "agg:archive-header"
    const val ARCHIVE_BODY = "agg:archive-body"
    const val REFRESH_NOTE = "agg:refresh-note"
    const val SOURCE_EMPTY = "agg:source-empty"
    const val CONFIG_SECTION = "agg:config-section"
    const val CONFIG_HEADER = "agg:config-header"
    const val CONFIG_BODY = "agg:config-body"
    const val FILTERS_TOGGLE = "agg:filters-toggle"
    const val EMPTY_PAGE = "agg:empty-page"
    fun panel(i: Int) = "agg:panel:$i"
    fun panelHeader(i: Int) = "agg:panel-header:$i"
    fun panelBody(i: Int) = "agg:panel-body:$i"
    fun filter(id: String, option: String) = "agg:filter:$id:$option"
    fun group(key: String) = "agg:group:$key"
    fun groupHeader(key: String) = "agg:group-header:$key"
    fun groupCheck(key: String) = "agg:group-check:$key"
    fun groupArchive(key: String) = "agg:group-archive:$key"
    fun groupChip(key: String) = "agg:group-chip:$key"
    fun row(id: String) = "agg:row:$id"
    fun rowUnread(id: String) = "agg:row-unread:$id"
    fun groupMore(key: String) = "agg:group-more:$key"
    fun groupChevron(key: String) = "agg:group-chevron:$key"
    fun groupMenu(key: String) = "agg:group-menu:$key"
    fun groupOpen(key: String) = "agg:group-open:$key"
    fun rowMenu(id: String) = "agg:row-menu:$id"
    fun rowRead(id: String) = "agg:row-read:$id"
    fun rowArchive(id: String) = "agg:row-archive:$id"
    fun rowOpen(id: String) = "agg:row-open:$id"
    fun rowAvatar(id: String) = "agg:row-avatar:$id"
    const val LIST_ACTIONS = "agg:list-actions"
    const val MARK_ALL_READ = "agg:mark-all-read"
    const val ARCHIVE_ALL = "agg:archive-all"
    // #1672 the Notify page blocks: the grouped-by-app / by-time switch of a feed, its one-timeline body, and the
    // notification-access prompt that opens Configs > Perms.
    fun groupMode(feed: String, mode: String) = "agg:group-mode:$feed:$mode"
    fun timeline(feed: String) = "agg:timeline:$feed"
    fun timelineRow(id: String) = "agg:timeline-row:$id"
    fun timelineRestore(id: String) = "agg:timeline-restore:$id"
    fun feed(feed: String) = "agg:feed:$feed"
    fun category(feed: String, cat: String) = "agg:category:$feed:$cat"
    const val ACCESS_PROMPT = "agg:access-prompt"
    const val OPEN_PERMS = "agg:open-perms"
    const val ARCHIVE_EMPTY = "agg:archive-empty"
    const val CONFIG_APPS = "agg:config-apps"
    const val CONFIG_ACCESS = "agg:config-access"
}

/** The state chip / verdict line colour of a [Tone]: grey means "we did not measure", never red. */
internal fun toneColor(p: KitPalette, tone: Tone): Color = when (tone) {
    Tone.OK -> p.ok
    Tone.WARN -> p.warn
    Tone.UNKNOWN -> p.textSecondary
    Tone.DIM -> p.textSecondary.copy(alpha = 0.7f)
}

@Composable
internal fun AggCaption(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalKitPalette.current.textPrimary, style = MaterialTheme.typography.labelSmall,
        modifier = modifier.fillMaxWidth().alpha(0.55f).padding(horizontal = 12.dp, vertical = 4.dp))
}

@Composable
internal fun AggEmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalKitPalette.current.textPrimary, style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.fillMaxWidth().alpha(0.6f).padding(16.dp))
}

/** A card-level verdict line, for the states that apply to a whole stream rather than one app. */
@Composable
internal fun AggStateLine(text: String, tone: Tone, modifier: Modifier = Modifier) {
    Text(text, color = toneColor(LocalKitPalette.current, tone), fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 8.dp))
}

/** The divider between two halves of one stream: small, upper-case and quiet — lighter than a group header. */
@Composable
internal fun AggShadeLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalKitPalette.current.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp, modifier = modifier.padding(start = 2.dp, top = 14.dp, bottom = 2.dp))
}

/** One kind and its number. [sourced] false greys the value: the text in it is a statement about the source. */
@Composable
internal fun AggKindRow(label: String, value: String, sourced: Boolean, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = p.textSecondary, fontSize = 13.sp)
        Text(value, color = if (sourced) p.textPrimary else p.textSecondary, fontSize = 13.sp,
            fontWeight = if (sourced) FontWeight.Bold else FontWeight.Normal)
    }
}

/** A label with its target on the right, tappable. */
@Composable
internal fun AggLinkRow(link: Sections.LinkItem, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Row(modifier.fillMaxWidth().clickable { onOpen(link.url) }.padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(link.label, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f))
        Text(link.url, color = p.textPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(0.55f))
    }
}

/** A row's background wash by severity: error and warn colour it, idle is a neutral dim, the rest the card surface. */
@Composable
internal fun severityWash(severity: String): Color {
    val p = LocalKitPalette.current
    return when (severity) {
        "error" -> p.bad.copy(alpha = 0.33f)
        "warn" -> p.warn.copy(alpha = 0.33f)
        "idle" -> p.textPrimary.copy(alpha = 0.13f)
        else -> p.surface
    }
}

/** The card name as the phone shows it: the declared string resource when the panel names one, otherwise the English word
 *  build.json carries. A resource build.json names and this build does not carry falls back to the word, never blank. */
internal fun panelTitle(ctx: android.content.Context, panel: Sections.StackPanel): String {
    if (panel.titleRes.isNotBlank()) {
        val id = ctx.resources.getIdentifier(panel.titleRes, "string", ctx.packageName)
        if (id != 0) return ctx.getString(id)
        android.util.Log.w("AggregatorStack", "panel '${panel.title}' declares title_res " +
            "'${panel.titleRes}', which this build has no string for")
    }
    return panel.title.ifBlank { panel.kind.replace('_', ' ') }
}

/** A string named in build.json, or "" when this build carries no such resource (the data may name one it lacks). */
internal fun stringByName(ctx: android.content.Context, name: String): String {
    if (name.isBlank()) return ""
    val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
    return if (id == 0) "" else ctx.getString(id)
}
