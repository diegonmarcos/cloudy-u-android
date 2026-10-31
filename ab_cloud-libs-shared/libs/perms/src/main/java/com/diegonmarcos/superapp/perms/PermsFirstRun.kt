package com.diegonmarcos.superapp.perms

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * #1360 first run asks for everything missing; #1352 v2 makes it per grant: every catalogue entry this app declares (its
 * own and its helpers') must have been put in front of the user at least once (PermAskLog.askedIds). On each launch from
 * the launcher, the missing entries never asked yet open the app's Perms page with those rows highlighted and Grant all on
 * top, and are recorded as asked. A grant added in a later version is therefore asked once on the first launch after the
 * update. [ASK_STORE_VERSION] versions the asked-state: raising it forgets every recorded ask once, so every app asks
 * all its missing grants again on its next launch. Off when the app asks itself (perms.first_run "own:..."), under
 * instrumentation or a test harness unless `settings put global cloud_perms_first_run 2` (the perms emulator proof), and
 * when `settings put global cloud_perms_first_run 0` (emulator walks).
 */
object PermsFirstRun {
    /** 2 = #1352: every app does a fresh ask of all its permissions on the first launch after this update. */
    const val ASK_STORE_VERSION = 2
    const val GLOBAL_OFF = "cloud_perms_first_run"

    fun install(app: Application) {
        if (AppPerms.decl(app).firstRun != "libs:perms") return
        PermAskLog.migrate(app, ASK_STORE_VERSION)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is PermsActivity) return
                app.unregisterActivityLifecycleCallbacks(this)
                if (!fromLauncher(activity.intent) || suppressed(activity)) return
                val ids = neverAsked(activity)
                if (ids.isEmpty()) return
                PermAskLog.markAskedIds(activity, ids)
                runCatching { activity.startActivity(PermsActivity.intent(activity, PermsActivity.FIRST_RUN, ids)) }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** The catalogue ids this app declares, missing on this device and never shown to the user (own first, then helpers'). */
    fun neverAsked(ctx: Context): List<String> {
        val asked = PermAskLog.askedIds(ctx)
        return (AppPerms.missingOwn(ctx).map { it.id } + AppPerms.missingHelpers(ctx).map { it.entry.id }).distinct().filter { it !in asked }
    }

    fun fromLauncher(i: Intent?): Boolean = i != null && i.action == Intent.ACTION_MAIN && i.hasCategory(Intent.CATEGORY_LAUNCHER)

    /** `cloud_perms_first_run`: 0 = never ask (emulator walks), 2 = ask even under a test harness or monkey (the perms
     *  emulator proof, whose AVD reports ro.test_harness=1 and ro.monkey=1), anything else = the default below. */
    private fun global(ctx: Context): Int = runCatching { Settings.Global.getInt(ctx.contentResolver, GLOBAL_OFF, 1) }.getOrDefault(1)

    private fun suppressed(ctx: Context): Boolean = global(ctx) != 2 && (
        ActivityManager.isRunningInTestHarness() || ActivityManager.isUserAMonkey() ||
            global(ctx) == 0 ||
            runCatching { Class.forName("androidx.test.platform.app.InstrumentationRegistry"); true }.getOrDefault(false))
}
