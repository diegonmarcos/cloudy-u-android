package com.diegonmarcos.superapp.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.bgpolicy.BgHost
import com.diegonmarcos.superapp.notificationcenter.NetworkBadgeService
import com.diegonmarcos.superapp.settings.PresetModes
import com.diegonmarcos.superapp.settings.PresetState

/**
 * #1847 the optional auto side of Banking Apps Mode: fed the foreground package by the poll the floating-nav host already runs
 * (UsageStats, no new service, no new permission). A bank (build.json banking entry `banks`, plus the owner's additions in the
 * mode's settings) coming to the foreground offers or applies the mode; leaving it offers or restores. Off by default. It selects
 * the mode through the same [PresetModes.select] a tap does, with the shell only when it is ALREADY armed ([BgHost.shell] never
 * arms it): nothing here can raise an ADB pairing prompt (#1833 gate).
 */
object BankingWatcher {
    private const val CHANNEL = "banking_offer"
    private const val ID = 7747
    @Volatile private var prev: String? = null

    fun onForeground(ctx: Context, fg: String?) {
        val app = ctx.applicationContext
        val before = prev
        if (fg == before) return
        prev = fg ?: before
        val modeOn = PresetState.mode(app) == BankingMode.MODE_ID
        val act = BankingMode.decide(fg, before, BankingMode.banks(app), modeOn, BankingMode.auto(app), BankingMode.autoApplied(app))
        when (act) {
            BankingMode.Action.NONE -> Unit
            BankingMode.Action.APPLY_ON -> PresetModes.async {
                BankingMode.setAutoApplied(app, true)
                PresetModes.select(app, BankingMode.MODE_ID, BgHost.shell(app))
            }
            BankingMode.Action.APPLY_OFF -> PresetModes.async {
                PresetModes.off(app, BgHost.shell(app))
                BankingMode.setAutoApplied(app, false)
            }
            BankingMode.Action.OFFER_ON -> offer(app, "Banking app opened", "Turn on Banking Apps Mode (it restores everything when you turn it off).")
            BankingMode.Action.OFFER_OFF -> offer(app, "Left the banking app", "Banking Apps Mode is still on: open Quick actions > Preset to restore.")
        }
    }

    private fun offer(app: Context, title: String, text: String) {
        if (!NetworkBadgeService.canPost(app)) return
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Banking Apps Mode", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(app, ID,
                Intent(app, MainActivity::class.java).putExtra("shortcut_action", "page:config/presets")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(ID, NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
