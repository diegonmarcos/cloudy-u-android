package com.diegonmarcos.superapp.floatingnav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.notificationcenter.BadgeCustomization
import com.diegonmarcos.superapp.notificationcenter.BadgeModel
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.notificationcenter.NotifyGate
import com.diegonmarcos.superapp.notificationcenter.NotifyGroups
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationListenerService
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.media.LastMediaSession

/**
 * "Cloud SuperApp - Notification Center Media Session" — a rich media-control
 * notification that mirrors + drives whatever is actually playing on the
 * device. It reads the active MediaSession via MediaSessionManager (needs the
 * app's NotificationListener access) and forwards Prev / Play-Pause / Next to
 * that session's transport controls. Album art + title/artist are the real
 * metadata; MediaStyle is linked to the live session token for the full
 * media-player rendering. When nothing is playing, the notification is removed.
 */
class MediaProxy(private val ctx: Context) {

    private var lastKey: String? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private val watched = HashMap<MediaController, MediaController.Callback>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Event-drive [refresh] instead of polling it: the session list changing (a player starts or ends) and each live
     * controller's own metadata / playback callbacks re-render the badge. Idempotent; needs the notification-listener
     * grant, so the owner calls it again on screen-on in case the grant arrived later.
     */
    fun watch() {
        if (sessionsListener != null || !listenerEnabled()) return
        runCatching {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(ctx, PhoneNotificationListenerService::class.java)
            val l = MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list.orEmpty()); refresh() }
            msm.addOnActiveSessionsChangedListener(l, comp, mainHandler)
            sessionsListener = l
            track(msm.getActiveSessions(comp))
        }
    }

    private fun track(list: List<MediaController>) {
        val gone = watched.keys.filter { k -> list.none { it.sessionToken == k.sessionToken } }
        gone.forEach { k -> watched.remove(k)?.let { runCatching { k.unregisterCallback(it) } } }
        for (c in list) {
            if (!LastMediaSession.isHeldSource(c.packageName)) continue
            if (watched.keys.any { it.sessionToken == c.sessionToken }) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { LastMediaSession.save(ctx, c); refresh() }
                override fun onMetadataChanged(metadata: MediaMetadata?) { LastMediaSession.save(ctx, c); refresh() }
            }
            runCatching { c.registerCallback(cb, mainHandler); watched[c] = cb }
        }
    }

    private fun unwatch() {
        sessionsListener?.let { l ->
            runCatching { (ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager).removeOnActiveSessionsChangedListener(l) }
        }
        sessionsListener = null
        watched.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        watched.clear()
    }

    private fun nm() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Whether the app's NotificationListener is enabled — REQUIRED for
     *  getActiveSessions to read the playing media (a separate grant from the
     *  POST_NOTIFICATIONS permission the other notifications use). */
    fun listenerEnabled(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** The active controller — the playing one if any, else the first. Null
     *  without notification-listener access or when nothing is playing. */
    fun activeController(): MediaController? {
        if (!listenerEnabled()) return null
        return runCatching {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val comp = ComponentName(ctx, PhoneNotificationListenerService::class.java)
            val list = msm.getActiveSessions(comp)
            // #1648: only Tidal / Spotify may drive the held card (never Telegram, a browser or any other app).
            LastMediaSession.pickHeld(list, { it.packageName }, { it.playbackState?.state == PlaybackState.STATE_PLAYING })
        }.getOrNull()
    }

    /** Forward a `media:*` action to the active session's transport. */
    fun transport(target: String) {
        // #535: the badge was swiped away. Forget what was posted so the poll
        // loop's next refresh() re-posts instead of deduping against it.
        if (target == REPOST) { lastKey = null; refresh(); return }
        val c = activeController()
        if (c == null) {
            // Nothing playing: Play restarts the persisted last session (package + track), the rest have nothing to act on.
            if (target == "media:playpause") LastMediaSession.resume(ctx)
            return
        }
        when (target) {
            "media:playpause" ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                else c.transportControls.play()
            "media:next" -> c.transportControls.skipToNext()
            "media:prev" -> c.transportControls.skipToPrevious()
        }
        lastKey = null // force a re-render on the next refresh
        mainHandler.postDelayed({ refresh() }, 500) // the session callbacks usually do it; this covers a silent transport
    }

    /** The `media_now_playing` entry of build.json::ui.notification_center —
     *  this badge's own declaration, which is where its persistence now comes
     *  from instead of from the playback state. */
    private val decl: BadgeDeclaration.Badge?
        get() = BadgeServices.declared.firstOrNull { it.id == BADGE_ID }

    /** #515: persistence is a DECLARED property of the badge, not an
     *  expression over the transport. It used to be `setOngoing(playing)`,
     *  which meant the badge was ongoing only while audio was actually
     *  playing and evaporated the moment it stopped — so "our media player
     *  notification center" disappeared every time a track ended, which is
     *  not what a persistent badge does. */
    private fun persistent(): Boolean =
        decl?.let { BadgeCustomization.isPersistent(ctx, it) } ?: false

    private fun showWhenIdle(): Boolean =
        decl?.let { BadgeCustomization.bool(ctx, it, "show_when_idle") } ?: false

    /** Forget what was posted and re-post now — the badge was removed from the
     *  shade behind the dedupe's back (Launch, or a stray cancel). */
    fun repost() { lastKey = null; refresh() }

    /** (Re)post the media notification for the current session, or cancel it. */
    fun refresh() {
        // #777: switched off here or by its group → out of the shade, not
        // merely not re-posted.
        if (decl?.let { BadgeCustomization.isEnabled(ctx, it) } == false) { cancel(); return }
        watch() // idempotent: a later grant or a switch back on picks the session events up again
        val c = activeController()
        if (c == null) {
            // A persistent badge holds its place with an idle state rather
            // than vanishing — vanishing is indistinguishable from broken,
            // which is exactly how this one got reported as missing.
            if (persistent() && showWhenIdle()) postIdle() else { NotifyGate.invalidate(null, NOTIF_MEDIA); nm().cancel(NOTIF_MEDIA); lastKey = null }
            return
        }
        ensureChannel()
        LastMediaSession.save(ctx, c) // survive the player being backgrounded or killed
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: "Now playing"
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val key = "$title|$artist|$playing"
        if (key == lastKey) return // no change → don't re-post (avoid flicker)
        lastKey = key
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val b = NotificationCompat.Builder(ctx, MEDIA_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Cloud SA - Media")
            .setOnlyAlertOnce(true)
            // #515: declared, not derived from `playing`. See [persistent].
            .setOngoing(persistent() || playing)
            .apply { if (persistent() || playing) setDeleteIntent(pi(REPOST)) }
            .let { NotifyGroups.attach(ctx, it, BADGE_ID) } // #777 group G3
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (art != null) b.setLargeIcon(art)
        BadgeModel.add(b, listOf(
            BadgeModel.Action("prev", android.R.drawable.ic_media_previous, "Prev", pi("media:prev")),
            BadgeModel.Action(if (playing) "pause" else "play",
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", pi("media:playpause")),
            BadgeModel.Action("next", android.R.drawable.ic_media_next, "Next", pi("media:next")),
        ))
        // NOTE: deliberately NOT calling setMediaSession(token). Linking our
        // notification to the playing app's session token makes Android MERGE
        // it into that app's existing media card (so our distinct "NC Media"
        // entry never appears). Without it, this is a standalone media-styled
        // notification in the list, which is what we want here.
        b.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2),
        )
        runCatching { NotifyGate.post(nm(), null, NOTIF_MEDIA, b.build()) }
    }

    /**
     * The badge with nothing to mirror. Same channel, same id, same subtext —
     * so the shade entry stays put across a track ending rather than being
     * torn down and rebuilt — but it says plainly that nothing is playing
     * instead of showing a stale last track as if it were live.
     */
    private fun postIdle() {
        ensureChannel()
        val last = LastMediaSession.load(ctx)
        val key = "idle|${last?.pkg}|${last?.title}"
        if (key == lastKey) return
        lastKey = key
        val b = NotificationCompat.Builder(ctx, MEDIA_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
            .setContentTitle(last?.title ?: ctx.getString(R.string.badge_media_idle_title))
            .setContentText(last?.artist?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.badge_media_idle_text))
            .setSubText("Cloud SA - Media")
            .setOnlyAlertOnce(true)
            .setOngoing(persistent())
            .setDeleteIntent(pi(REPOST))
            .let { NotifyGroups.attach(ctx, it, BADGE_ID) }
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        last?.art?.let { b.setLargeIcon(it) }
        // Play is the only transport that means anything with no session; the
        // other two would be buttons that silently do nothing.
        BadgeModel.add(b, BadgeModel.Action("play", android.R.drawable.ic_media_play, "Play", pi("media:playpause")))
        runCatching { NotifyGate.post(nm(), null, NOTIF_MEDIA, b.build()) }
    }

    fun cancel() {
        unwatch()
        runCatching { NotifyGate.invalidate(null, NOTIF_MEDIA); nm().cancel(NOTIF_MEDIA) }; lastKey = null
        NotifyGroups.release(ctx, BADGE_ID)
    }

    /** Media transport keeps the shade OPEN (unlike the Main quick actions), so
     *  it's a plain service PendingIntent, not the close-shade trampoline. */
    private fun pi(target: String): PendingIntent = PendingIntent.getService(
        ctx, target.hashCode(),
        Intent(ctx, FloatingNavService::class.java)
            .setAction(FloatingNavService.ACTION_NAV).putExtra(FloatingNavService.EXTRA_TARGET, target),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm().getNotificationChannel(MEDIA_CHANNEL_ID) == null) {
                nm().createNotificationChannel(NotificationChannel(
                    MEDIA_CHANNEL_ID, "Media controls", NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Cloud SuperApp media controls for the active session."; setShowBadge(false) })
            }
        }
    }

    companion object {
        const val NOTIF_MEDIA = 0xF3
        private const val MEDIA_CHANNEL_ID = "floating_nav_media"
        private const val REPOST = "media:repost"

        /** This badge's id in build.json::ui.notification_center.producers.
         *  The declaration is the contract; this is the key into it. */
        const val BADGE_ID = "media_now_playing"
    }
}
