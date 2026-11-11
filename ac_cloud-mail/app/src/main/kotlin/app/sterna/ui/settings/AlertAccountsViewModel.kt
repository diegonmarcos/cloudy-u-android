package app.sterna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.rss.StoredAlertRssAccount
import app.sterna.ui.rss.DefaultAlertRssAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether an add request carried a usable feed address. */
enum class AddAlertAccountOutcome { ADDED, INVALID }

/**
 * Backs Configs ▸ Alert (RSS) accounts (#1624): the built-in default account (c3-notify) shown
 * alongside the user's own added RSS accounts, which live in the app's ordinary settings store.
 * Modeled on the fleet account+sync pattern — the default account is declared in code and its
 * channels are synced from the fleet at runtime (not baked); additional accounts are added here.
 */
class AlertAccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.container.settingsRepository

    /** The default account's display name — the c3-notify system, not removable. */
    val defaultAccountName: String = DefaultAlertRssAccount.NAME

    private val _defaultChannelCount = MutableStateFlow(0)

    /** How many channels the default account synced from the fleet; 0 until the sync answers. */
    val defaultChannelCount: StateFlow<Int> = _defaultChannelCount.asStateFlow()

    /** The user-added accounts, live from the settings store. */
    val userAccounts: StateFlow<List<StoredAlertRssAccount>> = settings.rssAccounts.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    init {
        viewModelScope.launch {
            _defaultChannelCount.value = DefaultAlertRssAccount.syncChannels().size
        }
    }

    /** Add a user account. [INVALID] when [url] is not an http(s) feed address — the caller says the
     *  sentence, it is never dropped in silence. */
    fun add(name: String, url: String): AddAlertAccountOutcome {
        if (!app.sterna.core.data.rss.isFeedAddress(url)) return AddAlertAccountOutcome.INVALID
        viewModelScope.launch { settings.addRssAccount(name, url) }
        return AddAlertAccountOutcome.ADDED
    }

    /** Remove the user account whose feed address is [url]. */
    fun remove(url: String) {
        viewModelScope.launch { settings.removeRssAccount(url) }
    }
}
