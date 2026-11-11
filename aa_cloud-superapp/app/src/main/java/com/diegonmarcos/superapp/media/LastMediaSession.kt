package com.diegonmarcos.superapp.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import java.io.File

/**
 * The last media session, persisted so the card survives the player being backgrounded or killed: package, track
 * metadata, last position and the album art (a small PNG in filesDir). Written from the live [MediaController]
 * ([save]); read when nothing is playing, where the card shows the art dimmed with a Play button that [resume]s it.
 */
object LastMediaSession {
    private const val PREFS = "last_media_session"
    private const val ART = "last_media_art.png"
    private const val ART_MAX_PX = 512

    data class Last(val pkg: String, val title: String, val artist: String, val positionMs: Long, val durationMs: Long, val art: Bitmap?) {
        val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    }

    /** #1648: the ONLY packages whose media session the held now-playing card may bind to. Telegram, browsers and every
     *  other app are excluded; the open-all-players shortcut icons are a separate list and are unaffected. */
    val HELD_SOURCES: Set<String> = setOf("com.aspiro.tidal", "com.spotify.music")

    fun isHeldSource(pkg: String?): Boolean = pkg != null && pkg in HELD_SOURCES

    /** Pure: the controller the held card binds to, from [sessions] as (package, playing): the playing allowlisted one,
     *  else the first allowlisted one, else null (the card then shows empty / the last Tidal-Spotify session). */
    fun <T> pickHeld(sessions: List<T>, pkg: (T) -> String, playing: (T) -> Boolean): T? {
        val ok = sessions.filter { isHeldSource(pkg(it)) }
        return ok.firstOrNull(playing) ?: ok.firstOrNull()
    }

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Pure: the position a PlaybackState-like (state position, last update, speed, playing) reads at [nowElapsed]. */
    fun positionAt(position: Long, updatedAt: Long, speed: Float, playing: Boolean, nowElapsed: Long): Long =
        if (playing) position + ((nowElapsed - updatedAt) * speed).toLong() else position

    fun save(ctx: Context, c: MediaController) = runCatching {
        if (!isHeldSource(c.packageName)) return@runCatching
        val md = c.metadata ?: return@runCatching
        val title = (md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            ?.takeIf { it.isNotBlank() } ?: return@runCatching
        val artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        val st = c.playbackState
        val pos = st?.let { positionAt(it.position, it.lastPositionUpdateTime, it.playbackSpeed, it.state == PlaybackState.STATE_PLAYING, SystemClock.elapsedRealtime()) } ?: 0L
        val sp = p(ctx)
        val sameTrack = sp.getString("pkg", null) == c.packageName && sp.getString("title", null) == title
        sp.edit().putString("pkg", c.packageName).putString("title", title).putString("artist", artist)
            .putLong("pos", pos.coerceAtLeast(0)).putLong("dur", md.getLong(MediaMetadata.METADATA_KEY_DURATION)).apply()
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art != null && (!sameTrack || !artFile(ctx).exists())) writeArt(ctx, art)
        else if (art == null && !sameTrack) artFile(ctx).delete()
    }

    fun load(ctx: Context): Last? {
        val sp = p(ctx)
        val pkg = sp.getString("pkg", null) ?: return null
        // #1648: a session persisted by an older build from Telegram / a browser is dropped, never shown.
        if (!isHeldSource(pkg)) { runCatching { sp.edit().clear().apply(); artFile(ctx).delete() }; return null }
        val title = sp.getString("title", null) ?: return null
        val art = runCatching { artFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }.getOrNull()
        return Last(pkg, title, sp.getString("artist", "").orEmpty(), sp.getLong("pos", 0), sp.getLong("dur", 0), art)
    }

    /** Play again: the app's live session if it has one, else a MEDIA_PLAY button event to its package, else launch it. */
    fun resume(ctx: Context, active: MediaController? = null): Boolean {
        val last = load(ctx) ?: return false
        if (active != null && active.packageName == last.pkg) { runCatching { active.transportControls.play() }.onSuccess { return true } }
        val sent = runCatching {
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP))
                ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(last.pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY)))
            true
        }.getOrDefault(false)
        if (!sent) runCatching {
            ctx.packageManager.getLaunchIntentForPackage(last.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
        }
        return sent
    }

    private fun artFile(ctx: Context) = File(ctx.applicationContext.filesDir, ART)

    private fun writeArt(ctx: Context, b: Bitmap) = runCatching {
        val s = ART_MAX_PX.toFloat() / maxOf(b.width, b.height)
        val out = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
        artFile(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }
}
