package com.diegonmarcos.superapp.fleetxray

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsTab

/**
 * Registers the X-Ray tab on the shared Configs screen for every app that links this lib. An app opts in only by adding
 * libs:fleetconfigs-xray to its module graph — it writes no Kotlin — and the tab then draws between Perms and About. An app that
 * does not link this lib is untouched.
 */
public object FleetXrayTab {
    public const val ID: String = "xray"

    public fun register() {
        FleetConfigsExtensions.register(FleetConfigsTab(ID, "X-Ray App", "insights") { dark -> FleetXrayPage(dark) })
    }
}

/** Registers [FleetXrayTab] once per process at start (merged from this lib's manifest; not exported, answers no query). */
public class FleetXrayInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        FleetXrayTab.register()
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
