package com.diegonmarcos.cloudlib.gitsync

import android.content.Context
import android.os.Build
import android.os.Environment
import com.diegonmarcos.cloudlib.gitcore.GitCalls
import java.io.File
import org.json.JSONObject

/**
 * #1408 the default [GitTransport]: libs:git-core's [GitCalls] answered in the host's own process. No bind,
 * no second APK, no second grant: git reads and writes with the host's own storage access (Cloud Drive's
 * All files access), on any root the host can open (its own files, emulated storage, a USB volume).
 * One [GitCalls] per process, so a long result's chunks outlive a single call.
 */
class InProcessGitTransport private constructor(private val calls: GitCalls) : GitTransport {
    override fun unavailable(): String? = null

    override fun session(): GitTransport.Session = object : GitTransport.Session {
        override fun git(request: JSONObject): JSONObject = JSONObject(calls.git(request))
        override fun chunk(request: JSONObject): JSONObject = JSONObject(calls.chunk(request))
        override fun info(): JSONObject = JSONObject(calls.info())
        override fun close() {}
    }

    companion object {
        const val VERSION = "jgit-7.8.0"
        @Volatile private var one: InProcessGitTransport? = null

        fun get(context: Context): InProcessGitTransport = one ?: synchronized(this) {
            one ?: InProcessGitTransport(GitCalls(File(context.applicationContext.cacheDir, "git-scratch"), VERSION) {
                Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
            }).also { one = it }
        }
    }
}
