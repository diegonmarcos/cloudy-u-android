@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.diegonmarcos.superapp.media
import com.diegonmarcos.superapp.R

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.apps.AppIcon
import com.diegonmarcos.superapp.ui.AppIconCache
import com.diegonmarcos.superapp.ui.KitPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Samsung One-UI-style now-playing controls popup, anchored under
 * the music mini-island. Three rows:
 *   1. App icon (left, 48dp — tap → open the playing app) + title
 *      stack (title + artist).
 *   2. SeekBar progress with elapsed / total time underneath.
 *   3. Centered transport buttons — prev | play/pause | next.
 *
 * Lifecycle — show() builds + presents; dismiss() tears down all
 * callbacks + the 1-second tick handler. Re-show is idempotent;
 * an existing popup is dismissed before a new one is built so the
 * caller doesn't have to track state.
 *
 * MediaController source — we DON'T re-walk MediaSessionManager;
 * the caller (MainActivity) passes the currently-playing
 * [MediaController] from [NowPlayingMonitor.currentController].
 * That keeps notification-listener access checks in one place and
 * means the popup never has to deal with permission errors.
 *
 * If the controller dies mid-popup (the app stops publishing the
 * session), playback callbacks fall silent and the SeekBar stops
 * advancing — fine for a transient UI; the user dismisses + the
 * island also flips GONE via [NowPlayingMonitor].
 */
class MusicControlsPopup(private val ctx: Context) {

    private var closer: (() -> Unit)? = null

    fun show(
        anchor: View,
        controller: MediaController?,
        playingPackage: String?,
    ) {
        dismiss()
        // NOTE: controller/playingPackage may be NULL — nothing is playing. We STILL open the popup (user wants the tap to
        // work even when idle); it just renders an empty "Nothing playing" card with inert controls.
        closer = KitPopup.show(anchor, KitPopup.Align.CENTER) { close -> MusicControlsCard(ctx, controller, playingPackage, close) }
    }

    fun dismiss() {
        closer?.invoke()
        closer = null
    }

    companion object {
        /** Fixed popup height in dp — the compact landscape box that ends just after the transport buttons. */
        internal const val POPUP_HEIGHT_DP = 167

        /** Shazam package — the leftmost popup button launches it. */
        internal const val SHAZAM_PKG = "com.shazam.android"

        /** Quick-launch streaming apps shown beside "Nothing playing" in the idle popup state. Order = display order. */
        internal val IDLE_LAUNCH_PKGS = listOf(
            "com.aspiro.tidal",                       // Tidal
            "com.spotify.music",                      // Spotify
            "com.google.android.apps.youtube.music",  // YT Music ("Google Music")
            "com.google.android.youtube",             // YouTube
            "com.vimeo.android.videoapp",             // Vimeo
        )

        internal fun formatTime(ms: Long): String {
            val totalSec = ms / 1000L
            val m = totalSec / 60L
            val s = totalSec % 60L
            return "%d:%02d".format(m, s)
        }
    }
}

private val SHADOW = TextStyle(shadow = Shadow(Color.Black, androidx.compose.ui.geometry.Offset(0f, 2f), 4f))

/** What the card shows of the session: metadata, art, play state, heart. Fed by the controller's callbacks. */
private class MusicSnapshot(c: MediaController?) {
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var art by mutableStateOf<Bitmap?>(null)
    var playing by mutableStateOf(false)

    init { refreshMetadata(c); refreshState(c) }

    fun refreshMetadata(c: MediaController?) {
        val md = c?.metadata
        title = md?.let {
            it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        }?.takeIf { it.isNotBlank() } ?: ""
        artist = md?.let {
            it.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        }?.takeIf { it.isNotBlank() } ?: ""
        // Album cover → full-bleed card BACKGROUND. Prefer ALBUM_ART, fall back to ART, then DISPLAY_ICON; none → the black card.
        art = md?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
    }

    fun refreshState(c: MediaController?) { playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING }
}

/** The card, split from [MusicControlsPopup.show] so it can be drawn without a window. */
@Composable
internal fun MusicControlsCard(ctx: Context, controller: MediaController?, playingPackage: String?, close: () -> Unit) {
    val dm = ctx.resources.displayMetrics
    val snap = remember(controller) { MusicSnapshot(controller) }
    // Registered while the card is composed, unregistered with it - the old attach/detachController.
    DisposableEffect(controller) {
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) { snap.refreshState(controller) }
            override fun onMetadataChanged(metadata: MediaMetadata?) { snap.refreshMetadata(controller) }
        }
        controller?.registerCallback(cb, Handler(Looper.getMainLooper()))
        onDispose { controller?.let { runCatching { it.unregisterCallback(cb) } } }
    }
    var faved by remember(controller) {
        mutableStateOf(runCatching {
            controller?.metadata?.getRating(MediaMetadata.METADATA_KEY_USER_RATING)?.takeIf { it.isRated }?.hasHeart() == true
        }.getOrDefault(false))
    }

    // WIDE + SHORT landscape card (Samsung media-card shape): nearly the full screen wide, a compact fixed box tall that ends
    // right after the transport buttons. The art is clipped INTO that box, so it can never inflate the card.
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.width((dm.widthPixels * 0.92f / dm.density).dp).height(MusicControlsPopup.POPUP_HEIGHT_DP.dp)
        .clip(shape).background(Color.Black).border(1.dp, Color(0x55B794F4), shape).testTag("music:card")) {
        snap.art?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Dark scrim over the art for text/button contrast.
        Box(Modifier.fillMaxSize().background(Color(0xA6000000)))
        Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)) {
            // Row 1: app icon (tap → open the playing app) + title / artist (+ the idle quick-launch strip).
            Row(verticalAlignment = Alignment.CenterVertically) {
                val icon by produceState<android.graphics.drawable.Drawable?>(null, playingPackage) {
                    value = playingPackage?.let { withContext(Dispatchers.IO) { AppIconCache.load(ctx, it) } }
                }
                Box(Modifier.size(44.dp).clickable(enabled = playingPackage != null) {
                    runCatching {
                        val intent = ctx.packageManager.getLaunchIntentForPackage(playingPackage!!)
                        if (intent != null) ctx.startActivity(intent)
                    }
                    close()
                }.testTag("music:app-icon")) { AppIcon(icon, 44.dp) }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(if (controller == null) "Nothing playing" else snap.title, color = Color(0xFFF5F5F5), fontSize = 14.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, style = SHADOW.copy(fontSize = 14.sp),
                        modifier = Modifier.basicMarquee().testTag("music:title"))
                    Text(snap.artist, color = Color(0xFFD4D4D4), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = SHADOW.copy(fontSize = 11.sp), modifier = Modifier.padding(top = 2.dp))
                }
                if (controller == null) IdleLaunchers(ctx, close)
            }
            if (controller != null) LiveRows(ctx, controller, snap) else IdleRows()
            // Row 3: Shazam | prev | play/pause | next | heart.
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Glyph(R.drawable.ic_music_recognise, 34, "music:shazam", Color.White) {
                    val intent = ctx.packageManager.getLaunchIntentForPackage(MusicControlsPopup.SHAZAM_PKG)
                        ?: android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("market://details?id=${MusicControlsPopup.SHAZAM_PKG}"))
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(intent) }
                    close()
                }
                Spacer(Modifier.width(14.dp))
                Glyph(android.R.drawable.ic_media_previous, 38, "music:prev", Color.White) {
                    controller?.let { runCatching { it.transportControls.skipToPrevious() } }
                }
                Spacer(Modifier.width(14.dp))
                Glyph(if (snap.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, 46, "music:play", Color.White) {
                    controller?.let { c ->
                        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                        runCatching { if (playing) c.transportControls.pause() else c.transportControls.play() }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Glyph(android.R.drawable.ic_media_next, 38, "music:next", Color.White) {
                    controller?.let { runCatching { it.transportControls.skipToNext() } }
                }
                Spacer(Modifier.width(14.dp))
                // Favourite (heart): reflects the session's heart rating when published, toggles on tap, and pushes the new
                // rating back when a controller exists (apps that honour setRating sync; others track the local look).
                Glyph(if (faved) R.drawable.ic_music_heart_filled else R.drawable.ic_music_heart, 34, "music:heart", null) {
                    faved = !faved
                    controller?.let { runCatching { it.transportControls.setRating(android.media.Rating.newHeartRating(faved)) } }
                }
            }
        }
    }
}

@Composable
private fun Glyph(res: Int, sizeDp: Int, tag: String, tint: Color?, onClick: () -> Unit) {
    val m = Modifier.size(sizeDp.dp).clickable(onClick = onClick).padding(6.dp).testTag(tag)
    if (tint != null) Icon(painterResource(res), contentDescription = null, tint = tint, modifier = m)
    else Image(painterResource(res), contentDescription = null, modifier = m)
}

/** Idle: the streaming apps' launcher icons beside "Nothing playing"; tap opens the app (or its Play listing) and closes. */
@Composable
private fun IdleLaunchers(ctx: Context, close: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("music:idle-apps")) {
        for ((i, pkg) in MusicControlsPopup.IDLE_LAUNCH_PKGS.withIndex()) {
            val icon by produceState<android.graphics.drawable.Drawable?>(AppIconCache.cached(pkg), pkg) {
                if (value == null) value = withContext(Dispatchers.IO) { AppIconCache.load(ctx, pkg) }
            }
            Box(Modifier.padding(start = if (i > 0) 6.dp else 0.dp).size(26.dp).clickable {
                val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    ?: android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$pkg"))
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(intent) }
                close()
            }.testTag("music:idle:$pkg")) {
                if (icon != null) AppIcon(icon, 26.dp)
                else Icon(painterResource(R.drawable.ic_music_recognise), null, tint = Color(0xCCFFFFFF))
            }
        }
    }
}

/** Idle placeholder - no track, an inert progress bar. */
@Composable
private fun IdleRows() {
    Spacer(Modifier.height(14.dp))
    Slider(value = 0f, onValueChange = {}, enabled = false, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("music:progress"))
    Row(Modifier.fillMaxWidth()) {
        Text("0:00", color = Color(0xFFE0E0E0), fontSize = 10.sp)
        Spacer(Modifier.weight(1f))
        Text("0:00", color = Color(0xFFE0E0E0), fontSize = 10.sp)
    }
}

/** Rows 2: the audio route + session volume, the progress bar and the times; refreshed on a 500 ms tick. */
@Composable
private fun LiveRows(ctx: Context, c: MediaController, snap: MusicSnapshot) {
    var nowPos by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var route by remember { mutableStateOf(Route(R.drawable.ic_route_speaker, "")) }
    var volume by remember { mutableStateOf("") }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(c) {
        while (true) {
            duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val state = c.playbackState
            nowPos = if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                state.position + (elapsed * state.playbackSpeed).toLong()
            } else state?.position ?: 0L
            // AudioManager binder reads off the composition (main) coroutine.
            val (r, v) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { routeOf(ctx) to volumeLabel(ctx, c) }
            r?.let { route = it }
            volume = v
            delay(1000L)
        }
    }
    // Audio-route + volume info row. Left: where the media is playing. Right: the session volume percent.
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(route.icon), null, tint = Color.White, modifier = Modifier.size(12.dp))
        Text(route.label, color = Color(0xFFE0E0E0), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = SHADOW.copy(fontSize = 11.sp), modifier = Modifier.weight(1f).padding(start = 5.dp).testTag("music:route"))
        Text(volume, color = Color(0xFFE0E0E0), fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp).testTag("music:volume"))
    }
    val fraction = if (duration > 0L) (nowPos.toFloat() / duration).coerceIn(0f, 1f) else 0f
    // Only commit on release so the controller is not spammed while the thumb moves; the tick refreshes it otherwise.
    Slider(value = dragging ?: fraction, onValueChange = { dragging = it },
        onValueChangeFinished = {
            val f = dragging
            dragging = null
            if (f != null && duration > 0L) runCatching { c.transportControls.seekTo((duration * f).toLong().coerceAtLeast(0)) }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).testTag("music:progress"))
    Row(Modifier.fillMaxWidth()) {
        Text(MusicControlsPopup.formatTime(nowPos.coerceAtLeast(0L)), color = Color(0xFFE0E0E0), fontSize = 10.sp,
            modifier = Modifier.testTag("music:elapsed"))
        Spacer(Modifier.weight(1f))
        Text(if (duration > 0L) MusicControlsPopup.formatTime(duration) else "--:--", color = Color(0xFFE0E0E0), fontSize = 10.sp)
    }
}

private class Route(val icon: Int, val label: String)

/** Where audio is going NOW: bluetooth (by the headset's name when known) / headphones / the phone speaker. Uses the
 *  deprecated-but-accurate "is audio going there NOW" booleans (they reflect the ACTIVE route, unlike getDevices). */
@Suppress("DEPRECATION")
private fun routeOf(ctx: Context): Route? {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return null
    return when {
        am.isBluetoothA2dpOn -> {
            val name = runCatching {
                am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                    .firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                    }?.productName?.toString()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            Route(R.drawable.ic_route_bluetooth, name ?: "Bluetooth")
        }
        am.isWiredHeadsetOn -> Route(R.drawable.ic_route_headphones, "Headphones")
        else -> Route(R.drawable.ic_route_speaker, "Phone speaker")
    }
}

/** Session volume as a percent. Prefers the MediaController's own PlaybackInfo (accurate for LOCAL and REMOTE/cast sessions);
 *  falls back to the system STREAM_MUSIC volume when the session publishes none. */
private fun volumeLabel(ctx: Context, c: MediaController): String {
    val info = c.playbackInfo
    val pct = if (info != null && info.maxVolume > 0) {
        (info.currentVolume * 100f / info.maxVolume).toInt()
    } else {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val max = am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
        val cur = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
        if (max > 0) cur * 100 / max else return ""
    }
    return "Vol $pct%"
}
