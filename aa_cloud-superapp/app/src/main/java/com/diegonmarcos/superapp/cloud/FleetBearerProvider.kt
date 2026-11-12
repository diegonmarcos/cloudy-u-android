package com.diegonmarcos.superapp.cloud

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.diegonmarcos.superapp.appstore.FleetBearer
import com.diegonmarcos.superapp.core.FleetConfig
import com.diegonmarcos.superapp.ops.dagu.DaguPrefs
import com.diegonmarcos.superapp.fleetendpoints.android.AccountClient

/**
 * #866 The fleet bearer (libs:ops DaguPrefs) for Cloud Store's Commits / CI-CD
 * feeds. READ-ONLY: one method, [FleetBearer.METHOD_BEARER]; nothing a caller
 * sends is written. Exported behind CONSTELLATION_DATA (signature) and
 * re-checked in call(), because the framework does not permission-check call()
 * by itself - the AccountData.Provider pattern. The token is never logged.
 *
 * #874 the token's source is Cloud Account (AccountData.secret "fleet.bearer", granted per package);
 * this app reads through to it first and falls back to its own DaguPrefs.
 */
class FleetBearerProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return refuse()
        if (Binder.getCallingUid() != Process.myUid() &&
            ctx.checkCallingPermission(FleetConfig.PERMISSION) != PackageManager.PERMISSION_GRANTED) return refuse()
        if (method != FleetBearer.METHOD_BEARER) return refuse()
        // #874 Cloud Account is the source of the fleet bearer (its Connections, `fleet.bearer`, behind a grant);
        // what DaguPrefs holds is the copy Fleet Setup pushed, or the owner's own login on a phone without Cloud Account.
        val token = AccountClient.secret(ctx, "fleet.bearer").trim()
            .ifEmpty { runCatching { DaguPrefs(ctx).bearerToken }.getOrDefault("") }
        return Bundle().apply { putBoolean(FleetBearer.KEY_OK, true); putString(FleetBearer.KEY_TOKEN, token) }
    }

    private fun refuse() = Bundle().apply { putBoolean(FleetBearer.KEY_OK, false) }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
