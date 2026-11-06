package com.diegonmarcos.superapp.fleetdata

import java.io.InputStream

/** Where bytes come from (the provider through openFile on a phone, a fake in a test). Null = not available right now. */
fun interface DataSource { fun open(authority: String, path: String): InputStream? }

/** The client's own last-known-good copy of a dataset, keyed by its route path. */
interface LastKnownGood {
    fun load(key: String): ByteArray?
    fun store(key: String, bytes: ByteArray)
}

/**
 * Read -> validate -> parse, with the last-known-good copy behind it. Never throws: a provider that is missing, a dataset not synced yet,
 * a refused schemaVersion or a parser error all end in the copy of the last good read, else null (the caller's loading state).
 */
class FleetDataReader(
    private val source: DataSource,
    private val lkg: LastKnownGood,
    private val supportedSchema: Int = 1,
    private val maxBytes: Int = 32 * 1024 * 1024,
) {
    fun <T> get(dataset: String, ownPackage: String? = null, parse: (String) -> T): T? {
        if (!FleetDataRouting.validDataset(dataset)) return null
        val path = FleetDataRouting.path(dataset, ownPackage)
        val key = path.replace('/', '__')
        val fresh = runCatching { source.open(FleetDataRouting.authorityFor(dataset), path)?.use { readAll(it) } }.getOrNull()
        if (fresh != null) {
            val parsed = decode(fresh, parse)
            if (parsed != null) { runCatching { lkg.store(key, fresh) }; return parsed }
        }
        val old = runCatching { lkg.load(key) }.getOrNull() ?: return null
        return decode(old, parse)
    }

    private fun <T> decode(bytes: ByteArray, parse: (String) -> T): T? = runCatching {
        val text = String(bytes, Charsets.UTF_8)
        if (SchemaCheck.check(text, supportedSchema) !is SchemaCheck.Result.Ok) null else parse(text)
    }.getOrNull()

    /** Streams the whole body; a body past [maxBytes] is dropped (a runaway file must not take the app down). */
    private fun readAll(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > maxBytes) return null
        }
        return out.toByteArray()
    }
}
