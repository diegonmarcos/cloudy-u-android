package com.termux.app;

import android.app.Application;

import com.diegonmarcos.superapp.shellrish.ShellRish;
import com.termux.cloud.AgentAuth;
import com.termux.cloud.CloudRootfs;
import com.termux.cloud.CloudTermuxProperties;
import com.termux.shared.crash.TermuxCrashUtils;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.logger.Logger;


public class TermuxApplication extends Application {
    public void onCreate() {
        super.onCreate();

        // #620 — write allow-external-apps=true into ~/.termux/termux.properties on
        // every launch and every boot, before anything in this process reads that
        // file. Without it RunCommandService refuses the boot runner's intent, and
        // nothing in this app ever wrote the property (it only read it).
        CloudTermuxProperties.ensureAllowExternalApps();

        // #790 the agent CLIs' credentials from the Account (the agent-auth store a FleetConfig
        // import fills and then restarts this app) into the file every login sources.
        AgentAuth.provision(this);

        // #1512 `rish` over the SuperApp's shell broker (libs:shell-client): start the in-process loopback relay
        // and export `rish` + its env (relay URL, random token) into the rootfs stage dir, so a shell INSIDE proot
        // runs allowlisted commands at adb-shell privilege through the broker, under this terminal's own entry.
        ShellRish.export(this, CloudRootfs.loginDir());

        // Set crash handler for the app
        TermuxCrashUtils.setCrashHandler(this);

        // Set log level for the app
        setLogLevel();

        // #747 /api/terminal/exec and /api/terminal/selftest on the fleet debug API.
        TerminalDebugApi.register(this);
    }

    private void setLogLevel() {
        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(getApplicationContext());
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
        Logger.logDebug("Starting Application");
    }
}

