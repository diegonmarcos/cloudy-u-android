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

        // #1708 the links enter.sh follows to the proot and loader in this APK's native library
        // directory (the one place an app targeting SDK 29+ may exec from); the directory moves
        // with every update, so they are re-pointed on every process start.
        CloudRootfs.linkNative(this);

        // #1708 targetSdk 35 enforces edge-to-edge; the terminal's own screens keep the window
        // they were laid out for (res/values-v35/edge_to_edge.xml).
        keepWindowInsideSystemBars();

        // Set crash handler for the app
        TermuxCrashUtils.setCrashHandler(this);

        // Set log level for the app
        setLogLevel();

        // #747 /api/terminal/exec and /api/terminal/selftest on the fleet debug API.
        TerminalDebugApi.register(this);
    }

    /**
     * Applies the edge-to-edge opt-out style to every activity of this app's own code, before
     * its content view exists (the only moment a window attribute can still be set). The style
     * is empty below API 35. Activities of the shared fleet libraries are left as they are.
     */
    private void keepWindowInsideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT < 35) return;
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityPreCreated(android.app.Activity activity, android.os.Bundle savedInstanceState) {
                try {
                    if (activity.getClass().getName().startsWith("com.termux."))
                        activity.getTheme().applyStyle(com.termux.R.style.CloudEdgeToEdgeOptOut, false);
                } catch (RuntimeException e) {
                    Logger.logStackTraceWithMessage("TermuxApplication", "Edge-to-edge opt-out not applied", e);
                }
            }
            @Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle savedInstanceState) { }
            @Override public void onActivityStarted(android.app.Activity activity) { }
            @Override public void onActivityResumed(android.app.Activity activity) { }
            @Override public void onActivityPaused(android.app.Activity activity) { }
            @Override public void onActivityStopped(android.app.Activity activity) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle outState) { }
            @Override public void onActivityDestroyed(android.app.Activity activity) { }
        });
    }

    private void setLogLevel() {
        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(getApplicationContext());
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
        Logger.logDebug("Starting Application");
    }
}

