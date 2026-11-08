package com.diegonmarcos.superapp.notificationcenter

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.core.FleetAlerts
import com.diegonmarcos.superapp.network.MeshDownModel
import com.diegonmarcos.superapp.network.MeshIntent
import com.diegonmarcos.superapp.network.MeshSupervisorRunner

/**
 * #1120 The mesh-down alert: raised in the notification centre (a plain ungrouped shade notification; the Alerts group is gone)
 * once [MeshDownModel] says the mesh has stayed down, withdrawn by itself when it recovers. Its one button,
 * Reconnect, is a [BadgeModel.Action] like every badge button (#1113), so the notification and the Notify
 * card read it from the same place.
 */
object MeshDownAlert {
    const val DEDUPE = "mesh.down"
    const val ACTION_RECONNECT = "com.diegonmarcos.superapp.network.MESH_RECONNECT"
    private const val RC_RECONNECT = 0x4E40

    /** The buttons the alert carries; empty for every other alert. */
    fun actionsFor(ctx: Context, a: AlertStore.Alert): List<BadgeModel.Action> =
        if (a.dedupeKey != DEDUPE) emptyList()
        else listOf(BadgeModel.Action("reconnect", R.drawable.ic_act_refresh, "Reconnect", reconnectIntent(ctx)))

    fun reconnectIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, RC_RECONNECT, Intent(ctx, ReconnectReceiver::class.java).setAction(ACTION_RECONNECT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun raise(ctx: Context, r: MeshDownModel.Reading, sinceMs: Long) {
        val a = FleetAlerts.Alert(MeshDownModel.title(r), MeshDownModel.text(r, sinceMs), FleetAlerts.ERROR, "page:config/wg", DEDUPE)
        val e = AlertStore.add(ctx, ctx.packageName, a)
        AlertsNotifier.refresh(ctx, e.id)
    }

    fun clear(ctx: Context) {
        if (AlertStore.withdraw(ctx, ctx.packageName, DEDUPE)) AlertsNotifier.refresh(ctx)
    }

    /** The Reconnect button: a tap is the owner's Connect, so the intent flips back to wanted and the supervisor acts at once. */
    class ReconnectReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_RECONNECT) return
            MeshIntent.setWanted(ctx, true)
            MeshSupervisorRunner.reconnectNow(ctx)
        }
    }
}
