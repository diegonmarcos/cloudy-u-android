package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.fleetconfig.BuiltFrom
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.updater.BootstrapInstall
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.FleetIdentity
import com.diegonmarcos.superapp.updater.VersionOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tap a Constellation row's "Details" action → this sheet. Two stacked
 * sections, INSTALLED then AVAILABLE, same fields in the same order, so the
 * one line that actually matters — installed versionCode next to available
 * versionCode — sits where a human can compare them without doing the
 * arithmetic in their head. That was the whole ask behind #496: the
 * cloud-notes incident this ticket is named for was a REFUSED downgrade that
 * showed up as a wall of prose in logcat instead of two numbers on screen.
 *
 * A READER, not a second implementation. Every fact on this sheet comes
 * straight out of [Fleet] / [VersionOrder] / ApkIntegrity (via [Fleet]'s
 * public wrappers — ApkIntegrity itself stays internal to libs:updater).
 * Nothing here recomputes a version ordering or an install decision; the one
 * comparison performed here ([Drift]) is the same function [Fleet.commit]
 * calls for the real gate — this sheet only renders its answer earlier, for
 * a candidate it fetched itself to find out.
 */
object ApkDetailSheet {

    const val TAG = "apk-detail"

    private val cUpd = 0xFFED8936.toInt(); private val cBad = 0xFFF56565.toInt()
    private val cDim = 0xFF9B93AB.toInt(); private val cText = 0xFFECE6F5.toInt()
    private val cLink = 0xFFB794F6.toInt()

    /** What the release-asset probe found (the HEAD probe, the sha256 sidecar, the .source commit). */
    private class Release(val asset: Fleet.ReleaseAsset?, val sha256: String?, val commit: String?)

    /** The "Check exact version" run: fetching, failed, or the candidate's versionCode. */
    private sealed class Verify {
        object Fetching : Verify()
        class Failed(val why: String?) : Verify()
        class Got(val versionCode: Long) : Verify()
    }

    /**
     * The sheet for [app]. [state] is what the last Check pass found (no new network call to open the
     * sheet). A lib row's own Details opens through [onOpenOther], which the host stacks on top.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Sheet(app: Fleet.App, state: Fleet.State?, onDismiss: () -> Unit, onOpenOther: (Fleet.App) -> Unit) {
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        // INSTALLED renders instantly — one local PackageManager read, no network.
        val installed = remember(app.id) { Fleet.installedDetails(ctx, app) }
        // AVAILABLE starts with only what [state] already carries, then fills in the release-asset facts
        // (HEAD probe + sha256 sidecar) once they land.
        var release by remember(app.id) { mutableStateOf<Release?>(null) }
        LaunchedEffect(app.id) {
            if (app.releaseUrl.isNotBlank()) release = withContext(Dispatchers.IO) {
                Release(runCatching { Fleet.releaseAsset(app) }.getOrNull(), runCatching { Fleet.releaseSha256(app) }.getOrNull(),
                    runCatching { BuiltFrom.fetchReleaseCommit(app.abiReleaseUrl) }.getOrNull())
            }
        }
        var verify by remember(app.id) { mutableStateOf<Verify?>(null) }
        /**
         * Fetch the real candidate through the SAME verified pipeline [Fleet.install] uses
         * ([Fleet.download]: source ladder + sha/length verification), read its manifest via
         * [Fleet.candidateIdentity], then ask [VersionOrder] — once — whether that is a downgrade.
         */
        val verifyExact: () -> Unit = {
            verify = Verify.Fetching
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { Fleet.candidateIdentity(ctx, Fleet.download(ctx, app).file) }
                }
                val identity = result.getOrNull()
                verify = if (identity == null) Verify.Failed(result.exceptionOrNull()?.message) else Verify.Got(identity.versionCode)
            }
        }

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SHEET_BG.ink(),
            modifier = Modifier.testTag(TAG),
        ) {
            Header(app)
            Column(Modifier.fillMaxWidth().height(sdp(StoreDensity.SHEET)).verticalScroll(rememberScrollState())) {
                InstalledCard(installed)
                AvailableCard(app, state, installed, release, verify, verifyExact)

                // Direct install + App settings, in the order and wording appstore-fleet-actions.json
                // declares. App settings only for an INSTALLED package: Android's page for an absent
                // package shows nothing, so the button is not drawn at all.
                Card {
                    BlockTitle("Actions")
                    for (a in FleetActions.details(ctx)) {
                        if (a.id == "app_settings" && Fleet.installedId(ctx, app) == null) continue
                        Action(a.label, a.detail, "$TAG:action:${a.id}") { detailAction(ctx, scope, app, a) }
                    }
                }

                // #678 the libs this app uses — its own build.json declares them
                // (Fleet.App.libs / engineLibs, carried by regen.sh). Each names the
                // lib's own fleet row and opens that row's Details.
                Card { LibsBody(app, onOpenOther) }

                Note("INSTALLED is read straight from PackageManager. AVAILABLE's size/timestamp/sha256 " +
                    "come from one HEAD probe of the release asset. The available build's exact " +
                    "versionCode is not knowable without fetching it — \"Check exact version\" below " +
                    "does that fetch (the same verified download Install would do) and compares it " +
                    "through VersionOrder, the one comparator Fleet.commit's downgrade gate also uses.")
            }
        }
    }

    // ── ACTIONS ──────────────────────────────────────────────────────────

    /** One declared Details action. An id with no handler says so on tap. */
    private fun detailAction(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, app: Fleet.App, a: FleetActions.Action) {
        when (a.id) {
            "direct_install" -> directInstall(ctx, scope, app)
            "app_settings" -> {
                val pkg = Fleet.installedId(ctx, app)
                if (pkg == null) toast(ctx, ctx.getString(R.string.store_fleet_not_installed, app.label))
                else runCatching { ctx.startActivity(PhoneAppActions.appInfo(pkg)) }
                    .onFailure { toast(ctx, "${a.label}: ${it.message}") }
            }
            else -> toast(ctx, "'${a.id}' is declared in ${FleetActions.DATASET} but this build has no handler for it")
        }
    }

    /**
     * THE FLOOR: fetch through the ordinary verified source ladder and hand the
     * result to the SYSTEM package installer. Needs no privileged shell channel,
     * which is the point — a device that lost its channel can still install the
     * build that gives it back. Kept apart from Install / Update because it
     * always shows Android's confirmation sheet; a silent fallback inside that
     * button would be indistinguishable from the silent path never working.
     */
    private fun directInstall(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, app: Fleet.App) {
        if (app.blocked) return toast(ctx, "${app.label}: not published yet — nothing to install")
        toast(ctx, "Fetching ${app.label}…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { BootstrapInstall.launch(ctx, app) }
            r.onSuccess { c -> toast(ctx, "${app.label}: ${c.versionName ?: c.versionCode}\n${c.evidence}") }
                .onFailure { toast(ctx, "${app.label}: ${it.message}") }
        }
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    // ── LIBS ─────────────────────────────────────────────────────────────

    /** Linked libs (compiled in: nothing to install for this app) and bound
     *  engines (installed separately), each resolved to its own fleet row. */
    @Composable
    private fun ColumnScope.LibsBody(app: Fleet.App, onOpenOther: (Fleet.App) -> Unit) {
        BlockTitle("Libs")
        val fleet = remember { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64).associateBy { it.id } }
        @Composable
        fun group(title: String, ids: List<String>, how: String) {
            Note("$title — $how", Modifier)
            if (ids.isEmpty()) { Note("none declared in ${app.label}'s build.json", Modifier); return }
            for (id in ids) {
                val row = fleet[id]
                if (row == null) Kv("cloud-$id", "no Store row for $id")
                else Action(row.label, "its Store row's Details", "$TAG:lib:$id") { onOpenOther(row) }
            }
        }
        group("Compiled in", app.libs, "linked into this APK, nothing to install for it")
        group("Installed separately", app.engineLibs, "bound at runtime, its own APK must be installed")
    }

    // ── INSTALLED ────────────────────────────────────────────────────────

    @Composable
    private fun InstalledCard(d: Fleet.InstalledDetails?) = Card {
        BlockTitle("Installed")
        if (d == null) {
            Kv("Status", "not installed on this device")
            return@Card
        }
        Kv("Package", d.pkg)
        // #631: the ONE identity pattern's version part, so this sheet cannot
        // spell a version differently from the row that opened it — and so the
        // wall-clock versionCode is labelled `apk build` here too.
        Kv("Version", FleetIdentity.version(d.versionName, 0L, d.versionCode))
        // #820 the commit this build baked into its own versionName.
        Kv("Built from", BuiltFrom.short(BuiltFrom.shaFromVersionName(d.versionName)))
        Kv("First installed", ts(d.firstInstallAtMs))
        Kv("Last updated", ts(d.lastUpdateAtMs))
        Kv("APK size", FleetIdentity.size(d.bytes))
        Kv("sha256", d.sha256.ifBlank { "—" })
        Kv("Signing cert", d.signingCertSha256?.take(16)?.plus("…") ?: "—")
        Kv("Installer", d.installerPackage ?: "—")
        Kv("ABI", d.abis.joinToString().ifBlank { "—" })
        Kv("minSdk / targetSdk", "${d.minSdk} / ${d.targetSdk}")
    }

    // ── AVAILABLE ────────────────────────────────────────────────────────

    @Composable
    private fun AvailableCard(
        app: Fleet.App, state: Fleet.State?, installed: Fleet.InstalledDetails?, release: Release?, verify: Verify?,
        onVerifyExact: () -> Unit,
    ) = Card {
        BlockTitle("Available")
        if (state == null) { Kv("Status", "not checked yet"); return@Card }
        // Smart-cast to non-null Fleet.State from here on (the null branch above
        // already returned) — every read below is [state].
        when (state) {
            is Fleet.State.Installed -> {
                Kv("Status", "up to date — nothing newer offered")
                return@Card
            }
            is Fleet.State.Blocked -> { Kv("Status", "blocked — not installable"); return@Card }
            is Fleet.State.Error -> { Kv("Status", "check failed: ${state.message}"); return@Card }
            else -> {}
        }
        val source = (state as? Fleet.State.UpdateAvailable)?.source
            ?: if (app.releaseUrl.isNotBlank()) "release" else "ghcr"
        Kv("Served by", if (source == "release") "GitHub release" else "GHCR (OCI registry)")
        when (state) {
            is Fleet.State.UpdateAvailable -> Kv("Remote digest", "sha256:${state.remoteDigest12}…")
            is Fleet.State.Missing -> Kv("Status", "not installed — available to install")
            else -> {}
        }
        Kv("Size", FleetIdentity.size(state.bytes))
        val asset = release?.asset
        if (asset != null) {
            Kv("Published", if (asset.publishedAtMillis > 0) ts(asset.publishedAtMillis) else "—")
            Kv("Release URL", app.abiReleaseUrl)
        } else if (source == "ghcr") {
            Kv("GHCR page", app.ghcrPage.ifBlank { "—" })
        }
        Kv("sha256", release?.sha256 ?: "— (not sidecar-published; digest above is authoritative)")
        if (verify is Verify.Got) Kv("Available versionCode", "${verify.versionCode}")
        else Kv("Available versionCode", "unknown until fetched — see below")

        Action("Check exact version", "fetch + verify, no install", "$TAG:verify") { onVerifyExact() }
        when (verify) {
            null -> {}
            Verify.Fetching -> Text("Fetching…", color = cDim.ink(), fontSize = StoreDensity.T_META.sp,
                modifier = Modifier.padding(top = sdp(StoreDensity.S6)).testTag("$TAG:verify-status"))
            is Verify.Failed -> Text("Could not read the candidate's manifest" + (verify.why?.let { ": $it" } ?: ""),
                color = cBad.ink(), fontSize = StoreDensity.T_META.sp,
                modifier = Modifier.padding(top = sdp(StoreDensity.S6)).testTag("$TAG:verify-status"))
            is Verify.Got -> Drift(verify.versionCode, installed?.versionCode)
        }
        // #820 AVAILABLE's built-from, from the release .source sidecar.
        if (release != null) {
            val inst = BuiltFrom.shaFromVersionName(installed?.versionName)
            val commit = release.commit
            val tag = when {
                commit == null -> ""
                BuiltFrom.same(inst, commit) -> " (same as installed)"
                inst != null -> " (installed: ${BuiltFrom.short(inst)})"
                else -> ""
            }
            val url = BuiltFrom.commitUrl(app.releaseUrl, commit)
            Kv("Available built from", BuiltFrom.short(commit) + tag + (url?.let { "\n$it" } ?: ""))
        }
    }

    /** The one place this sheet compares two versionCodes — by asking
     *  [VersionOrder], never by subtracting them itself. */
    @Composable
    private fun Drift(candidateCode: Long, installedCode: Long?) {
        val order = VersionOrder.compare(candidateCode, installedCode)
        val (glyph, color, msg) = when (order) {
            VersionOrder.Order.OLDER -> Triple("⚠ DOWNGRADE", cBad,
                "available versionCode $candidateCode is OLDER than installed $installedCode. " +
                "Installing will ask you to confirm — refusing is the default.")
            VersionOrder.Order.NEWER -> Triple("⬆ update", cUpd,
                "available versionCode $candidateCode is newer than installed ${installedCode ?: "—"}.")
            VersionOrder.Order.SAME -> Triple("= same", cDim,
                "available versionCode $candidateCode matches what is installed — reinstalling repairs it.")
            VersionOrder.Order.UNKNOWN -> Triple("? unknown", cDim,
                "cannot compare (installed versionCode is unknown).")
        }
        Text("$glyph — $msg", color = color.ink(), fontSize = StoreDensity.T_META.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = sdp(StoreDensity.S6)).testTag("$TAG:drift"))
    }

    // ── small helpers (this module's own flat/dark style; ContainerSheet
    //    lives in the app module and libs:appstore may not depend on it) ────

    private const val SHEET_BG = 0xFF140E1F.toInt()

    @Composable
    private fun Header(app: Fleet.App) {
        Column(Modifier.padding(start = sdp(StoreDensity.GLYPH), top = sdp(StoreDensity.S12), end = sdp(StoreDensity.GLYPH), bottom = sdp(StoreDensity.S8))) {
            Text(app.label, color = cText.ink(), fontSize = StoreDensity.T_HEAD.sp, fontWeight = FontWeight.Bold)
            Text(app.pkg, color = cDim.ink(), fontSize = StoreDensity.T_META.sp)
        }
    }

    @Composable
    private fun Card(content: @Composable ColumnScope.() -> Unit) {
        val shape = RoundedCornerShape(sdp(StoreDensity.S12))
        Column(
            Modifier.padding(start = sdp(StoreDensity.S12), top = sdp(StoreDensity.S8), end = sdp(StoreDensity.S12))
                .fillMaxWidth().clip(shape).background(Ink.CARD.ink())
                .border(sdp(StoreDensity.S1), 0xFF322A44.toInt().ink(), shape)
                .padding(start = sdp(StoreDensity.S12), top = sdp(StoreDensity.S8), end = sdp(StoreDensity.S12), bottom = sdp(StoreDensity.S12)),
            content = content,
        )
    }

    @Composable
    private fun BlockTitle(text: String) {
        Text(text, color = cLink.ink(), fontSize = StoreDensity.T_BODY.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = sdp(StoreDensity.S4)))
    }

    @Composable
    private fun Kv(k: String, v: String) {
        Row(Modifier.fillMaxWidth().padding(top = sdp(StoreDensity.S4))) {
            Text(k, color = cDim.ink(), fontSize = StoreDensity.T_META.sp, modifier = Modifier.weight(1f))
            SelectionContainer(Modifier.weight(1.6f)) {
                Text(v, color = cText.ink(), fontSize = StoreDensity.T_META.sp, textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }

    @Composable
    private fun Action(label: String, detail: String, tag: String, onTap: () -> Unit) {
        Row(Modifier.fillMaxWidth().testTag(tag).tap(onTap).padding(top = sdp(StoreDensity.S8), bottom = sdp(StoreDensity.S4)),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = cLink.ink(), fontSize = StoreDensity.T_BODY.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(detail, color = cDim.ink(), fontSize = StoreDensity.T_CAPTION.sp, textAlign = TextAlign.End)
        }
    }

    @Composable
    private fun Note(text: String, modifier: Modifier = Modifier) {
        Text(text, color = cDim.ink(), fontSize = StoreDensity.T_CAPTION.sp, lineHeight = (StoreDensity.T_CAPTION * 1.3f).sp,
            modifier = modifier.padding(horizontal = sdp(StoreDensity.GLYPH), vertical = sdp(StoreDensity.S12)))
    }

    // The size format used to live here too, a second copy of the row's own
    // (#631). Both size rows above now ask FleetIdentity, which is also the one
    // place that decides what 0 bytes means — not a size, so it says so.

    private fun ts(ms: Long): String =
        if (ms <= 0L) "—"
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))
}
