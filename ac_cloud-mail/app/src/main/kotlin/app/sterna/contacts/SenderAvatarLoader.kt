package app.sterna.contacts

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.sterna.ui.attachment.AttachmentOpen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Step two of a sender avatar's declared fallback chain (task #464):
 *
 *   1. the device address book's photo for the address (local, free — always first);
 *   2. the sender DOMAIN's brand logo — fetched here, from the sender's own host, cached on disk;
 *   3. the monogram (the fallback kept in [ContactAvatar]).
 *
 * Privacy is the design constraint, not an afterthought. This object only ever contacts the
 * sender's OWN domain (which already knows the address), by name — the domain — and never an
 * individual address, and it never contacts a third-party avatar service (Gravatar and its kind)
 * at all. Fetching is off the main thread, cached on disk keyed by the DOMAIN (never the person),
 * and gated on the fleet's #46 rule: it never spends the owner's mobile data unasked.
 */
object SenderAvatarLoader {

    /** Longest side kept when decodes, in pixels — same restraint as a device thumbnail. */
    private const val MAX_PX = 128

    /** Room for a couple of screens' worth of logos (128 px ARGB_8888 ≈ 64 KiB each). */
    private const val MAX_ENTRIES = 64
    private const val MAX_BYTES = 4L * 1024 * 1024

    private class Cached(val image: ImageBitmap?)

    /** In-memory index over the disk cache, keyed by domain; a null is a remembered miss. */
    private val memory = PhotoCache<Cached>(MAX_ENTRIES, MAX_BYTES)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followSslRedirects(false) // an avatar logo must never be chased across schemes
        .build()

    /**
     * The declared fallback order, made pure (and generic) so a unit test can pin it without
     * Android: a device photo always wins, then the domain logo, then null (the monogram).
     */
    fun <T> pickAvatar(device: T?, domain: T?): T? = device ?: domain

    /** An address's host, lower-cased; empty when the address carries none. */
    fun domainOf(email: String): String = email.substringAfterLast('@', "").trim().lowercase()

    /** A domain's candidate logo URLs, in fetch order: the classic icon first, then a PNG. */
    fun faviconCandidates(domain: String): List<String> =
        listOf("https://$domain/favicon.ico", "https://$domain/favicon.png")

    /**
     * The on-disk cache entry for a logo, stable across calls so a recomposition never re-fetches.
     * Keyed by `domain.hashCode()` (the domain is a person-agnostic name), not by any address.
     */
    fun cacheFile(cacheDir: File, domain: String): File = File(cacheDir, "avatar-${domain.hashCode()}.img")

    /** What is already decoded for [domain], from the in-memory index, without touching disk. */
    fun cached(domain: String): ImageBitmap? = domain.takeIf { it.isNotBlank() }?.let { memory.get(it)?.image }

    /**
     * The logo for [domain]: in-memory index, then the disk cache, then — only on an unmetered
     * network and only from the sender's own host — a fetch. Every failure falls through to null so
     * the caller can keep its monogram; an avatar is decoration and must never block or error a row.
     */
    suspend fun load(context: Context, domain: String): ImageBitmap? {
        if (domain.isBlank()) return null
        memory.get(domain)?.let { return it.image }
        return withContext(Dispatchers.IO) {
            memory.get(domain)?.let { return@withContext it.image }
            val dir = File(context.cacheDir, "avatars").apply { mkdirs() }
            val file = cacheFile(dir, domain)
            if (file.exists()) {
                val hit = decode(file.readBytes())
                memory.put(domain, Cached(hit), file.length())
                return@withContext hit
            }
            // The fleet's #46 rule: an avatar is decoration, never worth the owner's mobile data.
            // Leave the miss un-cached so a later unmetered session can fetch it.
            if (AttachmentOpen.isMetered(context)) return@withContext null
            val bytes = fetchLogo(domain) ?: run {
                // A real miss (host refused or has no favicon): remember it so we do not hammer.
                memory.put(domain, Cached(null), 0L)
                return@withContext null
            }
            runCatching { file.writeBytes(bytes) }
            val image = decode(bytes)
            memory.put(domain, Cached(image), bytes.size.toLong())
            image
        }
    }

    private suspend fun fetchLogo(domain: String): ByteArray? =
        runCatching {
            for (url in faviconCandidates(domain)) {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.bytes()?.takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
            null
        }.getOrNull()

    private fun decode(bytes: ByteArray): ImageBitmap? {
        if (bytes.isEmpty()) return null
        val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        return app.sterna.ui.JankProbe.section(if (onMain) "avatar.decode.main" else "avatar.decode.bg") { decodeBytes(bytes) }
    }

    private fun decodeBytes(bytes: ByteArray): ImageBitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_PX)
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }
        }.getOrNull()
    }
}
