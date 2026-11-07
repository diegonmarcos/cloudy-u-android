package com.diegonmarcos.superapp.torrent

import android.content.Context
import android.os.Environment
import java.io.File

/** The engine's settings. [PrefsSettings] keeps them in the app's private preferences; tests use [MemorySettings]. */
interface TorrentSettings {
    /** The folder new torrents are saved to. */
    var saveDir: File
    /** True when [saveDir] was chosen by the user (false = the default Downloads/Cloudy-store/Torrent). */
    val saveDirIsCustom: Boolean
    fun resetSaveDir()
    /** Keep uploading after a download finishes (new torrents inherit it; each torrent can override). */
    var seedAfterComplete: Boolean
    /** Stop seeding at this upload/download ratio; 0 = no limit. */
    var seedRatioLimit: Float
    /** The default folder (what [resetSaveDir] returns to). */
    val defaultDir: File
}

class MemorySettings(override val defaultDir: File) : TorrentSettings {
    private var custom: File? = null
    override var saveDir: File
        get() = custom ?: defaultDir
        set(v) { custom = v }
    override val saveDirIsCustom: Boolean get() = custom != null
    override fun resetSaveDir() { custom = null }
    override var seedAfterComplete: Boolean = true
    override var seedRatioLimit: Float = 0f
}

/** Settings in SharedPreferences `libs_torrent`. The default folder is Downloads/Cloudy-store/Torrent; when the app cannot
 *  create it (no all-files access yet) the default degrades to the app's own Downloads folder, and [usingFallback] says so
 *  so the page can ask for the grant instead of failing silently. */
class PrefsSettings(ctx: Context) : TorrentSettings {
    private val app = ctx.applicationContext
    private val p = app.getSharedPreferences("libs_torrent", Context.MODE_PRIVATE)
    private val publicDefault: File get() = defaultFor()
    private var memo: Pair<Long, File>? = null
    /** Downloads/Cloudy-store/Torrent when it can be created and written (created here if missing), else the app folder.
     *  The answer is kept for a few seconds: a page reads this on every redraw. */
    override val defaultDir: File
        @Synchronized get() {
            val now = System.currentTimeMillis()
            memo?.takeIf { now - it.first < 5_000L }?.let { return it.second }
            return (if (TorrentPaths.prepare(publicDefault).ok) publicDefault else fallbackDir()).also { memo = now to it }
        }
    val usingFallback: Boolean get() = !saveDirIsCustom && defaultDir != publicDefault
    /** The app-specific folder: always writable, invisible to other apps. */
    fun fallbackDir(): File = TorrentPaths.defaultDir(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(app.filesDir, "Download"))
    override var saveDir: File
        get() = p.getString(KEY_DIR, null)?.let(::File) ?: defaultDir
        set(v) { p.edit().putString(KEY_DIR, v.path).apply() }
    override val saveDirIsCustom: Boolean get() = p.getString(KEY_DIR, null) != null
    override fun resetSaveDir() { p.edit().remove(KEY_DIR).apply() }
    override var seedAfterComplete: Boolean
        get() = p.getBoolean(KEY_SEED, true)
        set(v) { p.edit().putBoolean(KEY_SEED, v).apply() }
    override var seedRatioLimit: Float
        get() = p.getFloat(KEY_RATIO, 0f)
        set(v) { p.edit().putFloat(KEY_RATIO, v).apply() }

    companion object {
        private const val KEY_DIR = "save_dir"
        private const val KEY_SEED = "seed_after_complete"
        private const val KEY_RATIO = "seed_ratio_limit"

        /** The public Downloads directory's default torrent folder on this device. */
        @Suppress("DEPRECATION")
        fun defaultFor(): File = TorrentPaths.defaultDir(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
    }
}
