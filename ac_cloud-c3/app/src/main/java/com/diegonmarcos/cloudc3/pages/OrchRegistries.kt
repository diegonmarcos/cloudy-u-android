package com.diegonmarcos.cloudc3.pages

import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.cloud.GitHubFeed
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * #1942 Orch > GH Releases and Orch > GHCR: per repo, the GitHub release table (tag, assets, size, updated) and the GHCR package
 * table (package, tags, visibility, updated). Both load LAZILY, one group at a time when its header is opened, so a cold page
 * costs no request (GitHub's anonymous quota is 60/h per IP; GitHubFeed caches each repo 15 min). Nothing here holds a token:
 * GHCR tags are read anonymously (ghcr.io/token, then /v2/<owner>/<img>/tags/list); a package the anonymous client cannot read
 * is shown as private with no tags, never as a failure. The package list is derived at run time from the declared containers' image fields plus cgc-db-<repo> per declared repo.
 */
object OrchRegistries {
    // Tables are drawn by OrchReleasesScreen / OrchGhcrScreen below; OrchRegistryFragment hosts them on the Orch tab (#1942).

    data class Pkg(val owner: String, val name: String, val repo: String)

    /**
     * The GHCR packages, DERIVED from data the app already carries (no new baked data, #1547): every `ghcr.io/<owner>/<image>` of the
     * declared containers (tag or digest dropped, one row per package) plus one `cgc-db-<repo>` package per declared repo.
     */
    fun packages(images: List<String>, repos: List<String>, owner: String = "diegonmarcos"): List<Pkg> {
        val out = LinkedHashMap<Pair<String, String>, Pkg>()
        images.filter { it.startsWith("ghcr.io/") }.forEach { im ->
            val ref = im.removePrefix("ghcr.io/").substringBefore("@")
            val name = ref.substringAfterLast('/')
            val clean = if (':' in name) ref.substringBeforeLast(':') else ref
            val o = clean.substringBefore('/', ""); val n = clean.substringAfter('/', "")
            if (o.isNotBlank() && n.isNotBlank()) out.putIfAbsent(o to n, Pkg(o, n, ""))
        }
        repos.forEach { r -> out.putIfAbsent(owner to "cgc-db-$r", Pkg(owner, "cgc-db-$r", r)) }
        return out.values.toList()
    }

    /** One release-table row: [assets] is the asset names, [sizeBytes] their total. */
    data class ReleaseRow(val tag: String, val assets: List<String>, val sizeBytes: Long, val updated: String)

    /** One GHCR-table row; [visibility] is public or private, [tags] empty for a private package. */
    data class GhcrRow(val pkg: String, val tags: List<String>, val visibility: String, val updated: String)

    sealed class Loaded<out T> {
        object Loading : Loaded<Nothing>()
        data class Ok<T>(val rows: List<T>, val note: String = "") : Loaded<T>()
    }

    /** Where the tables read from; the screens take it as a parameter so tests inject a fake feed. */
    interface Source {
        suspend fun releases(repo: String): GitHubFeed.Feed<GitHubFeed.Release>
        suspend fun ghcr(pkg: Pkg): GhcrRow
    }

    fun day(ms: Long): String = if (ms <= 0) "-" else java.time.Instant.ofEpochMilli(ms).toString().take(10)

    fun size(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    fun releaseRow(r: GitHubFeed.Release) = ReleaseRow(r.tag, r.assets.map { it.name }, r.assets.sumOf { it.size }, day(r.tsMillis))

    fun releaseNote(s: GitHubFeed.Status, n: Int) = when {
        s == GitHubFeed.Status.RATE_LIMITED -> "rate limited (60/h anonymous), showing cached rows"
        s == GitHubFeed.Status.UNREACHABLE -> "unreachable, showing cached rows"
        n == 0 -> "no releases"
        else -> ""
    }

    /** Group id and label of a package: its repo (cgc-db-<repo>) or the declared fleet images. */
    const val FLEET = "fleet"
    fun groupOf(p: Pkg) = p.repo.ifBlank { FLEET }

    object Tags {
        fun repo(r: String) = "orch_repo_$r"
        fun release(r: String, tag: String) = "orch_rel_${r}_$tag"
        fun group(g: String) = "orch_ghcr_group_$g"
        fun pkg(p: String) = "orch_ghcr_pkg_$p"
        fun note(id: String) = "orch_note_$id"
    }

    /** The live source: GitHubFeed for releases, an anonymous GHCR client for packages. */
    class Live(private val ctx: android.content.Context, private val owner: String = "diegonmarcos") : Source {
        override suspend fun releases(repo: String) = GitHubFeed.releases(ctx, owner, repo)
        override suspend fun ghcr(pkg: Pkg) = GhcrClient.row(pkg)
    }
}

/** Anonymous GHCR reads. No credential of any kind: the token endpoint hands a pull token for a public package and nothing usable for a private one. */
object GhcrClient {
    private const val HOST = "https://ghcr.io"
    private val cache = HashMap<String, Pair<Long, OrchRegistries.GhcrRow>>()
    private const val TTL = 900_000L

    suspend fun row(p: OrchRegistries.Pkg): OrchRegistries.GhcrRow {
        val key = "${p.owner}/${p.name}"
        cache[key]?.let { if (System.currentTimeMillis() - it.first < TTL) return it.second }
        val r = withContext(Dispatchers.IO) { runCatching { read(p) }.getOrElse { OrchRegistries.GhcrRow(p.name, emptyList(), "private", "-") } }
        cache[key] = System.currentTimeMillis() to r
        return r
    }

    private fun get(url: String, bearer: String? = null, accept: String? = null): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 5_000; c.readTimeout = 5_000
            c.setRequestProperty("User-Agent", "Cloud-C3")
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (accept != null) c.setRequestProperty("Accept", accept)
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally { c.disconnect() }
    }

    private fun read(p: OrchRegistries.Pkg): OrchRegistries.GhcrRow {
        val priv = OrchRegistries.GhcrRow(p.name, emptyList(), "private", "-")
        val scope = "repository:${p.owner}/${p.name}:pull"
        val token = get("$HOST/token?service=ghcr.io&scope=$scope")?.let { JSONObject(it).optString("token") }.orEmpty()
        if (token.isBlank()) return priv
        val list = get("$HOST/v2/${p.owner}/${p.name}/tags/list?n=100", token) ?: return priv
        val arr = JSONObject(list).optJSONArray("tags") ?: return priv
        val tags = (0 until arr.length()).map { arr.getString(it) }
        val newest = if ("latest" in tags) "latest" else tags.lastOrNull()
        return OrchRegistries.GhcrRow(p.name, tags, "public", newest?.let { created(p, it, token) } ?: "-")
    }

    /** The image config's `created` of [tag]: manifest (first platform of an index), then its config blob. Best effort; "-" when any step is refused. */
    private fun created(p: OrchRegistries.Pkg, tag: String, token: String): String = runCatching {
        val accept = "application/vnd.oci.image.index.v1+json,application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json,application/vnd.docker.distribution.manifest.list.v2+json"
        val base = "$HOST/v2/${p.owner}/${p.name}"
        var m = JSONObject(get("$base/manifests/$tag", token, accept) ?: return "-")
        m.optJSONArray("manifests")?.let { m = JSONObject(get("$base/manifests/${it.getJSONObject(0).getString("digest")}", token, accept) ?: return "-") }
        val cfg = m.getJSONObject("config").getString("digest")
        val c = JSONObject(get("$base/blobs/$cfg", token) ?: return "-")
        c.optString("created").take(10).ifBlank { "-" }
    }.getOrDefault("-")
}

private val CELL = 12.sp

@Composable
private fun Cell(text: String, weight: Float, tag: String? = null, strong: Boolean = false) {
    val p = LocalKitPalette.current
    Text(text, color = if (strong) p.textPrimary else p.textSecondary, fontSize = CELL,
        modifier = Modifier.padding(2.dp).let { if (tag != null) it.testTag(tag) else it })
}

/** Orch > GH Releases: every declared repo as a header; opening one loads its release table (tag | assets | size | updated). */
@Composable
fun OrchReleasesScreen(repos: List<String>, source: OrchRegistries.Source, modifier: Modifier = Modifier) {
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val data = remember { mutableStateMapOf<String, OrchRegistries.Loaded<OrchRegistries.ReleaseRow>>() }
    val p = LocalKitPalette.current
    open.filterValues { it }.keys.forEach { repo ->
        LaunchedEffect(repo) {
            if (data[repo] == null) {
                val f = source.releases(repo)
                data[repo] = OrchRegistries.Loaded.Ok(f.items.map(OrchRegistries::releaseRow), OrchRegistries.releaseNote(f.status, f.items.size))
            }
        }
    }
    LazyColumn(modifier.fillMaxWidth().testTag("orch_releases")) {
        items(repos, key = { it }) { repo ->
            Text((if (open[repo] == true) "v " else "> ") + repo, color = p.accent, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { open[repo] = open[repo] != true }.padding(8.dp).testTag(OrchRegistries.Tags.repo(repo)))
            if (open[repo] == true) when (val d = data[repo]) {
                null, OrchRegistries.Loaded.Loading -> Cell("loading", 1f)
                is OrchRegistries.Loaded.Ok -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), Arrangement.spacedBy(8.dp)) {
                        Cell("Tag", 1f, strong = true); Cell("Assets", 1f, strong = true); Cell("Size", 1f, strong = true); Cell("Updated", 1f, strong = true)
                    }
                    if (d.note.isNotBlank()) Cell(d.note, 1f, OrchRegistries.Tags.note(repo))
                    d.rows.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag(OrchRegistries.Tags.release(repo, r.tag)), Arrangement.spacedBy(8.dp)) {
                            Cell(r.tag, 1f, strong = true)
                            Cell("${r.assets.size}: ${r.assets.joinToString(", ")}", 1f)
                            Cell(OrchRegistries.size(r.sizeBytes), 1f)
                            Cell(r.updated, 1f)
                        }
                    }
                }
            }
        }
    }
}

/** Orch > GHCR: the declared packages grouped by repo (cgc-db-<repo>) and the fleet images; opening a group loads each package's tags anonymously (package | tags | visibility | updated). */
@Composable
fun OrchGhcrScreen(packages: List<OrchRegistries.Pkg>, source: OrchRegistries.Source, modifier: Modifier = Modifier) {
    val groups = remember(packages) { packages.groupBy(OrchRegistries::groupOf).toList().sortedBy { (g, _) -> if (g == OrchRegistries.FLEET) "" else g } }
    val open = remember { mutableStateMapOf<String, Boolean>() }
    val data = remember { mutableStateMapOf<String, OrchRegistries.GhcrRow>() }
    val p = LocalKitPalette.current
    groups.filter { open[it.first] == true }.forEach { (g, pk) ->
        LaunchedEffect(g) { pk.forEach { if (data[it.name] == null) data[it.name] = source.ghcr(it) } }
    }
    LazyColumn(modifier.fillMaxWidth().testTag("orch_ghcr")) {
        items(groups, key = { it.first }) { (g, pk) ->
            Text((if (open[g] == true) "v " else "> ") + (if (g == OrchRegistries.FLEET) "Fleet images" else g) + " (${pk.size})", color = p.accent, fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth().clickable { open[g] = open[g] != true }.padding(8.dp).testTag(OrchRegistries.Tags.group(g)))
            if (open[g] == true) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), Arrangement.spacedBy(8.dp)) {
                    Cell("Package", 1f, strong = true); Cell("Tags", 1f, strong = true); Cell("Visibility", 1f, strong = true); Cell("Updated", 1f, strong = true)
                }
                pk.forEach { k ->
                    val r = data[k.name]
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag(OrchRegistries.Tags.pkg(k.name)), Arrangement.spacedBy(8.dp)) {
                        Cell(k.name, 1f, strong = true)
                        Cell(if (r == null) "loading" else r.tags.joinToString(", "), 1f)
                        Cell(r?.visibility.orEmpty(), 1f)
                        Cell(r?.updated.orEmpty(), 1f)
                    }
                }
            }
        }
    }
}

/** The Orch > GH Releases / GHCR page: the live source over the declared repos and packages. */
class OrchRegistryFragment : KitComposeFragment() {
    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val src = remember { OrchRegistries.Live(requireContext().applicationContext) }
        if (arguments?.getString(ARG_KIND) == KIND_GHCR) OrchGhcrScreen(OrchRegistries.packages(ContainersSource.current().entries.map { it.image }, Declarations.containerProjects.repos.filter { it.role == "repo" }.map { it.name }), src)
        else OrchReleasesScreen(Declarations.containerProjects.repos.map { it.name }, src)
    }

    companion object {
        private const val ARG_KIND = "kind"
        const val KIND_RELEASES = "releases"
        const val KIND_GHCR = "ghcr"
        fun newInstance(kind: String) = OrchRegistryFragment().apply { arguments = Bundle().apply { putString(ARG_KIND, kind) } }
    }
}
