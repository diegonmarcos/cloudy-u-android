package com.diegonmarcos.superapp.updater.source

import com.diegonmarcos.superapp.updater.BuildConfig
import com.diegonmarcos.superapp.updater.apk.ApkIntegrity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal OCI registry client for GHCR. Mirrors what build.sh oras-pull does:
 *   1. anonymous bearer token from /token
 *   2. manifest GET (vnd.oci.image.manifest.v1+json)
 *   3. blob GET by digest
 *
 * All registry/namespace/image come from BuildConfig (which gets them from
 * build.json::release.ghcr at gradle eval time). Zero hardcoded strings.
 */
internal class GhcrClient(
    private val registry: String = BuildConfig.GHCR_REGISTRY,
    private val namespace: String = BuildConfig.GHCR_NAMESPACE,
    private val image: String = BuildConfig.GHCR_IMAGE,
) {
    private val repo = "$namespace/$image"
    private val json = Json { ignoreUnknownKeys = true }

    /** Non-2xx GHCR response, carrying the status code so callers can tell a
     *  genuinely-absent tag (404 — e.g. an ABI variant not published yet)
     *  from a transient/auth failure. */
    class HttpException(val code: Int, val target: String, body: String?) :
        java.io.IOException("HTTP $code for $target: $body")

    /** Anonymous bearer token for pull. Public packages: this just works. */
    fun token(): String {
        val url = URL("https://$registry/token?service=$registry&scope=repository:$repo:pull")
        return openGet(url, mapOf()).use { stream ->
            val body = stream.bufferedReader().readText()
            json.parseToJsonElement(body).jsonObject["token"]!!.jsonPrimitive.content
        }
    }

    data class ManifestLayer(val digest: String, val size: Long, val title: String, val revision: String?)

    /** Returns the first layer (the APK blob) + the manifest-level
     *  `org.opencontainers.image.revision` (short git sha) for code-identity
     *  update checks. */
    fun manifest(tag: String, token: String): ManifestLayer {
        val url = URL("https://$registry/v2/$repo/manifests/$tag")
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.oci.image.manifest.v1+json",
        )
        return openGet(url, headers).use { stream ->
            val body = stream.bufferedReader().readText()
            val root = json.parseToJsonElement(body).jsonObject
            val layer = root["layers"]!!.jsonArray[0].jsonObject
            ManifestLayer(
                digest = layer["digest"]!!.jsonPrimitive.content,
                size = layer["size"]!!.jsonPrimitive.content.toLong(),
                title = layer["annotations"]?.jsonObject?.get("org.opencontainers.image.title")
                    ?.jsonPrimitive?.content ?: "$image.apk",
                revision = root["annotations"]?.jsonObject?.get("org.opencontainers.image.revision")
                    ?.jsonPrimitive?.content,
            )
        }
    }

    /** Streams the blob into [target] via [Download], which owns resume,
     *  the stall watchdog and progress. Caller verifies sha256 against
     *  [digest]. [expectedBytes] is the manifest layer size — [Download] needs
     *  it to tell a resumable prefix from a leftover of another build.
     *  [onProgress] reports bytes actually written and the total, -1 when the
     *  registry declined to declare one. [shouldCancel] is polled every chunk
     *  so a Cancel actually aborts the blocking read loop. */
    fun blob(
        digest: String, token: String, target: File,
        expectedBytes: Long = -1L,
        shouldCancel: () -> Boolean = { false },
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        // Content-addressed cache HIT. Both callers name `target` after the
        // digest, so a file already sitting there with that exact content IS
        // this blob - re-downloading 35MB to produce bytes we already have is
        // pure waste, and worse, the old code opened `target` for writing
        // FIRST, so a failed or cancelled retry truncated the good copy it was
        // about to replace. That is why a failed install "lost" the download.
        // Length first: hashing is a full read of the file, which for a 265 MB
        // artifact is seconds of CPU on a phone, and a length mismatch already
        // proves these are not the bytes. Only pay for the digest when the
        // cheap test cannot rule it out.
        val lengthCouldMatch = expectedBytes <= 0 || target.length() == expectedBytes
        if (target.isFile && lengthCouldMatch &&
            runCatching { "sha256:" + ApkIntegrity.sha256(target) == digest }.getOrDefault(false)) {
            onProgress?.invoke(target.length(), target.length())
            return
        }
        Download.toFile(
            url = "https://$registry/v2/$repo/blobs/$digest",
            target = target,
            headers = mapOf("Authorization" to "Bearer $token"),
            expectedBytes = expectedBytes,
            shouldCancel = shouldCancel,
            onProgress = onProgress,
        )
    }

    /** #1517 The blob URL the download engine fetches (it adds the bearer header itself). */
    fun blobUrl(digest: String) = "https://$registry/v2/$repo/blobs/$digest"

    /** Drop older cache entries for the same app - [prefix] is per-app and the
     *  rest of the name is the digest, so anything matching but not [keep] is a
     *  superseded version. Without this the cache grows one APK per release
     *  until Android evicts the whole directory, taking the current one too. */
    fun pruneCache(prefix: String, keep: File) {
        keep.parentFile?.listFiles { f: File -> f.name.startsWith(prefix) && f != keep }
            ?.forEach { it.delete() }
    }

    private fun openGet(url: URL, headers: Map<String, String>) =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (responseCode !in 200..299) {
                val msg = errorStream?.bufferedReader()?.readText()
                throw HttpException(responseCode, url.toString(), msg)
            }
        }.inputStream
}
