package app.sterna.ui.rss

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.rss.RssFetchFailure
import app.sterna.core.data.rss.RssFeed
import app.sterna.core.data.rss.RssFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the News screen can show for one subscribed feed. [url] is declared on the interface so
 *  list-keying and identity never depend on which variant is underneath. */
sealed interface RssFeedUi {
    val url: String

    data class Fetched(override val url: String, val feed: RssFeed) : RssFeedUi

    /** The fetch did not produce articles. Each reason maps to its own honest sentence. */
    data class Unavailable(override val url: String, val messageKey: RssMessage) : RssFeedUi
}

/** The user-facing reason a feed could not be shown; each maps to one string on screen. */
enum class RssMessage { FETCH_FAILED, HOST_UNREACHABLE, NOT_A_FEED, EMPTY }

/** Whether a subscribe request dropped a usable address. */
enum class RssSubscribeOutcome { ADDED, INVALID }

/**
 * Backs the News screen (#465): the subscribed feed addresses and what each one currently shows.
 * Fetching and parsing happen off the main thread through [RssFetcher]; the subscriptions live in
 * the app's ordinary settings store, so they survive restarts exactly like a theme choice.
 */
class RssViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = application.container.settingsRepository
    private val fetcher = RssFetcher()

    /** Every feed address the Alerts view shows (#1624): the default c3-notify account's channels
     *  (shipped out of the box through [DefaultAlertRssAccount]), the user's added Alert (RSS)
     *  accounts, and the legacy flat subscriptions — unioned, so a channel declared by the fleet and
     *  a feed the user pasted both appear. Live from the settings store, so it survives restarts. */
    val subscriptions: StateFlow<Set<String>> =
        combine(settings.rssFeeds, settings.rssAccounts) { legacy, accounts ->
            DefaultAlertRssAccount.feedUrls().toSet() + accounts.map { it.url }.toSet() + legacy
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DefaultAlertRssAccount.feedUrls().toSet(),
        )

    private val _feeds = MutableStateFlow<List<RssFeedUi>>(emptyList())

    /** Everything each subscription currently shows: a loaded feed, or the honest reason not. */
    val feeds: StateFlow<List<RssFeedUi>> = _feeds.asStateFlow()

    /** Start (or re-start) loading every subscribed feed, off the main thread, in sentinel order.
     *  The default c3-notify account's channels are SYNCED from the fleet first (not baked), then
     *  unioned with the user's accounts and the legacy subscriptions, so the Alerts view shows every
     *  channel the fleet publishes beside anything the user added. */
    fun refresh() {
        viewModelScope.launch {
            val defaults = DefaultAlertRssAccount.syncChannels().map { it.url }
            val urls = (defaults.toSet() + subscriptions.value).toList().sorted()
            if (urls.isEmpty()) {
                _feeds.value = emptyList()
                return@launch
            }
            _feeds.value = urls.map { RssFeedUi.Unavailable(it, RssMessage.FETCH_FAILED) }
            val loaded = urls.map { fetch(it) }
            _feeds.value = loaded
        }
    }

    /** Add [raw] if it is a usable feed address, then (re)load. [ADDED] once stored; [INVALID]
     *  when [raw] is not an http(s) address — the caller says that sentence, never a silent drop. */
    fun subscribe(raw: String): RssSubscribeOutcome {
        if (!isFeedAddress(raw)) return RssSubscribeOutcome.INVALID
        viewModelScope.launch {
            settings.setRssSubscribed(raw.trim(), subscribed = true)
            refresh()
        }
        return RssSubscribeOutcome.ADDED
    }

    /** Stop following [url], then (re)load. */
    fun unsubscribe(url: String) {
        viewModelScope.launch {
            settings.setRssSubscribed(url, subscribed = false)
            _feeds.value = _feeds.value.filter { it !is RssFeedUi.Unavailable || it.url != url }
        }
    }

    /** Fetch one subscribed feed for display, off the main thread. */
    private suspend fun fetch(url: String): RssFeedUi = when (val result = fetcher.fetch(url)) {
        is app.sterna.core.data.rss.RssFetchResult.Loaded -> RssFeedUi.Fetched(url, result.feed)
        is app.sterna.core.data.rss.RssFetchResult.Failed -> feedUiFor(result.reason, url)
    }

    /** A transport that died means the host was unreachable — its own sentence, telling the reader
     *  to check connectivity to THAT feed. An HTTP error is a "could not load". */
    private fun feedUiFor(reason: RssFetchFailure, url: String): RssFeedUi.Unavailable =
        RssFeedUi.Unavailable(
            url = url,
            messageKey = when (reason) {
                RssFetchFailure.UNREACHABLE -> RssMessage.HOST_UNREACHABLE
                RssFetchFailure.HTTP_ERROR -> RssMessage.FETCH_FAILED
                RssFetchFailure.NOT_A_FEED -> RssMessage.NOT_A_FEED
                RssFetchFailure.EMPTY -> RssMessage.EMPTY
            },
        )
}

/** The address gate the screen and the view model both use; one definition, no drift. */
internal fun isFeedAddress(raw: String): Boolean = app.sterna.core.data.rss.isFeedAddress(raw)