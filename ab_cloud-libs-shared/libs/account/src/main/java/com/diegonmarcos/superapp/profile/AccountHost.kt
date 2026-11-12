package com.diegonmarcos.superapp.profile

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.diegonmarcos.cloudlib.auth.VaultConnect
import com.diegonmarcos.cloudlib.auth.VaultFile
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * #867 What Account needs from whatever app is hosting it.
 *
 * Account is a library now (libs:account), hosted by cloud-superapp and by
 * cloud-account, so it cannot reach the launcher's own classes: the theme
 * palette, the tab-pill chrome, the icon table, the tile router, haptics and
 * the mesh tunnel's prefs all live in the SuperApp's module. The host supplies
 * them once, in Application.onCreate. Every default degrades to a plain but
 * working screen, never a crash, so a host that sets nothing still shows Account.
 *
 * Same shape as libs:appstore's AppStoreHost: plain fields, no interface to implement.
 */
object AccountHost {

    /** The host's theme (SuperApp: LauncherPalette.kit). Default: a neutral dark set. */
    @Volatile var palette: (Context) -> KitPalette = { DEFAULT_PALETTE }

    /** Drawable for a declared icon name, 0 when the host has none (SuperApp: Sections.iconResFor). */
    @Volatile var iconFor: (Context, String) -> Int = { _, _ -> 0 }

    /**
     * Opens a launcher route (`page:…`, `section:…`, `extapp:…`) that Account links
     * to. False when the host cannot route it, and Account then says so rather than
     * doing nothing. SuperApp: its TileClickListener.
     */
    @Volatile var route: (Activity, String) -> Boolean = { _, _ -> false }

    /** A short haptic tick on a tap (SuperApp: Haptics.tap). */
    @Volatile var tap: (View) -> Unit = {}

    /**
     * THE classifier for a picked or pasted config (ImportConfigsFragment.classify, in this lib since #1693;
     * which reads the import schema it bakes). The default knows the declared sign-in schema
     * versions and no paste-shape blob sections.
     */
    // #1693 Connect ▸ Import moved into this lib: its classifier (the paste schema's sections) is the default.
    @Volatile var classify: (String) -> VaultFile.Verdict =
        { text -> com.diegonmarcos.superapp.settings.ImportConfigsFragment.classify(text) }

    /**
     * Why a file is NOT the decrypted vault export, in the host's own sentences; null for the
     * export itself (ImportConfigsFragment.refusal, in this lib since #1693).
     */
    @Volatile var refusal: (Context, VaultFile.Verdict) -> String? =
        { c, v -> com.diegonmarcos.superapp.settings.ImportConfigsFragment.refusal(c, v) }

    /**
     * The mesh tunnel the Fleet tab reads and configures. It lives in the SuperApp
     * (libs:net and its WireGuard prefs); a host without one leaves this null and
     * the cockpit reports the tunnel as not available in this app.
     */
    @Volatile var mesh: Mesh? = null

    /** The mesh tunnel as Account sees it. */
    interface Mesh {
        /** This device's address on the mesh, "" when none is configured. */
        fun interfaceAddress(ctx: Context): String
        /** What the tunnel is right now, for the cockpit's comparison column. */
        fun state(ctx: Context): VaultCockpit.TunnelState
        /** Applies the mesh profile [conf] named [name]; returns the one-line report. */
        fun apply(ctx: Context, name: String, conf: String): String
        /** The public half of the key THIS phone holds, "" when it holds none. */
        fun publicKey(ctx: Context): String = ""
        /**
         * #573 Stores EVERY profile of [profiles] (name → wg-quick text) as the device's
         * selectable set, makes [active] the one tunnel, stores [privateKey] as the device
         * key when given (null = keep the phone's own), brings the tunnel up through the
         * engine and reports its handshake. One report, ✓ or ✗ per line.
         */
        fun applyAll(ctx: Context, profiles: Map<String, String>, active: String, privateKey: String?): String =
            "✗ this app's mesh cannot store a profile set"
        /** The live tunnel in one line: up/down, active profile, last handshake — never a key. */
        fun status(ctx: Context): String = ""
    }

    /**
     * #867 Whether an empty imported-configs blob reads through to the Cloud Account app. SuperApp
     * turns it on; Cloud Account is the owner and keeps the default (off).
     */
    @Volatile var readThrough: Boolean = false

    /**
     * Cloud Account redesign task 3: the host's declared navigation (build.json::ui), as
     * `[{id, label, pages:[{id, label}]}]`, for the debug API's `tabs` op. Null when the host
     * declares none (SuperApp hosts no Account island; the op then answers the legacy strip).
     */
    @Volatile var nav: (() -> org.json.JSONArray)? = null

    /** The host re-arms its auto-backup job after Settings changed `backup.auto` (Cloud Account: WorkManager). */
    @Volatile var autoBackupChanged: (Context) -> Unit = {}

    /**
     * #1093 THE fleet endpoint declaration (the host's build.json::fleet_endpoints as JSON text): every fleet service
     * Cloud Account talks to, with its mesh and public endpoint, taken from the service's fleet declaration. The host's
     * own transport registers it ([FleetEndpointsRuntime]), Connect > Status shows it, and the accountdata provider
     * publishes it to the other apps. "" declares nothing (the SuperApp host).
     */
    @Volatile var fleetEndpointsDecl: () -> String = { "" }

    /**
     * #1090 The mail services of that declaration (those with `kind: mail`), from which [MailEndpointsPublisher] derives
     * each fleet mail account's mesh and public URL. Derived, never declared a second time.
     */
    @Volatile var mailEndpointsDecl: () -> String = { com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract.declOfKind(fleetEndpointsDecl(), "mail") }

    val DEFAULT_PALETTE: KitPalette = KitPalette.fromArgb(
        surface = 0xFF15161A.toInt(), surfaceSelected = 0xFF23252B.toInt(),
        textPrimary = 0xFFE6E6E6.toInt(), textSecondary = 0xFF9AA0A6.toInt(),
        accent = 0xFF7AA2F7.toInt(), hairline = 0xFF2A2C33.toInt(), tileInk = 0xFF000000.toInt(),
        // The three state tokens (cloud-account-ui spec 0.3): pills, banners and stat tiles only.
        ok = 0xFF7FC98F.toInt(), warn = 0xFFE2B85C.toInt(), bad = 0xFFE5737A.toInt(),
    )
}

/** The host palette as ARGB ints, for Account's View-based cockpits. */
class AccountColors private constructor(p: KitPalette) {
    val surface = p.surface.toArgb()
    val surfaceSelected = p.surfaceSelected.toArgb()
    val textPrimary = p.textPrimary.toArgb()
    val textSecondary = p.textSecondary.toArgb()
    val accent = p.accent.toArgb()
    val hairline = p.hairline.toArgb()
    val tileInk = p.tileInk.toArgb()

    companion object {
        fun of(ctx: Context) = AccountColors(AccountHost.palette(ctx))
    }
}
