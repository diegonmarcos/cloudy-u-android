package com.diegonmarcos.superapp.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.apps.InboxClasses
import com.diegonmarcos.superapp.apps.PhoneTaxonomy
import com.diegonmarcos.superapp.core.NotificationStore
import com.diegonmarcos.superapp.notificationcenter.AppSplit
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Everything the aggregator page remembers while it is on screen, as Compose state: the five toggle rows, the read ids,
 * the archive, the ntfy answers and the pull-down refresh. The page is built ONCE from this; a toggle or a pull bumps
 * [epoch] and every notification body re-reads its stores (what the View version did by re-running its "body
 * refreshers"). [page] is the StackFilters key — the page's mode, whether or not it draws a toggle row — so swipes on a
 * page without filters never land in a blank-keyed bucket shared with every other stackless page.
 */
internal class AggregatorState(
    val ctx: Context,
    val page: String,
    val panels: List<Sections.StackPanel>,
    val filters: List<Sections.StackFilter>,
) {
    /** Start of this visit's NEW window: the ts of the PREVIOUS visit. Captured before the watermark advances so toggling
     *  Show back and forth within one visit keeps answering the same question. */
    val visitSeenAt: Long = StackFilters.lastSeen(ctx, page)

    init {
        // The watermark moves on EVERY visit: a page can carry notification boxes without declaring a toggle row, and
        // while this sat behind the filters branch their watermark stayed 0 and every box reported its whole history as new.
        StackFilters.markSeen(ctx, page, System.currentTimeMillis())
    }

    var readIds: Set<String> by mutableStateOf<Set<String>>(StackFilters.readKeys(ctx, page))
        private set

    var sortMode by mutableStateOf(selection("sort", "time"))
    var showMode by mutableStateOf(selection("show", "all"))
    /** The two phone-taxonomy rows: section PREFIX characters ("@", ".", "=", "-", "+"), or "all". */
    var toolsMode by mutableStateOf(selection(AggregatorModel.FILTER_TOOLS, "all"))
    var servicesMode by mutableStateOf(selection(AggregatorModel.FILTER_SERVICES, "all"))
    var sourceMode by mutableStateOf(selection("source", "both"))

    /** Bumped to make every notification body re-read its stores (a toggle, a pull, a swipe that hides a row). */
    var epoch by mutableIntStateOf(0)
    private var archiveVersion by mutableIntStateOf(0)
    /** The page's SETTINGS section at the very bottom: folded until the user opens it, remembered per page. */
    var configOpen by mutableStateOf(
        StackFilters.selected(ctx, page, yesNo(AggregatorModel.FILTER_CONFIG_OPEN)) == "yes")

    fun toggleConfigOpen() {
        configOpen = !configOpen
        StackFilters.select(ctx, page, AggregatorModel.FILTER_CONFIG_OPEN, if (configOpen) "yes" else "no")
    }

    // ── #1672 grouped by app / by time, per notification block ─────────

    private fun groupFilter(feed: String) = Sections.StackFilter(
        id = AggregatorModel.FILTER_GROUP_PREFIX + feed, label = "", default = AggregatorModel.GROUP_APP,
        options = listOf(Sections.FilterOption(AggregatorModel.GROUP_APP, "By app"),
            Sections.FilterOption(AggregatorModel.GROUP_TIME, "By time")),
    )

    /** What the user picked this visit; a feed not in here reads its stored choice (default: by app). */
    private val groupModes = mutableStateMapOf<String, String>()

    fun groupMode(feed: String): String =
        groupModes[feed] ?: runCatching { StackFilters.selected(ctx, page, groupFilter(feed)) }.getOrDefault(AggregatorModel.GROUP_APP)

    fun setGroupMode(feed: String, mode: String) {
        runCatching { StackFilters.select(ctx, page, AggregatorModel.FILTER_GROUP_PREFIX + feed, mode) }
        groupModes[feed] = mode
    }

    var archiveOpen by mutableStateOf(
        StackFilters.selected(ctx, page, yesNo(AggregatorModel.FILTER_ARCHIVE_OPEN)) == "yes")

    /** Groups the user folded shut (kept for the life of the page, like the View version). */
    val collapsedGroups = mutableStateMapOf<String, Boolean>()
    val panelOpen = mutableStateMapOf<Int, Boolean>()

    val ntfyCache = mutableStateMapOf<String, NtfyResult>()
    /** Bumped by a pull: a card that has not yet served this value goes back to the network instead of repainting its cache. */
    var pollEpoch by mutableIntStateOf(0)

    var refreshing by mutableStateOf(false)
        private set
    var refreshNote by mutableStateOf<Pair<String, Tone>?>(null)
        private set
    private var refreshBefore: Set<String> = emptySet()
    private var pendingPolls = 0
    var badgeEpoch by mutableIntStateOf(0)

    /** Current choice for the filter with [id], or [fallback] when the page does not declare that filter at all. */
    fun selection(id: String, fallback: String): String =
        filters.firstOrNull { it.id == id }?.let { StackFilters.selected(ctx, page, it) } ?: fallback

    /** The page can re-ask something: only then does the pull-down gesture arm (a spinner over a page with nothing to
     *  re-ask is a control reporting a fetch it never made). */
    val refreshable: Boolean = panels.any { it.kind in AggregatorModel.REFRESHABLE_KINDS }

    // ── toggles ──────────────────────────────────────────────────────

    fun yesNo(id: String) = Sections.StackFilter(
        id = id, label = "", default = "no",
        options = listOf(Sections.FilterOption("yes", "yes"), Sections.FilterOption("no", "no")),
    )

    fun selected(filter: Sections.StackFilter): String = StackFilters.selected(ctx, page, filter)

    /** A tap on a toggle option: stored, the taxonomy sibling reset, every body re-rendered, the Source row re-applied. */
    fun select(filter: Sections.StackFilter, optionId: String) {
        StackFilters.select(ctx, page, filter.id, optionId)
        resetTaxonomySibling(filter.id, optionId)
        sortMode = selection("sort", sortMode)
        showMode = selection("show", showMode)
        toolsMode = selection(AggregatorModel.FILTER_TOOLS, "all")
        servicesMode = selection(AggregatorModel.FILTER_SERVICES, "all")
        sourceMode = selection("source", "both")
        epoch++
    }

    /** The two taxonomy rows are ONE choice spread over two rows: an app is a Tool or a Service, never both, so a value
     *  left set in each would AND to a permanently empty page. Narrowing one row therefore returns the other to All. */
    private fun resetTaxonomySibling(changed: String, picked: String) {
        if (picked == "all") return
        val siblingId = when (changed) {
            AggregatorModel.FILTER_TOOLS -> AggregatorModel.FILTER_SERVICES
            AggregatorModel.FILTER_SERVICES -> AggregatorModel.FILTER_TOOLS
            else -> return
        }
        val sibling = filters.firstOrNull { it.id == siblingId } ?: return
        StackFilters.select(ctx, page, sibling.id, "all")
    }

    fun want(): String? = AggregatorModel.wantedPrefixes(toolsMode, servicesMode)

    // ── read / unread ────────────────────────────────────────────────

    fun isUnread(r: NotifRow): Boolean = AggregatorModel.isUnread(r, readIds, visitSeenAt)

    fun markRead(id: String, read: Boolean) {
        readIds = StackFilters.markRead(ctx, page, id, read)
    }

    fun markGroupRead(g: NotifGroup) {
        for (r in g.rows) readIds = StackFilters.markRead(ctx, page, r.id, true)
    }

    /** "Mark all read": every row this page draws right now, in one write. */
    fun markAllRead() {
        readIds = StackFilters.markAllRead(ctx, page, displayedRowIds())
    }

    // ── per-row archive (swipe left, One UI's dismiss) ───────────────

    var archivedRowIds: Set<String> by mutableStateOf<Set<String>>(StackFilters.archivedKeys(ctx, page))
        private set

    fun isRowArchived(r: NotifRow): Boolean = r.id.isNotBlank() && r.id in archivedRowIds

    fun archiveRow(id: String, archived: Boolean) {
        archivedRowIds = StackFilters.archiveRows(ctx, page, listOf(id), archived)
    }

    fun restoreRows(g: NotifGroup) {
        archivedRowIds = StackFilters.archiveRows(ctx, page, g.rows.map { it.id }, false)
    }

    /** "Archive all": every box still on the page moves into the Archive (each one restorable on its own). */
    fun archiveAll() {
        for (p in panels) for (v in allViews(p)) {
            StackFilters.select(ctx, page, AggregatorModel.ARCHIVED_PREFIX + v.group.key, "yes")
        }
        archiveVersion++
    }

    // ── archive ──────────────────────────────────────────────────────

    fun isArchived(key: String): Boolean {
        archiveVersion  // read: an archive tap re-evaluates every group
        return StackFilters.selected(ctx, page, yesNo(AggregatorModel.ARCHIVED_PREFIX + key)) == "yes"
    }

    fun setArchived(key: String, archived: Boolean) {
        StackFilters.select(ctx, page, AggregatorModel.ARCHIVED_PREFIX + key, if (archived) "yes" else "no")
        archiveVersion++
    }

    fun toggleArchiveOpen() {
        archiveOpen = !archiveOpen
        StackFilters.select(ctx, page, AggregatorModel.FILTER_ARCHIVE_OPEN, if (archiveOpen) "yes" else "no")
    }

    // ── groups per panel ─────────────────────────────────────────────

    private fun now() = System.currentTimeMillis()

    fun phoneViews(): List<GroupView> {
        epoch
        if (!AggregatorModel.isNotificationAccessGranted(ctx)) return emptyList()
        val stored = PhoneNotificationStore.all(ctx)
        PhoneTaxonomy.prime(ctx, stored.associate { it.packageName to it.appLabel })
        val want = want()
        val groups = AggregatorModel.phoneGroups(stored)
            .filter { AggregatorModel.taxonomyKeeps(ctx, want, it.launchPackage, it.label) }
        return AggregatorModel.visibleGroups(groups, sortMode, showMode, readIds, visitSeenAt, now())
    }

    /** The phone listener's captured feed (read here so the page has ONE reader of the notification stores). */
    fun phoneFeed(): List<PhoneNotificationStore.Entry> = PhoneNotificationStore.all(ctx)

    /** How many in-app events exist at all (the page's one reader of the store is this class). */
    fun localFeedTotal(): Int = NotificationStore.all(ctx).size

    /** The in-app (SuperApp's own) feed, grouped per producer. */
    fun localFeedViews(): List<GroupView> {
        epoch
        val want = want()
        val groups = NotificationStore.all(ctx).groupBy { it.source.ifBlank { "SuperApp" } }
            .filterKeys { AggregatorModel.cloudTaxonomyKeeps(want, it) }
            .map { (source, entries) ->
                NotifGroup(
                    key = source, label = source, sub = "in-app producer",
                    rows = entries.map {
                        NotifRow(it.ts, it.title, it.body, when (it.severity) {
                            NotificationStore.Sev.ERROR -> "error"
                            NotificationStore.Sev.WARN -> "warn"
                            else -> "info"
                        }, id = AggregatorModel.APP_NS + it.id)
                    },
                )
            }
        return AggregatorModel.visibleGroups(groups, sortMode, showMode, readIds, visitSeenAt, now())
    }

    /**
     * #1672 a `split` panel's REAL notifications: Cloud (cloudy) = the in-app feed plus every captured notification of a
     * fleet app; Phone = every captured notification of any other app. One list, re-ordered as one.
     */
    fun splitViews(panel: Sections.StackPanel): List<GroupView> {
        epoch
        // "all" (the All page): both sides together, one box per app, as Android's own notification centre.
        val phoneSide = phoneViews().filter {
            panel.split == AggregatorModel.SPLIT_ALL || AppSplit.of(it.group.launchPackage.ifBlank { it.group.key }) == panel.split
        }
        val all = if (panel.split != AppSplit.PHONE) localFeedViews() + phoneSide else phoneSide
        return AggregatorModel.visibleGroups(all.map { it.group }, sortMode, showMode, readIds, visitSeenAt, now())
    }

    /** How many notifications a `split` panel has at all, before Show / taxonomy filtering. */
    fun splitTotal(panel: Sections.StackPanel): Int {
        val phone = if (AggregatorModel.isNotificationAccessGranted(ctx))
            PhoneNotificationStore.all(ctx).count {
                panel.split == AggregatorModel.SPLIT_ALL || AppSplit.of(it.packageName.ifBlank { it.appLabel }) == panel.split
            } else 0
        return phone + if (panel.split != AppSplit.PHONE) NotificationStore.all(ctx).size else 0
    }

    /** #1672 the app category a box belongs to (its launcher folder's name; the in-app feed is the SuperApp's). */
    fun categoryOf(g: NotifGroup): String {
        if (g.launchPackage.isBlank()) return AggregatorModel.INAPP_CATEGORY
        return runCatching {
            PhoneTaxonomy.folderLabelOf(PhoneTaxonomy.folderIdOf(g.launchPackage, g.label, ctx))
        }.getOrDefault("").ifBlank { AggregatorModel.OTHER_CATEGORY }
    }

    fun ntfyTopics(panel: Sections.StackPanel): List<String> {
        val want = want()
        return AggregatorModel.ntfyTopicsOf(panel.scopes) { AggregatorModel.cloudTaxonomyKeeps(want, it) }
    }

    /** One group per channel, in [order] (decided ONCE by the page, so a poll landing never re-sorts the boxes under the
     *  thumb), each carrying the chip its poll state earns. */
    fun ntfyViews(
        panel: Sections.StackPanel,
        order: List<String> = AggregatorModel.orderTopics(ntfyTopics(panel), sortMode, ntfyCache),
    ): List<GroupView> {
        val n = now()
        return order.map {
            AggregatorModel.ntfyGroupView(it, ntfyCache[it], panel.limit, showMode, readIds, visitSeenAt, n)
        }
    }

    /** The phone groups an inbox card (mail / chat / stats naming an app) draws. */
    fun inboxViews(panel: Sections.StackPanel): List<GroupView> {
        epoch
        val app = AggregatorModel.inboxApp(panel) ?: return emptyList()
        if (!AggregatorModel.isNotificationAccessGranted(ctx)) return emptyList()
        val feed = PhoneNotificationStore.all(ctx)
        PhoneTaxonomy.prime(ctx, feed.associate { it.packageName to it.appLabel })
        val folderId = AggregatorModel.inboxFolderId(ctx, panel, panels)
        val stored = AggregatorModel.inboxEntries(ctx, feed, app, folderId)
        val groups = stored.groupBy { it.packageName }.map { (pkg, entries) ->
            NotifGroup(
                key = pkg, label = entries.firstOrNull { it.appLabel.isNotBlank() }?.appLabel ?: pkg, sub = pkg,
                launchPackage = pkg,
                rows = entries.map { NotifRow(it.ts, it.title.ifBlank { it.appLabel }, it.text, id = AggregatorModel.PHONE_NS + it.key) },
            )
        }
        return AggregatorModel.visibleGroups(groups, sortMode, showMode, readIds, visitSeenAt, now())
    }

    /** The phone groups one CHANNEL CLASS claims. */
    fun classGroups(panel: Sections.StackPanel): List<NotifGroup> {
        epoch
        val cls = InboxClasses.byId(panel.classId) ?: return emptyList()
        if (!AggregatorModel.isNotificationAccessGranted(ctx)) return emptyList()
        val feed = PhoneNotificationStore.all(ctx)
        PhoneTaxonomy.prime(ctx, feed.associate { it.packageName to it.appLabel })
        return feed.filter { InboxClasses.claims(cls.id, it.packageName, it.appLabel, ctx) }
            .groupBy { it.packageName }.map { (pkg, entries) ->
                NotifGroup(
                    key = pkg, label = entries.firstOrNull { it.appLabel.isNotBlank() }?.appLabel ?: pkg, sub = pkg,
                    launchPackage = pkg,
                    rows = entries.map { NotifRow(it.ts, it.title.ifBlank { it.appLabel }, it.text, id = AggregatorModel.PHONE_NS + it.key) },
                )
            }
    }

    fun classViews(panel: Sections.StackPanel): List<GroupView> =
        AggregatorModel.visibleGroups(classGroups(panel), sortMode, showMode, readIds, visitSeenAt, now())

    /** Whether a panel polls ntfy at all (so a pull has something to wait for). */
    fun pollsNtfy(panel: Sections.StackPanel): Boolean = when (panel.kind) {
        // #1672 a split panel carries no ntfy channels, so it never polls.
        "notification_center" -> panel.split.isBlank() &&
            (panel.stream == "cloud" || panel.stream == "channels") && ntfyTopics(panel).isNotEmpty()
        "class_inbox" -> InboxClasses.byId(panel.classId)?.source == AggregatorModel.C3NTFY_SOURCE && ntfyTopics(panel).isNotEmpty()
        else -> false
    }

    /** Every group a panel draws, in draw order — what the Archive collects from and what a pull diffs. */
    fun allViews(panel: Sections.StackPanel): List<GroupView> = when (panel.kind) {
        "notification_center" -> if (panel.split.isNotBlank()) splitViews(panel) else when (panel.stream) {
            "phone" -> phoneViews()
            "cloud" -> localFeedViews() + ntfyViews(panel)
            "channels" -> ntfyViews(panel)
            else -> emptyList()
        }
        "mail_accounts", "chat_matrix", "chat_mattermost", "stats" -> inboxViews(panel)
        "class_inbox" -> {
            val cls = InboxClasses.byId(panel.classId)
            (if (cls?.source == AggregatorModel.C3NTFY_SOURCE) ntfyViews(panel) else emptyList()) + classViews(panel)
        }
        else -> emptyList()
    }

    /** Every notification id drawn on this page right now. */
    fun displayedRowIds(): Set<String> {
        val ids = LinkedHashSet<String>()
        for (p in panels) for (v in allViews(p)) for (r in v.group.rows) if (r.id.isNotBlank()) ids += r.id
        return ids
    }

    // ── polling and housekeeping ─────────────────────────────────────

    private val served = HashMap<Int, Int>()

    /**
     * Poll the ntfy channels of one panel in ONE request. Driven by the page (not by the card's body) so a collapsed or
     * source-hidden card still answers a pull: the spinner waits for exactly the panels that [pollsNtfy]. A card goes
     * to the network when it has no answer yet, or when a pull asked for fresh data it has not yet served.
     */
    suspend fun pollPanel(index: Int, panel: Sections.StackPanel) {
        val forced = pollEpoch > (served[index] ?: 0)
        served[index] = pollEpoch
        val topics = ntfyTopics(panel)
        val slots = if (forced) topics else topics.filter { it !in ntfyCache }
        val counted = forced && refreshing
        if (slots.isNotEmpty()) {
            val byTopic = withContext(Dispatchers.IO) { AggregatorModel.ntfyPoller(slots) }
            for ((topic, result) in byTopic) {
                ntfyCache[topic] = result
                // A SUCCESSFUL poll is the complete current window for this topic, and only then may its read keys be
                // retired; an unavailable one must not resurrect every row the user already swiped away.
                if (result.ok) StackFilters.pruneRead(ctx, page, AggregatorModel.ntfyNs(topic),
                    result.rows.mapTo(HashSet()) { it.id })
            }
        }
        if (counted) pollSettled()
    }

    /** Retire read keys of rows that left a complete store, and clear the framework badge a cloud card's opening clears. */
    fun housekeeping() {
        if (panels.any { it.kind == "notification_center" && it.stream == "phone" } &&
            AggregatorModel.isNotificationAccessGranted(ctx)) {
            val stored = PhoneNotificationStore.all(ctx)
            // The store IS the complete phone namespace, so this prune sees everything it may retire.
            if (stored.isNotEmpty()) StackFilters.pruneRead(ctx, page, AggregatorModel.PHONE_NS,
                stored.mapTo(HashSet()) { AggregatorModel.PHONE_NS + it.key })
        }
        if (panels.any { it.kind == "notification_center" && it.stream == "cloud" }) {
            // Opening the in-app feed also clears the framework badge: the launcher badge and this list must not
            // disagree. #535: NOT the declared badges — a bare cancelAll() removed Media and Alerts from the shade.
            runCatching { com.diegonmarcos.superapp.notificationcenter.BadgeServices.clearNonBadges(ctx) }
            StackFilters.pruneRead(ctx, page, AggregatorModel.APP_NS,
                NotificationStore.all(ctx).mapTo(HashSet()) { AggregatorModel.APP_NS + it.id })
        }
    }

    // ── pull-down refresh ────────────────────────────────────────────

    /** The gesture re-runs the same re-render a toggle does, with the ntfy cards forced back to the network. */
    fun startRefresh(scope: CoroutineScope) {
        if (!refreshable) { refreshing = false; return }
        refreshBefore = displayedRowIds()
        refreshing = true
        pendingPolls = panels.count { pollsNtfy(it) }
        pollEpoch++
        epoch++
        scope.launch {
            delay(AggregatorModel.REFRESH_TIMEOUT_MS)
            if (refreshing) { pendingPolls = 0; finishRefresh(timedOut = true) }
        }
        if (pendingPolls == 0) finishRefresh(timedOut = false)
    }

    /** One forced channel poll came back (or failed). */
    fun pollSettled() {
        if (pendingPolls > 0) pendingPolls--
        if (pendingPolls == 0 && refreshing) finishRefresh(timedOut = false)
    }

    private fun finishRefresh(timedOut: Boolean) {
        refreshing = false
        val fresh = displayedRowIds().count { it !in refreshBefore }
        val unreachable = ntfyCache.count { !it.value.ok }
        val at = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date())
        val outcome = when {
            timedOut -> "did not finish · sources did not answer"
            fresh > 0 -> "$fresh new"
            else -> "nothing new"
        }
        val notReached = if (unreachable > 0) " · $unreachable channel${if (unreachable == 1) "" else "s"} not reached" else ""
        val tone = when {
            timedOut || unreachable > 0 -> Tone.UNKNOWN
            fresh > 0 -> Tone.OK
            else -> Tone.DIM
        }
        refreshNote = "Refreshed $at · $outcome$notReached" to tone
    }

    // ── Source toggle ────────────────────────────────────────────────

    /** Cloud vs Phone. The rule is the PANEL's declared `origin`, i.e. which stream the notification arrived on — not a list
     *  of phone package names, which would start going stale the day it was written. A panel with no origin is never hidden. */
    fun visible(panel: Sections.StackPanel): Boolean =
        panel.origin.isBlank() || sourceMode == "both" || panel.origin == sourceMode

    /** Every classified card hidden means the toggle did it, not a dead page — and the page says so. */
    val allSourcesHidden: Boolean
        get() = panels.any { it.origin.isNotBlank() } && panels.none { it.origin.isNotBlank() && visible(it) }

    fun sourceLabel(): String =
        filters.firstOrNull { it.id == "source" }?.options?.firstOrNull { it.id == sourceMode }?.label ?: sourceMode

    /** Host ids for the panels that embed a child fragment: a fixed pool, stable across process death. */
    val embedSlots: Map<Int, Int> = run {
        var next = 0
        panels.withIndex().filter { it.value.kind in EMBED_KINDS }.associate { it.index to next++ }
    }

    // ── panel folding ────────────────────────────────────────────────

    fun isOpen(index: Int, panel: Sections.StackPanel): Boolean = panelOpen[index] ?: !panel.collapsed

    /** The bottom-nav re-tap: collapse every panel if any is open, expand every panel if all are closed. */
    fun toggleAllCollapsed(): Boolean {
        val real = panels.withIndex().filter { it.value.kind != "section_title" && it.value.kind != "entry" }
        if (real.isEmpty()) return false
        val anyOpen = real.any { isOpen(it.index, it.value) }
        for ((i, _) in real) panelOpen[i] = !anyOpen
        return true
    }

    companion object {
        /** The panel kinds whose body is a child fragment. */
        val EMBED_KINDS = setOf("c3_public", "c3_private", "wg_mesh", "rss", "calendar_month", "calendar_agenda", "tasks")
    }
}
