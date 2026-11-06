package com.termux.app;

import android.content.Context;

import com.termux.BuildConfig;
import com.termux.cloud.MeshAddrs;
import com.termux.cloud.MeshSshd;
import com.termux.shared.logger.Logger;

/** BOOT_COMPLETED: bring the terminal up as a foreground service and start the mesh sshd. */
public final class MeshSshdBoot {
    private static final String LOG_TAG = "MeshSshdBoot";
    private MeshSshdBoot() {}

    public static void onBoot(Context context) {
        if (!BuildConfig.CLOUD_MESH_SSHD_ON_BOOT) return;
        final Context app = context.getApplicationContext();
        new Thread(() -> start(app, BuildConfig.CLOUD_MESH_ONLY_DEFAULT), "mesh-sshd-boot").start();
    }

    /**
     * The one start path (boot, and MeshSshdService when a fleet app asks). Blocking: call it off the main thread.
     * [meshOnly] ON binds the WireGuard mesh addresses plus loopback, never the wildcard; with no mesh address it FAILS CLOSED:
     * nothing starts and a running sshd is ended. OFF binds build.json::mesh_sshd.bind (the wildcard).
     */
    static void start(Context app, boolean meshOnly) {
        String why = TerminalDebugApi.prepare(app); // bootstrap + startForegroundService(TermuxService)
        if (why != null) { Logger.logError(LOG_TAG, "terminal not ready: " + why); return; }
        String[] binds = MeshAddrs.binds(meshOnly,
            meshOnly ? MeshAddrs.resolve(BuildConfig.CLOUD_MESH_IFACES, BuildConfig.CLOUD_MESH_CIDRS.split(",")) : null,
            BuildConfig.CLOUD_MESH_LOOPBACK.split(","), BuildConfig.CLOUD_MESH_SSHD_BIND.split(","));
        if (binds == null) {
            Logger.logError(LOG_TAG, "mesh-only is ON and the mesh has no address: failed closed, no sshd");
            stop(app);
            return;
        }
        try {
            String script = MeshSshd.script(BuildConfig.CLOUD_MESH_SSHD_PORT, binds);
            Logger.logInfo(LOG_TAG, TerminalDebugApi.run(app, "sh -s", script, 300_000).toString());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "mesh sshd did not start", e);
        }
    }

    /** Ends the listener {@link #start} brought up (its own pidfile only); the terminal service is left as it is. Blocking. */
    static void stop(Context app) {
        try {
            Logger.logInfo(LOG_TAG, TerminalDebugApi.run(app, "sh -s", MeshSshd.stopScript(), 60_000).toString());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "mesh sshd did not stop", e);
        }
    }
}
