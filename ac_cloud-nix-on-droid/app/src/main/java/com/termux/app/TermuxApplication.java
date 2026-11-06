package com.termux.app;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import com.diegonmarcos.cloudlib.sysdns.SystemDnsBridge;
import com.termux.BuildConfig;
import com.termux.cloud.AgentAuth;
import com.termux.cloud.CloudTermuxProperties;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxBootstrap;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.am.TermuxAmSocketServer;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.theme.TermuxThemeUtils;

import java.io.IOException;

public class TermuxApplication extends Application {

    private static final String LOG_TAG = "TermuxApplication";

    /** #758 held so the bridge's sockets live as long as the process. */
    private static SystemDnsBridge dnsBridge;
    /** #900 the ordered upstream reads the active network's DNS list through it. */
    private static Context dnsContext;
    /** #794 why there is no bridge here, for /api/sysdns/state; null while none was tried. */
    private static String dnsBridgeWhyNot;

    public void onCreate() {
        super.onCreate();

        Context context = getApplicationContext();

        // Set crash handler for the app
        TermuxCrashUtils.setDefaultCrashHandler(this);

        // Set log config for the app
        setLogConfig(context);

        Logger.logDebug("Starting Application");

        // #747 /api/terminal/exec and /api/terminal/selftest on the fleet debug API. First, so a
        // start that returns early below (files directory unusable) can still be debugged through it.
        TerminalDebugApi.register(this);

        // #758 bin/login binds a resolv.conf naming 127.0.0.1 over the guest's /etc/resolv.conf
        // and runs proot -p, so every shell lookup (a typed session, RunCommandService or
        // /api/terminal/exec, all of which run in this process) lands here and is answered by
        // Android's resolver, i.e. by the SuperApp's DNS menu. Before any early return below.
        dnsContext = getApplicationContext();
        startDnsBridge();

        // Set TermuxBootstrap.TERMUX_APP_PACKAGE_MANAGER and TermuxBootstrap.TERMUX_APP_PACKAGE_VARIANT
        TermuxBootstrap.setTermuxPackageManagerAndVariant(BuildConfig.TERMUX_PACKAGE_VARIANT);

        // #620 — write allow-external-apps=true into ~/.termux/termux.properties
        // BEFORE the line below caches that file. Without it RunCommandService
        // refuses the boot runner's intent on every single reboot, and nothing
        // in this app ever wrote the property (it only read it).
        CloudTermuxProperties.ensureAllowExternalApps();

        // #790 the agent CLIs' credentials from the Account (the agent-auth store a FleetConfig
        // import fills and then restarts this app) into the file every login sources.
        AgentAuth.provision(this);

        // #1512 `rish` over the SuperApp's shell broker (libs:shell-client): start the in-process loopback relay and
        // export `rish` + its env (relay URL, random token) under $PREFIX so bin/login (patched by
        // bake_default_packages.patch_bin_login_rish, mirroring the #758 DNS bind) binds them into the rootfs; a shell
        // inside proot then runs allowlisted commands at adb-shell privilege through the broker, under this terminal's entry.
        com.diegonmarcos.superapp.shellrish.ShellRish.export(
            this, new java.io.File(getFilesDir(), "usr"));

        // Init app wide SharedProperties loaded from termux.properties
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(context);

        // Init app wide shell manager
        TermuxShellManager shellManager = TermuxShellManager.init(context);

        // Set NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(properties.getNightMode());

        // Check and create termux files directory. If failed to access it like in case of secondary
        // user or external sd card installation, then don't run files directory related code
        Error error = TermuxFileUtils.isTermuxFilesDirectoryAccessible(this, true, true);
        boolean isTermuxFilesDirectoryAccessible = error == null;
        if (isTermuxFilesDirectoryAccessible) {
            Logger.logInfo(LOG_TAG, "Termux files directory is accessible");

            error = TermuxFileUtils.isAppsTermuxAppDirectoryAccessible(true, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Create apps/termux-app directory failed\n" + error);
                return;
            }

            // Setup termux-am-socket server
            TermuxAmSocketServer.setupTermuxAmSocketServer(context);
        } else {
            Logger.logErrorExtended(LOG_TAG, "Termux files directory is not accessible\n" + error);
        }

        // Init TermuxShellEnvironment constants and caches after everything has been setup including termux-am-socket server
        TermuxShellEnvironment.init(this);

        if (isTermuxFilesDirectoryAccessible) {
            TermuxShellEnvironment.writeEnvironmentToFile(this);
        }
    }

    /** #889 while the shells' port is held elsewhere, one bind attempt this often until it is ours. */
    private static final long DNS_BRIDGE_RETRY_MS = 3000;
    private static Thread dnsBridgeRebind;

    private static synchronized void startDnsBridge() {
        if (dnsBridge != null || dnsBridgeRebind != null) return;
        if (Build.VERSION.SDK_INT < 29) {
            // ponytail: no raw system resolver below Android 10 (DnsResolver is API 29); the shell
            // then has no DNS rather than a server of its own, as in the termux terminal.
            dnsBridgeWhyNot = "Android " + Build.VERSION.SDK_INT + " has no raw system resolver: shell lookups will fail";
            Logger.logError(LOG_TAG, dnsBridgeWhyNot);
            return;
        }
        if (bindDnsBridge()) return;
        Logger.logWarn(LOG_TAG, dnsBridgeWhyNot);
        // #889 the holder may be the other terminal (fine) or a process that is cached, frozen or
        // gone -- then every lookup in this shell hangs. Keep trying; own the port when it frees.
        dnsBridgeRebind = new Thread(() -> {
            while (true) {
                try { Thread.sleep(DNS_BRIDGE_RETRY_MS); } catch (InterruptedException e) { return; }
                synchronized (TermuxApplication.class) {
                    if (bindDnsBridge()) { dnsBridgeRebind = null; return; }
                }
            }
        }, "sysdns-rebind");
        dnsBridgeRebind.setDaemon(true);
        dnsBridgeRebind.start();
    }

    /** One bind on the shells' port: true when this terminal now answers it. */
    private static boolean bindDnsBridge() {
        try {
            dnsBridge = new SystemDnsBridge(BuildConfig.CLOUD_DNS_BRIDGE_PORT, SystemDnsBridge.ordered(dnsContext),
                line -> Logger.logInfo(LOG_TAG, line));
            dnsBridgeWhyNot = null;
            return true;
        } catch (IOException e) {
            dnsBridgeWhyNot = "127.0.0.1:" + BuildConfig.CLOUD_DNS_BRIDGE_PORT
                + " is taken (" + e.getMessage() + "): another fleet terminal answers this shell's DNS;"
                + " retrying every " + (DNS_BRIDGE_RETRY_MS / 1000) + " s to take it over when it frees (#889)";
            return false;
        }
    }

    /** #794 the body of /api/sysdns/state: this terminal's bridge, or why it has none. */
    static synchronized String dnsBridgeState() {
        if (dnsBridge != null) return dnsBridge.stateJson();
        return SystemDnsBridge.notListeningJson(BuildConfig.CLOUD_DNS_BRIDGE_PORT,
            dnsBridgeWhyNot != null ? dnsBridgeWhyNot : "not started yet: the app's start starts it");
    }

    public static void setLogConfig(Context context) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_APP_NAME);

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
    }

}
