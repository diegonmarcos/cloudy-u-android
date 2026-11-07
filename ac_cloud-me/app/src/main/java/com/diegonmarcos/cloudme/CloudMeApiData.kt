package com.diegonmarcos.cloudme

import android.content.Context
import android.net.Uri
import com.diegonmarcos.superapp.fleetconfig.FleetHttpSeam
import com.diegonmarcos.superapp.fleetendpoints.android.FleetBoot
import com.diegonmarcos.superapp.fleetendpoints.android.FleetEndpointsClient
import com.diegonmarcos.superapp.fleetendpoints.android.FleetHttpInstall
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Cloud Me's personal data (wallet records, health, repo outline / roadmap / vault rows) is served by cloud-me-api from the
 * private repo cloud-me-pvt-configs (`fleet-data/1/personal/`), through its generic file route
 * `GET /me/repos/cloud-me_configs/file?path=...`. cloud-me-api verifies the fleet bearer itself, mesh or public.
 *
 * Chain per file: cloud-me-api (mesh first, then the c3-pvt public host with the fleet bearer, both chosen by the shared fleet
 * transport from the endpoint pairs Cloud Account publishes) -> the last good copy cached on the phone -> the baked asset.
 * The baked assets stay in the APK as the offline fallback; nothing is removed. Readers are synchronous, so [text] never
 * touches the network: [sync] (background, once per process start) refreshes the cache and the next read picks it up.
 */
object CloudMeApiData {
    const val SERVICE = "cloud-me-api"
    const val REPO = "cloud-me_configs"
    const val DIR = "fleet-data/1/personal"
    private const val CACHE = "cloud-me-api"

    /** One GET (url, headers) -> (status, body), or null when the transport does not claim the url. A test hands in its own. */
    @Volatile var http: (String, Map<String, String>) -> Pair<Int, String>? = { url, headers ->
        FleetHttpSeam.exchange("GET", url, headers, null, 15_000, 30_000)?.let { it.code to it.body }
    }

    /** The file name cloud-me-api holds for a baked asset, or null for an asset that is baked only. */
    fun remoteName(asset: String): String? {
        val flat = { s: String -> s.replace('/', '-').replace('_', '-') }
        return when {
            asset.endsWith(".json") && asset.startsWith("wallet/") -> flat(asset)
            asset.startsWith("health_") && asset.endsWith(".json") && !asset.contains('/') -> flat(asset)
            asset in setOf("repos.outline.json", "roadmap-tree.json", "vault-rows.json") -> "repos-$asset"
            else -> null
        }
    }

    private fun cacheFile(ctx: Context, remote: String) = File(File(ctx.filesDir, CACHE), remote)

    /** The cloud-me-api copy last synced, else the baked asset. */
    fun text(ctx: Context, asset: String): String {
        remoteName(asset)?.let { r ->
            val f = cacheFile(ctx, r)
            if (f.isFile) runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ctx.assets.open(asset).bufferedReader().use { it.readText() }
    }

    /** Every baked asset that cloud-me-api also holds. */
    fun mappedAssets(ctx: Context): List<String> {
        fun walk(p: String): List<String> = (ctx.assets.list(p) ?: emptyArray()).flatMap { n ->
            val q = "$p/$n"
            if (n.contains('.')) listOf(q) else walk(q)
        }
        val flat = (ctx.assets.list("") ?: emptyArray()).toList()
        return (walk("wallet") + flat).filter { remoteName(it) != null }
    }

    fun url(base: String, remote: String): String = "$base/me/repos/$REPO/file?path=" + Uri.encode("$DIR/$remote")

    @Volatile private var started = false

    /** Background refresh; safe to call from onCreate. No Account, no grant or no bearer -> returns quietly, baked data stays. */
    fun sync(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        thread(name = "cloud-me-api-sync", isDaemon = true) { runCatching { syncNow(app) } }
    }

    fun syncNow(app: Context): Int {
        val pulled = FleetEndpointsClient.pull(app) as? FleetEndpointsClient.Outcome.Pulled ?: return 0
        val svc = pulled.published.entries.firstOrNull { it.service == SERVICE } ?: return 0
        FleetHttpInstall.install()
        FleetBoot.register(pulled.published.entries, pulled.published.bearer)
        // the public base routes the request: the shared transport sends it over the mesh first and falls back to this host with the bearer
        val base = svc.public.ifEmpty { svc.mesh }.trimEnd('/')
        var ok = 0
        for (asset in mappedAssets(app)) {
            val remote = remoteName(asset) ?: continue
            val (code, body) = runCatching { http(url(base, remote), mapOf("Accept" to "text/plain")) }.getOrNull() ?: continue
            if (code !in 200..299 || runCatching { JSONObject(body) }.isFailure && !body.trimStart().startsWith("[")) continue
            val f = cacheFile(app, remote)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(body)
            if (tmp.renameTo(f)) ok++ else tmp.delete()
        }
        return ok
    }
}
