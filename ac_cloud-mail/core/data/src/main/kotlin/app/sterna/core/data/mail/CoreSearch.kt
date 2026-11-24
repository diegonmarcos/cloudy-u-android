package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Email

/**
 * #1837 phase 3: the rule of [MailRepository.searchIndexOrCore], pure so a test can drive it with a fake core. The Room index's
 * [hits] win whenever there are any; only an empty answer asks the core ([coreIds] per account, null = core off or failed) and
 * loads the rows ([load]). Any failure keeps [hits] (empty): the answer the old path gave.
 */
internal suspend fun searchWithCoreFallback(
    hits: List<Email>,
    accountIds: List<String>,
    limit: Int,
    coreIds: suspend (String) -> List<String>?,
    load: suspend (String, List<String>) -> List<Email>,
): List<Email> {
    if (hits.isNotEmpty()) return hits
    return runCatching {
        accountIds.flatMap { acc ->
            coreIds(acc).orEmpty().chunked(500).flatMap { chunk -> load(acc, chunk) }
        }.take(limit)
    }.getOrDefault(hits)
}
