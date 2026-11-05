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
    const val K_TIMEOUT = "timeoutMs"
    const val K_MAX_BYTES = "maxBytes"
    const val K_JSON = "json"

    const val DEFAULT_TIMEOUT_MS = 15_000L
    const val MAX_TIMEOUT_MS = 120_000L
    const val STREAM_MAX_BYTES = 64 * 1024
    const val EXEC_MAX_BYTES = 256 * 1024

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
}
