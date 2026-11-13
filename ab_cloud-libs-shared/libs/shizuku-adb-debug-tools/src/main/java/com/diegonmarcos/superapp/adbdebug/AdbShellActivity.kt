package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView

/**
 * The ADB Shell page as its own screen. Declared once, in this module's manifest, so EVERY app that links
 * the module has it; every status chip (the Store bar, Access > Android Perms, the Permissions page,
 * Account's Perms page) opens it with [AdbShellLink.open] instead of carrying channel controls of its own.
 * It hosts the same [AdbShellScreen] the in-app pages draw.
 */
class AdbShellActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply { setContent { AdbShellScreen() } })
    }
}

/**
 * An activity that hosts the ADB Shell page INSIDE its own frame (Cloud Store: Configs > ADB Shell; SuperApp: Configs >
 * Network > ADB Shell). [openAdbShellInFrame] navigates there and returns true; false = this host cannot right now.
 */
interface AdbShellHost {
    fun openAdbShellInFrame(): Boolean
}

/**
 * "Open the ADB Shell page": the one call every chip makes. An app that hosts the page in its own frame
 * ([AdbShellHost]) opens THAT page; only an app with no such page (Termux, Nix-on-Droid) gets the full-screen
 * [AdbShellActivity].
 */
object AdbShellLink {
    /** #1682 P3 step 1: the host app's in-frame opener (installed once by its Application, like AdbReadiness.hostOpener): it opens the
     *  page inside the host's own frame and returns true, or false when [ctx] is not its host. Replaces the host Activity having to
     *  implement [AdbShellHost]; the interface still works for an app that has no Application hook. */
    @Volatile var hostOpener: ((Context) -> Boolean)? = null

    /** #1442 A chip's tap: [ControlStatus.Action.OPEN_SETTINGS] lands on the Wireless debugging switch
     *  (falls back to the page); every other action (pair, reconnect, status) is the page. */
    fun open(ctx: Context, action: ControlStatus.Action?) {
        if (action == ControlStatus.Action.OPEN_SETTINGS) {
            val i = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) return
        }
        open(ctx)
    }

    fun open(ctx: Context) {
        if (runCatching { hostOpener?.invoke(ctx) == true }.getOrDefault(false)) return
        var c: Context? = ctx
        while (c is android.content.ContextWrapper) {
            val host = c as? AdbShellHost
            if (host != null && runCatching { host.openAdbShellInFrame() }.getOrDefault(false)) return
            c = c.baseContext
        }
        // #1682 P4 this lib is compiled only into the ADB OWNER: its own app's page is THE ADB Shell page, so open it through the same launcher
        // deep-link extra the broker's ShellArmActivity uses (the Webserver's MainActivity turns it into its ADB Shell section); the bare lib
        // screen only when that start is refused.
        val home = Intent().setClassName(ctx.packageName, "${ctx.packageName}.MainActivity")
            .putExtra("shortcut_action", "page:config/adb-shell")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (runCatching { ctx.startActivity(home) }.isSuccess) return
        val i = Intent(ctx, AdbShellActivity::class.java)
        if (ctx !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }
}
