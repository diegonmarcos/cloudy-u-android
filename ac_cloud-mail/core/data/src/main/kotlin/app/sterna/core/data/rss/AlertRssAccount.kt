package app.sterna.core.data.rss

/**
 * One Alert (RSS) Account (#1624): a named source that contributes one or more feed addresses to the
 * Alerts view (the renamed News tab). Modeled on the fleet account+sync pattern and on Cloud-chat's
 * server-provided account — the default account and its channels come from the fleet declaration
 * (build.json::alerts_rss), not from the user.
 *
 * A [builtIn] account ships with the app (the c3-notify default) and is not removable; a user-added
 * account is stored under [app.sterna.core.data.settings.SettingsRepository.rssAccounts]. Either way
 * the account exposes a flat list of [AlertRssChannel] feed addresses that the Alerts reader fetches.
 */
data class AlertRssAccount(
    val id: String,
    val name: String,
    val channels: List<AlertRssChannel>,
    val builtIn: Boolean = false,
)

/** One feed address inside an account, with the human [name] to show before its articles load. */
data class AlertRssChannel(
    val name: String,
    val url: String,
)

/**
 * The storage record for ONE user-added Alert (RSS) account: a name and a single feed address. Kept
 * line-safe for the DataStore string-set store — the two fields are joined by [SEP] (a unit
 * separator, never valid in a name or a URL), so a record round-trips without a schema.
 */
data class StoredAlertRssAccount(val name: String, val url: String) {
    fun serialize(): String = name.replace(SEP, " ").trim() + SEP + url.trim()

    companion object {
        private const val SEP = "\u0001"

        /** Parse a stored record; null when it is blank or carries no address. */
        fun parse(raw: String): StoredAlertRssAccount? {
            val parts = raw.split(SEP, limit = 2)
            val url = parts.getOrNull(1)?.trim().orEmpty()
            if (url.isEmpty()) return null
            val name = parts.getOrNull(0)?.trim().orEmpty()
            return StoredAlertRssAccount(name.ifEmpty { url }, url)
        }
    }
}
