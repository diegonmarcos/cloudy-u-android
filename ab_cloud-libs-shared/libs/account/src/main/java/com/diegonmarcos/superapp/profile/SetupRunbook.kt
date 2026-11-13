package com.diegonmarcos.superapp.profile

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.diegonmarcos.superapp.appstore.AppInventory
import com.diegonmarcos.superapp.appstore.BuildConfig
import com.diegonmarcos.superapp.appstore.PhoneAppActions
import com.diegonmarcos.superapp.appstore.StoreImport
import com.diegonmarcos.superapp.fleetconfig.SetupContract
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Setup ▸ runbook, the engine (account redesign spec 4.6): an ordered list of steps that make this
 * phone match the loaded profile. Each step is `(id, check, run)`; the page (a later slot) only
 * draws them, the debug API (`/api/account/runbook`) drives them.
 *
 * The ids and their order are DATA: build.json::ui.account.runbook.steps (BuildConfig.UI_ACCOUNT_B64,
 * the same blob ForgeClient.Decl reads). An id left out is a disabled step. A host that declares no
 * runbook (SuperApp) gets the spec's eight.
 *
 * Steps: `connected` (a forge token + a fetched vault), `profile` (the working slot holds this
 * phone's file, else DEFAULT), `shell` (Account's own uid-2000 channel through [HostShell]), `store`
 * (Cloud Store from the fleet release over that channel), `apps` (the working inventory handed to
 * the Store over [StoreImport.EXTRA_IMPORT], counted in the Store's own plan classes), `configs`
 * ([FleetSetup.run] over [DeviceProfile.plan] of the working file, SuperApp first), `perms`
 * ([PermsPlan] over the channel) and `verified` (every installed fleet app's export diffed against
 * the working file). A step with nothing to do answers ALREADY, so a re-run on a set-up phone is all
 * ALREADY. [runAll] walks them in order and stops at the first FAILED (or a RUNNING that waits on
 * the user). Every check and run BLOCKS (network, adb, package manager): call off the main thread.
 * States carry names, counts and short reasons only, never a value.
 */
class SetupRunbook(private val ctx: Context) {

    sealed class State(val name: String, val detail: String) {
        class Todo(detail: String) : State("TODO", detail)
        class Running(detail: String) : State("RUNNING", detail)
        class Done(detail: String) : State("DONE", detail)
        class Failed(reason: String) : State("FAILED", reason)
        class Already(detail: String) : State("ALREADY", detail)

        fun json(): JSONObject = JSONObject().put("state", name).put("detail", detail)
    }

    class Step(val id: String, val check: () -> State, val run: () -> State)

    /** The declared steps, in order. [allowPrompt]: may `store` fall back to the system install prompt. */
    fun steps(allowPrompt: Boolean = false): List<Step> = declaredIds().map { id ->
        when (id) {
            SHELL -> Step(id, { checkShell() }, { runShell() })
            STORE -> Step(id, { checkStore() }) { runStore(allowPrompt) }
            CONNECTED -> Step(id, { checkConnected() }, { runConnected() })
            PROFILE -> Step(id, { checkProfile() }, { runProfile() })
            APPS -> Step(id, { checkApps() }, { runApps() })
            CONFIGS -> Step(id, { checkConfigs() }, { runConfigs() })
            PERMS -> Step(id, { perms(false) }, { perms(true) })
            VERIFIED -> Step(id, { checkVerified(false) }, { checkVerified(true) })
            else -> Step(id, { notHere() }, { notHere() })
        }
    }

    fun step(id: String, allowPrompt: Boolean = false): Step? = steps(allowPrompt).firstOrNull { it.id == id }

    /** Every step's check, in order (the dry run); `checked_at` = when the checks finished (UTC ISO). */
    fun dry(): JSONObject {
        val arr = JSONArray()
        for (s in steps()) arr.put(guard { s.check() }.json().put("id", s.id))
        return JSONObject().put("steps", arr).put("checked_at", java.time.Instant.now().toString())
    }

    /** ONE step's check (what the runbook page runs per row on open and after every run). */
    fun check(id: String): JSONObject {
        val s = step(id) ?: return State.Failed("no step '$id' (declared: ${declaredIds().joinToString(",")})").json().put("id", id)
        return guard { s.check() }.json().put("id", s.id)
    }

    /** Run ONE step. Unknown or undeclared ids answer FAILED with the declared list. */
    fun run(id: String, allowPrompt: Boolean = false): JSONObject {
        val s = step(id, allowPrompt)
            ?: return State.Failed("no step '$id' (declared: ${declaredIds().joinToString(",")})").json().put("id", id)
        return guard { s.run() }.json().put("id", s.id)
    }

    /** The plan sheet Run all shows before it acts: every step's check (names and counts) + how many would act. */
    fun plan(): JSONObject {
        val d = dry()
        val arr = d.getJSONArray("steps")
        val act = (0 until arr.length()).count { arr.getJSONObject(it).optString("state") !in setOf("ALREADY", "DONE") }
        return d.put("to_run", act).put("declared", arr.length())
    }

    /** Run all: every declared step in order; stops at the first FAILED, and at a RUNNING (it waits on the user). */
    fun runAll(allowPrompt: Boolean = false, each: (JSONObject) -> Unit = {}): JSONObject {
        val arr = JSONArray()
        var stopped: String? = null
        for (s in steps(allowPrompt)) {
            val st = guard { s.run() }
            val row = st.json().put("id", s.id)
            arr.put(row); each(row)
            if (st is State.Failed || st is State.Running) { stopped = s.id; break }
        }
        return JSONObject().put("steps", arr).put("stopped_at", stopped ?: JSONObject.NULL)
            .put("result", if (stopped == null) "✓ all ${arr.length()} steps" else "stopped at $stopped")
    }

    // ── connected ────────────────────────────────────────────────────────

    private fun tokenHeld(): Boolean = DeviceVault(ctx).credentials().let { c -> c.keys().asSequence().any { c.optBoolean(it) } }

    private fun fetched(): JSONObject? = ConnectWays.lastFetch(ctx)?.takeIf { it.optBoolean("ok") }

    private fun checkConnected(): State {
        val t = tokenHeld(); val f = fetched()
        return when {
            t && f != null -> State.Already("forge token held; vault fetched ${f.optString("at")}")
            !t -> State.Todo("no forge token: Account ▸ connect")
            else -> State.Todo("forge token held, vault not fetched yet")
        }
    }

    private fun runConnected(): State {
        checkConnected().let { if (it is State.Already) return it }
        if (!tokenHeld()) return State.Failed("no forge token: open Account ▸ connect and sign in")
        val o = ConnectWays.fetchNow(ctx)
        return if (o.ok) State.Done(o.line) else State.Failed(o.line.removePrefix("✗ "))
    }

    // ── profile ──────────────────────────────────────────────────────────

    private fun checkProfile(): State {
        val id = AccountDevice.id(ctx)
        val w = DeviceVault(ctx).working() ?: return State.Todo("working slot empty (this phone is '$id')")
        val dev = w.optString("device")
        return if (dev == id || dev == DeviceProfile.DEFAULT_ID) State.Already("working = $dev (${w.optString("sha").take(7)})")
               else State.Todo("working = $dev, this phone is '$id'")
    }

    private fun runProfile(): State {
        val v = DeviceVault(ctx)
        // Refresh the devices/ listing first, so the model derivation sees every file's `model`.
        if (AccountDevice.id(ctx).isBlank()) runCatching { v.devices() }
        val dev = AccountDevice.resolve(ctx)
        val id = dev.id
        if (id.isBlank()) return State.Failed("this phone has no device id: pick it in Account ▸ profile " +
            "(model ${dev.model}; candidates: ${dev.candidates.joinToString(", ").ifBlank { "none" }})")
        if (v.working()?.optString("device") == id) return checkProfile()
        val path = v.decl.devicePath(id)
        val defPath = v.decl.devicePath(DeviceProfile.DEFAULT_ID)
        val r = v.load(id)
        if (r.optBoolean("ok")) return State.Done(r.optString("result").removePrefix("✓ "))
        if (r.optInt("status") != 404) return State.Failed("$path: " + r.optString("result").removePrefix("✗ "))
        if (v.working()?.optString("device") == DeviceProfile.DEFAULT_ID) return checkProfile()
        val d = v.load(DeviceProfile.DEFAULT_ID)
        if (d.optBoolean("ok")) return State.Done("no $path: " + d.optString("result").removePrefix("✓ "))
        return if (d.optInt("status") == 404) State.Failed("neither $path nor $defPath exists on '${v.primary()?.id.orEmpty()}'")
               else State.Failed("$defPath: " + d.optString("result").removePrefix("✗ "))
    }

    private fun workingProfile(): JSONObject? = DeviceVault(ctx).working()?.optJSONObject("profile")

    // ── apps ─────────────────────────────────────────────────────────────

    /** The working inventory against this phone, in the Store's own classes. Null: nothing loaded. */
    fun appsPlan(): AppInventory.Plan? {
        val inv = workingProfile()?.optJSONObject("apps") ?: return null
        val wanted = AppInventory.parse(inv.toString())
        val installed = wanted.map { it.pkg }.filter { FleetSetup.installed(ctx, it) }.toSet()
        return AppInventory.plan(wanted, installed, AppInventory.fleetPackages(), PhoneAppActions.sources(ctx))
    }

    private fun checkApps(): State {
        val p = appsPlan() ?: return State.Todo("no working profile: run step 'profile' first")
        return if (p.ours.isEmpty() && p.direct.isEmpty()) State.Already(appsLine(p)) else State.Todo(appsLine(p))
    }

    private fun runApps(): State {
        val p = appsPlan() ?: return State.Failed("no working profile: run step 'profile' first")
        if (p.ours.isEmpty() && p.direct.isEmpty()) return State.Already(appsLine(p))
        if (storeInfo() == null) return State.Failed("Cloud Store not installed: run step 'store' first")
        if (!handToStore()) return State.Failed("Cloud Store did not open")
        return State.Running("handed to Cloud Store: confirm its plan sheet (Install all missing); ${appsLine(p)}")
    }

    /** The working inventory → Cloud Store's Phone page over [StoreImport.EXTRA_IMPORT]; it shows its plan first. */
    fun handToStore(): Boolean = runCatching {
        val inv = workingProfile()?.optJSONObject("apps") ?: return false
        val i = android.content.Intent("$STORE_PKG.OPEN").setPackage(STORE_PKG).putExtra("tab", "phone")
            .putExtra(StoreImport.EXTRA_IMPORT, inv.toString()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i); true
    }.getOrDefault(false)

    // ── configs / verified ───────────────────────────────────────────────

    /**
     * The SAME plan `/api/account/setup` pushes (Connections + the declared copy + the vault bundle's Configs
     * section, [FleetSetup.plan]) MERGED with the working file's config-class keys, the device file winning per
     * key ([SetupPlan.merge]); SuperApp first. `verified` diffs against this same plan.
     */
    fun configsPlan(): SetupPlan.Plan? {
        val prof = workingProfile() ?: return null
        val vault = com.diegonmarcos.superapp.settings.AccountVault(ctx)
        val fleet = FleetSetup.plan(ctx, vault.prefs.json, AccountModel.get(ctx).shown(), vault.appConfigs())
        val device = FleetSetup.plan(ctx, null, null, DeviceProfile.plan(prof, DeviceProfile.manifestClass(ctx)))
        val pl = SetupPlan.merge(fleet, device)
        return SetupPlan.Plan(pl.apps.sortedBy { if (it.app.pkg == SUPERAPP_PKG) 0 else 1 }, pl.unmapped)
    }

    /** Every installed app's export against the plan: drifted `app.store.key` names (never values). */
    private fun drift(plan: SetupPlan.Plan): List<String> {
        val t = FleetSetup.transport(ctx)
        val out = ArrayList<String>()
        for (ap in plan.apps) {
            if (ap.items.isEmpty() || !FleetSetup.installed(ctx, ap.app.pkg)) continue
            for ((sf, items) in ap.groups()) {
                val back = (t.call(ap.app.pkg, SetupContract.METHOD_EXPORT, sf.first, null) as? SetupContract.Reply.Ok)
                    ?.json?.optJSONObject("stores")?.optJSONObject(sf.first)?.optJSONObject(sf.second)
                for (i in items) if (back == null || !FleetSetup.same(back.opt(i.key), i.value)) out += "${ap.app.id}.${sf.first}.${i.key}"
            }
        }
        return out
    }

    private fun checkConfigs(): State {
        val pl = configsPlan() ?: return State.Todo("no working profile: run step 'profile' first")
        if (pl.itemCount == 0) return State.Already("nothing to push (0 config keys in the vault bundle and the working file)")
        val d = drift(pl)
        return if (d.isEmpty()) State.Already("${pl.itemCount} keys in ${pl.apps.size} apps already applied")
               else State.Todo("${d.size} key(s) to apply: ${d.take(5).joinToString()}")
    }

    private fun runConfigs(): State {
        val pl = configsPlan() ?: return State.Failed("no working profile: run step 'profile' first")
        checkConfigs().let { if (it is State.Already) return it }
        val outs = FleetSetup.run(pl, { FleetSetup.installed(ctx, it) }, FleetSetup.transport(ctx))
        val good = outs.count { it.state == FleetSetup.State.DONE || it.state == FleetSetup.State.NOTHING }
        val bad = outs.filter { it.state == FleetSetup.State.FAILED || it.state == FleetSetup.State.NO_CONTRACT }
        val line = "✓ $good apps" + bad.joinToString("") { " · " + it.line() }
        return if (bad.isEmpty()) State.Done(line) else State.Failed(line)
    }

    /** [run]: a drift is FAILED (Re-check found it); as a check it is TODO (configs not run yet). */
    private fun checkVerified(run: Boolean): State {
        val pl = configsPlan() ?: return State.Todo("no working profile: run step 'profile' first")
        val d = drift(pl)
        if (d.isEmpty()) return State.Already("drift 0")
        val line = "drift ${d.size}: ${d.take(5).joinToString()}"
        return if (run) State.Failed(line) else State.Todo(line)
    }

    // ── perms (PermsPlan, task 6) ────────────────────────────────────────

    private fun perms(run: Boolean): State {
        val prof = workingProfile() ?: return if (run) State.Failed("no working profile: run step 'profile' first")
                                              else State.Todo("no working profile: run step 'profile' first")
        val ch = ShellChannels.active(ctx)
        val plan = PermsPlan.plan(ctx, prof, ch)
        val user = plan.todo.count { it.kind == PermsPlan.Kind.USER }
        if (plan.todo.size == user) return State.Already("✓ ${plan.apps.size} apps · $user need the user")
        if (!run) return State.Todo(plan.summary())
        val out = PermsPlan.apply(ctx, plan, ch)
        val bad = out.lines.filter { it.status == PermsPlan.Status.FAILED || it.status == PermsPlan.Status.NO_CHANNEL }
        val needs = out.lines.count { it.status == PermsPlan.Status.NEEDS_USER }
        val line = "✓ ${plan.apps.size} apps · $needs need the user" + bad.take(3).joinToString("") { " · " + it.text() }
        return if (bad.isEmpty()) State.Done(line) else State.Failed(line)
    }

    // ── shell ────────────────────────────────────────────────────────────

    private fun checkShell(): State {
        val ch = ShellChannels.active(ctx)
        if (ch != null) return State.Already("channel ${ch.name()} up")
        return State.Todo("the ADB shell of Cloud C3 Webserver is not armed: ${ShellChannels.status(ctx)}")
    }

    private fun runShell(): State {
        ShellChannels.active(ctx)?.let { return State.Already("channel ${it.name()} up") }
        // #1512 The channel belongs to the SuperApp's broker: the runbook opens its arm flow rather than carrying a pairing flow of its own,
        // and picks up once the broker answers armed.
        com.diegonmarcos.superapp.shellclient.ShellClient.openArmPage(ctx)
        return State.Running("The SuperApp's arm flow is open: switch Wireless debugging on and pair there. This step is done once the broker answers armed.")
    }

    // ── store ────────────────────────────────────────────────────────────

    private fun storeInfo(): PackageInfo? = runCatching { ctx.packageManager.getPackageInfo(STORE_PKG, 0) }.getOrNull()

    private fun checkStore(): State =
        storeInfo()?.let { State.Already("Cloud Store ${it.versionName} installed") }
            ?: State.Todo("Cloud Store not installed")

    private fun runStore(allowPrompt: Boolean): State {
        storeInfo()?.let { return State.Already("Cloud Store ${it.versionName} installed") }
        val app = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64).firstOrNull { it.pkg == STORE_PKG }
            ?: return State.Failed("Cloud Store ($STORE_PKG) is not in this build's fleet manifest")
        val ch = ShellChannels.active(ctx)
        if (ch == null && !allowPrompt)
            return State.Failed("no shell channel: run step 'shell' first (pair Wireless debugging); " +
                "the system install prompt is the fallback only when asked for (prompt=1)")
        val apk = Fleet.download(ctx, app)
        // The fleet key is the key this app is signed with: refuse anything else before it reaches pm.
        val theirs = signers(ctx.packageManager.getPackageArchiveInfo(apk.file.path, signingFlags()))
        val ours = signers(runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, signingFlags()) }.getOrNull())
        if (theirs.isEmpty()) return State.Failed("cannot read the downloaded APK's signer; not installed")
        if (ours.isEmpty()) return State.Failed("cannot read this app's own signer to compare; not installed")
        if (theirs.intersect(ours).isEmpty())
            return State.Failed("downloaded APK is not signed with the fleet key " +
                "(${theirs.first().take(12)} vs ${ours.first().take(12)}); not installed")
        val via = Fleet.commit(ctx, app, apk)
        storeInfo()?.let { return State.Done("Cloud Store ${it.versionName} installed via $via (${apk.evidence})") }
        return if (ch == null) State.Running("handed to the system installer ($via): confirm the prompt on screen")
               else State.Failed("install over $via finished but $STORE_PKG is still not installed")
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(pi: PackageInfo?): Set<String> {
        pi ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = pi.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else pi.signatures
        return sigs.orEmpty().map { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    // ── declaration ──────────────────────────────────────────────────────

    private fun notHere() = State.Todo("not implemented in this slot")

    private inline fun guard(f: () -> State): State =
        try { f() } catch (t: Throwable) { State.Failed("${t.javaClass.simpleName}: ${t.message ?: "no message"}") }

    fun declaredIds(): List<String> = declared(com.diegonmarcos.superapp.account.BuildConfig.UI_ACCOUNT_B64)

    companion object {
        const val SHELL = "shell"
        const val STORE = "store"
        const val STORE_PKG = "com.diegonmarcos.cloudstore"
        const val SUPERAPP_PKG = "com.diegonmarcos.superapp"
        const val CONNECTED = "connected"
        const val PROFILE = "profile"
        const val APPS = "apps"
        const val CONFIGS = "configs"
        const val PERMS = "perms"
        const val VERIFIED = "verified"

        /** The plan as names and counts (the `apps` debug op): no store intents, no versions. */
        fun appsJson(p: AppInventory.Plan): JSONObject {
            fun names(l: List<AppInventory.Entry>) = JSONArray(l.map { it.pkg })
            return JSONObject().put("line", appsLine(p))
                .put("installed", names(p.installed)).put("fleet", names(p.ours)).put("direct", names(p.direct))
                .put("no_source", names(p.store.map { it.entry } + p.manual))
        }

        /** `61 declared · 58 installed · 3 need a source` (need a source = no fleet, vendor or F-Droid rung). */
        fun appsLine(p: AppInventory.Plan): String {
            val n = p.installed.size + p.ours.size + p.direct.size + p.store.size + p.manual.size
            // play-anon apps are installable by our Store (in p.direct); the ones whose
            // vendor ties them to Play Integrity are counted apart, not hidden.
            return "$n declared · ${p.installed.size} installed · ${p.managed.size} managed by our Store · " +
                "${p.needIntegrity.size} vendor requires Play · ${p.store.size + p.manual.size} need a source"
        }
        /** Spec 4.6, in order: the default when the host declares no runbook. */
        val SPEC_STEPS = listOf(CONNECTED, PROFILE, SHELL, STORE, APPS, CONFIGS, PERMS, VERIFIED)

        /** Step ids from the host's ui.account blob (base64 JSON); [SPEC_STEPS] when it declares none. */
        fun declared(uiAccountB64: String): List<String> {
            val o = runCatching {
                JSONObject(String(java.util.Base64.getDecoder().decode(uiAccountB64)).ifBlank { "{}" })
            }.getOrDefault(JSONObject())
            val arr = o.optJSONObject("runbook")?.optJSONArray("steps") ?: return SPEC_STEPS
            return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }.distinct()
        }
    }
}
