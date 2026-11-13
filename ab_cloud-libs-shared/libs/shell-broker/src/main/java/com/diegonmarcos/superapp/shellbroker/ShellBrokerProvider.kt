package com.diegonmarcos.superapp.shellbroker

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.shellclient.ShellWire

/**
 * #1510 The broker's provider door: `exec`, `stream` and `status` as ContentProvider `call()`s on `<SuperApp>.shellbroker`,
 * exported behind the CONSTELLATION_DATA signature permission and re-checked in [call] (the framework does not permission-check
 * call()). A call starts the SuperApp's process and holds nothing afterwards: no service, no keep-alive.
 */
class ShellBrokerProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val uid = Binder.getCallingUid()
        if (!CallerId.permitted(ctx, uid)) return reply(ShellWire.encode(ShellResult.Denied("signature permission missing")))
        if (method == ShellWire.M_STATUS) return reply(ShellWire.encodeStatus(ShellBroker.status(ctx)))
        if (method == ShellWire.M_EXEC_STDIN) return execStdin(ctx, uid, arg.orEmpty(), extras)
        if (method == ShellWire.M_EXEC_OUT) return execOut(ctx, uid, arg.orEmpty(), extras)
        if (method == ShellWire.M_INVALIDATE) { ShellBroker.invalidate(ctx); return reply("{}") }
        if (method == "keeper_get" || method == "keeper_set") {   // #1682 the owner's keep-alive switch for fleet clients (allowlist entry flag `keeper`)
            val who = CallerId.of(ctx, uid, callingPackage) ?: return reply(ShellWire.encode(ShellResult.Denied("unknown caller")))
            if (!ShellBroker.mayKeeper(ctx, who)) return reply(ShellWire.encode(ShellResult.Denied("no keeper grant")))
            val hook = ShellBroker.keeperHook ?: return reply(ShellWire.encode(ShellResult.Failed("no keeper on this owner")))
            val now = runCatching { hook(ctx, if (method == "keeper_set") arg == "1" else null) }.getOrNull()
                ?: return reply(ShellWire.encode(ShellResult.Failed("keeper failed")))
            return reply("{\"keeper\":$now}")
        }
        if (method == ShellWire.M_AUDIT || method == ShellWire.M_CALLERS) {
            val who = CallerId.of(ctx, uid, callingPackage) ?: return reply(ShellWire.encode(ShellResult.Denied("unknown caller")))
            if (!ShellBroker.mayAudit(ctx, who)) return reply(ShellWire.encode(ShellResult.Denied("no audit grant")))
            return reply(if (method == ShellWire.M_AUDIT)
                ShellWire.encodeAudit(ShellBroker.auditEntries(ctx).map { ShellWire.AuditRow(it.ts, it.caller, it.cmd, it.outcome, it.exit, it.ms, it.source) })
            else ShellWire.encodeCallers(ShellBroker.callerSummaries(ctx)))
        }
        val viaJson = method == ShellWire.M_EXEC_JSON || method == ShellWire.M_STREAM_JSON
        if (method != ShellWire.M_EXEC && method != ShellWire.M_STREAM && !viaJson) return null
        val caller = CallerId.of(ctx, uid, callingPackage) ?: return reply(ShellWire.encode(ShellResult.Denied("unknown caller")))
        var cmd = arg.orEmpty()
        var timeout = extras?.getString(ShellWire.K_TIMEOUT)?.toLongOrNull() ?: ShellWire.DEFAULT_TIMEOUT_MS
        var max = extras?.getString(ShellWire.K_MAX_BYTES)?.toIntOrNull()
            ?: if (method == ShellWire.M_STREAM_JSON) ShellWire.STREAM_MAX_BYTES else ShellWire.EXEC_MAX_BYTES
        if (viaJson) {
            val o = runCatching { org.json.JSONObject(arg.orEmpty()) }.getOrNull()
                ?: return reply(ShellWire.encode(ShellResult.Denied("body is not a JSON object")))
            cmd = o.optString("cmd")
            timeout = o.optLong("timeoutMs", timeout)
            max = o.optInt("maxBytes", max)
        }
        return reply(ShellWire.encode(ShellBroker.exec(ctx, caller, cmd, timeout, max.coerceIn(1, ShellWire.EXEC_MAX_BYTES))))
    }

    /** #1512 The stdin door: the command is the arg, the input a ParcelFileDescriptor extra of [ShellWire.K_STDIN_SIZE] bytes. */
    private fun execStdin(ctx: android.content.Context, uid: Int, cmd: String, extras: Bundle?): Bundle {
        val caller = CallerId.of(ctx, uid, callingPackage) ?: return reply(ShellWire.encode(ShellResult.Denied("unknown caller")))
        @Suppress("DEPRECATION")
        val pfd = runCatching { extras?.getParcelable<ParcelFileDescriptor>(ShellWire.K_STDIN_FD) }.getOrNull()
            ?: return reply(ShellWire.encode(ShellResult.Denied("no input descriptor")))
        val size = extras?.getString(ShellWire.K_STDIN_SIZE)?.toLongOrNull() ?: -1L
        val timeout = extras?.getString(ShellWire.K_TIMEOUT)?.toLongOrNull() ?: ShellWire.STDIN_DEFAULT_TIMEOUT_MS
        val r = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { ShellBroker.execStdin(ctx, caller, cmd, it, size, timeout) }
        return reply(ShellWire.encode(r))
    }

    /** #1682 The output door: the stdout goes to the client's pipe (write end = [ShellWire.K_OUT_FD]), the reply carries an empty stdout. */
    private fun execOut(ctx: android.content.Context, uid: Int, cmd: String, extras: Bundle?): Bundle {
        @Suppress("DEPRECATION")
        val pfd = runCatching { extras?.getParcelable<ParcelFileDescriptor>(ShellWire.K_OUT_FD) }.getOrNull()
            ?: return reply(ShellWire.encode(ShellResult.Denied("no output descriptor")))
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
            val caller = CallerId.of(ctx, uid, callingPackage) ?: return reply(ShellWire.encode(ShellResult.Denied("unknown caller")))
            val timeout = extras?.getString(ShellWire.K_TIMEOUT)?.toLongOrNull() ?: ShellWire.DEFAULT_TIMEOUT_MS
            val r = ShellBroker.execOut(ctx, caller, cmd, timeout)
            if (r !is ShellResult.Ok) return reply(ShellWire.encode(r))
            runCatching { out.write(r.stdout.toByteArray(Charsets.UTF_8)) }
            return reply(ShellWire.encode(r.copy(stdout = "")))
        }
    }

    /** The client reads `json`; the Cloud-Webserver bridge reads `ok` / `mime` / `payload` (the same shape every fleet provider answers). */
    private fun reply(json: String) = Bundle().apply {
        putString(ShellWire.K_JSON, json)
        putBoolean("ok", true); putString("mime", "application/json"); putString("payload", json)
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
