package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.App
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.appstore.ExternalInstall
import com.diegonmarcos.superapp.appstore.FleetInstall
import com.diegonmarcos.superapp.appstore.PhoneAppActions
import com.diegonmarcos.superapp.appstore.SourceResolver
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.extinstall.ComposeAsk
import com.diegonmarcos.superapp.ui.KitDialog

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.concurrent.thread

/**
 * Stock-Android-style "long-press an app icon" context menu —
 * mirrors what Pixel Launcher / One UI Home / Niagara show when
 * the user holds on an app tile:
 *
 *   • App shortcuts (dynamic + static, as published by the app via
 *     LauncherApps.getShortcuts — chat apps surface "New message",
 *     calendar shows "New event", browsers show "New tab" + recent
 *     tabs, etc.)
 *   • App info       — jumps to Settings → Apps → ThisApp.
 *   • Uninstall      — PackageInstaller.uninstall with the package.
 *   • Update         — the ONE path per kind Store ▸ Phone Apps runs: a Cloud
 *                      fleet app through FleetInstall, any other app down its
 *                      declared source ladder (ExternalInstall, #571) (#572).
 *   • URLs           — public + private endpoints (our apps) or the website
 *                      (phone apps), all derived by [AppUrls] from build.json
 *                      declarations; no URL is written in this file (#572).
 *
 * Sections: Actions (the app's shortcuts) · Configs · URLs. A section with no
 * rows is not drawn.
 *
 * The menu is a Compose dialog (KitDialog: a dimmed full-window backdrop)
 * with a dark-glass rounded container, mirroring the visual language
 * of BatteryEstimatePopup / SystemInfoPopup so the launcher feels
 * consistent across long-press surfaces. Tap outside → dismiss.
 *
 * Used by BOTH PhoneAppsFragment (Home Apps / Phone tab, expanded
 * folder dialog) and SuitePhoneAppsFragment (Suite / Phone grid +
 * folder modal). Single helper means the menu shape is identical
 * regardless of where the long-press happened.
 *
 * Permissions:
 *   • LauncherApps.getShortcuts(...) needs the calling app to be
 *     the default launcher OR to have been granted shortcuts
 *     access. The SuperApp ships with the HOME intent-filter
 *     (AndroidManifest.xml) so users who pick it as their home
 *     get shortcut access for free. Non-default-launcher install
 *     → the shortcuts query silently returns null and only the
 *     App info / Uninstall rows render. No crash, no warning.
 */
object AppLongPressMenu {

    /** One row of the menu: its label, whether it is a shortcut (lavender, indented), whether it is dimmed, what it does. */
    internal class Row(val label: String, val shortcut: Boolean = false, val dim: Float = 1f, val onClick: () -> Unit)

    /** One section of the menu; a section with no rows is not drawn. */
    internal class Section(val title: String, val rows: List<Row>)

    /** Build + show the long-press menu for the given package. The
     *  caller is typically the long-press handler on a tile; we
     *  resolve user + componentName ourselves so the call site stays
     *  one-liner. */
    fun show(ctx: Context, pkg: String) {
        val act = ComposeAsk.activityOf(ctx) ?: return
        KitDialog.show(act) { close -> Menu(ctx, pkg, close) }
    }

    /** The menu itself, split from [show] so it can be drawn without a window. */
    @Composable
    internal fun Menu(ctx: Context, pkg: String, close: () -> Unit) {
        val appLabel = androidx.compose.runtime.remember(pkg) {
            runCatching {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
        }
        val sections = androidx.compose.runtime.remember(pkg) { sectionsFor(ctx, pkg, appLabel, close) }
        Column(
            Modifier.width(280.dp).background(Color(0xEE111111), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(16.dp)).padding(16.dp).testTag("longpress:menu"),
        ) {
            // Header — app label.
            Text(appLabel, color = Color(0xFFE9D8FD), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp))
            sections.forEachIndexed { i, sec ->
                if (i > 0) Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Color(0x22FFFFFF))) {}
                Text(sec.title.uppercase(), color = Color(0x99E9D8FD), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp))
                for (r in sec.rows) Text(r.label, fontSize = 14.sp, color = if (r.shortcut) Color(0xFFB794F4) else Color.White,
                    modifier = Modifier.fillMaxWidth().alpha(r.dim).clickable(onClick = r.onClick)
                        .padding(start = if (r.shortcut) 20.dp else 12.dp, top = if (r.shortcut) 10.dp else 12.dp,
                            end = 12.dp, bottom = if (r.shortcut) 10.dp else 12.dp).testTag("longpress:row:${r.label}"))
            }
        }
    }

    internal fun sectionsFor(ctx: Context, pkg: String, appLabel: String, close: () -> Unit): List<Section> {
        val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val me = Process.myUserHandle()
        val out = mutableListOf<Section>()

        // App shortcuts (dynamic + manifest). API 25+. Fails silently
        // when not the default launcher — see KDoc above.
        val shortcuts = queryShortcuts(launcher, pkg, me)
        if (shortcuts.isNotEmpty()) out += Section("Actions", shortcuts.map { s ->
            Row(s.shortLabel?.toString() ?: s.id, shortcut = true) {
                close()
                runCatching { launcher?.startShortcut(s, null, null) }
            }
        })

        val configs = mutableListOf<Row>()
        // App info — Settings → Apps → ThisApp.
        configs += Row("App info") {
            close()
            // #1515 the App info intent is built in libs:perms (PermsPages), the one place that opens settings pages.
            runCatching { com.diegonmarcos.superapp.perms.PermsPages.openAppInfo(ctx, pkg) }
        }

        // Uninstall — modern PackageInstaller.uninstall(): reliably raises the
        // system "Uninstall this app?" dialog across OEMs (ACTION_DELETE was
        // silently no-op on some devices). The shared PackageInstallerReceiver
        // forwards STATUS_PENDING_USER_ACTION → the confirm dialog and reports
        // the result. Needs REQUEST_DELETE_PACKAGES (declared in the manifest).
        configs += Row("Uninstall") {
            close()
            requestUninstall(ctx, pkg)
        }

        // Update — the ONE path per kind, the same one Store ▸ Phone Apps runs:
        // a fleet app through FleetInstall, any other app down the ladder its
        // install-source map declares (#571 SourceResolver → ExternalInstall).
        // A Play-only app has no direct rung: the row is dimmed and the tap
        // says why (ExternalInstall.run returns that message).
        val resolver = runCatching { PhoneAppActions.resolver(PhoneAppActions.sources(ctx)) }.getOrNull()
        val fleetApp = PhoneAppActions.fleetByPackage(Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64))[pkg]
        val external = if (fleetApp == null && resolver != null) SourceResolver.resolve(resolver, pkg) else null
        configs += Row("Update", dim = if (fleetApp == null && (external == null || external.needsPlay)) 0.4f else 1f) {
            if (fleetApp == null && resolver == null) {
                Toast.makeText(ctx, "No update source for this app", Toast.LENGTH_LONG).show()
            } else {
                close()
                requestUpdate(ctx, fleetApp, resolver, pkg, appLabel)
            }
        }
        out += Section("Configs", configs)

        // URLs — every row derived by AppUrls from build.json declarations.
        val links = AppUrls.of(ctx, pkg)
        out += if (links.isNotEmpty()) Section("URLs", links.map { l ->
            Row("${l.label} · ${l.url.substringAfter("//")}") {
                close()
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }) else {
            // #677 not silent: say whether a declared URL is missing or the app
            // has no URL kind at all — two different messages.
            Section("URLs", listOf(Row(AppUrls.absence(ctx, pkg), dim = 0.5f) {}))
        }
        return out
    }

    /** Fire the system uninstall flow for [pkg] via PackageInstaller.uninstall.
     *  The result callback goes to the shared PackageInstallerReceiver (which
     *  launches the confirm dialog and surfaces the outcome). Wrapped so a
     *  SecurityException (permission revoked) or a protected/system package
     *  can't crash the launcher. */
    internal fun requestUninstall(ctx: Context, pkg: String) {
        runCatching {
            val installer = ctx.packageManager.packageInstaller
            val intent = Intent(ctx, com.diegonmarcos.superapp.updater.PackageInstallerReceiver::class.java)
                .setPackage(ctx.packageName)
                .putExtra(
                    com.diegonmarcos.superapp.updater.PackageInstallerReceiver.EXTRA_OP,
                    com.diegonmarcos.superapp.updater.PackageInstallerReceiver.OP_UNINSTALL,
                )
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_MUTABLE
            val pending = android.app.PendingIntent.getBroadcast(ctx, pkg.hashCode(), intent, flags)
            installer.uninstall(pkg, pending.intentSender)
        }.onFailure {
            android.widget.Toast.makeText(ctx, "Can't uninstall: ${it.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** #1736 Update [pkg] by its fleet row or its declared source ladder (the same path the long-press menu's Update runs). */
    internal fun requestUpdateOf(ctx: Context, pkg: String) {
        val resolver = runCatching { PhoneAppActions.resolver(PhoneAppActions.sources(ctx)) }.getOrNull()
        val fleetApp = PhoneAppActions.fleetByPackage(Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64))[pkg]
        if (fleetApp == null && resolver == null) { Toast.makeText(ctx, "No update source for this app", Toast.LENGTH_LONG).show(); return }
        requestUpdate(ctx, fleetApp, resolver, pkg, fleetApp?.label ?: pkg)
    }

    /** Update [pkg] off the main thread. Blocking installers return the failure
     *  message, or null on success / the user's own cancel. */
    private fun requestUpdate(ctx: Context, fleetApp: Fleet.App?, resolver: SourceResolver.Config?, pkg: String, label: String) {
        val app = ctx.applicationContext
        val ui = Handler(Looper.getMainLooper())
        Toast.makeText(app, "Updating $label…", Toast.LENGTH_SHORT).show()
        thread(name = "menu-update-$pkg") {
            // Not `fleetApp?.let { } ?: external`: a fleet install that SUCCEEDS
            // returns null, and that elvis would go on to run the external path.
            val msg = if (fleetApp != null) FleetInstall.run(app, fleetApp)
                      else resolver?.let { ExternalInstall.run(app, it, SourceResolver.resolve(it, pkg)) }
            if (msg != null) ui.post { Toast.makeText(app, "$label: $msg", Toast.LENGTH_LONG).show() }
        }
    }

    /** Query the dynamic + manifest shortcuts for the given package.
     *  Returns an empty list when the LauncherApps service isn't
     *  available OR when we don't have the privilege to query
     *  (non-default-launcher install). Never throws. */
    private fun queryShortcuts(launcher: LauncherApps?, pkg: String, me: UserHandle): List<ShortcutInfo> {
        if (launcher == null || Build.VERSION.SDK_INT < 25) return emptyList()
        return runCatching {
            val q = LauncherApps.ShortcutQuery()
                .setPackage(pkg)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                )
            launcher.getShortcuts(q, me) ?: emptyList()
        }.getOrDefault(emptyList())
    }
}
