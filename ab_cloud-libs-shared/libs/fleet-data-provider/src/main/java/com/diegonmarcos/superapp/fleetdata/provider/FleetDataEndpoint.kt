package com.diegonmarcos.superapp.fleetdata.provider

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.diegonmarcos.superapp.fleetdata.FleetDataRouting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

/**
 * The Android half of one provider (one per authority per process): the store, the server, the sync and the glue the ContentProvider calls.
 * Cloud Store's [FleetDataProvider] and Cloud Account's accountdata provider both delegate here; nothing is per-dataset.
 */
class FleetDataEndpoint private constructor(private val app: Context, val authority: String) {
    val store = FleetDataStore(File(app.filesDir, "fleetdata"), if (authority == FleetDataRouting.ACCOUNT) KeystoreCodec() else null)
    val log = AccessLog(File(app.filesDir, "fleetdata/access.log"))
    val server = FleetDataServer(store, log, app.packageName)
    private val statusFile = File(app.filesDir, "fleetdata/status.json")
    @Volatile private var lastNudge = 0L

    private val sync: FleetDataSync? by lazy {
        FleetDataHost.source(authority)?.let { FleetDataSync(store, it, HttpFetcher(), changed = { p -> announce(p) }) }
    }

    fun announce(path: String) {
        runCatching { app.contentResolver.notifyChange(Uri.parse("content://$authority/$path"), null) }
    }

    /** Runs one sync (the worker, "Sync now"); writes the status the Data page shows. */
    fun runSync(): FleetDataSync.Report? {
        val r = sync?.run() ?: return null
        if (!r.throttled) writeStatus(r)
        return r
    }

    class Status(val at: Long, val updated: List<String>, val notModified: List<String>, val failed: Map<String, String>, val source: String)

    private fun writeStatus(r: FleetDataSync.Report) {
        val o = buildJsonObject {
            put("at", r.at); put("source", FleetDataHost.source(authority)?.manifest()?.url?.substringBefore("?").orEmpty())
            put("updated", buildJsonArray { r.updated.forEach { add(it) } })
            put("notModified", buildJsonArray { r.notModified.forEach { add(it) } })
            put("failed", buildJsonObject { r.failed.forEach { (k, v) -> put(k, v) } })
        }
        statusFile.parentFile?.mkdirs(); statusFile.writeText(o.toString())
    }

    fun status(): Status? = runCatching {
        val o = Json.parseToJsonElement(statusFile.readText()) as JsonObject
        fun strs(k: String) = (o[k] as JsonArray).mapNotNull { (it as JsonPrimitive).contentOrNull }
        Status((o["at"] as JsonPrimitive).longOrNull ?: 0, strs("updated"), strs("notModified"),
            (o["failed"] as JsonObject).mapValues { (_, v) -> (v as JsonPrimitive).content }, (o["source"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }.getOrNull()

    /** openFile: read-only, by callingPackage; a body kept encrypted is streamed through a pipe, a plain file by descriptor. */
    fun openFile(uri: Uri, mode: String, callingPackage: String?): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val caller = callingPackage ?: if (Binder.getCallingUid() == Process.myUid()) app.packageName else null
        FleetDataSchedule.ensure(app, authority)
        return when (val o = server.open(caller, uri.pathSegments)) {
            is FleetDataServer.Outcome.Served -> ParcelFileDescriptor.open(o.file, ParcelFileDescriptor.MODE_READ_ONLY)
            is FleetDataServer.Outcome.Bytes -> pipe(o.bytes)
            is FleetDataServer.Outcome.Denied -> throw SecurityException(o.why)
            FleetDataServer.Outcome.Missing -> { nudge(); throw FileNotFoundException(uri.toString()) }
        }
    }

    private fun pipe(bytes: ByteArray): ParcelFileDescriptor {
        val p = ParcelFileDescriptor.createPipe()
        Thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(p[1]).use { it.write(bytes) } }
        }.start()
        return p[0]
    }

    /** A dataset a caller wanted is not here yet: ask for one sync, at most every five minutes. */
    private fun nudge() {
        val t = System.currentTimeMillis()
        if (t - lastNudge < 300_000) return
        lastNudge = t
        FleetDataSchedule.syncNow(app, authority)
    }

    companion object {
        private val all = ConcurrentHashMap<String, FleetDataEndpoint>()
        fun of(ctx: Context, authority: String): FleetDataEndpoint =
            all.getOrPut(authority) { FleetDataEndpoint(ctx.applicationContext, authority) }
    }
}
