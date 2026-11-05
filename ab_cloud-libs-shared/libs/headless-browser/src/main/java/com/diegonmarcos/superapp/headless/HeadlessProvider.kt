package com.diegonmarcos.superapp.headless

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONObject

/**
 * #1498 the signature-permission IPC door of the headless browser (same pattern as libs:log-export's provider, #1490): exported, gated on
 * CONSTELLATION_DATA in the manifest, and [call] re-checks the caller. Methods: command | run | status | stop | loopback | cdp_on | cdp_off | store_set.
 * Cloud-Webserver calls it and serves the same API on the mesh as /browser/*. Every reply is {ok, mime, payload, error}.
 */
class HeadlessProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val granted = ctx.checkPermission(PERMISSION, Binder.getCallingPid(), Binder.getCallingUid()) == PackageManager.PERMISSION_GRANTED || Binder.getCallingUid() == Process.myUid()
        if (!granted) return reply(false, "", "caller lacks $PERMISSION")
        if (method !in METHODS) return reply(false, "", "unknown method $method")
        val rt = HeadlessRuntime.get(ctx)
        HeadlessBrowserService.ensure(ctx)
        val out = runCatching {
            when (method) {
                "command" -> rt.execute("/command", JSONObject(arg ?: "{}"))
                "run" -> rt.execute("/run", JSONObject(arg ?: "{}"))
                "status" -> rt.status()
                "stop" -> { rt.shutdown(); JSONObject().put("ok", true) }
                "loopback" -> rt.startLoopback()
                // the provider caller already holds the signature permission, which is the token gate of this door
                "cdp_on" -> rt.armCdp(true)
                "cdp_off" -> { rt.disarmCdp(); JSONObject().put("ok", true) }
                else -> JSONObject(arg ?: "{}").let { rt.storeSet(it.getString("key"), it.optString("value")); JSONObject().put("ok", true) }
            }
        }.getOrElse { HeadlessApi.fail(it) }
        return reply(out.optBoolean("ok"), out.toString(), out.optJSONObject("error")?.optString("message").orEmpty())
    }

    private fun reply(ok: Boolean, payload: String, error: String) = Bundle().apply { putBoolean("ok", ok); putString("mime", "application/json"); putString("payload", payload); putString("error", error) }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0

    companion object {
        const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
        val METHODS = setOf("command", "run", "status", "stop", "loopback", "cdp_on", "cdp_off", "store_set")
        fun authority(pkg: String) = "$pkg.headless"
    }
}
