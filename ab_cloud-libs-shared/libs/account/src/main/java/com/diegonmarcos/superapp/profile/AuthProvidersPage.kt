package com.diegonmarcos.superapp.profile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudlib.auth.ProviderStatus
import com.diegonmarcos.cloudlib.auth.ProviderStatus.State
import com.diegonmarcos.cloudlib.auth.SignIn
import com.diegonmarcos.cloudlib.auth.SignInHost
import com.diegonmarcos.cloudlib.auth.SignInResult
import com.diegonmarcos.cloudlib.auth.SignInWays
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #1083 Connect ▸ Authentication providers ▸ Providers: the four sign-in providers of the fleet, one row each
 * with its live status and a Sign in action. Authelia is the hub: the edge accepts an Authelia session, the
 * auth broker's session (Google, GitHub) or a Cloudflare Access token wherever Authelia protects a route.
 *
 * Everything the page names (the families, the clients, the URLs) is `auth.sign_in.providers` in
 * ab_cloud-libs-shared/build.json, read through [SignIn] and [ProviderStatus]; no client secret exists on the
 * phone. Status is measured: a probe of each provider's declared `probe_url`, whether a bearer or a recorded
 * sign-in identity is on this phone, whether a VPN transport (WARP or the mesh tunnel) is active.
 */
object AuthProvidersTags {
    const val PAGE = "auth:providers"
    const val REFRESH = "auth:providers:refresh"
    fun row(family: String) = "auth:row:$family"
    fun action(family: String, what: String) = "auth:action:$family:$what"
}

/**
 * The identities a sign-in on this phone proved, per provider family. Identity only: never a token or a secret.
 * It rides the account's existing encrypted config store ([ConfigsPrefs.text]), so no new store exists to declare.
 */
class AuthProviderLog(private val read: (String) -> String, private val write: (String, String) -> Unit) {
    constructor(ctx: Context) : this({ k -> ConfigsPrefs(ctx).text(k) }, { k, v -> ConfigsPrefs(ctx).putText(k, v) })
    private fun key(family: String) = "auth_identity.$family"
    fun get(family: String): String = runCatching { read(key(family)) }.getOrDefault("")
    fun all(families: Collection<String>): Map<String, String> = families.associateWith { get(it) }.filterValues { it.isNotBlank() }
    fun record(family: String, identity: String) { if (identity.isNotBlank()) runCatching { write(key(family), identity) } }
    fun forget(family: String) { runCatching { write(key(family), "") } }
}

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

private fun vpnActive(ctx: Context): Boolean = runCatching {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
}.getOrDefault(false)

private fun stateOf(s: State): Pair<String, KitState> = when (s) {
    State.SIGNED_IN -> "signed in" to KitState.OK
    State.SIGNED_OUT -> "signed out" to KitState.IDLE
    State.UNREACHABLE -> "unreachable" to KitState.BAD
    State.DISABLED -> "off" to KitState.WARN
    State.NOT_CONFIGURED -> "not configured" to KitState.WARN
}

/** Opens [url] in the browser; false when nothing on the phone can show it. */
private fun openUrl(ctx: Context, url: String): Boolean = runCatching {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    val a = ctx.activityOrNull()
    if (a != null) a.startActivity(i) else ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)

@Composable
fun AuthProvidersPage() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var said by remember { mutableStateOf("") }
    val providers = remember { SignIn.providers }
    val families = remember { ProviderStatus.families(providers) }
    val probes = remember { mutableStateMapOf<String, ProviderStatus.Probe>() }
    val log = remember { AuthProviderLog(ctx) }
    val bearer = remember(tick) { runCatching { ConfigsPrefs(ctx).autheliaToken.isNotBlank() }.getOrDefault(false) }
    val signals = ProviderStatus.Signals(
        autheliaBearer = bearer,
        signedIn = remember(tick) { log.all(families.keys) },
        probes = probes.toMap(),
        vpnActive = remember(tick) { vpnActive(ctx) },
    )
    LaunchedEffect(tick) {
        probes.clear()
        val found = withContext(Dispatchers.IO) { families.mapValues { (_, ways) -> ProviderStatus.probe(ways.first().probeUrl) } }
        probes.putAll(found)
    }
    val host = remember {
        object : SignInHost {
            override fun onSignedIn(result: SignInResult) {
                said = ConnectWays.signedIn(ctx, result).line
                if (result.provider.kind != SignIn.Kind.AUTHELIA_BEARER && result.provider.kind != SignIn.Kind.AUTHELIA_WEB) {
                    log.record(result.provider.familyId, result.identity)
                }
                tick++
            }
        }
    }
    val rows = ProviderStatus.rows(providers, signals)
    // #1693 only Cloud Account starts a sign-in: any other host draws the same rows as links to Cloud Account's page.
    if (!SignInOwner.isOwner(ctx)) { SignInLinkRows(rows.map { it.family to it.label }); return }
    val signedCount = rows.count { it.state == State.SIGNED_IN }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp).testTag(AuthProvidersTags.PAGE),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KitStatusBanner(
            "Authelia is the hub: Google, GitHub and Cloudflare sessions are accepted wherever Authelia protects a route · $signedCount of ${rows.size} signed in",
            if (signedCount > 0) KitState.OK else KitState.IDLE, tag = "auth:providers:hub")
        KitActionBar(listOf(KitAction("Refresh", AuthProvidersTags.REFRESH) { tick++ }), filledFirst = false)
        if (said.isNotBlank()) KitStatusBanner(said, if (said.startsWith("✓")) KitState.OK else KitState.WARN, tag = "auth:providers:said")
        GroupTitle("Providers · ${rows.size}")
        for (r in rows) {
            Tile(r.label, tag = AuthProvidersTags.row(r.family), pill = stateOf(r.state), secondary = r.detail)
            val actions = mutableListOf<KitAction>()
            val first = r.ways.first()
            when (r.family) {
                "authelia" -> Unit   // the shared pills below: bearer and portal login
                "google" -> actions += KitAction("Sign in", AuthProvidersTags.action(r.family, "signin"), first.startUrl.isNotBlank()) {
                    said = if (openUrl(ctx, first.startUrl)) "Google sign-in opened in the browser; finish there, then Refresh." else "no browser can open the sign-in page"
                }
                "github" -> actions += KitAction("Sign in with the browser", AuthProvidersTags.action(r.family, "web"), first.startUrl.isNotBlank()) {
                    said = if (openUrl(ctx, first.startUrl)) "GitHub sign-in opened in the browser; finish there, then Refresh." else "no browser can open the sign-in page"
                }
                "cloudflare" -> {
                    actions += KitAction("Sign in", AuthProvidersTags.action(r.family, "signin"), first.configured) {
                        // The broker's Cloudflare One flow (same as the chooser button); the team login page is the fallback.
                        said = if (openUrl(ctx, first.startUrl.ifBlank { first.loginUrl })) "Cloudflare One sign-in opened in the browser; finish there, then Refresh." else "no browser can open the sign-in page"
                    }
                    if (first.route.isNotBlank()) actions += KitAction("WARP / One", AuthProvidersTags.action(r.family, "route")) {
                        val a = ctx.activityOrNull()
                        said = if (a != null && AccountHost.route(a, first.route)) "" else "cannot open ${first.route.substringAfter(':')} from here"
                    }
                }
            }
            if (log.get(r.family).isNotBlank()) actions += KitAction("Sign out", AuthProvidersTags.action(r.family, "signout")) { log.forget(r.family); tick++ }
            if (actions.isNotEmpty()) KitActionBar(actions, filledFirst = false)
            // The in-app device grant (GitHub) and the Authelia ways ride the shared sign-in pills, one code path with Connect ▸ Sign in.
            val ids = r.ways.filter { it.kind == SignIn.Kind.AUTHELIA_BEARER || it.kind == SignIn.Kind.AUTHELIA_WEB || (r.family == "github" && it.kind == SignIn.Kind.DEVICE_FLOW) }.map { it.id }
            if (ids.isNotEmpty()) SignInWays(host = host, policy = ids, pill = { label, tag, onClick ->
                FilledTonalButton(onClick = onClick, modifier = Modifier.testTag(tag)) { Text(label) }
            })
        }
    }
}


/** #1693 a non-owner host's provider rows: each one the same Tile, its state from Cloud Account, a tap opening Cloud Account there. */
@Composable
private fun SignInLinkRows(families: List<Pair<String, String>>) {
    val ctx = LocalContext.current
    var said by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val status = remember { mutableStateMapOf<String, com.diegonmarcos.superapp.fleetendpoints.SignInContract.Status>() }
    LaunchedEffect(tick) {
        status.putAll(withContext(Dispatchers.IO) { families.associate { (f, _) -> f to SignInOwner.status(ctx, f) } })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp).testTag(AuthProvidersTags.PAGE),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KitStatusBanner("Sign-ins happen in Cloud Account; this app reads their state from it", KitState.IDLE, tag = "auth:providers:hub")
        KitActionBar(listOf(KitAction("Refresh", AuthProvidersTags.REFRESH) { tick++ }), filledFirst = false)
        if (said.isNotBlank()) KitStatusBanner(said, KitState.WARN, tag = "auth:providers:said")
        GroupTitle("Providers · ${families.size}")
        for ((family, label) in families) {
            val s = status[family]
            Tile(label, tag = AuthProvidersTags.row(family), pill = if (s?.signedIn == true) stateOf(State.SIGNED_IN) else null,
                secondary = s?.line ?: "reading Cloud Account…")
            KitActionBar(listOf(KitAction("Open in Cloud Account", AuthProvidersTags.action(family, "cloud-account")) {
                said = SignInOwner.open(ctx, family).orEmpty()
            }), filledFirst = false)
        }
    }
}
