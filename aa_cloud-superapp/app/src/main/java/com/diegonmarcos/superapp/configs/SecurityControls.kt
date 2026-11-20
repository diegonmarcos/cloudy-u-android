package com.diegonmarcos.superapp.configs

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.diegonmarcos.superapp.configs.DeviceControls.Probe
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellResult
import com.diegonmarcos.superapp.system.AdbReadiness

/**
 * #1833 Quick actions ▸ Panels ▸ Security: the capability behind its tiles. [DeviceControls.byId] holds one entry per declared id;
 * the Accessibility apps group adds one tile per installed fleet accessibility service ([installedFleet], DeviceControls.serviceTile).
 *
 * THE READ RULE (redo after the on-phone check): every read is what a normal app can read itself, live, and its [Probe] names the
 * source and the value ("AccessibilityManager: bound", "Settings.Global adb_wifi_enabled=1", "overlay strips on screen: 2"). No read
 * goes through ADB or the shell broker, except the ADB shell tile, whose subject IS the broker. Only the writes Android reserves
 * (Settings.Secure / Settings.Global puts, `cmd location`) use WRITE_SECURE_SETTINGS first, then the broker; a verdict is the re-read.
 *
 * Root causes of the wrong lights this replaces: (1) the accessibility tiles looked for ONE hardcoded component per app in
 * Settings.Secure, never asked the system what it had bound, and said nothing about what they looked at; (2) "Cloud SuperApp" read
 * only the lock-screen service while the edge menu (an overlay, no accessibility) worked, so the tile read as "the SuperApp's
 * features are off"; (3) every read queued on one thread behind the ADB shell's broker round trip, so with the channel down the
 * readings aged into Unknown before they landed; (4) the ADB tile and the status-strip dot read two different facts (broker armed vs
 * adb transport) and showed neither.
 */
object SecurityControls {

    /** A settings write's outcome: what was done, and whether the re-read agrees. */
    data class Write(val ok: Boolean, val detail: String)

    // ── The fleet's accessibility services, enumerated ──────────────────────

    /** Packages counted as the fleet's own. */
    const val FLEET_PREFIX = "com.diegonmarcos."

    /** Fleet libraries that keep their upstream package id (the vendored GitSync clone), counted as the fleet's own too. */
    val FLEET_LIB_PACKAGES = setOf("com.viscouspot.gitsync")

    /** = ab_cloud-libs-shared/lib-apks/build.json lib_apks.application_id_prefix (guard: test-panels-security-1833.sh). */
    const val LIB_PREFIX = "com.diegonmarcos.cloudlib"

    fun fleetPackage(pkg: String): Boolean = pkg.startsWith(FLEET_PREFIX) || pkg in FLEET_LIB_PACKAGES

    /** A lib-apks package (`<LIB_PREFIX>.<module without '-'>`). */
    fun libPackage(pkg: String): Boolean = pkg.startsWith("$LIB_PREFIX.")

    /**
     * Pure: "" for a current build, else the sentence the tile shows. A lib package is CURRENT only when this build's fleet
     * catalog ([catalog] = package -> label of its lib rows) ships it under the label it is installed with. No current lib module
     * declares an accessibility service, so a lib that offers one is an old build (e.g. one still labelled "Cloud Library").
     */
    fun libVerdict(pkg: String, installedLabel: String, catalog: Map<String, String>): String {
        if (!libPackage(pkg)) return ""
        val want = catalog[pkg]
        return when {
            want == null -> "old library build: $pkg ('$installedLabel', not a shipped module)"
            want != installedLabel -> "old library build: $pkg (installed as '$installedLabel', the fleet names it '$want')"
            else -> ""
        }
    }

    /** One installed accessibility service of a fleet package. [old] "" = current, else the old-build sentence ([libVerdict]). */
    data class Svc(val component: String, val pkg: String, val serviceLabel: String, val appLabel: String, val old: String = "")

    /** Pure: the installed services that are the fleet's, in label order, the ones a declared tile presents ([adopted]) left out. */
    fun fleetOnly(all: List<Svc>, adopted: Set<String>): List<Svc> =
        all.filter { fleetPackage(it.pkg) && it.pkg !in adopted }.distinctBy { it.component }
            .sortedWith(compareBy({ it.appLabel.lowercase() }, { it.component }))

    /** Pure: the tile label of [s]: its app's name, with the service's own label when the app runs more than one. */
    fun tileLabel(s: Svc, all: List<Svc>): String =
        if (all.count { it.pkg == s.pkg } > 1 && s.serviceLabel.isNotBlank() && s.serviceLabel != s.appLabel) "${s.appLabel}: ${s.serviceLabel}"
        else s.appLabel.ifBlank { s.pkg }

    private fun am(ctx: Context): AccessibilityManager? =
        runCatching { ctx.applicationContext.getSystemService(AccessibilityManager::class.java) }.getOrNull()

    /** Every installed accessibility service of a fleet package (lib-apks packages included), from AccessibilityManager. */
    fun installedFleet(ctx: Context): List<Svc> {
        val pm = ctx.packageManager
        val catalog = libCatalog()
        val list = runCatching { am(ctx)?.installedAccessibilityServiceList }.getOrNull().orEmpty()
        return list.mapNotNull { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
            val pkg = si.packageName ?: return@mapNotNull null
            if (!fleetPackage(pkg)) return@mapNotNull null
            val app = runCatching { si.applicationInfo.loadLabel(pm).toString() }.getOrDefault(pkg)
            Svc(normalize("$pkg/${si.name}"), pkg, runCatching { si.loadLabel(pm).toString() }.getOrDefault(""), app,
                libVerdict(pkg, app, catalog))
        }
    }

    /** Lib rows of the baked fleet catalog: package -> label. */
    private fun libCatalog(): Map<String, String> = runCatching {
        com.diegonmarcos.superapp.updater.Fleet.parse(com.diegonmarcos.superapp.appstore.BuildConfig.CONSTELLATION_FLEET_B64)
            .filter { it.id.startsWith("lib-") && it.pkg.isNotBlank() }.associate { it.pkg to it.label }
    }.getOrDefault(emptyMap())

    /** The services the system has BOUND and running (AccessibilityManager.getEnabledAccessibilityServiceList, all feedback types). */
    fun bound(ctx: Context): Set<String> = runCatching {
        am(ctx)?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
            .mapNotNull { it.id?.let { id -> normalize(id) } }.toSet()
    }.getOrDefault(emptySet())

    private const val ENABLED = "enabled_accessibility_services"
    private const val MASTER = "accessibility_enabled"

    /** `pkg/.Cls` and `pkg/pkg.Cls` are the same component; the stored list may use either. */
    fun normalize(flat: String): String {
        val t = flat.trim()
        val slash = t.indexOf('/')
        if (slash <= 0) return t
        val pkg = t.substring(0, slash)
        val cls = t.substring(slash + 1)
        return pkg + "/" + (if (cls.startsWith(".")) pkg + cls else cls)
    }

    /** The stored colon-separated list, normalized, blanks dropped. */
    fun parse(list: String?): List<String> =
        list.orEmpty().split(':').map { normalize(it) }.filter { it.isNotBlank() && it != "null" }

    /** [list] with [component] present ([on]) or absent, order kept, nothing duplicated. */
    fun edit(list: String?, component: String, on: Boolean): List<String> {
        val c = normalize(component)
        val cur = parse(list).filter { it != c }.distinct()
        return if (on) cur + c else cur
    }

    /** Pure: the fleet components the stored list names that no installed service answers to (an uninstalled app's stale entry). */
    fun stale(list: String?, installed: Set<String>): List<String> =
        parse(list).filter { fleetPackage(it.substringBefore('/')) && it !in installed }.distinct()

    fun enabledList(ctx: Context): String? =
        runCatching { Settings.Secure.getString(ctx.contentResolver, ENABLED) }.getOrNull()

    /** Pure: the one-line reading of a service: bound by the system (the state), and the Settings.Secure list as a second fact. */
    fun serviceLine(bound: Boolean, inSecure: Boolean, old: String = ""): String =
        (if (old.isNotBlank()) "$old; tap uninstalls. " else "") +
            "AccessibilityManager: " + (if (bound) "bound" else "not bound") +
            "; Settings.Secure $ENABLED: " + (if (inSecure) "listed" else "absent")

    fun serviceProbe(ctx: Context, component: String, old: String = ""): Probe {
        val c = normalize(component)
        val b = c in bound(ctx)
        return Probe(b, serviceLine(b, c in parse(enabledList(ctx)), old))
    }

    /** The services of [pkg] installed now (a declared tile presents its app's services). */
    fun servicesOf(ctx: Context, pkg: String): List<Svc> = installedFleet(ctx).filter { it.pkg == pkg }

    /** A declared app tile: on while ANY service of [pkg] is bound; off with "none installed" when it has none. */
    fun appProbe(ctx: Context, pkg: String): Probe {
        if (pkg.isBlank()) return Probe(null, "no a11y_package declared")
        val svcs = servicesOf(ctx, pkg)
        if (svcs.isEmpty()) return Probe(false, "AccessibilityManager: no accessibility service of $pkg installed")
        val b = bound(ctx)
        val sec = parse(enabledList(ctx)).toSet()
        return Probe(svcs.any { it.component in b }, svcs.joinToString("; ") { s ->
            s.component.substringAfterLast('.') + ": " + serviceLine(s.component in b, s.component in sec)
        })
    }

    fun setApp(ctx: Context, pkg: String, on: Boolean): Write {
        val svcs = servicesOf(ctx, pkg)
        if (svcs.isEmpty()) return Write(false, "no accessibility service of $pkg installed")
        var list = enabledList(ctx)
        // on: the app's first service (an app tile switches its app on); off: every one of them.
        val targets = if (on) listOf(svcs.first().component) else svcs.map { it.component }
        for (t in targets) list = edit(list, t, on).joinToString(":")
        return writeList(ctx, parse(list)) { appProbe(ctx, pkg).state == on }
    }

    fun setService(ctx: Context, component: String, on: Boolean): Write =
        writeList(ctx, edit(enabledList(ctx), component, on)) { (normalize(component) in bound(ctx)) == on }

    /** "Other fleet services": stale fleet entries in the stored list (the service is gone, the entry keeps accessibility on). */
    fun staleProbe(ctx: Context): Probe {
        val gone = stale(enabledList(ctx), installedFleet(ctx).map { it.component }.toSet())
        return Probe(gone.isNotEmpty(), if (gone.isEmpty()) "Settings.Secure $ENABLED: no stale fleet entry"
            else "Settings.Secure $ENABLED lists ${gone.size} not installed: " + gone.joinToString(", "))
    }

    fun clearStale(ctx: Context): Write {
        val list = enabledList(ctx)
        val gone = stale(list, installedFleet(ctx).map { it.component }.toSet()).toSet()
        if (gone.isEmpty()) return Write(staleProbe(ctx).state == false, "no stale fleet entry")
        val w = writeList(ctx, parse(list).filter { it !in gone }) { staleProbe(ctx).state == false }
        return w.copy(detail = "removed " + gone.joinToString(", ") + ": " + w.detail)
    }

    /** Pure: the Vault-autofill reading from its two mechanisms. [af] = Settings.Secure autofill_service (null unset), [afError] when unreadable. */
    fun vaultLine(pkg: String, installed: Boolean, a11yBound: Boolean, af: String?, afError: String?): Probe {
        val afVault = af?.startsWith("$pkg/") == true
        val via = when { a11yBound && afVault -> "both"; a11yBound -> "accessibility"; afVault -> "autofill framework"; else -> "neither" }
        return Probe(a11yBound || afVault, "via $via. AccessibilityManager: " +
            (if (!installed) "no Vault service installed" else if (a11yBound) "bound" else "not bound") +
            "; Settings.Secure " + (afError?.let { "autofill_service unreadable ($it)" } ?: "autofill_service=${af ?: "null"}"))
    }

    fun vaultProbe(ctx: Context, pkg: String): Probe {
        val svcs = servicesOf(ctx, pkg)
        val b = bound(ctx)
        val af = autofillService(ctx)
        return vaultLine(pkg, svcs.isNotEmpty(), svcs.any { it.component in b }, af.getOrNull(), af.exceptionOrNull()?.javaClass?.simpleName)
    }

    fun autofillService(ctx: Context): Result<String?> = runCatching { Settings.Secure.getString(ctx.contentResolver, AUTOFILL) }

    /** On = Vault's accessibility service in; off = out, and Android's autofill service cleared when it names Vault. */
    fun setVault(ctx: Context, pkg: String, on: Boolean): Write {
        if (on) return setApp(ctx, pkg, true).let { w -> Write(vaultProbe(ctx, pkg).state == true, w.detail) }
        val a = if (servicesOf(ctx, pkg).isNotEmpty()) setApp(ctx, pkg, false).detail else "no Vault service"
        val b = if (autofillService(ctx).getOrNull()?.startsWith("$pkg/") == true) secureDelete(ctx, AUTOFILL) else "autofill_service is not Vault"
        return Write(vaultProbe(ctx, pkg).state == false, "$a; $b")
    }

    private const val AUTOFILL = "autofill_service"

    private fun secureDelete(ctx: Context, key: String): String {
        if (holdsSecure(ctx) && runCatching { Settings.Secure.putString(ctx.contentResolver, key, null) }.isSuccess &&
            autofillService(ctx).getOrNull().isNullOrBlank()) return "self (WRITE_SECURE_SETTINGS)"
        return shell(ctx, listOf("settings delete secure $key")) ?: NO_CHANNEL
    }

    private fun writeList(ctx: Context, next: List<String>, agrees: () -> Boolean): Write {
        val value = next.joinToString(":")
        if (holdsSecure(ctx)) {
            runCatching {
                Settings.Secure.putString(ctx.contentResolver, ENABLED, value.ifBlank { null })
                if (value.isNotBlank()) Settings.Secure.putInt(ctx.contentResolver, MASTER, 1)
            }
            // the system binds (or unbinds) on its own thread after the settings write: wait briefly before judging
            if (settle(agrees)) return Write(true, "self (WRITE_SECURE_SETTINGS)")
        }
        val cmds = if (value.isBlank()) listOf("settings delete secure $ENABLED")
                   else listOf("settings put secure $ENABLED $value", "settings put secure $MASTER 1")
        val out = shell(ctx, cmds) ?: return Write(false, NO_CHANNEL)
        return Write(settle(agrees), out)
    }

    private fun settle(check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + SETTLE_MS
        while (!check()) {
            if (System.currentTimeMillis() >= end) return false
            runCatching { Thread.sleep(100) }
        }
        return true
    }

    private const val SETTLE_MS = 3000L

    // ── Developer options, USB / Wireless debugging, unverified developers, the ADB shell ─

    private fun global(ctx: Context, key: String): String? =
        runCatching { Settings.Global.getString(ctx.contentResolver, key) }.getOrNull()

    /** Pure: a Settings.Global flag's reading: 1 on, 0 or unset off, anything else unknown; the detail is the raw value. */
    fun flag(key: String, raw: String?): Probe = Probe(
        when (raw?.trim()) { "1" -> true; null, "", "0", "null" -> false; else -> null }, "Settings.Global $key=${raw ?: "null"}")

    fun devOptions(ctx: Context): Probe =
        flag(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, global(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED))
    fun devOptionsOn(ctx: Context): Boolean? = devOptions(ctx).state
    fun usbDebugging(ctx: Context): Probe = flag(Settings.Global.ADB_ENABLED, global(ctx, Settings.Global.ADB_ENABLED))
    fun usbDebuggingOn(ctx: Context): Boolean? = usbDebugging(ctx).state
    fun wirelessDebugging(ctx: Context): Probe = flag("adb_wifi_enabled", global(ctx, "adb_wifi_enabled"))

    fun setGlobal(ctx: Context, key: String, on: Boolean, read: (Context) -> Boolean?): Write {
        val v = if (on) 1 else 0
        if (holdsSecure(ctx)) {
            runCatching { Settings.Global.putInt(ctx.contentResolver, key, v) }
            if (read(ctx) == on) return Write(true, "self (WRITE_SECURE_SETTINGS)")
        }
        val out = shell(ctx, listOf("settings put global $key $v")) ?: return Write(false, NO_CHANNEL)
        return Write(read(ctx) == on, out)
    }

    /** Pure: the ADB shell reading from the broker's status: on only when armed; the adb transport verdict rides along. */
    fun shellLine(armed: Boolean, channel: String, reason: String, adbLabel: String, adbDetail: String): Probe = Probe(armed,
        "broker " + (if (armed) "armed via $channel" else "not armed: " + reason.ifBlank { "unknown" }) +
            "; adb transport (status-strip dot): $adbLabel" + (if (adbDetail.isNotBlank()) " ($adbDetail)" else ""))

    /**
     * The ADB shell: the broker's live status (ShellClient.status: an `id -u` round trip). On only when armed; the detail carries
     * the adb transport verdict too (the home status strip's ADB dot, ShellStatus.adb), so the two can no longer disagree unseen.
     */
    fun shell(ctx: Context): Probe {
        val st = runCatching { ShellClient.status(ctx.applicationContext) }.getOrNull()
            ?: return Probe(false, "ShellClient.status: no answer")
        return shellLine(st.armed, st.channel, st.reason.ifBlank { st.detail }, st.adb.label, st.adbDetail)
    }

    /** On = run the host's Connect chain, or open the ADB owner's page (a tap), and re-ask; off ends with Wireless debugging. */
    fun setShell(ctx: Context, on: Boolean): Write {
        if (!on) return Write(shell(ctx).state == false,
            "The channel is closed by turning Wireless debugging (or USB debugging) off, not from here.")
        val connect = AdbReadiness.connector
        if (connect != null) runCatching { connect(ctx.applicationContext) } else AdbReadiness.openAdbShell(ctx)
        val p = shell(ctx)
        return Write(p.state == true, if (connect == null) "opened the ADB owner's page: ${p.detail}" else p.detail)
    }

    /**
     * Apps from unverified developers (#1822), read in-process: the verifier package (PackageManager), the developer-verification
     * keys Settings lets this app read, else the last measurement the Perms row keeps. Lit = installs from unverified developers
     * are allowed (verifier absent or disabled, or allowed indefinitely).
     */
    fun devVerifier(ctx: Context): Probe {
        val dv = com.diegonmarcos.superapp.perms.DevVerifier
        val pkg = dv.PKG
        val ai = runCatching { ctx.packageManager.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
        if (ai == null) return Probe(true, "PackageManager: $pkg not installed (no verifier checks installs)")
        if (!ai.enabled) return Probe(true, "PackageManager: $pkg disabled for this user")
        val now = System.currentTimeMillis()
        val rows = listOf(Settings.Global.CONTENT_URI, Settings.Secure.CONTENT_URI).joinToString("\n") { uri -> runCatching {
            ctx.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                buildString { while (c.moveToNext()) appendLine(c.getString(0) + "=" + c.getString(1)) } }.orEmpty()
        }.getOrDefault("") }
        val hits = dv.matches(rows)
        val reading = if (hits.isNotEmpty()) hits.map { (k, v) -> dv.interpret(k, v, now) }
                          .let { rs -> rs.firstOrNull { it.state != com.diegonmarcos.superapp.perms.DevVerifier.State.UNKNOWN } ?: rs.first() }
                      else dv.recall(ctx, now)
        val st = dv.status(reading)
        val on = when (st.state) {
            com.diegonmarcos.superapp.perms.PermState.GRANTED -> true
            com.diegonmarcos.superapp.perms.PermState.DENIED -> false
            else -> null
        }
        return Probe(on, "PackageManager: $pkg enabled; " +
            (if (hits.isNotEmpty()) "Settings ${reading.detail}" else "no readable key, last measured ${reading.state}") + " - " + st.reason)
    }

    // ── Overlays: edge menu, floating menu ──────────────────────────────────

    /** Pure: the edge-menu reading from the strips on screen and the switch. */
    fun edgeLine(attached: Int, total: Int, prefOn: Boolean, canOverlay: Boolean, running: Boolean): Probe = Probe(attached > 0,
        "overlay strips on screen: $attached" + (if (total != attached) " of $total" else "") + "; switch " + (if (prefOn) "ON" else "OFF") +
            (if (prefOn && attached == 0) " but no strip on screen (" +
                (if (!canOverlay) "no overlay permission" else if (!running) "service not running" else "service up, no strip") + ")" else "") +
            "; no accessibility needed")

    fun edge(ctx: Context): Probe {
        val svc = com.diegonmarcos.superapp.onehand.OneHandOverlayService.instance
        val rects = runCatching { svc?.stripRects().orEmpty() }.getOrDefault(emptyList())
        return edgeLine(rects.count { it.attached }, rects.size, com.diegonmarcos.superapp.onehand.OneHandPrefs.isEnabled(ctx),
            Settings.canDrawOverlays(ctx), svc != null)
    }

    fun floating(ctx: Context): Probe {
        val f = com.diegonmarcos.superapp.floatingnav.FloatingNavService
        val on = f.isRunning && f.armed
        return Probe(on, "FloatingNavService running=${f.isRunning}, armed=${f.armed}; windows bubble=${f.bubbleDrawn} bar=${f.barDrawn}" +
            (if (on && !f.bubbleDrawn && !f.barDrawn) " (hidden while the SuperApp is in front, by design)" else "") +
            "; overlay permission=${Settings.canDrawOverlays(ctx)}")
    }

    /** Any network carrying the VPN transport (whoever owns it); null when ConnectivityManager will not say. */
    fun anyVpnNetwork(ctx: Context): Boolean? = runCatching {
        val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
    }.getOrNull()

    // ── Location ────────────────────────────────────────────────────────────

    fun location(ctx: Context): Probe {
        val on = runCatching {
            (ctx.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
        }.getOrNull()
        @Suppress("DEPRECATION")
        val mode = runCatching { Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE) }.getOrNull()
        return Probe(on, "LocationManager.isLocationEnabled=${on ?: "unreadable"}; Settings.Secure location_mode=${mode ?: "unreadable"}")
    }

    fun locationOn(ctx: Context): Boolean? = location(ctx).state

    fun setLocation(ctx: Context, on: Boolean): Write {
        val out = shell(ctx, listOf("cmd location set-location-enabled $on")) ?: return Write(false, NO_CHANNEL)
        return Write(locationOn(ctx) == on, out)
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    fun holdsSecure(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun canWrite(ctx: Context): Boolean = holdsSecure(ctx) || AdbReadiness.usableNow(ctx)

    const val NO_CHANNEL = "Needs WRITE_SECURE_SETTINGS or a live shell channel (Wireless debugging pairing); this device has neither."

    /** Each command through the broker, one at a time; null when there is no channel. The joined output is the detail. */
    private fun shell(ctx: Context, cmds: List<String>): String? {
        val app = ctx.applicationContext
        val st = runCatching { ShellClient.status(app) }.getOrNull()
        if (st == null || !st.armed) return null
        return cmds.joinToString("; ") { c ->
            val r = runCatching { ShellClient.exec(app, c) }.getOrNull()
            val text = when (r) {
                is ShellResult.Ok -> (r.stdout + r.stderr).trim().ifBlank { "ok" }
                null -> "failed"
                else -> r.toString()
            }
            "$c -> $text"
        }
    }
}
