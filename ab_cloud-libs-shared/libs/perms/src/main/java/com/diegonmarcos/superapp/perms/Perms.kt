package com.diegonmarcos.superapp.perms

import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings

/**
 * The permission contract (#1274). The host app's Permissions page is the ONE place that asks for, checks and opens the
 * settings of any permission. A feature lib (wifi, bluetooth, nfc, mounts, ...) only:
 *  1. declares its needs as catalogue `features` (perms-catalogue.json),
 *  2. reads [PermsNeeds.missing] to decide whether to show its compact "Permissions needed" banner,
 *  3. calls [PermsLink.open] from that banner, which opens the Permissions page filtered on and highlighting its entries.
 * It never calls requestPermissions or starts a permission settings page itself (guarded by PermsOnePlaceTest).
 */
object Perms {
    /**
     * An app's name for a sentence: its launcher label in words (a fleet id label like "cloud-lib-sample-engine" reads
     * "Cloud Sample Engine"), else [fallback] in words. Never the package.
     */
    fun appLabel(ctx: Context, pkg: String, fallback: String = pkg): String = runCatching {
        val pm = ctx.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() && it != pkg }?.let(PermsView::appName) ?: PermsView.appName(fallback)

    @Volatile private var cached: PermCatalogue? = null

    fun catalogue(ctx: Context): PermCatalogue = cached ?: synchronized(this) {
        cached ?: runCatching {
            PermCatalogue.parse(ctx.applicationContext.assets.open(PermCatalogue.ASSET).bufferedReader().use { it.readText() })
        }.getOrElse { PermCatalogue(emptyList(), emptyList()) }.also { cached = it }
    }

    /** The values the {placeholders} in settings keys and adb lines take on this device. */
    fun vars(ctx: Context): Map<String, String> {
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        fun service(action: String): String = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentServices(Intent(action).setPackage(pkg), 0).firstOrNull()?.serviceInfo
                ?.let { ComponentName(it.packageName, it.name).flattenToString() }
        }.getOrNull().orEmpty()
        val admin = runCatching {
            @Suppress("DEPRECATION")
            pm.queryBroadcastReceivers(Intent(android.app.admin.DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED).setPackage(pkg), 0).firstOrNull()
                ?.activityInfo?.let { ComponentName(it.packageName, it.name).flattenToString() }
        }.getOrNull().orEmpty()
        return mapOf(
            "pkg" to pkg,
            "listener" to service("android.service.notification.NotificationListenerService"),
            "a11y" to service("android.accessibilityservice.AccessibilityService"),
            "admin" to admin,
        )
    }

    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    internal fun declared(ctx: Context): Set<String> = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet()
    }.getOrNull().orEmpty()
}

/** Which runtime permissions this app has handed to the system dialog at least once (shared with the app's PermAskTracker). */
object PermAskLog {
    private const val PREFS = "perm_ask_tracker"; private const val KEY = "asked_set"
    fun asked(ctx: Context): Set<String> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()).orEmpty()
    fun markAsked(ctx: Context, perms: Collection<String>) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = sp.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        if (cur.addAll(perms)) sp.edit().putStringSet(KEY, cur).apply()
    }

    // #1352 v2 every catalogue entry (runtime, special, app-op) this app has put in front of the user at least once: the
    // system dialog for a runtime grant, its exact settings page for a special one. PermsFirstRun asks the rest on start.
    private const val KEY_IDS = "asked_ids"
    fun askedIds(ctx: Context): Set<String> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_IDS, emptySet()).orEmpty()
    fun markAskedIds(ctx: Context, ids: Collection<String>) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = sp.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
        if (cur.addAll(ids)) sp.edit().putStringSet(KEY_IDS, cur).apply()
    }

    /**
     * The asked-state is versioned: when [PermsFirstRun.ASK_STORE_VERSION] is above the stored one, everything recorded as
     * asked is forgotten once, so the next launch asks every missing grant again. Returns true when it reset.
     */
    fun migrate(ctx: Context, version: Int): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getInt(KEY_VERSION, 1) >= version) return false
        sp.edit().remove(KEY).remove(KEY_IDS).putInt(KEY_VERSION, version).apply()
        return true
    }
    private const val KEY_VERSION = "ask_store_version"
}

/** The live state of every catalogue entry on this device. */
object PermStatusReader {

    fun status(ctx: Context, e: PermEntry, activity: Activity? = null, vars: Map<String, String>? = null, declared: Set<String>? = null): PermStatus {
        val cat = Perms.catalogue(ctx)
        val sdk = Build.VERSION.SDK_INT
        if (!cat.applicable(e, sdk, Build.MANUFACTURER)) return PermStatus(PermState.NOT_APPLICABLE, cat.notApplicableReason(e, sdk, Build.MANUFACTURER))
        val decl = declared ?: Perms.declared(ctx)
        if (e.permission.isNotEmpty() && e.kind != PermKind.APPOPS && e.permission !in decl)
            return PermStatus(PermState.NOT_APPLICABLE, "not declared in this app's manifest")
        return runCatching { read(ctx, e, activity, vars ?: Perms.vars(ctx)) }.getOrElse { PermStatus(PermState.UNKNOWN, it.message ?: "check failed") }
    }

    private fun bool(g: Boolean, deniedWhy: String = "") = if (g) PermStatus(PermState.GRANTED) else PermStatus(PermState.DENIED, deniedWhy)

    private fun read(ctx: Context, e: PermEntry, activity: Activity?, vars: Map<String, String>): PermStatus {
        val pkg = ctx.packageName
        if (e.kind == PermKind.RUNTIME) {
            if (ctx.checkSelfPermission(e.permission) == PackageManager.PERMISSION_GRANTED) return PermStatus(PermState.GRANTED)
            val asked = e.permission in PermAskLog.asked(ctx)
            val rationale = activity?.shouldShowRequestPermissionRationale(e.permission) ?: false
            return when {
                !asked -> PermStatus(PermState.DENIED, "not asked yet")
                rationale -> PermStatus(PermState.DENIED, "denied once: Android will ask again")
                !e.autoRequest -> PermStatus(PermState.DENIED, "granted from its settings page")
                else -> PermStatus(PermState.PERMANENTLY_DENIED, "don't ask again: only the settings page can grant it")
            }
        }
        return when (e.check) {
            "all_files" -> bool(Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager())
            "overlay" -> bool(Settings.canDrawOverlays(ctx))
            "write_settings" -> bool(Settings.System.canWrite(ctx))
            "install_unknown" -> bool(ctx.packageManager.canRequestPackageInstalls())
            "exact_alarm" -> bool(Build.VERSION.SDK_INT < 31 || (ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true))
            "battery_opt" -> bool(ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(pkg) == true)
            "dnd" -> bool(ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true)
            "notification_listener" -> vars["listener"].orEmpty().let { c ->
                if (c.isEmpty()) PermStatus(PermState.NOT_APPLICABLE, "no notification listener in this app")
                else bool(Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty().split(':').any { it.equals(c, true) || it.startsWith("$pkg/") })
            }
            "accessibility" -> vars["a11y"].orEmpty().let { c ->
                if (c.isEmpty()) PermStatus(PermState.NOT_APPLICABLE, "no accessibility service in this app")
                else bool(Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').any { it.startsWith("$pkg/") })
            }
            "device_admin" -> vars["admin"].orEmpty().let { c ->
                if (c.isEmpty()) PermStatus(PermState.NOT_APPLICABLE, "no device admin receiver in this app")
                else bool(ctx.getSystemService(DevicePolicyManager::class.java)?.isAdminActive(ComponentName.unflattenFromString(c)!!) == true)
            }
            "vpn" -> if (!declaresVpn(ctx)) PermStatus(PermState.NOT_APPLICABLE, "no VPN service in this app") else bool(android.net.VpnService.prepare(ctx) == null)
            "standby_bucket" -> if (Build.VERSION.SDK_INT < 28) PermStatus(PermState.NOT_APPLICABLE) else
                ctx.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket.let { b ->
                    if (b == null) PermStatus(PermState.UNKNOWN) else bool(b <= UsageStatsManager.STANDBY_BUCKET_ACTIVE, "bucket $b")
                }
            "appop" -> appop(ctx, e.appop)
            "location_enabled" -> bool(PermsWifiScan.locationOn(ctx), "Location is off: Android hides Wi-Fi scan results")
            "wifi_scan_throttle" -> if (PermsWifiScan.throttleOn(ctx)) PermStatus(PermState.UNKNOWN, "4 scans per 2 minutes per app")
                else PermStatus(PermState.GRANTED, "throttling is off (developer option)")
            "health_connect" -> Perms.declared(ctx).filter { it.startsWith("android.permission.health.") }.let { hp ->
                val granted = hp.count { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
                when {
                    hp.isEmpty() -> PermStatus(PermState.NOT_APPLICABLE, "no Health Connect data type in this app's manifest")
                    Build.VERSION.SDK_INT < 34 -> PermStatus(PermState.UNKNOWN, "check it in the Health Connect app")
                    else -> bool(granted == hp.size, "$granted of ${hp.size} data types allowed")
                }
            }
            "usb_host" -> PermsUsb.status(ctx)   // #1375 per-device USB grant (PermsUsb)
            "manual" -> PermStatus(PermState.UNKNOWN, "Android does not expose this: check it on the page")
            else -> if (e.permission.isNotEmpty()) bool(ctx.checkSelfPermission(e.permission) == PackageManager.PERMISSION_GRANTED, "only a shell can grant it")
                    else PermStatus(PermState.UNKNOWN)
        }
    }

    private fun appop(ctx: Context, op: String): PermStatus {
        if (op.isEmpty()) return PermStatus(PermState.UNKNOWN)
        val ao = ctx.getSystemService(AppOpsManager::class.java) ?: return PermStatus(PermState.UNKNOWN)
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow(op, android.os.Process.myUid(), ctx.packageName)
            else @Suppress("DEPRECATION") ao.checkOpNoThrow(op, android.os.Process.myUid(), ctx.packageName)
        }.getOrElse { return PermStatus(PermState.NOT_APPLICABLE, "op $op unknown on this Android") }
        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> PermStatus(PermState.GRANTED)
            AppOpsManager.MODE_DEFAULT -> PermStatus(PermState.DENIED, "default (not allowed)")
            else -> PermStatus(PermState.DENIED, "app-op mode $mode")
        }
    }

    private fun declaresVpn(ctx: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.queryIntentServices(Intent("android.net.VpnService").setPackage(ctx.packageName), 0).isNotEmpty()
    }.getOrDefault(false)
}

/** Opens the exact settings page of an entry, with the field highlighted where Settings supports it. */
object PermsSettings {
    const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    /**
     * The intents tried in order: Samsung component (on Samsung), the declared action, its fallbacks, App info.
     * [pkg] is the package whose grant the page changes: this app, or a helper app that does work for it (#1360).
     */
    fun intents(ctx: Context, e: PermEntry, vars: Map<String, String> = Perms.vars(ctx), pkg: String = ctx.packageName): List<Intent> {
        val t = e.settings ?: SettingsTarget(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri = true, highlightKey = "permission_settings")
        val key = t.highlightKey.replace(Regex("\\{([a-z0-9_]+)\\}")) { vars[it.groupValues[1]].orEmpty() }
        fun decorate(i: Intent, withData: Boolean): Intent = i.apply {
            if (withData) data = Uri.parse("package:$pkg")
            if (key.isNotEmpty()) {
                putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
                putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
            }
            if (e.check == "notification_listener" && vars["listener"].orEmpty().isNotEmpty() && Build.VERSION.SDK_INT >= 30)
                putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", vars["listener"])
            if (t.action == "android.app.action.ADD_DEVICE_ADMIN") ComponentName.unflattenFromString(vars["admin"].orEmpty())
                ?.let { putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, it) }
            putExtra("android.provider.extra.APP_PACKAGE", pkg)
            if (e.check == "health_connect") putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val out = ArrayList<Intent>()
        if (Perms.isSamsung() && t.samsungComponent.isNotEmpty())
            ComponentName.unflattenFromString(t.samsungComponent)?.let { out += decorate(Intent().setComponent(it), false) }
        // Notification listener detail page (API 30+) is the package-scoped variant of the list.
        if (e.check == "notification_listener" && Build.VERSION.SDK_INT >= 30 && vars["listener"].orEmpty().isNotEmpty())
            out += decorate(Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS"), false)
        out += decorate(Intent(t.action), t.packageUri)
        if (t.packageUri) out += decorate(Intent(t.action), false)
        t.fallbacks.forEach { out += decorate(Intent(it), false) }
        out += decorate(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), true)
        return out
    }

    /** true = a page opened. */
    fun open(ctx: Context, e: PermEntry, pkg: String = ctx.packageName): Boolean {
        if (pkg == ctx.packageName) PermAskLog.markAskedIds(ctx, listOf(e.id))
        for (i in intents(ctx, e, if (pkg == ctx.packageName) Perms.vars(ctx) else emptyMap(), pkg)) {
            if (runCatching { ctx.startActivity(i) }.isSuccess) return true
        }
        return false
    }
}

/** What a feature lib asks before deciding to show its banner. */
object PermsNeeds {
    /** The applicable, not-granted entries [features] need, most important first (catalogue order). */
    fun missing(ctx: Context, vararg features: String): List<PermEntry> {
        val cat = Perms.catalogue(ctx)
        val vars = Perms.vars(ctx)
        val decl = Perms.declared(ctx)
        return cat.entries.filter { e -> e.features.any { it in features } }
            .filter { PermStatusReader.status(ctx, it, null, vars, decl).missing }
    }

    fun labels(entries: List<PermEntry>): String = entries.joinToString(", ") { it.label }

    /** The catalogue ids of manifest [permissions] (android.permission.*), catalogue order; unknown ones dropped. */
    fun idsFor(ctx: Context, permissions: Collection<String>): List<String> =
        Perms.catalogue(ctx).entries.filter { it.permission.isNotEmpty() && it.permission in permissions }.map { it.id }.distinct()
}

/** An activity that hosts the Permissions page in its own frame (SuperApp's shell: Configs > Setup > Perms). */
interface PermsHost {
    fun openPermsPage(): Boolean
}

/** "Open Permissions on my entries": the one call a feature banner makes. */
object PermsLink {
    data class Request(val feature: String, val ids: Set<String>)

    @Volatile private var pending: Request? = null

    /** The page reads this once when it opens; null = opened directly. */
    fun consume(): Request? = pending.also { pending = null }
    fun peek(): Request? = pending

    fun open(ctx: Context, feature: String, ids: Collection<String>) {
        pending = Request(feature, ids.toSet())
        var c: Context? = ctx
        while (c is ContextWrapper) {
            val host = c as? PermsHost
            if (host != null && runCatching { host.openPermsPage() }.getOrDefault(false)) return
            c = c.baseContext
        }
        // An app with no Permissions page of its own (or no activity at all: an IME, a plugin): the page libs:perms
        // hosts in this same package. Permissions are per package, so it is never another app's page.
        pending = null
        if (runCatching { ctx.startActivity(PermsActivity.intent(ctx, feature, ids)) }.isSuccess) return
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                .putExtra(PermsSettings.EXTRA_FRAGMENT_ARG_KEY, "permission_settings")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/**
 * The one "Permissions needed → Open Permissions" banner wording (#1274). A lib draws its own row (in its own Compose
 * style) with [title] and [BUTTON] and calls [PermsLink.open] from the button; the wording stays the same everywhere.
 */
object PermsBanner {
    const val BUTTON = "Open Permissions"

    fun title(entries: List<PermEntry>): String =
        if (entries.isEmpty()) "Permissions needed" else "Permissions needed: " + PermsNeeds.labels(entries)
}

/** The shell lines that grant a permission (adb, Shizuku, the embedded channel): written here only (#1274). */
object PermsShell {
    fun grant(pkg: String, permission: String): String = "pm grant $pkg $permission"
    fun appop(pkg: String, op: String, mode: String = "allow"): String = "appops set $pkg $op $mode"
}

/** Settings pages that are not a permission of this app but still live here, so no other lib builds such an intent. */
object PermsPages {
    /** Another (or this) package's App info page (also where the platform's Force stop lives). */
    fun appInfoIntent(pkg: String): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))

    fun openAppInfo(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.startActivity(appInfoIntent(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /** #1352 this app's notification settings (Configs ▸ General ▸ Notifications). */
    fun notificationsIntent(pkg: String): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)

    /** #1352 this app's language (per-app locale on Android 13+, the system language list before). */
    fun languageIntent(pkg: String): Intent =
        if (Build.VERSION.SDK_INT >= 33) Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", pkg, null))
        else Intent(Settings.ACTION_LOCALE_SETTINGS)

    /** Starts [i] from any context; false when no screen answers it. */
    fun open(ctx: Context, i: Intent): Boolean = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
}
