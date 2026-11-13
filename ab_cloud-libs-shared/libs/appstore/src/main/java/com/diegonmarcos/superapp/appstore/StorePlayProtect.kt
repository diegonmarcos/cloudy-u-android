package com.diegonmarcos.superapp.appstore

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * #1512 Turns the Play Protect install-scan prompt off or on (the three Settings.Global values GmsCore's package verifier reads).
 * READING is unprivileged. Writing needs WRITE_SECURE_SETTINGS, which only the shell holds: this app writes directly when it was
 * granted it, else the SuperApp's shell broker runs `settings put global ...` under this app's allowlist entry.
 */
object StorePlayProtect {
    private const val CONSENT = "package_verifier_user_consent"
    private const val ENABLE = "package_verifier_enable"
    private const val ADB = "verifier_verify_adb_installs"

    data class State(val consent: Int, val enable: Int, val adb: Int) {
        val on: Boolean get() = consent >= 0 || enable != 0 || adb != 0
        fun describe(): String = "Play Protect install scan: ${if (on) "ON" else "OFF"} (consent=$consent enable=$enable adb=$adb)"
    }

    data class Result(val ok: Boolean, val channel: String, val output: String, val state: State)

    /** Unprivileged read that keeps "absent" distinct from a value. */
    internal fun reading(ctx: Context): ControlStatus.Reading {
        fun g(key: String) = runCatching { Settings.Global.getString(ctx.contentResolver, key) }.getOrNull()?.let(ControlStatus::parseSetting)
        return ControlStatus.Reading(g(CONSENT), g(ENABLE), g(ADB))
    }

    /** The same three values through the broker, for a ROM whose provider read comes back empty. Blocking. */
    internal fun readingViaShell(ctx: Context): ControlStatus.Reading {
        fun g(key: String) = ControlStatus.parseSetting(StoreShell.exec(ctx, "settings get global $key"))
        return ControlStatus.Reading(g(CONSENT), g(ENABLE), g(ADB))
    }

    fun state(ctx: Context): State {
        fun g(key: String, def: Int) = runCatching { Settings.Global.getInt(ctx.contentResolver, key, def) }.getOrDefault(def)
        return State(g(CONSENT, 1), g(ENABLE, 1), g(ADB, 1))   // a stock GMS device: consented, enabled, verifying
    }

    internal fun values(scan: Boolean): List<Pair<String, Int>> =
        listOf(CONSENT to if (scan) 1 else -1, ENABLE to if (scan) 1 else 0, ADB to if (scan) 1 else 0)

    internal fun commands(scan: Boolean): List<String> = values(scan).map { (k, v) -> "settings put global $k $v" }

    /** Blocking, off the main thread. Returns what happened plus the re-read state: a write that did not land is never reported as one. */
    fun setScanning(ctx: Context, scan: Boolean): Result {
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            val out = values(scan).joinToString("\n") { (key, value) ->
                "$key=$value -> " + runCatching { if (Settings.Global.putInt(ctx.contentResolver, key, value)) "ok" else "rejected" }
                    .getOrElse { "failed: ${it.message}" }
            }
            val after = state(ctx)
            if (after.on == scan) return Result(true, "self (WRITE_SECURE_SETTINGS)", out, after)
        }
        val st = StoreShell.status(ctx)
        if (!st.armed) return Result(false, "none",
            "No shell channel: ${st.detail}. Arm the ADB shell in Cloud C3 Webserver, then retry.", state(ctx))
        val out = commands(scan).joinToString("\n") { cmd -> cmd + " -> " + (StoreShell.exec(ctx, cmd) ?: "no output").trim().ifBlank { "ok" } }
        val after = state(ctx)
        return Result(after.on == scan, "shell broker (${st.channel})", out, after)
    }
}
