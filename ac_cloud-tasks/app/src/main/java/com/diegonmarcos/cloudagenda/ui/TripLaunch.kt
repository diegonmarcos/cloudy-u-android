package com.diegonmarcos.cloudagenda.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.diegonmarcos.cloudagenda.data.TripRoadmap
import com.diegonmarcos.cloudagenda.data.TripTarget

/** #1975 What a tap on a trip tile does: a page of this app (go), a Cloud Me page (its shortcut_action deep link), or another app's launcher. */
internal fun openTripTarget(context: Context, target: String, trip: TripRoadmap, go: (String, String?) -> Unit) {
    when (val t = TripTarget.parse(target)) {
        is TripTarget.Here -> go(t.section, t.page)
        is TripTarget.CloudMe -> launch(context, trip.apps["cloudme"], t.page)
        is TripTarget.App -> launch(context, trip.apps[t.id], null)
        null -> Unit
    }
}

private fun launch(context: Context, pkg: String?, deepLink: String?) {
    val intent = pkg?.let { context.packageManager.getLaunchIntentForPackage(it) }
    if (intent == null) { Toast.makeText(context, "Not installed: " + (pkg ?: "unknown app"), Toast.LENGTH_SHORT).show(); return }
    if (deepLink != null) intent.putExtra("shortcut_action", deepLink)
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
