package com.diegonmarcos.superapp.fleetdata

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * `FleetData.observe(ctx, "constellation-fleet", MlCatalogue::parse)`: the dataset's parsed value now and again after every update the
 * owning provider announces (notifyChange per URI), null while there is nothing (loading, provider absent, nothing synced yet, a refused
 * schemaVersion with no earlier good copy). [own] true reads the caller's own `app/<pkg>/<dataset>`; false the `shared/<dataset>`.
 */
object FleetData {
    fun <T> observe(ctx: Context, dataset: String, own: Boolean = false, parse: (String) -> T): Flow<T?> {
        val app = ctx.applicationContext
        val reader = reader(app)
        val pkg = if (own) app.packageName else null
        val uri = Uri.parse(FleetDataRouting.uri(dataset, pkg))
        return callbackFlow {
            val push = { launch(Dispatchers.IO) { trySend(reader.get(dataset, pkg, parse)) } }
            val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { push() }
            }
            runCatching { app.contentResolver.registerContentObserver(uri, false, obs) }
            push()
            awaitClose { runCatching { app.contentResolver.unregisterContentObserver(obs) } }
        }.flowOn(Dispatchers.IO)
    }

    /** One blocking read (a few ms from the provider's file); off the main thread when the file is large. Null as in [observe]. */
    fun <T> get(ctx: Context, dataset: String, own: Boolean = false, parse: (String) -> T): T? {
        val app = ctx.applicationContext
        return reader(app).get(dataset, if (own) app.packageName else null, parse)
    }

    private fun reader(app: Context) = FleetDataReader(ResolverSource(app), FileLkg(File(app.filesDir, "fleetdata-lkg")))

    /** openFile streaming: the descriptor is read as a plain file stream, so the Binder 1 MB limit never applies. */
    private class ResolverSource(private val ctx: Context) : DataSource {
        override fun open(authority: String, path: String): InputStream? {
            val fd = ctx.contentResolver.openFileDescriptor(Uri.parse("content://$authority/$path"), "r") ?: return null
            return ParcelStream(FileInputStream(fd.fileDescriptor), fd)
        }
    }

    private class ParcelStream(private val inner: FileInputStream, private val fd: android.os.ParcelFileDescriptor) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() { runCatching { inner.close() }; runCatching { fd.close() } }
    }

    class FileLkg(private val dir: File) : LastKnownGood {
        override fun load(key: String): ByteArray? = File(dir, "$key.json").takeIf { it.isFile }?.readBytes()
        override fun store(key: String, bytes: ByteArray) {
            dir.mkdirs()
            val tmp = File(dir, "$key.tmp"); tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(dir, "$key.json"))) tmp.delete()
        }
    }
}
