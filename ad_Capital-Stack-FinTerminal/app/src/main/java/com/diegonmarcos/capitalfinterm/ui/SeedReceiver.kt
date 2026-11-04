package com.diegonmarcos.capitalfinterm.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.diegonmarcos.capitalfinterm.Alerts

/**
 * Test seed for the emulator walk (#1370): `adb shell am broadcast -a com.diegonmarcos.capitalfinterm.SEED --es watchlist AAPL,MSFT,NVDA`
 * writes the watchlist into the encrypted box so the long-press menu and the reorder can be shot. Only the shell (permission DUMP) can send it;
 * it carries symbols only (validated by Alerts.watchlist), never a key or a holding.
 */
class SeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val list = Alerts.watchlist(intent.getStringExtra("watchlist") ?: return)
        EncryptedSecretBox(context).put(KEY_WATCHLIST, Alerts.watchlistText(list))
    }
}
