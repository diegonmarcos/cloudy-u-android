package com.diegonmarcos.superapp.shellclient

import org.json.JSONObject

/** #1510 The broker's wire: provider authority, methods, keys, and the JSON codec both halves share (pure, unit-tested). */
object ShellWire {
    const val BROKER_PKG = "com.diegonmarcos.superapp"
    const val AUTHORITY = "$BROKER_PKG.shellbroker"
    /** Signature permission guarding the provider, the service and the arm activity (the fleet key). */
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
    /** The exported SuperApp activity "Arm in SuperApp" opens (the ADB Shell page, or the Wireless debugging switch). */
    const val ACTION_ARM = "com.diegonmarcos.superapp.action.ARM_SHELL"

    const val M_EXEC = "exec"
    const val M_STREAM = "stream"
    const val M_STATUS = "status"
    /** The mesh door (the Cloud-Webserver shell route): the whole request is the call's arg, a JSON `{cmd, timeoutMs?, maxBytes?}`; the reply also carries ok/mime/payload. */
    const val M_EXEC_JSON = "exec_json"
    const val M_STREAM_JSON = "stream_json"
    /** #1512 The stdin door: `exec_stdin` carries the command as the arg and the input as a ParcelFileDescriptor extra ([K_STDIN_FD]) of [K_STDIN_SIZE] bytes. Provider door only (never the mesh). */
    const val M_EXEC_STDIN = "exec_stdin"
    const val K_STDIN_FD = "stdinFd"
    const val K_STDIN_SIZE = "stdinSize"
    const val K_TIMEOUT = "timeoutMs"
    const val K_MAX_BYTES = "maxBytes"
    const val K_JSON = "json"

    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val MAX_TIMEOUT_MS = 120_000L
    const val STREAM_MAX_BYTES = 64 * 1024
    const val EXEC_MAX_BYTES = 256 * 1024
    /** #1512 Hard cap of ONE stdin transfer (an APK, a split): a rule may lower it, never raise it. */
    const val STDIN_MAX_BYTES = 512L * 1024 * 1024
    const val STDIN_DEFAULT_TIMEOUT_MS = 180_000L
    const val STDIN_MAX_TIMEOUT_MS = 600_000L

    private const val S_OK = "ok"
    private const val S_NEEDS_ARM = "needs_arm"
    private const val S_DENIED = "denied"
    private const val S_FAILED = "failed"
    private const val S_TIMEOUT = "timeout"

    fun encode(r: ShellResult): String = JSONObject().apply {
        when (r) {
            is ShellResult.Ok -> put("state", S_OK).put("exitCode", r.exitCode).put("stdout", r.stdout)
                .put("stderr", r.stderr).put("source", r.source)
            ShellResult.NeedsArm -> put("state", S_NEEDS_ARM)
            is ShellResult.Denied -> put("state", S_DENIED).put("reason", r.reason)
            is ShellResult.Failed -> put("state", if (r.timedOut) S_TIMEOUT else S_FAILED).put("reason", r.reason)
        }
    }.toString()

    fun decode(json: String?): ShellResult {
        if (json.isNullOrBlank()) return ShellResult.Failed("no answer from the broker")
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return ShellResult.Failed("unreadable broker answer")
        return when (o.optString("state")) {
            S_OK -> ShellResult.Ok(o.optInt("exitCode", -1), o.optString("stdout"), o.optString("stderr"), o.optString("source"))
            S_NEEDS_ARM -> ShellResult.NeedsArm
            S_DENIED -> ShellResult.Denied(o.optString("reason", "denied"))
            S_TIMEOUT -> ShellResult.Failed(o.optString("reason", "timed out"), timedOut = true)
            else -> ShellResult.Failed(o.optString("reason", "failed"))
        }
    }

    fun encodeStatus(s: ShellStatus): String = JSONObject().put("brokerInstalled", s.brokerInstalled)
        .put("armed", s.armed).put("channel", s.channel).put("detail", s.detail).toString()

    fun decodeStatus(json: String?): ShellStatus {
        val o = runCatching { JSONObject(json.orEmpty()) }.getOrNull()
            ?: return ShellStatus(true, false, "none", "unreadable broker status")
        return ShellStatus(true, o.optBoolean("armed", false), o.optString("channel", "none"), o.optString("detail"))
    }

    /** Clamp a caller's timeout into 1 s .. [MAX_TIMEOUT_MS]; every call has one. */
    fun clampTimeout(ms: Long): Long = ms.coerceIn(1_000L, MAX_TIMEOUT_MS)

    /** #1512 Clamp a stdin call's timeout into 5 s .. [STDIN_MAX_TIMEOUT_MS] (a transfer is slower than an exec). */
    fun clampStdinTimeout(ms: Long): Long = ms.coerceIn(5_000L, STDIN_MAX_TIMEOUT_MS)

    /** #1512 Why an input of [size] bytes may not be streamed, or null when its size is acceptable. Both halves apply it. */
    fun stdinProblem(size: Long): String? = when {
        size <= 0L -> "empty input"
        size > STDIN_MAX_BYTES -> "input of $size bytes is over the $STDIN_MAX_BYTES byte limit"
        else -> null
    }
}
