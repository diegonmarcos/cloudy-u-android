package app.sterna.push

import android.app.Application
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.settings.NotificationContent
import com.diegonmarcos.superapp.core.BackgroundApps
import com.diegonmarcos.superapp.core.BackgroundCards
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the push service's keep-alive notification SAYS. It used to say one fixed line ("watching for new mail") and nothing
 * else; it now carries the mail: the unread count across the inboxes and the latest message. Pure ([content]) so a test can
 * execute it; [read] is the one place that touches the repository.
 *
 * Privacy is the widget's rule, not a new one: the sender and subject go through [MailNotificationText.widgetRow], so the
 * notification-content setting and the app lock hide names here exactly as they do on the home-screen widget. The count
 * names nobody and is always shown.
 */
object KeepAliveBadge {

    /**
     * [title] is the headline (the unread count), [lines] the rest (the app's own card). #1965 [fields] are the same data as the
     * declared "Background Apps" card fields (BackgroundCards.Mail): Unread, one Folder per 0*-9* category, Last sync, Push and
     * Latest. Nothing the lines say is missing from the fields, and the lines gained the Last sync line.
     */
    data class Content(val title: String, val lines: List<String>, val fields: List<BackgroundApps.Field> = emptyList())

    /** The app's own strings, passed in so the rule needs no Android resources. */
    class Texts(
        val unread: (Int) -> String,
        val noUnread: String,
        val watching: String,
        val hidden: String,
        val unknownSender: String,
        val noSubject: String,
        val counting: String = "counting…",
        val never: String = "never",
    )

    /** [unread] null = the count could not be read (no account, or a slow read): the headline is then the plain watching line. */
    fun content(
        unread: Int?,
        latest: RecentEmailRow?,
        setting: NotificationContent,
        appLockEnabled: Boolean,
        texts: Texts,
        folders: List<Pair<String, Int>> = emptyList(),
        lastSync: String? = null,
    ): Content {
        val head = when {
            unread == null -> texts.watching
            unread > 0 -> texts.unread(unread)
            else -> texts.noUnread
        }
        val lines = mutableListOf<String>()
        val latestLine = latest?.let {
            val sender = it.fromName?.takeIf { n -> n.isNotBlank() } ?: it.fromEmail?.takeIf { e -> e.isNotBlank() } ?: texts.unknownSender
            val subject = it.subject?.takeIf { t -> t.isNotBlank() } ?: texts.noSubject
            val row = MailNotificationText.widgetRow(setting, appLockEnabled, sender, subject, texts.hidden)
            listOfNotNull(row.primary, row.secondary).joinToString(" - ")
        }
        if (latestLine != null) lines += latestLine
        val folderLines = folderLines(folders, texts)
        lines += folderLines
        if (lastSync != null) lines += "${BackgroundCards.Mail.LAST_SYNC}: $lastSync"
        if (unread != null) lines += texts.watching
        // #1965 the card's fields, in the order the owner reads them: the counts first, the latest message last.
        val fields = buildList {
            add(BackgroundApps.Field(BackgroundCards.Mail.UNREAD, if (unread == null) texts.counting else head))
            folderLines.forEach { add(BackgroundApps.Field(BackgroundCards.Mail.FOLDER, it)) }
            add(BackgroundApps.Field(BackgroundCards.Mail.LAST_SYNC, lastSync ?: texts.never))
            add(BackgroundApps.Field(BackgroundCards.Mail.PUSH, texts.watching))
            if (latestLine != null) add(BackgroundApps.Field(BackgroundCards.Mail.LATEST, latestLine))
        }
        return Content(head, lines, fields)
    }

    /** #1944 A folder of the categories 0* to 9*: its name starts with a digit. */
    fun isCategoryFolder(name: String): Boolean = leaf(name).firstOrNull()?.let { it in '0'..'9' } == true

    /** #1965 The folder's own name: a "/"-delimited IMAP path ("INBOX/0 Work") is judged and shown by its last segment. */
    fun leaf(name: String): String = name.trim().substringAfterLast('/').trim().ifEmpty { name.trim() }

    /**
     * #1944 One line per category folder (names starting 0..9) with its unread count, in name order. The same folder name on
     * several watched accounts is one line with the counts added. Folder names are the user's own, not message content, so the
     * privacy setting does not apply to them (the counts name nobody).
     */
    fun folderLines(folders: List<Pair<String, Int>>, texts: Texts): List<String> =
        folders.filter { isCategoryFolder(it.first) }
            .groupBy({ leaf(it.first) }, { it.second.coerceAtLeast(0) })
            .toSortedMap()
            .map { (name, counts) -> "$name · ${texts.unread(counts.sum())}" }

    /** #1965 was 2 s: on a large cache the unified-unread aggregate missed it and the card fell back to "In flight, watching". */
    private const val READ_TIMEOUT_MS = 8_000L

    /** Read the count, the latest message, the setting and the lock, and project them. Bounded: a slow read costs a plainer line. */
    suspend fun read(app: Application): Content {
        val c = app.container
        val setting = c.settingsRepository.notificationContent.first()
        val lock = c.accountStore.appLockEnabled()
        val unread = withTimeoutOrNull(READ_TIMEOUT_MS) { c.mailRepository.observeUnifiedInboxUnread().first() }
        val latest = runCatching { c.mailRepository.recentUnifiedInbox(1).rows.firstOrNull() }.getOrNull()
        return content(unread, latest, setting, lock, texts(app), folders = readFolders(app), lastSync = readLastSync(app))
    }

    /** #1965 The newest successful sync of the watched accounts, as a local time ("18:34"; a date before today adds the day). */
    private fun readLastSync(app: Application): String? = runCatching {
        val store = app.container.accountStore
        val ids = (if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())).map { it.id }
        ids.mapNotNull { store.lastSyncAt(it) }.maxOrNull()?.let { lastSyncText(it, System.currentTimeMillis()) }
    }.getOrNull()

    fun lastSyncText(at: Long, now: Long): String {
        val sameDay = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).let { it.format(java.util.Date(at)) == it.format(java.util.Date(now)) }
        val pattern = if (sameDay) "HH:mm" else "d MMM HH:mm"
        return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(at))
    }

    /** #1944 (name, unread) of every folder of the watched accounts, from the cached mailbox rows: no network beyond what the app already does. */
    private suspend fun readFolders(app: Application): List<Pair<String, Int>> {
        val c = app.container
        val store = c.accountStore
        val ids = (if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())).map { it.id }
        return ids.flatMap { id ->
            withTimeoutOrNull(READ_TIMEOUT_MS) { c.mailRepository.observeMailboxes(id).first() }.orEmpty()
                .map { it.name to it.unreadForList }
        }
    }

    fun texts(app: Application): Texts = Texts(
        unread = { app.getString(R.string.notif_keepalive_unread, it) },
        noUnread = app.getString(R.string.notif_keepalive_none),
        watching = app.getString(R.string.notif_watching),
        hidden = app.getString(R.string.widget_latest_hidden),
        unknownSender = app.getString(R.string.message_unknown_sender),
        noSubject = app.getString(R.string.message_no_subject),
    )
}
