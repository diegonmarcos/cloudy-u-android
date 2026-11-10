package com.diegonmarcos.superapp.bgpolicy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import java.io.File

/**
 * #1602 The SuperApp's own "essential app" flag, user-controllable. ON by default: the app marks itself essential so battery
 * policy and Super Saving never restrict or hibernate it. The owner can turn it OFF to let Samsung's super-saving mode put the
 * app to sleep. Stored in the same `bg_policy` prefs the engine already uses; read into [BgEssential] by [BgHost.essential].
 */
object EssentialSelfPolicy {
    private const val PREFS = "bg_policy"
    private const val KEY = "self_essential_protected"
    fun protectedSelf(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    fun setProtectedSelf(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}

/** SharedPreferences behind the [BgKv] seam. */
class BgPrefsKv(ctx: Context) : BgKv {
    private val sp = ctx.applicationContext.getSharedPreferences("bg_policy", Context.MODE_PRIVATE)
    override fun get(key: String): String? = sp.getString(key, null)
    override fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
}

/** Append-only audit file (`bg-policy-audit.log`), one line per action; trimmed to its newest half past 256 KB. */
object BgAudit {
    private const val MAX = 256 * 1024
    @Synchronized fun append(ctx: Context, line: String) {
        runCatching {
            val f = File(ctx.applicationContext.filesDir, "bg-policy-audit.log")
            if (f.length() > MAX) f.writeText(f.readText().takeLast(MAX / 2))
            f.appendText("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $line\n")
        }
    }
    fun tail(ctx: Context, n: Int = 40): List<String> =
        runCatching { File(ctx.applicationContext.filesDir, "bg-policy-audit.log").readLines().takeLast(n) }.getOrDefault(emptyList())
}

/** The Android side of the policy: the declaration, the installed apps, the device's roles, the shell and the engine over them. */
object BgHost {
    fun config(ctx: Context): BgConfig =
        BgConfig.parse(ctx.applicationContext.assets.open("bg-policy.json").bufferedReader().use { it.readText() })

    fun shell(ctx: Context): BgShell? {
        val app = ctx.applicationContext
        val ch = runCatching { ShellChannels.active(app) }.getOrNull() ?: return null
        return object : BgShell {
            override val channel = ch.name()
            // #1510 through the SuperApp's one shell broker: allowlist (SuperApp entry), timeout, concurrency cap, audit.
            override fun exec(cmd: String): String? = ShellBroker.execText(app, cmd)
        }
    }

    /** Installed apps with a launcher entry or user-installed; fleet apps and the rest are ordered by [BgEngine.readAll]. */
    fun packages(ctx: Context): List<BgPkg> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0).map {
            val system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0 && it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            BgPkg(it.packageName, runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName), system)
        }
    }

    /** The on-device answer for each declared essential role. */
    fun roles(ctx: Context, cfg: BgConfig): Set<String> {
        val pm = ctx.packageManager
        val out = HashSet<String>()
        for (r in cfg.essentialRoles) when (r) {
            "self" -> out += ctx.packageName
            "dialer" -> runCatching { (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage }.getOrNull()?.let { out += it }
            "sms" -> runCatching { Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()?.let { out += it }
            "launcher" -> runCatching {
                pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            }.getOrNull()?.let { out += it }
            "keyboard" -> runCatching { Settings.Secure.getString(ctx.contentResolver, "default_input_method") }.getOrNull()
                ?.substringBefore('/')?.takeIf { it.isNotEmpty() }?.let { out += it }
            "systemui" -> out += "com.android.systemui"
        }
        return out
    }

    fun essential(ctx: Context, cfg: BgConfig = config(ctx)) =
        BgEssential(cfg, roles(ctx, cfg), ctx.packageName, EssentialSelfPolicy.protectedSelf(ctx))   // #1602 honour the self-essential flag

    /** Receivers and services the package declares, as class names. */
    fun components(ctx: Context, pkg: String): List<Pair<String, Boolean>> = runCatching {
        val pi = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES)
        (pi.receivers.orEmpty().map { "receiver" to it.name } + pi.services.orEmpty().map { "service" to it.name })
            .map { it.second to isEnabled(ctx, pkg, it.second) }
    }.getOrDefault(emptyList())

    fun isEnabled(ctx: Context, pkg: String, comp: String): Boolean = runCatching {
        val st = ctx.packageManager.getComponentEnabledSetting(ComponentName(pkg, comp))
        st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    }.getOrDefault(true)

    fun engine(ctx: Context, shell: BgShell? = shell(ctx), cfg: BgConfig = config(ctx)): BgEngine {
        val app = ctx.applicationContext
        return BgEngine(shell, essential(app, cfg), BgStore(BgPrefsKv(app)), { BgAudit.append(app, it) },
            componentEnabled = { p, c -> runCatching { isEnabled(app, p, c) }.getOrNull() })
    }
}
