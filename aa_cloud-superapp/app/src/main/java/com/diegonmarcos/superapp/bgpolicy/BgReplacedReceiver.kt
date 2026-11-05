package com.diegonmarcos.superapp.bgpolicy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * #1506 After an app update the chosen policy is applied ONCE and the work ends: PACKAGE_REPLACED names the package, MY_PACKAGE_REPLACED
 * (this app's own update) re-applies every chosen package. No service, no job, no retry timer: without the shell the skip is written
 * to the audit log and nothing waits for it. PACKAGE_REPLACED is delivered to a registered receiver only (App.onCreate), the
 * manifest entry carries MY_PACKAGE_REPLACED.
 */
class BgReplacedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pkg = when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> null
            Intent.ACTION_PACKAGE_REPLACED -> intent.data?.schemeSpecificPart ?: return
            else -> return
        }
        val pending = goAsync()
        Thread({ try { BgReplaced.apply(app, pkg) } finally { pending.finish() } }, "bg-reapply").start()
    }

    companion object {
        fun register(app: Context) {
            runCatching {
                ContextCompat.registerReceiver(app, BgReplacedReceiver(), IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
                }, ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }
    }
}

object BgReplaced {
    /** Apply the persisted choice to [pkg] (or to every chosen package when null), once. Returns the outcomes (empty = nothing chosen). */
    fun apply(ctx: Context, pkg: String?): List<BgOutcome> {
        val store = BgStore(BgPrefsKv(ctx))
        val targets = if (pkg != null) listOf(pkg).filter { store.choice(it) != null } else store.choices().keys.toList()
        if (targets.isEmpty()) return emptyList()
        val engine = BgHost.engine(ctx)
        if (!engine.available) {
            BgAudit.append(ctx, "bg-policy reapply skipped for ${targets.size} package(s): ${BgOutcome.NO_ADB}")
            return emptyList()
        }
        return targets.mapNotNull { engine.reapply(it) }
    }
}
