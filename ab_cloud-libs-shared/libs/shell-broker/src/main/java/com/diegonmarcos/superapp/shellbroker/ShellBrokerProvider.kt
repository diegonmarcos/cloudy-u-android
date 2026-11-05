package com.diegonmarcos.superapp.shellbroker

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
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
        if (method != ShellWire.M_EXEC && method != ShellWire.M_STREAM) return null
        val caller = CallerId.of(ctx, uid, callingPackage) ?: return reply(ShellWire.encode(ShellResult.Denied("unknown caller")))
        val timeout = extras?.getString(ShellWire.K_TIMEOUT)?.toLongOrNull() ?: ShellWire.DEFAULT_TIMEOUT_MS
        val max = extras?.getString(ShellWire.K_MAX_BYTES)?.toIntOrNull() ?: ShellWire.EXEC_MAX_BYTES
        return reply(ShellWire.encode(ShellBroker.exec(ctx, caller, arg.orEmpty(), timeout, max.coerceIn(1, ShellWire.EXEC_MAX_BYTES))))
    }

    private fun reply(json: String) = Bundle().apply { putString(ShellWire.K_JSON, json) }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
