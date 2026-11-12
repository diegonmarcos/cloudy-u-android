package com.diegonmarcos.superapp.profile

import android.content.Context
import com.diegonmarcos.cloudlib.auth.AuthDeclaration
import com.diegonmarcos.superapp.core.ConfigSyncClient
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.cloudlib.auth.SignInResult
import com.diegonmarcos.cloudlib.auth.UserRegistry
import com.diegonmarcos.cloudlib.auth.VaultConnect
import com.diegonmarcos.cloudlib.auth.VaultFile
import com.diegonmarcos.superapp.account.BuildConfig
import com.diegonmarcos.superapp.account.R
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.json.JSONObject

/**
 * Cloud Account redesign task 3 (spec 4.2): the sign-in logic of the deleted ProfileFragment,
 * as a plain object. Account ▸ connect draws one pill per declared way
 * (build.json::ui.account.forges[].ways + ui.account.connect.file) and dispatches on the
 * way's `kind` alone ([handler]). Every function here is blocking (call it on IO) and returns
 * one [Outcome] line; a token or key is never put in a line, a log or a URL.
 *
 * Every successful read lands through [land] (AccountModel.landBundle: schema gate, Imported,
 * registry, S), and every attempt is recorded under Connections `fetch.last`.
 */
object ConnectWays {
    const val GH_AUTH_LOGIN = "gh_auth_login"
    const val GITHUB_PAT = "github_pat"
    const val GITHUB_SSH = "github_ssh"
    const val GITEA_TOKEN = "gitea_token"
    const val AUTHELIA_WEB = "authelia_web"
    const val VAULT_FILE = "vault_file"

    data class Way(val kind: String, val label: String, val forge: String?, val note: String = "")
    data class Outcome(val ok: Boolean, val line: String)

    sealed class Handler {
        object GhLogin : Handler()
        object Pat : Handler()
        object Ssh : Handler()
        object GiteaToken : Handler()
        object AutheliaWeb : Handler()
        object File : Handler()
        data class Unwired(val why: String) : Handler()
    }

    /** THE dispatch: a way's kind → what its pill does. Nothing here reads a label. */
    fun handler(kind: String): Handler = when (kind) {
        GH_AUTH_LOGIN -> Handler.GhLogin
        GITHUB_PAT -> Handler.Pat
        GITHUB_SSH -> Handler.Ssh
        GITEA_TOKEN -> Handler.GiteaToken
        AUTHELIA_WEB -> Handler.AutheliaWeb
        VAULT_FILE -> Handler.File
        else -> Handler.Unwired("✗ the way '$kind' has no handler in this build")
    }

    private val LABELS = mapOf(
        GH_AUTH_LOGIN to "gh WebAuth",
        GITHUB_PAT to "PAT",
        GITHUB_SSH to "SSH key",
        GITEA_TOKEN to "Token",
        AUTHELIA_WEB to "Authelia WebAuth",
        VAULT_FILE to "Import file",
    )
    private val NOTES = mapOf(
        GITHUB_SSH to "read only: a shallow clone (JGit has no push); backups need a token",
        AUTHELIA_WEB to "private Gitea repos need the Gitea-to-Authelia user mapping; the Token way works today",
        VAULT_FILE to "offline: the decrypted vault export, never the sops file",
    )

    fun raw(): JSONObject = runCatching {
        JSONObject(String(android.util.Base64.decode(BuildConfig.UI_ACCOUNT_B64, android.util.Base64.NO_WRAP)))
    }.getOrDefault(JSONObject())

    fun decl(): ForgeClient.Decl = ForgeClient.Decl.fromBuildConfig(BuildConfig.UI_ACCOUNT_B64)

    /** Every declared way, forge by forge, then the file way (ui.account.connect.file). */
    fun ways(): List<Way> {
        val out = mutableListOf<Way>()
        for (f in decl().forges) for (k in f.ways) out += Way(k, LABELS[k] ?: k, f.id, NOTES[k].orEmpty())
        raw().optJSONObject("connect")?.optJSONObject("file")?.let { f ->
            val k = f.optString("kind", VAULT_FILE)
            out += Way(k, f.optString("label").ifBlank { LABELS[k] ?: k }, null, NOTES[k].orEmpty())
        }
        return out
    }

    private fun fail(line: String) = Outcome(false, if (line.startsWith("✗")) line else "✗ $line")

    /** Connections `fetch.last` = {at, ok, line}: what the connect page's last-fetch line reads. */
    private fun record(ctx: Context, o: Outcome): Outcome {
        runCatching {
            AccountVault(ctx).putConnection("fetch.last", JSONObject().put("at", AccountModel.now())
                .put("ok", o.ok).put("line", o.line).toString())
        }
        return o
    }

    fun lastFetch(ctx: Context): JSONObject? =
        (AccountVault(ctx).connection("fetch.last") as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** ONE landing for every way: the schema gate, Imported, the registry, then S. */
    fun land(ctx: Context, body: JSONObject, via: String): Outcome {
        AccountModel.landBundle(ctx, body, via)?.let { v ->
            return record(ctx, fail(ctx.getString(R.string.vault_connect_schema_unknown, v,
                VaultConnect.knownSchemaVersions.sorted().joinToString(", "))))
        }
        val sections = VaultConnect.Imported.last.orEmpty()
        return record(ctx, Outcome(true, "✓ vault fetched via $via: ${sections.sumOf { it.rows.size }} values in ${sections.size} topics"))
    }

    /** The secrets file over a forge's contents API with [token]; the token is filed only after it worked. */
    fun fetchWith(ctx: Context, forgeId: String, token: String, via: String, file: Boolean): Outcome {
        val d = decl()
        val f = d.forge(forgeId) ?: return record(ctx, fail("forge '$forgeId' is not declared"))
        if (f.repo == null) return record(ctx, fail("forge '$forgeId' declares no vault repo yet"))
        return when (val g = ForgeClient(f, token).get(d.secretsFile, d.branch)) {
            is ForgeClient.Result.Ok -> {
                val body = runCatching { JSONObject(g.value.text) }.getOrNull()
                    ?: return record(ctx, fail("${d.secretsFile} on $forgeId is not JSON (${g.value.text.length} bytes read)"))
                val o = land(ctx, body, via)
                if (o.ok && file) AccountVault(ctx).putConnection("forge.${f.id}.token", token)
                o
            }
            is ForgeClient.Result.Failed -> record(ctx, fail("$forgeId ${g.status}: ${g.reason}"))
        }
    }

    /** Fetch now: the primary forge (Connections forge.primary, else the first with a repo). */
    fun fetchNow(ctx: Context): Outcome {
        val v = DeviceVault(ctx)
        val f = v.primary() ?: return record(ctx, fail("no usable forge declared (ui.account.forges with a repo)"))
        val c = v.client(f.id) ?: return record(ctx, fail("forge '${f.id}' unavailable"))
        return when (val g = c.get(v.decl.secretsFile, v.decl.branch)) {
            is ForgeClient.Result.Ok -> runCatching { JSONObject(g.value.text) }.getOrNull()
                ?.let { land(ctx, it, "fetch:${f.id}") } ?: record(ctx, fail("${v.decl.secretsFile} is not JSON (${g.value.text.length} bytes read)"))
            is ForgeClient.Result.Failed -> record(ctx, fail("${f.id} ${g.status}: ${g.reason}"))
        }
    }

    /** GitHub ▸ gh WebAuth: gh's own `auth login` in the gh engine, then one read with the token gh holds. */
    fun ghLogin(ctx: Context, onPrompt: (code: String, page: String?) -> Unit): Outcome {
        // #1693 only Cloud Account starts a sign-in; any other host is sent there instead.
        if (!SignInOwner.isOwner(ctx)) return Outcome(false, "✗ " + (SignInOwner.open(ctx, "github") ?: "opened Cloud Account to sign in to GitHub"))
        val engine = GhEngine(ctx)
        val host = AccountModel.ghHost().orEmpty()
        when (val c = engine.check()) {
            is GhEngine.Check.NotInstalled -> return record(ctx, fail(ctx.getString(R.string.connect_gh_missing, c.pkg)))
            is GhEngine.Check.TooOld -> return record(ctx, fail(ctx.getString(R.string.connect_gh_old, c.pkg, c.found, c.needed)))
            GhEngine.Check.Ready -> if (host.isBlank()) return record(ctx, fail("no gh host declared (engines.gh)"))
        }
        val token = engine.token(host) ?: run {
            val r = engine.login(host) { code, page -> onPrompt(code, GhEngine.pageOnHost(page, host)) }
            if (!r.ok) return record(ctx, fail(ctx.getString(R.string.connect_gh_failed, r.output.trim().lines().lastOrNull().orEmpty())))
            engine.token(host) ?: return record(ctx, fail(ctx.getString(R.string.connect_gh_no_token, host)))
        }
        // gh keeps its own token; DeviceVault reads it from the engine when no forge token is filed.
        return fetchWith(ctx, "github", token, GH_AUTH_LOGIN, file = false)
    }

    /** gh's prompt: the code copied (flagged sensitive), the page opened in the fleet browser. Main thread. */
    fun ghPrompt(ctx: Context, code: String, page: String?): String {
        if (code.isNotBlank()) copySensitive(ctx, ctx.getString(R.string.connect_gh_code_clip), code)
        val line = ctx.getString(R.string.connect_gh_prompt, code.ifBlank { "…" }, page ?: "…")
        page ?: return line
        val browser = AuthDeclaration.browserMission?.pkg.orEmpty()
        return runCatching {
            require(browser.isNotBlank())
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(page))
                .setPackage(browser).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            line
        }.getOrElse { "✗ " + ctx.getString(R.string.connect_gh_no_browser, browser.ifBlank { "—" }, page) }
    }

    /** GitHub ▸ PAT: one read; the token is filed at forge.github.token only once it worked. */
    fun pat(ctx: Context, token: String): Outcome =
        if (token.isBlank()) fail(ctx.getString(R.string.connect_pat_empty)) else fetchWith(ctx, "github", token.trim(), GITHUB_PAT, file = true)

    /** GitHub ▸ SSH key: a shallow bare clone, one file read, deleted. READ ONLY — no push over SSH. */
    fun ssh(ctx: Context, keyText: String, passphrase: String): Outcome {
        val key = extractSshKey(keyText)
        if (key.isBlank()) return fail("paste or import a private key first")
        return when (val o = GitSshVault.fetchArtifact(ctx.cacheDir, key, passphrase, decl().secretsFile)) {
            is ConfigSyncClient.Outcome.Ok -> land(ctx, o.body, GITHUB_SSH)
            is ConfigSyncClient.Outcome.Failed -> record(ctx, fail("${o.kind}: ${o.message}"))
        }
    }

    /** Gitea ▸ Token: filed at forge.gitea.token; read through it when the Gitea vault repo is declared. */
    fun giteaToken(ctx: Context, token: String): Outcome {
        if (token.isBlank()) return fail("paste a Gitea token first")
        val f = decl().forge("gitea") ?: return fail("forge 'gitea' is not declared")
        if (f.repo == null) {
            AccountVault(ctx).putConnection("forge.gitea.token", token.trim())
            return record(ctx, Outcome(true, "✓ Gitea token filed · no vault repo is declared on Gitea yet (ui.account.forges[gitea].repo), so nothing was read"))
        }
        return fetchWith(ctx, "gitea", token.trim(), GITEA_TOKEN, file = true)
    }

    /** Import file: classified first (sops/ENC and unknown schemas refused), then landed. */
    fun importFile(ctx: Context, text: String): Outcome = when (val v = AccountHost.classify(text)) {
        is VaultFile.Verdict.Bundle -> land(ctx, v.bundle, VAULT_FILE)
        else -> record(ctx, fail(AccountHost.refusal(ctx, v).orEmpty().ifBlank { "not a decrypted vault export" }))
    }

    /** Authelia ▸ WebAuth (libs:auth SignInWays): the registry, the session, a bearer stored WITH its address. */
    fun signedIn(ctx: Context, result: SignInResult): Outcome {
        val app = ctx.applicationContext
        result.artifact?.let { UserRegistry.remember(app, it) }
        val who = result.identity.ifBlank { UserRegistry.Current.registry?.primaryIdentity?.email.orEmpty() }
        SignIn.Current.session = SignIn.Session(result.provider.id, who)
        val stored = if (result.bearer.isNotBlank()) ConfigsPrefs(app).setAutheliaCredential(who, result.bearer) else null
        return record(ctx, if (stored != null) fail(stored) else Outcome(true, "✓ signed in through ${result.provider.label}${if (who.isNotBlank()) " as $who" else ""}"))
    }

    /**
     * The address a stored Authelia token without one can be linked to: the registry's primary identity, else the
     * contact card's email; null when no token is stranded or no address is known. A token is never deleted for
     * lacking an address (ConfigsPrefs.hasOrphanToken); linking it is the owner's explicit tap.
     */
    fun orphanAddress(ctx: Context): String? {
        val app = ctx.applicationContext
        if (!ConfigsPrefs(app).hasOrphanToken()) return null
        return UserRegistry.Current.registry?.primaryIdentity?.email.orEmpty().ifBlank { ProfilePrefs(app).email.trim() }.ifBlank { null }
    }

    /** Link the stranded token to [address]; the store's own check of the address answers (null = linked). */
    fun linkOrphan(ctx: Context, address: String): Outcome =
        ConfigsPrefs(ctx.applicationContext).adoptOrphanToken(address.trim())?.let { fail(it) }
            ?: Outcome(true, "✓ the stored token is linked to ${address.trim()}")

    /** A raw PEM, or `ssh.vault_repo_key` out of an exported config JSON. */
    fun extractSshKey(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching {
            val o = JSONObject(trimmed)
            o.optJSONObject("ssh")?.optString("vault_repo_key").orEmpty()
                .ifBlank { o.optString("vault_repo_key") }
                .ifBlank { trimmed }
                .replace("\\n", "\n")
        }.getOrDefault(trimmed)
    }

    private fun copySensitive(ctx: Context, label: String, text: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val item = android.content.ClipData.newPlainText(label, text)
        if (android.os.Build.VERSION.SDK_INT >= 33) item.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        clip.setPrimaryClip(item)
    }
}
