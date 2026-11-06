package app.sterna.ui.inbox

import android.content.Context
import app.sterna.BuildConfig
import app.sterna.core.data.mail.AlertScope
import app.sterna.core.data.mail.AuthFilter
import app.sterna.core.data.mail.InboxSplit
import app.sterna.core.data.mail.InboxSplitRules
import app.sterna.core.data.mail.ListShape
import app.sterna.core.data.settings.SortOrder
import java.util.Base64

/** How rows are grouped: threads ("by subject"), by sender address, by sender domain, or not at all. */
internal enum class GroupMode { SUBJECT, SENDER, DOMAIN, NONE }

/** How rows are ranked. Newest first is the default. */
internal enum class RankMode(val order: SortOrder) {
    NEWEST(SortOrder.DATE_DESC),
    SENDER(SortOrder.SENDER),
    SUBJECT(SortOrder.SUBJECT),
}

/**
 * The view every folder opens with, DECLARED in build.json::mail_list_defaults (baked as
 * BuildConfig.LIST_DEFAULT_*) and read here, so no default lives in code. The alert rules of the two
 * Inbox breakdown views come from build.json::mail_inbox_split the same way.
 */
internal object ListViewDefaults {
    val group: GroupMode = GroupMode.valueOf(BuildConfig.LIST_DEFAULT_GROUP)
    val rank: RankMode = RankMode.valueOf(BuildConfig.LIST_DEFAULT_RANK)

    /** The default filter; ALL means none, and is stored as the empty set. */
    val filter: ListFilter = ListFilter.valueOf(BuildConfig.LIST_DEFAULT_FILTER)
    val filters: Set<ListFilter> = if (filter == ListFilter.ALL) emptySet() else setOf(filter)

    val alertRules: InboxSplitRules by lazy {
        InboxSplit.parse(String(Base64.getDecoder().decode(BuildConfig.INBOX_SPLIT_B64), Charsets.UTF_8))
    }
}

/**
 * The combinable filters. UNREAD is the old funnel, now one filter among the others. ALL is the first
 * entry of the menu: "no filter", never stored, and choosing it lifts every other filter.
 */
internal enum class ListFilter {
    ALL, STARRED, UNREAD, ATTACHMENTS,

    /** Rule "G0 _ Auth": Ga Code, Gb Link to auth, or either. Exclusive among themselves. */
    AUTH_CODES, AUTH_LINKS, AUTH_ANY,

    /** The Inbox's two breakdown views, a strict partition (InboxSplit). Exclusive among themselves. */
    ALERTS, NO_ALERTS;

    val isAuth get() = this == AUTH_CODES || this == AUTH_LINKS || this == AUTH_ANY
    val isAlertScope get() = this == ALERTS || this == NO_ALERTS
}

/**
 * One folder's view choices. A folder nobody touched opens on [ListViewDefaults] (Domain, Newest
 * first, All); a saved choice wins. A null [rank] means "not chosen here": the global sort applies.
 */
internal data class ListView(
    val group: GroupMode? = ListViewDefaults.group,
    val rank: RankMode? = ListViewDefaults.rank,
    val filters: Set<ListFilter> = ListViewDefaults.filters,
) {
    fun effectiveGroup(globalConversation: Boolean): GroupMode =
        group ?: if (globalConversation) GroupMode.SUBJECT else GroupMode.NONE

    fun effectiveSort(globalSort: SortOrder): SortOrder = rank?.order ?: globalSort

    fun shape(): ListShape = ListShape(
        starred = ListFilter.STARRED in filters,
        attachments = ListFilter.ATTACHMENTS in filters,
        auth = when {
            ListFilter.AUTH_ANY in filters -> AuthFilter.ANY
            ListFilter.AUTH_CODES in filters -> AuthFilter.CODES
            ListFilter.AUTH_LINKS in filters -> AuthFilter.LINKS
            else -> AuthFilter.OFF
        },
        bySender = group == GroupMode.SENDER,
        byDomain = group == GroupMode.DOMAIN,
        alerts = when {
            ListFilter.ALERTS in filters -> AlertScope.ALERTS
            ListFilter.NO_ALERTS in filters -> AlertScope.NO_ALERTS
            else -> AlertScope.ALL
        },
        alertRules = if (filters.any { it.isAlertScope }) ListViewDefaults.alertRules else null,
    )

    /** Whether the three icon groups should read as active: any non-default choice is set. */
    fun viewModeActive(globalConversation: Boolean): Boolean =
        effectiveGroup(globalConversation) != ListView().effectiveGroup(globalConversation)

    fun rankActive(globalSort: SortOrder): Boolean = effectiveSort(globalSort) != ListViewDefaults.rank.order

    fun filterActive(): Boolean = filters != ListViewDefaults.filters

    /** Toggle [filter]; choosing one auth class lifts the others (they are exclusive), and so for the
     *  two alert views. ALL lifts every filter. */
    fun toggled(filter: ListFilter): ListView = copy(
        filters = when {
            filter == ListFilter.ALL -> emptySet()
            filter in filters -> filters - filter
            filter.isAuth -> filters.filterNot { it.isAuth }.toSet() + filter
            filter.isAlertScope -> filters.filterNot { it.isAlertScope }.toSet() + filter
            else -> filters + filter
        },
    )

    /** `group;rank;FILTER,FILTER` — empty fields are "not chosen". */
    fun encode(): String =
        listOf(group?.name.orEmpty(), rank?.name.orEmpty(), filters.sorted().joinToString(",")).joinToString(";")

    companion object {
        fun decode(raw: String?): ListView {
            if (raw == null) return ListView()
            val parts = raw.orEmpty().split(';')
            fun <T> pick(i: Int, parse: (String) -> T): T? =
                parts.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { runCatching { parse(it) }.getOrNull() }
            return ListView(
                group = pick(0) { GroupMode.valueOf(it) } ?: ListView().group,
                rank = pick(1) { RankMode.valueOf(it) } ?: ListView().rank,
                filters = parts.getOrNull(2).orEmpty().split(',')
                    .mapNotNull { f -> runCatching { ListFilter.valueOf(f) }.getOrNull() }
                    .filter { it != ListFilter.ALL }.toSet(),
            )
        }
    }
}

/**
 * Persistence of [ListView] per account and folder, in this app's own preferences file `list_view`
 * (declared in fleet-config.json). The key names the view, never a bare folder id: servers number
 * folders per account.
 */
internal object ListViewPrefs {
    private const val FILE = "list_view"

    fun keyFor(accountId: String?, folderId: String?) = "${accountId.orEmpty()}|${folderId.orEmpty()}"

    fun load(context: Context, key: String): ListView =
        ListView.decode(prefs(context).getString(key, null))

    /** Reset to defaults: forget this folder's saved choices, so it opens on [ListViewDefaults] again. */
    fun reset(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }

    fun save(context: Context, key: String, view: ListView) {
        prefs(context).edit().apply {
            if (view == ListView()) remove(key) else putString(key, view.encode())
        }.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
