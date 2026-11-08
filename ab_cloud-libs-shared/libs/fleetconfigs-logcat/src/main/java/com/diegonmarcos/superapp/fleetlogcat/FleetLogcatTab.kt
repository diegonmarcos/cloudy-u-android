package com.diegonmarcos.superapp.fleetlogcat

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsTab

/**
 * Registers the Logcat tab on the shared Configs screen for every app that links this lib. An app opts in only by adding
 * libs:fleetconfigs-logcat to its module graph — it writes no Kotlin — and the tab then draws between Perms and About
 * (General | Perms | Logcat | … | About). An app that does not link this lib is untouched.
 */
public object FleetLogcatTab {
    public const val ID: String = "logcat"

    public fun register() {
        FleetConfigsExtensions.register(FleetConfigsTab(ID, "Logcat", "code") { dark -> FleetLogcatPage(dark) })
    }
}

/** Registers [FleetLogcatTab] once per process at start (merged from this lib's manifest; not exported, answers no query). */
public class FleetLogcatInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        FleetLogcatTab.register()
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
