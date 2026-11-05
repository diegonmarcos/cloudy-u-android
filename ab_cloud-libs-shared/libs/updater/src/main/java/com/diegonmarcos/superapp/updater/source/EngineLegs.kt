package com.diegonmarcos.superapp.updater.source

import android.content.Context
import com.diegonmarcos.superapp.updater.Fleet

/**
 * #1517 The download legs for one fleet app as plain data, in the order [Fleet.sourceOrder] declares (mesh, release, ghcr), for the
 * Store's download engine (libs:appstore, which cannot see [GhcrClient]). Each leg is resolved LAZILY, per attempt, because
 * resolving may fetch a sidecar or a token; a leg that cannot serve throws and the engine moves to the next one. The engine does
 * the transfer (Range, resume, sha256), these only say WHERE and WHAT it must hash to.
 */
object EngineLegs {
    class Plan(val url: String, val headers: Map<String, String>, val sha256: String?, val bytes: Long)
    class Leg(val name: String, val plan: () -> Plan)

    fun legs(ctx: Context, app: Fleet.App): List<Leg> = listOf(
        Leg("mesh") {
            val (url, sha) = MeshMirror.plan(ctx, app) ?: throw java.io.IOException("mesh: not configured for ${app.id}")
            Plan(url, emptyMap(), sha, -1L)
        },
        Leg("release") {
            if (app.releaseUrl.isBlank()) throw java.io.IOException("release: not configured for ${app.id}")
            val size = Fleet.releaseSize(app)
            val sha = Fleet.releaseSha256(app)
            // Same rule as ReleaseSource: no digest degrades to the declared length, never to no check.
            if (sha == null && size <= 0L) throw java.io.IOException("release: no sha256 sidecar and no declared length for ${app.id}")
            Plan(app.abiReleaseUrl, emptyMap(), sha, size)
        },
        Leg("ghcr") {
            val client = GhcrClient(app.registry, app.namespace, app.image)
            val token = client.token()
            val layer = Fleet.remoteLayerFor(app, client, token)
            Plan(client.blobUrl(layer.digest), mapOf("Authorization" to "Bearer $token"), layer.digest.substringAfter(':'), layer.size)
        },
    )
}
