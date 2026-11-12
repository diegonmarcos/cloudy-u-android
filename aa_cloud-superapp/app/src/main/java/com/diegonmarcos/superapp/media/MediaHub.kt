package com.diegonmarcos.superapp.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * #1635: the ONE media state + transport layer. [NowPlayingMonitor] publishes every live session here; the home Media
 * Island popup and the Notify page's persistent Media badge read [state] and drive the session through [send], so both
 * surfaces always act on the same, current session (never a controller captured when a popup opened).
 *
 * The state is the REAL session list from MediaSessionManager - nothing is invented: no session -> an empty list (the
 * card then shows the persisted last Tidal / Spotify session from [LastMediaSession]), no notification-listener access ->
 * [Access.MISSING] with a reason, never a crash.
 */
object MediaHub {

    enum class Access { UNKNOWN, GRANTED, MISSING }

    /** One active media session, as the cards show it. [key] identifies the session across re-queries. */
    data class Session(
        val key: String, val pkg: String, val title: String, val artist: String,
        val playing: Boolean, val positionMs: Long, val durationMs: Long,
    ) { val held: Boolean get() = LastMediaSession.isHeldSource(pkg) }

    data class State(val access: Access = Access.UNKNOWN, val sessions: List<Session> = emptyList(), val targetKey: String? = null) {
        val target: Session? get() = sessions.firstOrNull { it.key == targetKey }
        /** Why the controls cannot reach a session, or null when they can. */
        val reason: String? get() = when {
            access == Access.MISSING -> "Notification access is off - allow it so the media controls can reach the player"
            sessions.isEmpty() -> "No media session is active"
            else -> null
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var controllers: Map<String, MediaController> = emptyMap()
    /** The session the user picked in the session switcher; kept while it exists. */
    @Volatile var selectedKey: String? = null
        private set

    fun keyOf(c: MediaController): String = "${c.packageName}@${runCatching { c.sessionToken.hashCode() }.getOrDefault(0)}"

    /**
     * Pure: which session the controls drive. The user-selected one while it still exists; else a PLAYING held source
     * (Tidal / Spotify); else any PLAYING session (that is what the island lights up for); else a paused held source
     * (#1648: never a paused Telegram / browser session); else none.
     */
    fun <T> pickTarget(sessions: List<T>, key: (T) -> String, pkg: (T) -> String, playing: (T) -> Boolean, selected: String?): T? {
        sessions.firstOrNull { selected != null && key(it) == selected }?.let { return it }
        val playingOnes = sessions.filter(playing)
        return playingOnes.firstOrNull { LastMediaSession.isHeldSource(pkg(it)) }
            ?: playingOnes.firstOrNull()
            ?: sessions.firstOrNull { LastMediaSession.isHeldSource(pkg(it)) }
    }

    /** Publish the monitor's live controllers. Returns the target controller. */
    fun publish(list: List<MediaController>): MediaController? {
        val map = LinkedHashMap<String, MediaController>()
        for (c in list) runCatching { map[keyOf(c)] = c }
        controllers = map
        val sessions = map.map { (k, c) -> runCatching { info(k, c) }.getOrNull() }.filterNotNull()
        if (selectedKey != null && sessions.none { it.key == selectedKey }) selectedKey = null
        val t = pickTarget(sessions, { it.key }, { it.pkg }, { it.playing }, selectedKey)
        _state.value = State(Access.GRANTED, sessions, t?.key)
        return t?.let { map[it.key] }
    }

    fun publishAccessMissing() {
        controllers = emptyMap()
        _state.value = State(Access.MISSING, emptyList(), null)
    }

    /** Pick a session in the switcher: the controls drive it from now on (while it exists). */
    fun select(key: String?) {
        selectedKey = key
        val s = _state.value
        val t = pickTarget(s.sessions, { it.key }, { it.pkg }, { it.playing }, key)
        _state.value = s.copy(targetKey = t?.key)
    }

    /** Back to the start state (tests). */
    internal fun reset() { controllers = emptyMap(); selectedKey = null; _state.value = State() }

    fun controller(key: String?): MediaController? = key?.let { controllers[it] }

    /** The live controller the controls should use right now; [preferred] wins while its session is still listed. */
    fun current(preferred: MediaController? = null): MediaController? {
        if (preferred != null) controllers[keyOf(preferred)]?.let { return it }
        return controller(_state.value.targetKey)
    }

    private fun info(key: String, c: MediaController): Session {
        val md = c.metadata
        val st = c.playbackState
        val playing = st?.state == PlaybackState.STATE_PLAYING
        return Session(
            key, c.packageName,
            md?.let { it.getString(MediaMetadata.METADATA_KEY_TITLE) ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) }.orEmpty(),
            md?.let { it.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) }.orEmpty(),
            playing,
            st?.let { LastMediaSession.positionAt(it.position, it.lastPositionUpdateTime, it.playbackSpeed, playing, SystemClock.elapsedRealtime()) }?.coerceAtLeast(0) ?: 0L,
            md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
        )
    }

    /** Whether the app's notification listener is enabled (getActiveSessions needs it). */
    fun listenerEnabled(ctx: Context): Boolean = runCatching {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    }.getOrDefault(false)

    /** A fresh session list straight from the system (null without access). */
    fun query(ctx: Context): List<MediaController>? = runCatching {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
        msm.getActiveSessions(ComponentName(ctx, PhoneNotificationListenerService::class.java))
    }.getOrNull()

    fun openListenerSettings(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    // ── transport ───────────────────────────────────────────────────────────────────────────────────────────────

    enum class Action { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREV, SEEK, OPEN }

    /** How an action was delivered. */
    enum class Route { CONTROLLER, RESUME_LAST, MEDIA_KEY, LAUNCH, NONE }

    /**
     * Pure routing: a live controller takes every action; without one, Play / Play-Pause restarts the persisted last
     * Tidal / Spotify session, Open launches it, and Next / Prev / Pause go out as a system media key (which the system
     * routes to its media-button session, and which needs no notification-listener access). Seek needs a session.
     */
    fun route(action: Action, hasController: Boolean, hasLast: Boolean): Route = when {
        hasController -> Route.CONTROLLER
        action == Action.OPEN -> if (hasLast) Route.LAUNCH else Route.NONE
        action == Action.SEEK -> Route.NONE
        (action == Action.PLAY || action == Action.PLAY_PAUSE) && hasLast -> Route.RESUME_LAST
        else -> Route.MEDIA_KEY
    }

    fun keyCode(action: Action): Int = when (action) {
        Action.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        Action.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
        Action.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
        Action.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
        Action.PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        else -> KeyEvent.KEYCODE_UNKNOWN
    }

    /**
     * Send [action] to the session the controls drive: [preferred] while it is still live, else the current target, else
     * a fresh query. Never throws. Returns the route taken.
     */
    fun send(ctx: Context, action: Action, preferred: MediaController? = null, seekMs: Long = 0L): Route {
        val c = current(preferred) ?: preferred?.takeIf { p -> query(ctx)?.any { it.sessionToken == p.sessionToken } == true }
            ?: query(ctx)?.let { list -> pickTarget(list, ::keyOf, { it.packageName }, { it.playbackState?.state == PlaybackState.STATE_PLAYING }, selectedKey) }
        val last = if (c == null) LastMediaSession.load(ctx) else null
        val r = route(action, c != null, last != null)
        val ok = runCatching {
            when (r) {
                Route.CONTROLLER -> c!!.transportControls.let { t ->
                    when (action) {
                        Action.PLAY_PAUSE -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
                        Action.PLAY -> t.play()
                        Action.PAUSE -> t.pause()
                        Action.NEXT -> t.skipToNext()
                        Action.PREV -> t.skipToPrevious()
                        Action.SEEK -> t.seekTo(seekMs.coerceAtLeast(0))
                        Action.OPEN -> open(ctx, c.packageName)
                    }
                }
                Route.RESUME_LAST -> LastMediaSession.resume(ctx)
                Route.LAUNCH -> open(ctx, last!!.pkg)
                Route.MEDIA_KEY -> mediaKey(ctx, keyCode(action))
                Route.NONE -> Unit
            }
            true
        }.getOrDefault(false)
        return if (ok) r else Route.NONE
    }

    private fun open(ctx: Context, pkg: String) {
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
    }

    private fun mediaKey(ctx: Context, code: Int) {
        if (code == KeyEvent.KEYCODE_UNKNOWN) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
