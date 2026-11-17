package app.sterna.push

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.sterna.container
import app.sterna.mail.OfflineSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Brings live push back after a reboot, and after the app is updated in place (#98). Only one of
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Both actions are protected broadcasts, but the receiver has to be exported to receive
        // them at all — so check what we got.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext as? Application ?: return
        // #1768: right after an app update the window is brought up to date at once (the periodic work survives updates,
        // but waits for its next slot).
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { OfflineSyncWorker.runNow(app) }
        }
        // Off the main thread, but still inside the broadcast: the decision reads the account store
        // and DataStore, and at boot the disk is cold and contended. goAsync() keeps the process at
        // receiver importance until finish(), which every path below reaches.
        //
        // What lets us start a foreground service from here is NOT goAsync(): it is the temporary
        // power exemption the system grants the UID when it dispatches BOOT_COMPLETED, bounded in
        // TIME (~20 s by default), which finish() neither extends nor ends. So the work below must
        // stay short; anything long would silently fall out of the window and the start be refused.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                restartPushIfNeeded(app)
            } catch (t: Throwable) {
                // A coroutine that lets an exception escape takes the process down, and boot
                // receivers are retried. Mail keeps arriving through the fallback poll either way.
                // This catches CancellationException too, which is safe only because
                // restartPushIfNeeded is NOT suspend and this scope is never cancelled.
                Log.w(TAG, "Push restart failed at boot; fallback poll carries delivery", t)
            } finally {
                // Exactly once, on every path: never finishing leaks the broadcast, twice throws.
                pending.finish()
            }
        }
    }

    /** Blocking — reads the account store and the delivery setting. Callers are off-main. */
    private fun restartPushIfNeeded(app: Application) {
        // Reaching the container means Application.onCreate already ran, which is what
        // (re)schedules the fallback poll — the safety net for everything below.
        val container = app.container
        val store = container.accountStore
        val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())
        val accounts = watched.map {
            AccountPushState(
                notificationsEnabled = store.notificationsEnabled(it.id),
                transport = PushController.transportFor(app, it),
            )
        }
        if (!BootRestart.needsPushService(accounts)) return
        // userInitiated false: a background arm, so mail that arrived while the device was off is
        // diffed and announced instead of being silently swallowed.
        //
        // specialUse is not among the foreground service types Android 15+ forbids starting from a
        // BOOT_COMPLETED receiver, and receiving that broadcast is itself an exemption from the
        // Android 12+ background-start restriction. Should a policy refuse anyway, take it quietly:
        // [MailFetchWorker] keeps mail coming and the account screen reports "Periodic".
        runCatching { PushService.start(app, userInitiated = false) }
            .onFailure { Log.w(TAG, "Push service refused at boot; fallback poll carries delivery", it) }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
