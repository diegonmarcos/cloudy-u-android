package com.diegonmarcos.superapp.profile

import android.content.Context
import com.diegonmarcos.superapp.devtools.AppDebugServer
import com.diegonmarcos.superapp.devtools.AppDebugServer.Op
import org.json.JSONArray
import org.json.JSONObject

/**
 * #778 `/api/account/…` on the fleet debug API (libs:devtools: loopback + fleet token), so the
 * architect tests Configs ▸ Account on the phone with curl. Reads and actions call [AccountModel] —
 * the very functions the tabs' buttons call. GET-only server, so every action is a GET with its
 * verb as the op (the "POST-equivalents").
 *
 * NO SECRET LEAVES HERE: values pass the Infos mask ([InfoMask.declared]) or are not printed at
 * all (runtime and drift answer paths, kinds and counts); a masked field cannot be set through
 * [edit]; the upload uses the gh engine's token in-process and prints only the commit.
 */
object AccountDebugApi {

    /** #802 profile-secrets.json is ~2 MB; the server's default body ceiling (256 KiB) holds for every other op. */
    const val IMPORT_MAX_BODY = 4 * 1024 * 1024

    fun register(ctx: Context) {
        val app = ctx.applicationContext
        AppDebugServer.route("account", listOf(
            Op("tabs", "", "the host's declared islands and their pages (Cloud Account: ui.bottom_nav + ui.sections), the legacy tab strip and drift pairs"),
            Op("profiles", "", "the local declared copy (else the server file) per topic, through the mask"),
            Op("runtime", "", "the last runtime snapshot: per app its status, declared fields, observed paths, missing / not-read and counts; totals"),
            Op("drift", "", "file metadata, per-pair counts, per-app drifted paths, three-way classes"),
            Op("refresh", "", "read every app now and store R"),
            Op("populate", "from=runtime|server", "populate the local copy (unsaved)"),
            Op("edit", "path=&value=", "set one unmasked field of the local copy (unsaved)"),
            Op("save", "", "write the local copy"),
            Op("apply", "app=mesh|mail", "#573 apply the whole declared section as a unit — what the Runtime card's Apply all taps"),
            Op("sync", "dir=push|pull|discard&path=|app=|all=1", "server→runtime, runtime→declared, or discard L"),
            Op("upload", "dry=1", "commit the saved local copy with the gh engine's token (dry=1: the plan only)"),
            Op("erase", "confirm=1", "GDPR: erase the contact card on this device and ask the profile-sync server to drop its copy"),
            // #783 the whole fleet and the new phone
            Op("fleet", "", "per fleet app: contract coverage (covered stores, named gaps, %) from the manifest"),
            // #873/#874 the vault and the fleet setup: counts, key names and ✓/✗ lines; never a value
            Op("vault", "", "section sizes (connections, data, configs, secrets), per-package grants (patterns only), and per-manifest-class key counts (config/secret/device)"),
            Op("setup", "dry=1|run=1&app=", "dry=1: per app the keys Fleet Setup would push (names, never values) and what no declared store takes; run=1: describe -> apply -> read back, one ✓/✗ line per app (app= retries one)"),
            Op("migrate", "dry=1|status=1", "dry=1: the plan per app; status=1: the running/last report; bare: start the migration (install missing, apply all)"),
            // Cloud Account redesign task 2: the per-device files in the vault, through ForgeClient
            Op("devices", "", "devices/ on the primary forge: id, path, blob sha, captured_at per file; and which forges hold a token (true/false)"),
            Op("backup", "device=&dry=1&capture=0", "capture this phone as devices/<device>.json, one commit: first every installed fleet app's export into Configs (capture=0 skips it); dry=1: key NAMES and counts only; equal but for captured_at = no commit"),
            Op("load", "device=", "fetch devices/<device|DEFAULT>.json into the working slot (refused if a secret-class key holds a literal)"),
            Op("setdefault", "device=", "copy devices/<device>.json over devices/DEFAULT.json, one commit"),
            Op("forge", "op=get|put&path=&forge=&dry=1", "get: the file's blob sha and size (never the content); put: dry=1 only — target, method, body keys"),
            Op("runbook", "dry=1 | plan=1 | run=1[&step=<id>]&prompt=1", "Setup runbook (spec 4.6): dry=1 = every declared step's id + check state; plan=1 = the plan sheet Run all shows; run=1&step= runs one; run=1 with no step = Run all (in order, stops at the first FAILED or a RUNNING that waits on the user); prompt=1 lets store fall back to the system install prompt. Names, counts and states only"),
            // #802 the engine's way in (linux-account phone import): the decrypted export as the POST body
            Op("import", "POST body = the decrypted vault export · device=<electronics id> (optional: the device this phone is, as the terminal picked it)", "land the bundle as S through the UI import's own gates (VaultFile.classify: sops/ENC refused, schema_version must be known) — the verdict and per-topic counts, never a value", maxBody = IMPORT_MAX_BODY),
            // Cloud Account redesign task 6: Setup ▸ perms (spec 4.9) through PermsPlan
            Op("perms", "dry=1|run=1", "Setup ▸ perms (spec 4.9): dry=1 = per app every runtime permission and special grant, its state (granted/denied/unknown) and whether the working profile wants it, names only; run=1 = Grant all over the shell channel, one line per item, each judged by a re-read"),
            Op("apps", "", "Setup ▸ apps (spec 4.7): the working profile's inventory vs this phone in the Store's classes (installed / fleet / direct = vendor or F-Droid rung / no source declared), package names and counts only"),
            Op("profiles", "", "Profiles pages (spec 4.3-4.5): the loaded device and the working-vs-runtime drift count; counts only, never a value"),
            Op("device", "set=<id>&new=1", "which device this phone is: id, source (connections|cockpit|derived|none), Build.MODEL and the model-matched candidates; set= persists Connections device.id, refused unless the id is a declared device (electronics / devices/) or new=1"),
        )) { op, q -> runCatching { handle(app, op, q)?.toString() }.getOrElse { JSONObject().put("error", it.message).toString() } }
        // /api/adb/... on Account's own port, so its uid-2000 channel can be driven from a terminal.
        // Same shape as SuperApp's DevControlServer handler (copied: a lib never imports app code);
        // behind the fleet token like every route group.
        AppDebugServer.route("adb", listOf(
            Op("status", "", "the shell-channel ladder: which channel is active, and per channel ready + status"),
            Op("exec", "cmd=<shell command>", "'adb shell' passthrough: runs `sh -c <cmd>` as uid 2000 over the active channel and returns raw stdout; token-gated, loopback-only"),
        )) { op, q -> runCatching { adb(app, op, q) }.getOrElse { "ERR: ${it.javaClass.simpleName}\n" } }
    }


    /** `vault`: section counts, grants, and per-manifest-class key counts (config / secret / device). */
    private fun vault(ctx: Context): JSONObject {
        val v = com.diegonmarcos.superapp.settings.AccountVault(ctx)
        val m = runCatching { AccountFleet.manifest(ctx) }.getOrNull()
        val routes = m?.let { SetupPlan.routes(it) }.orEmpty()
        val classes = JSONObject()
        if (m != null) {
            SetupPlan.leaves(v.connections()).mapNotNull { path ->
                val r = routes[path]?.firstOrNull() ?: return@mapNotNull null
                val app = m.apps.values.firstOrNull { a -> m.stores[r.store]?.usedBy?.any { it in a.libs || it == a.module } == true } ?: return@mapNotNull null
                m.keyClass(m.stores.getValue(r.store), r.key, app.id)
            }.groupingBy { it }.eachCount().forEach { (cls, n) -> classes.put(cls, n) }
        }
        return JSONObject().put("sections", v.summary())
            .put("grants", JSONObject().also { o -> v.grants.all().forEach { (pkg, keys) -> o.put(pkg, JSONArray(keys)) } })
            .put("classes", classes)
    }

    private fun setup(ctx: Context, q: Map<String, String>, m: AccountModel): JSONObject {
        val v = com.diegonmarcos.superapp.settings.AccountVault(ctx)
        val plan = FleetSetup.plan(ctx, com.diegonmarcos.superapp.settings.ConfigsPrefs(ctx).json, m.shown(), v.appConfigs())
        val unmapped = JSONArray(plan.unmapped.map { JSONObject().put("source", it.source).put("why", it.why) })
        if (q["run"] != "1") return JSONObject().put("keys", plan.itemCount).put("unmapped", unmapped)
            .put("apps", JSONArray(plan.apps.map { a -> JSONObject().put("id", a.app.id).put("installed", FleetSetup.installed(ctx, a.app.pkg))
                .put("keys", JSONArray(a.items.map { it.store + "." + it.key })) }))
        val only = q["app"]?.takeIf { it.isNotBlank() }?.let { setOf(it) }
        val out = FleetSetup.run(plan, { FleetSetup.installed(ctx, it) }, FleetSetup.transport(ctx), only)
        return JSONObject().put("lines", JSONArray(out.map { it.line() })).put("ok", out.all { it.ok })
    }

    fun handle(ctx: Context, op: String, q: Map<String, String>): JSONObject? {
        val m = AccountModel.get(ctx)
        return when (op) {
            "", "tabs" -> JSONObject()
                .also { o -> AccountHost.nav?.invoke()?.let { o.put("islands", it) } }
                .put("tabs", JSONArray(AccountModel.tabs().map { JSONObject().put("id", it.id).put("label", it.label) }))
                .put("pairs", JSONArray(AccountModel.pairs().map { JSONObject().put("id", it.id).put("a", it.a.name).put("b", it.b.name) }))
            "vault" -> vault(ctx)
            "setup" -> setup(ctx, q, m)
            "profiles" -> profiles(m)
            "runtime" -> m.runtime()?.let { r -> JSONObject().put("meta", r.meta.json().put("intact", r.intact))
                .put("apps", r.apps ?: JSONObject()).put("totals", totals(r.apps)).put("unmapped", unmapped()) }
                ?: JSONObject().put("meta", JSONObject.NULL)
            "drift" -> m.report()
            "refresh" -> done(m.refreshRuntime(), m)
            "populate" -> done(if (q["from"] == "server") m.populateFromServer() else if (q["from"] == "runtime") m.populateFromRuntime()
                               else "✗ from=runtime|server", m)
            "edit" -> {
                val path = q["path"].orEmpty(); val value = q["value"].orEmpty()
                done(if (path.isBlank() || InfoMask.declared.hides(path, value)) "✗ not an editable field (blank or masked)" else m.edit(path, value), m)
            }
            "save" -> done(m.save(), m)
            "apply" -> done(m.applySection(q["app"].orEmpty()), m)
            "sync" -> {
                val paths = target(m, q, if (q["dir"] == "push") AccountStore.Slot.S else AccountStore.Slot.L)
                done(when (q["dir"]) {
                    "push" -> m.pushServerToRuntime(paths)
                    "pull" -> m.pullRuntimeToLocal(paths)
                    "discard" -> m.discardLocal()
                    else -> "✗ dir=push|pull|discard"
                }, m)
            }
            "upload" -> {
                val device = VaultCockpit.selectedDevice(ctx)
                if (q["dry"] == "1") {
                    val plan = m.uploadPlan(device) ?: return JSONObject().put("result", "✗ no saved local copy")
                    JSONObject().put("repo", plan.first.repo).put("path", plan.first.path).put("branch", plan.first.branch)
                        .put("bytes", plan.second.size).put("message", plan.third)
                } else {
                    val token = AccountModel.ghHost()?.let { GhEngine(ctx).token(it) }
                    done(if (token == null) "✗ the gh engine holds no GitHub token — sign in on Connect ▸ GitHub ▸ WebAuth" else m.upload(token, device), m)
                }
            }
            // #781 the contact card form (and its Erase button) left Runtime; the erasure stays reachable here.
            "erase" -> {
                if (q["confirm"] != "1") return JSONObject().put("result", "✗ confirm=1 — this erases the contact card here and on the server")
                val done = java.util.concurrent.LinkedBlockingQueue<String>()
                ProfileSync.forgetMe(ctx) { done.put(it) }
                JSONObject().put("result", done.poll(30, java.util.concurrent.TimeUnit.SECONDS) ?: "✗ no answer in 30 s — the erase may still complete")
            }
            "fleet" -> fleet(ctx)
            "devices" -> DeviceVault(ctx).let { v -> v.devices().put("credentials", v.credentials()) }
            "backup" -> DeviceVault(ctx).backup(q["device"]?.trim().orEmpty().ifBlank { VaultCockpit.selectedDevice(ctx) }, q["dry"] == "1", q["capture"] != "0")
            "load" -> DeviceVault(ctx).load(q["device"]?.trim().orEmpty())
            "setdefault" -> DeviceVault(ctx).setDefault(q["device"]?.trim().orEmpty())
            "forge" -> forge(ctx, q)
            "runbook" -> SetupRunbook(ctx).let { r ->
                when {
                    q["run"] == "1" && q["step"].isNullOrBlank() -> r.runAll(allowPrompt = q["prompt"] == "1")
                    q["run"] == "1" -> r.run(q["step"]?.trim().orEmpty(), allowPrompt = q["prompt"] == "1")
                    q["plan"] == "1" -> r.plan()
                    else -> r.dry()
                }
            }
            "perms" -> perms(ctx, q)
            "apps" -> SetupRunbook(ctx).appsPlan()?.let { SetupRunbook.appsJson(it) }
                ?: JSONObject().put("result", "✗ no working profile: load one first (/api/account/load)")
            "profiles" -> JSONObject().put("loaded", DeviceVault(ctx).working()?.optString("device").orEmpty())
                .put("drift", profilesDriftCount(ctx, m))
            "import" -> importBundle(ctx, q["_body"].orEmpty(), q, m)
            "device" -> device(ctx, q)
            "migrate" -> when {
                q["dry"] == "1" -> m.migratePlan()
                q["status"] == "1" -> AccountFleet.progress
                else -> {
                    // Long (installs): started on its own thread, followed with status=1.
                    if (AccountFleet.progress.optString("state") != "running") Thread({ m.migrate() }, "account-migrate").start()
                    JSONObject().put("started", true).put("follow", "/api/account/migrate?status=1")
                }
            }
            else -> null
        }
    }

    /** `device`: read the resolved id; `set=<id>` persists it after checking it is declared (or new=1). */
    private fun device(ctx: Context, q: Map<String, String>): JSONObject {
        val set = q["set"]?.trim()
        if (set.isNullOrEmpty()) return AccountDevice.resolve(ctx).json()
        if (set == DeviceProfile.DEFAULT_ID) return JSONObject().put("result", "✗ ${DeviceProfile.DEFAULT_ID} is the fallback file, not a device")
        val bundle = AccountModel.get(ctx).server()?.body
        val listing = com.diegonmarcos.superapp.settings.ConfigsPrefs(ctx).text(AccountDevice.K_LISTING)
            .let { t -> runCatching { org.json.JSONArray(t) }.getOrNull() }
        val known = LinkedHashSet<String>()
        AccountDevice.declared(ctx).forEach { known += it.id }
        bundle?.optJSONObject("electronics")?.optJSONObject("fleet")?.keys()?.forEach { known += it }
        if (listing != null) for (i in 0 until listing.length()) listing.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { known += it }
        if (set !in known && q["new"] != "1")
            return JSONObject().put("result", "✗ '$set' is not a declared device (known: ${known.joinToString(",")}); add new=1 for a new one")
        AccountDevice.setId(ctx, set)
        return AccountDevice.resolve(ctx).json().put("result", "✓ device id = $set")
    }

    /**
     * #802 `POST /api/account/import` with the decrypted export as the body: the SAME gates as the
     * Import File way ([ConnectWays.importFile] → [AccountHost.classify], then
     * [AccountModel.landBundle]'s schema check and write of S). The answer is the verdict, the
     * refusal sentence when refused, and the Profiles topics as counts only (rows dropped):
     * nothing of the body is echoed.
     */
    private fun importBundle(ctx: Context, text: String, q: Map<String, String>, m: AccountModel): JSONObject {
        if (text.isEmpty()) return JSONObject().put("verdict", "empty").put("result", "✗ POST the decrypted vault export as the request body")
        val v = AccountHost.classify(text)
        val verdict = v::class.java.simpleName.lowercase()
        if (v !is com.diegonmarcos.cloudlib.auth.VaultFile.Verdict.Bundle)
            return JSONObject().put("verdict", verdict).put("result", "✗ " + AccountHost.refusal(ctx, v).orEmpty())
        AccountModel.landBundle(ctx, v.bundle, "api:import")?.let { bad ->
            return JSONObject().put("verdict", "unknownschema").put("schema_version", bad)
                .put("result", "✗ schema_version $bad — this build knows ${com.diegonmarcos.cloudlib.auth.VaultConnect.knownSchemaVersions.sorted()}")
        }
        // The device pick travels with the bundle: the terminal already knows which declared
        // device this phone is (linux-account device), and a phone whose pick is left to the
        // live tunnel inherits whoever's profile is up — the A37 ran as the S21+ (10.0.0.9).
        val device = q["device"]?.trim().orEmpty()
        val picked = when {
            device.isEmpty() -> null
            VaultCockpit.devices(v.bundle).none { it.id == device } -> "✗ device '$device' is not declared in this bundle"
            else -> { VaultCockpit.selectDevice(ctx, device); "✓ $device" }
        }
        val out = profiles(m)
        picked?.let { out.put("device", it) }
        out.optJSONArray("topics")?.let { t -> for (i in 0 until t.length()) t.optJSONObject(i)?.remove("rows") }
        return out.put("verdict", verdict).put("result", m.last)
    }

    /** `forge?op=get|put`: the raw ForgeClient, for testing both forges. Never prints a token or a file's content. */
    private fun forge(ctx: Context, q: Map<String, String>): JSONObject {
        val v = DeviceVault(ctx)
        val path = q["path"].orEmpty().trim()
        if (path.isBlank()) return JSONObject().put("result", "✗ path=")
        val forgeId = q["forge"]?.trim()
        val f = (if (forgeId.isNullOrBlank()) v.primary() else v.decl.forge(forgeId))
            ?: return JSONObject().put("result", "✗ no such forge (declared: ${v.decl.forges.joinToString { it.id }})")
        if (f.repo == null) return JSONObject().put("forge", f.id).put("result", "✗ forge '${f.id}' declares no repo")
        return when (q["op"]) {
            "get" -> when (val g = v.client(f.id)!!.get(path, v.decl.branch)) {
                is ForgeClient.Result.Ok -> JSONObject().put("forge", f.id).put("path", path).put("sha", g.value.sha).put("bytes", g.value.text.length)
                is ForgeClient.Result.Failed -> JSONObject().put("forge", f.id).put("status", g.status).put("result", "✗ ${g.reason}")
            }
            "put" -> if (q["dry"] != "1") JSONObject().put("result", "✗ forge put is dry=1 only here — writes go through backup / setdefault")
                     else ForgeClient(f, "-").putShape(path, q["sha"]?.ifBlank { null }, "account(dry): put $path", v.decl.branch)
            else -> JSONObject().put("result", "✗ op=get|put")
        }
    }

    /** #783 per fleet app: what the contract moves and what it cannot yet (paths and counts, never values). */
    private fun fleet(ctx: Context): JSONObject {
        val m = AccountFleet.manifest(ctx)
        val apps = JSONObject()
        var covered = 0; var total = 0
        m.apps.values.forEach { a ->
            val c = m.coverage(a)
            covered += c.covered.size; total += c.total
            apps.put(a.id, c.json().put("package", a.pkg).put("schema_version", a.schema))
        }
        return JSONObject().put("contract", m.json.optInt("contract")).put("stores", m.stores.size)
            .put("covered", covered).put("total", total).put("percent", if (total == 0) 100 else covered * 100 / total).put("apps", apps)
    }

    /** Which paths a sync names: `path=` one, `app=` that app's drift against R, `all=1` every drift against R
     *  — R compared with [side] (S for a push, L for a pull). */
    private fun target(m: AccountModel, q: Map<String, String>, side: AccountStore.Slot): List<String> {
        q["path"]?.takeIf { it.isNotBlank() }?.let { return listOf(it) }
        val p = AccountModel.FilePair("", side, AccountStore.Slot.R)
        val fields = m.diff(p)
        return when {
            !q["app"].isNullOrBlank() -> AccountDrift.drifted(fields, q["app"]!!)
            q["all"] == "1" -> AccountDrift.drifted(fields)
            else -> emptyList()
        }
    }

    /** #781 Every app's counts summed — the one line the architect checks first. */
    private fun totals(apps: JSONObject?): JSONObject {
        val t = JSONObject()
        apps?.keys()?.forEach { id ->
            val c = apps.optJSONObject(id)?.optJSONObject("counts") ?: return@forEach
            c.keys().forEach { k -> t.put(k, t.optInt(k) + c.optInt(k)) }
        }
        return t.put("apps", apps?.length() ?: 0)
            .put("reachable", apps?.keys()?.asSequence()?.count { apps.optJSONObject(it)?.optString("status") == "reachable" } ?: 0)
    }

    /** #781 The Profiles fields no app holds, with the declared reason (cockpit vault_fields). */
    private fun unmapped(): JSONObject = JSONObject().apply {
        VaultCockpit.layout.vaultFields.filterValues { !it.held || it.apps.isEmpty() }
            .forEach { (path, f) -> put(path, JSONObject().put("apps", JSONArray(f.apps)).put("why", f.why)) }
    }

    private fun done(line: String, m: AccountModel) = JSONObject().put("result", line).put("local_unsaved", m.dirty)

    private fun profiles(m: AccountModel): JSONObject {
        val shown = m.shown()
        val topics = JSONArray()
        for (section in InfoMask.sectionsFor(InfoMask.schema, emptyList(), shown)) {
            val rows = InfoMask.declared.schemaRows(section, shown?.opt(section.id))
            topics.put(JSONObject().put("id", section.id).put("label", section.label)
                .put("fields", section.fields.size)
                .put("filled", rows.count { it.kind != InfoMask.Kind.EMPTY })
                .put("empty", rows.count { it.kind == InfoMask.Kind.EMPTY })
                .put("masked", rows.count { it.kind == InfoMask.Kind.MASKED })
                .put("rows", JSONArray(rows.map { r ->
                    JSONObject().put("path", r.path).put("kind", r.kind.name.lowercase()).apply {
                        if (r.kind == InfoMask.Kind.SHOWN) put("text", r.text.take(120)) else put("size", r.size)
                    }
                })))
        }
        val file = if (m.local != null) m.savedLocal() else m.server()
        return JSONObject().put("showing", if (m.local != null) "L" else if (shown != null) "S" else JSONObject.NULL)
            .put("meta", file?.meta?.json() ?: JSONObject.NULL).put("local_unsaved", m.dirty).put("topics", topics)
    }

    /** Setup ▸ perms (spec 4.9): dry = the plan, names and states; run=1 = PermsPlan.apply's lines. */
    private fun perms(ctx: Context, q: Map<String, String>): JSONObject {
        val profile = DeviceVault(ctx).working()?.optJSONObject("profile")
        val channel = runCatching { ShellChannels.active(ctx) }.getOrNull()
        val plan = PermsPlan.plan(ctx, profile, channel)
        val ch: Any = channel?.name() ?: JSONObject.NULL
        if (q["run"] != "1") return plan.json().put("channel", ch).put("profile", profile != null)
        return PermsPlan.apply(ctx, plan, channel).json().put("channel", ch).put("summary", plan.summary())
    }

    /** `/api/adb/status` and `/api/adb/exec?cmd=` through the SuperApp's shell broker (#1512), under this app's allowlist entry; null (404) for any other op. */
    private fun adb(ctx: Context, op: String, q: Map<String, String>): String? {
        return when (op) {
            "status" -> {
                val s = com.diegonmarcos.superapp.shellclient.ShellClient.status(ctx)
                JSONObject().put("active", if (s.armed) s.channel else "none").put("broker", s.brokerInstalled)
                    .put("armed", s.armed).put("detail", s.detail).toString()
            }
            "exec" -> {
                val cmd = q["cmd"]
                if (cmd.isNullOrBlank()) "missing cmd\n"
                else when (val r = com.diegonmarcos.superapp.shellclient.ShellClient.exec(ctx, cmd)) {
                    is com.diegonmarcos.superapp.shellclient.ShellResult.Ok -> r.stdout + r.stderr
                    is com.diegonmarcos.superapp.shellclient.ShellResult.Denied -> "ERR: denied by the shell broker: ${r.reason}\n"
                    is com.diegonmarcos.superapp.shellclient.ShellResult.NeedsArm -> "ERR: shell broker not armed — ${ShellChannels.status(ctx)}\n"
                    is com.diegonmarcos.superapp.shellclient.ShellResult.Failed -> "ERR: ${r.reason}\n"
                }
            }
            else -> null
        }
    }
}
