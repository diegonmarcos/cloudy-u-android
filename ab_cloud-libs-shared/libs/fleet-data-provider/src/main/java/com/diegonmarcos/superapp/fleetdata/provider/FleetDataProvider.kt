package com.diegonmarcos.superapp.fleetdata.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/**
 * Cloud Store's provider (authority `cloud.store.data`, signature permission CONSTELLATION_DATA in the manifest): openFile only. Every
 * other operation is refused (null / 0); the policy and the files live in [FleetDataEndpoint].
 */
open class FleetDataProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        return FleetDataEndpoint.of(ctx, uri.authority ?: return null).openFile(uri, mode, callingPackage)
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
