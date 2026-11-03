package com.diegonmarcos.cloudlib.sharedstore

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.termux.cloud.ICloudExec
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The EXEC fallback of [StoreAccess]: a shell script INSIDE cld.termux's glibc root (store at
 * /root/<dir>, Debian's git, the user's gitconfig) through the signature-level CLOUD_EXEC service.
 * Blocking. One call is capped at 120 s, so [job] runs detached and is polled. Secrets go on stdin.
 */
class StoreExec(private val ctx: Context) {

    data class Result(val exit: Int, val out: String, val err: String) { val ok get() = exit == 0 }

    private fun <T> bound(block: (ICloudExec) -> T): T {
        val latch = CountDownLatch(1)
        var svc: ICloudExec? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { svc = ICloudExec.Stub.asInterface(binder); latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { svc = null }
        }
        val intent = Intent(BuildConfig.EXEC_ACTION).setPackage(BuildConfig.OWNER_PACKAGE)
        check(ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)) { "cannot bind ${BuildConfig.EXEC_ACTION} in ${BuildConfig.OWNER_PACKAGE}" }
        try {
            check(latch.await(15, TimeUnit.SECONDS)) { "CLOUD_EXEC did not connect" }
            return block(svc ?: error("CLOUD_EXEC disconnected"))
        } finally { runCatching { ctx.unbindService(conn) } }
    }

    /** [script] in the guest (sh -c), stdin [stdin], at most [timeoutMs] (<= 120 s). */
    fun run(script: String, stdin: String? = null, timeoutMs: Int = 60_000): Result = call(guestWrap(script), stdin, timeoutMs)

    /** [script] on the HOST side of cld.termux (its own uid: its whole data dir is readable), not in the glibc guest. */
    fun runHost(script: String, timeoutMs: Int = 60_000, stdin: String? = null): Result = call(script, stdin, timeoutMs)

    private fun call(script: String, stdin: String?, timeoutMs: Int): Result = bound { exec ->
        val b = exec.exec("/system/bin/sh", arrayOf("-c", script), stdin, null, timeoutMs)
        val err = b.getString("error")
        Result(if (err != null) -1 else b.getInt("exit"), b.getString("stdout").orEmpty(), (b.getString("stderr").orEmpty() + (err?.let { "\n$it" } ?: "")).trim())
    }

    /** A long-running [script]: started detached from the HOST side ([jobStart]), polled until it writes its exit code or [deadlineMs] passes. */
    fun job(script: String, stdin: String? = null, deadlineMs: Long = 30 * 60_000L): Result {
        val id = UUID.randomUUID().toString()
        val dir = "\$HOME/${BuildConfig.STORE_DIR}/.jobs"
        val started = runHost(jobStart(script, dir, id), stdin = stdin ?: "")
        if (!started.ok) return started
        val until = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < until) {
            Thread.sleep(2_000)
            val r = runHost("[ -f $dir/$id.rc ] && cat $dir/$id.rc", timeoutMs = 20_000)
            if (r.ok && r.out.isNotBlank()) {
                val log = runHost("tail -c 16000 $dir/$id.log; rm -f $dir/$id.log $dir/$id.rc", timeoutMs = 20_000).out
                return Result(r.out.trim().toIntOrNull() ?: -1, log, "")
            }
        }
        return Result(-1, "", "job $id outlived ${deadlineMs / 1000}s (log: ~/${BuildConfig.STORE_DIR}/.jobs/$id.log)")
    }

    companion object {
        /** Single-quote [s] for sh. */
        fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        /**
         * enter.sh hands -c to root's login shell, fish, which reads \' inside '...' as an escape and
         * refused every twice-quoted [job] ("Unsupported use of '='"). So the script travels base64
         * (nothing any shell interprets): `exec /bin/sh -c "$(echo B64 | base64 -d)"` reads alike in fish, bash, zsh, sh.
         */
        fun guestWrap(script: String, termuxShell: String = "\$HOME/.termux/shell"): String =
            "if [ -e \"$termuxShell\" ]; then exec /system/bin/sh \"$termuxShell\" -c " + q(loginLine(script)) +
                "; else exec /system/bin/sh -c " + q(loginLine(script)) + "; fi"

        /**
         * #1436 the script runs behind [ENV_PRELUDE] (resources/cloud-exec-env.sh): HOME, PATH, the shell and git resolve in
         * BOTH logins enter.sh can give -- the proot rootfs (HOME=/root, Debian git) and its plain-Termux fallback (HOME =
         * the Termux home, guest /root/ paths rewritten to it, git from Termux, `pkg install -y git` when missing). Before,
         * the fallback ran the guest script as-is: "mkdir: cannot create directory '/root'" and "git: not found" (exit 127).
         */
        fun loginLine(script: String): String {
            val b64 = java.util.Base64.getEncoder()
            val inner = b64.encodeToString(script.toByteArray(Charsets.UTF_8))
            val outer = b64.encodeToString(("CLOUD_EXEC_SCRIPT_B64=$inner\n" + ENV_PRELUDE).toByteArray(Charsets.UTF_8))
            return "exec /bin/sh -c \"\$(echo $outer | base64 -d)\""
        }

        /** The one copy of the prelude, a java resource of this lib (also read by ac_cloud-termux/test/test-git-exec-modes.sh). */
        val ENV_PRELUDE: String by lazy {
            (StoreExec::class.java.classLoader ?: ClassLoader.getSystemClassLoader()).getResourceAsStream("cloud-exec-env.sh")
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("shared-store carries no cloud-exec-env.sh resource")
        }

        /** #1392 no `nohup`: it is not on cld.termux's PATH on every phone (exit 127, "force_pull failed"); the subshell ignores HUP itself.
         * #1358 a [job]'s detached start, run on the HOST: a session's proot is --kill-on-exit, so a job backgrounded inside it died when the call returned (no .rc, polled to the deadline); its own session ([loginLine]) lives until the script ends. Stdin to <dir>/<id>.in (read BEFORE the braces: a trailing & would background the whole && list, stdin /dev/null), output to .log, exit code to .rc. */
        fun jobStart(script: String, dir: String, id: String, termuxShell: String = "\$HOME/.termux/shell"): String = "mkdir -p $dir && chmod 700 $dir && cat > $dir/$id.in && " +
            "{ ( trap '' HUP; CLOUD_EXEC_JOB=1; export CLOUD_EXEC_JOB; if [ -e \"$termuxShell\" ]; then /system/bin/sh \"$termuxShell\" -c ${q(loginLine(script))}; else /system/bin/sh -c ${q(loginLine(script))}; fi < $dir/$id.in > $dir/$id.log 2>&1; echo \$? > $dir/$id.rc; rm -f $dir/$id.in ) >/dev/null 2>&1 & }"
    }
}
