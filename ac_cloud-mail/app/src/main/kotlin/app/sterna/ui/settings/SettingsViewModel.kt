package app.sterna.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.account.K9SettingsImporter
import app.sterna.core.data.settings.LIST_MONOGRAM_DEFAULT
import app.sterna.core.data.settings.ListDensity
import app.sterna.core.data.settings.MessageTextSize
import app.sterna.core.data.settings.QUOTED_DATES_UTC_DEFAULT
import app.sterna.core.data.settings.PreviewLines
import app.sterna.core.data.settings.PURE_BLACK_DEFAULT
import app.sterna.core.data.settings.REPLY_BAR_DEFAULT
import app.sterna.core.data.settings.SettingsBackup
import app.sterna.core.data.settings.SettingsBackupCodec
import app.sterna.core.data.settings.SwipeAction
import app.sterna.core.data.settings.AppStyle
import app.sterna.core.data.settings.ThemeMode
import app.sterna.core.data.settings.DeliveryMode
import app.sterna.core.data.settings.NotificationContent
import app.sterna.push.NewMailNotifier
import app.sterna.push.PushController
import app.sterna.security.canAuthenticate
import app.sterna.widget.RecentMailWidgetDraw
import app.sterna.widget.UnreadWidgetDraw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val appLock = application.container.appLock
    private val settings = application.container.settingsRepository

    private val _pushAllAccounts = MutableStateFlow(store.pushAllAccounts())
    val pushAllAccounts = _pushAllAccounts.asStateFlow()

    private val _appLock = MutableStateFlow(store.appLockEnabled())
    val appLockEnabled = _appLock.asStateFlow()

    /** Set when the user tries to enable app lock but no biometric / screen lock exists. */
    private val _appLockUnavailable = MutableStateFlow(false)
    val appLockUnavailable = _appLockUnavailable.asStateFlow()

    val themeMode = settings.themeMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ThemeMode.SYSTEM,
    )

    val appStyle = settings.appStyle.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppStyle.CLOUD_DARK,
    )

    fun setAppStyle(style: AppStyle) {
        viewModelScope.launch { settings.setAppStyle(style) }
    }

    val dynamicColor = settings.dynamicColor.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    val listDensity = settings.listDensity.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ListDensity.NORMAL,
    )

    val previewLines = settings.previewLines.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PreviewLines.ONE,
    )

    // PURE_BLACK_DEFAULT, not a literal: a second copy would disagree with the repository for as
    // long as DataStore takes to answer.
    val pureBlack = settings.pureBlack.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PURE_BLACK_DEFAULT,
    )

    /**
     * Both home-screen cells take their colours from this setting, not from the system's night
     */
    fun setPureBlack(enabled: Boolean) {
        viewModelScope.launch {
            settings.setPureBlack(enabled)
            RecentMailWidgetDraw.forget()
            RecentMailWidgetDraw.refresh(getApplication())
            UnreadWidgetDraw.refresh(getApplication())
        }
    }

    // LIST_MONOGRAM_DEFAULT, not a literal, for the reason given at listDensity above.
    val listMonogram = settings.listMonogram.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LIST_MONOGRAM_DEFAULT,
    )

    /** The latest-messages home-screen cell carries this switch too (#112), and its redraw
     *  triggers are all fed by mail, so without these calls the badges stay until mail arrives.
     * Order as at [setPureBlack]. No `UnreadWidgetDraw.refresh` here: the counter draws no
     *  badge, so waking it would read the store and Room for nothing. */
    fun setListMonogram(enabled: Boolean) {
        viewModelScope.launch {
            settings.setListMonogram(enabled)
            RecentMailWidgetDraw.forget()
            RecentMailWidgetDraw.refresh(getApplication())
        }
    }

    val swipeRight = settings.swipeRightAction.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SwipeAction.TOGGLE_READ,
    )

    val swipeLeft = settings.swipeLeftAction.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SwipeAction.DELETE,
    )

    val contactSuggestions = settings.contactSuggestions.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setContactSuggestions(value: Boolean) {
        viewModelScope.launch { settings.setContactSuggestions(value) }
    }

    val stripTrackingParams = settings.stripTrackingParams.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setStripTrackingParams(value: Boolean) {
        viewModelScope.launch { settings.setStripTrackingParams(value) }
    }

    /** #1837 Load remote images automatically: on by default (the first frame is the same definition the stored value is read against). */
    val remoteImages = settings.remoteImages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = app.sterna.core.data.settings.REMOTE_IMAGES_DEFAULT,
    )

    fun setRemoteImages(value: Boolean) {
        viewModelScope.launch { settings.setRemoteImages(value) }
    }

    val confirmLinks = settings.confirmLinks.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setConfirmLinks(value: Boolean) {
        viewModelScope.launch { settings.setConfirmLinks(value) }
    }

    /** The read-receipt switch (#148): whether an opened message may ask whether to answer the
     * sender who requested one. No third position: "always send" is not a setting this app has. */
    val askReadReceipt = settings.askReadReceipt.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setAskReadReceipt(value: Boolean) {
        viewModelScope.launch { settings.setAskReadReceipt(value) }
    }

    val imageAllowlist = settings.imageAllowlist.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptySet(),
    )

    fun clearImageAllowlist() {
        viewModelScope.launch { settings.clearImageAllowlist() }
    }

    fun setImageAllowed(sender: String, allowed: Boolean) {
        viewModelScope.launch { settings.setImageAllowed(sender, allowed) }
    }

    /** Everything the notifications screen shows — delivery mode (#17), notification content (#25)
     * and quiet hours — as one value, null until DataStore has answered for all five. Not five
     *  `stateIn`s seeded with defaults: a tap landing in that first frame writes the default over
     *  the stored choice (#57). */
    val notificationSettings: StateFlow<NotificationSettingsState?> = notificationSettingsState(
        deliveryMode = settings.deliveryMode,
        notificationContent = settings.notificationContent,
        quietHoursEnabled = settings.quietHoursEnabled,
        quietHoursStart = settings.quietHoursStart,
        quietHoursEnd = settings.quietHoursEnd,
    ).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    fun setDeliveryMode(mode: DeliveryMode) {
        viewModelScope.launch {
            settings.setDeliveryMode(mode)
            // Re-arm with the new outcome (stops or restarts the foreground service).
            PushController.apply(getApplication(), userInitiated = true)
        }
    }

    fun setNotificationContent(mode: NotificationContent) {
        viewModelScope.launch {
            settings.setNotificationContent(mode)
            // As at setAppLock: this setting governs the home-screen widget too, and a cell already
            // drawn does not re-read it.
            RecentMailWidgetDraw.forget()
            RecentMailWidgetDraw.refresh(getApplication())
        }
    }

    fun setPushAllAccounts(value: Boolean) {
        store.setPushAllAccounts(value)
        _pushAllAccounts.value = value
        if (value) {
            // Accounts (re)entering the watched scope kept frozen baselines while out of it;
            // diffing those would burst stale notifications, so drop them and reseed silently.
            store.allCredentials().filter { it.id != store.currentId() }
                .forEach { NewMailNotifier.clear(getApplication(), it.id) }
        }
        PushController.apply(getApplication(), userInitiated = true)
    }

    fun setQuietHoursEnabled(value: Boolean) {
        viewModelScope.launch { settings.setQuietHoursEnabled(value) }
    }

    fun setQuietHoursStart(minutes: Int) {
        viewModelScope.launch { settings.setQuietHoursStart(minutes) }
    }

    fun setQuietHoursEnd(minutes: Int) {
        viewModelScope.launch { settings.setQuietHoursEnd(minutes) }
    }

    /** Write a JSON snapshot of preferences and account configuration, never credentials, to [uri].
     *  [onResult] is invoked on the main thread. */
    fun exportSettings(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                val backup = settings.snapshotBackup().copy(
                    pushAllAccounts = store.pushAllAccounts(),
                    language = currentAppLanguage().tag,
                    accounts = store.accountsForBackup(),
                )
                val text = SettingsBackupCodec.encode(backup)
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(text.toByteArray())
                    } ?: error("no output stream")
                }
            }.isSuccess
            onResult(ok)
        }
    }

    /** Read a backup file from [uri] and apply it. [onResult] reports success plus how many
     *  accounts were newly added; imported accounts have no password and must be signed into.
     *  [onLanguageChanged] fires only when the language differs, so the caller can recreate the
     *  activity to load the new strings. */
    fun importSettings(
        uri: Uri,
        onResult: (ok: Boolean, accountsAdded: Int) -> Unit,
        onLanguageChanged: (AppLanguage) -> Unit,
    ) {
        viewModelScope.launch {
            val backup: SettingsBackup? = runCatching {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    } ?: error("no input stream")
                }
                SettingsBackupCodec.decode(text)
            }.getOrNull()
            // Reject a file that is unparseable, or that parses but carries no recognizable backup
            // fields; the caller shows an error and stays on its menu.
            if (backup == null || !backup.isPlausible()) {
                onResult(false, 0)
                return@launch
            }
            settings.restoreBackup(backup)
            backup.pushAllAccounts?.let { setPushAllAccounts(it) }
            _pushAllAccounts.value = store.pushAllAccounts()
            val accountsAdded = backup.accounts?.let { store.importAccounts(it) } ?: 0
            onResult(true, accountsAdded)
            backup.language?.let { tag ->
                val target = AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.SYSTEM
                if (target != currentAppLanguage()) onLanguageChanged(target)
            }
        }
    }

    /** Parse a K-9 / Thunderbird `.k9s` export and import its inert accounts. [onResult] reports
     *  success, how many were added, how many skipped (POP3 / unsupported), and how many got a
     *  guessed connection security the user should check. Imported accounts have no credentials. */
    fun importK9Settings(uri: Uri, onResult: (ok: Boolean, added: Int, skipped: Int, unverified: Int) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                        K9SettingsImporter.parse(it)
                    } ?: error("no input stream")
                }
            }.getOrNull()
            if (result == null) { onResult(false, 0, 0, 0); return@launch }
            val added = store.importAccounts(result.accounts)
            onResult(true, added, result.skipped.size, result.securityUnverified.size)
        }
    }

    fun setAppLock(value: Boolean) {
        if (value && !canAuthenticate(getApplication())) {
            _appLockUnavailable.value = true
            _appLock.value = false
            return
        }
        _appLockUnavailable.value = false
        appLock.setEnabled(value)
        _appLock.value = value
        // The home screen has no periodic update by design, so without this the latest-messages
        // widget goes on listing senders and subjects until mail next arrives. SECURITY.md promises
        viewModelScope.launch {
            RecentMailWidgetDraw.forget()
            RecentMailWidgetDraw.refresh(getApplication())
            UnreadWidgetDraw.refresh(getApplication())
        }
    }

    /** Redraws both cells: awaited write, then `forget()`, then the two refreshes ([setPureBlack]). */
    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            settings.setDynamicColor(enabled)
            RecentMailWidgetDraw.forget()
            RecentMailWidgetDraw.refresh(getApplication())
            UnreadWidgetDraw.refresh(getApplication())
        }
    }

    /** Redraws both cells: awaited write, then `forget()`, then the two refreshes ([setPureBlack]). */
    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            settings.setThemeMode(mode)
            RecentMailWidgetDraw.forget()
            RecentMailWidgetDraw.refresh(getApplication())
            UnreadWidgetDraw.refresh(getApplication())
        }
    }

    fun setListDensity(density: ListDensity) {
        viewModelScope.launch { settings.setListDensity(density) }
    }

    fun setPreviewLines(value: PreviewLines) {
        viewModelScope.launch { settings.setPreviewLines(value) }
    }

    fun setSwipeRight(action: SwipeAction) {
        viewModelScope.launch { settings.setSwipeRightAction(action) }
    }

    fun setSwipeLeft(action: SwipeAction) {
        viewModelScope.launch { settings.setSwipeLeftAction(action) }
    }

    val conversationView = settings.conversationView.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setConversationView(enabled: Boolean) {
        viewModelScope.launch { settings.setConversationView(enabled) }
    }

    val markReadOnDelete = settings.markReadOnDelete.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setMarkReadOnDelete(enabled: Boolean) {
        viewModelScope.launch { settings.setMarkReadOnDelete(enabled) }
    }

    val markReadOnArchive = settings.markReadOnArchive.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setMarkReadOnArchive(enabled: Boolean) {
        viewModelScope.launch { settings.setMarkReadOnArchive(enabled) }
    }

    val markReadOnMove = settings.markReadOnMove.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setMarkReadOnMove(enabled: Boolean) {
        viewModelScope.launch { settings.setMarkReadOnMove(enabled) }
    }

    val unarchiveOnReply = settings.unarchiveOnReply.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setUnarchiveOnReply(enabled: Boolean) {
        viewModelScope.launch { settings.setUnarchiveOnReply(enabled) }
    }

    val signatureOnReplies = settings.signatureOnReplies.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setSignatureOnReplies(enabled: Boolean) {
        viewModelScope.launch { settings.setSignatureOnReplies(enabled) }
    }

    val signatureBelowQuote = settings.signatureBelowQuote.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = false,
    )

    fun setSignatureBelowQuote(enabled: Boolean) {
        viewModelScope.launch { settings.setSignatureBelowQuote(enabled) }
    }

    val signatureDelimiter = settings.signatureDelimiter.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = true,
    )

    fun setSignatureDelimiter(enabled: Boolean) {
        viewModelScope.launch { settings.setSignatureDelimiter(enabled) }
    }

    // REPLY_BAR_DEFAULT, not a literal: a second copy of the default is free to disagree with the
    // repository for as long as the store takes to open (#63).
    val replyBar = settings.replyBar.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = REPLY_BAR_DEFAULT,
    )

    fun setReplyBar(enabled: Boolean) {
        viewModelScope.launch { settings.setReplyBar(enabled) }
    }

    // QUOTED_DATES_UTC_DEFAULT, not a literal, as at replyBar above (#120). The composer never
    // reads this state: it suspends on the repository flow itself.
    val quotedDatesUtc = settings.quotedDatesUtc.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = QUOTED_DATES_UTC_DEFAULT,
    )

    fun setQuotedDatesUtc(enabled: Boolean) {
        viewModelScope.launch { settings.setQuotedDatesUtc(enabled) }
    }

    val messageTextSize = settings.messageTextSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MessageTextSize.NORMAL,
    )

    fun setMessageTextSize(size: MessageTextSize) {
        viewModelScope.launch { settings.setMessageTextSize(size) }
    }

    val offlineWindow = settings.offlineWindow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = app.sterna.core.data.mail.OfflineWindow.DEFAULT,
    )

    fun setOfflineWindow(window: app.sterna.core.data.mail.OfflineWindow) {
        viewModelScope.launch {
            settings.setOfflineWindow(window)
            app.sterna.mail.OfflineSyncWorker.runNow(getApplication())
        }
    }

    /** #1952 "Body cache limit" and the usage shown next to it (bytes, bodies). */
    val bodyCacheLimit = settings.bodyCacheLimit.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = app.sterna.core.data.mail.BodyCacheLimit.DEFAULT,
    )

    fun setBodyCacheLimit(limit: app.sterna.core.data.mail.BodyCacheLimit) {
        viewModelScope.launch {
            settings.setBodyCacheLimit(limit)
            app.sterna.mail.OfflineSyncWorker.runNow(getApplication())
            refreshBodyCacheUsage()
        }
    }

    private val _bodyCacheUsage = MutableStateFlow(0L to 0)
    val bodyCacheUsage = _bodyCacheUsage.asStateFlow()

    fun refreshBodyCacheUsage() {
        viewModelScope.launch {
            _bodyCacheUsage.value = runCatching { getApplication<Application>().container.mailRepository.bodyCacheUsage() }.getOrDefault(0L to 0)
        }
    }

    val offlineAttachmentLimit = settings.offlineAttachmentLimit.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = app.sterna.core.data.mail.OfflineAttachmentLimit.DEFAULT,
    )

    fun setOfflineAttachmentLimit(limit: app.sterna.core.data.mail.OfflineAttachmentLimit) {
        viewModelScope.launch {
            settings.setOfflineAttachmentLimit(limit)
            app.sterna.mail.OfflineSyncWorker.runNow(getApplication())
        }
    }

    /** "Download attachments" (#1768): Always / Wi-Fi only / Off; Wi-Fi only until the user changes it. */
    val attachmentPolicy = settings.attachmentPolicy.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = app.sterna.core.data.mail.AttachmentPolicy.DEFAULT,
    )

    fun setAttachmentPolicy(policy: app.sterna.core.data.mail.AttachmentPolicy) {
        viewModelScope.launch {
            settings.setAttachmentPolicy(policy)
            app.sterna.mail.OfflineSyncWorker.runNow(getApplication())
        }
    }

    /** Attachment bytes held offline per account (name to bytes), for the Configs line under the policy row. */
    private val _attachmentStorage = MutableStateFlow<List<Pair<String, Long>>>(emptyList())
    val attachmentStorage = _attachmentStorage.asStateFlow()

    fun refreshAttachmentStorage() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val container = getApplication<Application>().container
            _attachmentStorage.value = runCatching {
                container.accountStore.accounts().map { it.accountName.ifBlank { it.username } to container.mailRepository.offlineAttachmentBytes(it.id) }
            }.getOrDefault(emptyList())
        }
    }

    val offlineLastSync = settings.offlineLastSync.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0L,
    )

    private val _offlineCacheBytes = MutableStateFlow(0L)
    val offlineCacheBytes = _offlineCacheBytes.asStateFlow()

    fun refreshOfflineCacheSize() {
        viewModelScope.launch {
            _offlineCacheBytes.value = runCatching { getApplication<Application>().container.mailRepository.offlineCacheBytes() }.getOrDefault(0L)
        }
    }
}
