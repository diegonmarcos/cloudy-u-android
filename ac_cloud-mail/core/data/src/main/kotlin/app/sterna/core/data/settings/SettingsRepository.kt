package app.sterna.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import app.sterna.core.data.mail.OfflineAttachmentLimit
import app.sterna.core.data.mail.OfflineWindow
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** How the app picks light vs dark colours. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The whole app's look (list, reader, compose, chrome). [CLOUD_DARK] is the look the app always had,
 * and still honours [ThemeMode]; [GMAIL_DARK] is a Gmail-dark replica (always dark); [CLOUD_EINK] is
 * an e-paper look (always light: black on white, no colour, no motion, serif reading).
 */
enum class AppStyle { CLOUD_DARK, GMAIL_DARK, CLOUD_EINK }

/** The stored [AppStyle] name read back; anything unknown or absent is the default, [AppStyle.CLOUD_DARK]. */
fun appStyleFrom(stored: String?): AppStyle =
    stored?.let { runCatching { AppStyle.valueOf(it) }.getOrNull() } ?: AppStyle.CLOUD_DARK

/** Vertical density of message-list rows. */
enum class ListDensity { COMPACT, NORMAL, SPACED }

/**
 * How much of each message's body preview to show in the list.
 *
 * [THREE] carries 4 lines, not 3, since #500: the row's preview grew a line and the identifier
 * stays put on purpose — it is stored by name in [SettingsRepository.setPreviewLines], and
 * renaming it would strand every phone that already picked this option back on [ONE].
 */
enum class PreviewLines(val lines: Int) { NONE(0), ONE(1), THREE(4), FIVE(5) }

/** An action bound to a swipe gesture on a message row. */
enum class SwipeAction { NONE, TOGGLE_READ, DELETE, ARCHIVE, FLAG }

/**
 * How the message list is ordered. [FLAGGED_FIRST] is a sort like any other, not a modifier: until
 */
enum class SortOrder { DATE_DESC, DATE_ASC, SUBJECT, SENDER, UNREAD_FIRST, FLAGGED_FIRST }

/** Reading text size for the message body (WebView text zoom, in percent). */
enum class MessageTextSize(val zoom: Int) { SMALL(85), NORMAL(100), LARGE(125), HUGE(150) }

/**
 * How new mail reaches the device (#17, outcome-framed — never a transport choice): INSTANT keeps
 */
enum class DeliveryMode { INSTANT, BATTERY_SAVER }

/**
 * How much a new-mail notification reveals on the lock screen (#25), most talkative first.
 */
enum class NotificationContent { BODY_PREVIEW, SENDER_AND_SUBJECT, SENDER_ONLY, NONE }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** The app's one instance of the settings store, for the fleet setup contract's handler (a second
 *  DataStore over the same file in one process is an error). */
fun Context.mailSettingsDataStore(): DataStore<Preferences> = applicationContext.settingsDataStore

/**
 * Reactive app-preferences store backed by a Preferences [DataStore]. Separate from
 */
class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME_MODE] = mode.name }
    }

    /** The app style (Cloud Dark, Gmail Dark, Cloud E-ink), persisted as `app_style`. */
    val appStyle: Flow<AppStyle> = dataStore.data.map { prefs -> appStyleFrom(prefs[KEY_APP_STYLE]) }

    suspend fun setAppStyle(style: AppStyle) {
        dataStore.edit { it[KEY_APP_STYLE] = style.name }
    }

    /** Use Material You (wallpaper-derived) colours instead of Sterna's brand palette. Off by default. */
    val dynamicColor: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_DYNAMIC_COLOR] ?: false
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    val listDensity: Flow<ListDensity> = dataStore.data.map { prefs ->
        prefs[KEY_LIST_DENSITY]?.let { runCatching { ListDensity.valueOf(it) }.getOrNull() }
            ?: ListDensity.NORMAL
    }

    suspend fun setListDensity(density: ListDensity) {
        dataStore.edit { it[KEY_LIST_DENSITY] = density.name }
    }

    val previewLines: Flow<PreviewLines> = dataStore.data.map { prefs ->
        prefs[KEY_PREVIEW_LINES]?.let { runCatching { PreviewLines.valueOf(it) }.getOrNull() }
            ?: PreviewLines.ONE
    }

    suspend fun setPreviewLines(value: PreviewLines) {
        dataStore.edit { it[KEY_PREVIEW_LINES] = value.name }
    }

    /** Whether the dark theme sits on a black background, for OLED panels (#117). OFF by default,
     *  and it only ever touches the DARK scheme. Elevated surfaces are compressed toward black
     *  rather than flattened onto it — see `pulledToBlack`. */
    val pureBlack: Flow<Boolean> = dataStore.data.map(::pureBlackFrom)

    suspend fun setPureBlack(enabled: Boolean) {
        dataStore.edit { it[KEY_PURE_BLACK] = enabled }
    }

    /** Whether a message-list row starts with the sender's coloured initials (#144). ON by default.
     *  No other monogram in the app (the reader, the drawer, the account picker) is touched. */
    val listMonogram: Flow<Boolean> = dataStore.data.map(::listMonogramFrom)

    suspend fun setListMonogram(enabled: Boolean) {
        dataStore.edit { it[KEY_LIST_MONOGRAM] = enabled }
    }

    val swipeRightAction: Flow<SwipeAction> = swipeFlow(KEY_SWIPE_RIGHT, SwipeAction.TOGGLE_READ)
    val swipeLeftAction: Flow<SwipeAction> = swipeFlow(KEY_SWIPE_LEFT, SwipeAction.DELETE)

    suspend fun setSwipeRightAction(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_RIGHT] = action.name }
    }

    suspend fun setSwipeLeftAction(action: SwipeAction) {
        dataStore.edit { it[KEY_SWIPE_LEFT] = action.name }
    }

    /** Deduped: DataStore republishes the WHOLE `Preferences` on every write, whatever key was
     *  touched, and the browse list is built off this flow — so an unrelated setting's write threw
     *  away the running pager and started it at the first page again. */
    val sortOrder: Flow<SortOrder> = dataStore.data.map { prefs ->
        prefs[KEY_SORT_ORDER]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.DATE_DESC
    }.distinctUntilChanged()

    suspend fun setSortOrder(order: SortOrder) {
        dataStore.edit { it[KEY_SORT_ORDER] = order.name }
    }

    /** Collapse threads into one conversation row in the list (on by default). Deduped for
     *  [sortOrder]'s reason: it is the other settings flow the browse list's paging key uses. */
    val conversationView: Flow<Boolean> = dataStore.data.map { it[KEY_CONVERSATION_VIEW] ?: true }
        .distinctUntilChanged()

    suspend fun setConversationView(enabled: Boolean) {
        dataStore.edit { it[KEY_CONVERSATION_VIEW] = enabled }
    }

    /** Mark a message as read when deleting it (off by default). */
    val markReadOnDelete: Flow<Boolean> = dataStore.data.map { it[KEY_MARK_READ_ON_DELETE] ?: false }

    suspend fun setMarkReadOnDelete(enabled: Boolean) {
        dataStore.edit { it[KEY_MARK_READ_ON_DELETE] = enabled }
    }

    /** Mark a message as read when archiving it (off by default; #67). */
    val markReadOnArchive: Flow<Boolean> = dataStore.data.map { it[KEY_MARK_READ_ON_ARCHIVE] ?: false }

    suspend fun setMarkReadOnArchive(enabled: Boolean) {
        dataStore.edit { it[KEY_MARK_READ_ON_ARCHIVE] = enabled }
    }

    /** Mark a message as read when it LEAVES the Inbox for another folder — an explicit
     *  move-to-folder, Report spam included, never a move back INTO the Inbox (off by default; #67). */
    val markReadOnMove: Flow<Boolean> = dataStore.data.map { it[KEY_MARK_READ_ON_MOVE] ?: false }

    suspend fun setMarkReadOnMove(enabled: Boolean) {
        dataStore.edit { it[KEY_MARK_READ_ON_MOVE] = enabled }
    }

    /** Return a conversation's archived messages to the Inbox when a new reply arrives (on by
     *  default; #50). */
    val unarchiveOnReply: Flow<Boolean> = dataStore.data.map { it[KEY_UNARCHIVE_ON_REPLY] ?: true }

    suspend fun setUnarchiveOnReply(enabled: Boolean) {
        dataStore.edit { it[KEY_UNARCHIVE_ON_REPLY] = enabled }
    }

    /** Whether a reply or a forward also opens with the signature in its body. Off by default. The
     *  signature itself is per identity; this only says WHEN it is inserted. */
    val signatureOnReplies: Flow<Boolean> = dataStore.data.map { it[KEY_SIGNATURE_ON_REPLIES] ?: false }

    suspend fun setSignatureOnReplies(enabled: Boolean) {
        dataStore.edit { it[KEY_SIGNATURE_ON_REPLIES] = enabled }
    }

    /** Whether the signature sits BELOW the quoted text in a reply. Off by default. Independent of
     *  [signatureOnReplies]: neither disables the other. */
    val signatureBelowQuote: Flow<Boolean> = dataStore.data.map { it[KEY_SIGNATURE_BELOW_QUOTE] ?: false }

    suspend fun setSignatureBelowQuote(enabled: Boolean) {
        dataStore.edit { it[KEY_SIGNATURE_BELOW_QUOTE] = enabled }
    }

    /** Whether the composer puts the standard "-- " delimiter line above the signature. ON by
     *  default: that line is what other mail apps recognise a signature by. Turned off, the
     *  signature field holds EXACTLY what goes into the message (#90). */
    val signatureDelimiter: Flow<Boolean> = dataStore.data.map { it[KEY_SIGNATURE_DELIMITER] ?: true }

    suspend fun setSignatureDelimiter(enabled: Boolean) {
        dataStore.edit { it[KEY_SIGNATURE_DELIMITER] = enabled }
    }

    /** The OpenPGP app the user picked, by package name; `null` = no choice expressed (#151). null
     *  is NOT "no provider": it means the app decides, through `PgpProviders.resolve`.
     *  restored onto a phone without that app it would name a provider that cannot be bound. */
    val pgpProvider: Flow<String?> = dataStore.data.map { it[KEY_PGP_PROVIDER] }

    suspend fun setPgpProvider(packageName: String) {
        dataStore.edit { it[KEY_PGP_PROVIDER] = packageName }
    }

    /** Whether the reader shows the Reply/Forward bar along the bottom of a message. ON by default;
     *  both actions stay one tap away in the top bar either way (#63). Global, not per account: the
     *  reader is shared by the unified inbox, so a per-account answer would make the bar come and go
     *  between two messages of one list. */
    val replyBar: Flow<Boolean> = dataStore.data.map(::replyBarFrom)

    suspend fun setReplyBar(enabled: Boolean) {
        dataStore.edit { it[KEY_REPLY_BAR] = enabled }
    }

    /** Whether the dates the composer writes into an outgoing message — a reply's quote attribution
     *  line and a forward's Date header — are rendered in UTC rather than in the device's time zone
     *  (#120). OFF by default. Global: the question is about the device, not about a mailbox. */
    val quotedDatesUtc: Flow<Boolean> = dataStore.data.map(::quotedDatesUtcFrom)

    suspend fun setQuotedDatesUtc(enabled: Boolean) {
        dataStore.edit { it[KEY_QUOTED_DATES_UTC] = enabled }
    }

    /** "Keep mail offline for": every message inside this window is stored for offline reading. */
    val offlineWindow: Flow<OfflineWindow> = dataStore.data.map { OfflineWindow.from(it[KEY_OFFLINE_WINDOW]) }

    suspend fun setOfflineWindow(window: OfflineWindow) {
        dataStore.edit { it[KEY_OFFLINE_WINDOW] = window.name }
    }

    /** Largest attachment the offline sync downloads. */
    val offlineAttachmentLimit: Flow<OfflineAttachmentLimit> =
        dataStore.data.map { OfflineAttachmentLimit.from(it[KEY_OFFLINE_ATTACHMENTS]) }

    suspend fun setOfflineAttachmentLimit(limit: OfflineAttachmentLimit) {
        dataStore.edit { it[KEY_OFFLINE_ATTACHMENTS] = limit.name }
    }

    /** Epoch millis of the last completed offline sync; 0 = never. */
    val offlineLastSync: Flow<Long> = dataStore.data.map { it[KEY_OFFLINE_LAST_SYNC] ?: 0L }

    suspend fun setOfflineLastSync(millis: Long) {
        dataStore.edit { it[KEY_OFFLINE_LAST_SYNC] = millis }
    }

    /** Reading text size for the message body. */
    val messageTextSize: Flow<MessageTextSize> = dataStore.data.map { prefs ->
        prefs[KEY_MESSAGE_TEXT_SIZE]?.let { runCatching { MessageTextSize.valueOf(it) }.getOrNull() }
            ?: MessageTextSize.NORMAL
    }

    suspend fun setMessageTextSize(size: MessageTextSize) {
        dataStore.edit { it[KEY_MESSAGE_TEXT_SIZE] = size.name }
    }

    /** Whether recipient autocomplete may read the device's contacts (off by default). */
    val contactSuggestions: Flow<Boolean> = dataStore.data.map { it[KEY_CONTACT_SUGGESTIONS] ?: false }

    suspend fun setContactSuggestions(enabled: Boolean) {
        dataStore.edit { it[KEY_CONTACT_SUGGESTIONS] = enabled }
    }

    /** Whether the first-launch privacy welcome has been shown (shown once, before adding an account). */
    val hasSeenWelcome: Flow<Boolean> = dataStore.data.map { it[KEY_HAS_SEEN_WELCOME] ?: false }

    suspend fun setHasSeenWelcome(seen: Boolean) {
        dataStore.edit { it[KEY_HAS_SEEN_WELCOME] = seen }
    }

    /** Whether the contacts-permission priming has already been offered at compose (shown once). */
    val hasPrimedContacts: Flow<Boolean> = dataStore.data.map { it[KEY_HAS_PRIMED_CONTACTS] ?: false }

    suspend fun setHasPrimedContacts(primed: Boolean) {
        dataStore.edit { it[KEY_HAS_PRIMED_CONTACTS] = primed }
    }

    /** Whether to remove tracking query params (utm_*, fbclid, …) from tapped links (on by default). */
    val stripTrackingParams: Flow<Boolean> = dataStore.data.map { it[KEY_STRIP_TRACKING] ?: true }

    suspend fun setStripTrackingParams(enabled: Boolean) {
        dataStore.edit { it[KEY_STRIP_TRACKING] = enabled }
    }

    /** Whether to show the destination and ask before opening a tapped link (off by default). */
    val confirmLinks: Flow<Boolean> = dataStore.data.map { it[KEY_CONFIRM_LINKS] ?: false }

    suspend fun setConfirmLinks(enabled: Boolean) {
        dataStore.edit { it[KEY_CONFIRM_LINKS] = enabled }
    }

    /**
     * Whether an opened message that asks for a read receipt (RFC 8098's
     */
    val askReadReceipt: Flow<Boolean> = dataStore.data.map(::askReadReceiptFrom)

    suspend fun setAskReadReceipt(enabled: Boolean) {
        dataStore.edit { it[KEY_ASK_READ_RECEIPT] = enabled }
    }

    /** Sender addresses (lower-cased) whose remote images load automatically. */
    val imageAllowlist: Flow<Set<String>> = dataStore.data.map { it[KEY_IMAGE_ALLOWLIST] ?: emptySet() }

    suspend fun setImageAllowed(sender: String, allowed: Boolean) {
        val key = sender.trim().lowercase()
        if (key.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_IMAGE_ALLOWLIST] ?: emptySet()
            prefs[KEY_IMAGE_ALLOWLIST] = if (allowed) current + key else current - key
        }
    }

    suspend fun clearImageAllowlist() {
        dataStore.edit { it.remove(KEY_IMAGE_ALLOWLIST) }
    }

    suspend fun setImageAllowlist(senders: Set<String>) {
        val cleaned = senders.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        dataStore.edit { it[KEY_IMAGE_ALLOWLIST] = cleaned }
    }

    /** The RSS / Atom feed addresses the user subscribed to (#465). A set, like [imageAllowlist]:
     *  the same declared store as every other preference, so a subscription survives restarts. */
    val rssFeeds: Flow<Set<String>> = dataStore.data.map { it[KEY_RSS_FEEDS] ?: emptySet() }

    /** Subscribe to or unsubscribe from [url]. Blank or already-in-the-desired-state is a no-op. */
    suspend fun setRssSubscribed(url: String, subscribed: Boolean) {
        val key = url.trim()
        if (key.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_RSS_FEEDS] ?: emptySet()
            prefs[KEY_RSS_FEEDS] = if (subscribed) current + key else current - key
        }
    }

    /** The user-added Alert (RSS) Accounts (#1624): each a name + a feed address, stored as a set of
     *  line-safe records beside the default c3-notify account (which ships from build.json::alerts_rss
     *  and is not stored here). The same declared store as every other preference. */
    val rssAccounts: Flow<List<app.sterna.core.data.rss.StoredAlertRssAccount>> =
        dataStore.data.map { prefs ->
            (prefs[KEY_RSS_ACCOUNTS] ?: emptySet())
                .mapNotNull { app.sterna.core.data.rss.StoredAlertRssAccount.parse(it) }
                .sortedBy { it.name.lowercase() }
        }

    /** Add a user Alert (RSS) account. Blank address is a no-op; a duplicate address replaces its name. */
    suspend fun addRssAccount(name: String, url: String) {
        val record = app.sterna.core.data.rss.StoredAlertRssAccount(name.trim(), url.trim())
        if (record.url.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_RSS_ACCOUNTS] ?: emptySet()
            val withoutSameUrl = current.filter {
                app.sterna.core.data.rss.StoredAlertRssAccount.parse(it)?.url != record.url
            }.toSet()
            prefs[KEY_RSS_ACCOUNTS] = withoutSameUrl + record.serialize()
        }
    }

    /** Remove the user Alert (RSS) account whose address is [url]. */
    suspend fun removeRssAccount(url: String) {
        val key = url.trim()
        if (key.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[KEY_RSS_ACCOUNTS] ?: emptySet()
            prefs[KEY_RSS_ACCOUNTS] = current.filter {
                app.sterna.core.data.rss.StoredAlertRssAccount.parse(it)?.url != key
            }.toSet()
        }
    }

    /** New-mail delivery mode; INSTANT (today's behavior) by default. */
    val deliveryMode: Flow<DeliveryMode> = dataStore.data.map { prefs ->
        prefs[KEY_DELIVERY_MODE]?.let { runCatching { DeliveryMode.valueOf(it) }.getOrNull() }
            ?: DeliveryMode.INSTANT
    }

    suspend fun setDeliveryMode(mode: DeliveryMode) {
        dataStore.edit { it[KEY_DELIVERY_MODE] = mode.name }
    }

    /** What a new-mail notification shows; sender + subject by default (Codeberg #25). */
    val notificationContent: Flow<NotificationContent> = dataStore.data.map(::notificationContentFrom)

    suspend fun setNotificationContent(mode: NotificationContent) {
        dataStore.edit { it[KEY_NOTIFICATION_CONTENT] = mode.name }
    }

    /** Captures every DataStore-backed preference into a portable [SettingsBackup]. */
    suspend fun snapshotBackup(): SettingsBackup = SettingsBackup(
        themeMode = themeMode.first().name,
        dynamicColor = dynamicColor.first(),
        listDensity = listDensity.first().name,
        previewLines = previewLines.first().name,
        swipeRight = swipeRightAction.first().name,
        swipeLeft = swipeLeftAction.first().name,
        sortOrder = sortOrder.first().name,
        contactSuggestions = contactSuggestions.first(),
        stripTracking = stripTrackingParams.first(),
        confirmLinks = confirmLinks.first(),
        askReadReceipt = askReadReceipt.first(),
        imageAllowlist = imageAllowlist.first().toList(),
        rssFeeds = rssFeeds.first().toList(),
        quietHoursEnabled = quietHoursEnabled.first(),
        quietHoursStart = quietHoursStart.first(),
        quietHoursEnd = quietHoursEnd.first(),
        conversationView = conversationView.first(),
        messageTextSize = messageTextSize.first().name,
        markReadOnDelete = markReadOnDelete.first(),
        markReadOnArchive = markReadOnArchive.first(),
        markReadOnMove = markReadOnMove.first(),
        unarchiveOnReply = unarchiveOnReply.first(),
        signatureOnReplies = signatureOnReplies.first(),
        signatureBelowQuote = signatureBelowQuote.first(),
        signatureDelimiter = signatureDelimiter.first(),
        replyBar = replyBar.first(),
        quotedDatesUtc = quotedDatesUtc.first(),
        pureBlack = pureBlack.first(),
        listMonogram = listMonogram.first(),
        deliveryMode = deliveryMode.first().name,
        notificationContent = notificationContent.first().name,
    )

    /** Applies the DataStore-backed fields of [backup]; unknown enum values are skipped. */
    suspend fun restoreBackup(backup: SettingsBackup) {
        backup.themeMode?.let { v -> runCatching { ThemeMode.valueOf(v) }.getOrNull()?.let { setThemeMode(it) } }
        backup.dynamicColor?.let { setDynamicColor(it) }
        backup.listDensity?.let { v -> runCatching { ListDensity.valueOf(v) }.getOrNull()?.let { setListDensity(it) } }
        backup.previewLines?.let { v -> runCatching { PreviewLines.valueOf(v) }.getOrNull()?.let { setPreviewLines(it) } }
        backup.swipeRight?.let { v -> runCatching { SwipeAction.valueOf(v) }.getOrNull()?.let { setSwipeRightAction(it) } }
        backup.swipeLeft?.let { v -> runCatching { SwipeAction.valueOf(v) }.getOrNull()?.let { setSwipeLeftAction(it) } }
        backup.sortOrder?.let { v -> runCatching { SortOrder.valueOf(v) }.getOrNull()?.let { setSortOrder(it) } }
        backup.contactSuggestions?.let { setContactSuggestions(it) }
        backup.stripTracking?.let { setStripTrackingParams(it) }
        backup.confirmLinks?.let { setConfirmLinks(it) }
        backup.askReadReceipt?.let { setAskReadReceipt(it) }
        backup.imageAllowlist?.let { setImageAllowlist(it.toSet()) }
        backup.rssFeeds?.let { feeds ->
            dataStore.edit { prefs -> prefs[KEY_RSS_FEEDS] = feeds.toSet() }
        }
        backup.quietHoursEnabled?.let { setQuietHoursEnabled(it) }
        backup.quietHoursStart?.let { setQuietHoursStart(it) }
        backup.quietHoursEnd?.let { setQuietHoursEnd(it) }
        backup.conversationView?.let { setConversationView(it) }
        backup.messageTextSize?.let { v -> runCatching { MessageTextSize.valueOf(v) }.getOrNull()?.let { setMessageTextSize(it) } }
        backup.markReadOnDelete?.let { setMarkReadOnDelete(it) }
        backup.markReadOnArchive?.let { setMarkReadOnArchive(it) }
        backup.markReadOnMove?.let { setMarkReadOnMove(it) }
        backup.unarchiveOnReply?.let { setUnarchiveOnReply(it) }
        backup.signatureOnReplies?.let { setSignatureOnReplies(it) }
        backup.signatureBelowQuote?.let { setSignatureBelowQuote(it) }
        backup.signatureDelimiter?.let { setSignatureDelimiter(it) }
        backup.replyBar?.let { setReplyBar(it) }
        backup.quotedDatesUtc?.let { setQuotedDatesUtc(it) }
        backup.pureBlack?.let { setPureBlack(it) }
        backup.listMonogram?.let { setListMonogram(it) }
        backup.deliveryMode?.let { v -> runCatching { DeliveryMode.valueOf(v) }.getOrNull()?.let { setDeliveryMode(it) } }
        notificationContentOrNull(backup.notificationContent)?.let { setNotificationContent(it) }
    }

    /**
     * Quiet hours — new-mail notifications still arrive but are posted silently during the nightly
     * window. Off by default.
     */
    val quietHoursEnabled: Flow<Boolean> = dataStore.data.map { it[KEY_QUIET_ENABLED] ?: false }

    suspend fun setQuietHoursEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_QUIET_ENABLED] = enabled }
    }

    /** Window start, minutes past midnight (default 22:00). */
    val quietHoursStart: Flow<Int> = dataStore.data.map { it[KEY_QUIET_START] ?: DEFAULT_QUIET_START }

    suspend fun setQuietHoursStart(minutes: Int) {
        dataStore.edit { it[KEY_QUIET_START] = minutes.coerceIn(0, 24 * 60 - 1) }
    }

    /** Window end, minutes past midnight (default 07:00). */
    val quietHoursEnd: Flow<Int> = dataStore.data.map { it[KEY_QUIET_END] ?: DEFAULT_QUIET_END }

    suspend fun setQuietHoursEnd(minutes: Int) {
        dataStore.edit { it[KEY_QUIET_END] = minutes.coerceIn(0, 24 * 60 - 1) }
    }

    private fun swipeFlow(key: Preferences.Key<String>, default: SwipeAction): Flow<SwipeAction> =
        dataStore.data.map { prefs ->
            prefs[key]?.let { runCatching { SwipeAction.valueOf(it) }.getOrNull() } ?: default
        }

    companion object {
        const val DEFAULT_QUIET_START = 22 * 60
        const val DEFAULT_QUIET_END = 7 * 60

        /**
         * True if [nowMinutes] falls in the quiet window `[start, end)`, handling windows that wrap
         * past midnight. An empty window (start == end) is never quiet.
         */
        fun isWithinQuietHours(nowMinutes: Int, start: Int, end: Int): Boolean = when {
            start == end -> false
            start < end -> nowMinutes in start until end
            else -> nowMinutes >= start || nowMinutes < end
        }

        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_APP_STYLE = stringPreferencesKey("app_style")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_LIST_DENSITY = stringPreferencesKey("list_density")
        private val KEY_PREVIEW_LINES = stringPreferencesKey("preview_lines")
        private val KEY_SWIPE_RIGHT = stringPreferencesKey("swipe_right")
        private val KEY_SWIPE_LEFT = stringPreferencesKey("swipe_left")
        private val KEY_SORT_ORDER = stringPreferencesKey("sort_order")
        private val KEY_CONVERSATION_VIEW = booleanPreferencesKey("conversation_view")
        private val KEY_MARK_READ_ON_DELETE = booleanPreferencesKey("mark_read_on_delete")
        private val KEY_MARK_READ_ON_ARCHIVE = booleanPreferencesKey("mark_read_on_archive")
        private val KEY_MARK_READ_ON_MOVE = booleanPreferencesKey("mark_read_on_move")
        private val KEY_UNARCHIVE_ON_REPLY = booleanPreferencesKey("unarchive_on_reply")
        private val KEY_SIGNATURE_ON_REPLIES = booleanPreferencesKey("signature_on_replies")
        private val KEY_SIGNATURE_BELOW_QUOTE = booleanPreferencesKey("signature_below_quote")
        private val KEY_SIGNATURE_DELIMITER = booleanPreferencesKey("signature_delimiter")
        private val KEY_PGP_PROVIDER = stringPreferencesKey("pgp_provider")
        private val KEY_MESSAGE_TEXT_SIZE = stringPreferencesKey("message_text_size")
        private val KEY_OFFLINE_WINDOW = stringPreferencesKey("offline_window")
        private val KEY_OFFLINE_ATTACHMENTS = stringPreferencesKey("offline_attachment_limit")
        private val KEY_OFFLINE_LAST_SYNC = longPreferencesKey("offline_last_sync")
        private val KEY_CONTACT_SUGGESTIONS = booleanPreferencesKey("contact_suggestions")
        private val KEY_HAS_SEEN_WELCOME = booleanPreferencesKey("has_seen_welcome")
        private val KEY_HAS_PRIMED_CONTACTS = booleanPreferencesKey("has_primed_contacts")
        private val KEY_STRIP_TRACKING = booleanPreferencesKey("strip_tracking_params")
        private val KEY_CONFIRM_LINKS = booleanPreferencesKey("confirm_links")
        private val KEY_IMAGE_ALLOWLIST = stringSetPreferencesKey("image_allowlist")
        private val KEY_RSS_FEEDS = stringSetPreferencesKey("rss_feeds")
        private val KEY_RSS_ACCOUNTS = stringSetPreferencesKey("rss_accounts")
        private val KEY_DELIVERY_MODE = stringPreferencesKey("delivery_mode")
        private val KEY_QUIET_ENABLED = booleanPreferencesKey("quiet_hours_enabled")
        private val KEY_QUIET_START = intPreferencesKey("quiet_hours_start")
        private val KEY_QUIET_END = intPreferencesKey("quiet_hours_end")
    }
}

// Two rules hold for every setting below. Each `KEY_*` is pinned BY NAME in its `…From`'s test:
// renaming one loses the choice of every user who touched that switch. And each `…From(prefs)`
// takes the whole [Preferences] rather than the stored value — see [replyBarFrom] for why.

/** The key the notification-content position is stored under. */
internal val KEY_NOTIFICATION_CONTENT = stringPreferencesKey("notification_content")

/**
 * What a new-mail notification shows when nobody has chosen a position, and what an unreadable
 */
internal val NOTIFICATION_CONTENT_DEFAULT = NotificationContent.SENDER_AND_SUBJECT

/**
 * A stored position name, or null when there is nothing usable to read. Null means "no answer", NOT
 */
internal fun notificationContentOrNull(stored: String?): NotificationContent? =
    stored?.let { name -> NotificationContent.entries.firstOrNull { it.name == name } }

/** The position a new-mail notification is posted at, read from the stored preferences. */
internal fun notificationContentFrom(prefs: Preferences): NotificationContent =
    notificationContentOrNull(prefs[KEY_NOTIFICATION_CONTENT]) ?: NOTIFICATION_CONTENT_DEFAULT

/** The key the reader's Reply/Forward bar switch is stored under. */
internal val KEY_REPLY_BAR = booleanPreferencesKey("reply_bar")

/**
 * What the reader shows when nobody has touched the switch: the bar, which is what the app has
 * always done and what the setting's subtitle promises (#63).
 */
const val REPLY_BAR_DEFAULT = true

/**
 * Whether the reader shows its bottom Reply/Forward bar, read from the stored preferences.
 */
internal fun replyBarFrom(prefs: Preferences): Boolean = prefs[KEY_REPLY_BAR] ?: REPLY_BAR_DEFAULT

/** The key the pure-black switch is stored under. */
internal val KEY_PURE_BLACK = booleanPreferencesKey("pure_black")

/**
 * What the dark theme does when nobody has touched the switch: keep the Pelagic surfaces (#117).
 * OFF deliberately — a default of `true` would repaint every dark-theme user's app on update.
 */
const val PURE_BLACK_DEFAULT = false

/** Whether the dark theme sits on a black background, read from the stored preferences. */
internal fun pureBlackFrom(prefs: Preferences): Boolean = prefs[KEY_PURE_BLACK] ?: PURE_BLACK_DEFAULT

/** The key the read-receipt switch is stored under. */
internal val KEY_ASK_READ_RECEIPT = booleanPreferencesKey("ask_read_receipt")

/**
 * Off, and this is the one value in this file that must never be flipped by a refactor: a read
 */
const val ASK_READ_RECEIPT_DEFAULT = false

/**
 * Whether an opened message may raise the read-receipt question, read from the stored preferences.
 */
internal fun askReadReceiptFrom(prefs: Preferences): Boolean =
    prefs[KEY_ASK_READ_RECEIPT] ?: ASK_READ_RECEIPT_DEFAULT

/** The key the sender-initials switch is stored under. */
internal val KEY_LIST_MONOGRAM = booleanPreferencesKey("list_monogram")

/**
 * What a message-list row does when nobody has touched the switch: show the sender's initials
 * (#144).
 */
const val LIST_MONOGRAM_DEFAULT = true

/** Whether message-list rows start with the sender's initials, read from the stored preferences. */
internal fun listMonogramFrom(prefs: Preferences): Boolean =
    prefs[KEY_LIST_MONOGRAM] ?: LIST_MONOGRAM_DEFAULT

/** The key the quoted-dates-in-UTC switch is stored under. */
internal val KEY_QUOTED_DATES_UTC = booleanPreferencesKey("quoted_dates_utc")

/**
 * What the composer writes when nobody has touched the switch: the device's local time (#120). OFF
 * deliberately — a default of `true` would change the dates every user's correspondents read.
 */
const val QUOTED_DATES_UTC_DEFAULT = false

/** Whether outgoing quoted/forwarded dates are written in UTC, read from the stored preferences. */
internal fun quotedDatesUtcFrom(prefs: Preferences): Boolean =
    prefs[KEY_QUOTED_DATES_UTC] ?: QUOTED_DATES_UTC_DEFAULT
