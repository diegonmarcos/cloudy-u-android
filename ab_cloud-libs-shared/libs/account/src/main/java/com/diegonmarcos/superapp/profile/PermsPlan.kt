package com.diegonmarcos.superapp.profile

import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.telecom.TelecomManager
import com.diegonmarcos.superapp.appstore.StorePlayProtect
import org.json.JSONArray
import org.json.JSONObject

/**
 * Setup ▸ perms (account redesign spec 4.9): per app, declared vs granted, and the grants through
 * the uid-2000 shell channel.
 *
 * PUBLIC SURFACE (SetupRunbook's `perms` step and the debug op call exactly these):
 *   PermsPlan.plan(ctx: Context, profile: JSONObject?): PermsPlan.Plan   // profile = the device file (or null)
 *   PermsPlan.Plan.summary(): String                                      // one line: apps, granted, denied, to grant
 *   PermsPlan.Plan.todo: List<Item>                                       // what the profile wants and the phone lacks
 *   PermsPlan.apply(ctx: Context, plan: Plan, channel: ShellChannel?): PermsPlan.Outcome
 *   PermsPlan.Outcome.ok: Boolean · Outcome.lines: List<Line> · Line.text()
 *   PermsPlan.applyOne(ctx, item, channel): Line                          // the page's per-row Grant
 *   PermsPlan.capture(ctx, channel?): JSONObject                          // DeviceProfile's `perms` topic
 * All of them BLOCK (PackageManager / binder / shell): call off the main thread.
 *
 * The verbs are DATA: ac_cloud-account/build.json::ui.account.perms.shell_verbs (BuildConfig.UI_ACCOUNT_B64).
 * A host that declares none gets "no shell verb declared", never a guessed command.
 *
 * Honesty rules: every write is followed by a re-read ([read]) and only the re-read can say GRANTED;
 * a kind the shell cannot grant (device admin, accessibility) is NEEDS_USER with its Settings action;
 * no channel is NO_CHANNEL. Nothing is skipped silently.
 *
 * Profile shape (`perms` topic): { "<pkg>": { "granted": [perm…], "appops": { "<OP>": "allow" },
 * "roles": [dialer|sms|browser|home], "battery": bool } }. The appops map also records the pseudo-ops
 * NOTIFICATION_LISTENER, DEVICE_ADMIN and ACCESSIBILITY. Play Protect is the profile's system.play_protect.
 */
object PermsPlan {

    const val LISTENER_OP = "NOTIFICATION_LISTENER"
    const val DEVICE_ADMIN = "device_admin"
    const val ACCESSIBILITY = "accessibility"

    enum class Kind(val id: String) {
        RUNTIME("runtime"), APPOP("appop"), LISTENER("listener"), ROLE("role"),
        BATTERY("battery"), VERIFIER("verifier"), USER("user")
    }

    /** One grant. [arg] = the permission, shell op, component, role id, Settings action or desired verifier state. */
    data class Item(val pkg: String, val kind: Kind, val name: String, val arg: String,
                    val wanted: Boolean, val granted: Boolean?, val opStr: String? = null) {
        val missing: Boolean get() = wanted && granted != true
        fun state(): String = when (granted) { true -> "granted"; false -> "denied"; null -> "unknown" }
        fun json(): JSONObject = JSONObject().put("kind", kind.id).put("name", name).put("state", state()).put("wanted", wanted)
    }

    data class App(val pkg: String, val label: String, val fleet: Boolean, val items: List<Item>) {
        val granted: Int get() = items.count { it.granted == true }
        val denied: Int get() = items.count { it.granted != true }
    }

    data class Plan(val apps: List<App>, val global: List<Item>) {
        val todo: List<Item> get() = apps.flatMap { it.items }.filter { it.missing } + global.filter { it.missing }
        fun summary(): String {
            val all = apps.flatMap { it.items } + global
            val user = todo.count { it.kind == Kind.USER }
            return "${apps.size} apps · ${all.count { it.granted == true }} granted · ${all.count { it.granted != true }} denied · " +
                "${todo.size} to grant from the profile ($user need the user)"
        }
        fun json(): JSONObject = JSONObject().put("summary", summary())
            .put("apps", JSONArray(apps.map { a ->
                JSONObject().put("pkg", a.pkg).put("fleet", a.fleet).put("granted", a.granted).put("denied", a.denied)
                    .put("items", JSONArray(a.items.map { it.json() }))
            }))
            .put("global", JSONArray(global.map { it.json() }))
            .put("todo", JSONArray(todo.map { it.json().put("pkg", it.pkg) }))
    }

    enum class Status { GRANTED, ALREADY, FAILED, NEEDS_USER, NO_CHANNEL }

    data class Line(val item: Item, val status: Status, val detail: String) {
        fun text(): String = when (status) {
            Status.GRANTED -> "✓ ${item.pkg} ${item.name} granted (re-read)"
            Status.ALREADY -> "✓ ${item.pkg} ${item.name} already"
            Status.NEEDS_USER -> "✋ ${item.pkg} ${item.name} needs the user: $detail"
            Status.NO_CHANNEL -> "✗ ${item.pkg} ${item.name}: no shell channel — arm the ADB shell in Cloud C3 Webserver"
            Status.FAILED -> "✗ ${item.pkg} ${item.name}: $detail"
        }
    }

    data class Outcome(val lines: List<Line>) {
        val ok: Boolean get() = lines.all { it.status == Status.GRANTED || it.status == Status.ALREADY }
        fun json(): JSONObject = JSONObject().put("ok", ok).put("lines", JSONArray(lines.map { it.text() }))
    }

    // ── the declaration ──────────────────────────────────────────────────

    class Decl(val verbs: Map<String, String>, val readback: Map<String, String>,
               val appops: Map<String, Pair<String, String>>, val roles: Map<String, String>,
               val needsUser: Map<String, String>) {
        companion object {
            private fun strings(o: JSONObject?): Map<String, String> {
                if (o == null) return emptyMap()
                return o.keys().asSequence().filter { !it.startsWith("_") }
                    .associateWith { o.optString(it) }.filterValues { it.isNotEmpty() }
            }

            fun parse(perms: JSONObject?): Decl {
                val ops = perms?.optJSONObject("appops") ?: JSONObject()
                val appops = ops.keys().asSequence().filter { !it.startsWith("_") }.mapNotNull { k ->
                    val o = ops.optJSONObject(k) ?: return@mapNotNull null
                    k to (o.optString("opstr") to o.optString("permission"))
                }.toMap()
                return Decl(strings(perms?.optJSONObject("shell_verbs")), strings(perms?.optJSONObject("readback")),
                    appops, strings(perms?.optJSONObject("roles")), strings(perms?.optJSONObject("needs_user")))
            }

            fun fromBuildConfig(b64: String): Decl = parse(runCatching {
                JSONObject(String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP))).optJSONObject("perms")
            }.getOrNull())
        }
    }

    val decl: Decl by lazy { Decl.fromBuildConfig(com.diegonmarcos.superapp.account.BuildConfig.UI_ACCOUNT_B64) }

    /** Names go into a shell line: no quote, no whitespace, no shell metacharacter but `$` (inner classes), quoted. */
    private val SAFE = Regex("^[A-Za-z0-9._/:\$+-]+$")

    /** [template] with {pkg} {perm} {op} {component} {role} filled, each single-quoted; null when a value is unsafe. */
    fun fill(template: String, vars: Map<String, String>): String? {
        var out = template
        for ((k, v) in vars) {
            if (!out.contains("{$k}")) continue
            if (!SAFE.matches(v)) return null
            out = out.replace("{$k}", "'$v'")
        }
        return if (Regex("\\{[a-z]+\\}").containsMatchIn(out)) null else out
    }

    private fun vars(item: Item): Map<String, String> = when (item.kind) {
        Kind.RUNTIME -> mapOf("pkg" to item.pkg, "perm" to item.arg)
        Kind.APPOP -> mapOf("pkg" to item.pkg, "op" to item.arg)
        Kind.LISTENER -> mapOf("pkg" to item.pkg, "component" to item.arg)
        Kind.ROLE -> mapOf("pkg" to item.pkg, "role" to item.arg)
        else -> mapOf("pkg" to item.pkg)
    }

    // ── reading the phone ────────────────────────────────────────────────

    private fun profilePerms(profile: JSONObject?): JSONObject = profile?.optJSONObject("perms") ?: JSONObject()

    private fun JSONArray?.strSet(): Set<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()

    /** Fleet packages installed here + every launchable app + what the profile names and is installed. */
    fun packages(ctx: Context, profile: JSONObject?): Map<String, Pair<String, Boolean>> {
        val pm = ctx.packageManager
        val inv = com.diegonmarcos.superapp.appstore.AppInventory
        val labels = runCatching { inv.launchable(ctx) }.getOrDefault(emptyMap())
        val manifest = runCatching { com.diegonmarcos.superapp.fleetconfig.FleetPolicy.manifestOrNull(ctx) }.getOrNull()
        val fleet = runCatching { inv.fleetPackages() }.getOrDefault(emptySet()) +
            manifest?.apps?.values?.map { it.pkg }?.filter { it.isNotEmpty() }.orEmpty()
        val named = profilePerms(profile).keys().asSequence().toSet()
        fun installed(p: String) = runCatching { pm.getPackageInfo(p, 0); true }.getOrDefault(false)
        return (fleet + labels.keys + named).filter { installed(it) }.sorted()
            .associateWith { (labels[it] ?: it) to (it in fleet) }
    }

    private fun services(ctx: Context, action: String, pkg: String): List<ComponentName> = runCatching {
        ctx.packageManager.queryIntentServices(Intent(action).setPackage(pkg), 0).map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    }.getOrDefault(emptyList())

    private fun receivers(ctx: Context, action: String, pkg: String): List<ComponentName> = runCatching {
        ctx.packageManager.queryBroadcastReceivers(Intent(action).setPackage(pkg), 0).map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun dangerous(pm: PackageManager, perm: String): Boolean = runCatching {
        val level = pm.getPermissionInfo(perm, 0).protectionLevel
        (level and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun appOpAllowed(ctx: Context, pkg: String, opStr: String?): Boolean? {
        if (opStr.isNullOrEmpty()) return null
        return runCatching {
            val uid = ctx.packageManager.getApplicationInfo(pkg, 0).uid
            val aom = ctx.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= 29) aom.unsafeCheckOpNoThrow(opStr, uid, pkg)
                       else aom.checkOpNoThrow(opStr, uid, pkg)
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrNull()
    }

    private fun localRoleHolder(ctx: Context, short: String): String? = runCatching {
        when (short) {
            "dialer" -> ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
            "sms" -> Telephony.Sms.getDefaultSmsPackage(ctx)
            "browser" -> ctx.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org")),
                PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            "home" -> ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            else -> null
        }
    }.getOrNull()

    private fun shell(ctx: Context, channel: ShellChannel?, key: String, item: Item): String? {
        val t = decl.readback[key] ?: return null
        val cmd = fill(t, vars(item)) ?: return null
        return channel?.let { runCatching { it.exec(ctx, "$cmd 2>&1") }.getOrNull() }
    }

    /**
     * The phone's state for [item] NOW. The shell's own read-back (build.json readback) when a
     * channel is up, else the platform API. This is the ONLY judge of a grant: [applyOne] calls it
     * after every write.
     */
    fun read(ctx: Context, item: Item, channel: ShellChannel?): Boolean? {
        val pm = ctx.packageManager
        return when (item.kind) {
            Kind.RUNTIME -> pm.checkPermission(item.arg, item.pkg) == PackageManager.PERMISSION_GRANTED
            Kind.APPOP -> shell(ctx, channel, "appop", item)?.let { out ->
                if (out.contains("No operations", true) || !out.contains(item.arg)) null else out.contains(": allow")
            } ?: appOpAllowed(ctx, item.pkg, item.opStr)
            Kind.LISTENER -> {
                val list = shell(ctx, channel, "listener", item)?.trim()?.takeIf { it.isNotEmpty() && !it.contains("Exception") }
                    ?: runCatching { Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") }.getOrNull()
                list?.let { l -> val want = ComponentName.unflattenFromString(item.arg)
                    l.split(':').any { ComponentName.unflattenFromString(it.trim()) == want } }
            }
            Kind.ROLE -> {
                val short = decl.roles.entries.firstOrNull { it.value == item.arg }?.key.orEmpty()
                shell(ctx, channel, "role", item)?.let { out -> out.lines().any { it.trim() == item.pkg } }
                    ?: localRoleHolder(ctx, short)?.let { it == item.pkg }
            }
            Kind.BATTERY -> runCatching { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(item.pkg) }.getOrNull()
            Kind.VERIFIER -> runCatching { StorePlayProtect.state(ctx).on == (item.arg == "on") }.getOrNull()
            Kind.USER -> when (item.name) {
                DEVICE_ADMIN -> runCatching { ctx.getSystemService(DevicePolicyManager::class.java).activeAdmins
                    ?.any { it.packageName == item.pkg } ?: false }.getOrNull()
                ACCESSIBILITY -> runCatching { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    ?.split(':')?.any { it.startsWith(item.pkg + "/") } ?: false }.getOrNull()
                else -> null
            }
        }
    }

    // ── the plan ─────────────────────────────────────────────────────────

    /** Every package's grants and the profile's wants. Reads with the platform API (no channel). */
    fun plan(ctx: Context, profile: JSONObject?): Plan = plan(ctx, profile, null)

    fun plan(ctx: Context, profile: JSONObject?, channel: ShellChannel?): Plan {
        val pm = ctx.packageManager
        val want = profilePerms(profile)
        val apps = packages(ctx, profile).map { (pkg, meta) ->
            val w = want.optJSONObject(pkg) ?: JSONObject()
            val wGranted = w.optJSONArray("granted").strSet()
            val wOps = w.optJSONObject("appops") ?: JSONObject()
            val wRoles = w.optJSONArray("roles").strSet()
            val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES) }.getOrNull()
            val requested = info?.requestedPermissions?.toList().orEmpty()
            val servicePerms = info?.services?.mapNotNull { it.permission }.orEmpty().toSet()
            val items = mutableListOf<Item>()
            fun add(i: Item) { items += i.copy(granted = read(ctx, i, channel)) }

            (requested.filter { dangerous(pm, it) } + wGranted.filter { it !in requested }).distinct().sorted()
                .forEach { p -> add(Item(pkg, Kind.RUNTIME, p.removePrefix("android.permission."), p, p in wGranted, null)) }
            decl.appops.forEach { (op, v) ->
                val (opStr, perm) = v
                val declares = perm.isNotEmpty() && (perm in requested || perm in servicePerms)
                if (declares || wOps.has(op)) add(Item(pkg, Kind.APPOP, op, op, wOps.optString(op) == "allow", null, opStr))
            }
            val listener = services(ctx, NotificationListenerService.SERVICE_INTERFACE, pkg).firstOrNull()
            if (listener != null) add(Item(pkg, Kind.LISTENER, LISTENER_OP, listener.flattenToString(), wOps.optString(LISTENER_OP) == "allow", null))
            decl.roles.forEach { (short, role) ->
                val i = Item(pkg, Kind.ROLE, short, role, short in wRoles, null).let { it.copy(granted = read(ctx, it, channel)) }
                if (i.wanted || i.granted == true) items += i
            }
            add(Item(pkg, Kind.BATTERY, "battery", "", w.optBoolean("battery", false), null))
            decl.needsUser.forEach { (kind, action) ->
                val has = when (kind) {
                    DEVICE_ADMIN -> receivers(ctx, DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED, pkg).isNotEmpty()
                    ACCESSIBILITY -> services(ctx, AccessibilityService.SERVICE_INTERFACE, pkg).isNotEmpty()
                    else -> false
                }
                val key = kind.uppercase()
                if (has || wOps.has(key)) add(Item(pkg, Kind.USER, kind, action, wOps.optString(key) == "allow", null))
            }
            App(pkg, meta.first, meta.second, items)
        }
        val global = mutableListOf<Item>()
        val system = profile?.optJSONObject("system")
        if (decl.verbs.containsKey(Kind.VERIFIER.id)) {
            val desired = if (system?.has("play_protect") == true) system.optBoolean("play_protect") else null
            val i = Item("", Kind.VERIFIER, "play_protect", if (desired == false) "off" else "on", desired != null, null)
            global += i.copy(granted = read(ctx, i, channel))
        }
        return Plan(apps, global)
    }

    // ── writing ──────────────────────────────────────────────────────────

    /** Grant everything the profile wants and the phone lacks; one line per item. */
    fun apply(ctx: Context, plan: Plan, channel: ShellChannel?): Outcome = Outcome(plan.todo.map { applyOne(ctx, it, channel) })

    /** Grant one [item] (the page's per-row Grant). Only the re-read after the write can say GRANTED. */
    fun applyOne(ctx: Context, item: Item, channel: ShellChannel?): Line {
        if (read(ctx, item, channel) == true) return Line(item, Status.ALREADY, "")
        if (item.kind == Kind.USER) return Line(item, Status.NEEDS_USER, "open Settings (${item.arg})")
        val verb = decl.verbs[item.kind.id] ?: return Line(item, Status.FAILED, "no shell verb declared for ${item.kind.id}")
        if (item.kind == Kind.VERIFIER) {
            val r = StorePlayProtect.setScanning(ctx, item.arg == "on")
            val after = read(ctx, item, channel)
            return if (after == true) Line(item, Status.GRANTED, r.channel)
                   else Line(item, Status.FAILED, "$verb did not take (${r.state.describe()})")
        }
        if (channel == null) return Line(item, Status.NO_CHANNEL, "")
        val cmd = fill(verb, vars(item)) ?: return Line(item, Status.FAILED, "refused: a name is not shell-safe")
        val out = runCatching { channel.exec(ctx, "$cmd 2>&1") }.getOrNull()
        val after = read(ctx, item, channel)
        return if (after == true) Line(item, Status.GRANTED, cmd)
               else Line(item, Status.FAILED, "still ${if (after == null) "unknown" else "denied"} after `$cmd`: ${out?.trim()?.take(160) ?: "no output"}")
    }

    /** The `perms` topic of the device file: exactly this page, as granted now. */
    fun capture(ctx: Context, channel: ShellChannel? = null): JSONObject {
        val out = JSONObject()
        for (a in plan(ctx, null, channel).apps) {
            val on = a.items.filter { it.granted == true }
            val granted = JSONArray(on.filter { it.kind == Kind.RUNTIME }.map { it.arg })
            val appops = JSONObject().also { o ->
                on.filter { it.kind == Kind.APPOP || it.kind == Kind.LISTENER }.forEach { o.put(it.name, "allow") }
                on.filter { it.kind == Kind.USER }.forEach { o.put(it.name.uppercase(), "allow") }
            }
            val roles = JSONArray(on.filter { it.kind == Kind.ROLE }.map { it.name })
            val battery = on.any { it.kind == Kind.BATTERY }
            out.put(a.pkg, JSONObject().put("granted", granted).put("appops", appops).put("roles", roles).put("battery", battery))
        }
        return out
    }

    /** The Settings screen a NEEDS_USER row opens: the declared action, else the app's own details page. */
    fun settingsIntent(item: Item): Intent =
        (if (item.kind == Kind.USER && item.arg.isNotEmpty()) Intent(item.arg)
         else com.diegonmarcos.superapp.perms.PermsPages.appInfoIntent(item.pkg))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
