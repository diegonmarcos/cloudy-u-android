package com.diegonmarcos.superapp.launcher

import android.content.Context
import com.diegonmarcos.superapp.apps.PhoneTaxonomy
import com.diegonmarcos.superapp.cloud.GitHubFeed
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationStore
import com.diegonmarcos.superapp.rss.NtfyCatalog
import com.diegonmarcos.superapp.rss.NtfyScopes

/**
 * The data half of the aggregator page (AggregatorStackFragment): the notification groups and rows, how they sort,
 * filter and age, the ntfy poll, the inbox / class arithmetic and the feed wording. Nothing here draws or holds a View,
 * so a JVM test pins each rule directly (AggregatorModelTest) — the page itself only paints what this returns.
 */

/** One notification: [id] is the stable identity read/unread is remembered under ("" = none, falls back to arrival). */
internal data class NotifRow(
    val ts: Long,
    val title: String,
    val text: String,
    val severity: String = "info",
    val id: String = "",
)

/** One app / publisher box: its rows, plus how to open the thing it stands for ([launchPackage] or [url]). */
internal data class NotifGroup(
    val key: String,
    val label: String,
    val sub: String,
    val rows: List<NotifRow>,
    val launchPackage: String = "",
    val url: String = "",
) {
    val newest: Long get() = rows.maxOfOrNull { it.ts } ?: 0L
}

/** How loud a group's state chip is: the page maps these to palette roles. */
internal enum class Tone { OK, WARN, UNKNOWN, DIM }

/** The chip on a group's header ("3 new · 5", "silent 7d · publisher?") and its tone. */
internal data class GroupChip(val text: String, val tone: Tone)

/** A group as drawn: the data, its chip, and (for ntfy) whether it is still waiting for its first answer. */
internal data class GroupView(val group: NotifGroup, val chip: GroupChip)

/** One ntfy poll's answer for a topic. */
internal data class NtfyResult(val ok: Boolean, val error: String, val rows: List<NotifRow>)

internal object AggregatorModel {

    const val PHONE_NS = "phone:"
    const val APP_NS = "app:"
    fun ntfyNs(topic: String) = "ntfy:$topic:"

    const val FILTER_TOOLS = "tools"
    const val FILTER_SERVICES = "services"
    /** A filter whose id starts with this is a page-level SETTING written in the same JSON shape as a toggle. */
    const val SETTING_PREFIX = "__"
    const val FILTER_COLLAPSED = "__collapsed"
    const val ARCHIVED_PREFIX = "__archived/"
    const val FILTER_ARCHIVE_OPEN = "__archive_open"
    const val FILTER_CONFIG_OPEN = "__config_open"
    /** #1672 per-feed layout of a Notify notification block (Cloud Apps, Phone Apps, Archived): grouped by app, or one
     *  timeline by time. A "__" setting, so the Filters row never draws it; remembered per page and feed. */
    const val FILTER_GROUP_PREFIX = "__group_"
    const val GROUP_APP = "app"
    const val GROUP_TIME = "time"
    const val FEED_ARCHIVED = "archived"
    /** #1672 the app pages' View switch: one section per app category, or one timeline. */
    const val VIEW_CATEGORIES = "categories"
    /** #1672 the All page's split: every notification, both sides. */
    const val SPLIT_ALL = "all"
    const val INAPP_CATEGORY = "Cloud SuperApp"
    const val OTHER_CATEGORY = "Other"

    /** #1672 By Categories: one section per category, sections A-Z by name, each section's notifications newest first. */
    fun byCategory(groups: List<NotifGroup>, categoryOf: (NotifGroup) -> String): List<Pair<String, List<NotifGroup>>> =
        groups.groupBy(categoryOf).toList().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
    /** Configs > Perms, where notification access is granted (#1672: the prompt a missing grant shows opens it). */
    const val PERMS_TARGET = "page:config/perms"

    /** #1672 the by-time layout: every notification of [groups] in ONE list, newest first, each with its app. */
    fun timeline(groups: List<NotifGroup>): List<Pair<NotifGroup, NotifRow>> =
        groups.flatMap { g -> g.rows.map { g to it } }.sortedByDescending { it.second.ts }
    const val ARCHIVE_LABEL = "Archive"
    const val ARCHIVE_RESTORE_LABEL = "Restore"
    const val MARK_READ_LABEL = "Mark read"
    const val MARK_ALL_READ_LABEL = "Mark all read"
    const val ARCHIVE_ALL_LABEL = "Archive all"

    /** The line under a stacked One UI card: how many notifications sit behind the one on top. */
    fun moreLabel(hidden: Int): String = "+$hidden more"

    /** The count pill on a folded One UI notification group: "+N" behind the newest. */
    fun countPill(hidden: Int): String = "+$hidden"
    const val REFRESH_TIMEOUT_MS = 20_000L
    const val EXTAPP_PREFIX = "extapp:"
    const val C3NTFY_SOURCE = "c3ntfy"
    val INBOX_KINDS = setOf("mail_accounts", "chat_matrix", "chat_mattermost")
    /** Panel kinds whose body answers to Sort/Show and to the pull-down gesture. */
    val REFRESHABLE_KINDS = setOf(
        "mail_accounts", "chat_matrix", "chat_mattermost", "notification_center", "stats", "class_inbox",
        "board_inbox", "board_steps", "board_places", "board_timeline", "board_task_projects",
    )

    fun ago(ms: Long): String = when {
        ms < 60_000     -> "just now"
        ms < 3_600_000  -> "${ms / 60_000}m ago"
        ms < 86_400_000 -> "${ms / 3_600_000}h ago"
        else            -> "${ms / 86_400_000}d ago"
    }

    /** One UI's notification clock: "now", "5m", "3h", "2d". */
    fun shortAgo(ms: Long): String = when {
        ms < 60_000     -> "now"
        ms < 3_600_000  -> "${ms / 60_000}m"
        ms < 86_400_000 -> "${ms / 3_600_000}h"
        else            -> "${ms / 86_400_000}d"
    }

    fun filteredAwayNote(total: Int): String = "Nothing new · $total older"

    fun isNotificationAccessGranted(ctx: Context): Boolean {
        val flat = android.provider.Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners",
        ).orEmpty()
        return flat.contains(ctx.packageName)
    }

    /** Read is EXPLICIT — the user swiped this row away. A row with no stable identity has nothing to remember, so it
     *  falls back to the arrival watermark rather than pretending to be permanently unread. */
    fun isUnread(r: NotifRow, readIds: Set<String>, visitSeenAt: Long): Boolean =
        if (r.id.isBlank()) r.ts > visitSeenAt else r.id !in readIds

    /** A group's rows under the Show toggle, newest first. */
    fun withinGroup(rows: List<NotifRow>, showMode: String, readIds: Set<String>, visitSeenAt: Long): List<NotifRow> {
        val shown = if (showMode == "unread") rows.filter { isUnread(it, readIds, visitSeenAt) } else rows
        return shown.sortedByDescending { it.ts }
    }

    /** The chip of a group of phone / in-app rows: "N new · total" while something arrived since the last visit, else
     *  "total · age". Arrival, not attention. */
    fun chipFor(g: NotifGroup, visitSeenAt: Long, now: Long): GroupChip {
        val fresh = g.rows.count { it.ts > visitSeenAt }
        return if (fresh > 0) GroupChip("$fresh new · ${g.rows.size}", Tone.OK)
        else GroupChip("${g.rows.size} · ${ago(now - g.newest)}", Tone.DIM)
    }

    /** The groups worth drawing, ordered by the Sort toggle: A–Z by label, or newest first. */
    fun visibleGroups(
        groups: List<NotifGroup>, sortMode: String, showMode: String, readIds: Set<String>, visitSeenAt: Long, now: Long,
    ): List<GroupView> {
        val visible = groups
            .map { it.copy(rows = withinGroup(it.rows, showMode, readIds, visitSeenAt)) }
            .filter { it.rows.isNotEmpty() }
        val ordered =
            if (sortMode == "app")
                visible.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { g: NotifGroup -> g.label })
            else visible.sortedByDescending { it.newest }
        return ordered.map { GroupView(it, chipFor(it, visitSeenAt, now)) }
    }

    // ── ntfy ──────────────────────────────────────────────────────────

    /** The newest message of a topic whose last poll succeeded; a topic never measured sorts last. */
    fun newestMeasured(cache: Map<String, NtfyResult>, topic: String): Long =
        cache[topic]?.takeIf { it.ok }?.rows?.maxOfOrNull { it.ts } ?: Long.MIN_VALUE

    /** The channels a panel covers: its scopes of the vendored catalogue, narrowed by the taxonomy rows. */
    fun ntfyTopicsOf(panelScopes: List<String>, keeps: (String) -> Boolean): List<String> {
        val scopes = NtfyScopes.load()
        val catalog = NtfyScopes.fallbackChannels()
        return (if (panelScopes.isEmpty()) catalog else catalog.filter { NtfyScopes.scopeOf(it, scopes).id in panelScopes })
            .filter(keeps)
    }

    /** The topics in draw order: A–Z by label, or newest measured first (label breaks ties). */
    fun orderTopics(topics: List<String>, sortMode: String, cache: Map<String, NtfyResult>): List<String> {
        val byLabel = compareBy(String.CASE_INSENSITIVE_ORDER) { t: String -> NtfyCatalog.labelOf(t) }
        return if (sortMode == "app") topics.sortedWith(byLabel)
        else topics.sortedWith(compareByDescending<String> { newestMeasured(cache, it) }.then(byLabel))
    }

    /** A topic as a group: its rows (limited, under Show) and the chip its poll state earns. */
    fun ntfyGroupView(
        topic: String, result: NtfyResult?, limit: Int, showMode: String, readIds: Set<String>, visitSeenAt: Long, now: Long,
    ): GroupView {
        val base = NotifGroup(
            key = topic, label = NtfyCatalog.labelOf(topic), sub = topic, rows = emptyList(),
            url = "${NtfyCatalog.webBaseUrl()}/$topic",
        )
        if (result == null) return GroupView(base, GroupChip("checking…", Tone.UNKNOWN))
        if (!result.ok) return GroupView(base, GroupChip(result.error, Tone.UNKNOWN))
        val rows = withinGroup(result.rows, showMode, readIds, visitSeenAt).let { if (limit > 0) it.take(limit) else it }
        val g = base.copy(rows = rows)
        if (rows.isEmpty()) {
            return if (result.rows.isEmpty()) GroupView(g, GroupChip("silent ${NtfyCatalog.pollWindow()} · publisher?", Tone.WARN))
            else GroupView(g, GroupChip("nothing new", Tone.DIM))
        }
        return GroupView(g, GroupChip("${rows.size} · ${ago(now - rows.first().ts)}", Tone.OK))
    }

    /** The poll the page runs. A seam only so a unit test can answer without opening a socket. */
    internal var ntfyPoller: (List<String>) -> Map<String, NtfyResult> = { pollTopics(it) }

    /** One request for every topic of a panel; an unreachable relay answers every topic the same. */
    fun pollTopics(topics: List<String>): Map<String, NtfyResult> {
        fun all(r: NtfyResult) = topics.associateWith { r }
        return try {
            val url = java.net.URL(NtfyCatalog.pollUrl(topics.joinToString(",")))
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 15_000
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
            try {
                if (conn.responseCode != 200) all(NtfyResult(false, NtfyCatalog.readVerdict(conn.responseCode), emptyList()))
                else {
                    val rows = topics.associateWith { mutableListOf<NotifRow>() }
                    conn.inputStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) runCatching {
                            val o = org.json.JSONObject(line)
                            val topic = o.optString("topic")
                            if (o.optString("event") == "message") rows[topic]?.let { bucket ->
                                val ts = o.optLong("time", 0L) * 1000L
                                bucket += NotifRow(
                                    ts = ts,
                                    title = o.optString("title").ifBlank { topic },
                                    text = o.optString("message"),
                                    id = ntfyNs(topic) + o.optString("id").ifBlank { ts.toString() },
                                )
                            }
                        }
                    }
                    rows.mapValues { (_, v) -> NtfyResult(true, "", v) }
                }
            } finally { conn.disconnect() }
        } catch (_: Throwable) {
            all(NtfyResult(false, NtfyCatalog.UNREACHABLE_VERDICT, emptyList()))
        }
    }

    // ── phone stream / taxonomy ───────────────────────────────────────

    /** The section prefixes the two taxonomy rows ask for, or null for "All". Their values are the prefix characters
     *  declared in ui.phone_sections; an option id is a SET of prefixes ("-+"). */
    fun wantedPrefixes(toolsMode: String, servicesMode: String): String? = when {
        toolsMode != "all" -> toolsMode
        servicesMode != "all" -> servicesMode
        else -> null
    }

    /** Keep a phone-stream app under the two taxonomy rows. [ctx] IS REQUIRED: the metadata pass of ui.phone_folders
     *  only runs when the lookup is given a Context, and without it every app no keyword names falls to the sink. */
    fun taxonomyKeeps(ctx: Context, want: String?, packageName: String, label: String): Boolean {
        if (want == null) return true
        val prefix = PhoneTaxonomy.sectionPrefixOf(packageName, label, ctx)
        return prefix.isNotEmpty() && want.contains(prefix)
    }

    /** The same narrowing for a CLOUD group, whose key is an ntfy topic or an in-app producer name rather than a package:
     *  the section prefix is declared per publisher in ui.ntfy.taxon; an undeclared publisher shows on All only. */
    fun cloudTaxonomyKeeps(want: String?, groupKey: String): Boolean {
        if (want == null) return true
        val prefix = NtfyCatalog.taxonOf(groupKey)
        return prefix.isNotEmpty() && want.contains(prefix)
    }

    /** The phone notifications grouped per app, as boxes. */
    fun phoneGroups(entries: List<PhoneNotificationStore.Entry>): List<NotifGroup> =
        entries.groupBy { it.packageName.ifBlank { it.appLabel } }.map { (key, es) ->
            NotifGroup(
                key = key,
                label = es.firstOrNull { it.appLabel.isNotBlank() }?.appLabel ?: key,
                sub = key,
                launchPackage = es.first().packageName,
                rows = es.map { NotifRow(it.ts, it.title.ifBlank { it.appLabel }, it.text, id = PHONE_NS + it.key) },
            )
        }

    // ── inbox cards ───────────────────────────────────────────────────

    /** The companion app an inbox card speaks for: the first `extapp:` target among its url and links. */
    fun inboxApp(panel: Sections.StackPanel): Sections.ExternalApp? =
        (listOf(panel.url) + panel.links.map { it.url })
            .firstOrNull { it.startsWith(EXTAPP_PREFIX) }
            ?.removePrefix(EXTAPP_PREFIX)?.substringBefore('/')
            ?.let { Sections.externalApp(it) }

    /** Every package the card may speak for: hub, alt, install target and forks. */
    fun inboxPackages(app: Sections.ExternalApp): Set<String> =
        (listOf(app.hubPackage, app.altPackage, app.installPackage) + app.forks.values)
            .filterNot { it.isBlank() }.toSet()

    fun isSectionFolder(folderId: String): Boolean =
        PhoneTaxonomy.folderLabelOf(folderId).firstOrNull()?.isLetterOrDigit() == false

    fun kindLabel(folderId: String): String =
        PhoneTaxonomy.folderLabelOf(folderId).dropWhile { !it.isLetterOrDigit() }

    /** The ONE section folder all of an app's packages classify into, or "" (none, or they disagree). */
    fun folderOfApp(ctx: Context, app: Sections.ExternalApp): String =
        inboxPackages(app)
            .map { PhoneTaxonomy.folderIdOf(it, app.label, ctx) }
            .distinct().singleOrNull()
            ?.takeIf { isSectionFolder(it) }
            .orEmpty()

    /** The folder an inbox card draws for: its app's, but only when no sibling card is about the same one. */
    fun inboxFolderId(ctx: Context, panel: Sections.StackPanel, pagePanels: List<Sections.StackPanel>): String {
        val mine = inboxApp(panel)?.let { folderOfApp(ctx, it) }.orEmpty()
        if (mine.isEmpty()) return ""
        val claimants = pagePanels.count { inboxApp(it)?.let { app -> folderOfApp(ctx, app) }.orEmpty() == mine }
        return if (claimants == 1) mine else ""
    }

    /** The phone notifications an inbox card is about. */
    fun inboxEntries(ctx: Context, feed: List<PhoneNotificationStore.Entry>,
                     app: Sections.ExternalApp, folderId: String) =
        if (folderId.isEmpty()) feed.filter { it.packageName in inboxPackages(app) }
        else feed.filter { PhoneTaxonomy.folderIdOf(it.packageName, it.appLabel, ctx) == folderId }

    // ── repo / run feeds ──────────────────────────────────────────────

    fun feedEmptyLabel(status: GitHubFeed.Status, noun: String): String = when (status) {
        GitHubFeed.Status.RATE_LIMITED ->
            "(rate limited — GitHub's anonymous 60 requests/hour quota is spent; retry in a few minutes)"
        GitHubFeed.Status.UNREACHABLE -> "(unreachable — GitHub did not answer; check the network)"
        GitHubFeed.Status.OK -> "(no recent $noun)"
    }

    fun feedStaleLabel(status: GitHubFeed.Status): String = when (status) {
        GitHubFeed.Status.RATE_LIMITED -> "(rate limited — showing the last cached rows)"
        else -> "(offline — showing the last cached rows)"
    }

    fun daguStatusLabel(code: Int): String = when (code) {
        0 -> "never run"
        1 -> "running"
        2 -> "failed"
        3 -> "cancelled"
        4 -> "succeeded"
        6 -> "partially succeeded"
        7 -> "queued"
        else -> "status $code"
    }

    fun daguSeverity(code: Int): String = when (code) {
        0 -> "idle"
        2, 3 -> "error"
        1, 6, 7 -> "warn"
        else -> "info"
    }

    /** A workflow run's severity: success is info, a failed / timed out / cancelled run is an error, anything else warns. */
    fun runSeverity(conclusion: String): String = when (conclusion) {
        "success" -> "info"
        "failure", "timed_out", "cancelled" -> "error"
        else -> "warn"
    }

    /** One line of run statistics: "N runs · P% green · F failed · last green …". */
    fun runVerdict(runs: List<GitHubFeed.Run>, now: Long): Pair<String, String> {
        val finished = runs.filter { it.conclusion.isNotBlank() }
        val green = finished.filter { it.conclusion == "success" }
        val failed = finished.count { it.conclusion == "failure" || it.conclusion == "timed_out" }
        val lastGreen = green.maxOfOrNull { it.tsMillis } ?: 0L
        val verdict = if (finished.isEmpty()) "no verdicts yet (${runs.size} still running or queued)" else {
            val pct = (green.size * 100) / finished.size
            val last = if (lastGreen > 0L) "last green ${GitHubFeed.ago(now - lastGreen)}" else "no green run in this window"
            "${runs.size} runs · $pct% green · $failed failed · $last"
        }
        val severity = when {
            finished.isEmpty() -> "idle"
            failed > 0 -> "error"
            else -> "info"
        }
        return verdict to severity
    }

    /** A stable colour per publisher / app for the monogram avatar. */
    fun monogramHue(key: String, size: Int): Int = ((key.hashCode() % size) + size) % size
}
