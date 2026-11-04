package com.diegonmarcos.cloudc3.pages

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The owner's PRIVATE data (the email identities on Infra > Domains > Public), loaded at run time and never in this
 * public repo. WHERE it lives is a declaration (build.json ui.private_sources: owner, repo, ref, path); the content is
 * the owner's private repo (the same store Cloud Me / Cloud Account read, #1438/#1445) fetched with the owner's GitHub
 * token, which is kept in an encrypted store on this phone. When no token or no network answers, a side-loaded copy
 * (external files dir, private/<id>.json) or the last good copy cached in app storage is used, and the page says which.
 * Tests and emulator fixtures hand in FAKE content only.
 */
object PrivateData {

    data class Source(val id: String, val owner: String, val repo: String, val ref: String, val path: String)

    data class Identity(val address: String, val label: String, val provider: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** ui.private_sources: { "<id>": { owner, repo, ref, path } }. An entry without repo or path is dropped. */
    fun parseSources(text: String): Map<String, Source> {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
        return o.mapNotNull { (id, v) ->
            val e = v as? JsonObject ?: return@mapNotNull null
            if (e.s("repo").isBlank() || e.s("path").isBlank()) return@mapNotNull null
            id to Source(id, e.s("owner"), e.s("repo"), e.s("ref").ifBlank { "main" }, e.s("path"))
        }.toMap()
    }

    /** The private file's `emails[]`: { address, label?, provider? }. Entries with no address are dropped. */
    fun parseIdentities(text: String): List<Identity> {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        return (o["emails"] as? JsonArray).orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            x.s("address").takeIf { it.isNotBlank() }?.let { Identity(it, x.s("label"), x.s("provider")) }
        }
    }

    /** Where a copy came from, for the page's one-line note. */
    enum class From(val text: String) { REPO("owner's private repo"), SIDELOADED("side-loaded copy"), CACHE("cached copy") }

    class Loaded(val identities: List<Identity>, val from: From)

    /** The raw bytes of [src] from the private repo (GitHub contents API, raw media type); throws with a one-line reason. */
    fun fetch(src: Source, token: String): String {
        val url = URL("https://api.github.com/repos/${src.owner}/${src.repo}/contents/${src.path}?ref=${src.ref}")
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github.raw")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "cloud-c3")
        }
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private const val PREFS = "c3_private"
    private const val KEY_TOKEN = "github_token"

    private fun prefs(ctx: Context) = SafeEncryptedPrefs.prefs(ctx.applicationContext, PREFS)
    fun hasToken(ctx: Context): Boolean = runCatching { !prefs(ctx).getString(KEY_TOKEN, "").isNullOrBlank() }.getOrDefault(false)
    fun saveToken(ctx: Context, token: String) { prefs(ctx).edit().putString(KEY_TOKEN, token.trim()).apply() }

    private fun cacheFile(ctx: Context, src: Source) = File(ctx.filesDir, "private/${src.id}.json")
    private fun sideFile(ctx: Context, src: Source) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "private/${src.id}.json")

    /** Live > side-loaded > cached. BLOCKS: call on IO. Returns the identities and where they came from, or throws the reason none could be read. */
    fun load(ctx: Context, src: Source, fetcher: (Source, String) -> String = ::fetch): Loaded {
        val token = runCatching { prefs(ctx).getString(KEY_TOKEN, "").orEmpty() }.getOrDefault("")
        var why = "no GitHub token on this phone"
        if (token.isNotBlank()) {
            try {
                val body = fetcher(src, token)
                cacheFile(ctx, src).apply { parentFile?.mkdirs() }.writeText(body)
                return Loaded(parseIdentities(body), From.REPO)
            } catch (e: Exception) { why = "${e.javaClass.simpleName}: ${e.message.orEmpty().lineSequence().first()}" }
        }
        sideFile(ctx, src).takeIf { it.isFile }?.let { return Loaded(parseIdentities(it.readText()), From.SIDELOADED) }
        cacheFile(ctx, src).takeIf { it.isFile }?.let { return Loaded(parseIdentities(it.readText()), From.CACHE) }
        error(why)
    }
}

/** What the Public page observes: the identities, where they came from, or why they are absent. */
class PrivateIdentitiesState {
    var items by mutableStateOf<List<PrivateData.Identity>>(emptyList())
    var note by mutableStateOf<String?>(null)
    var needsToken by mutableStateOf(false)

    suspend fun refresh(ctx: Context, src: PrivateData.Source?) {
        if (src == null) { note = "no private source is declared (build.json ui.private_sources)"; return }
        withContext(Dispatchers.IO) {
            runCatching { PrivateData.load(ctx, src) }
                .onSuccess { items = it.identities; note = "${it.identities.size} from the ${it.from.text}"; needsToken = !PrivateData.hasToken(ctx) }
                .onFailure { note = "unavailable: ${it.message.orEmpty()}"; needsToken = !PrivateData.hasToken(ctx) }
        }
    }
}
