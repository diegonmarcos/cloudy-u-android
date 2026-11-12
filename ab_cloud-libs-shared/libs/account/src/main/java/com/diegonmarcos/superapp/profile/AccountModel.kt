package com.diegonmarcos.superapp.profile

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.diegonmarcos.superapp.account.BuildConfig
import com.diegonmarcos.superapp.profile.AccountStore.Slot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * #778 Configs ▸ Account — Profiles, Runtime and Drift share ONE model, and so does the debug API
 * (`/api/account/…`): every action the tabs offer is a function here, so the architect's curl and
 * the owner's tap run the same code.
 *
 * L is a WORKING COPY: Profiles edits and populates it in memory ([dirty]) until Save writes it.
 * The Drift sync actions are commands, so each one saves what it wrote.
 *
 * The declaration: build.json::ui.profile.tabs (the strip), ui.profile.drift (the comparison pairs,
 * the upload target and the runtime deadline), ui.profile.infos.schema (the topics) and
 * ui.vault_connect.cockpit (the apps and the vault sections each consumes).
 */
class AccountModel(private val ctx: Context, val store: AccountStore) {

    /** Bumped on every change; the tabs read it so a change anywhere redraws them. */
    val version = mutableIntStateOf(0)

    /** Each slot as last read or written — the files are decrypted and parsed once, not per draw. */
    private val docs = HashMap<Slot, AccountStore.Doc?>()
    private val leafCache = HashMap<Slot, Map<String, Any>>()

    fun doc(slot: Slot): AccountStore.Doc? = synchronized(docs) { if (slot in docs) docs[slot] else store.read(slot).also { docs[slot] = it } }

    private fun write(slot: Slot, body: JSONObject, source: String, apps: JSONObject? = null): AccountStore.Doc =
        store.write(slot, body, source, now(), apps).also { synchronized(docs) { docs[slot] = it; leafCache.clear() } }

    var local: JSONObject? = doc(Slot.L)?.body; private set
    var dirty = false; private set
    /** The last action's report line, shown under the actions and returned by the debug API. */
    var last: String = ""; private set

    /** #790 S as the Account reads it: the stored server file plus the settings [AccountFleet.derive]
     *  adds from it (the terminals' credentials), derived once per stored file. The file itself is untouched. */
    fun server(): AccountStore.Doc? {
        val d = doc(Slot.S) ?: return null
        serverView?.let { (raw, view) -> if (raw === d) return view }
        return d.copy(body = derived(d.body)!!).also { serverView = d to it }
    }
    @Volatile private var serverView: Pair<AccountStore.Doc, AccountStore.Doc>? = null
    private fun derived(body: JSONObject?) = VaultCockpit.layout.derivations.fold(body) { b, a ->
        AccountFleet.derive(b, a) { id -> AccountFleet.manifest(ctx).apps[id]?.schema ?: 1 }
    }
    fun runtime(): AccountStore.Doc? = doc(Slot.R)
    fun savedLocal(): AccountStore.Doc? = doc(Slot.L)

    /** #867 A slot file was written behind the model's back (AccountData.migrate): drop what it cached. */
    fun invalidate() { synchronized(docs) { docs.clear(); leafCache.clear() }; serverView = null; version.intValue++ }

    private fun changed(line: String) { synchronized(docs) { leafCache.clear() }; last = line; version.intValue++ }

    // ── declaration ──────────────────────────────────────────────────────

    data class Tab(val id: String, val label: String)
    data class FilePair(val id: String, val a: Slot, val b: Slot, val label: String = id)

    val apps: List<AccountDrift.App> by lazy {
        // #783 the cockpit's sections, then every fleet app's `settings › <id>` (one app per id).
        AccountFleet.driftApps(VaultCockpit.layout.sections.map { AccountDrift.App(it.id, it.label, it.vault) },
            AccountFleet.manifest(ctx), AccountFleet.fleetApps().associate { it.id to it.label })
    }

    // ── Connect lands S ──────────────────────────────────────────────────

    /** A Connect way fetched the server file: S is replaced; L starts as S when there is none. */
    fun landServer(body: JSONObject, via: String) {
        write(Slot.S, body, via.ifBlank { "connect" })
        if (local == null) { local = AccountDrift.copy(body); save("= server file") }
        changed("server file fetched through ${via.ifBlank { "connect" }}")
    }

    // ── Profiles ─────────────────────────────────────────────────────────

    /** What Profiles shows: the working L, else S. */
    fun shown(): JSONObject? = local ?: server()?.body

    fun populateFromServer(): String {
        val s = server() ?: return "✗ no server file — fetch it on Connect".also { changed(it) }
        local = AccountDrift.copy(s.body); dirty = true
        return "✓ local declared copy reset to the server file (${s.meta.sha256.take(8)}) — Save to keep".also { changed(it) }
    }

    fun populateFromRuntime(): String {
        val r = runtime() ?: return "✗ no runtime snapshot — Refresh on Runtime".also { changed(it) }
        val observed = AccountRuntime.observed(r.apps)
        val w = AccountDrift.runtimeToDeclared(shown(), AccountDrift.leaves(r.body), observed, AccountRuntime.readOnly(r.apps), observed)
        local = w.body; dirty = true
        return "✓ ${w.written.size} runtime values written into the local copy, ${w.skipped.size} skipped — Save to keep".also { changed(it) }
    }

    /** Edit one field of the working copy (Profiles' row editor, the debug API's test field). */
    fun edit(path: String, value: String): String {
        val l = AccountDrift.copy(shown())
        local = AccountDrift.put(l, path, value); dirty = true
        return "✓ $path edited — Save to keep".also { changed(it) }
    }

    fun save(source: String = "edited on this phone"): String {
        val l = local ?: return "✗ nothing to save".also { changed(it) }
        val d = write(Slot.L, l, source)
        dirty = false
        return "✓ local declared copy saved (${d.meta.sha256.take(8)})".also { changed(it) }
    }

    // ── Runtime ──────────────────────────────────────────────────────────

    /** Read every app and store R. BLOCKS (binder reads): call on IO. */
    fun refreshRuntime(): String {
        val reads = AccountRuntime.read(ctx, shown(), deadlineMs)
        val (body, apps) = AccountRuntime.snapshot(reads)
        write(Slot.R, body, "runtime · ${reads.count { it.status == AccountRuntime.Status.REACHABLE }}/${reads.size} apps", apps)
        return "✓ runtime read from ${reads.size} apps".also { changed(it) }
    }

    // ── Drift ────────────────────────────────────────────────────────────

    fun leavesOf(slot: Slot): Map<String, Any> = synchronized(docs) { leafCache[slot] } ?: when (slot) {
        // #790 L is read through the same derivation as S, so a derived setting is no L↔S drift.
        Slot.L -> AccountDrift.leaves(derived(local ?: savedLocal()?.body))
        Slot.S -> AccountDrift.leaves(server()?.body)
        else -> AccountDrift.leaves(doc(slot)?.body)
    }.also { synchronized(docs) { leafCache[slot] = it } }

    /** One declared pair, field by field; a comparison with R is limited to what R observed. */
    fun diff(p: FilePair): List<AccountDrift.Field> {
        val scope = if (p.a == Slot.R || p.b == Slot.R) AccountRuntime.observed(runtime()?.apps) else null
        return AccountDrift.diff(leavesOf(p.a), leavesOf(p.b), apps, scope)
    }

    fun threeWay(): List<AccountDrift.ThreeWay> =
        AccountDrift.threeWay(leavesOf(Slot.S), leavesOf(Slot.R), leavesOf(Slot.L), AccountRuntime.observed(runtime()?.apps), apps)

    /** SERVER → RUNTIME for [paths] (the S↔R drift of an item, an app, or all). BLOCKS: call on IO. */
    fun pushServerToRuntime(paths: Collection<String>): String {
        val s = server() ?: return "✗ no server file".also { changed(it) }
        val plan = AccountDrift.pushPlan(AccountDrift.leaves(s.body), AccountRuntime.observed(runtime()?.apps), paths, apps)
        if (plan.isEmpty()) return "✗ nothing to push: the server file holds none of those observed fields".also { changed(it) }
        // #783 fleet settings go one import per app (an app restarts after its import); the rest field by field.
        val (fleet, cockpit) = plan.values.flatten().partition { AccountFleet.owns(it.first) }
        val lines = cockpit.map { (p, v) -> AccountRuntime.push(ctx, p, v, s.body) } + AccountFleet.push(ctx, fleet, s.body)
        // #781 this IS the apply now (the per-peer "Your config" Apply is deleted): it lights Connect's step 4.
        if (lines.any { it.startsWith("✓") }) com.diegonmarcos.cloudlib.auth.UserRegistry.markApplied(ctx, now())
        refreshRuntime()
        return lines.joinToString("\n").also { changed(it) }
    }

    /** #573 Runtime ▸ app ▸ Apply all: the declared section [id] applied as a unit (S, else L). */
    fun applySection(id: String): String {
        val section = VaultCockpit.layout.sections.firstOrNull { it.id == id } ?: return "✗ $id: not a cockpit app".also { changed(it) }
        val (_, body) = migrationSource()
        if (body == null) return "✗ no server file and no local copy — fetch it on Connect".also { changed(it) }
        val line = AccountRuntime.applyAll(ctx, section, body)
        if (line.lineSequence().any { it.startsWith("✓") }) com.diegonmarcos.cloudlib.auth.UserRegistry.markApplied(ctx, now())
        refreshRuntime()
        return line.also { changed(it) }
    }

    // ── the new phone (#783) ─────────────────────────────────────────────

    /** The migration source: the server file, else the local copy. */
    private fun migrationSource(): Pair<String, JSONObject?> = server()?.let { "S" to it.body } ?: ("L" to (local ?: savedLocal()?.body))

    /** What "apply all server → runtime" would do per app (dry run). */
    fun migratePlan(): JSONObject {
        val (from, body) = migrationSource()
        return JSONObject().put("source", from).put("steps", AccountFleet.planJson(AccountFleet.plan(ctx, body)))
    }

    /**
     * APPLY ALL SERVER → RUNTIME on a new phone: install every declared app that is missing (the
     * Store's stages), apply each app's declared configuration, then push the cockpit sections.
     * Resumable and idempotent (see [AccountFleet.run]). BLOCKS: call on IO.
     */
    fun migrate(): String {
        val (from, body) = migrationSource()
        if (body == null) return "✗ no server file and no local copy — fetch it on Connect".also { changed(it) }
        val r = AccountFleet.run(ctx, body) {
            refreshRuntime()
            val drifted = AccountDrift.drifted(diff(FilePair("SR", Slot.S, Slot.R))).filterNot(AccountFleet::owns)
            if (drifted.isEmpty() || from != "S") "cockpit in sync" else pushServerToRuntime(drifted)
        }
        refreshRuntime()
        val res = r.optJSONArray("results") ?: JSONArray()
        val lines = (0 until res.length()).map { res.getJSONObject(it) }.filter { it.optString("action") != AccountFleet.NOTHING }
            .map { "${it.optString("id")}: ${it.optString("result")}" }
        return (listOf("migrated from $from · ${lines.size} apps") + lines + r.optString("cockpit")).joinToString("\n").also { changed(it) }
    }

    /** RUNTIME → DECLARED for [paths]: written into L and saved. */
    fun pullRuntimeToLocal(paths: Collection<String>): String {
        val r = runtime() ?: return "✗ no runtime snapshot".also { changed(it) }
        val w = AccountDrift.runtimeToDeclared(shown(), AccountDrift.leaves(r.body), AccountRuntime.observed(r.apps), AccountRuntime.readOnly(r.apps), paths)
        local = w.body
        save("runtime → declared")
        val skipped = w.skipped.entries.groupBy({ it.value }, { it.key }).entries.joinToString("; ") { (why, ps) -> "${ps.size} ${why.name.lowercase()}" }
        return ("✓ ${w.written.size} written into the local copy" + if (skipped.isEmpty()) "" else " · skipped: $skipped").also { changed(it) }
    }

    /** DISCARD L: the local copy is the server file again. */
    fun discardLocal(): String {
        val s = server() ?: return "✗ no server file to fall back to".also { changed(it) }
        local = AccountDrift.copy(s.body)
        save("= server file (local discarded)")
        return "✓ local edits discarded".also { changed(it) }
    }

    /** What an upload commits: the saved L's file, its target and its message. Null when there is no saved L. */
    fun uploadPlan(device: String): Triple<UploadTarget, ByteArray, String>? {
        val l = savedLocal() ?: return null
        val cs = com.diegonmarcos.cloudlib.auth.AuthDeclaration.configSource
        val target = UploadTarget(cs.gitRepo, upload.optString("path"), cs.gitRef)
        val differ = diff(FilePair("LS", Slot.L, Slot.S)).count { it.kind != AccountDrift.Kind.SAME }
        val message = upload.optString("message").replace("{device}", device.ifBlank { "this phone" }).replace("{fields}", differ.toString())
        return Triple(target, (l.json().toString(2) + "\n").toByteArray(), message)
    }

    /** UPLOAD L → SERVER with [token] (used for this request only). BLOCKS: call on IO. */
    fun upload(token: String, device: String): String {
        if (dirty) save()
        val (target, bytes, message) = uploadPlan(device) ?: return "✗ no saved local copy to upload".also { changed(it) }
        // ForgeClient replaced AccountUpload (cloud-account redesign 5.3): same contents API, the
        // GitHub forge (Bearer) on auth.config_source.git.repo; a stale sha is refetched and retried once.
        val client = ForgeClient(ForgeClient.Forge("github", upload.optString("api").substringBefore("/repos/").ifBlank { "https://api.github.com" }, target.repo, ForgeClient.Auth.BEARER), token)
        val sha = when (val g = client.get(target.path, target.branch)) {
            is ForgeClient.Result.Ok -> g.value.sha
            is ForgeClient.Result.Failed -> if (g.status == 404) null else return "✗ upload refused: ${g.reason}".also { changed(it) }
        }
        return when (val r = client.put(target.path, String(bytes), sha, message, target.branch)) {
            is ForgeClient.Result.Ok ->
                "✓ committed ${r.value.take(12)} to ${target.repo}:${target.path}${if (sha == null) " (new file)" else ""}"
            is ForgeClient.Result.Failed -> "✗ upload refused: ${r.reason}"
        }.also { changed(it) }
    }

    /** The diff report: every pair's counts and per-app drift, paths and kinds only — never a value. */
    fun report(): JSONObject {
        val out = JSONObject()
        val files = JSONObject()
        Slot.values().forEach { s -> doc(s)?.let { files.put(s.name, it.meta.json().put("intact", it.intact)) } }
        out.put("files", files).put("local_unsaved", dirty)
        val byPair = JSONObject()
        for (p in pairs()) {
            val fields = diff(p)
            val perApp = JSONObject()
            AccountDrift.byApp(fields, apps).forEach { (app, c) ->
                perApp.put(app.ifBlank { "-" }, c.json().put("fields", JSONArray(
                    fields.filter { it.app == app && it.kind != AccountDrift.Kind.SAME }
                        .map { JSONObject().put("path", it.path).put("kind", it.kind.name.lowercase()) })))
            }
            byPair.put(p.id, AccountDrift.counts(fields).json().put("apps", perApp))
        }
        out.put("pairs", byPair)
        out.put("three_way", JSONObject().apply {
            threeWay().groupBy { it.state }.forEach { (k, v) -> put(k.name.lowercase(), v.size) }
        })
        return out
    }

    /** Where an upload commits: repo, path, branch (the GitHub forge; the token is never part of it). */
    data class UploadTarget(val repo: String, val path: String, val branch: String)

    companion object {
        /**
         * #802 THE landing of a decrypted vault export, shared by the UI's import/connect
         * ([ConnectWays.land]) and `/api/account/import`: the schema gate, the Imported
         * handle, the owner's peers, then S through [landServer]. Returns the unknown
         * `schema_version` (nothing landed), or null once S holds the file.
         */
        fun landBundle(ctx: Context?, body: JSONObject, via: String): Int? {
            com.diegonmarcos.cloudlib.auth.VaultConnect.unknownSchemaVersion(body, com.diegonmarcos.cloudlib.auth.VaultConnect.knownSchemaVersions)?.let { return it }
            val imported = com.diegonmarcos.cloudlib.auth.VaultConnect.Imported
            imported.last = com.diegonmarcos.cloudlib.auth.VaultConnect.sections(body)
            val bundle = body.optJSONObject("bundle") ?: body
            imported.bundle = bundle
            imported.via = via
            ctx ?: return null
            // #766 the vault names the owner's peers too: who / which device answer on every line.
            com.diegonmarcos.cloudlib.auth.UserRegistry.fromVault(bundle)?.let { com.diegonmarcos.cloudlib.auth.UserRegistry.adopt(ctx, it) }
            // #778 the fetch IS the server file S, stored with its source; L starts as S when there is none.
            get(ctx).landServer(bundle, via)
            return null
        }

        private fun decoded(b64: String): String =
            String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP))

        /** build.json::ui.profile.tabs — the strip, in order. */
        fun tabs(): List<Tab> = runCatching {
            val a = JSONArray(decoded(BuildConfig.UI_PROFILE_TABS_B64))
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Tab(o.getString("id"), o.optString("label", o.getString("id"))) } }
        }.getOrDefault(emptyList())

        private val drift: JSONObject by lazy { runCatching { JSONObject(decoded(BuildConfig.UI_PROFILE_DRIFT_B64)) }.getOrDefault(JSONObject()) }
        val upload: JSONObject get() = drift.optJSONObject("upload") ?: JSONObject()
        val deadlineMs: Long get() = drift.optLong("runtime_deadline_ms", 4000)

        /** build.json::ui.profile.drift.pairs — which files Drift compares, in order. */
        fun pairs(): List<FilePair> = runCatching {
            val a = drift.getJSONArray("pairs")
            (0 until a.length()).map { a.getJSONObject(it).let { o ->
                FilePair(o.getString("id"), Slot.valueOf(o.getString("a")), Slot.valueOf(o.getString("b")), o.optString("label").ifBlank { o.getString("id") })
            } }
        }.getOrDefault(emptyList())

        /** The host gh signs in to for the Connect ▸ GitHub ▸ WebAuth way (its declared git_chain rung). */
        fun ghHost(): String? = runCatching {
            val lines = JSONObject(decoded(BuildConfig.UI_PROFILE_CONNECT_B64)).getJSONArray("lines")
            // #1693 the owner's engines.gh names its rung; the connect lines' old gh_auth_login way is the fallback.
            val rung = BuildConfig.GH_ENGINE_RUNG.ifBlank { null } ?: (0 until lines.length()).flatMap { i ->
                val w = lines.getJSONObject(i).optJSONArray("ways") ?: JSONArray()
                (0 until w.length()).map { w.getJSONObject(it) }
            }.firstOrNull { it.optString("kind") == "gh_auth_login" }?.optString("rung")
            com.diegonmarcos.cloudlib.auth.AuthDeclaration.gitChain.firstOrNull { it.id == rung }?.config?.optString("host")?.ifBlank { null }
        }.getOrNull()

        fun now(): String = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).format(java.util.Date())

        @Volatile private var instance: AccountModel? = null

        /** The JVM suite's seam: the Android Keystore behind [EncryptedIo] does not exist under Robolectric, so a test installs a model over [AccountStore.PlainIo]. null puts the phone's model back. */
        fun install(model: AccountModel?) { instance = model }

        /** The process's one model, its files in an EncryptedFile per slot under filesDir/account. */
        fun get(ctx: Context): AccountModel = instance ?: synchronized(this) {
            instance ?: AccountModel(ctx.applicationContext, AccountStore(File(ctx.applicationContext.filesDir, "account"), EncryptedIo(ctx.applicationContext))).also {
                // #783 what the fleet manifest classes secret is masked on every Account surface.
                val m = AccountFleet.manifest(ctx)
                InfoMask.secretPath = { p -> AccountFleet.owns(p) && AccountFleet.isSecret(m, p) }
                instance = it
            }
        }
    }

    /** The phone's [AccountStore.Io]: one EncryptedFile per slot (an EncryptedFile is never overwritten in place). */
    class EncryptedIo(private val ctx: Context) : AccountStore.Io {
        private val key by lazy { MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }
        private fun enc(f: File) = EncryptedFile.Builder(ctx, f, key, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
        override fun read(file: File): ByteArray? = if (!file.isFile) null else enc(file).openFileInput().use { it.readBytes() }
        override fun write(file: File, bytes: ByteArray) {
            file.parentFile?.mkdirs()
            file.delete()
            enc(file).openFileOutput().use { it.write(bytes) }
        }
    }
}
