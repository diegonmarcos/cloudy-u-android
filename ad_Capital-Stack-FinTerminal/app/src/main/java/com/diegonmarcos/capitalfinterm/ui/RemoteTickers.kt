package com.diegonmarcos.capitalfinterm.ui

import com.diegonmarcos.capitalfinterm.FinSearchIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The remote symbol list of the search (#1370), fetched once per process off the main thread; a failed fetch is retried by the next query. */
object RemoteTickers {
    @Volatile private var cached: List<Pair<String, String>>? = null

    suspend fun get(fetch: () -> String): List<Pair<String, String>> {
        cached?.let { return it }
        return withContext(Dispatchers.IO) { FinSearchIndex.parseTickers(fetch()).also { if (it.isNotEmpty()) cached = it } }
    }
}
